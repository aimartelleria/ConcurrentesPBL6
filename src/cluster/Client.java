package cluster;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;

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

    /** Runs a compute() call with cross-node failover. */
    public double compute(double input) throws Exception {
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
                    System.out.println("-> dispatching to " + e);
                    return stub.compute(input);
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

        // RabbitMQ connection (act as consumer, simulating the ingest layer)
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection conn = factory.newConnection();
        Channel channel = conn.createChannel();

        // Join the Fanout Exchange
        channel.exchangeDeclare("telemetry_fanout", "fanout", true);

        // Shared queue across all Java consumer instances so messages are round-robin'd among them 
        String queueName = "telemetry_java_consumer_queue";
        channel.queueDeclare(queueName, true, false, false, null);
        channel.queueBind(queueName, "telemetry_fanout", "");

        System.out.println(" [*] Consumer started. Waiting for Avro telemetry. To exit press CTRL+C");

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            byte[] body = delivery.getBody();
            System.out.println("\n [x] Received Avro Telemetry (" + body.length + " bytes)");
            
            // Simular guardado RAW en MinIO
            System.out.println("     -> [Simulado] Guardando RAW " + body.length + " bytes en MinIO crudo /web-logs/part-XX");
            
            // Reenvío al RMI Enricher
            try {
                client.processTelemetry(body);
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            } catch (Exception e) {
                System.err.println("Error enviando a cluster RMI: " + e.getMessage());
                // Nack / dead-letter
            }
        };

        // Manual Ack configured
        channel.basicConsume(queueName, false, deliverCallback, consumerTag -> { });
    }
}
