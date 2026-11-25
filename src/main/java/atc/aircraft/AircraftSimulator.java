package atc.aircraft;

import atc.common.AtcClock;
import atc.common.AtcConfig;
import atc.common.AtcConfig.AirportConfig;
import atc.common.CodecException;
import atc.common.JsonCodec;
import atc.common.Log;
import atc.common.Message;
import atc.common.PositionUpdate;
import atc.common.SizeClass;
import atc.allocator.Eligibility;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The AircraftSimulator process: runs N {@link AircraftAgent}s for one airport.
 *
 * Sockets: one DEALER per aircraft to the control service's ROUTER (so each aircraft can be
 * reconnected independently, lazy-pirate style) and one shared PUB to the control service's
 * position SUB. One thread owns all sockets and agents.
 *
 * Run: java -cp target/atc.jar atc.aircraft.AircraftSimulator --airport YYZ
 *          [--count 5] [--cycles 0] [--time-scale 1] [--seed 1] [--retry-ms n]
 *          [--sizes SMALL,MEDIUM,HEAVY] [--emergency YYZ-AC3,...] [--control-host localhost]
 *          [--config config/airports.json]
 * --cycles N: each aircraft performs N runway operations then stops; the process exits when
 * all are done (0 = run forever).
 */
public final class AircraftSimulator implements Runnable {

    private final AirportConfig airport;
    private final SimParams params;
    private final int count;
    private final int maxOps;
    private final long seed;
    private final List<SizeClass> sizes;
    private final java.util.Set<String> emergencies;
    private final String routerEndpoint;
    private final String positionEndpoint;
    private final AtcClock clock;
    private final Log log;
    private volatile boolean running = true;
    private volatile List<AircraftAgent> agents = List.of();

    public AircraftSimulator(AirportConfig airport, SimParams params, int count, int maxOps, long seed,
                             List<SizeClass> sizes, String routerEndpoint, String positionEndpoint, AtcClock clock) {
        this(airport, params, count, maxOps, seed, sizes, java.util.Set.of(), routerEndpoint, positionEndpoint, clock);
    }

    /** @param emergencies aircraft ids that declare an emergency on their landing requests */
    public AircraftSimulator(AirportConfig airport, SimParams params, int count, int maxOps, long seed,
                             List<SizeClass> sizes, java.util.Set<String> emergencies,
                             String routerEndpoint, String positionEndpoint, AtcClock clock) {
        this.emergencies = emergencies;
        this.airport = airport;
        this.params = params;
        this.count = count;
        this.maxOps = maxOps;
        this.seed = seed;
        this.sizes = sizes == null || sizes.isEmpty() ? eligibleSizes(airport) : sizes;
        this.routerEndpoint = routerEndpoint;
        this.positionEndpoint = positionEndpoint;
        this.clock = clock;
        this.log = new Log("sim-" + airport.id());
    }

    /** Size classes that at least one runway at this airport can take (so defaults are never rejected). */
    static List<SizeClass> eligibleSizes(AirportConfig airport) {
        return Arrays.stream(SizeClass.values())
                .filter(s -> airport.runways().stream().anyMatch(r -> Eligibility.isEligible(r, s)))
                .toList();
    }

    @Override
    public void run() {
        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket pub = ctx.createSocket(SocketType.PUB);
            pub.setLinger(500); // let the final position updates go out on exit
            pub.connect(positionEndpoint);

            Map<String, ZMQ.Socket> sockets = new LinkedHashMap<>();
            boolean[] socketsChanged = {true};
            AircraftAgent.Output output = new AircraftAgent.Output() {
                @Override
                public void send(String aircraftId, Message message, boolean reconnect) {
                    ZMQ.Socket s = sockets.get(aircraftId);
                    if (reconnect || s == null) {
                        // Lazy pirate: a socket may be stuck on a dead peer; start clean.
                        if (s != null) {
                            ctx.destroySocket(s);
                        }
                        s = newDealer(ctx);
                        sockets.put(aircraftId, s);
                        socketsChanged[0] = true;
                    }
                    s.send(JsonCodec.encodeBytes(message), ZMQ.DONTWAIT);
                }

                @Override
                public void publish(PositionUpdate position) {
                    pub.send(JsonCodec.encodeBytes(position), ZMQ.DONTWAIT);
                }
            };

            long now = clock.nowMillis();
            // Unique per run: requestIds must never repeat across runs (the allocator remembers
            // them), and seq must keep increasing across restarts (control service seq filter).
            String runId = Long.toString(now, 36);
            Random rnd = new Random(seed);
            List<AircraftAgent> list = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String id = airport.id() + "-AC" + (i + 1);
                SizeClass size = sizes.get(i % sizes.size());
                boolean arrival = i % 2 == 0;
                boolean emergency = emergencies.contains(id);
                list.add(new AircraftAgent(id, airport.id(), size, i, params, output, new Random(rnd.nextLong()),
                        runId, now, maxOps, arrival, emergency, now));
                log.info("%s %s starts %s%s", id, size, arrival ? "on approach" : "parked",
                        emergency ? " (EMERGENCY on landing)" : "");
            }
            agents = List.copyOf(list);

            ZMQ.Poller poller = null;
            List<String> order = new ArrayList<>();
            while (running && !Thread.currentThread().isInterrupted()) {
                now = clock.nowMillis();
                for (AircraftAgent a : agents) {
                    a.tick(now);
                }
                if (maxOps > 0 && agents.stream().allMatch(AircraftAgent::isFinished)) {
                    log.info("all %d aircraft finished %d operation(s) each", count, maxOps);
                    break;
                }
                if (socketsChanged[0]) {
                    if (poller != null) {
                        poller.close();
                    }
                    poller = ctx.createPoller(sockets.size());
                    order.clear();
                    for (Map.Entry<String, ZMQ.Socket> e : sockets.entrySet()) {
                        poller.register(e.getValue(), ZMQ.Poller.POLLIN);
                        order.add(e.getKey());
                    }
                    socketsChanged[0] = false;
                }
                if (order.isEmpty()) {
                    sleepQuietly(50); // no sockets yet (all aircraft parked): nothing to poll
                    continue;
                }
                if (poller.poll(50) <= 0) {
                    continue;
                }
                for (int i = 0; i < order.size(); i++) {
                    if (!poller.pollin(i)) {
                        continue;
                    }
                    byte[] data = sockets.get(order.get(i)).recv(ZMQ.DONTWAIT);
                    AircraftAgent agent = agentById(order.get(i));
                    if (data == null || agent == null) {
                        continue;
                    }
                    try {
                        agent.onMessage(JsonCodec.decode(data), clock.nowMillis());
                    } catch (CodecException e) {
                        log.warn("%s ignoring malformed message: %s", agent.id(), e.getMessage());
                    }
                }
            }
            // Final positions so the control service / monitor see the end state.
            now = clock.nowMillis();
            for (AircraftAgent a : agents) {
                a.tick(now + params.positionPeriodMs());
            }
        } finally {
            log.info("stopped");
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ZMQ.Socket newDealer(ZContext ctx) {
        ZMQ.Socket s = ctx.createSocket(SocketType.DEALER);
        s.setLinger(0);
        s.connect(routerEndpoint);
        return s;
    }

    private AircraftAgent agentById(String id) {
        for (AircraftAgent a : agents) {
            if (a.id().equals(id)) {
                return a;
            }
        }
        return null;
    }

    public void stop() {
        running = false;
    }

    public List<AircraftAgent> agents() {
        return agents;
    }

    public static void main(String[] args) throws Exception {
        String airportId = null;
        Path configPath = Path.of("config/airports.json");
        Integer count = null;
        int cycles = 0;
        Integer timeScale = null;
        long seed = 1;
        Long retryMs = null;
        List<SizeClass> sizes = null;
        String host = "localhost";
        java.util.Set<String> emergencies = java.util.Set.of();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--airport" -> airportId = args[++i];
                case "--config" -> configPath = Path.of(args[++i]);
                case "--count" -> count = Integer.parseInt(args[++i]);
                case "--cycles" -> cycles = Integer.parseInt(args[++i]);
                case "--time-scale" -> timeScale = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--retry-ms" -> retryMs = Long.parseLong(args[++i]);
                case "--sizes" -> sizes = Arrays.stream(args[++i].split(",")).map(String::trim).map(SizeClass::valueOf).toList();
                case "--control-host" -> host = args[++i];
                case "--emergency" -> emergencies = java.util.Set.of(args[++i].split(","));
                default -> usage("unknown argument: " + args[i]);
            }
        }
        if (airportId == null) {
            usage("--airport is required");
        }
        AtcConfig config = AtcConfig.load(configPath);
        if (timeScale != null) {
            config = config.withTimeScale(timeScale);
        }
        String id = airportId;
        AirportConfig airport = config.airport(id).orElseThrow(() -> new IllegalArgumentException("unknown airport " + id));
        SimParams params = SimParams.defaults(config.timing());
        if (retryMs != null) {
            params = params.withRetryMs(retryMs);
        }
        AircraftSimulator sim = new AircraftSimulator(airport, params, count != null ? count : airport.aircraftCount(),
                cycles, seed, sizes, emergencies, "tcp://" + host + ":" + airport.controlRouterPort(),
                "tcp://" + host + ":" + airport.positionSubPort(), AtcClock.SYSTEM);
        Thread loop = new Thread(sim, "sim-" + id + "-loop");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sim.stop();
            try {
                loop.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));
        new Log("sim-" + id).info("starting %d aircraft, time scale %dx, retry %d ms, cycles %s",
                sim.count, config.timing().scale(), params.retryMs(), cycles == 0 ? "unlimited" : cycles);
        loop.start();
        loop.join();
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("usage: AircraftSimulator --airport YYZ|YOW [--count n] [--cycles n] [--time-scale n] "
                + "[--seed n] [--retry-ms n] [--sizes SMALL,MEDIUM,HEAVY] [--emergency id,...] [--control-host h] [--config file]");
        System.exit(2);
    }
}
