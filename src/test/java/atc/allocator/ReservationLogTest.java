package atc.allocator;

import atc.common.AtcConfig;
import atc.common.Confirm;
import atc.common.ErrorMessage;
import atc.common.Operation;
import atc.common.Reservation;
import atc.common.ReservationState;
import atc.common.SizeClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static atc.allocator.TestFixtures.SEC;
import static atc.allocator.TestFixtures.T0;
import static atc.allocator.TestFixtures.reserve;
import static atc.common.ReservationState.CONFIRMED;
import static atc.common.ReservationState.PENDING_ACK;
import static atc.common.ReservationState.UNCERTAIN;
import static atc.common.SizeClass.SMALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationLogTest {
    @TempDir
    Path dir;

    private final AtcConfig config = TestFixtures.config();
    private final AtomicLong now = new AtomicLong(T0);

    private static ReservationRecord record(String id, ReservationState state) {
        return new ReservationRecord(id, "AC-" + id, "AAA", Operation.LANDING, SizeClass.SMALL, false,
                state, "AAA-S1", T0, T0 + 60 * SEC, null, T0);
    }

    @Test
    void appendedRecordsAreReplayedInOrder() throws Exception {
        Path file = dir.resolve("sub/dir/log.jsonl"); // parent dirs are created
        try (ReservationLog log = ReservationLog.open(file)) {
            assertTrue(log.recovered().isEmpty());
            log.append(record("a", PENDING_ACK));
            log.append(record("b", PENDING_ACK));
            log.append(record("a", CONFIRMED));
        }
        try (ReservationLog log = ReservationLog.open(file)) {
            assertEquals(List.of(record("a", PENDING_ACK), record("b", PENDING_ACK), record("a", CONFIRMED)),
                    log.recovered());
        }
    }

    @Test
    void tornTailFromCrashIsCutSoLaterAppendsStayReadable() throws Exception {
        Path file = dir.resolve("log.jsonl");
        try (ReservationLog log = ReservationLog.open(file)) {
            log.append(record("a", PENDING_ACK));
            log.append(record("b", PENDING_ACK));
        }
        // Simulate a crash half-way through writing a third line.
        Files.write(file, "{\"requestId\":\"c\",\"aircr".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        try (ReservationLog log = ReservationLog.open(file)) {
            assertEquals(2, log.recovered().size());
            log.append(record("d", PENDING_ACK));
        }
        try (ReservationLog log = ReservationLog.open(file)) {
            assertEquals(List.of("a", "b", "d"), log.recovered().stream().map(ReservationRecord::requestId).toList());
        }
    }

    @Test
    void corruptCompleteLineIsSkippedNotFatal() throws Exception {
        Path file = dir.resolve("log.jsonl");
        try (ReservationLog log = ReservationLog.open(file)) {
            log.append(record("a", PENDING_ACK));
        }
        Files.write(file, "garbage line\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        try (ReservationLog log = ReservationLog.open(file)) {
            log.append(record("b", PENDING_ACK));
        }
        try (ReservationLog log = ReservationLog.open(file)) {
            assertEquals(2, log.recovered().size());
        }
    }

    @Test
    void decisionIsOnDiskBeforeTheReplyIsReturned() throws Exception {
        Path file = dir.resolve("log.jsonl");
        try (ReservationLog log = ReservationLog.open(file)) {
            AllocatorCore core = new AllocatorCore(config, now::get, log);
            Reservation r = assertInstanceOf(Reservation.class, core.handle(reserve("r1", "AAA", SMALL)).reply());
            // Read the file while the log is still open: the decision must already be there.
            String onDisk = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(onDisk.contains("\"requestId\":\"r1\"") && onDisk.contains(r.runwayId()), onDisk);
        }
    }

    @Test
    void failedAppendMeansErrorReplyAndNoStateChange() throws Exception {
        ReservationLog log = ReservationLog.open(dir.resolve("log.jsonl"));
        AllocatorCore core = new AllocatorCore(config, now::get, log);
        log.close(); // every append now fails
        ErrorMessage e = assertInstanceOf(ErrorMessage.class, core.handle(reserve("r1", "AAA", SMALL)).reply());
        assertTrue(e.reason().startsWith("storage failure"), e.reason());
        assertTrue(core.snapshot().isEmpty());
    }

    @Test
    void restartRebuildsStateAndMarksPendingAckUncertain() throws Exception {
        Path file = dir.resolve("log.jsonl");
        Reservation pending;
        Reservation confirmed;
        try (ReservationLog log = ReservationLog.open(file)) {
            AllocatorCore core = new AllocatorCore(config, now::get, log);
            pending = (Reservation) core.handle(reserve("p1", "BBB", SMALL)).reply();
            confirmed = (Reservation) core.handle(reserve("c1", "BBB", SMALL)).reply();
            core.handle(new Confirm("c1"));
        } // "crash"

        now.addAndGet(10 * SEC);
        try (ReservationLog log = ReservationLog.open(file)) {
            AllocatorCore core = new AllocatorCore(config, now::get, log);
            assertEquals(UNCERTAIN, core.get("p1").orElseThrow().state());
            assertEquals(CONFIRMED, core.get("c1").orElseThrow().state());
            assertEquals(pending.slotStart(), core.get("p1").orElseThrow().slotStart());

            // Dedup survives the restart: a retry gets the existing record, no new slot.
            Reservation retry = (Reservation) core.handle(reserve("p1", "BBB", SMALL)).reply();
            assertEquals(UNCERTAIN, retry.state());
            assertEquals(pending.slotStart(), retry.slotStart());

            // UNCERTAIN still blocks its runway: a new request goes after both existing slots.
            Reservation fresh = (Reservation) core.handle(reserve("n1", "BBB", SMALL)).reply();
            assertTrue(fresh.slotStart() > confirmed.slotStart(), "new slot " + fresh.slotStart());
            assertTrue(InvariantChecker.overlapViolations(core.snapshot(), 30 * SEC).isEmpty());

            // Reconciliation: the control service learns the aircraft had acked -> CONFIRM.
            assertEquals(CONFIRMED, ((Reservation) core.handle(new Confirm("p1")).reply()).state());
        }

        // The UNCERTAIN transition itself was logged, so a second restart doesn't redo it.
        try (ReservationLog log = ReservationLog.open(file)) {
            AllocatorCore core = new AllocatorCore(config, now::get, log);
            assertEquals(CONFIRMED, core.get("p1").orElseThrow().state());
            // n1 was PENDING_ACK at the second "crash", so it is UNCERTAIN now.
            assertEquals(UNCERTAIN, core.get("n1").orElseThrow().state());
        }
    }
}
