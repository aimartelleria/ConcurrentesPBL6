import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Channel;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import java.io.ByteArrayOutputStream;

/**
 * Sends N Avro telemetry messages to test failover and round-robin.
 * Usage: java TestSend [count]  (default: 3)
 */
public class TestSend {
    public static void main(String[] args) throws Exception {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 3;

        String schemaStr = "{ \"type\": \"record\", \"name\": \"Metrics\", \"namespace\": \"com.example\", \"fields\": [ " +
            "{\"name\": \"timestamp\", \"type\": \"long\"}, " +
            "{\"name\": \"cpu_percent\", \"type\": \"double\"}, " +
            "{\"name\": \"cpu_model\", \"type\": \"string\"}, " +
            "{\"name\": \"ram_percent\", \"type\": \"double\"}, " +
            "{\"name\": \"ram_total\", \"type\": \"long\"}, " +
            "{\"name\": \"disk_percent\", \"type\": \"double\"}, " +
            "{\"name\": \"disk_total\", \"type\": \"long\"}, " +
            "{\"name\": \"temp_c\", \"type\": [\"null\", \"double\"], \"default\": null} ]}";
        Schema schema = new Schema.Parser().parse(schemaStr);

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        try (Connection conn = factory.newConnection(); Channel ch = conn.createChannel()) {
            ch.exchangeDeclare("telemetry_fanout", "fanout", true);

            for (int i = 1; i <= count; i++) {
                GenericRecord record = new GenericData.Record(schema);
                record.put("timestamp", System.currentTimeMillis() / 1000);
                record.put("cpu_percent", 20.0 + (i * 15.0));   // varies per msg
                record.put("cpu_model", "Intel Core i7 Gen " + i);
                record.put("ram_percent", 50.0 + (i * 5.0));
                record.put("ram_total", 16L * 1024 * 1024 * 1024); // 16 GB
                record.put("disk_percent", 40.0 + (i * 3.0));
                record.put("disk_total", 512L * 1024 * 1024 * 1024); // 512 GB
                record.put("temp_c", 65.0 + (i * 5.0));

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DatumWriter<GenericRecord> writer = new GenericDatumWriter<>(schema);
                Encoder encoder = EncoderFactory.get().binaryEncoder(baos, null);
                writer.write(record, encoder);
                encoder.flush();
                byte[] payload = baos.toByteArray();

                ch.basicPublish("telemetry_fanout", "", null, payload);
                System.out.println("[msg " + i + "/" + count + "] Sent " + payload.length + " bytes (cpu=" + record.get("cpu_percent") + ")");
                Thread.sleep(500); // small gap between messages
            }
        }
        System.out.println("Done. " + count + " messages sent.");
    }
}
