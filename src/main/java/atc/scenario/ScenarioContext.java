package atc.scenario;

import atc.allocator.CheckLog;
import atc.allocator.InvariantChecker;
import atc.allocator.ReservationRecord;
import atc.common.AtcConfig;
import atc.common.AtcConfig.AirportConfig;
import atc.common.CodecException;
import atc.common.ControlState;
import atc.common.Event;
import atc.common.Heartbeat;
import atc.common.JsonCodec;
import atc.common.Log;
import atc.common.Message;
import atc.common.ReservationState;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Everything one scenario run needs: its results folder, an isolated config (ports +1000 so a
 * running demo is never disturbed), the processes it started, ZeroMQ, and the PASS/FAIL checks
 * it records. Closing it stops every process it started and writes checks.txt.
 */
public final class ScenarioContext implements AutoCloseable {
    public record Check(String scenario, String invariant, String name, boolean pass, String detail) {
    }

    static final int PORT_OFFSET = 1000;

    final String scenarioId;
    final Path dir;
    final ZContext zmq = new ZContext();
    final AtcConfig config;
    final Path configFile;
    final Log log;
    private final List<ServiceProcess> processes = new ArrayList<>();
    private final List<Check> checks = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    ScenarioContext(String scenarioId, Path resultsRoot, Path baseConfig, int timeScale, int receiveTimeoutMs)
            throws IOException {
        this.scenarioId = scenarioId;
        this.dir = resultsRoot.resolve(scenarioId);
        this.log = new Log("scenario-" + scenarioId);
        if (Files.exists(dir)) {
            try (var files = Files.walk(dir)) {
                for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    if (!p.equals(dir)) {
                        Files.delete(p);
                    }
                }
            }
        }
        Files.createDirectories(dir);
        AtcConfig base = AtcConfig.load(baseConfig);
        this.config = isolated(base, timeScale, receiveTimeoutMs);
        this.configFile = dir.resolve("config.json");
        Files.writeString(configFile, JsonCodec.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(config),
                StandardCharsets.UTF_8);
    }

    private static AtcConfig isolated(AtcConfig base, int timeScale, int receiveTimeoutMs) {
        AtcConfig.AllocatorConfig a = base.allocator();
        AtcConfig.Timing t = base.timing();
        List<AirportConfig> airports = base.airports().stream().map(ap -> new AirportConfig(ap.id(),
                ap.controlRouterPort() + PORT_OFFSET, ap.positionSubPort() + PORT_OFFSET, ap.eventPubPort() + PORT_OFFSET,
                ap.aircraftCount(), ap.taxiways(), ap.runways())).toList();
        return new AtcConfig(new AtcConfig.AllocatorConfig("127.0.0.1", a.routerPort() + PORT_OFFSET, a.pubPort() + PORT_OFFSET),
                new AtcConfig.Timing(t.slotSeconds(), t.bufferSeconds(), t.waitThresholdSeconds(), receiveTimeoutMs,
                        t.heartbeatMs(), timeScale),
                airports);
    }

    // ------------------------------------------------------------------ processes

    private ServiceProcess start(String name, String mainClass, List<String> args) throws IOException {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"), mainClass));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve(name + ".log").toFile()));
        ServiceProcess p = new ServiceProcess(name, pb.start());
        processes.add(p);
        log.info("started %s (pid %d)", name, p.pid());
        return p;
    }

    Path allocatorLog() {
        return dir.resolve("allocator-reservations.jsonl");
    }

    ServiceProcess startAllocator(String name, Path logFile, String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of("--config", configFile.toString(), "--log", logFile.toString()));
        args.addAll(List.of(extra));
        ServiceProcess p = start(name, "atc.allocator.RunwayAllocator", args);
        awaitHeartbeat(allocatorPub(), "allocator", 15_000);
        return p;
    }

    /** Starts a control service and waits until it has heard from the allocator (if expected). */
    ServiceProcess startControl(String airportId, int timeoutMs, boolean expectAllocator) throws Exception {
        ServiceProcess p = start("control-" + airportId, "atc.control.ControlService",
                List.of("--airport", airportId, "--config", configFile.toString(), "--timeout-ms", String.valueOf(timeoutMs)));
        if (expectAllocator) {
            awaitControlState(airportId, ControlState::allocatorReachable, 15_000);
        } else {
            awaitHeartbeat(controlEvents(airportId), "control-" + airportId, 15_000);
        }
        return p;
    }

    // ------------------------------------------------------------------ endpoints

    String allocatorRouter() {
        return "tcp://127.0.0.1:" + config.allocator().routerPort();
    }

    String allocatorPub() {
        return "tcp://127.0.0.1:" + config.allocator().pubPort();
    }

    private AirportConfig airport(String id) {
        return config.airport(id).orElseThrow();
    }

    String controlRouter(String airportId) {
        return "tcp://127.0.0.1:" + airport(airportId).controlRouterPort();
    }

    String controlPositions(String airportId) {
        return "tcp://127.0.0.1:" + airport(airportId).positionSubPort();
    }

    String controlEvents(String airportId) {
        return "tcp://127.0.0.1:" + airport(airportId).eventPubPort();
    }

    // ------------------------------------------------------------------ waiting on published state

    void awaitHeartbeat(String endpoint, String service, long timeoutMs) throws Exception {
        try (ZMQ.Socket sub = subscriber(endpoint)) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                byte[] data = sub.recv();
                if (data != null && decode(data) instanceof Heartbeat hb && hb.service().equals(service)) {
                    return;
                }
            }
        }
        throw new IllegalStateException(service + " did not come up within " + timeoutMs + " ms (see its log in " + dir + ")");
    }

    ControlState awaitControlState(String airportId, Predicate<ControlState> condition, long timeoutMs) throws Exception {
        try (ZMQ.Socket sub = subscriber(controlEvents(airportId))) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                byte[] data = sub.recv();
                if (data != null && decode(data) instanceof Event e && e.kind().equals("STATE")) {
                    ControlState s = JsonCodec.decode(e.detail(), ControlState.class);
                    if (condition.test(s)) {
                        return s;
                    }
                }
            }
        }
        throw new IllegalStateException("control-" + airportId + " never reached the expected state within " + timeoutMs + " ms");
    }

    ZMQ.Socket subscriber(String endpoint) {
        ZMQ.Socket sub = zmq.createSocket(SocketType.SUB);
        sub.setLinger(0);
        sub.subscribe(new byte[0]);
        sub.setReceiveTimeOut(200);
        sub.connect(endpoint);
        return sub;
    }

    private static Message decode(byte[] data) {
        try {
            return JsonCodec.decode(data);
        } catch (CodecException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ results

    void check(String invariant, String name, boolean pass, String detail) {
        checks.add(new Check(scenarioId, invariant, name, pass, detail));
        log.info("%s  [inv %s] %s%s", pass ? "PASS" : "FAIL", invariant, name, detail.isEmpty() ? "" : " - " + detail);
    }

    void note(String fmt, Object... args) {
        String text = String.format(fmt, args);
        notes.add(text);
        log.info("%s", text);
    }

    List<Check> checks() {
        return checks;
    }

    PrintWriter csv(String file, String header) throws IOException {
        PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve(file), StandardCharsets.UTF_8));
        w.println(header);
        return w;
    }

    /**
     * Invariants 1-3 from the allocator's durable log: final-state separation (with buffers),
     * no simultaneous occupancy over the whole history, eligibility, and one decision per requestId.
     */
    List<ReservationRecord> logInvariantChecks(Path logFile) throws IOException {
        List<ReservationRecord> history = CheckLog.readHistory(logFile);
        Map<String, ReservationRecord> latest = new java.util.LinkedHashMap<>();
        history.forEach(r -> latest.put(r.requestId(), r));
        List<String> v1 = InvariantChecker.overlapViolations(latest.values(), config.timing().bufferMs());
        check("1", "no overlapping blocking reservations incl. buffers (final state)", v1.isEmpty(), first(v1));
        List<String> v1h = InvariantChecker.historicalOverlapViolations(history);
        check("1", "no simultaneous runway occupancy (whole history)", v1h.isEmpty(), first(v1h));
        List<String> v2 = InvariantChecker.eligibilityViolations(latest.values(), config);
        check("2", "every assigned runway eligible", v2.isEmpty(), first(v2));
        Set<ReservationState> decisions = EnumSet.of(ReservationState.PENDING_ACK, ReservationState.REJECTED, ReservationState.WAIT);
        Map<String, Long> decisionCount = history.stream().filter(r -> decisions.contains(r.state()))
                .collect(Collectors.groupingBy(ReservationRecord::requestId, Collectors.counting()));
        List<String> dup = decisionCount.entrySet().stream().filter(e -> e.getValue() > 1)
                .map(e -> e.getKey() + " decided " + e.getValue() + " times").toList();
        check("3", "at most one decision per requestId in the log", dup.isEmpty(), first(dup));
        return history;
    }

    private static String first(List<String> violations) {
        return violations.isEmpty() ? "" : violations.size() + " violation(s), e.g. " + violations.get(0);
    }

    @Override
    public void close() throws Exception {
        for (int i = processes.size() - 1; i >= 0; i--) {
            processes.get(i).stop();
        }
        zmq.close();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("checks.txt"), StandardCharsets.UTF_8))) {
            for (Check c : checks) {
                w.printf("%s  [inv %s] %s%s%n", c.pass() ? "PASS" : "FAIL", c.invariant(), c.name(),
                        c.detail().isEmpty() ? "" : " - " + c.detail());
            }
            if (!notes.isEmpty()) {
                w.println();
                notes.forEach(w::println);
            }
        }
    }
}
