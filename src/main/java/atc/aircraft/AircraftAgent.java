package atc.aircraft;

import atc.common.Ack;
import atc.common.Complete;
import atc.common.ErrorMessage;
import atc.common.Instruction;
import atc.common.InstructionStatus;
import atc.common.Log;
import atc.common.Message;
import atc.common.Operation;
import atc.common.Phase;
import atc.common.PositionUpdate;
import atc.common.RunwayRequest;
import atc.common.SizeClass;

import java.util.Random;

/**
 * One simulated aircraft: a state machine driven by {@link #tick} (time) and
 * {@link #onMessage} (control service replies and pushes). No sockets: the driver supplies an
 * {@link Output}, so the behaviour is unit-testable with a controlled clock.
 *
 * Cycle: arrival (APPROACH, LANDING, TAXI_IN, PARKED) then turnaround, then departure
 * (TAXI_OUT, HOLDING_SHORT, TAKEOFF_ROLL, DEPARTED) then cruise, then back as an arrival.
 *
 * Distributed-systems behaviour that matters:
 * - Every request has a unique requestId. Retries (no reply within retryMs) resend the SAME
 *   requestId on a fresh socket (lazy pirate). A changed plan (missed slot, WAIT) uses a NEW one.
 * - HELD does not stop retries: the aircraft keeps asking until it gets a real decision, which
 *   also keeps it alive across a control-service restart.
 * - The aircraft only uses the runway inside its assigned slot. Too late for that, it asks
 *   again (a go-around for arrivals) instead of intruding on the next slot.
 * - Position seq starts at the simulator's start time in ms, so a restarted simulator's
 *   updates are not discarded as "old" by the control service's seq filter.
 */
public final class AircraftAgent {

    public interface Output {
        /** Send to the control service; reconnect=true: recreate this aircraft's socket first. */
        void send(String aircraftId, Message message, boolean reconnect);

        void publish(PositionUpdate position);
    }

    enum Stage {
        TURNAROUND(Phase.PARKED),
        AWAIT_DEPARTURE(Phase.PARKED),
        TAXI_OUT(Phase.TAXI_OUT),
        HOLDING_SHORT(Phase.HOLDING_SHORT),
        TAKEOFF_ROLL(Phase.TAKEOFF_ROLL),
        CRUISE(Phase.DEPARTED),
        AWAIT_ARRIVAL(Phase.APPROACH),
        FINAL(Phase.APPROACH),
        LANDING(Phase.LANDING),
        TAXI_IN(Phase.TAXI_IN),
        DONE_PARKED(Phase.PARKED),
        DONE_DEPARTED(Phase.DEPARTED),
        /** Rejected: no eligible runway; the aircraft leaves the simulation. */
        DONE_REJECTED(Phase.PARKED);

        final Phase phase;

        Stage(Phase phase) {
            this.phase = phase;
        }
    }

    private final String id;
    private final String airportId;
    private final SizeClass sizeClass;
    private final int index;
    private final SimParams p;
    private final Output out;
    private final Random rnd;
    private final String runId;
    private final int maxOps;
    private final Log log;

    private Stage stage;
    private long stageStart;
    private long stageEnd;
    private long seq;
    private long nextPositionAt;

    private int requestCounter;
    private String requestId;
    private Operation operation;
    /** Latest instruction for the current requestId. */
    private Instruction current;
    /** runway+slot of the assignment last acked; a new assignment (rebook) is acked again. */
    private String ackedAssignment;
    /** WAIT: when to ask again. */
    private long retryRequestAt;

    private Message outstanding;
    /** The current request, kept to re-register after a control-service restart. */
    private RunwayRequest lastRequest;
    private boolean emergency;
    private long outstandingDeadline;
    private int opsCompleted;

    /**
     * @param startAsArrival true: begins on approach; false: begins parked (turnaround)
     * @param maxOps         stop after this many runway operations (0 = run forever)
     * @param runId          unique per simulator run, so requestIds never collide with a
     *                       previous run's (the allocator remembers requestIds forever)
     */
    public AircraftAgent(String id, String airportId, SizeClass sizeClass, int index, SimParams params,
                         Output out, Random rnd, String runId, long seqStart, int maxOps,
                         boolean startAsArrival, long now) {
        this(id, airportId, sizeClass, index, params, out, rnd, runId, seqStart, maxOps, startAsArrival, false, now);
    }

    /** @param emergency declare an emergency on every landing request (simulator --emergency) */
    public AircraftAgent(String id, String airportId, SizeClass sizeClass, int index, SimParams params,
                         Output out, Random rnd, String runId, long seqStart, int maxOps,
                         boolean startAsArrival, boolean emergency, long now) {
        this.emergency = emergency;
        this.id = id;
        this.airportId = airportId;
        this.sizeClass = sizeClass;
        this.index = index;
        this.p = params;
        this.out = out;
        this.rnd = rnd;
        this.runId = runId;
        this.seq = seqStart;
        this.maxOps = maxOps;
        this.log = new Log("aircraft-" + airportId);
        if (startAsArrival) {
            enter(Stage.AWAIT_ARRIVAL, now, Long.MAX_VALUE);
            newRequest(Operation.LANDING, now + p.ms(p.approachLeadSec()), now);
        } else {
            // Short random first turnaround so departures don't all ask at the same instant.
            enter(Stage.TURNAROUND, now, now + p.ms(p.turnaroundMinSec() / 2 * rnd.nextDouble()));
        }
    }

    // ------------------------------------------------------------------ time

    public void tick(long now) {
        retryIfNoReply(now);
        switch (stage) {
            case TURNAROUND -> {
                if (now >= stageEnd) {
                    enter(Stage.AWAIT_DEPARTURE, now, Long.MAX_VALUE);
                    newRequest(Operation.TAKEOFF, now + p.ms(p.taxiSec()), now);
                }
            }
            case AWAIT_DEPARTURE -> {
                if (assigned()) {
                    if (now > latestRunwayEntry() - p.ms(p.taxiSec())) {
                        missedSlot(now, "cannot reach the runway in time");
                    } else if (current.taxiwayId() != null && now >= current.slotStart() - p.ms(p.taxiSec())) {
                        enter(Stage.TAXI_OUT, now, now + p.ms(p.taxiSec()));
                    }
                } else {
                    retryAfterWait(now, p.ms(p.taxiSec()));
                }
            }
            case TAXI_OUT -> {
                if (now >= stageEnd) {
                    enter(Stage.HOLDING_SHORT, now, Long.MAX_VALUE);
                }
            }
            case HOLDING_SHORT -> {
                if (assigned()) {
                    if (now > latestRunwayEntry()) {
                        missedSlot(now, "slot passed while holding short");
                    } else if (now >= current.slotStart()) {
                        enter(Stage.TAKEOFF_ROLL, now, current.slotEnd());
                    }
                } else {
                    retryAfterWait(now, 0);
                }
            }
            case TAKEOFF_ROLL -> {
                if (now >= stageEnd) {
                    complete(now);
                    boolean done = maxOps > 0 && opsCompleted >= maxOps;
                    enter(done ? Stage.DONE_DEPARTED : Stage.CRUISE, now,
                            now + p.ms(between(p.cruiseMinSec(), p.cruiseMaxSec())));
                }
            }
            case CRUISE -> {
                if (now >= stageEnd) {
                    enter(Stage.AWAIT_ARRIVAL, now, Long.MAX_VALUE);
                    newRequest(Operation.LANDING, now + p.ms(p.approachLeadSec()), now);
                }
            }
            case AWAIT_ARRIVAL -> {
                if (assigned()) {
                    enter(Stage.FINAL, now, current.slotStart());
                } else {
                    retryAfterWait(now, p.ms(p.approachLeadSec()));
                }
            }
            case FINAL -> {
                if (!assigned()) {
                    enter(Stage.AWAIT_ARRIVAL, now, Long.MAX_VALUE); // e.g. bumped: back to holding
                } else if (now > latestRunwayEntry()) {
                    enter(Stage.AWAIT_ARRIVAL, now, Long.MAX_VALUE);
                    missedSlot(now, "go-around");
                } else if (now >= current.slotStart()) {
                    enter(Stage.LANDING, now, current.slotEnd());
                }
            }
            case LANDING -> {
                if (now >= stageEnd) {
                    complete(now); // runway vacated
                    // Without a taxiway yet, wait at the runway exit (end = MAX) until granted.
                    boolean haveTaxiway = current != null && current.taxiwayId() != null;
                    enter(Stage.TAXI_IN, now, haveTaxiway ? now + p.ms(p.taxiSec()) : Long.MAX_VALUE);
                }
            }
            case TAXI_IN -> {
                if (stageEnd == Long.MAX_VALUE && current != null && current.taxiwayId() != null) {
                    stageStart = now; // taxiway granted: start moving
                    stageEnd = now + p.ms(p.taxiSec());
                }
                if (now >= stageEnd) {
                    boolean done = maxOps > 0 && opsCompleted >= maxOps;
                    enter(done ? Stage.DONE_PARKED : Stage.TURNAROUND, now,
                            now + p.ms(between(p.turnaroundMinSec(), p.turnaroundMaxSec())));
                }
            }
            default -> {
                // DONE_*: nothing left to do
            }
        }
        if (now >= nextPositionAt) {
            publishPosition(now);
        }
    }

    // ------------------------------------------------------------------ messages

    public void onMessage(Message m, long now) {
        if (m instanceof Instruction i) {
            onInstruction(i, now);
        } else if (m instanceof Complete c) {
            if (outstanding instanceof Complete pending && pending.requestId().equals(c.requestId())) {
                outstanding = null;
            }
        } else if (m instanceof ErrorMessage e) {
            log.warn("%s got ERROR: %s", id, e.reason());
            if (outstanding instanceof Ack && lastRequest != null && e.reason() != null
                    && e.reason().startsWith("unknown requestId")) {
                // The control service restarted and forgot us: re-register with the SAME
                // request. It is answered from the allocator's record (dedup), not re-decided.
                log.warn("%s re-registering %s with the control service", id, requestId);
                sendExpectingReply(lastRequest, now, false);
            } else {
                outstanding = null; // retrying would only repeat the error
            }
        }
    }

    private void onInstruction(Instruction i, long now) {
        if (requestId == null || !requestId.equals(i.requestId())) {
            return; // about an older request
        }
        boolean changed = current == null || !current.equals(i);
        // An answer to a (re)sent RUNWAY_REQUEST, as opposed to an ACK receipt or a push.
        boolean answersRequest = outstanding instanceof RunwayRequest;
        current = i;
        // HELD is not an answer: keep retrying the request until a real decision arrives.
        if (i.status() != InstructionStatus.HELD && (outstanding instanceof RunwayRequest || outstanding instanceof Ack)) {
            outstanding = null;
        }
        if (changed) {
            log.info("%s %s %s%s%s", id, i.status(), i.runwayId() == null ? "" : i.runwayId(),
                    i.taxiwayId() == null ? "" : " via " + i.taxiwayId(),
                    i.reason() == null ? "" : " (" + i.reason() + ")");
        }
        switch (i.status()) {
            case ASSIGNED -> {
                String assignment = i.runwayId() + "@" + i.slotStart();
                // Ack a new assignment, and also re-ack one that answers a re-registration (a
                // restarted control service may not know our earlier ack). Never ack an ACK
                // receipt: that would ping-pong forever.
                if (!assignment.equals(ackedAssignment) || answersRequest) {
                    ackedAssignment = assignment;
                    sendExpectingReply(new Ack(requestId, id), now, false);
                }
            }
            case WAIT -> {
                long lead = operation == Operation.TAKEOFF ? p.ms(p.taxiSec()) : p.ms(p.approachLeadSec());
                long estimate = i.slotStart() == null ? now : i.slotStart();
                retryRequestAt = Math.max(now + p.ms(10), estimate - lead);
            }
            case REJECTED -> {
                log.warn("%s rejected (%s): leaving the simulation", id, i.reason());
                enter(Stage.DONE_REJECTED, now, Long.MAX_VALUE);
                outstanding = null;
            }
            default -> {
                // HELD: keep waiting (and retrying)
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean assigned() {
        return current != null && current.status() == InstructionStatus.ASSIGNED && current.slotStart() != null;
    }

    /**
     * Last moment the aircraft may still enter the runway: it must be able to finish by the
     * slot end, with at least half a slot of occupancy, so it never spills past its slot.
     */
    private long latestRunwayEntry() {
        return current.slotEnd() - (current.slotEnd() - current.slotStart()) / 2;
    }

    private void retryAfterWait(long now, long leadMs) {
        if (current != null && current.status() == InstructionStatus.WAIT && now >= retryRequestAt) {
            newRequest(operation, now + leadMs, now);
        }
    }

    private void missedSlot(long now, String why) {
        log.warn("%s missed slot on %s (%s); requesting a new one", id, current.runwayId(), why);
        long lead = stage == Stage.AWAIT_ARRIVAL ? p.ms(p.approachLeadSec())
                : stage == Stage.AWAIT_DEPARTURE ? p.ms(p.taxiSec()) : 0;
        newRequest(operation, now + lead, now); // control service supersedes the old booking
    }

    private void newRequest(Operation op, long earliestStart, long now) {
        requestCounter++;
        operation = op;
        requestId = id + "/" + runId + "-" + requestCounter;
        current = null;
        ackedAssignment = null;
        // Emergency aircraft declare it on their landing requests (departures are routine).
        lastRequest = new RunwayRequest(requestId, id, airportId, op, sizeClass,
                emergency && op == Operation.LANDING, now, earliestStart);
        sendExpectingReply(lastRequest, now, false);
    }

    private void complete(long now) {
        opsCompleted++;
        sendExpectingReply(new Complete(requestId, id), now, false);
    }

    private void sendExpectingReply(Message m, long now, boolean reconnect) {
        outstanding = m;
        outstandingDeadline = now + p.retryMs();
        out.send(id, m, reconnect);
    }

    /** Lazy pirate: no reply in time, so recreate the socket and resend the same message. */
    private void retryIfNoReply(long now) {
        if (outstanding != null && now >= outstandingDeadline) {
            log.warn("%s no reply to %s within %d ms; resending on a new socket", id,
                    outstanding.getClass().getSimpleName(), p.retryMs());
            sendExpectingReply(outstanding, now, true);
        }
    }

    private void enter(Stage next, long now, long end) {
        if (stage != next) {
            if (stage != null) {
                log.info("%s %s -> %s", id, stage.phase, next.phase == stage.phase ? next : next.phase);
            }
            stage = next;
            nextPositionAt = now; // publish phase changes immediately
        }
        stageStart = now;
        stageEnd = end;
    }

    private double between(double min, double max) {
        return min + (max - min) * rnd.nextDouble();
    }

    private void publishPosition(long now) {
        double[] w = where(now);
        out.publish(new PositionUpdate(id, airportId, ++seq, stage.phase, w[0], w[1], w[2], w[3], now));
        nextPositionAt = now + p.positionPeriodMs();
    }

    /** Synthetic geometry (metres): runway along y=0 from x=0 to 2500; gates south of it. */
    private double[] where(long now) {
        double gateX = 400 + 150 * index;
        double gateY = -900;
        double f = stageEnd == Long.MAX_VALUE || stageEnd <= stageStart ? 0
                : Math.min(1, Math.max(0, (now - stageStart) / (double) (stageEnd - stageStart)));
        return switch (stage) {
            case TURNAROUND, AWAIT_DEPARTURE, DONE_PARKED, DONE_REJECTED -> new double[]{gateX, gateY, 0, 0};
            case TAXI_OUT -> new double[]{gateX * (1 - f), gateY + (gateY * -1 - 150) * f, 0, 15};
            case HOLDING_SHORT -> new double[]{0, -150, 0, 0};
            case TAKEOFF_ROLL -> new double[]{2500 * f, 0, 0, 160 * f};
            case CRUISE, DONE_DEPARTED -> new double[]{20000, 5000, 10000, 250};
            case AWAIT_ARRIVAL -> {
                double a = (now % 60000) / 60000.0 * 2 * Math.PI; // holding pattern
                yield new double[]{-20000 + 3000 * Math.cos(a), 3000 * Math.sin(a), 5000, 210};
            }
            case FINAL -> new double[]{-15000 * (1 - f), 0, 3000 * (1 - f), 160};
            case LANDING -> new double[]{2500 * f, 0, 0, 140 * (1 - f) + 20};
            case TAXI_IN -> new double[]{2500 + (gateX - 2500) * f, -150 + (gateY + 150) * f, 0,
                    stageEnd == Long.MAX_VALUE ? 0 : 15};
        };
    }

    // ------------------------------------------------------------------ observers

    /** Make this aircraft declare an emergency on its landing requests from now on. */
    public void setEmergency(boolean emergency) {
        this.emergency = emergency;
    }

    public String id() {
        return id;
    }

    public Phase phase() {
        return stage.phase;
    }

    Stage stage() {
        return stage;
    }

    public boolean isDone() {
        return stage == Stage.DONE_PARKED || stage == Stage.DONE_DEPARTED || stage == Stage.DONE_REJECTED;
    }

    /** Done and nothing left to deliver (the final COMPLETE was acknowledged). */
    public boolean isFinished() {
        return isDone() && outstanding == null;
    }

    public int opsCompleted() {
        return opsCompleted;
    }

    String requestId() {
        return requestId;
    }
}
