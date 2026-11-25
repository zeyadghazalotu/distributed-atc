package atc.control;

import atc.allocator.AllocatorCore;
import atc.allocator.ReservationLog;
import atc.allocator.RunwayAllocator;
import atc.allocator.TestFixtures;
import atc.common.Ack;
import atc.common.AtcConfig;
import atc.common.Complete;
import atc.common.ControlState;
import atc.common.Event;
import atc.common.Instruction;
import atc.common.InstructionStatus;
import atc.common.JsonCodec;
import atc.common.Message;
import atc.common.Operation;
import atc.common.Phase;
import atc.common.PositionUpdate;
import atc.common.RunwayRequest;
import atc.common.SizeClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real processes-in-threads over TCP: aircraft DEALER -> ControlService -> RunwayAllocator. */
class ControlServiceTest {
    @TempDir
    Path dir;

    private final AtcConfig config = TestFixtures.config();
    private final List<Runnable> cleanup = new ArrayList<>();
    private final ZContext ctx = new ZContext();

    @AfterEach
    void tearDown() {
        ctx.close();
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            cleanup.get(i).run();
        }
    }

    private RunwayAllocator startAllocator(int routerPort, int pubPort) throws Exception {
        ReservationLog log = ReservationLog.open(dir.resolve("alloc.jsonl"));
        AllocatorCore core = new AllocatorCore(config, System::currentTimeMillis, log);
        RunwayAllocator allocator = new RunwayAllocator(core, routerPort, pubPort, 100, System::currentTimeMillis);
        Thread t = new Thread(allocator, "test-allocator");
        t.start();
        assertTrue(allocator.awaitBound(5000));
        cleanup.add(() -> {
            allocator.stop();
            join(t);
            try {
                log.close();
            } catch (Exception ignored) {
                // test teardown
            }
        });
        return allocator;
    }

    private ControlService startControl(int allocRouterPort, int allocPubPort, long timeoutMs) throws Exception {
        ControlService.Endpoints ep = new ControlService.Endpoints(0, 0, 0,
                "tcp://127.0.0.1:" + allocRouterPort, "tcp://127.0.0.1:" + allocPubPort);
        ControlService control = new ControlService(config.airport("AAA").orElseThrow(), ep, timeoutMs, 100,
                System::currentTimeMillis);
        Thread t = new Thread(control, "test-control");
        t.start();
        assertTrue(control.awaitBound(5000));
        cleanup.add(() -> {
            control.stop();
            join(t);
        });
        return control;
    }

    private static void join(Thread t) {
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ZMQ.Socket aircraft(ControlService control) {
        ZMQ.Socket dealer = ctx.createSocket(SocketType.DEALER);
        dealer.setReceiveTimeOut(5000);
        dealer.connect("tcp://127.0.0.1:" + control.aircraftRouterPort());
        return dealer;
    }

    private static void send(ZMQ.Socket s, Message m) {
        s.send(JsonCodec.encodeBytes(m));
    }

    private static Message recv(ZMQ.Socket s) throws Exception {
        byte[] data = s.recv();
        assertNotNull(data, "no message within timeout");
        return JsonCodec.decode(data);
    }

    /** Read instructions until one has the wanted status (earlier HELD ones are allowed). */
    private static Instruction awaitStatus(ZMQ.Socket s, InstructionStatus wanted, List<Instruction> seen) throws Exception {
        while (true) {
            Instruction i = assertInstanceOf(Instruction.class, recv(s));
            seen.add(i);
            if (i.status() == wanted) {
                return i;
            }
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void aircraftGetsAssignmentAcksAndCompletesOverTcp() throws Exception {
        RunwayAllocator allocator = startAllocator(0, 0);
        ControlService control = startControl(allocator.routerPort(), allocator.pubPort(), 2000);
        ZMQ.Socket ac = aircraft(control);

        send(ac, new RunwayRequest("t1", "AC1", "AAA", Operation.TAKEOFF, SizeClass.SMALL, false, 0));
        Instruction assigned = awaitStatus(ac, InstructionStatus.ASSIGNED, new ArrayList<>());
        assertEquals("AAA-S1", assigned.runwayId());
        assertEquals("AAA-A", assigned.taxiwayId());

        send(ac, new Ack("t1", "AC1"));
        assertEquals(assigned, recv(ac), "ack receipt is the current instruction");
        send(ac, new Complete("t1", "AC1"));
        assertEquals(new Complete("t1", "AC1"), recv(ac));

        send(ac, new atc.common.Confirm("not-for-aircraft"));
        assertInstanceOf(atc.common.ErrorMessage.class, recv(ac));
        ac.send("garbage");
        assertInstanceOf(atc.common.ErrorMessage.class, recv(ac), "malformed input gets ERROR, service survives");
    }

    @Test
    void positionsReachTheMonitorFeedAsStateSnapshots() throws Exception {
        RunwayAllocator allocator = startAllocator(0, 0);
        ControlService control = startControl(allocator.routerPort(), allocator.pubPort(), 2000);

        ZMQ.Socket monitor = ctx.createSocket(SocketType.SUB);
        monitor.subscribe(new byte[0]);
        monitor.setReceiveTimeOut(200);
        monitor.connect("tcp://127.0.0.1:" + control.eventPubPort());
        ZMQ.Socket pub = ctx.createSocket(SocketType.PUB);
        pub.connect("tcp://127.0.0.1:" + control.positionSubPort());

        long deadline = System.currentTimeMillis() + 5000;
        long seq = 0;
        while (System.currentTimeMillis() < deadline) {
            // Keep publishing: PUB drops messages until the connection is up (slow joiner).
            send(pub, new PositionUpdate("AC9", "AAA", ++seq, Phase.APPROACH, 1, 2, 3000, 160, System.currentTimeMillis()));
            byte[] data = monitor.recv();
            if (data != null && JsonCodec.decode(data) instanceof Event e && e.kind().equals("STATE")) {
                ControlState s = JsonCodec.decode(e.detail(), ControlState.class);
                if (s.aircraft().stream().anyMatch(p -> p.aircraftId().equals("AC9"))) {
                    assertTrue(s.allocatorReachable());
                    return;
                }
            }
        }
        throw new AssertionError("STATE snapshot with AC9 never arrived");
    }

    @Test
    void withAllocatorDownRequestsAreHeldThenAssignedWhenItStarts() throws Exception {
        int routerPort = freePort();
        int pubPort = freePort();
        ControlService control = startControl(routerPort, pubPort, 500); // nothing listening yet
        ZMQ.Socket ac = aircraft(control);

        send(ac, new RunwayRequest("h1", "AC1", "AAA", Operation.LANDING, SizeClass.MEDIUM, false, 0));
        Instruction first = assertInstanceOf(Instruction.class, recv(ac));
        assertEquals(InstructionStatus.HELD, first.status());

        // Give the control service time to time out and retry a few times: still nothing assigned.
        ac.setReceiveTimeOut(1500);
        byte[] early = ac.recv();
        if (early != null) {
            assertEquals(InstructionStatus.HELD, ((Instruction) JsonCodec.decode(early)).status());
        }

        startAllocator(routerPort, pubPort);
        ac.setReceiveTimeOut(5000);
        List<Instruction> seen = new ArrayList<>();
        Instruction assigned = awaitStatus(ac, InstructionStatus.ASSIGNED, seen);
        assertEquals("AAA-M1", assigned.runwayId());
        assertTrue(seen.stream().limit(seen.size() - 1).allMatch(i -> i.status() == InstructionStatus.HELD));
    }
}
