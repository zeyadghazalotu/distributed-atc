package atc.control;

import atc.allocator.AllocatorCore;
import atc.allocator.InvariantChecker;
import atc.allocator.ReservationLog;
import atc.allocator.ReservationRecord;
import atc.allocator.RunwayAllocator;
import atc.allocator.TestFixtures;
import atc.common.Ack;
import atc.common.AtcConfig;
import atc.common.Instruction;
import atc.common.InstructionStatus;
import atc.common.JsonCodec;
import atc.common.Message;
import atc.common.Operation;
import atc.common.ReservationState;
import atc.common.RunwayRequest;
import atc.common.SizeClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariants 5-7 over TCP: the allocator dies mid-run and restarts from its log while the
 * control service and aircraft keep running.
 */
class AllocatorRestartTest {
    @TempDir
    Path dir;

    private final AtcConfig config = TestFixtures.config();

    /** One allocator incarnation (thread + log) on fixed ports. */
    private record Incarnation(RunwayAllocator allocator, Thread thread, ReservationLog log, AllocatorCore core) {
        void stop() throws Exception {
            allocator.stop();
            thread.join(5000);
            log.close();
        }
    }

    private Incarnation startAllocator(int routerPort, int pubPort) throws Exception {
        ReservationLog log = ReservationLog.open(dir.resolve("alloc.jsonl"));
        AllocatorCore core = new AllocatorCore(config, System::currentTimeMillis, log);
        RunwayAllocator allocator = new RunwayAllocator(core, routerPort, pubPort, 100, System::currentTimeMillis);
        Thread t = new Thread(allocator, "restart-test-allocator");
        t.start();
        assertTrue(allocator.awaitBound(5000));
        return new Incarnation(allocator, t, log, core);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Instruction awaitInstruction(ZMQ.Socket s, InstructionStatus wanted) throws Exception {
        while (true) {
            byte[] data = s.recv();
            assertNotNull(data, "timed out waiting for " + wanted);
            Message m = JsonCodec.decode(data);
            if (m instanceof Instruction i && i.status() == wanted) {
                return i;
            }
        }
    }

    @Test
    @Timeout(40)
    void allocatorKilledAndRestartedMidRun() throws Exception {
        int routerPort = freePort();
        int pubPort = freePort();
        Incarnation first = startAllocator(routerPort, pubPort);

        ControlService control = new ControlService(config.airport("AAA").orElseThrow(),
                new ControlService.Endpoints(0, 0, 0, "tcp://127.0.0.1:" + routerPort, "tcp://127.0.0.1:" + pubPort),
                500, 100, System::currentTimeMillis);
        Thread controlThread = new Thread(control, "restart-test-control");
        controlThread.start();
        assertTrue(control.awaitBound(5000));

        Incarnation second = null;
        Instruction assigned;
        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket ac1 = ctx.createSocket(SocketType.DEALER);
            ZMQ.Socket ac2 = ctx.createSocket(SocketType.DEALER);
            for (ZMQ.Socket s : List.of(ac1, ac2)) {
                s.setReceiveTimeOut(8000);
                s.connect("tcp://127.0.0.1:" + control.aircraftRouterPort());
            }

            // AC1 gets a runway but has not acked when the allocator dies.
            ac1.send(JsonCodec.encodeBytes(new RunwayRequest("p1", "AC1", "AAA", Operation.LANDING, SizeClass.SMALL, false, 0)));
            assigned = awaitInstruction(ac1, InstructionStatus.ASSIGNED);

            first.stop(); // "kill": every decision it made is already on disk
            Thread.sleep(800); // control notices: timeouts / missing heartbeats

            // During the outage: AC1's ack is accepted locally (CONFIRM keeps being retried);
            // AC2's new request must be HELD, never assigned (invariant 7).
            ac1.send(JsonCodec.encodeBytes(new Ack("p1", "AC1")));
            ac2.send(JsonCodec.encodeBytes(new RunwayRequest("p2", "AC2", "AAA", Operation.LANDING, SizeClass.SMALL, false, 0)));
            Instruction held = assertInstanceOf(Instruction.class, JsonCodec.decode(ac2.recv()));
            assertEquals(InstructionStatus.HELD, held.status());

            second = startAllocator(routerPort, pubPort); // recovers: p1 PENDING_ACK -> UNCERTAIN
            Instruction late = awaitInstruction(ac2, InstructionStatus.ASSIGNED);
            ac2.send(JsonCodec.encodeBytes(new Ack("p2", "AC2")));
            assertNotNull(late.runwayId());

            // Wait until reconciliation has confirmed p1 and p2's ack is recorded (read the durable log).
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline
                    && java.nio.file.Files.readAllLines(dir.resolve("alloc.jsonl")).stream()
                    .filter(l -> l.contains("\"state\":\"CONFIRMED\"")).count() < 2) {
                Thread.sleep(50);
            }
        } finally {
            control.stop();
            controlThread.join(5000);
            if (second != null) {
                second.stop();
            }
        }

        // Inspect the durable history after everything has stopped.
        try (ReservationLog log = ReservationLog.open(dir.resolve("alloc.jsonl"))) {
            List<ReservationRecord> history = log.recovered();
            List<ReservationState> p1 = history.stream().filter(r -> r.requestId().equals("p1"))
                    .map(ReservationRecord::state).toList();
            assertEquals(List.of(ReservationState.PENDING_ACK, ReservationState.UNCERTAIN, ReservationState.CONFIRMED), p1,
                    "p1: decided, uncertain after restart, reconciled to confirmed");
            ReservationRecord p2 = history.stream().filter(r -> r.requestId().equals("p2"))
                    .reduce((a, b) -> b).orElseThrow();
            assertEquals(ReservationState.CONFIRMED, p2.state());
            assertTrue(history.stream().filter(r -> r.requestId().equals("p1")).allMatch(r -> r.slotStart().equals(assigned.slotStart())),
                    "p1 kept the slot the aircraft was given");
            assertTrue(InvariantChecker.historicalOverlapViolations(history).isEmpty());
        }
    }
}
