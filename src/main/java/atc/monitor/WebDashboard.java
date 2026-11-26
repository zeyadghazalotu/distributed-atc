package atc.monitor;

import atc.common.AtcClock;
import atc.common.AtcConfig;
import atc.common.AtcConfig.AirportConfig;
import atc.common.CodecException;
import atc.common.ControlState;
import atc.common.JsonCodec;
import atc.common.Log;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The web dashboard: the same live view as the console {@link Monitor}, in a browser.
 *
 * Like the Monitor it only listens: one SUB socket on the allocator's and every control
 * service's PUB. A single loop thread owns the ZeroMQ socket and the {@link MonitorState}; it
 * publishes an immutable JSON snapshot a few times per second, and the HTTP threads only ever
 * read that string. So the web server never touches ZeroMQ or shared mutable state.
 *
 * Endpoints: {@code /} the page, {@code /api/state} the latest snapshot (JSON),
 * {@code /api/results} the evaluation scenario results (read from disk on each request).
 *
 * Run: java -cp target/atc.jar atc.monitor.WebDashboard
 *          [--config config/airports.json] [--host localhost] [--port 8080] [--bind 127.0.0.1]
 *          [--results results]
 * then open http://localhost:8080
 */
public final class WebDashboard implements Runnable {
    static final int MAX_EVENTS = 60;
    private static final long SNAPSHOT_MS = 250;

    private final AtcConfig config;
    private final List<String> endpoints;
    private final AtcClock clock;
    private final Path resultsDir;
    private final Log log = new Log("web");

    private volatile boolean running = true;
    private volatile String snapshot;

    public WebDashboard(AtcConfig config, List<String> endpoints, AtcClock clock) {
        this(config, endpoints, clock, null);
    }

    /** @param resultsDir scenario results to show; null = results/ if it has a run, else the committed sample */
    public WebDashboard(AtcConfig config, List<String> endpoints, AtcClock clock, Path resultsDir) {
        this.resultsDir = resultsDir;
        this.config = config;
        this.endpoints = endpoints;
        this.clock = clock;
        this.snapshot = snapshot(config, new MonitorState(MAX_EVENTS), clock.nowMillis());
    }

    @Override
    public void run() {
        MonitorState state = new MonitorState(MAX_EVENTS);
        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket sub = ctx.createSocket(SocketType.SUB);
            sub.setLinger(0);
            sub.subscribe(new byte[0]);
            for (String e : endpoints) {
                sub.connect(e); // ZeroMQ reconnects, so services may start later
            }
            ZMQ.Poller poller = ctx.createPoller(1);
            poller.register(sub, ZMQ.Poller.POLLIN);
            long next = clock.nowMillis();
            while (running && !Thread.currentThread().isInterrupted()) {
                long now = clock.nowMillis();
                if (now >= next) {
                    snapshot = snapshot(config, state, now);
                    next = now + SNAPSHOT_MS;
                }
                if (poller.poll(Math.max(1, Math.min(100, next - now))) > 0) {
                    byte[] data;
                    while ((data = sub.recv(ZMQ.DONTWAIT)) != null) {
                        try {
                            state.onMessage(JsonCodec.decode(data), clock.nowMillis());
                        } catch (CodecException e) {
                            state.onMalformed();
                        }
                    }
                }
            }
        }
    }

    public void stop() {
        running = false;
    }

    /** The latest JSON snapshot served at /api/state. */
    public String snapshot() {
        return snapshot;
    }

    /**
     * Everything the page needs, as JSON. Pure (state + time in, text out) so it is unit-tested.
     * Health uses the same rule as the console: UP while a heartbeat arrived within 3 periods.
     */
    static String snapshot(AtcConfig config, MonitorState state, long now) {
        long heartbeatMs = config.timing().heartbeatMs();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("now", now);
        root.put("heartbeatMs", heartbeatMs);
        root.put("slotSeconds", config.timing().slotSeconds());
        root.put("bufferSeconds", config.timing().bufferSeconds());

        List<Map<String, Object>> services = new ArrayList<>();
        services.add(service("allocator", state.lastHeartbeat("allocator"), now, heartbeatMs));
        for (AirportConfig a : config.airports()) {
            services.add(service("control-" + a.id(), state.lastHeartbeat("control-" + a.id()), now, heartbeatMs));
        }
        root.put("services", services);

        List<Map<String, Object>> airports = new ArrayList<>();
        for (AirportConfig a : config.airports()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("runways", a.runways());
            m.put("taxiways", a.taxiways());
            ControlState s = state.airport(a.id());
            m.put("state", s);
            Long at = state.airportStateReceivedAt(a.id());
            m.put("stateAgeMs", at == null ? null : now - at);
            airports.add(m);
        }
        root.put("airports", airports);

        List<Map<String, Object>> events = new ArrayList<>();
        for (MonitorState.LoggedEvent e : state.events()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", e.receivedAt());
            m.put("service", e.service());
            m.put("kind", e.kind());
            m.put("text", e.text());
            events.add(m);
        }
        root.put("events", events);
        root.put("messages", state.messages());
        root.put("malformed", state.malformed());
        return JsonCodec.encode(root);
    }

    private static Map<String, Object> service(String name, Long lastHeartbeat, long now, long heartbeatMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("up", lastHeartbeat != null && now - lastHeartbeat <= 3 * heartbeatMs);
        m.put("heartbeatAgeMs", lastHeartbeat == null ? null : now - lastHeartbeat);
        return m;
    }

    // ------------------------------------------------------------------ evaluation results

    /** The latest scenario run in results/ if there is one, else the committed sample run. */
    static Path defaultResultsDir() {
        Path latest = Path.of("results");
        return Files.isRegularFile(latest.resolve("summary.csv")) ? latest : Path.of("results/sample/scenarios");
    }

    /**
     * The ScenarioRunner's output as JSON: every check from summary.csv, each scenario's measured
     * numbers (the non-check lines of its checks.txt), and the load-test table from
     * s7-load/summary.csv. Missing files give empty sections, never an error.
     */
    static String results(Path dir) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("source", dir.toString().replace('\\', '/'));
        List<Map<String, String>> checks = readCsv(dir.resolve("summary.csv"));
        root.put("available", !checks.isEmpty());
        root.put("checks", checks);

        Map<String, List<String>> notes = new LinkedHashMap<>();
        for (Map<String, String> c : checks) {
            String scenario = c.get("scenario");
            if (scenario != null && !notes.containsKey(scenario)) {
                notes.put(scenario, measurements(dir.resolve(scenario).resolve("checks.txt")));
            }
        }
        root.put("notes", notes);
        root.put("load", readCsv(dir.resolve("s7-load").resolve("summary.csv")));
        return JsonCodec.encode(root);
    }

    private static List<String> measurements(Path checksTxt) {
        try {
            return Files.readAllLines(checksTxt, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("PASS") && !l.startsWith("FAIL"))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** A small RFC 4180 reader (quoted fields, doubled quotes); rows as header -> value maps. */
    static List<Map<String, String>> readCsv(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> header = null;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            List<String> cells = parseCsvLine(line);
            if (header == null) {
                header = cells;
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), i < cells.size() ? cells.get(i) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    // ------------------------------------------------------------------ HTTP

    public HttpServer startHttp(String bind, int port) throws IOException {
        byte[] page = resource("/web/index.html");
        HttpServer http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        http.createContext("/api/state", ex -> send(ex, 200, "application/json; charset=utf-8",
                snapshot.getBytes(StandardCharsets.UTF_8)));
        http.createContext("/api/results", ex -> send(ex, 200, "application/json; charset=utf-8",
                results(resultsDir == null ? defaultResultsDir() : resultsDir).getBytes(StandardCharsets.UTF_8)));
        http.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if ("/".equals(path) || "/index.html".equals(path)) {
                send(ex, 200, "text/html; charset=utf-8", page);
            } else {
                send(ex, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            }
        });
        http.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "web-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        return http;
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            status = 405;
            body = new byte[0];
        }
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = WebDashboard.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing resource " + name + " (rebuild the jar)");
            }
            return in.readAllBytes();
        }
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("config/airports.json");
        String host = "localhost";
        String bind = "127.0.0.1";
        int port = 8080;
        Path results = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = Path.of(args[++i]);
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--bind" -> bind = args[++i];
                case "--results" -> results = Path.of(args[++i]);
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.err.println("usage: WebDashboard [--config file] [--host h] [--port 8080] [--bind 127.0.0.1] [--results dir]");
                    System.exit(2);
                }
            }
        }
        AtcConfig config = AtcConfig.load(configPath);
        WebDashboard web = new WebDashboard(config, Monitor.endpointsFrom(config, host), AtcClock.SYSTEM, results);
        HttpServer http = web.startHttp(bind, port);
        web.log.info("web dashboard on http://%s:%d/ (listening to %s)",
                "0.0.0.0".equals(bind) ? "localhost" : bind, port, web.endpoints);
        Thread loop = new Thread(web, "web-loop");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.stop();
            http.stop(0);
            try {
                loop.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));
        loop.start();
        loop.join();
    }
}
