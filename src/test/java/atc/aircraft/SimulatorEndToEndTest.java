package atc.aircraft;

import atc.allocator.AllocatorCore;
import atc.allocator.InvariantChecker;
import atc.allocator.ReservationLog;
import atc.allocator.ReservationRecord;
import atc.allocator.RunwayAllocator;
import atc.allocator.TestFixtures;
import atc.common.AtcConfig;
import atc.common.Operation;
import atc.common.Phase;
import atc.common.ReservationState;
import atc.common.SizeClass;
import atc.control.ControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone 3 demo as a test: allocator + control service + simulator over TCP. Two aircraft,
 * one operation each: AC1 lands (approach to parked), AC2 departs (parked to departed).
 */
class SimulatorEndToEndTest {
    @TempDir
    Path dir;

    @Test
    @Timeout(60)
    void oneLandingAndOneTakeoffEndToEnd() throws Exception {
        // 30x: 60 s slot -> 2 s, 30 s buffer -> 1 s.
        AtcConfig config = TestFixtures.config().withTimeScale(30);
        ReservationLog log = ReservationLog.open(dir.resolve("alloc.jsonl"));
        AllocatorCore core = new AllocatorCore(config, System::currentTimeMillis, log);
        RunwayAllocator allocator = new RunwayAllocator(core, 0, 0, 100, System::currentTimeMillis);
        Thread allocThread = new Thread(allocator, "e2e-allocator");
        allocThread.start();
        assertTrue(allocator.awaitBound(5000));

        ControlService control = new ControlService(config.airport("AAA").orElseThrow(),
                new ControlService.Endpoints(0, 0, 0, "tcp://127.0.0.1:" + allocator.routerPort(),
                        "tcp://127.0.0.1:" + allocator.pubPort()), 1000, 100, System::currentTimeMillis);
        Thread controlThread = new Thread(control, "e2e-control");
        controlThread.start();
        assertTrue(control.awaitBound(5000));
        Thread.sleep(300); // let the control service hear the allocator's heartbeat first

        // Simulated seconds: taxi 30, approach lead 60, turnaround 15-30 (scaled 30x).
        SimParams params = new SimParams(config.timing(), 30, 60, 15, 30, 10, 20, 1500, 100);
        AircraftSimulator sim = new AircraftSimulator(config.airport("AAA").orElseThrow(), params, 2, 1, 42,
                List.of(SizeClass.MEDIUM, SizeClass.SMALL),
                "tcp://127.0.0.1:" + control.aircraftRouterPort(),
                "tcp://127.0.0.1:" + control.positionSubPort(), System::currentTimeMillis);
        Thread simThread = new Thread(sim, "e2e-sim");
        simThread.start();
        simThread.join(45_000);

        try {
            assertTrue(!simThread.isAlive(), "simulator did not finish");
            List<AircraftAgent> agents = sim.agents();
            assertEquals(Phase.PARKED, agents.get(0).phase(), "AC1 landed and parked");
            assertEquals(Phase.DEPARTED, agents.get(1).phase(), "AC2 took off");
            agents.forEach(a -> assertEquals(1, a.opsCompleted()));

            // The control service acks COMPLETE to the aircraft immediately and forwards it to the
            // allocator asynchronously, so the last one may still be in flight. Wait for it in the
            // durable log (safe to read from this thread), then stop the allocator before
            // inspecting its in-memory state.
            long deadline = System.currentTimeMillis() + 5000;
            while (countCompleted(dir.resolve("alloc.jsonl")) < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            sim.stop();
            control.stop();
            allocator.stop();
            controlThread.join(3000);
            allocThread.join(3000);

            List<ReservationRecord> completed = core.snapshot().stream()
                    .filter(r -> r.state() == ReservationState.COMPLETED).toList();
            assertEquals(2, completed.size(), "allocator records: " + core.snapshot());
            assertTrue(completed.stream().anyMatch(r -> r.operation() == Operation.LANDING && r.aircraftId().equals("AAA-AC1")));
            assertTrue(completed.stream().anyMatch(r -> r.operation() == Operation.TAKEOFF && r.aircraftId().equals("AAA-AC2")));
            assertTrue(InvariantChecker.overlapViolations(core.snapshot(), config.timing().bufferMs()).isEmpty());
            assertTrue(InvariantChecker.eligibilityViolations(core.snapshot(), config).isEmpty());
        } finally {
            sim.stop();
            control.stop();
            allocator.stop();
            simThread.join(3000);
            controlThread.join(3000);
            allocThread.join(3000);
            log.close();
        }
    }

    private static long countCompleted(Path logFile) throws Exception {
        return java.nio.file.Files.readAllLines(logFile).stream()
                .filter(l -> l.contains("\"state\":\"COMPLETED\""))
                .count();
    }
}
