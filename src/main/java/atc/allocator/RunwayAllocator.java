package atc.allocator;

import atc.common.AtcClock;
import atc.common.AtcConfig;
import atc.common.CodecException;
import atc.common.ErrorMessage;
import atc.common.Event;
import atc.common.Heartbeat;
import atc.common.JsonCodec;
import atc.common.Log;
import atc.common.Message;
import atc.common.ProcessMetrics;
import atc.common.Reservation;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The RunwayAllocator process: the single authority for runway reservations at both airports.
 *
 * Sockets: ROUTER (bind) for requests from control services, PUB (bind) for heartbeats and
 * events. ONE thread owns both sockets and the {@link AllocatorCore}: ZeroMQ sockets are not
 * thread-safe, and serial processing is what makes allocation decisions atomic.
 *
 * Run: java -cp target/atc.jar atc.allocator.RunwayAllocator
 *          [--config config/airports.json] [--log data/allocator-log.jsonl]
 *          [--router-port 5555] [--pub-port 5556] [--time-scale 1]
 */
public final class RunwayAllocator implements Runnable {
    public static final String SERVICE = "allocator";
    private static final Log LOG = new Log(SERVICE);

    private final AllocatorCore core;
    private final int routerPort;
    private final int pubPort;
    private final long heartbeatMs;
    private final AtcClock clock;
    /** Identifies this incarnation of the allocator; changes on restart (see Heartbeat). */
    private final long startedAt;
    /** Requests processed (for METRICS); only touched by the loop thread. */
    private long handled;

    private volatile boolean running = true;
    private volatile int boundRouterPort = -1;
    private volatile int boundPubPort = -1;
    private final CountDownLatch bound = new CountDownLatch(1);

    /** Ports of 0 bind to a random free port (used by tests). */
    public RunwayAllocator(AllocatorCore core, int routerPort, int pubPort, long heartbeatMs, AtcClock clock) {
        this.core = core;
        this.routerPort = routerPort;
        this.pubPort = pubPort;
        this.heartbeatMs = heartbeatMs;
        this.clock = clock;
        this.startedAt = clock.nowMillis();
    }

    @Override
    public void run() {
        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket router = ctx.createSocket(SocketType.ROUTER);
            ZMQ.Socket pub = ctx.createSocket(SocketType.PUB);
            boundRouterPort = bind(router, routerPort);
            boundPubPort = bind(pub, pubPort);
            bound.countDown();
            LOG.info("listening: ROUTER tcp://*:%d, PUB tcp://*:%d", boundRouterPort, boundPubPort);

            ZMQ.Poller poller = ctx.createPoller(1);
            poller.register(router, ZMQ.Poller.POLLIN);
            long nextHeartbeat = 0;
            while (running && !Thread.currentThread().isInterrupted()) {
                long now = clock.nowMillis();
                if (now >= nextHeartbeat) {
                    publish(pub, new Heartbeat(SERVICE, now, startedAt));
                    publish(pub, new Event(SERVICE, "METRICS",
                            JsonCodec.encode(ProcessMetrics.sample(startedAt, handled)), now));
                    nextHeartbeat = now + heartbeatMs;
                }
                // Poll no longer than until the next heartbeat, so heartbeats keep their period.
                if (poller.poll(Math.max(1, nextHeartbeat - now)) > 0 && poller.pollin(0)) {
                    handleOne(router, pub);
                }
            }
        } catch (RuntimeException e) {
            LOG.error("allocator loop stopped: %s", e);
            throw e;
        } finally {
            bound.countDown();
            LOG.info("stopped");
        }
    }

    private static int bind(ZMQ.Socket socket, int port) {
        if (port == 0) {
            return socket.bindToRandomPort("tcp://127.0.0.1");
        }
        socket.bind("tcp://*:" + port);
        return port;
    }

    /**
     * ROUTER frames: [client identity, (empty delimiter if REQ/DEALER-with-delimiter), payload].
     * We keep every frame before the payload as the envelope and send it back unchanged, so
     * both REQ and DEALER clients are routed correctly.
     */
    private void handleOne(ZMQ.Socket router, ZMQ.Socket pub) {
        ZMsg msg = ZMsg.recvMsg(router, ZMQ.DONTWAIT);
        if (msg == null) {
            return;
        }
        if (msg.size() < 2) {
            msg.destroy(); // no identity + payload: nothing we can reply to
            return;
        }
        ZFrame payload = msg.removeLast();
        Message reply = process(payload.getData(), pub);
        payload.destroy();
        msg.add(JsonCodec.encodeBytes(reply));
        msg.send(router);
    }

    /** Decode, decide, and build the reply. Never throws: bad input gets an ERROR reply. */
    private Message process(byte[] data, ZMQ.Socket pub) {
        handled++;
        Message request;
        try {
            request = JsonCodec.decode(data);
        } catch (CodecException e) {
            LOG.warn("rejecting malformed message: %s", e.getMessage());
            return new ErrorMessage(e.getMessage());
        }
        try {
            AllocatorCore.Outcome outcome = core.handle(request);
            for (ReservationRecord b : outcome.bumped()) {
                // Control services watch for BUMPED to send the affected aircraft a new INSTRUCTION.
                publish(pub, new Event(SERVICE, "BUMPED", JsonCodec.encode(b.toReply()), clock.nowMillis()));
            }
            if (outcome.reply() instanceof Reservation r) {
                LOG.info("%s %s -> %s %s", request.getClass().getSimpleName().toUpperCase(), r.requestId(),
                        r.state(), r.runwayId() == null ? "" : r.runwayId());
                publish(pub, new Event(SERVICE, "RESERVATION", JsonCodec.encode(r), clock.nowMillis()));
            }
            return outcome.reply();
        } catch (RuntimeException e) {
            LOG.error("internal error handling %s: %s", request, e);
            return new ErrorMessage("internal error: " + e.getMessage());
        }
    }

    private static void publish(ZMQ.Socket pub, Message m) {
        pub.send(JsonCodec.encodeBytes(m), ZMQ.DONTWAIT);
    }

    public void stop() {
        running = false;
    }

    /** Blocks until sockets are bound (or startup failed). */
    public boolean awaitBound(long timeoutMs) throws InterruptedException {
        return bound.await(timeoutMs, TimeUnit.MILLISECONDS) && boundRouterPort > 0;
    }

    public int routerPort() {
        return boundRouterPort;
    }

    public int pubPort() {
        return boundPubPort;
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("config/airports.json");
        Path logPath = Path.of("data/allocator-log.jsonl");
        Integer routerPort = null;
        Integer pubPort = null;
        Integer timeScale = null;
        boolean fsync = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = Path.of(args[++i]);
                case "--log" -> logPath = Path.of(args[++i]);
                case "--router-port" -> routerPort = Integer.parseInt(args[++i]);
                case "--pub-port" -> pubPort = Integer.parseInt(args[++i]);
                case "--time-scale" -> timeScale = Integer.parseInt(args[++i]);
                case "--unsafe-no-fsync" -> fsync = false;
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.err.println("usage: RunwayAllocator [--config file] [--log file] [--router-port n] [--pub-port n] [--time-scale n] [--unsafe-no-fsync]");
                    System.exit(2);
                }
            }
        }
        AtcConfig config = AtcConfig.load(configPath);
        if (timeScale != null) {
            config = config.withTimeScale(timeScale);
        }
        if (!fsync) {
            LOG.warn("--unsafe-no-fsync: decisions are NOT forced to disk; invariant 5 does not hold "
                    + "(load-test comparison only)");
        }
        ReservationLog log = ReservationLog.open(logPath, fsync);
        AllocatorCore core = new AllocatorCore(config, AtcClock.SYSTEM, log);
        RunwayAllocator allocator = new RunwayAllocator(core,
                routerPort != null ? routerPort : config.allocator().routerPort(),
                pubPort != null ? pubPort : config.allocator().pubPort(),
                config.timing().heartbeatMs(), AtcClock.SYSTEM);

        Thread loop = new Thread(allocator, "allocator-loop");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            allocator.stop();
            try {
                loop.join(3000);
                log.close();
            } catch (Exception ignored) {
                // shutting down anyway
            }
        }));
        LOG.info("starting with config %s, log %s, time scale %dx (slot %d ms, buffer %d ms)", configPath, logPath,
                config.timing().scale(), config.timing().slotMs(), config.timing().bufferMs());
        loop.start();
        loop.join();
    }
}
