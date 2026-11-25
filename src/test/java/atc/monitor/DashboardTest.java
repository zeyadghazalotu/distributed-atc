package atc.monitor;

import atc.allocator.TestFixtures;
import atc.common.ControlState;
import atc.common.ControlState.RequestView;
import atc.common.Event;
import atc.common.Heartbeat;
import atc.common.Instruction;
import atc.common.InstructionStatus;
import atc.common.JsonCodec;
import atc.common.Operation;
import atc.common.Phase;
import atc.common.PositionUpdate;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardTest {
    private static final long NOW = 1_000_000_000_000L;
    private final MonitorState state = new MonitorState();
    private final DashboardRenderer renderer = new DashboardRenderer(TestFixtures.config()); // heartbeat 100 ms

    private String frame() {
        return renderer.render(state, NOW);
    }

    private static String lineWith(String frame, String needle) {
        return frame.lines().filter(l -> l.contains(needle)).findFirst()
                .orElseThrow(() -> new AssertionError("no line with '" + needle + "' in:\n" + frame));
    }

    private ControlState snapshot() {
        Map<String, String> taxiways = new LinkedHashMap<>();
        taxiways.put("AAA-A", "r1");
        return new ControlState("control-AAA", "AAA", true,
                List.of(new PositionUpdate("AC1", "AAA", 5, Phase.LANDING, 1200, 0, 0, 90, NOW),
                        new PositionUpdate("AC2", "AAA", 3, Phase.PARKED, 400, -900, 0, 0, NOW)),
                List.of(new RequestView("r1", "AC1", Operation.LANDING, false, "ACKED", "AAA-M1", "AAA-A",
                                NOW - 10_000, NOW + 20_000, "ASSIGNED"),
                        new RequestView("r2", "AC2", Operation.TAKEOFF, false, "ASSIGNED", "AAA-S1", null,
                                NOW + 30_000, NOW + 90_000, "ASSIGNED"),
                        new RequestView("r3", "AC3", Operation.LANDING, false, "HELD", null, null, null, null, "HELD")),
                taxiways, List.of("r2"), 4, NOW);
    }

    @Test
    void emptyStateShowsEverythingDownWithoutCrashing() {
        String f = frame();
        assertTrue(lineWith(f, "allocator").contains("DOWN"));
        assertTrue(lineWith(f, "allocator").contains("never seen"));
        assertTrue(f.contains("AIRPORT AAA   (no snapshot yet"));
        assertTrue(f.contains("(none yet)"));
    }

    @Test
    void healthFollowsHeartbeats() {
        state.onMessage(new Heartbeat("allocator", 0), NOW - 200);
        state.onMessage(new Heartbeat("control-AAA", 0), NOW - 500); // > 3 x 100 ms ago
        String f = frame();
        assertTrue(lineWith(f, "  allocator").contains("UP"), f);
        assertTrue(lineWith(f, "control-AAA").contains("DOWN"), f);
        assertTrue(lineWith(f, "control-BBB").contains("never seen"), f);
    }

    @Test
    void airportSnapshotShowsAircraftRunwaysAndTaxiways() {
        state.onMessage(new Event("control-AAA", "STATE", JsonCodec.encode(snapshot()), NOW), NOW);
        String f = frame();

        String ac1 = lineWith(f, "  AC1 ");
        assertTrue(ac1.contains("LANDING") && ac1.contains("ACKED") && ac1.contains("AAA-M1")
                && ac1.contains("AAA-A") && ac1.contains("NOW (20.0s left)"), ac1);
        assertTrue(lineWith(f, "  AC2 ").contains("in 30.0s"));
        assertTrue(lineWith(f, "  AC3 ").contains("HELD"), "aircraft known only from its request is listed");

        // Runway cells (the runway id alone also appears in aircraft rows, so match the whole cell).
        assertTrue(f.contains("AAA-M1   2600m IN USE AC1 (20.0s left)"), f);
        assertTrue(f.contains("AAA-S1   1500m booked AC2 in 30.0s"), f);
        assertTrue(f.contains("AAA-L1   3400m free"), f);
        assertTrue(lineWith(f, "TAXIWAYS").contains("AAA-A: AC1"), "taxiway shows the aircraft, not the requestId");
        assertTrue(lineWith(f, "TAXIWAY QUEUE").contains("AC2"));
        String control = lineWith(f, "control-AAA");
        assertTrue(control.contains("allocator reachable: yes") && control.contains("stale positions dropped: 4"));
    }

    @Test
    void eventsAreSummarizedCappedAndStateIsNotLogged() {
        Instruction i = new Instruction("r1", "AC1", InstructionStatus.ASSIGNED, "AAA-S1", "AAA-A", 1L, 2L, null);
        state.onMessage(new Event("control-AAA", "INSTRUCTION", JsonCodec.encode(i), NOW), NOW);
        state.onMessage(new Event("control-AAA", "STATE", JsonCodec.encode(snapshot()), NOW), NOW);
        assertEquals(1, state.events().size());
        assertEquals("AC1 ASSIGNED AAA-S1 via AAA-A", state.events().get(0).text());

        for (int k = 0; k < 30; k++) {
            state.onMessage(new Event("allocator", "RESERVATION", "not json " + k, NOW), NOW);
        }
        assertEquals(MonitorState.MAX_EVENTS, state.events().size());
        assertEquals("not json 29", state.events().get(MonitorState.MAX_EVENTS - 1).text(), "newest kept");
        assertFalse(frame().contains("not json 0 "), "oldest dropped");
    }

    @Test
    void malformedMessagesAreCountedNotFatal() {
        state.onMalformed();
        state.onMessage(new Event("control-AAA", "STATE", "{broken", NOW), NOW);
        assertTrue(frame().contains("2 malformed"));
    }
}
