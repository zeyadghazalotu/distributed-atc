package atc.control;

import atc.common.Ack;
import atc.common.AtcClock;
import atc.common.AtcConfig;
import atc.common.AtcConfig.AirportConfig;
import atc.common.CodecException;
import atc.common.Complete;
import atc.common.ErrorMessage;
import atc.common.JsonCodec;
import atc.common.Log;
import atc.common.Message;
import atc.common.PositionUpdate;
import atc.common.RunwayRequest;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The ControlService process for one airport. All sockets and the {@link ControlCore} are owned
 * by one thread (ZeroMQ sockets are not thread-safe; serial handling keeps the core lock-free).
 *
 * <pre>
 *   aircraft DEALER  --->  ROUTER  (bind, controlRouterPort)  requests / acks / completes, and pushes back
 *   aircraft PUB     --->  SUB     (bind, positionSubPort)    position updates
 *                          PUB     (bind, eventPubPort)       heartbeats, events, STATE snapshots -> Monitor
 *                          DEALER  (connect allocator ROUTER) [key, command] -> [key, reply]
 *                          SUB     (connect allocator PUB)    allocator heartbeats + BUMPED events
 * </pre>
 *
 * Aircraft use DEALER rather than REQ because the control service must push instructions the
 * aircraft did not ask for (after a bump, or when a HELD request is finally decided). The
 * ROUTER remembers each aircraft's latest envelope, so an aircraft that recreates its socket
 * (lazy pirate) is reachable again as soon as it sends anything.
 *
 * Run: java -cp target/atc.jar atc.control.ControlService --airport YYZ
 *          [--config config/airports.json] [--timeout-ms 30000] [--allocator-host localhost]
 */
public final class ControlService implements Runnable {

    /** Socket endpoints; port 0 binds a random free port (tests). */
    public record Endpoints(int aircraftRouterPort, int positionSubPort, int eventPubPort,
                            String allocatorRouter, String allocatorPub) {
    }

    private final AirportConfig airport;
    private final Endpoints endpoints;
    private final long timeoutMs;
    private final long heartbeatMs;
    private final AtcClock clock;
    private final Log log;

    private volatile boolean running = true;
    private final CountDownLatch bound = new CountDownLatch(1);
    private volatile int boundRouterPort = -1;
    private volatile int boundPositionPort = -1;
    private volatile int boundEventPort = -1;

    public ControlService(AirportConfig airport, Endpoints endpoints, long timeoutMs, long heartbeatMs, AtcClock clock) {
        this.airport = airport;
        this.endpoints = endpoints;
        this.timeoutMs = timeoutMs;
        this.heartbeatMs = heartbeatMs;
        this.clock = clock;
        this.log = new Log("control-" + airport.id());
    }

    @Override
    public void run() {
        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket aircraftRouter = ctx.createSocket(SocketType.ROUTER);
            ZMQ.Socket positionSub = ctx.createSocket(SocketType.SUB);
            ZMQ.Socket eventPub = ctx.createSocket(SocketType.PUB);
            ZMQ.Socket allocDealer = ctx.createSocket(SocketType.DEALER);
            ZMQ.Socket allocSub = ctx.createSocket(SocketType.SUB);
            for (ZMQ.Socket s : List.of(aircraftRouter, positionSub, eventPub, allocDealer, allocSub)) {
                s.setLinger(0); // don't hang on shutdown with undeliverable messages
            }
            positionSub.subscribe(new byte[0]);
            allocSub.subscribe(new byte[0]);

            boundRouterPort = bind(aircraftRouter, endpoints.aircraftRouterPort());
            boundPositionPort = bind(positionSub, endpoints.positionSubPort());
            boundEventPort = bind(eventPub, endpoints.eventPubPort());
            allocDealer.connect(endpoints.allocatorRouter());
            allocSub.connect(endpoints.allocatorPub());
            bound.countDown();
            log.info("listening: aircraft ROUTER :%d, positions SUB :%d, events PUB :%d; allocator %s / %s",
                    boundRouterPort, boundPositionPort, boundEventPort,
                    endpoints.allocatorRouter(), endpoints.allocatorPub());

            /** aircraftId -> envelope frames of its latest message, for pushing to it later. */
            Map<String, List<byte[]>> aircraftEnvelopes = new HashMap<>();
            ControlCore core = new ControlCore(airport, timeoutMs, heartbeatMs, clock, new ControlCore.Output() {
                @Override
                public void toAircraft(String aircraftId, Message message) {
                    List<byte[]> envelope = aircraftEnvelopes.get(aircraftId);
                    if (envelope == null) {
                        log.warn("cannot push to %s: it has not connected yet", aircraftId);
                        return;
                    }
                    send(aircraftRouter, envelope, message);
                }

                @Override
                public boolean toAllocator(String key, Message command) {
                    // [key, payload]; the allocator echoes the key frame back with its reply.
                    // DONTWAIT: if the queue is full we report failure and the timeout retries.
                    return allocDealer.send(key.getBytes(StandardCharsets.UTF_8), ZMQ.SNDMORE | ZMQ.DONTWAIT)
                            && allocDealer.send(JsonCodec.encodeBytes(command), ZMQ.DONTWAIT);
                }

                @Override
                public void publish(Message message) {
                    eventPub.send(JsonCodec.encodeBytes(message), ZMQ.DONTWAIT);
                }
            });

            ZMQ.Poller poller = ctx.createPoller(4);
            int iAircraft = poller.register(aircraftRouter, ZMQ.Poller.POLLIN);
            int iPositions = poller.register(positionSub, ZMQ.Poller.POLLIN);
            int iAllocReply = poller.register(allocDealer, ZMQ.Poller.POLLIN);
            int iAllocPub = poller.register(allocSub, ZMQ.Poller.POLLIN);

            while (running && !Thread.currentThread().isInterrupted()) {
                core.tick();
                // Short poll so timeouts and heartbeats are checked promptly.
                if (poller.poll(50) <= 0) {
                    continue;
                }
                if (poller.pollin(iAircraft)) {
                    handleAircraft(aircraftRouter, core, aircraftEnvelopes);
                }
                if (poller.pollin(iPositions)) {
                    decode(positionSub.recv(ZMQ.DONTWAIT), "position").ifPresent(m -> {
                        if (m instanceof PositionUpdate p) {
                            core.onPosition(p);
                        }
                    });
                }
                if (poller.pollin(iAllocReply)) {
                    ZMsg reply = ZMsg.recvMsg(allocDealer, ZMQ.DONTWAIT);
                    if (reply != null && reply.size() >= 2) {
                        String key = reply.popString();
                        decode(reply.getLast().getData(), "allocator reply").ifPresent(m -> core.onAllocatorReply(key, m));
                    }
                }
                if (poller.pollin(iAllocPub)) {
                    decode(allocSub.recv(ZMQ.DONTWAIT), "allocator event").ifPresent(core::onAllocatorBroadcast);
                }
            }
        } catch (RuntimeException e) {
            log.error("control loop stopped: %s", e);
            throw e;
        } finally {
            bound.countDown();
            log.info("stopped");
        }
    }

    private void handleAircraft(ZMQ.Socket router, ControlCore core, Map<String, List<byte[]>> envelopes) {
        ZMsg msg = ZMsg.recvMsg(router, ZMQ.DONTWAIT);
        if (msg == null || msg.size() < 2) {
            return;
        }
        ZFrame payload = msg.removeLast();
        List<byte[]> envelope = new ArrayList<>();
        for (ZFrame f : msg) {
            envelope.add(f.getData());
        }
        Message reply;
        try {
            Message request = JsonCodec.decode(payload.getData());
            String aircraftId = aircraftIdOf(request);
            if (aircraftId != null) {
                envelopes.put(aircraftId, envelope); // latest socket of this aircraft
            }
            reply = core.onAircraftMessage(request);
        } catch (CodecException e) {
            log.warn("rejecting malformed aircraft message: %s", e.getMessage());
            reply = new ErrorMessage(e.getMessage());
        } catch (RuntimeException e) {
            log.error("internal error: %s", e);
            reply = new ErrorMessage("internal error: " + e.getMessage());
        }
        if (reply != null) {
            send(router, envelope, reply);
        }
    }

    private static String aircraftIdOf(Message m) {
        if (m instanceof RunwayRequest r) return r.aircraftId();
        if (m instanceof Ack a) return a.aircraftId();
        if (m instanceof Complete c) return c.aircraftId();
        return null;
    }

    private static void send(ZMQ.Socket router, List<byte[]> envelope, Message message) {
        ZMsg out = new ZMsg();
        envelope.forEach(out::add);
        out.add(JsonCodec.encodeBytes(message));
        out.send(router);
    }

    private java.util.Optional<Message> decode(byte[] data, String what) {
        if (data == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(JsonCodec.decode(data));
        } catch (CodecException e) {
            log.warn("ignoring malformed %s: %s", what, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private static int bind(ZMQ.Socket socket, int port) {
        if (port == 0) {
            return socket.bindToRandomPort("tcp://127.0.0.1");
        }
        socket.bind("tcp://*:" + port);
        return port;
    }

    public void stop() {
        running = false;
    }

    public boolean awaitBound(long timeoutMs) throws InterruptedException {
        return bound.await(timeoutMs, TimeUnit.MILLISECONDS) && boundRouterPort > 0;
    }

    public int aircraftRouterPort() {
        return boundRouterPort;
    }

    public int positionSubPort() {
        return boundPositionPort;
    }

    public int eventPubPort() {
        return boundEventPort;
    }

    public static void main(String[] args) throws Exception {
        String airportId = null;
        Path configPath = Path.of("config/airports.json");
        Integer timeoutOverride = null;
        String allocatorHost = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--airport" -> airportId = args[++i];
                case "--config" -> configPath = Path.of(args[++i]);
                case "--timeout-ms" -> timeoutOverride = Integer.parseInt(args[++i]);
                case "--allocator-host" -> allocatorHost = args[++i];
                default -> usage("unknown argument: " + args[i]);
            }
        }
        if (airportId == null) {
            usage("--airport is required");
        }
        AtcConfig config = AtcConfig.load(configPath);
        String id = airportId;
        AirportConfig airport = config.airport(id).orElseThrow(() -> new IllegalArgumentException("unknown airport " + id));
        String host = allocatorHost != null ? allocatorHost : config.allocator().host();
        Endpoints endpoints = new Endpoints(airport.controlRouterPort(), airport.positionSubPort(), airport.eventPubPort(),
                "tcp://" + host + ":" + config.allocator().routerPort(),
                "tcp://" + host + ":" + config.allocator().pubPort());
        long timeoutMs = timeoutOverride != null ? timeoutOverride : config.timing().receiveTimeoutMs();

        ControlService service = new ControlService(airport, endpoints, timeoutMs, config.timing().heartbeatMs(), AtcClock.SYSTEM);
        Thread loop = new Thread(service, "control-" + id + "-loop");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            service.stop();
            try {
                loop.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));
        loop.start();
        loop.join();
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("usage: ControlService --airport YYZ|YOW [--config file] [--timeout-ms n] [--allocator-host h]");
        System.exit(2);
    }
}
