package atc.allocator;

import atc.common.Operation;
import atc.common.ReservationState;
import atc.common.SizeClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static atc.common.ReservationState.COMPLETED;
import static atc.common.ReservationState.CONFIRMED;
import static atc.common.ReservationState.PENDING_ACK;
import static atc.common.ReservationState.RELEASED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvariantCheckerTest {
    private static ReservationRecord rec(String id, ReservationState state, long start, long end, long at) {
        return new ReservationRecord(id, "AC-" + id, "AAA", Operation.LANDING, SizeClass.SMALL, false,
                state, "AAA-S1", start, end, null, at);
    }

    @Test
    void bookingBehindAnEarlyCompletionIsNotAViolation() {
        List<ReservationRecord> history = List.of(
                rec("a", PENDING_ACK, 0, 100, 0),
                rec("a", CONFIRMED, 0, 100, 1),
                rec("a", COMPLETED, 0, 100, 50),   // finished early at t=50
                rec("b", PENDING_ACK, 60, 160, 55)); // booked after that, inside a's original slot
        assertTrue(InvariantChecker.historicalOverlapViolations(history).isEmpty());
    }

    @Test
    void simultaneousOccupancyIsDetected() {
        List<ReservationRecord> history = List.of(
                rec("a", CONFIRMED, 0, 100, 0),
                rec("b", CONFIRMED, 50, 150, 1));
        assertEquals(1, InvariantChecker.historicalOverlapViolations(history).size());
        assertEquals(1, InvariantChecker.overlapViolations(List.of(history.get(0), history.get(1)), 0).size());
    }

    @Test
    void bookingReleasedBeforeItsSlotNeverOccupiedTheRunway() {
        List<ReservationRecord> history = List.of(
                rec("a", PENDING_ACK, 100, 200, 0),
                rec("a", RELEASED, 100, 200, 10),
                rec("b", CONFIRMED, 100, 200, 20));
        assertTrue(InvariantChecker.historicalOverlapViolations(history).isEmpty());
    }
}
