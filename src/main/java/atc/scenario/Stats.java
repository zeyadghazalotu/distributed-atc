package atc.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Latency statistics (nearest-rank percentiles) over millisecond samples. */
final class Stats {
    private final List<Double> samples = new ArrayList<>();

    void add(double ms) {
        samples.add(ms);
    }

    int count() {
        return samples.size();
    }

    double percentile(double p) {
        if (samples.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    double max() {
        return samples.isEmpty() ? Double.NaN : Collections.max(samples);
    }

    double mean() {
        return samples.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    String summary() {
        return String.format("n=%d p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms",
                count(), percentile(50), percentile(95), percentile(99), max());
    }
}
