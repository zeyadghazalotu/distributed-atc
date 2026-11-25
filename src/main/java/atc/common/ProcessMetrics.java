package atc.common;

/**
 * A service's own resource usage, published as the detail of {@code EVENT kind=METRICS} once
 * per heartbeat. Measured from inside the process, so it works on every OS; the ScenarioRunner
 * samples it during load tests.
 *
 * @param processCpuNanos total CPU time used by the process so far (all threads)
 * @param requestsHandled requests processed since start
 */
public record ProcessMetrics(long heapUsedBytes, long heapMaxBytes, long processCpuNanos, long uptimeMs,
                             long requestsHandled, int availableProcessors) {

    public static ProcessMetrics sample(long startedAt, long requestsHandled) {
        Runtime rt = Runtime.getRuntime();
        long cpu = -1;
        if (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean os) {
            cpu = os.getProcessCpuTime();
        }
        return new ProcessMetrics(rt.totalMemory() - rt.freeMemory(), rt.maxMemory(), cpu,
                System.currentTimeMillis() - startedAt, requestsHandled, rt.availableProcessors());
    }
}
