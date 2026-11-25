package atc.monitor;

import atc.allocator.AllocatorCore;
import atc.allocator.ReservationLog;
import atc.allocator.RunwayAllocator;
import atc.allocator.TestFixtures;
import atc.common.AtcConfig;
import atc.common.JsonCodec;
import atc.common.Phase;
import atc.common.PositionUpdate;
import atc.control.ControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Monitor attached to a live allocator + control service over TCP. */
class MonitorProcessTest {
    @TempDir
    Path dir;

    @Test
    @Timeout(30)
    void dashboardShowsLiveServicesAndAircraft() throws Exception {
        AtcConfig config = TestFixtures.config();
        ReservationLog log = ReservationLog.open(dir.resolve("alloc.jsonl"));
        RunwayAllocator allocator = new RunwayAllocator(new AllocatorCore(config, System::currentTimeMillis, log),
                0, 0, 100, System::currentTimeMillis);
        Thread allocThread = new Thread(allocator);
        allocThread.start();
        allocator.awaitBound(5000);
        ControlService control = new ControlService(config.airport("AAA").orElseThrow(),
                new ControlService.Endpoints(0, 0, 0, "tcp://127.0.0.1:" + allocator.routerPort(),
                        "tcp://127.0.0.1:" + allocator.pubPort()), 1000, 100, System::currentTimeMillis);
        Thread controlThread = new Thread(control);
        controlThread.start();
        control.awaitBound(5000);

        Path eventsLog = dir.resolve("events.log");
        Monitor monitor = new Monitor(config, List.of("tcp://127.0.0.1:" + allocator.pubPort(),
                "tcp://127.0.0.1:" + control.eventPubPort()), 200, false, false, null, eventsLog, System::currentTimeMillis);
        Thread monitorThread = new Thread(monitor);
        monitorThread.start();

        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket pub = ctx.createSocket(SocketType.PUB);
            pub.connect("tcp://127.0.0.1:" + control.positionSubPort());
            long deadline = System.currentTimeMillis() + 15_000;
            long seq = 0;
            String frame = "";
            while (System.currentTimeMillis() < deadline) {
                pub.send(JsonCodec.encodeBytes(new PositionUpdate("AC9", "AAA", ++seq, Phase.APPROACH,
                        -5000, 0, 2000, 150, System.currentTimeMillis())));
                Thread.sleep(100);
                frame = monitor.lastFrame();
                if (frame.lines().anyMatch(l -> l.contains("  allocator") && l.contains("UP"))
                        && frame.lines().anyMatch(l -> l.contains("control-AAA") && l.contains("UP")
                        && l.contains("allocator reachable: yes"))
                        && frame.lines().anyMatch(l -> l.contains("AC9") && l.contains("APPROACH"))) {
                    break;
                }
            }
            String f = frame;
            assertTrue(f.lines().anyMatch(l -> l.contains("AC9") && l.contains("APPROACH")), f);
            assertTrue(f.lines().anyMatch(l -> l.contains("control-AAA") && l.contains("UP")), f);
            assertTrue(f.lines().anyMatch(l -> l.contains("control-BBB") && l.contains("never seen")), f);
            assertTrue(Files.readString(eventsLog).contains("ALLOCATOR_REACHABLE"), "events are recorded to the log file");
        } finally {
            monitor.stop();
            control.stop();
            allocator.stop();
            monitorThread.join(3000);
            controlThread.join(3000);
            allocThread.join(3000);
            log.close();
        }
    }
}
