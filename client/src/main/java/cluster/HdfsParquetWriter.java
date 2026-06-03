package cluster;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.Closeable;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Escribe la telemetría enriquecida (con stress_score) en HDFS como Parquet,
 * particionada por día estilo Hive: /data/telemetry/year=YYYY/month=MM/day=DD/.
 *
 * Camino batch, complementario al hot path (Cassandra). Tolerante a fallos: si
 * HDFS no está disponible, el llamante captura la excepción y el flujo principal
 * (Cassandra) no se ve afectado.
 *
 * Thread-safe (lock global, compatible con Virtual Threads). Un writer por día,
 * abierto de forma lazy y cerrado en el shutdown hook.
 */
public class HdfsParquetWriter implements Closeable {

    /** Esquema de salida: todos los campos del Avro de entrada + stress_score. */
    public static final Schema SCHEMA = new Schema.Parser().parse(
        "{\"type\":\"record\",\"name\":\"TelemetryEnriched\",\"namespace\":\"com.webhardmon\",\"fields\":[" +
        "{\"name\":\"empresa_id\",\"type\":\"long\"}," +
        "{\"name\":\"nombre\",\"type\":\"string\"}," +
        "{\"name\":\"ts\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}}," +
        "{\"name\":\"cpu_percent\",\"type\":\"double\"}," +
        "{\"name\":\"ram_percent\",\"type\":\"double\"}," +
        "{\"name\":\"disco_percent\",\"type\":\"double\"}," +
        "{\"name\":\"temperatura\",\"type\":[\"null\",\"double\"],\"default\":null}," +
        "{\"name\":\"bateria_percent\",\"type\":[\"null\",\"double\"],\"default\":null}," +
        "{\"name\":\"ram\",\"type\":\"string\"}," +
        "{\"name\":\"almacenamiento\",\"type\":\"string\"}," +
        "{\"name\":\"procesador\",\"type\":[\"null\",\"string\"],\"default\":null}," +
        "{\"name\":\"stress_score\",\"type\":\"double\"}]}");

    private static final String BASE = "/data/telemetry";
    private static final long ROW_GROUP = 128L * 1024 * 1024; // 128 MB

    private final String hdfsUri;
    private final Configuration conf;
    private final Object lock = new Object();
    private final Map<String, ParquetWriter<GenericRecord>> writers = new HashMap<>();

    public HdfsParquetWriter(String hdfsUri) {
        this.hdfsUri = hdfsUri.replaceAll("/+$", "");
        this.conf = new Configuration();
        conf.set("fs.defaultFS", this.hdfsUri);
        conf.setInt("dfs.replication", 2);
    }

    /** Escribe una medición enriquecida. Particiona por el día de {@code tsMillis}. */
    public void write(long empresaId, String nombre, long tsMillis,
                      double cpu, double ram, double disco, Double temp, Double bateria,
                      String ramTxt, String almTxt, String procesador, double stress) throws Exception {
        ZonedDateTime z = Instant.ofEpochMilli(tsMillis).atZone(ZoneOffset.UTC);
        String dayKey = String.format("year=%04d/month=%02d/day=%02d",
                z.getYear(), z.getMonthValue(), z.getDayOfMonth());

        GenericRecord rec = new GenericData.Record(SCHEMA);
        rec.put("empresa_id", empresaId);
        rec.put("nombre", nombre);
        rec.put("ts", tsMillis);
        rec.put("cpu_percent", cpu);
        rec.put("ram_percent", ram);
        rec.put("disco_percent", disco);
        rec.put("temperatura", temp);
        rec.put("bateria_percent", bateria);
        rec.put("ram", ramTxt);
        rec.put("almacenamiento", almTxt);
        rec.put("procesador", procesador);
        rec.put("stress_score", stress);

        synchronized (lock) {
            ParquetWriter<GenericRecord> w = writers.get(dayKey);
            if (w == null) {
                String file = String.format("%s/%s/part-%s-%d.parquet",
                        BASE, dayKey, UUID.randomUUID(), tsMillis);
                w = AvroParquetWriter.<GenericRecord>builder(new Path(hdfsUri + file))
                        .withSchema(SCHEMA)
                        .withConf(conf)
                        .withCompressionCodec(CompressionCodecName.SNAPPY)
                        .withRowGroupSize(ROW_GROUP)
                        .build();
                writers.put(dayKey, w);
            }
            w.write(rec);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            for (ParquetWriter<GenericRecord> w : writers.values()) {
                try { w.close(); } catch (Exception ignore) { /* best-effort */ }
            }
            writers.clear();
        }
    }
}
