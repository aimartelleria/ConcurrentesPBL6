package cluster;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A cluster node with no shared infrastructure.
 *
 *  - Each node creates its OWN RMI registry on its own port.
 *  - The "cluster" is the union of nodes that can reach each other,
 *    learned by gossip seeded from a static list of bootstrap peers.
 *  - A node is evicted after MAX_FAILURES consecutive gossip failures.
 *  - Evicted addresses are tombstoned for TOMBSTONE_TTL_MS so a peer
 *    that still has the dead address can't keep re-introducing it.
 *
 * No node is special. Killing any subset short of "all of them" leaves
 * the rest functional, and a restarted node rejoins via any seed.
 */
@SuppressWarnings("serial")
public class ClusterNode extends UnicastRemoteObject implements ComputeService {

    private static final long serialVersionUID = 1L;
    public static final  String SERVICE_NAME       = "ComputeNode";
    private static final int    MAX_FAILURES       = 3;
    private static final long   TOMBSTONE_TTL_MS   = 30_000;
    private static final long   GOSSIP_INTERVAL_S  = 3;

    private final Endpoint                                 self;
    private final List<Endpoint>                           seeds;
    /** Members keyed by host:port so a "?"-id seed can be upgraded with its real nodeId. */
    private final ConcurrentHashMap<String, Endpoint>      members     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer>       failures    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>          tombstones  = new ConcurrentHashMap<>();
    
    // El Thread Pool (Executors) para paralelizar las peticiones entrantes usando Virtual Threads (Java 25)
    private final ExecutorService executorPool = Executors.newVirtualThreadPerTaskExecutor();

    /** Puerto de exportación del objeto remoto (fijo y abrible en firewall/WireGuard).
     *  Por defecto 0 = puerto anónimo. Se fija con RMI_OBJECT_PORT (p.ej. 1100). */
    private static int rmiObjectPort() {
        String v = System.getenv("RMI_OBJECT_PORT");
        try { return (v == null || v.isEmpty()) ? 0 : Integer.parseInt(v); }
        catch (NumberFormatException e) { return 0; }
    }

    public ClusterNode(String nodeId, String host, int port, List<Endpoint> seeds) throws RemoteException {
        super(rmiObjectPort()); // export object on RMI_OBJECT_PORT (0 = anonymous); registry is the entry point
        this.self  = new Endpoint(nodeId, host, port);
        this.seeds = List.copyOf(seeds);
        for (Endpoint s : seeds) {
            if (!s.addr().equals(self.addr())) members.put(s.addr(), s);
        }
    }

    // ---------- Remote methods ----------

    @Override public String getNodeId() { return self.nodeId; }
    @Override public long   ping()      { return System.currentTimeMillis(); }

    @Override
    public <T> T executeTask(Task<T> t) {
        System.out.printf("[%s] executing remote task...%n", self.nodeId);
        return t.execute();
    }

    @Override
    public void processTelemetry(byte[] avroPayload) {
        // Entregamos el payload al ExecutorService en lugar de procesarlo en el hilo principal
        executorPool.submit(() -> {
            try {
                // 1. Hardcoded Schema
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
                DatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
                Decoder decoder = DecoderFactory.get().binaryDecoder(avroPayload, null);
                GenericRecord record = reader.read(null, decoder);

                double cpu = (Double) record.get("cpu_percent");
                String cpuModel = record.get("cpu_model") != null ? record.get("cpu_model").toString() : "Unknown";
                double ram = (Double) record.get("ram_percent");
                long ramTotal = record.get("ram_total") != null ? (Long) record.get("ram_total") : 0L;
                double disk = (Double) record.get("disk_percent");
                long diskTotal = record.get("disk_total") != null ? (Long) record.get("disk_total") : 0L;
                Object tempObj = record.get("temp_c");

                // 2. Simulación de ETL / Enriquecimiento 
                // Scoring que mide el nivel de estrés del hardware
                double stressScore = (cpu * 0.4) + (ram * 0.4) + (disk * 0.2);
                if (tempObj != null) {
                    double temp = (Double) tempObj;
                    if (temp > 80.0) stressScore += 20.0;
                }

                double ramTotalGB = ramTotal / (1024.0 * 1024.0 * 1024.0);
                double diskTotalGB = diskTotal / (1024.0 * 1024.0 * 1024.0);

                System.out.printf("[%s | Thread: %s] ETL Procesado.%n" +
                                  "  - CPU Model: %s (%.2f%%)%n" +
                                  "  - RAM Total: %.2f GB (%.2f%%)%n" +
                                  "  - Disk Total: %.2f GB (%.2f%%)%n" +
                                  "  - Temp: %s%n" +
                                  "  - Stress Score: %.2f / 100%n", 
                                  self.nodeId, Thread.currentThread().getName(),
                                  cpuModel, cpu, ramTotalGB, ram, diskTotalGB, disk,
                                  (tempObj != null ? String.format("%.2f ºC", (Double) tempObj) : "N/A"),
                                  stressScore);
            } catch (Exception e) {
                System.err.println("Error procesando telemetría Avro en el thread: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @Override
    public Set<Endpoint> gossip(Endpoint sender, Set<Endpoint> theirView) {
        // Direct contact from the sender is first-hand proof of life:
        // override any tombstone we have on them. Second-hand entries in
        // theirView still respect tombstones via absorb().
        if (sender != null && !sender.addr().equals(self.addr())) {
            if (tombstones.remove(sender.addr()) != null) {
                System.out.println("Tombstone cleared by direct contact: " + sender);
            }
            members.put(sender.addr(), sender);
        }
        if (theirView != null) {
            for (Endpoint e : theirView) absorb(e);
        }
        Set<Endpoint> out = new HashSet<>(members.values());
        out.add(self);
        return out;
    }

    // ---------- Membership state ----------

    private void absorb(Endpoint e) {
        if (e == null || e.addr().equals(self.addr())) return;
        // Expire tombstones lazily.
        Long t = tombstones.get(e.addr());
        if (t != null) {
            if (System.currentTimeMillis() - t > TOMBSTONE_TTL_MS) {
                tombstones.remove(e.addr());
            } else {
                return; // still tombstoned; ignore re-introduction
            }
        }
        // Upsert — a later sighting with a known nodeId replaces a "?" seed.
        members.put(e.addr(), e);
    }

    // ---------- Lifecycle ----------

    public void start() throws Exception {
        Registry registry = LocateRegistry.createRegistry(self.port);
        registry.rebind(SERVICE_NAME, this);
        System.out.println("Started node " + self + " (own registry on port " + self.port + ")");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-gossip");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::gossipRound, 1, GOSSIP_INTERVAL_S, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                registry.unbind(SERVICE_NAME);
                System.out.println("Unbound " + self);
            } catch (Exception ignored) { /* registry already gone */ }
        }));
    }

    private void gossipRound() {
        try {
            // If we've lost every peer (partition, restart), retry seeds.
            if (members.isEmpty()) {
                for (Endpoint s : seeds) {
                    if (!s.addr().equals(self.addr()) && !tombstones.containsKey(s.addr())) {
                        members.put(s.addr(), s);
                    }
                }
            }

            List<Map.Entry<String, Endpoint>> peers = new ArrayList<>(members.entrySet());
            Collections.shuffle(peers);

            for (Map.Entry<String, Endpoint> entry : peers) {
                String   addr = entry.getKey();
                Endpoint peer = entry.getValue();
                try {
                    Registry reg  = LocateRegistry.getRegistry(peer.host, peer.port);
                    ComputeService stub = (ComputeService) reg.lookup(SERVICE_NAME);

                    Set<Endpoint> mine = new HashSet<>(members.values());
                    Set<Endpoint> theirs = stub.gossip(self, mine);

                    for (Endpoint e : theirs) absorb(e);
                    failures.remove(addr);
                } catch (Exception ex) {
                    int fails = failures.merge(addr, 1, Integer::sum);
                    if (fails >= MAX_FAILURES && members.remove(addr) != null) {
                        tombstones.put(addr, System.currentTimeMillis());
                        failures.remove(addr);
                        System.out.println("Evicted (no response after "
                                + MAX_FAILURES + " rounds): " + peer);
                    }
                }
            }

            System.out.printf("[%s] view: %s%n", self.nodeId, members.values());
        } catch (Exception e) {
            System.err.println("gossipRound failed: " + e.getMessage());
        }
    }

    // ---------- Main ----------
    /**
     * Supports TLS/SSL via JVM properties:
     *   -Djavax.net.ssl.keyStore=server.keystore
     *   -Djavax.net.ssl.keyStorePassword=changeit
     * See RUN_WITH_TLS.md for setup instructions.
     */    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: ClusterNode <nodeId> <host> <port> [seedHost:Port ...]");
            System.exit(1);
        }
        String nodeId = args[0];
        String host   = args[1];
        int    port   = Integer.parseInt(args[2]);

        List<Endpoint> seeds = new ArrayList<>();
        for (int i = 3; i < args.length; i++) seeds.add(Endpoint.parse(args[i]));

        new ClusterNode(nodeId, host, port, seeds).start();
        Thread.currentThread().join();
    }
}
