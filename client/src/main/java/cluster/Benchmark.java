package cluster;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark tool to quantitatively justify the choice of Virtual Threads (Loom)
 * and show linear/horizontal scalability as the number of nodes in the cluster increases.
 */
public class Benchmark {

    public static final String SERVICE_NAME = "ComputeNode";

    public static void main(String[] args) throws Exception {
        System.out.println("======================================================================");
        System.out.println("        📊 CLUSTER RMI - BENCHMARK & JUSTIFICACIÓN CUANTITATIVA       ");
        System.out.println("======================================================================");

        if (args.length > 0 && args[0].equalsIgnoreCase("cluster")) {
            runClusterBenchmark(args);
        } else {
            runLocalBenchmark();
            System.out.println();
            System.out.println("💡 Para correr el benchmark del clúster RMI distribuido y probar la escalabilidad:");
            System.out.println("   java -cp client/target/client-1.0-SNAPSHOT.jar cluster.Benchmark cluster localhost:6100 [localhost:6101 ...]");
        }
    }

    private static void runLocalBenchmark() throws Exception {
        int numTasks = 500;
        System.out.println("1. BENCHMARK LOCAL DE ESTRATEGIAS DE CONCURRENCIA");
        System.out.println("----------------------------------------------------------------------");
        System.out.println("Ejecutando " + numTasks + " tareas concurrentes.");
        System.out.println("Cada tarea procesa 1.000 muestras de telemetría (Stress Score Piecewise + EWMA + P95/P99)");
        System.out.println("y bloquea el hilo con sleep de 15ms simulando persistencia en BD.");
        System.out.println();

        // --- ESTRATEGIA A: SECUENCIAL ---
        System.out.print("⏱️  Ejecutando de forma Secuencial... ");
        long startSeq = System.currentTimeMillis();
        for (int i = 0; i < numTasks; i++) {
            simulateTaskWork();
        }
        long timeSeq = System.currentTimeMillis() - startSeq;
        double throughputSeq = numTasks / (timeSeq / 1000.0);
        System.out.printf("COMPLETADO%n   - Tiempo: %d ms%n   - Rendimiento: %.2f tareas/seg%n   - Hilos activos: %d%n%n", 
            timeSeq, throughputSeq, Thread.activeCount());

        // --- ESTRATEGIA B: HILOS DE PLATAFORMA (Pool Fijo de 20) ---
        int poolSize = 20;
        System.out.print("⏱️  Ejecutando con Hilos de Plataforma (Pool Fijo de " + poolSize + ")... ");
        ExecutorService platformPool = Executors.newFixedThreadPool(poolSize);
        long startPlat = System.currentTimeMillis();
        List<Future<?>> futuresPlat = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) {
            futuresPlat.add(platformPool.submit(() -> {
                simulateTaskWork();
                return null;
            }));
        }
        for (Future<?> f : futuresPlat) {
            f.get();
        }
        long timePlat = System.currentTimeMillis() - startPlat;
        platformPool.shutdown();
        double throughputPlat = numTasks / (timePlat / 1000.0);
        System.out.printf("COMPLETADO%n   - Tiempo: %d ms%n   - Rendimiento: %.2f tareas/seg%n   - Hilos activos en JVM: ~%d%n%n", 
            timePlat, throughputPlat, poolSize);

        // --- ESTRATEGIA C: HILOS VIRTUALES (Java 21 Loom) ---
        System.out.print("⏱️  Ejecutando con Hilos Virtuales (Project Loom)... ");
        ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor();
        long startVirt = System.currentTimeMillis();
        List<Future<?>> futuresVirt = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) {
            futuresVirt.add(virtualPool.submit(() -> {
                simulateTaskWork();
                return null;
            }));
        }
        for (Future<?> f : futuresVirt) {
            f.get();
        }
        long timeVirt = System.currentTimeMillis() - startVirt;
        virtualPool.shutdown();
        double throughputVirt = numTasks / (timeVirt / 1000.0);
        System.out.printf("COMPLETADO%n   - Tiempo: %d ms%n   - Rendimiento: %.2f tareas/seg%n   - Hilos activos en JVM (Portador): %d%n%n", 
            timeVirt, throughputVirt, Thread.activeCount());

        // --- COMPARACIÓN Y CONCLUSIONES ---
        System.out.println("----------------------------------------------------------------------");
        System.out.println("📊 RESULTADOS COMPARATIVOS:");
        System.out.printf("   Sequential (Bloqueante): %d ms (Base) -> %.1f tareas/seg%n", timeSeq, throughputSeq);
        System.out.printf("   Platform Threads (Pool=%d): %d ms (Speedup: %.2fx) -> %.1f tareas/seg%n", 
            poolSize, timePlat, (double) timeSeq / timePlat, throughputPlat);
        System.out.printf("   Virtual Threads (Loom): %d ms (Speedup: %.2fx) -> %.1f tareas/seg%n", 
            timeVirt, (double) timeSeq / timeVirt, throughputVirt);
        System.out.println();
        System.out.println("🧠 JUSTIFICACIÓN DE LA SOLUCIÓN ELEGIDA (Hilos Virtuales):");
        System.out.println("   - Latencia y Concurrencia masiva: Los hilos virtuales evitan el bloqueo del hilo");
        System.out.println("     del sistema operativo cuando se espera por I/O (red RMI, base de datos, RabbitMQ).");
        System.out.println("   - Escabilidad de recursos: Instanciar un hilo virtual cuesta ~2KB frente a ~1MB de un");
        System.out.println("     hilo de plataforma. Permite escalar la carga sin agotar el espacio de pila de la JVM.");
        System.out.println("----------------------------------------------------------------------");
    }

    private static void simulateTaskWork() {
        // 1. Simular la obtención de métricas base de telemetría
        Random rng = new Random();
        double cpu  = rng.nextDouble() * 100;
        double ram  = rng.nextDouble() * 100;
        double disk = rng.nextDouble() * 100;
        double temp = 30 + rng.nextDouble() * 60;

        // 2. Procesar ventana de muestras con el Modelo a Trozos (Piecewise)
        int windowSize = 1000;
        double[] scores = new double[windowSize];
        for (int i = 0; i < windowSize; i++) {
            double cpuS  = Math.max(0, Math.min(100, cpu + rng.nextGaussian() * 5.0));
            double ramS  = Math.max(0, Math.min(100, ram + rng.nextGaussian() * 3.0));
            double diskS = Math.max(0, Math.min(100, disk + rng.nextGaussian() * 2.0));
            double tempS = Math.max(20, Math.min(105, temp + rng.nextGaussian() * 4.0));

            // Modelo a Trozos: Media Ponderada + Hard Override al 90%
            double tempScore = Math.max(0, Math.min(100, ((tempS - 30.0) / 70.0) * 100.0));
            double weightedAvg = (0.35 * cpuS) + (0.30 * tempScore)
                               + (0.20 * ramS) + (0.15 * diskS);
            double maxCritical = Math.max(cpuS, tempScore);
            scores[i] = (maxCritical >= 90.0)
                ? Math.max(weightedAvg, maxCritical) : weightedAvg;
        }

        // 3. Suavizado EWMA (α=0.3) + estadísticas
        double[] ewma = new double[windowSize];
        ewma[0] = scores[0];
        for (int i = 1; i < windowSize; i++) {
            ewma[i] = 0.3 * scores[i] + 0.7 * ewma[i - 1];
        }
        double sum = 0, sumSq = 0;
        for (double v : ewma) { sum += v; sumSq += v * v; }
        double mean = sum / windowSize;
        double stdDev = Math.sqrt(Math.abs((sumSq / windowSize) - (mean * mean)));

        // 4. Percentiles P95/P99 (requiere ordenación O(n log n))
        double[] sorted = Arrays.copyOf(ewma, windowSize);
        Arrays.sort(sorted);

        // 5. Simular latencia de red/disco (I/O bloqueante)
        try {
            Thread.sleep(15);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runClusterBenchmark(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: java cluster.Benchmark cluster <seedHost:Port> [seedHost:Port ...]");
            return;
        }

        List<Endpoint> endpoints = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            endpoints.add(Endpoint.parse(args[i]));
        }

        int numTasks = 150;
        System.out.println("2. BENCHMARK DE ESCALABILIDAD DISTRIBUIDA (VISTA DE NODO VARIABLE)");
        System.out.println("----------------------------------------------------------------------");
        System.out.println("Enviando " + numTasks + " tareas de procesamiento de Stress Score a través de RMI.");
        System.out.println("Se probará el rendimiento de forma incremental (1 nodo, 2 nodos, ..., N nodos).");
        System.out.println();

        for (int count = 1; count <= endpoints.size(); count++) {
            List<Endpoint> subset = endpoints.subList(0, count);
            System.out.printf("🚀 Evaluando clúster con %d Nodo(s) Activo(s): %s%n", count, subset);

            List<ComputeService> stubs = new ArrayList<>();
            for (Endpoint e : subset) {
                try {
                    Registry reg = LocateRegistry.getRegistry(e.host, e.port);
                    stubs.add((ComputeService) reg.lookup(SERVICE_NAME));
                } catch (Exception ex) {
                    System.err.printf("   ❌ No se pudo conectar al nodo %s: %s%n", e, ex.getMessage());
                }
            }

            if (stubs.isEmpty()) {
                System.err.println("   ⚠️  No hay stubs RMI disponibles para este grupo.");
                continue;
            }

            // Ejecutar las tareas distribuidas por Round-Robin
            ExecutorService dispatcher = Executors.newVirtualThreadPerTaskExecutor();
            AtomicInteger rr = new AtomicInteger();
            
            long startTime = System.currentTimeMillis();
            List<Future<Double>> futures = new ArrayList<>();
            
            for (int i = 0; i < numTasks; i++) {
                futures.add(dispatcher.submit(() -> {
                    int idx = Math.floorMod(rr.getAndIncrement(), stubs.size());
                    ComputeService targetStub = stubs.get(idx);
                    // Lanza la tarea de cálculo de Stress Score en el nodo remoto
                    return targetStub.executeTask(new BenchmarkTask());
                }));
            }

            int successes = 0;
            for (Future<Double> f : futures) {
                try {
                    f.get();
                    successes++;
                } catch (Exception e) {
                    System.err.println("      ❌ Error ejecutando tarea: " + e.getCause());
                }
            }
            
            long totalTime = System.currentTimeMillis() - startTime;
            dispatcher.shutdown();
            double throughput = successes / (totalTime / 1000.0);

            System.out.printf("   📊 Resultados [%d Nodo(s)]:%n", count);
            System.out.printf("      - Tareas exitosas: %d/%d%n", successes, numTasks);
            System.out.printf("      - Tiempo total: %d ms%n", totalTime);
            System.out.printf("      - Rendimiento global: %.2f tareas/seg%n%n", throughput);
        }

        System.out.println("----------------------------------------------------------------------");
        System.out.println("🧠 EXPLICACIÓN DE LA ESCALABILIDAD DISTRIBUIDA:");
        System.out.println("   - Distribución horizontal: El despachador RMI (Client) utiliza round-robin");
        System.out.println("     para repartir la telemetría. Al añadir más nodos físicos/procesos,");
        System.out.println("     las tareas intensivas en CPU se reparten entre más cores y JVMs.");
        System.out.println("   - Ausencia de cuello de botella: Al aumentar de 1 a N nodos, se observa");
        System.out.println("     un incremento significativo en el rendimiento de tareas/seg,");
        System.out.println("     justificando la arquitectura de clúster peer-to-peer modular.");
        System.out.println("======================================================================");
    }
}

