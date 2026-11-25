package atc.scenario;

import atc.common.CodecException;
import atc.common.Event;
import atc.common.JsonCodec;
import atc.common.ProcessMetrics;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.ArrayList;
import java.util.List;

/** Collects a service's METRICS events on a background thread (its own ZeroMQ context). */
final class MetricsSampler implements AutoCloseable {
    private final List<ProcessMetrics> samples = new ArrayList<>();
    private final Thread thread;
    private volatile boolean running = true;

    MetricsSampler(String pubEndpoint) {
        thread = new Thread(() -> {
            try (ZContext ctx = new ZContext()) {
                ZMQ.Socket sub = ctx.createSocket(SocketType.SUB);
                sub.subscribe(new byte[0]);
                sub.setReceiveTimeOut(200);
                sub.connect(pubEndpoint);
                while (running) {
                    byte[] data = sub.recv();
                    if (data == null) {
                        continue;
                    }
                    try {
                        if (JsonCodec.decode(data) instanceof Event e && "METRICS".equals(e.kind())) {
                            ProcessMetrics m = JsonCodec.decode(e.detail(), ProcessMetrics.class);
                            synchronized (samples) {
                                samples.add(m);
                            }
                        }
                    } catch (CodecException ignored) {
                        // not a metrics message
                    }
                }
            }
        }, "metrics-sampler");
        thread.start();
    }

    List<ProcessMetrics> samples() {
        synchronized (samples) {
            return List.copyOf(samples);
        }
    }

    /** CPU use between consecutive samples, in percent of one core. */
    List<Double> cpuPercent() {
        List<ProcessMetrics> s = samples();
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < s.size(); i++) {
            long dCpu = s.get(i).processCpuNanos() - s.get(i - 1).processCpuNanos();
            long dWall = s.get(i).uptimeMs() - s.get(i - 1).uptimeMs();
            if (dWall > 0 && dCpu >= 0) {
                out.add(dCpu / (dWall * 1e6) * 100.0);
            }
        }
        return out;
    }

    double maxHeapMb() {
        return samples().stream().mapToLong(ProcessMetrics::heapUsedBytes).max().orElse(0) / 1048576.0;
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        thread.join(2000);
    }
}
