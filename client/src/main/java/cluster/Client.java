package cluster;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Cluster-aware client. No single point of failure on the lookup path.
 *
 *  - Configured with a list of seed addresses. As long as ONE is reachable,
 *    bootstrap succeeds; the rest are tried on failure.
 *  - Once bootstrapped, caches the full member list and round-robins.
 *  - On a call failure, tries the next member; if every cached member
 *    fails, refreshes the view from any reachable seed and tries once more.
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
        // Try last-known members first (likely warmer), then fall back to seeds.
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
                System.err.println("  bootstrap via " + e + " failed: "
                        + ex.getClass().getSimpleName());
            }
        }
        throw new RuntimeException("Could not contact any seed or known member", last);
    }

    /** Runs a executeTask() call with cross-node failover. */
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
                    System.out.println("-> dispatching task to " + e);
                    return stub.executeTask(task);
                } catch (Exception ex) {
                    System.err.println("   " + e + " failed ("
                            + ex.getClass().getSimpleName() + "), trying next...");
                }
            }
            // Every cached member failed — view is stale. Refresh and retry once.
            System.err.println("   all cached members failed, refreshing view");
            try { refresh(); } catch (Exception refreshErr) { throw refreshErr; }
        }
        throw new RuntimeException("All members failed even after a refresh");
    }

    /** Runs the Avro payload over RMI using cross-node load balancing */
    public void processTelemetry(byte[] payload) throws Exception {
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
                    System.out.println("-> dispatching telemetry to " + e);
                    stub.processTelemetry(payload);
                    return;
                } catch (Exception ex) {
                    System.err.println("   " + e + " failed ("
                            + ex.getClass().getSimpleName() + "), trying next...");
                }
            }
            System.err.println("   all cached members failed, refreshing view");
            try { refresh(); } catch (Exception refreshErr) { throw refreshErr; }
        }
        throw new RuntimeException("All members failed even after a refresh");
    }

    /**
     * Supports TLS/SSL via JVM properties:
     *   -Djavax.net.ssl.trustStore=server.keystore
     *   -Djavax.net.ssl.trustStorePassword=changeit
     * See RUN_WITH_TLS.md for setup instructions.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: Client <seedHost:Port> [seedHost:Port ...]");
            System.exit(1);
        }
        List<Endpoint> seeds = new ArrayList<>();
        for (String s : args) seeds.add(Endpoint.parse(s));

        Client client = new Client(seeds);
        try { client.refresh(); } catch (Exception ignore) {}

        // Kafka Consumer configuration (act as consumer, simulating the ingest layer)
        Properties props = new Properties();
        String kafkaBrokers = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        if (kafkaBrokers == null || kafkaBrokers.isEmpty()) {
            kafkaBrokers = "localhost:9094";
        }
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "telemetry_java_consumers");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        // ── ACK MANUAL ──────────────────────────────────────────────────────────
        // Desactivamos el commit automático de offsets. Solo confirmaremos (commitSync)
        // el avance del consumidor DESPUÉS de haber procesado el lote o de haberlo
        // aparcado en la dead-letter. Garantía "at-least-once": si la app cae antes
        // del commit, Kafka reentrega el lote en el próximo arranque.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        // ── DEAD-LETTER ─────────────────────────────────────────────────────────
        // Productor hacia el "Dead-Letter Topic": ahí enviamos los mensajes que no se
        // pueden procesar (p. ej. el clúster RMI no responde) para no bloquear ni
        // perder datos. acks=all => esperamos confirmación del broker (fiabilidad).
        final String DLQ_TOPIC = "telemetry.DLT";
        Properties dlqProps = new Properties();
        dlqProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokers);
        dlqProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        dlqProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        dlqProps.put(ProducerConfig.ACKS_CONFIG, "all");

        System.out.println(" [*] Consumer started. Waiting for Avro telemetry. To exit press CTRL+C");

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props);
             KafkaProducer<String, byte[]> dlqProducer = new KafkaProducer<>(dlqProps)) {
            consumer.subscribe(Collections.singletonList("telemetry"));
            while (true) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
                if (records.isEmpty()) continue;

                // Procesamos el lote en paralelo con Virtual Threads, pero NO confirmamos
                // el offset hasta que todas las tareas del lote hayan terminado.
                List<Future<Boolean>> batch = new ArrayList<>();
                for (ConsumerRecord<String, byte[]> record : records) {
                    byte[] body = record.value();
                    System.out.println("\n [x] Received Avro Telemetry (" + body.length + " bytes)");

                    // Simular guardado RAW en MinIO
                    System.out.println("     -> [Simulado] Guardando RAW " + body.length + " bytes en MinIO crudo /web-logs/part-XX");

                    // Reenvío al RMI Enricher usando Virtual Threads.
                    // Devuelve true si el registro queda "resuelto" (procesado o en DLT).
                    batch.add(executor.submit(() -> {
                        try {
                            client.processTelemetry(body);
                            return true;
                        } catch (Exception e) {
                            // El clúster RMI no pudo procesarlo: lo aparcamos en la
                            // dead-letter en vez de perderlo o bloquear el pipeline.
                            return sendToDeadLetter(dlqProducer, DLQ_TOPIC, record, e);
                        }
                    }));
                }

                // Esperamos a que el lote completo quede resuelto.
                boolean batchHandled = true;
                for (Future<Boolean> f : batch) {
                    try {
                        if (!Boolean.TRUE.equals(f.get())) batchHandled = false;
                    } catch (Exception ex) {
                        batchHandled = false;
                    }
                }

                if (batchHandled) {
                    // ACK MANUAL: confirmamos el offset solo ahora que todo el lote
                    // está procesado o aparcado de forma segura en la dead-letter.
                    try {
                        consumer.commitSync();
                        System.out.println("     -> [ACK] Offset confirmado para el lote (" + records.count() + " msg).");
                    } catch (CommitFailedException e) {
                        System.err.println("Commit de offset falló (se reintentará el lote): " + e.getMessage());
                    }
                } else {
                    // No confirmamos y rebobinamos a los offsets iniciales del lote
                    // para reprocesarlo en el próximo poll (puede generar duplicados:
                    // semántica at-least-once).
                    System.err.println("   ⚠️  Lote no resuelto (¿dead-letter caída?): rebobinando para reintentar.");
                    for (TopicPartition tp : records.partitions()) {
                        long firstOffset = records.records(tp).get(0).offset();
                        consumer.seek(tp, firstOffset);
                    }
                }
            }
        }
    }

    /**
     * Envía un mensaje no procesable a la cola de mensajes muertos (Dead-Letter Topic).
     * Adjunta en cabeceras el origen y la causa del fallo para diagnóstico posterior.
     *
     * @return true si el mensaje se escribió en la DLT (queda "resuelto"); false si ni
     *         siquiera se pudo escribir en la DLT (hay que reintentar el lote completo).
     */
    private static boolean sendToDeadLetter(KafkaProducer<String, byte[]> producer, String dlqTopic,
                                            ConsumerRecord<String, byte[]> original, Exception cause) {
        System.err.println("   ⚠️  Mensaje no procesable -> Dead-Letter (" + dlqTopic + "): " + cause.getMessage());
        ProducerRecord<String, byte[]> dlqRecord =
                new ProducerRecord<>(dlqTopic, original.key(), original.value());
        dlqRecord.headers()
            .add("dlq-origin-topic",     original.topic().getBytes(StandardCharsets.UTF_8))
            .add("dlq-origin-partition", String.valueOf(original.partition()).getBytes(StandardCharsets.UTF_8))
            .add("dlq-origin-offset",    String.valueOf(original.offset()).getBytes(StandardCharsets.UTF_8))
            .add("dlq-error",            String.valueOf(cause.getMessage()).getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(dlqRecord).get(); // .get() => esperamos la confirmación del broker
            return true;
        } catch (Exception sendErr) {
            System.err.println("   ❌ No se pudo escribir en la Dead-Letter: " + sendErr.getMessage());
            return false;
        }
    }
}
