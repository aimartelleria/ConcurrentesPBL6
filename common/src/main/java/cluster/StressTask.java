package cluster;

/**
 * Tarea de enriquecimiento que calcula el StressScore de un equipo a partir de
 * sus métricas. Se ejecuta de forma remota en un nodo del clúster vía RMI
 * (patrón Compute Engine: el cliente envía la tarea, el nodo corre execute()).
 *
 * Score = 0.4·CPU + 0.4·RAM + 0.2·Disco, con un recargo de +20 si la
 * temperatura supera los 80 ºC. Acotado a [0, 100].
 */
public class StressTask implements Task<Double> {

    private static final long serialVersionUID = 1L;

    private final double cpuPercent;
    private final double ramPercent;
    private final double discoPercent;
    private final Double temperatura; // puede ser null

    public StressTask(double cpuPercent, double ramPercent, double discoPercent, Double temperatura) {
        this.cpuPercent   = cpuPercent;
        this.ramPercent   = ramPercent;
        this.discoPercent = discoPercent;
        this.temperatura  = temperatura;
    }

    @Override
    public Double execute() {
        double score = (cpuPercent * 0.4) + (ramPercent * 0.4) + (discoPercent * 0.2);
        if (temperatura != null && temperatura > 80.0) {
            score += 20.0;
        }
        return Math.max(0.0, Math.min(100.0, score));
    }
}
