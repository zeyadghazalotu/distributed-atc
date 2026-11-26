package atc.control;

import atc.common.Ack;
import atc.common.AtcClock;
import atc.common.AtcConfig.AirportConfig;
import atc.common.CodecException;
import atc.common.Complete;
import atc.common.Confirm;
import atc.common.ControlState;
import atc.common.ErrorMessage;
import atc.common.Event;
import atc.common.Heartbeat;
import atc.common.Instruction;
import atc.common.InstructionStatus;
import atc.common.JsonCodec;
import atc.common.Log;
import atc.common.Message;
import atc.common.Phase;
import atc.common.PositionUpdate;
import atc.common.Query;
import atc.common.Reservation;
import atc.common.Reserve;
import atc.common.RunwayRequest;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decision logic of one airport's control service (ground + air control), with no sockets.
 *
 * Driven by four inputs (aircraft messages, position updates, allocator replies, allocator
 * broadcasts) plus a periodic {@link #tick}. It talks back only through {@link Output}. Exactly
 * one thread calls it, the same single-owner design as the allocator.
 *
 * Safety rule (invariant 7): an ASSIGNED instruction is only ever built from a RESERVATION
 * reply the allocator actually sent. When the allocator is silent, requests go HELD and the
 * same RESERVE is retried. No local fallback ever hands out a runway.
 */
public final class ControlCore {

    /** Where the core's outputs go: sockets in production, lists in tests. */
    public interface Output {
        /** Push a message to an aircraft (unsolicited, e.g. a new instruction after a bump). */
        void toAircraft(String aircraftId, Message message);

        /** Send a command to the allocator, correlated by {@code key}. */
        boolean toAllocator(String key, Message command);

        /** Publish on this service's event feed (Monitor, evidence logs). */
        void publish(Message message);
    }

    private final Log log;
    private final String service;
    private final String airportId;
    private final AtcClock clock;
    private final Output out;
    private final long timeoutMs;
    private final long heartbeatMs;
    private final AllocatorLink allocator;
    private final AircraftView view;
    private final TaxiwayManager taxiways;

    /** By the aircraft's requestId. */
    private final Map<String, ControlRequest> requests = new LinkedHashMap<>();
    /** By the allocator-facing requestId (differs after a bump). */
    private final Map<String, ControlRequest> byAllocatorId = new HashMap<>();
    private long nextHeartbeat;
    /** An ASSIGNED request not acked within this is released (the aircraft is gone or deaf). */
    private final long ackTimeoutMs;
    /** startedAt of the allocator incarnation we last heard from; a change means it restarted. */
    private Long allocatorStartedAt;

    /** Ack timeout defaults to 3x the network timeout: longer than an aircraft's ACK retry (1.5x). */
    public ControlCore(AirportConfig airport, long timeoutMs, long heartbeatMs, AtcClock clock, Output out) {
        this(airport, timeoutMs, heartbeatMs, 3 * timeoutMs, clock, out);
    }

    public ControlCore(AirportConfig airport, long timeoutMs, long heartbeatMs, long ackTimeoutMs,
                       AtcClock clock, Output out) {
        this.ackTimeoutMs = ackTimeoutMs;
        this.airportId = airport.id();
        this.service = "control-" + airportId;
        this.log = new Log(service);
        this.clock = clock;
        this.out = out;
        this.timeoutMs = timeoutMs;
        this.heartbeatMs = heartbeatMs;
        // Allocator heartbeats every heartbeatMs; three missed in a row = presumed down.
        this.allocator = new AllocatorLink(out::toAllocator, timeoutMs, 3 * heartbeatMs);
        this.view = new AircraftView(airportId);
        this.taxiways = new TaxiwayManager(airport.taxiways());
    }

    public String service() {
        return service;
    }

    // ------------------------------------------------------------------ aircraft input

    /**
     * Handles a message from an aircraft and returns the direct reply, or null when the answer
     * is not known yet (it is pushed via {@link Output#toAircraft} once the allocator replies).
     */
    public Message onAircraftMessage(Message m) {
        if (m instanceof RunwayRequest r) {
            return onRunwayRequest(r);
        } else if (m instanceof Ack a) {
            return onAck(a);
        } else if (m instanceof Complete c) {
            return onComplete(c);
        } else if (m instanceof PositionUpdate p) {
            onPosition(p); // normally arrives via pub/sub, but accept it here too
            return null;
        }
        return new ErrorMessage("unsupported message for control service: "
                + (m == null ? "null" : m.getClass().getSimpleName()));
    }

    private Message onRunwayRequest(RunwayRequest r) {
        if (blank(r.requestId()) || blank(r.aircraftId()) || r.operation() == null || r.sizeClass() == null) {
            return new ErrorMessage("RUNWAY_REQUEST needs requestId, aircraftId, operation and sizeClass");
        }
        ControlRequest existing = requests.get(r.requestId());
        if (existing != null) {
            if (!existing.aircraftId.equals(r.aircraftId())) {
                return new ErrorMessage("requestId " + r.requestId() + " belongs to another aircraft");
            }
            // Retry from the aircraft: same answer as before. Null means the decision is still
            // pending and will be pushed when it arrives.
            return existing.lastInstruction;
        }

        supersedeActiveRequestsOf(r.aircraftId());
        ControlRequest req = new ControlRequest(r, clock.nowMillis());
        requests.put(req.requestId, req);
        event("REQUEST", req.aircraftId + " " + req.operation + " " + req.sizeClass
                + (req.emergency ? " EMERGENCY" : "") + " id=" + req.requestId);

        if (!airportId.equals(r.airportId())) {
            req.status = RequestStatus.REJECTED;
            return instruction(req, InstructionStatus.REJECTED, "this is " + airportId + ", not " + r.airportId());
        }
        sendReserve(req);
        if (!allocator.reachable()) {
            req.status = RequestStatus.HELD;
            return instruction(req, InstructionStatus.HELD, "allocator unreachable; hold until a decision arrives");
        }
        return null;
    }

    /**
     * An aircraft has at most one active request. A new one (e.g. after a missed slot or a
     * WAIT) supersedes the old: its booking is RELEASEd at the allocator and its taxiway freed,
     * so runway time and taxiways are never leaked by aircraft that changed plans.
     */
    private void supersedeActiveRequestsOf(String aircraftId) {
        for (ControlRequest old : requests.values()) {
            if (!old.aircraftId.equals(aircraftId)) {
                continue;
            }
            boolean active = switch (old.status) {
                case FORWARDED, HELD, ASSIGNED, ACKED, WAIT -> true;
                default -> false;
            };
            if (!active) {
                continue;
            }
            old.status = RequestStatus.CANCELLED;
            releaseTaxiway(old);
            allocator.send(key("RELEASE", old), new atc.common.Release(old.allocatorRequestId()), clock.nowMillis());
            event("CANCELLED", aircraftId + " superseded request " + old.requestId);
        }
    }

    private Message onAck(Ack a) {
        ControlRequest req = ownedRequest(a.requestId(), a.aircraftId());
        if (req == null) {
            return new ErrorMessage("unknown requestId " + a.requestId() + " for aircraft " + a.aircraftId());
        }
        if (req.status == RequestStatus.ASSIGNED) {
            req.status = RequestStatus.ACKED;
            allocator.send(key("CONFIRM", req), new Confirm(req.allocatorRequestId()), clock.nowMillis());
            event("ACKED", req.aircraftId + " acked " + req.runwayId + " id=" + req.requestId);
        }
        // The receipt is the instruction we consider current. If it differs from what the
        // aircraft acked (e.g. a bump raced with the ack), the aircraft learns it right here.
        return req.lastInstruction;
    }

    private Message onComplete(Complete c) {
        if (blank(c.requestId()) || blank(c.aircraftId())) {
            return new ErrorMessage("COMPLETE needs requestId and aircraftId");
        }
        ControlRequest req = requests.get(c.requestId());
        if (req == null) {
            // Unknown here: this control service restarted while the aircraft was using its slot
            // (control state is soft state). The allocator still has the booking, so forward
            // the COMPLETE under the aircraft's id and free the runway there. (If the booking
            // had been bumped to "#n" before the restart, the allocator answers "unknown"; the
            // slot then simply expires.)
            allocator.send("COMPLETE:" + c.requestId(), new Complete(c.requestId(), c.aircraftId()), clock.nowMillis());
            event("COMPLETED", c.aircraftId() + " finished (request unknown after restart, forwarded) id=" + c.requestId());
            return new Complete(c.requestId(), c.aircraftId());
        }
        if (!req.aircraftId.equals(c.aircraftId())) {
            return new ErrorMessage("requestId " + c.requestId() + " belongs to another aircraft");
        }
        if (req.status == RequestStatus.ASSIGNED || req.status == RequestStatus.ACKED) {
            req.status = RequestStatus.COMPLETED;
            allocator.send(key("COMPLETE", req), new Complete(req.allocatorRequestId(), req.aircraftId),
                    clock.nowMillis());
            if (req.isDeparture()) {
                releaseTaxiway(req); // departed: it is no longer on any taxiway
            } else {
                // Landed and vacated the runway: now it needs a taxiway to taxi in. If none is
                // free it waits off the runway and gets the next one released (pushed then).
                taxiways.acquire(req.requestId).ifPresent(t -> {
                    req.taxiwayId = t;
                    push(req, InstructionStatus.ASSIGNED, null);
                });
            }
            event("COMPLETED", req.aircraftId + " finished on " + req.runwayId + " id=" + req.requestId);
        } else if (req.status != RequestStatus.COMPLETED) {
            return new ErrorMessage("cannot complete request in status " + req.status);
        }
        return new Complete(req.requestId, req.aircraftId);
    }

    private ControlRequest ownedRequest(String requestId, String aircraftId) {
        ControlRequest req = requestId == null ? null : requests.get(requestId);
        return req != null && req.aircraftId.equals(aircraftId) ? req : null;
    }

    // ------------------------------------------------------------------ positions

    public void onPosition(PositionUpdate p) {
        AircraftView.Result result = view.accept(p);
        if (result != AircraftView.Result.ACCEPTED) {
            if (result == AircraftView.Result.STALE) {
                log.info("dropped stale position %s seq=%d", p.aircraftId(), p.seq());
            }
            return;
        }
        // Taxiways are freed from the (lagging) position view: conservative, since a taxiway
        // is held a little too long rather than ever given to two aircraft at once.
        for (ControlRequest req : requests.values()) {
            if (!req.aircraftId.equals(p.aircraftId()) || req.taxiwayId == null) {
                continue;
            }
            boolean offTaxiway = req.isDeparture()
                    ? p.phase() == Phase.TAKEOFF_ROLL || p.phase() == Phase.DEPARTED
                    : p.phase() == Phase.PARKED;
            if (offTaxiway) {
                releaseTaxiway(req);
            }
        }
    }

    // ------------------------------------------------------------------ allocator input

    /** A reply from the allocator's ROUTER, correlated by the key we sent. */
    public void onAllocatorReply(String key, Message reply) {
        if (allocator.onContact(clock.nowMillis())) {
            event("ALLOCATOR_REACHABLE", "reply received");
            reconcile("allocator reachable again");
        }
        if (reply instanceof ErrorMessage e && e.reason() != null && e.reason().startsWith("storage failure")) {
            // Transient at the allocator: keep the command pending so it is retried.
            log.warn("allocator storage failure for %s, will retry", key);
            return;
        }
        Message command = allocator.complete(key);
        if (command == null) {
            return; // late duplicate of something already handled
        }
        if (command instanceof Reserve reserve) {
            onReserveReply(reserve, reply);
        } else if (command instanceof Query query) {
            onQueryReply(query, reply);
        } else if (reply instanceof Reservation r && r.state() == atc.common.ReservationState.BUMPED) {
            // CONFIRM raced with an emergency bump: the slot is gone, book again.
            ControlRequest req = byAllocatorId.get(r.requestId());
            if (req != null && req.allocatorRequestId().equals(r.requestId())) {
                rebook(req, "bumped by an emergency before the ack was recorded");
            }
        } else if (reply instanceof ErrorMessage e) {
            log.warn("allocator rejected %s: %s", key, e.reason());
        }
    }

    private void onReserveReply(Reserve reserve, Message reply) {
        ControlRequest req = byAllocatorId.get(reserve.requestId());
        if (req == null || !req.allocatorRequestId().equals(reserve.requestId())) {
            return; // answer for a superseded booking attempt
        }
        if (req.status == RequestStatus.CANCELLED) {
            // Cancelled while the RESERVE was in flight. The RELEASE sent at cancel time may
            // have reached the allocator before this RESERVE did, so release again now that
            // the booking certainly exists (RELEASE is idempotent).
            allocator.send(key("RELEASE", req), new atc.common.Release(req.allocatorRequestId()), clock.nowMillis());
            return;
        }
        if (reply instanceof ErrorMessage e) {
            req.status = RequestStatus.REJECTED;
            push(req, InstructionStatus.REJECTED, "allocator error: " + e.reason());
            return;
        }
        if (!(reply instanceof Reservation r)) {
            log.warn("unexpected reply to RESERVE: %s", reply);
            return;
        }
        switch (r.state()) {
            case PENDING_ACK, CONFIRMED, UNCERTAIN -> {
                boolean sameBooking = java.util.Objects.equals(r.runwayId(), req.runwayId)
                        && java.util.Objects.equals(r.slotStart(), req.slotStart);
                if (req.status == RequestStatus.ACKED && sameBooking) {
                    // Re-reserved during reconciliation and got back the booking the aircraft
                    // already acked: just make sure the allocator records that ack.
                    if (r.state() != atc.common.ReservationState.CONFIRMED) {
                        allocator.send(key("CONFIRM", req), new Confirm(req.allocatorRequestId()), clock.nowMillis());
                    }
                    return;
                }
                // CONFIRMED means the allocator already holds an ack for this booking (this control
                // service restarted and the aircraft re-registered), so no new ack is awaited.
                req.status = r.state() == atc.common.ReservationState.CONFIRMED ? RequestStatus.ACKED : RequestStatus.ASSIGNED;
                req.assignedAt = clock.nowMillis();
                req.runwayId = r.runwayId();
                req.slotStart = r.slotStart();
                req.slotEnd = r.slotEnd();
                // Departures need a taxiway to reach the runway; arrivals only after vacating it.
                // Arrivals used to take one at assignment and held it for the whole approach,
                // starving departures (seen in the milestone 4 live run).
                req.taxiwayId = req.isDeparture() ? taxiways.acquire(req.requestId).orElse(null) : null;
                push(req, InstructionStatus.ASSIGNED,
                        req.isDeparture() && req.taxiwayId == null ? "taxiway pending" : null);
            }
            case WAIT -> {
                req.status = RequestStatus.WAIT;
                req.runwayId = null;
                req.slotStart = r.slotStart();
                req.slotEnd = r.slotEnd();
                push(req, InstructionStatus.WAIT, r.reason());
            }
            case REJECTED -> {
                req.status = RequestStatus.REJECTED;
                push(req, InstructionStatus.REJECTED, r.reason());
            }
            case BUMPED -> rebook(req, "bumped by an emergency");
            default -> {
                req.status = RequestStatus.REJECTED;
                push(req, InstructionStatus.REJECTED, "reservation is " + r.state());
            }
        }
    }

    /** A message from the allocator's PUB feed: heartbeats (health) and events (bumps). */
    public void onAllocatorBroadcast(Message m) {
        if (m instanceof Heartbeat hb) {
            // A new startedAt = new allocator incarnation, even if the restart was too quick for
            // us to notice missing heartbeats. Its PENDING_ACK bookings are now UNCERTAIN.
            boolean restarted = hb.startedAt() != null && allocatorStartedAt != null
                    && !hb.startedAt().equals(allocatorStartedAt);
            if (hb.startedAt() != null) {
                allocatorStartedAt = hb.startedAt();
            }
            boolean back = allocator.onHeartbeat(clock.nowMillis());
            if (back) {
                event("ALLOCATOR_REACHABLE", "heartbeat received");
            }
            if (restarted) {
                reconcile("allocator restarted");
            } else if (back) {
                reconcile("allocator reachable again");
            }
        } else if (m instanceof Event e && "BUMPED".equals(e.kind())) {
            try {
                if (JsonCodec.decode(e.detail()) instanceof Reservation r) {
                    ControlRequest req = byAllocatorId.get(r.requestId());
                    // Only act if this is the booking currently in force (idempotent against repeats).
                    if (req != null && req.allocatorRequestId().equals(r.requestId())) {
                        rebook(req, "bumped by an emergency");
                    }
                }
            } catch (CodecException ex) {
                log.warn("unreadable BUMPED event: %s", ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ reconciliation

    /**
     * Invariant 6: after an allocator restart (or outage) our view and the allocator's may
     * differ. Its PENDING_ACK bookings are now UNCERTAIN, and commands may have been lost. We
     * QUERY every booking we believe is assigned and settle each one on the allocator's answer
     * (see {@link #onQueryReply}). FORWARDED/HELD requests need no query: their RESERVE is
     * still pending and is being retried anyway.
     */
    private void reconcile(String why) {
        int queried = 0;
        for (ControlRequest req : requests.values()) {
            if (req.status == RequestStatus.ASSIGNED || req.status == RequestStatus.ACKED) {
                allocator.send(key("QUERY", req), new Query(req.allocatorRequestId()), clock.nowMillis());
                queried++;
            }
        }
        if (queried > 0) {
            event("RECONCILE", why + ": querying " + queried + " assignment(s)");
        }
    }

    private void onQueryReply(Query query, Message reply) {
        ControlRequest req = byAllocatorId.get(query.requestId());
        if (req == null || !req.allocatorRequestId().equals(query.requestId())
                || (req.status != RequestStatus.ASSIGNED && req.status != RequestStatus.ACKED)) {
            return; // moved on since the query was sent
        }
        String local = req.status.name();
        String action;
        if (reply instanceof ErrorMessage) {
            // The allocator has no record. It never replies before logging (invariant 5), so this
            // means its log was lost. Re-reserve under the same id (idempotent) to restore it.
            action = "unknown at allocator -> re-reserving";
            sendReserve(req);
        } else if (reply instanceof Reservation r) {
            action = switch (r.state()) {
                case PENDING_ACK, UNCERTAIN -> {
                    if (req.status == RequestStatus.ACKED) {
                        // The aircraft did ack; the allocator just never recorded it.
                        allocator.send(key("CONFIRM", req), new Confirm(req.allocatorRequestId()), clock.nowMillis());
                        yield "aircraft acked -> CONFIRM";
                    }
                    // Not acked yet: the ack (or the ack timeout) will settle it. An UNCERTAIN
                    // booking is never released without evidence, since the aircraft may be using it.
                    yield "awaiting ack";
                }
                case CONFIRMED -> "consistent";
                case COMPLETED -> {
                    req.status = RequestStatus.COMPLETED;
                    yield "allocator already completed it";
                }
                case BUMPED, RELEASED -> {
                    rebook(req, "booking " + r.state() + " at the allocator");
                    yield "booking lost -> rebooked";
                }
                default -> "unexpected state " + r.state();
            };
            local = local + ", allocator=" + r.state();
        } else {
            return;
        }
        event("RECONCILED", req.aircraftId + " id=" + req.requestId + " local=" + local + " -> " + action);
    }

    /**
     * An ASSIGNED request whose aircraft never acks (crashed, disconnected) would hold its runway
     * slot forever. After {@code ackTimeoutMs}, or once the slot has ended, release it and tell
     * the aircraft (if it is still there) to WAIT, i.e. ask again.
     */
    private void expireUnackedAssignments(long now) {
        for (ControlRequest req : requests.values()) {
            if (req.status != RequestStatus.ASSIGNED) {
                continue;
            }
            boolean timedOut = now - req.assignedAt > ackTimeoutMs;
            boolean slotOver = req.slotEnd != null && now > req.slotEnd;
            if (!timedOut && !slotOver) {
                continue;
            }
            req.status = RequestStatus.CANCELLED;
            releaseTaxiway(req);
            allocator.send(key("RELEASE", req), new atc.common.Release(req.allocatorRequestId()), now);
            String runway = req.runwayId;
            req.runwayId = null;
            req.slotStart = null;
            req.slotEnd = null;
            push(req, InstructionStatus.WAIT, "no ack received in time; booking released, request again");
            event("ACK_TIMEOUT", req.aircraftId + " never acked " + runway + " id=" + req.requestId
                    + (slotOver ? " (slot over)" : ""));
        }
    }

    // ------------------------------------------------------------------ time

    /** Call frequently (e.g. every poll loop): timeouts, retries, health, heartbeats. */
    public void tick() {
        long now = clock.nowMillis();
        expireUnackedAssignments(now);
        List<AllocatorLink.Pending> expired = allocator.expire(now);
        if (!expired.isEmpty() && allocator.markUnreachable()) {
            event("ALLOCATOR_UNREACHABLE", "no reply within " + timeoutMs + " ms");
        }
        for (AllocatorLink.Pending p : expired) {
            log.warn("allocator timeout on %s (attempt %d), retrying", p.key(), p.attempts());
            if (p.command() instanceof Reserve reserve) {
                ControlRequest req = byAllocatorId.get(reserve.requestId());
                if (req != null && req.status == RequestStatus.FORWARDED
                        && req.allocatorRequestId().equals(reserve.requestId())) {
                    // UNCERTAIN, not rejected: the allocator may have decided and the reply was lost.
                    req.status = RequestStatus.HELD;
                    push(req, InstructionStatus.HELD, "allocator did not answer; hold until a decision arrives");
                }
            }
        }
        if (allocator.contactStale(now) && allocator.markUnreachable()) {
            event("ALLOCATOR_UNREACHABLE", "no heartbeat or reply for " + 3 * heartbeatMs + " ms");
        }
        if (now >= nextHeartbeat) {
            out.publish(new Heartbeat(service, now));
            out.publish(new Event(service, "STATE", JsonCodec.encode(state()), now));
            nextHeartbeat = now + heartbeatMs;
        }
    }

    // ------------------------------------------------------------------ helpers

    private void sendReserve(ControlRequest req) {
        byAllocatorId.put(req.allocatorRequestId(), req);
        allocator.send(key("RESERVE", req), new Reserve(req.allocatorRequestId(), req.aircraftId, airportId,
                req.operation, req.sizeClass, req.emergency, req.earliestStart), clock.nowMillis());
    }

    /** The current booking was bumped: tell the aircraft to hold and book again under a new id. */
    private void rebook(ControlRequest req, String reason) {
        if (req.status == RequestStatus.CANCELLED || req.status == RequestStatus.COMPLETED) {
            return; // nobody is waiting for this booking any more
        }
        releaseTaxiway(req);
        req.attempt++;
        req.status = allocator.reachable() ? RequestStatus.FORWARDED : RequestStatus.HELD;
        req.runwayId = null;
        req.slotStart = null;
        req.slotEnd = null;
        push(req, InstructionStatus.HELD, reason + "; re-allocating");
        sendReserve(req);
    }

    private void releaseTaxiway(ControlRequest req) {
        if (req.taxiwayId == null) {
            taxiways.release(req.requestId); // may still be queued
            return;
        }
        String freed = req.taxiwayId;
        req.taxiwayId = null;
        event("TAXIWAY_FREE", freed + " released by " + req.aircraftId);
        Optional<TaxiwayManager.Grant> grant = taxiways.release(req.requestId);
        grant.ifPresent(g -> {
            // Only requests holding an assignment are ever queued (a rebook leaves the queue), and
            // an arrival that already landed (COMPLETED) still needs the taxiway to taxi in.
            ControlRequest next = requests.get(g.requestId());
            if (next != null) {
                next.taxiwayId = g.taxiwayId();
                push(next, InstructionStatus.ASSIGNED, null); // same assignment, now with a taxiway
            }
        });
    }

    private static String key(String kind, ControlRequest req) {
        return kind + ":" + req.allocatorRequestId();
    }

    /** Build, remember and publish an instruction (returned as a direct reply by the caller). */
    private Instruction instruction(ControlRequest req, InstructionStatus status, String reason) {
        Instruction i = new Instruction(req.requestId, req.aircraftId, status, req.runwayId, req.taxiwayId,
                req.slotStart, req.slotEnd, reason);
        req.lastInstruction = i;
        out.publish(new Event(service, "INSTRUCTION", JsonCodec.encode(i), clock.nowMillis()));
        log.info("INSTRUCTION %s %s %s%s%s", req.aircraftId, status,
                req.runwayId == null ? "" : req.runwayId,
                req.taxiwayId == null ? "" : " via " + req.taxiwayId,
                reason == null ? "" : " (" + reason + ")");
        return i;
    }

    /** Build an instruction and push it to the aircraft unsolicited. */
    private void push(ControlRequest req, InstructionStatus status, String reason) {
        out.toAircraft(req.aircraftId, instruction(req, status, reason));
    }

    private void event(String kind, String detail) {
        log.info("%s %s", kind, detail);
        out.publish(new Event(service, kind, detail, clock.nowMillis()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Snapshot for the Monitor and for tests. */
    public ControlState state() {
        List<ControlState.RequestView> views = requests.values().stream()
                .map(r -> new ControlState.RequestView(r.requestId, r.aircraftId, r.operation, r.emergency,
                        r.status.name(), r.runwayId, r.taxiwayId, r.slotStart, r.slotEnd,
                        r.lastInstruction == null ? null : r.lastInstruction.status().name(), r.sizeClass))
                .toList();
        return new ControlState(service, airportId, allocator.reachable(), view.all(), views,
                taxiways.occupancy(), taxiways.queue(), view.staleDropped(), clock.nowMillis());
    }

    public boolean allocatorReachable() {
        return allocator.reachable();
    }

    public int pendingAllocatorCommands() {
        return allocator.pendingCount();
    }
}
