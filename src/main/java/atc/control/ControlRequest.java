package atc.control;

import atc.common.Instruction;
import atc.common.Operation;
import atc.common.RunwayRequest;
import atc.common.SizeClass;

/**
 * One aircraft runway request tracked by a control service (mutable, owned by the single
 * control-service thread).
 *
 * The aircraft's requestId never changes. If the allocator bumps the reservation, the control
 * service books again under a derived allocator requestId ("id#2", "id#3", ...). The aircraft
 * keeps one id, and every allocator booking still has its own unique, idempotent id.
 */
final class ControlRequest {
    final String requestId;
    final String aircraftId;
    final Operation operation;
    final SizeClass sizeClass;
    final boolean emergency;
    final Long earliestStart;
    final long createdAt;

    int attempt = 1;
    /** When the current assignment was given (for the ack timeout); 0 = not assigned. */
    long assignedAt;
    RequestStatus status = RequestStatus.FORWARDED;
    String runwayId;
    Long slotStart;
    Long slotEnd;
    String taxiwayId;
    /** Last instruction given to the aircraft; returned again when the aircraft retries. */
    Instruction lastInstruction;

    ControlRequest(RunwayRequest r, long now) {
        this.requestId = r.requestId();
        this.aircraftId = r.aircraftId();
        this.operation = r.operation();
        this.sizeClass = r.sizeClass();
        this.emergency = r.emergency();
        this.earliestStart = r.earliestStart();
        this.createdAt = now;
    }

    String allocatorRequestId() {
        return attempt == 1 ? requestId : requestId + "#" + attempt;
    }

    boolean isDeparture() {
        return operation == Operation.TAKEOFF;
    }
}
