package atc.aircraft;

import atc.common.Ack;
import atc.common.AtcConfig.Timing;
import atc.common.Complete;
import atc.common.Instruction;
import atc.common.InstructionStatus;
import atc.common.Message;
import atc.common.Operation;
import atc.common.Phase;
import atc.common.PositionUpdate;
import atc.common.RunwayRequest;
import atc.common.SizeClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AircraftAgentTest {
    private static final long S = 1000;
    private static final long T0 = 1_000_000_000_000L;
    /** Real time (scale 1): taxi 60 s, approach lead 120 s, turnaround 60-120 s, retry 3 s. */
    private static final SimParams P = new SimParams(new Timing(60, 30, 300, 2000, 1000), 60, 120, 60, 120, 60, 180, 3 * S, 1000);

    private record Sent(Message message, boolean reconnect) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private final List<PositionUpdate> positions = new ArrayList<>();
    private long now = T0;

    private final AircraftAgent.Output out = new AircraftAgent.Output() {
        @Override
        public void send(String aircraftId, Message message, boolean reconnect) {
            sent.add(new Sent(message, reconnect));
        }

        @Override
        public void publish(PositionUpdate position) {
            positions.add(position);
        }
    };

    private AircraftAgent agent(boolean arrival, int maxOps) {
        return new AircraftAgent("AC1", "AAA", SizeClass.SMALL, 0, P, out, new Random(7), "run1", 500, maxOps, arrival, now);
    }

    private void at(AircraftAgent a, long t) {
        now = t;
        a.tick(now);
    }

    private Message last() {
        return sent.get(sent.size() - 1).message();
    }

    private RunwayRequest lastRequest() {
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).message() instanceof RunwayRequest r) {
                return r;
            }
        }
        throw new AssertionError("no request sent");
    }

    private static Instruction assigned(RunwayRequest r, long slotStart, String taxiway) {
        return new Instruction(r.requestId(), r.aircraftId(), InstructionStatus.ASSIGNED, "AAA-S1", taxiway,
                slotStart, slotStart + 60 * S, null);
    }

    private static Instruction status(RunwayRequest r, InstructionStatus st, Long slotStart) {
        return new Instruction(r.requestId(), r.aircraftId(), st, null, null, slotStart, null, "x");
    }

    @Test
    void departureRunsThroughEveryPhaseInsideItsSlot() {
        AircraftAgent a = agent(false, 1);
        at(a, T0 + 31 * S); // first turnaround is at most 30 s
        RunwayRequest req = lastRequest();
        assertEquals(Operation.TAKEOFF, req.operation());
        assertEquals(now + 60 * S, req.earliestStart(), "ETA = now + taxi time");

        long slot = req.earliestStart();
        a.onMessage(assigned(req, slot, "AAA-A"), now);
        assertEquals(new Ack(req.requestId(), "AC1"), last(), "acks the assignment");

        at(a, now);                         // slot - taxi time = now: start taxiing
        assertEquals(Phase.TAXI_OUT, a.phase());
        at(a, now + 60 * S);
        assertEquals(Phase.HOLDING_SHORT, a.phase());
        at(a, slot);
        assertEquals(Phase.TAKEOFF_ROLL, a.phase());
        at(a, slot + 60 * S);
        assertEquals(Phase.DEPARTED, a.phase());
        assertEquals(new Complete(req.requestId(), "AC1"), last());
        assertTrue(a.isDone());
        a.onMessage(new Complete(req.requestId(), "AC1"), now);
        assertTrue(a.isFinished());

        List<Phase> phases = positions.stream().map(PositionUpdate::phase).distinct().toList();
        assertEquals(List.of(Phase.PARKED, Phase.TAXI_OUT, Phase.HOLDING_SHORT, Phase.TAKEOFF_ROLL, Phase.DEPARTED), phases);
        for (int i = 1; i < positions.size(); i++) {
            assertTrue(positions.get(i).seq() > positions.get(i - 1).seq(), "seq strictly increasing");
        }
        assertTrue(positions.get(0).seq() > 500, "seq starts at the given base");
    }

    @Test
    void arrivalLandsInItsSlotTaxisInAndParks() {
        AircraftAgent a = agent(true, 1);
        RunwayRequest req = lastRequest();
        assertEquals(Operation.LANDING, req.operation());
        long slot = T0 + 120 * S;
        assertEquals(slot, req.earliestStart());

        a.onMessage(assigned(req, slot, "AAA-A"), now);
        at(a, now);
        assertEquals(Phase.APPROACH, a.phase());
        at(a, slot - S);
        assertEquals(Phase.APPROACH, a.phase(), "does not touch down before its slot");
        at(a, slot);
        assertEquals(Phase.LANDING, a.phase());
        at(a, slot + 60 * S);
        assertEquals(Phase.TAXI_IN, a.phase());
        assertEquals(new Complete(req.requestId(), "AC1"), last(), "COMPLETE when the runway is vacated");
        at(a, now + 60 * S);
        assertEquals(Phase.PARKED, a.phase());
        assertTrue(a.isDone());
    }

    @Test
    void silenceIsRetriedWithTheSameRequestIdOnANewSocketAndHeldKeepsRetrying() {
        AircraftAgent a = agent(true, 0);
        RunwayRequest req = lastRequest();
        at(a, T0 + 3 * S);
        Sent retry = sent.get(sent.size() - 1);
        assertEquals(req, retry.message(), "same message, same requestId");
        assertTrue(retry.reconnect(), "lazy pirate: fresh socket");

        a.onMessage(status(req, InstructionStatus.HELD, null), now);
        int before = sent.size();
        at(a, now + 3 * S);
        assertEquals(before + 1, sent.size(), "HELD is not a decision: still retrying");

        a.onMessage(assigned(req, T0 + 120 * S, "AAA-A"), now);
        int afterAssign = sent.size(); // includes the ACK
        at(a, now + 1); // ack receipt pending
        a.onMessage(assigned(req, T0 + 120 * S, "AAA-A"), now); // ack receipt = current instruction
        at(a, now + 10 * S);
        assertEquals(afterAssign, sent.size(), "no more retries once decided and acked");
    }

    @Test
    void tooLateForTheSlotMeansGoAroundWithANewRequestId() {
        AircraftAgent a = agent(true, 0);
        RunwayRequest first = lastRequest();
        a.onMessage(assigned(first, T0 + 120 * S, "AAA-A"), now);
        at(a, T0 + 155 * S); // past slotStart + half a slot
        at(a, now);
        RunwayRequest second = lastRequest();
        assertNotEquals(first.requestId(), second.requestId());
        assertEquals(Phase.APPROACH, a.phase(), "back in the holding pattern");
        assertEquals(now + 120 * S, second.earliestStart());
    }

    @Test
    void waitMeansAskAgainLaterWithANewRequestId() {
        AircraftAgent a = agent(true, 0);
        RunwayRequest first = lastRequest();
        a.onMessage(status(first, InstructionStatus.WAIT, T0 + 600 * S), now);
        at(a, T0 + 400 * S);
        assertEquals(first, lastRequest(), "not yet: estimate minus lead is 480 s");
        at(a, T0 + 480 * S);
        assertNotEquals(first.requestId(), lastRequest().requestId());
    }

    @Test
    void rejectedAircraftLeavesTheSimulation() {
        AircraftAgent a = agent(true, 0);
        a.onMessage(status(lastRequest(), InstructionStatus.REJECTED, null), now);
        assertTrue(a.isFinished());
    }

    @Test
    void departureWaitsAtTheGateForATaxiway() {
        AircraftAgent a = agent(false, 0);
        at(a, T0 + 31 * S);
        RunwayRequest req = lastRequest();
        long slot = now + 200 * S;
        a.onMessage(assigned(req, slot, null), now);
        a.onMessage(assigned(req, slot, null), now); // ACK receipt (otherwise the ACK is retried)
        int acks = (int) sent.stream().filter(s -> s.message() instanceof Ack).count();
        at(a, slot - 60 * S);
        assertEquals(Phase.PARKED, a.phase(), "no taxiway yet");
        a.onMessage(assigned(req, slot, "AAA-B"), now); // taxiway granted later
        at(a, now);
        assertEquals(Phase.TAXI_OUT, a.phase());
        assertEquals(acks, sent.stream().filter(s -> s.message() instanceof Ack).count(),
                "same runway and slot: no second ACK");
        assertInstanceOf(PositionUpdate.class, positions.get(positions.size() - 1));
    }

    @Test
    void unknownRequestErrorOnAckMakesTheAircraftReRegisterWithTheSameRequest() {
        AircraftAgent a = agent(true, 0);
        RunwayRequest req = lastRequest();
        Instruction assignment = assigned(req, T0 + 120 * S, null);
        a.onMessage(assignment, now);
        assertEquals(new Ack(req.requestId(), "AC1"), last());

        // The control service restarted and no longer knows the request.
        a.onMessage(new atc.common.ErrorMessage("unknown requestId " + req.requestId() + " for aircraft AC1"), now);
        assertEquals(req, last(), "re-registers with the SAME request (same requestId)");

        // The answer is the same assignment: ack it again so the new control service knows.
        a.onMessage(assignment, now);
        assertEquals(new Ack(req.requestId(), "AC1"), last());
        long acks = sent.stream().filter(s -> s.message() instanceof Ack).count();
        a.onMessage(assignment, now); // the ACK receipt
        assertEquals(acks, sent.stream().filter(s -> s.message() instanceof Ack).count(), "no ack ping-pong");
    }

    @Test
    void emergencyAircraftDeclaresItOnLandingRequestsOnly() {
        AircraftAgent a = new AircraftAgent("AC1", "AAA", SizeClass.SMALL, 0, P, out, new Random(7), "run1", 500, 0,
                true, true, now);
        assertTrue(lastRequest().emergency(), "landing request is an emergency");
        a.onMessage(status(lastRequest(), InstructionStatus.REJECTED, null), now);

        AircraftAgent d = new AircraftAgent("AC2", "AAA", SizeClass.SMALL, 1, P, out, new Random(7), "run1", 500, 0,
                false, true, now);
        at(d, T0 + 31 * S);
        assertEquals(Operation.TAKEOFF, lastRequest().operation());
        assertTrue(!lastRequest().emergency(), "departures are routine");
    }
}
