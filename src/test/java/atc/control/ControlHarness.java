package atc.control;

import atc.allocator.AllocatorCore;
import atc.allocator.ReservationLog;
import atc.allocator.ReservationRecord;
import atc.allocator.TestFixtures;
import atc.common.AtcConfig;
import atc.common.Event;
import atc.common.Heartbeat;
import atc.common.Instruction;
import atc.common.JsonCodec;
import atc.common.Message;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wires a ControlCore to a REAL AllocatorCore in-process, with no sockets. The test decides
 * when (or whether) allocator messages are delivered, so lost replies and outages are
 * deterministic.
 */
final class ControlHarness implements ControlCore.Output {
    record Pushed(String aircraftId, Message message) {
    }

    record Sent(String key, Message command) {
    }

    static final long TIMEOUT_MS = 2000;
    static final long HEARTBEAT_MS = 100;

    final AtcConfig config = TestFixtures.config();
    final AtomicLong now = new AtomicLong(TestFixtures.T0);
    final List<Pushed> pushed = new ArrayList<>();
    final Deque<Sent> toAllocator = new ArrayDeque<>();
    final List<Message> published = new ArrayList<>();
    static final long ACK_TIMEOUT_MS = 3 * TIMEOUT_MS;

    private final Path dir;
    private final String airportId;
    ReservationLog log;
    AllocatorCore allocator;
    ControlCore control;
    /** Simulated allocator incarnation, sent as Heartbeat.startedAt. */
    long allocatorStartedAt = 1;

    ControlHarness(Path dir, String airportId) throws Exception {
        this.dir = dir;
        this.airportId = airportId;
        log = ReservationLog.open(dir.resolve("alloc.jsonl"));
        allocator = new AllocatorCore(config, now::get, log);
        control = newControl();
    }

    private ControlCore newControl() {
        return new ControlCore(config.airport(airportId).orElseThrow(), TIMEOUT_MS, HEARTBEAT_MS, now::get, this);
    }

    /** Crash + restart the allocator on the same log (state rebuilt, PENDING_ACK -> UNCERTAIN). */
    void restartAllocator() throws Exception {
        toAllocator.clear(); // whatever was in flight died with it
        log.close();
        log = ReservationLog.open(dir.resolve("alloc.jsonl"));
        allocator = new AllocatorCore(config, now::get, log);
        allocatorStartedAt++;
    }

    /** Restart the allocator with its log LOST (new empty file). */
    void restartAllocatorWithEmptyLog() throws Exception {
        toAllocator.clear();
        log.close();
        log = ReservationLog.open(dir.resolve("alloc-" + (allocatorStartedAt + 1) + ".jsonl"));
        allocator = new AllocatorCore(config, now::get, log);
        allocatorStartedAt++;
    }

    /** Crash + restart the control service: all its (soft) state is gone. */
    void restartControl() {
        toAllocator.clear();
        control = newControl();
    }

    @Override
    public void toAircraft(String aircraftId, Message message) {
        pushed.add(new Pushed(aircraftId, message));
    }

    @Override
    public boolean toAllocator(String key, Message command) {
        toAllocator.add(new Sent(key, command));
        return true;
    }

    @Override
    public void publish(Message message) {
        published.add(message);
    }

    /** Allocator heartbeat -> control believes the allocator is reachable. */
    void allocatorHeartbeat() {
        control.onAllocatorBroadcast(new Heartbeat("allocator", now.get(), allocatorStartedAt));
    }

    /** Deliver every queued command to the allocator and its reply back; bumps become PUB events. */
    void deliverAll() {
        while (!toAllocator.isEmpty()) {
            Sent s = toAllocator.poll();
            AllocatorCore.Outcome outcome = allocator.handle(s.command());
            control.onAllocatorReply(s.key(), outcome.reply());
            broadcastBumps(outcome);
        }
    }

    void broadcastBumps(AllocatorCore.Outcome outcome) {
        for (ReservationRecord b : outcome.bumped()) {
            control.onAllocatorBroadcast(new Event("allocator", "BUMPED", JsonCodec.encode(b.toReply()), now.get()));
        }
    }

    /** Lose everything in flight (allocator down / network partition). */
    void dropAll() {
        toAllocator.clear();
    }

    void advance(long ms) {
        now.addAndGet(ms);
        control.tick();
    }

    List<Instruction> instructionsTo(String aircraftId) {
        return pushed.stream()
                .filter(p -> p.aircraftId().equals(aircraftId) && p.message() instanceof Instruction)
                .map(p -> (Instruction) p.message())
                .toList();
    }

    Instruction lastInstructionTo(String aircraftId) {
        List<Instruction> list = instructionsTo(aircraftId);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    List<Event> events(String kind) {
        return published.stream()
                .filter(m -> m instanceof Event e && e.kind().equals(kind))
                .map(m -> (Event) m)
                .toList();
    }
}
