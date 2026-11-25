package atc.scenario;

import atc.common.Message;
import atc.common.Operation;
import atc.common.Reservation;
import atc.common.Reserve;
import atc.common.SizeClass;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Scenario 7: load test of the allocator, the single serial authority and so the throughput
 * bottleneck. An open-loop generator (acting as a control service) sends RESERVEs at fixed rates
 * against a fresh allocator process per level, measuring decision latency percentiles, achieved
 * throughput, and the allocator's own CPU and heap. Run twice: with fsync per decision (the real
 * system, invariant 5) and with --unsafe-no-fsync to show what durability costs.
 */
final class S7LoadTest implements Scenario {
    private final int[] rates;
    private final int seconds;
    private final boolean compareFsync;

    S7LoadTest(int[] rates, int seconds, boolean compareFsync) {
        this.rates = rates;
        this.seconds = seconds;
        this.compareFsync = compareFsync;
    }

    @Override
    public String id() {
        return "s7-load";
    }

    @Override
    public String title() {
        return "Load test: throughput and p50/p95/p99 latency vs request rate";
    }

    private record Level(int sent, int replied, Stats latency, double throughput, Map<String, Integer> outcomes) {
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        List<String> modes = compareFsync ? List.of("fsync", "no-fsync") : List.of("fsync");
        try (PrintWriter summary = ctx.csv("summary.csv",
                "mode,target_rps,sent,replied,unanswered,throughput_rps,p50_ms,p95_ms,p99_ms,max_ms,cpu_pct_avg,cpu_pct_max,heap_mb_max,assigned,wait,rejected")) {
            for (String mode : modes) {
                for (int rate : rates) {
                    Path log = ctx.dir.resolve("alloc-" + mode + "-" + rate + ".jsonl");
                    String[] extra = mode.equals("no-fsync") ? new String[]{"--unsafe-no-fsync"} : new String[0];
                    ServiceProcess allocator = ctx.startAllocator("allocator-" + mode + "-" + rate, log, extra);
                    Level level;
                    MetricsSampler sampler = new MetricsSampler(ctx.allocatorPub());
                    try {
                        Thread.sleep(1200); // first METRICS sample as a baseline
                        level = runLevel(ctx, mode, rate);
                        Thread.sleep(1100); // one more sample after the burst
                    } finally {
                        sampler.close();
                        allocator.stop();
                    }
                    List<Double> cpu = sampler.cpuPercent();
                    double cpuAvg = cpu.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
                    double cpuMax = cpu.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
                    Stats l = level.latency();
                    summary.printf("%s,%d,%d,%d,%d,%.1f,%.3f,%.3f,%.3f,%.3f,%.1f,%.1f,%.1f,%d,%d,%d%n", mode, rate, level.sent(),
                            level.replied(), level.sent() - level.replied(), level.throughput(), l.percentile(50), l.percentile(95),
                            l.percentile(99), l.max(), cpuAvg, cpuMax, sampler.maxHeapMb(),
                            level.outcomes().getOrDefault("PENDING_ACK", 0), level.outcomes().getOrDefault("WAIT", 0),
                            level.outcomes().getOrDefault("REJECTED", 0));
                    summary.flush();
                    ctx.note("%-8s %5d req/s target: %.0f req/s achieved, %s, cpu avg %.0f%% max %.0f%% of a core, heap max %.0f MB",
                            mode, rate, level.throughput(), l.summary(), cpuAvg, cpuMax, sampler.maxHeapMb());
                    ctx.check("liveness", mode + " @" + rate + " req/s: every request answered", level.replied() == level.sent(),
                            level.replied() + "/" + level.sent());
                    if (rate == rates[rates.length - 1]) {
                        ctx.note("invariant checks on the highest-rate %s log (%s):", mode, log.getFileName());
                        ctx.logInvariantChecks(log);
                    }
                }
            }
        }
    }

    /** Mutable tallies for one level (single-threaded). */
    private static final class Tally {
        final Map<String, Long> sentAt = new HashMap<>();
        final Map<String, Integer> outcomes = new HashMap<>();
        final Stats latency = new Stats();
        int replied;
        long lastReply;

        void onReply(AllocatorClient.Reply r, PrintWriter raw) {
            Long t = sentAt.remove(r.key());
            if (t == null) {
                return; // not ours / duplicate
            }
            double ms = (r.receivedNanos() - t) / 1e6;
            latency.add(ms);
            String state = r.message() instanceof Reservation res ? res.state().name() : "ERROR";
            outcomes.merge(state, 1, Integer::sum);
            raw.printf("%s,%.3f,%s%n", r.key(), ms, state);
            replied++;
            lastReply = r.receivedNanos();
        }
    }

    /**
     * Open loop: request i is sent at start + i/rate regardless of replies, so a slow allocator
     * shows up as growing latency instead of a silently lower send rate (no coordinated omission).
     */
    private Level runLevel(ScenarioContext ctx, String mode, int rate) throws Exception {
        int total = rate * seconds;
        Random rnd = new Random(42);
        Tally tally = new Tally();
        long dayMs = 24L * 3600 * 1000;
        try (AllocatorClient client = new AllocatorClient(ctx.zmq, ctx.allocatorRouter());
             PrintWriter raw = ctx.csv("latencies-" + mode + "-" + rate + ".csv", "request,latency_ms,state")) {
            Thread.sleep(200); // connect before the clock starts
            long start = System.nanoTime();
            tally.lastReply = start;
            double intervalNs = 1e9 / rate;
            int sent = 0;
            long lastSend = start;
            while (true) {
                long now = System.nanoTime();
                while (sent < total && start + (long) (sent * intervalNs) <= now) {
                    boolean yyz = (sent & 1) == 0;
                    // YOW has no HEAVY runway; keep the mix eligible so we measure allocations.
                    SizeClass size = SizeClass.values()[rnd.nextInt(yyz ? 3 : 2)];
                    String id = "load-" + mode + "-" + rate + "-" + sent;
                    long eta = System.currentTimeMillis() + (long) (rnd.nextDouble() * dayMs); // ETAs spread over a day
                    client.send(id, new Reserve(id, "LOAD-" + (sent % 500), yyz ? "YYZ" : "YOW",
                            rnd.nextBoolean() ? Operation.LANDING : Operation.TAKEOFF, size, false, eta));
                    tally.sentAt.put(id, System.nanoTime());
                    sent++;
                    lastSend = System.nanoTime();
                }
                for (AllocatorClient.Reply r = client.pollNow(); r != null; r = client.pollNow()) {
                    tally.onReply(r, raw);
                }
                if (sent == total && (tally.replied == total || System.nanoTime() - lastSend > 15_000_000_000L)) {
                    break;
                }
                // Block for at most 1 ms (or until a reply) before the next send slot.
                AllocatorClient.Reply r = client.poll(1);
                if (r != null) {
                    tally.onReply(r, raw);
                }
            }
            double elapsedS = (tally.lastReply - start) / 1e9;
            return new Level(sent, tally.replied, tally.latency, elapsedS > 0 ? tally.replied / elapsedS : 0, tally.outcomes);
        }
    }
}
