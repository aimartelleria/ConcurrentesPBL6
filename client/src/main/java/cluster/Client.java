package cluster;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;

import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;

/**
 * Servicio puente Kafka -> RMI -> Cassandra (entregable desplegable).
 *
 * Flujo por mensaje:
 *   1. Consume telemetría Avro de Kafka deserializada vía Confluent Schema Registry.
 *   2. Calcula el StressScore en el clúster Java por RMI (round-robin + failover).
 *   3. Persiste la fila enriquecida en Cassandra (webhardmon.mediciones).
 *
 * Garantías: ack manual (commit tras procesar el lote) + dead-letter topic para
 * los mensajes no procesables (semántica at-least-once).
 *
 * Configuración por variables de entorno (sin valores hardcodeados de red):
 *   KAFKA_BOOTSTRAP_SERVERS   (def. localhost:9094)
 *   SCHEMA_REGISTRY_URL       (def. http://localhost:8085)
 *   TELEMETRY_TOPIC           (def. telemetry)
 *   CONSUMER_GROUP            (def. telemetry_java_consumers)
 *   CASSANDRA_CONTACT_POINTS  (def. localhost:9042  — coma-separado host:port)
 *   CASSANDRA_LOCAL_DC        (def. datacenter1)
 *   CASSANDRA_KEYSPACE        (def. webhardmon)
 * Los seeds RMI se pasan como argumentos: Client <seedHost:Port> [seedHost:Port ...]
 */
public class Client {

    public static final String SERVICE_NAME = "ComputeNode";

    private final List<Endpoint>      seeds;
    private volatile List<Endpoint>   members = List.of();
    private final AtomicInteger       cursor  = new AtomicInteger();

    public Client(List<Endpoint> seeds) {
        if (seeds.isEmpty()) throw new IllegalArgumentException("at least one seed required");
        this.seeds = List.copyOf(seeds);
    }

    /** Pulls the current member list from the first reachable seed/known-member. */
    private synchronized void refresh() throws Exception {
        List<Endpoint> tryOrder = new ArrayList<>(members);
        for (Endpoint s : seeds) if (!tryOrder.contains(s)) tryOrder.add(s);

        Exception last = null;
        for (Endpoint e : tryOrder) {
            try {
                Registry reg = LocateRegistry.getRegistry(e.host, e.port);
                ComputeService stub = (ComputeService) reg.lookup(SERVICE_NAME);
                Set<Endpoint> view = stub.gossip(null, new HashSet<>());
                List<Endpoint> sorted = new ArrayList<>(view);
                sorted.sort((a, b) -> a.addr().compareTo(b.addr()));
                this.members = sorted;
                System.out.println("Refreshed cluster view via " + e + ": " + sorted);
                return;
            } catch (Exception ex) {
                last = ex;
                System.err.println("  bootstrap via " + e + " failed: " + ex.getClass().getSimpleName());
            }
        }
        throw new RuntimeException("Could not contact any seed or known member", last);
    }

    /** Ejecuta una Task en el clúster con failover entre nodos (round-robin). */
    public <T> T executeTask(Task<T> task) throws Exception {
        if (members.isEmpty()) refresh();

        for (int attempt = 0; attempt < 2; attempt++) {
            List<Endpoint> snap = members;
            if (snap.isEmpty()) { refresh(); continue; }

            int start = Math.floorMod(cursor.getAndIncrement(), snap.size());
            for (int i = 0; i < snap.size(); i++) {
                Endpoint e = snap.get((start + i) % snap.size());
                try {
                    Registry reg = LocateRegistry.getRegistry(e.host, e.port);
                    ComputeService stub = (ComputeService) reg.lookup(SERVICE_NAME);
                    return stub.executeTask(task);
                } catch (Exception ex) {
                    System.err.println("   " + e + " failed (" + ex.getClass().getSimpleName() + "), trying next...");
                }
            }
            System.err.println("   all cached members failed, refreshing view");
            refresh();
        }
        throw new RuntimeException("All members failed even after a refresh");
    }

    // ------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: Client <seedHost:Port> [seedHost:Port ...]");
            System.exit(1);
        }
        List<Endpoint> seeds = new ArrayList<>();
        for (String s : args) seeds.add(Endpoint.parse(s));

        Client client = new Client(seeds);
        try { client.refresh(); } catch (Exception ignore) {}

        // --- Configuración por entorno ---
        String kafkaBrokers = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094");
        String srUrl        = env("SCHEMA_REGISTRY_URL",     "http://localhost:8081");
        String topic        = env("TELEMETRY_TOPIC",         "telemetry");
        String group        = env("CONSUMER_GROUP",          "telemetry_java_consumers");
        String cassPoints   = env("CASSANDRA_CONTACT_POINTS","localhost:9042");
        String cassDc       = env("CASSANDRA_LOCAL_DC",      "datacenter1");
        String cassKeyspace = env("CASSANDRA_KEYSPACE",      "webhardmon");
        String hdfsUri      = env("HDFS_URI",                "");   // vacío = HDFS desactivado
        final String DLQ_TOPIC = topic + ".DLT";

        // --- Consumidor Kafka con deserializador Avro de Schema Registry ---
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "io.confluent.kafka.serializers.KafkaAvroDeserializer");
        props.put("schema.registry.url", srUrl);
        props.put("specific.avro.reader", "false"); // GenericRecord, no clases generadas
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        // ACK MANUAL: el offset solo se confirma tras procesar/aparcar todo el lote.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        // --- Productor de la dead-letter ---
        Properties dlqProps = new Properties();
        dlqProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokers);
        dlqProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        dlqProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArraySerializer");
        dlqProps.put(ProducerConfig.ACKS_CONFIG, "all");

        // --- Sesión Cassandra + sentencia preparada ---
        CqlSessionBuilder cb = CqlSession.builder()
                .withKeyspace(cassKeyspace)
                .withLocalDatacenter(cassDc);
        for (String hp : cassPoints.split(",")) {
            String h = hp.trim();
            if (h.isEmpty()) continue;
            int idx = h.lastIndexOf(':');
            String host = idx > 0 ? h.substring(0, idx) : h;
            int port    = idx > 0 ? Integer.parseInt(h.substring(idx + 1)) : 9042;
            cb.addContactPoint(new InetSocketAddress(host, port));
        }

        System.out.printf(" [*] Bridge iniciado. Kafka=%s SR=%s topic=%s Cassandra=%s/%s%n",
                kafkaBrokers, srUrl, topic, cassPoints, cassKeyspace);

        // Camino batch opcional: HDFS (Parquet). Vacío = desactivado.
        final HdfsParquetWriter hdfs = hdfsUri.isBlank() ? null : new HdfsParquetWriter(hdfsUri);
        if (hdfs != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(hdfs::close));
            System.out.println(" [*] HDFS batch path activo: " + hdfsUri);
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        try (CqlSession session = cb.build();
             KafkaConsumer<String, GenericRecord> consumer = new KafkaConsumer<>(props);
             KafkaProducer<String, byte[]> dlqProducer = new KafkaProducer<>(dlqProps)) {

            PreparedStatement insert = session.prepare(
                "INSERT INTO mediciones (empresa_id, nombre, ts, cpu_percent, ram_percent, " +
                "disco_percent, temperatura, bateria_percent, stress_score, ram, almacenamiento) " +
                "VALUES (:empresa_id, :nombre, :ts, :cpu_percent, :ram_percent, :disco_percent, " +
                ":temperatura, :bateria_percent, :stress_score, :ram, :almacenamiento)");

            consumer.subscribe(Collections.singletonList(topic));
            while (true) {
                ConsumerRecords<String, GenericRecord> records = consumer.poll(Duration.ofMillis(100));
                if (records.isEmpty()) continue;

                // Procesamos el lote en paralelo (Virtual Threads); ack al final.
                List<Future<Boolean>> batch = new ArrayList<>();
                for (ConsumerRecord<String, GenericRecord> record : records) {
                    batch.add(executor.submit(() -> {
                        try {
                            processRecord(client, session, insert, hdfs, record.value());
                            return true;
                        } catch (Exception e) {
                            return sendToDeadLetter(dlqProducer, DLQ_TOPIC, record, e);
                        }
                    }));
                }

                boolean batchHandled = true;
                for (Future<Boolean> f : batch) {
                    try {
                        if (!Boolean.TRUE.equals(f.get())) batchHandled = false;
                    } catch (Exception ex) {
                        batchHandled = false;
                    }
                }

                if (batchHandled) {
                    try {
                        consumer.commitSync();
                        System.out.println("     -> [ACK] Offset confirmado para el lote (" + records.count() + " msg).");
                    } catch (CommitFailedException e) {
                        System.err.println("Commit de offset falló (se reintentará el lote): " + e.getMessage());
                    }
                } else {
                    System.err.println("   ⚠️  Lote no resuelto: rebobinando para reintentar.");
                    for (TopicPartition tp : records.partitions()) {
                        long firstOffset = records.records(tp).get(0).offset();
                        consumer.seek(tp, firstOffset);
                    }
                }
            }
        }
    }

    /** Calcula StressScore por RMI y persiste en Cassandra (hot) y, opcional, HDFS (batch). */
    private static void processRecord(Client client, CqlSession session, PreparedStatement insert,
                                      HdfsParquetWriter hdfs, GenericRecord r) throws Exception {
        if (r == null) throw new IllegalArgumentException("registro Avro nulo");

        long   empresaId = num(r, "empresa_id").longValue();
        String nombre    = str(r, "nombre");
        long   tsMillis  = num(r, "ts").longValue();
        double cpu       = num(r, "cpu_percent").doubleValue();
        double ram       = num(r, "ram_percent").doubleValue();
        double disco     = num(r, "disco_percent").doubleValue();
        Double temp      = optNum(r, "temperatura");
        Double bateria   = optNum(r, "bateria_percent");
        String ramTxt    = str(r, "ram");
        String almTxt    = str(r, "almacenamiento");

        // 1. Enriquecimiento StressScore en el clúster (RMI, con failover)
        double stress = client.executeTask(new StressTask(cpu, ram, disco, temp));

        // 2. Persistencia en Cassandra (webhardmon.mediciones)
        BoundStatementBuilder b = insert.boundStatementBuilder()
                .setLong("empresa_id", empresaId)
                .setString("nombre", nombre)
                .setInstant("ts", Instant.ofEpochMilli(tsMillis))
                .setDouble("cpu_percent", cpu)
                .setDouble("ram_percent", ram)
                .setDouble("disco_percent", disco)
                .setDouble("stress_score", stress)
                .setString("ram", ramTxt)
                .setString("almacenamiento", almTxt);
        if (temp != null)    b = b.setDouble("temperatura", temp);     else b = b.setToNull("temperatura");
        if (bateria != null) b = b.setDouble("bateria_percent", bateria); else b = b.setToNull("bateria_percent");
        session.execute(b.build());

        // 3. Camino batch: Parquet en HDFS (best-effort; un fallo de HDFS NO afecta a Cassandra).
        boolean hdfsOk = false;
        if (hdfs != null) {
            try {
                hdfs.write(empresaId, nombre, tsMillis, cpu, ram, disco, temp, bateria,
                        ramTxt, almTxt, str(r, "procesador"), stress);
                hdfsOk = true;
            } catch (Exception e) {
                System.err.println("   [HDFS] write falló (Cassandra OK): " + e.getMessage());
            }
        }

        System.out.printf("   [OK] %s (empresa %d) stress=%.1f -> Cassandra%s%n",
                nombre, empresaId, stress, hdfsOk ? " + HDFS" : "");
    }

    // --- Helpers de extracción de GenericRecord ---
    private static Number num(GenericRecord r, String f) { return (Number) r.get(f); }
    private static Double optNum(GenericRecord r, String f) {
        Object v = r.get(f); return v == null ? null : ((Number) v).doubleValue();
    }
    private static String str(GenericRecord r, String f) {
        Object v = r.get(f); return v == null ? null : v.toString();
    }
    private static String env(String k, String def) {
        String v = System.getenv(k); return (v == null || v.isEmpty()) ? def : v;
    }

    /**
     * Aparca un mensaje no procesable en la dead-letter (con cabeceras de diagnóstico).
     * @return true si se escribió en la DLT; false si ni la DLT respondió (reintentar lote).
     */
    private static boolean sendToDeadLetter(KafkaProducer<String, byte[]> producer, String dlqTopic,
                                            ConsumerRecord<String, GenericRecord> original, Exception cause) {
        System.err.println("   ⚠️  Mensaje no procesable -> Dead-Letter (" + dlqTopic + "): " + cause.getMessage());
        byte[] body = original.value() == null
                ? new byte[0]
                : original.value().toString().getBytes(StandardCharsets.UTF_8);
        ProducerRecord<String, byte[]> dlqRecord = new ProducerRecord<>(dlqTopic, original.key(), body);
        dlqRecord.headers()
            .add("dlq-origin-topic",     original.topic().getBytes(StandardCharsets.UTF_8))
            .add("dlq-origin-partition", String.valueOf(original.partition()).getBytes(StandardCharsets.UTF_8))
            .add("dlq-origin-offset",    String.valueOf(original.offset()).getBytes(StandardCharsets.UTF_8))
            .add("dlq-error",            String.valueOf(cause.getMessage()).getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(dlqRecord).get();
            return true;
        } catch (Exception sendErr) {
            System.err.println("   ❌ No se pudo escribir en la Dead-Letter: " + sendErr.getMessage());
            return false;
        }
    }
}
