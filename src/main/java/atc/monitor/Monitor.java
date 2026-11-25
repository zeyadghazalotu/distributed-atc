package atc.monitor;

import atc.common.AtcClock;
import atc.common.AtcConfig;
import atc.common.AtcConfig.AirportConfig;
import atc.common.CodecException;
import atc.common.Event;
import atc.common.JsonCodec;
import atc.common.Log;
import atc.common.Message;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The Monitor process: a console dashboard of the whole system, refreshed every second.
 *
 * It only listens. One SUB socket connects to the allocator's PUB and every control service's
 * PUB (ZeroMQ lets one SUB connect to many publishers). It never sends anything, so a crashed
 * or slow Monitor cannot affect air traffic control.
 *
 * Run: java -cp target/atc.jar atc.monitor.Monitor
 *          [--config config/airports.json] [--host localhost] [--interval-ms 1000]
 *          [--no-clear] [--once] [--events-log results/monitor-events.log]
 * --no-clear: print frames one after another (for terminals without ANSI support, or to keep a record).
 * --once: listen for ~2.5 s, print a single frame and exit (handy for screenshots).
 */
public final class Monitor implements Runnable {
    private static final String CLEAR = "\033[H\033[2J";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final List<String> endpoints;
    private final DashboardRenderer renderer;
    private final long intervalMs;
    private final boolean clear;
    private final boolean once;
    private final PrintStream out;
    private final Path eventsLog;
    private final AtcClock clock;
    private final Log log = new Log("monitor");

    private volatile boolean running = true;
    private volatile String lastFrame = "";

    /**
     * @param out       where frames go (null = render but don't print; used by tests)
     * @param eventsLog optional file that every non-STATE event is appended to (evidence)
     */
    public Monitor(AtcConfig config, List<String> endpoints, long intervalMs, boolean clear, boolean once,
                   PrintStream out, Path eventsLog, AtcClock clock) {
        this.endpoints = endpoints;
        this.renderer = new DashboardRenderer(config);
        this.intervalMs = intervalMs;
        this.clear = clear;
        this.once = once;
        this.out = out;
        this.eventsLog = eventsLog;
        this.clock = clock;
    }

    /** Allocator PUB plus every control service's event PUB from the config. */
    public static List<String> endpointsFrom(AtcConfig config, String host) {
        List<String> list = new ArrayList<>();
        list.add("tcp://" + host + ":" + config.allocator().pubPort());
        for (AirportConfig a : config.airports()) {
            list.add("tcp://" + host + ":" + a.eventPubPort());
        }
        return list;
    }

    @Override
    public void run() {
        MonitorState state = new MonitorState();
        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket sub = ctx.createSocket(SocketType.SUB);
            sub.setLinger(0);
            sub.subscribe(new byte[0]);
            for (String e : endpoints) {
                sub.connect(e); // fine even if that service is not running yet: ZeroMQ reconnects
            }
            ZMQ.Poller poller = ctx.createPoller(1);
            poller.register(sub, ZMQ.Poller.POLLIN);

            long start = clock.nowMillis();
            long nextFrame = once ? start + 2500 : start;
            while (running && !Thread.currentThread().isInterrupted()) {
                long now = clock.nowMillis();
                if (now >= nextFrame) {
                    show(renderer.render(state, now));
                    if (once) {
                        break;
                    }
                    nextFrame = now + intervalMs;
                }
                if (poller.poll(Math.max(1, Math.min(100, nextFrame - now))) > 0) {
                    byte[] data;
                    while ((data = sub.recv(ZMQ.DONTWAIT)) != null) { // drain everything queued
                        receive(state, data);
                    }
                }
            }
        }
    }

    private void receive(MonitorState state, byte[] data) {
        Message m;
        try {
            m = JsonCodec.decode(data);
        } catch (CodecException e) {
            state.onMalformed();
            return;
        }
        state.onMessage(m, clock.nowMillis());
        if (eventsLog != null && m instanceof Event e && !"STATE".equals(e.kind()) && !"METRICS".equals(e.kind())) {
            String line = LocalDateTime.now().format(TS) + " " + e.service() + " " + e.kind() + " "
                    + MonitorState.summarize(e) + System.lineSeparator();
            try {
                Files.writeString(eventsLog, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                log.warn("cannot write events log %s: %s", eventsLog, ex.getMessage());
            }
        }
    }

    private void show(String frame) {
        lastFrame = frame;
        if (out == null) {
            return;
        }
        if (clear) {
            out.print(CLEAR);
        } else {
            out.println();
        }
        out.print(frame);
        out.flush();
    }

    public void stop() {
        running = false;
    }

    /** The most recently rendered frame (for tests). */
    public String lastFrame() {
        return lastFrame;
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("config/airports.json");
        String host = "localhost";
        long intervalMs = 1000;
        boolean clear = true;
        boolean once = false;
        Path eventsLog = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = Path.of(args[++i]);
                case "--host" -> host = args[++i];
                case "--interval-ms" -> intervalMs = Long.parseLong(args[++i]);
                case "--no-clear" -> clear = false;
                case "--once" -> once = true;
                case "--events-log" -> eventsLog = Path.of(args[++i]);
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.err.println("usage: Monitor [--config file] [--host h] [--interval-ms n] [--no-clear] [--once] [--events-log file]");
                    System.exit(2);
                }
            }
        }
        if (eventsLog != null && eventsLog.toAbsolutePath().getParent() != null) {
            Files.createDirectories(eventsLog.toAbsolutePath().getParent());
        }
        AtcConfig config = AtcConfig.load(configPath);
        // UTF-8 output regardless of the platform default (Cp1252 here).
        PrintStream console = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Monitor monitor = new Monitor(config, endpointsFrom(config, host), intervalMs, clear && !once, once,
                console, eventsLog, AtcClock.SYSTEM);
        Thread loop = new Thread(monitor, "monitor-loop");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            monitor.stop();
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
