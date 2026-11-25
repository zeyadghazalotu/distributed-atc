package atc.control;

import atc.allocator.TestFixtures;
import atc.common.Ack;
import atc.common.Complete;
import atc.common.ControlState;
import atc.common.ErrorMessage;
import atc.common.Instruction;
import atc.common.InstructionStatus;
import atc.common.JsonCodec;
import atc.common.Message;
import atc.common.Operation;
import atc.common.Phase;
import atc.common.PositionUpdate;
import atc.common.Reserve;
import atc.common.RunwayRequest;
import atc.common.SizeClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static atc.common.ReservationState.COMPLETED;
import static atc.common.ReservationState.CONFIRMED;
import static atc.common.ReservationState.PENDING_ACK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlCoreTest {
    @TempDir
    Path dir;

    private ControlHarness h;

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) {
            h.log.close();
        }
    }

    private ControlHarness harness(String airport) throws Exception {
        h = new ControlHarness(dir, airport);
        return h;
    }

    private static RunwayRequest req(String id, String airport, Operation op, SizeClass size) {
        return new RunwayRequest(id, "AC-" + id, airport, op, size, false, 0);
    }

    private PositionUpdate pos(String aircraftId, long seq, Phase phase) {
        return new PositionUpdate(aircraftId, "AAA", seq, phase, 0, 0, 0, 0, h.now.get());
    }

    @Test
    void fullDepartureLifecycleEndToEndThroughTheRealAllocator() throws Exception {
        harness("AAA").allocatorHeartbeat();

        assertNull(h.control.onAircraftMessage(req("d1", "AAA", Operation.TAKEOFF, SizeClass.SMALL)),
                "decision is pushed later, not invented locally");
        h.deliverAll();
        Instruction assigned = h.lastInstructionTo("AC-d1");
        assertEquals(InstructionStatus.ASSIGNED, assigned.status());
        assertEquals("AAA-S1", assigned.runwayId());
        assertEquals("AAA-A", assigned.taxiwayId());
        assertEquals(PENDING_ACK, h.allocator.get("d1").orElseThrow().state());

        Message receipt = h.control.onAircraftMessage(new Ack("d1", "AC-d1"));
        assertEquals(assigned, receipt);
        h.deliverAll();
        assertEquals(CONFIRMED, h.allocator.get("d1").orElseThrow().state());

        h.control.onPosition(pos("AC-d1", 1, Phase.TAKEOFF_ROLL));
        assertEquals("", h.control.state().taxiwayOccupancy().get("AAA-A"), "taxiway freed on takeoff roll");

        assertEquals(new Complete("d1", "AC-d1"), h.control.onAircraftMessage(new Complete("d1", "AC-d1")));
        h.deliverAll();
        assertEquals(COMPLETED, h.allocator.get("d1").orElseThrow().state());
        assertEquals(0, h.control.pendingAllocatorCommands());
    }

    @Test
    void retriedRequestIsForwardedOnceAndGetsTheSameAnswer() throws Exception {
        harness("AAA").allocatorHeartbeat();
        RunwayRequest r = req("r1", "AAA", Operation.LANDING, SizeClass.MEDIUM);
        assertNull(h.control.onAircraftMessage(r));
        assertNull(h.control.onAircraftMessage(r), "still pending: no second decision");
        assertEquals(1, h.toAllocator.size(), "only one RESERVE in flight");
        h.deliverAll();

        Instruction first = h.lastInstructionTo("AC-r1");
        assertEquals(first, h.control.onAircraftMessage(r), "retry after the decision returns it again");
        assertEquals(1, h.allocator.snapshot().size());
    }

    @Test
    void allocatorTimeoutMeansHeldNeverAssignedUntilTheAllocatorAnswers() throws Exception {
        harness("AAA").allocatorHeartbeat();
        h.control.onAircraftMessage(req("a1", "AAA", Operation.LANDING, SizeClass.SMALL));
        h.dropAll(); // the RESERVE (or its reply) is lost

        h.advance(ControlHarness.TIMEOUT_MS);
        Instruction held = h.lastInstructionTo("AC-a1");
        assertEquals(InstructionStatus.HELD, held.status());
        assertNull(held.runwayId());
        assertFalse(h.control.allocatorReachable());
        assertEquals(1, h.events("ALLOCATOR_UNREACHABLE").size());
        assertEquals(1, h.toAllocator.size(), "same RESERVE retried");
        assertEquals("a1", ((Reserve) h.toAllocator.peek().command()).requestId(), "retry keeps the requestId");

        // A new request while unreachable is told to hold immediately.
        Message reply = h.control.onAircraftMessage(req("a2", "AAA", Operation.LANDING, SizeClass.SMALL));
        assertEquals(InstructionStatus.HELD, assertInstanceOf(Instruction.class, reply).status());

        // Invariant 7: nothing was assigned while the allocator was silent.
        assertTrue(h.pushed.stream().noneMatch(p -> p.message() instanceof Instruction i
                && i.status() == InstructionStatus.ASSIGNED));

        h.deliverAll(); // allocator is back
        assertTrue(h.control.allocatorReachable());
        assertEquals(InstructionStatus.ASSIGNED, h.lastInstructionTo("AC-a1").status());
        assertEquals(InstructionStatus.ASSIGNED, h.lastInstructionTo("AC-a2").status());
        assertEquals(2, h.allocator.snapshot().size(), "retries did not create extra reservations");
    }

    @Test
    void allocatorIsPresumedUnreachableUntilThereIsEvidence() throws Exception {
        harness("AAA"); // no heartbeat yet
        Message reply = h.control.onAircraftMessage(req("x", "AAA", Operation.TAKEOFF, SizeClass.SMALL));
        assertEquals(InstructionStatus.HELD, assertInstanceOf(Instruction.class, reply).status());
        h.allocatorHeartbeat(); // contact: pending RESERVE is resent at once
        h.deliverAll();
        assertEquals(InstructionStatus.ASSIGNED, h.lastInstructionTo("AC-x").status());
    }

    @Test
    void staleHeartbeatsMarkTheAllocatorUnreachable() throws Exception {
        harness("AAA").allocatorHeartbeat();
        assertTrue(h.control.allocatorReachable());
        h.advance(3 * ControlHarness.HEARTBEAT_MS + 1);
        assertFalse(h.control.allocatorReachable());
    }

    @Test
    void wrongAirportAndIneligibleAircraftAreRejected() throws Exception {
        harness("BBB").allocatorHeartbeat();
        Message wrong = h.control.onAircraftMessage(req("w", "AAA", Operation.LANDING, SizeClass.SMALL));
        assertEquals(InstructionStatus.REJECTED, assertInstanceOf(Instruction.class, wrong).status());

        h.control.onAircraftMessage(req("heavy", "BBB", Operation.LANDING, SizeClass.HEAVY));
        h.deliverAll();
        Instruction rejected = h.lastInstructionTo("AC-heavy");
        assertEquals(InstructionStatus.REJECTED, rejected.status());
        assertTrue(rejected.reason().contains("HEAVY"), rejected.reason());
    }

    @Test
    void invalidAircraftMessagesGetErrors() throws Exception {
        harness("AAA").allocatorHeartbeat();
        assertInstanceOf(ErrorMessage.class, h.control.onAircraftMessage(
                new RunwayRequest("r", null, "AAA", Operation.LANDING, SizeClass.SMALL, false, 0)));
        assertInstanceOf(ErrorMessage.class, h.control.onAircraftMessage(new Ack("nope", "AC")));
        assertInstanceOf(ErrorMessage.class, h.control.onAircraftMessage(null));

        h.control.onAircraftMessage(req("r1", "AAA", Operation.LANDING, SizeClass.SMALL));
        assertInstanceOf(ErrorMessage.class, h.control.onAircraftMessage(new Ack("r1", "AC-intruder")),
                "only the owning aircraft can ack");
        assertInstanceOf(ErrorMessage.class, h.control.onAircraftMessage(
                new RunwayRequest("r1", "AC-other", "AAA", Operation.LANDING, SizeClass.SMALL, false, 0)));
        assertInstanceOf(ErrorMessage.class, h.control.onAircraftMessage(new Complete("r1", "AC-r1")),
                "cannot complete before an assignment");
    }

    @Test
    void taxiwayIsExclusiveAndQueuedRequestGetsItWhenFreed() throws Exception {
        harness("AAA").allocatorHeartbeat(); // AAA has a single taxiway
        h.control.onAircraftMessage(req("d1", "AAA", Operation.TAKEOFF, SizeClass.SMALL));
        h.control.onAircraftMessage(req("d2", "AAA", Operation.TAKEOFF, SizeClass.SMALL));
        h.deliverAll();
        assertEquals("AAA-A", h.lastInstructionTo("AC-d1").taxiwayId());
        Instruction waiting = h.lastInstructionTo("AC-d2");
        assertEquals(InstructionStatus.ASSIGNED, waiting.status());
        assertNull(waiting.taxiwayId());
        assertEquals("taxiway pending", waiting.reason());

        h.control.onPosition(pos("AC-d1", 1, Phase.TAXI_OUT));
        assertNull(h.lastInstructionTo("AC-d2").taxiwayId(), "still taxiing: not freed yet");
        h.control.onPosition(pos("AC-d1", 2, Phase.TAKEOFF_ROLL));
        Instruction granted = h.lastInstructionTo("AC-d2");
        assertEquals("AAA-A", granted.taxiwayId());
        assertEquals(waiting.runwayId(), granted.runwayId(), "same runway assignment, now with a taxiway");
    }

    @Test
    void arrivalGetsTaxiwayOnlyAfterVacatingTheRunwayAndKeepsItUntilParked() throws Exception {
        harness("AAA").allocatorHeartbeat();
        h.control.onAircraftMessage(req("l1", "AAA", Operation.LANDING, SizeClass.SMALL));
        h.deliverAll();
        Instruction assigned = h.lastInstructionTo("AC-l1");
        assertNull(assigned.taxiwayId(), "no taxiway held during the approach");
        assertNull(assigned.reason());
        assertEquals("", h.control.state().taxiwayOccupancy().get("AAA-A"), "free for departures meanwhile");

        h.control.onAircraftMessage(new Ack("l1", "AC-l1"));
        h.control.onAircraftMessage(new Complete("l1", "AC-l1")); // runway vacated
        Instruction taxiIn = h.lastInstructionTo("AC-l1");
        assertEquals("AAA-A", taxiIn.taxiwayId(), "taxi-in clearance pushed on vacating");
        assertEquals(assigned.runwayId(), taxiIn.runwayId());
        assertEquals("l1", h.control.state().taxiwayOccupancy().get("AAA-A"), "still taxiing in");
        h.control.onPosition(pos("AC-l1", 1, Phase.PARKED));
        assertEquals("", h.control.state().taxiwayOccupancy().get("AAA-A"));
    }

    @Test
    void bumpedAircraftIsToldToHoldAndRebookedUnderTheSameRequestId() throws Exception {
        harness("BBB").allocatorHeartbeat(); // one runway
        h.control.onAircraftMessage(req("p1", "BBB", Operation.LANDING, SizeClass.SMALL));
        h.deliverAll();
        Instruction original = h.lastInstructionTo("AC-p1");
        assertEquals(InstructionStatus.ASSIGNED, original.status());

        // Another control service's emergency hits the allocator directly.
        h.broadcastBumps(h.allocator.handle(TestFixtures.emergency("e1", "BBB", SizeClass.SMALL)));
        Instruction hold = h.lastInstructionTo("AC-p1");
        assertEquals(InstructionStatus.HELD, hold.status());
        assertEquals("p1", hold.requestId(), "aircraft keeps its requestId");
        assertEquals("p1#2", ((Reserve) h.toAllocator.peek().command()).requestId());

        h.deliverAll();
        Instruction rebooked = h.lastInstructionTo("AC-p1");
        assertEquals(InstructionStatus.ASSIGNED, rebooked.status());
        assertTrue(rebooked.slotStart() > original.slotStart(), "new slot after the emergency");

        // Ack goes to the CURRENT booking.
        h.control.onAircraftMessage(new Ack("p1", "AC-p1"));
        h.deliverAll();
        assertEquals(CONFIRMED, h.allocator.get("p1#2").orElseThrow().state());
    }

    @Test
    void outOfOrderAndDuplicatePositionsAreIgnored() throws Exception {
        harness("AAA");
        h.control.onPosition(pos("AC1", 5, Phase.APPROACH));
        h.control.onPosition(pos("AC1", 3, Phase.PARKED));   // late
        h.control.onPosition(pos("AC1", 5, Phase.PARKED));   // duplicate seq
        h.control.onPosition(pos("AC2", 1, Phase.PARKED));   // other aircraft, own sequence
        h.control.onPosition(new PositionUpdate("AC3", "ZZZ", 1, Phase.PARKED, 0, 0, 0, 0, 0)); // other airport
        ControlState s = h.control.state();
        assertEquals(2, s.stalePositionsDropped());
        assertEquals(2, s.aircraft().size());
        assertEquals(Phase.APPROACH, s.aircraft().get(0).phase());

        h.control.onPosition(pos("AC1", 6, Phase.LANDING));
        assertEquals(Phase.LANDING, h.control.state().aircraft().get(0).phase());
    }

    @Test
    void tickPublishesHeartbeatAndDecodableStateSnapshot() throws Exception {
        harness("AAA").control.tick();
        assertTrue(h.published.stream().anyMatch(m -> m instanceof atc.common.Heartbeat hb
                && hb.service().equals("control-AAA")));
        String detail = h.events("STATE").get(0).detail();
        ControlState state = JsonCodec.decode(detail, ControlState.class);
        assertNotNull(state);
        assertEquals("AAA", state.airportId());
    }

    @Test
    void newRequestFromSameAircraftSupersedesAndReleasesTheOldBooking() throws Exception {
        harness("AAA").allocatorHeartbeat();
        h.control.onAircraftMessage(new RunwayRequest("r1", "AC1", "AAA", Operation.TAKEOFF, SizeClass.SMALL, false, 0));
        h.deliverAll();
        assertEquals("AAA-A", h.lastInstructionTo("AC1").taxiwayId());

        // Missed its slot: asks again with a new requestId.
        h.control.onAircraftMessage(new RunwayRequest("r2", "AC1", "AAA", Operation.TAKEOFF, SizeClass.SMALL, false, 0));
        h.deliverAll();
        assertEquals(atc.common.ReservationState.RELEASED, h.allocator.get("r1").orElseThrow().state());
        Instruction second = h.lastInstructionTo("AC1");
        assertEquals("r2", second.requestId());
        assertEquals("AAA-A", second.taxiwayId(), "taxiway moved to the new request, not leaked");
        assertTrue(atc.allocator.InvariantChecker.overlapViolations(h.allocator.snapshot(), 30_000).isEmpty());
    }

    @Test
    void supersededWhileReserveInFlightIsReleasedWhenTheBookingAppears() throws Exception {
        harness("AAA").allocatorHeartbeat();
        h.control.onAircraftMessage(new RunwayRequest("r1", "AC1", "AAA", Operation.LANDING, SizeClass.SMALL, false, 0));
        // r1's RESERVE is still in flight when r2 arrives; the RELEASE for r1 is queued behind it.
        h.control.onAircraftMessage(new RunwayRequest("r2", "AC1", "AAA", Operation.LANDING, SizeClass.SMALL, false, 0));
        h.deliverAll();
        assertEquals(atc.common.ReservationState.RELEASED, h.allocator.get("r1").orElseThrow().state());
        assertEquals("r2", h.lastInstructionTo("AC1").requestId());
        assertTrue(h.instructionsTo("AC1").stream().noneMatch(i -> i.requestId().equals("r1")),
                "the aircraft never hears about the superseded request");
    }

    @Test
    void earliestStartIsForwardedToTheAllocator() throws Exception {
        harness("AAA").allocatorHeartbeat();
        long eta = h.now.get() + 90_000;
        h.control.onAircraftMessage(new RunwayRequest("r1", "AC1", "AAA", Operation.LANDING, SizeClass.SMALL, false, 0, eta));
        h.deliverAll();
        assertEquals(eta, h.lastInstructionTo("AC1").slotStart());
    }

    @Test
    void landedArrivalQueuesForABusyTaxiwayAndGetsItWhenFreed() throws Exception {
        harness("AAA").allocatorHeartbeat(); // single taxiway
        h.control.onAircraftMessage(req("d1", "AAA", Operation.TAKEOFF, SizeClass.SMALL));
        h.control.onAircraftMessage(req("l1", "AAA", Operation.LANDING, SizeClass.SMALL));
        h.deliverAll();
        assertEquals("AAA-A", h.lastInstructionTo("AC-d1").taxiwayId());

        h.control.onAircraftMessage(new Ack("l1", "AC-l1"));
        h.control.onAircraftMessage(new Complete("l1", "AC-l1"));
        assertNull(h.lastInstructionTo("AC-l1").taxiwayId(), "departure still on the taxiway");
        assertEquals(List.of("l1"), h.control.state().taxiwayQueue());

        h.control.onPosition(pos("AC-d1", 1, Phase.TAKEOFF_ROLL));
        assertEquals("AAA-A", h.lastInstructionTo("AC-l1").taxiwayId(), "granted to the waiting arrival");
    }

    // ------------------------------------------------------------------ milestone 5: failure + recovery

    @Test
    void afterAllocatorRestartAnAckedButUnconfirmedBookingIsReconciledToConfirmed() throws Exception {
        harness("AAA").allocatorHeartbeat();
        h.control.onAircraftMessage(req("p1", "AAA", Operation.LANDING, SizeClass.SMALL));
        h.deliverAll();
        h.control.onAircraftMessage(new Ack("p1", "AC-p1")); // CONFIRM queued...
        h.restartAllocator();                               // ...and lost in the crash
        assertEquals(atc.common.ReservationState.UNCERTAIN, h.allocator.get("p1").orElseThrow().state());

        h.allocatorHeartbeat(); // new startedAt: restart detected
        assertEquals(1, h.events("RECONCILE").size());
        h.deliverAll();
        assertEquals(CONFIRMED, h.allocator.get("p1").orElseThrow().state());
        assertTrue(h.events("RECONCILED").get(0).detail().contains("aircraft acked -> CONFIRM"),
                h.events("RECONCILED").get(0).detail());
    }

    @Test
    void uncertainBookingWithoutAckIsKeptUntilTheAckTimeoutThenReleased() throws Exception {
        harness("AAA").allocatorHeartbeat();
        h.control.onAircraftMessage(req("p1", "AAA", Operation.TAKEOFF, SizeClass.SMALL));
        h.deliverAll(); // ASSIGNED, aircraft never acks (it crashed)
        h.restartAllocator();
        h.allocatorHeartbeat();
        h.deliverAll();
        assertTrue(h.events("RECONCILED").get(0).detail().contains("awaiting ack"));
        assertEquals(atc.common.ReservationState.UNCERTAIN, h.allocator.get("p1").orElseThrow().state(),
                "never released without evidence");

        h.now.addAndGet(ControlHarness.ACK_TIMEOUT_MS + 1);
        h.allocatorHeartbeat(); // keep the allocator 'reachable'
        h.control.tick();
        h.deliverAll();
        assertEquals(atc.common.ReservationState.RELEASED, h.allocator.get("p1").orElseThrow().state());
        Instruction wait = h.lastInstructionTo("AC-p1");
        assertEquals(InstructionStatus.WAIT, wait.status());
        assertNull(wait.runwayId());
        assertEquals("", h.control.state().taxiwayOccupancy().get("AAA-A"), "taxiway released too");
        assertEquals(1, h.events("ACK_TIMEOUT").size());

        // A late ACK does not resurrect it: the aircraft is told to WAIT (ask again).
        Message late = h.control.onAircraftMessage(new Ack("p1", "AC-p1"));
        assertEquals(InstructionStatus.WAIT, assertInstanceOf(Instruction.class, late).status());
        h.deliverAll();
        assertEquals(atc.common.ReservationState.RELEASED, h.allocator.get("p1").orElseThrow().state());
    }

    @Test
    void ackedBookingIsNotSubjectToTheAckTimeout() throws Exception {
        harness("AAA").allocatorHeartbeat();
        h.control.onAircraftMessage(req("p1", "AAA", Operation.LANDING, SizeClass.SMALL));
        h.deliverAll();
        h.control.onAircraftMessage(new Ack("p1", "AC-p1"));
        h.deliverAll();
        h.now.addAndGet(ControlHarness.ACK_TIMEOUT_MS * 5);
        h.control.tick();
        assertTrue(h.events("ACK_TIMEOUT").isEmpty());
        assertEquals(CONFIRMED, h.allocator.get("p1").orElseThrow().state());
    }

    @Test
    void allocatorThatLostItsLogGetsTheBookingBackUnderTheSameId() throws Exception {
        harness("AAA").allocatorHeartbeat();
        h.control.onAircraftMessage(req("p1", "AAA", Operation.LANDING, SizeClass.SMALL));
        h.deliverAll();
        h.control.onAircraftMessage(new Ack("p1", "AC-p1"));
        h.deliverAll();
        Instruction before = h.lastInstructionTo("AC-p1");

        h.restartAllocatorWithEmptyLog();
        h.allocatorHeartbeat();
        h.deliverAll();
        assertEquals(CONFIRMED, h.allocator.get("p1").orElseThrow().state(), "re-reserved and re-confirmed");
        assertTrue(h.events("RECONCILED").get(0).detail().contains("re-reserving"));
        assertEquals(before, h.lastInstructionTo("AC-p1"), "same booking: the aircraft is not disturbed");
    }

    @Test
    void afterControlRestartTheAircraftReRegistersAndCompletes() throws Exception {
        harness("AAA").allocatorHeartbeat();
        h.control.onAircraftMessage(req("p1", "AAA", Operation.LANDING, SizeClass.SMALL));
        h.deliverAll();
        h.control.onAircraftMessage(new Ack("p1", "AC-p1"));
        h.deliverAll();
        Instruction before = h.lastInstructionTo("AC-p1");

        h.restartControl();
        h.allocatorHeartbeat();
        ErrorMessage unknown = assertInstanceOf(ErrorMessage.class, h.control.onAircraftMessage(new Ack("p1", "AC-p1")));
        assertTrue(unknown.reason().startsWith("unknown requestId"), unknown.reason());

        // The aircraft re-registers with the same request; answered from the allocator's record.
        assertNull(h.control.onAircraftMessage(req("p1", "AAA", Operation.LANDING, SizeClass.SMALL)));
        h.deliverAll();
        Instruction again = h.lastInstructionTo("AC-p1");
        assertEquals(before.runwayId(), again.runwayId());
        assertEquals(before.slotStart(), again.slotStart());
        assertEquals("ACKED", h.control.state().requests().get(0).status(), "allocator already had the ack");
        assertEquals(1, h.allocator.snapshot().size(), "no second reservation");

        h.control.onAircraftMessage(new Complete("p1", "AC-p1"));
        h.deliverAll();
        assertEquals(COMPLETED, h.allocator.get("p1").orElseThrow().state());
    }

    @Test
    void completeForARequestUnknownAfterRestartIsForwardedToTheAllocator() throws Exception {
        harness("AAA").allocatorHeartbeat();
        h.control.onAircraftMessage(req("p1", "AAA", Operation.TAKEOFF, SizeClass.SMALL));
        h.deliverAll();
        h.control.onAircraftMessage(new Ack("p1", "AC-p1"));
        h.deliverAll();
        h.restartControl();
        h.allocatorHeartbeat();
        assertEquals(new Complete("p1", "AC-p1"), h.control.onAircraftMessage(new Complete("p1", "AC-p1")));
        h.deliverAll();
        assertEquals(COMPLETED, h.allocator.get("p1").orElseThrow().state());
    }

    @Test
    void aReplyAfterAnOutageKeepsTheAllocatorReachableUntilHeartbeatsCatchUp() throws Exception {
        // Regression (milestone 5 live run): after a restart the reply channel reconnected before
        // the heartbeat channel. Each reply marked the allocator reachable and the next tick
        // marked it unreachable again from the OLD incarnation's stale heartbeat: flapping, with
        // a resend + reconcile on every flap.
        harness("AAA").allocatorHeartbeat();
        h.control.onAircraftMessage(req("r1", "AAA", Operation.LANDING, SizeClass.SMALL));
        h.dropAll();
        h.advance(ControlHarness.TIMEOUT_MS);       // timeout + stale heartbeat -> unreachable
        assertFalse(h.control.allocatorReachable());

        h.deliverAll();                              // the restarted allocator answers the retry
        assertTrue(h.control.allocatorReachable());
        h.advance(10);                               // no heartbeat from it yet
        assertTrue(h.control.allocatorReachable(), "a reply is evidence too: no flap");
        assertEquals(1, h.events("ALLOCATOR_REACHABLE").size() - 1, "exactly one recovery after the initial contact");
    }
}
