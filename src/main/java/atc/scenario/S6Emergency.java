package atc.scenario;

import atc.allocator.CheckLog;
import atc.allocator.ReservationRecord;
import atc.common.Ack;
import atc.common.Instruction;
import atc.common.InstructionStatus;
import atc.common.Operation;
import atc.common.ReservationState;
import atc.common.RunwayRequest;
import atc.common.SizeClass;

import java.io.PrintWriter;
import java.util.List;

/**
 * Scenario 6: an emergency bumps a not-yet-acked reservation. YYZ has three HEAVY-capable
 * runways: two hold acked (CONFIRMED) bookings, one holds an unacked (PENDING_ACK) booking. An
 * emergency HEAVY must get the earliest slot by bumping the unacked one; the bumped aircraft is
 * told to hold and rebooked; the confirmed bookings are untouched.
 */
final class S6Emergency implements Scenario {
    @Override
    public String id() {
        return "s6-emergency";
    }

    @Override
    public String title() {
        return "Emergency request bumping a pending reservation";
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        ctx.startAllocator("allocator", ctx.allocatorLog());
        ctx.startControl("YYZ", 5000, true);
        long slotMs = ctx.config.timing().slotMs();
        long bufferMs = ctx.config.timing().bufferMs();

        try (AircraftClient c1 = new AircraftClient(ctx.zmq, ctx.controlRouter("YYZ"), "S6-C1");
             AircraftClient c2 = new AircraftClient(ctx.zmq, ctx.controlRouter("YYZ"), "S6-C2");
             AircraftClient p1 = new AircraftClient(ctx.zmq, ctx.controlRouter("YYZ"), "S6-P1");
             AircraftClient e1 = new AircraftClient(ctx.zmq, ctx.controlRouter("YYZ"), "S6-E1");
             PrintWriter w = ctx.csv("instructions.csv", "aircraft,status,runway,slot_start,reason")) {
            Thread.sleep(300);
            Instruction c1i = assign(c1, true);
            Instruction c2i = assign(c2, true);
            Instruction p1i = assign(p1, false); // stays PENDING_ACK
            Thread.sleep(300);

            long t = System.nanoTime();
            e1.send(new RunwayRequest("s6-S6-E1", "S6-E1", "YYZ", Operation.LANDING, SizeClass.HEAVY, true, System.currentTimeMillis()));
            Instruction e1i = e1.awaitDecision(10_000);
            double emergencyMs = (System.nanoTime() - t) / 1e6;
            if (e1i != null) {
                e1.send(new Ack(e1i.requestId(), "S6-E1"));
            }
            Instruction p1Held = p1.await(i -> i.status() == InstructionStatus.HELD, 10_000);
            Instruction p1New = p1.await(i -> i.status() == InstructionStatus.ASSIGNED, 10_000);
            if (p1New != null) {
                p1.send(new Ack(p1New.requestId(), "S6-P1"));
            }
            c1.drain(200);
            c2.drain(200);
            Thread.sleep(500);

            for (AircraftClient c : List.of(c1, c2, p1, e1)) {
                for (Instruction i : c.instructions()) {
                    w.printf("%s,%s,%s,%s,\"%s\"%n", c.aircraftId, i.status(), i.runwayId() == null ? "" : i.runwayId(),
                            i.slotStart() == null ? "" : i.slotStart(), i.reason() == null ? "" : i.reason());
                }
            }

            boolean e1Ok = e1i != null && e1i.status() == InstructionStatus.ASSIGNED;
            ctx.check("-", "emergency is ASSIGNED", e1Ok, String.valueOf(e1i));
            ctx.check("-", "emergency takes the bumped aircraft's runway", e1Ok && p1i != null && e1i.runwayId().equals(p1i.runwayId()),
                    e1Ok ? e1i.runwayId() + " (P1 had " + (p1i == null ? "?" : p1i.runwayId()) + ")" : "");
            // Without bumping, the earliest HEAVY slot would be after C1/C2/P1: slot + 2 buffers later.
            long withoutBump = Math.min(c1i.slotStart(), c2i.slotStart()) + slotMs + 2 * bufferMs;
            ctx.check("-", "emergency slot is earlier than any non-bumping option", e1Ok && e1i.slotStart() < withoutBump,
                    e1Ok ? "starts " + (e1i.slotStart() - p1i.slotStart()) + " ms after P1's old slot; non-bumping option "
                            + (withoutBump - p1i.slotStart()) + " ms after" : "");
            // The bumped aircraft loses its place: rebooked on whichever eligible runway is free
            // next (not necessarily the same one), strictly later than the slot it lost.
            // Non-overlap with everything else is checked by invariant 1 below.
            ctx.check("-", "bumped aircraft told to HOLD, then rebooked to a later slot", p1Held != null && p1New != null
                    && p1i != null && p1New.slotStart() > p1i.slotStart(),
                    p1Held == null || p1New == null ? p1Held + " then " + p1New
                            : "HELD (" + p1Held.reason() + "), then " + p1New.runwayId() + " +"
                            + (p1New.slotStart() - p1i.slotStart()) / 1000 + " s after its old slot on " + p1i.runwayId());

            List<ReservationRecord> history = CheckLog.readHistory(ctx.allocatorLog());
            ctx.check("-", "allocator log: P1's booking BUMPED, rebooked as #2 and confirmed",
                    last(history, "s6-S6-P1") == ReservationState.BUMPED && last(history, "s6-S6-P1#2") == ReservationState.CONFIRMED,
                    last(history, "s6-S6-P1") + " / " + last(history, "s6-S6-P1#2"));
            boolean confirmedUntouched = history.stream().filter(r -> r.requestId().equals("s6-S6-C1") || r.requestId().equals("s6-S6-C2"))
                    .noneMatch(r -> r.state() == ReservationState.BUMPED);
            ctx.check("-", "confirmed (acked) bookings are never bumped", confirmedUntouched
                    && last(history, "s6-S6-C1") == ReservationState.CONFIRMED && last(history, "s6-S6-C2") == ReservationState.CONFIRMED, "");
            ctx.logInvariantChecks(ctx.allocatorLog());
            ctx.note("emergency decision latency (aircraft -> control -> allocator -> aircraft): %.2f ms", emergencyMs);
        }
    }

    /** HEAVY landing request; returns the assignment, acking it if asked. */
    private static Instruction assign(AircraftClient c, boolean ack) throws Exception {
        c.send(new RunwayRequest("s6-" + c.aircraftId, c.aircraftId, "YYZ", Operation.LANDING, SizeClass.HEAVY, false,
                System.currentTimeMillis()));
        Instruction d = c.awaitDecision(10_000);
        if (ack && d != null) {
            c.send(new Ack(d.requestId(), c.aircraftId));
            c.drain(100);
        }
        return d;
    }

    private static ReservationState last(List<ReservationRecord> history, String id) {
        return history.stream().filter(r -> r.requestId().equals(id)).map(ReservationRecord::state)
                .reduce((a, b) -> b).orElse(null);
    }
}
