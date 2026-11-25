package atc.allocator;

import atc.common.AtcConfig;
import atc.common.Complete;
import atc.common.Confirm;
import atc.common.ErrorMessage;
import atc.common.Heartbeat;
import atc.common.Message;
import atc.common.Query;
import atc.common.Release;
import atc.common.Reservation;
import atc.common.Reserve;
import atc.common.SizeClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static atc.allocator.TestFixtures.SEC;
import static atc.allocator.TestFixtures.T0;
import static atc.allocator.TestFixtures.emergency;
import static atc.allocator.TestFixtures.reserve;
import static atc.common.ReservationState.BUMPED;
import static atc.common.ReservationState.COMPLETED;
import static atc.common.ReservationState.CONFIRMED;
import static atc.common.ReservationState.PENDING_ACK;
import static atc.common.ReservationState.REJECTED;
import static atc.common.ReservationState.RELEASED;
import static atc.common.ReservationState.WAIT;
import static atc.common.SizeClass.HEAVY;
import static atc.common.SizeClass.MEDIUM;
import static atc.common.SizeClass.SMALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocatorCoreTest {
    @TempDir
    Path dir;

    private final AtcConfig config = TestFixtures.config();
    private final AtomicLong now = new AtomicLong(T0);
    private ReservationLog log;
    private AllocatorCore core;

    @BeforeEach
    void setUp() throws Exception {
        log = ReservationLog.open(dir.resolve("log.jsonl"));
        core = new AllocatorCore(config, now::get, log);
    }

    @AfterEach
    void tearDown() throws Exception {
        log.close();
    }

    private Reservation ok(Message m) {
        return assertInstanceOf(Reservation.class, core.handle(m).reply());
    }

    private ErrorMessage err(Message m) {
        return assertInstanceOf(ErrorMessage.class, core.handle(m).reply());
    }

    @Test
    void assignsEarliestSlotOnShortestEligibleRunway() {
        Reservation r = ok(reserve("r1", "AAA", SMALL));
        assertEquals(PENDING_ACK, r.state());
        assertEquals("AAA-S1", r.runwayId());   // best fit: leaves long runways free
        assertEquals(T0, r.slotStart());
        assertEquals(T0 + 60 * SEC, r.slotEnd());

        // Same moment: S1 is busy, an equally early slot exists on M1.
        Reservation r2 = ok(reserve("r2", "AAA", SMALL));
        assertEquals("AAA-M1", r2.runwayId());
        assertEquals(T0, r2.slotStart());
    }

    @Test
    void neverAssignsRunwayAboveItsMaxClassOrTooShort() {
        for (int i = 0; i < 6; i++) {
            Reservation r = ok(reserve("m" + i, "AAA", MEDIUM));
            assertNotEquals("AAA-S1", r.runwayId());
            Reservation h = ok(reserve("h" + i, "AAA", HEAVY));
            if (h.runwayId() != null) {
                assertEquals("AAA-L1", h.runwayId());
            }
        }
        assertTrue(InvariantChecker.eligibilityViolations(core.snapshot(), config).isEmpty());
    }

    @Test
    void heavyAtSmallOnlyAirportIsRejectedWithReason() {
        Reservation r = ok(reserve("h1", "BBB", HEAVY));
        assertEquals(REJECTED, r.state());
        assertEquals(null, r.runwayId());
        assertTrue(r.reason().contains("HEAVY"), r.reason());
    }

    @Test
    void retriedRequestIdReturnsOriginalOutcomeWithoutSecondReservation() throws Exception {
        Reservation first = ok(reserve("r1", "AAA", MEDIUM));
        now.addAndGet(5 * SEC); // a retry arrives later; outcome must not be re-evaluated
        Reservation retry = ok(reserve("r1", "AAA", MEDIUM));
        assertEquals(first, retry);
        assertEquals(1, core.snapshot().size());
        assertEquals(1, Files.readAllLines(log.path()).size(), "retry must not write a new decision");

        Reservation rejected = ok(reserve("h1", "BBB", HEAVY));
        assertEquals(rejected, ok(reserve("h1", "BBB", HEAVY)));
    }

    @Test
    void slotBeyondThresholdIsWaitAndHoldsNoRunwayTime() {
        // BBB has one runway; slots at +0, +120, +240 are within the 300 s threshold.
        for (int i = 0; i < 3; i++) {
            Reservation r = ok(reserve("b" + i, "BBB", SMALL));
            assertEquals(PENDING_ACK, r.state());
            assertEquals(T0 + i * 120 * SEC, r.slotStart());
        }
        Reservation w1 = ok(reserve("w1", "BBB", SMALL));
        Reservation w2 = ok(reserve("w2", "BBB", SMALL));
        assertEquals(WAIT, w1.state());
        assertEquals(T0 + 360 * SEC, w1.slotStart(), "estimate");
        // w1 did not take the slot, so w2 gets the same estimate.
        assertEquals(w1.slotStart(), w2.slotStart());
        assertTrue(InvariantChecker.overlapViolations(core.snapshot(), 30 * SEC).isEmpty());
    }

    @Test
    void manySimultaneousRequestsNeverOverlap() {
        Random rnd = new Random(42); // deterministic
        SizeClass[] sizes = SizeClass.values();
        for (int i = 0; i < 200; i++) {
            String airport = rnd.nextBoolean() ? "AAA" : "BBB";
            Message reply = core.handle(reserve("s" + i, airport, sizes[rnd.nextInt(sizes.length)])).reply();
            assertInstanceOf(Reservation.class, reply);
            if (i % 7 == 0) {
                now.addAndGet(SEC); // a few requests a little later, most at the same instant
            }
        }
        assertTrue(InvariantChecker.overlapViolations(core.snapshot(), 30 * SEC).isEmpty());
        assertTrue(InvariantChecker.eligibilityViolations(core.snapshot(), config).isEmpty());
    }

    @Test
    void confirmCompleteAndReleaseFollowTheStateMachine() {
        ok(reserve("r1", "BBB", SMALL));
        assertEquals(CONFIRMED, ok(new Confirm("r1")).state());
        assertEquals(CONFIRMED, ok(new Confirm("r1")).state(), "repeat confirm is a no-op");
        assertEquals(COMPLETED, ok(new Complete("r1", "AC-r1")).state());
        assertEquals(COMPLETED, ok(new Release("r1")).state(), "terminal state is not changed");
        assertEquals(COMPLETED, ok(new Query("r1")).state());
    }

    @Test
    void releasedAndCompletedSlotsCanBeReused() {
        Reservation a = ok(reserve("a", "BBB", SMALL));
        assertEquals(RELEASED, ok(new Release("a")).state());
        Reservation b = ok(reserve("b", "BBB", SMALL));
        assertEquals(a.slotStart(), b.slotStart(), "released slot is free again");

        ok(new Confirm("b"));
        ok(new Complete("b", "AC-b"));
        assertEquals(b.slotStart(), ok(reserve("c", "BBB", SMALL)).slotStart());
    }

    @Test
    void emergencyBumpsPendingButNotConfirmedReservations() {
        Reservation confirmed = ok(reserve("c1", "BBB", SMALL));   // slot +0
        ok(new Confirm("c1"));
        Reservation pending = ok(reserve("p1", "BBB", SMALL));     // slot +120, not yet acked

        AllocatorCore.Outcome out = core.handle(emergency("e1", "BBB", SMALL));
        Reservation e = assertInstanceOf(Reservation.class, out.reply());
        assertEquals(PENDING_ACK, e.state());
        assertEquals(pending.slotStart(), e.slotStart(), "takes the earliest slot not held by an active reservation");
        assertEquals(1, out.bumped().size());
        assertEquals("p1", out.bumped().get(0).requestId());
        assertEquals(BUMPED, ok(new Query("p1")).state());
        assertEquals(CONFIRMED, ok(new Query("c1")).state());
        assertEquals(confirmed.slotStart(), ok(new Query("c1")).slotStart());
        assertTrue(InvariantChecker.overlapViolations(core.snapshot(), 30 * SEC).isEmpty());
    }

    @Test
    void emergencyDoesNotBumpAnotherEmergencyAndNeverWaits() {
        ok(emergency("e1", "BBB", SMALL));
        AllocatorCore.Outcome out = core.handle(emergency("e2", "BBB", SMALL));
        assertTrue(out.bumped().isEmpty());
        Reservation e2 = assertInstanceOf(Reservation.class, out.reply());
        assertEquals(T0 + 120 * SEC, e2.slotStart());

        for (int i = 0; i < 5; i++) {
            assertEquals(PENDING_ACK, ok(emergency("x" + i, "BBB", SMALL)).state(), "emergencies are never told to WAIT");
        }
        assertTrue(InvariantChecker.overlapViolations(core.snapshot(), 30 * SEC).isEmpty());
    }

    @Test
    void invalidRequestsGetErrorsAndChangeNothing() {
        err(new Reserve("r", null, "AAA", atc.common.Operation.LANDING, SMALL, false));
        err(new Reserve("", "AC", "AAA", atc.common.Operation.LANDING, SMALL, false));
        err(new Reserve("r", "AC", "ZZZ", atc.common.Operation.LANDING, SMALL, false));
        err(new Reserve("r", "AC", "AAA", null, SMALL, false));
        err(new Reserve("r", "AC", "AAA", atc.common.Operation.LANDING, null, false));
        err(new Confirm("nope"));
        err(new Query("nope"));
        err(new Heartbeat("x", 1));
        err(null);
        assertTrue(core.snapshot().isEmpty());
    }

    @Test
    void earliestStartDelaysTheSlotAndWaitIsMeasuredFromIt() {
        Reserve withEta = new Reserve("eta", "AC-eta", "BBB", atc.common.Operation.TAKEOFF, SMALL, false, T0 + 100 * SEC);
        assertEquals(T0 + 100 * SEC, ok(withEta).slotStart());

        // ETA 1000 s out on a free runway: the slot is at the ETA, which is no delay at all -> not WAIT.
        Reserve far = new Reserve("far", "AC-far", "AAA", atc.common.Operation.LANDING, MEDIUM, false, T0 + 1000 * SEC);
        Reservation r = ok(far);
        assertEquals(PENDING_ACK, r.state());
        assertEquals(T0 + 1000 * SEC, r.slotStart());

        // ETA in the past is clamped to now.
        assertEquals(T0, ok(new Reserve("past", "AC-past", "AAA", atc.common.Operation.LANDING, HEAVY, false, T0 - 50 * SEC)).slotStart());
    }
}
