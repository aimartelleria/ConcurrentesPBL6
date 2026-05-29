package cluster;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Random;

/**
 * Tarea de benchmark que ejecuta el pipeline completo de análisis de telemetría
 * utilizando el Modelo a Trozos (Piecewise) basado en la metodología USE de Brendan Gregg:
 *
 * 1. Generación de ventana temporal de muestras de telemetría (distribución gaussiana)
 * 2. Cálculo del Stress Score por muestra (Media Ponderada + Hard Override al 90%)
 * 3. Suavizado EWMA (Exponential Weighted Moving Average)
 * 4. Análisis estadístico: media, desviación típica, percentiles P95/P99
 * 5. Detección de anomalías por Z-Score
 * 6. Cálculo del score final compuesto
 * 7. Simulación de persistencia en almacenamiento distribuido (I/O bloqueante)
 */
public class BenchmarkTask implements Task<Double>, Serializable {
    private static final long serialVersionUID = 1L;

    private final double cpuPercent;
    private final double ramPercent;
    private final double diskPercent;
    private final double temperature;

    public BenchmarkTask() {
        // Constructor por defecto con valores aleatorios simulados
        this.cpuPercent = Math.random() * 100;
        this.ramPercent = Math.random() * 100;
        this.diskPercent = Math.random() * 100;
        this.temperature = 30 + Math.random() * 60; // entre 30ºC y 90ºC
    }

    public BenchmarkTask(double cpuPercent, double ramPercent, double diskPercent, double temperature) {
        this.cpuPercent = cpuPercent;
        this.ramPercent = ramPercent;
        this.diskPercent = diskPercent;
        this.temperature = temperature;
    }

    @Override
    public Double execute() {
        int windowSize = 5000;
        Random rng = new Random(Double.doubleToLongBits(cpuPercent + ramPercent));

        // --- 1. Simular ventana temporal de muestras de telemetría ---
        // Genera N lecturas con ruido gaussiano alrededor de las métricas base,
        // emulando las fluctuaciones reales de un sistema monitorizado.
        double[] cpuSamples  = new double[windowSize];
        double[] ramSamples  = new double[windowSize];
        double[] diskSamples = new double[windowSize];
        double[] tempSamples = new double[windowSize];
        for (int i = 0; i < windowSize; i++) {
            cpuSamples[i]  = clamp(cpuPercent + rng.nextGaussian() * 5.0, 0, 100);
            ramSamples[i]  = clamp(ramPercent + rng.nextGaussian() * 3.0, 0, 100);
            diskSamples[i] = clamp(diskPercent + rng.nextGaussian() * 2.0, 0, 100);
            tempSamples[i] = clamp(temperature + rng.nextGaussian() * 4.0, 20, 105);
        }

        // --- 2. Calcular Stress Score por muestra (Modelo a Trozos / Piecewise) ---
        // Fase 1: Media Ponderada = (0.35·CPU) + (0.30·Temp) + (0.20·RAM) + (0.15·Disco)
        // Fase 2: Hard Override si max(CPU, Temp) >= 90%
        double[] stressScores = new double[windowSize];
        for (int i = 0; i < windowSize; i++) {
            double tempScore = clamp(((tempSamples[i] - 30.0) / 70.0) * 100.0, 0, 100);
            double weightedAvg = (0.35 * cpuSamples[i]) + (0.30 * tempScore)
                               + (0.20 * ramSamples[i]) + (0.15 * diskSamples[i]);
            double maxCritical = Math.max(cpuSamples[i], tempScore);
            // Hard Override: si CPU o Temp >= 90%, el score lo gobierna el máximo
            stressScores[i] = (maxCritical >= 90.0)
                ? Math.max(weightedAvg, maxCritical)
                : weightedAvg;
        }

        // --- 3. Suavizado EWMA (Exponential Weighted Moving Average, α=0.3) ---
        double alpha = 0.3;
        double[] ewma = new double[windowSize];
        ewma[0] = stressScores[0];
        for (int i = 1; i < windowSize; i++) {
            ewma[i] = alpha * stressScores[i] + (1.0 - alpha) * ewma[i - 1];
        }

        // --- 4. Estadísticas: media, desviación típica ---
        double sum = 0.0, sumSq = 0.0;
        for (double v : ewma) {
            sum += v;
            sumSq += v * v;
        }
        double mean = sum / windowSize;
        double variance = (sumSq / windowSize) - (mean * mean);
        double stdDev = Math.sqrt(Math.abs(variance));

        // --- 5. Percentiles P95 y P99 (requiere ordenación O(n log n)) ---
        double[] sorted = Arrays.copyOf(ewma, windowSize);
        Arrays.sort(sorted);
        double p95 = sorted[(int) (windowSize * 0.95)];
        double p99 = sorted[(int) (windowSize * 0.99)];

        // --- 6. Detección de anomalías por Z-Score (umbral > 2σ) ---
        int anomalies = 0;
        for (double v : ewma) {
            if (stdDev > 0 && Math.abs((v - mean) / stdDev) > 2.0) {
                anomalies++;
            }
        }

        // --- 7. Score final compuesto (ponderación de estadísticos) ---
        double anomalyFactor = 1.0 + (anomalies * 0.02);
        double finalScore = (mean * 0.6 + p95 * 0.3 + p99 * 0.1) * anomalyFactor;
        finalScore = Math.min(100.0, finalScore);

        // --- 8. Simular persistencia en BD / almacenamiento distribuido ---
        try {
            Thread.sleep(40); // 40ms de escritura simulada en BD/disco
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return finalScore;
    }

    /** Limita el valor al rango [min, max]. */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
