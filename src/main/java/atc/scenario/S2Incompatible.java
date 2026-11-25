package atc.scenario;

import atc.allocator.CheckLog;
import atc.common.Ack;
import atc.common.Instruction;
import atc.common.InstructionStatus;
import atc.common.Message;
import atc.common.Operation;
import atc.common.Reservation;
import atc.common.ReservationState;
import atc.common.Reserve;
import atc.common.RunwayRequest;
import atc.common.SizeClass;

import java.io.PrintWriter;

/**
 * Scenario 2: incompatible aircraft. YOW has no runway that can take a HEAVY aircraft, so a
 * HEAVY request there must be REJECTED with a reason, through the control service and directly
 * at the allocator, and the same HEAVY at YYZ must get a long-enough runway (invariant 2).
 */
final class S2Incompatible implements Scenario {
    @Override
    public String id() {
        return "s2-incompatible";
    }

    @Override
    public String title() {
        return "Incompatible aircraft (HEAVY at small-runway airport YOW)";
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        ctx.startAllocator("allocator", ctx.allocatorLog());
        ctx.startControl("YOW", 5000, true);
        ctx.startControl("YYZ", 5000, true);

        try (PrintWriter w = ctx.csv("requests.csv", "airport,aircraft,size,status,runway,reason");
             AircraftClient heavyYow = new AircraftClient(ctx.zmq, ctx.controlRouter("YOW"), "S2-HEAVY-YOW");
             AircraftClient smallYow = new AircraftClient(ctx.zmq, ctx.controlRouter("YOW"), "S2-SMALL-YOW");
             AircraftClient mediumYow = new AircraftClient(ctx.zmq, ctx.controlRouter("YOW"), "S2-MEDIUM-YOW");
             AircraftClient heavyYyz = new AircraftClient(ctx.zmq, ctx.controlRouter("YYZ"), "S2-HEAVY-YYZ");
             AllocatorClient direct = new AllocatorClient(ctx.zmq, ctx.allocatorRouter())) {

            RunwayRequest heavyReq = request(heavyYow, "YOW", SizeClass.HEAVY);
            heavyYow.send(heavyReq);
            Instruction rejected = heavyYow.awaitDecision(10_000);
            row(w, "YOW", heavyYow, SizeClass.HEAVY, rejected);
            boolean isRejected = rejected != null && rejected.status() == InstructionStatus.REJECTED;
            ctx.check("2", "HEAVY at YOW is REJECTED via the control service", isRejected, String.valueOf(rejected));
            ctx.check("2", "rejection carries a reason", isRejected && rejected.reason() != null
                    && rejected.reason().contains("HEAVY"), isRejected ? rejected.reason() : "");

            heavyYow.send(heavyReq); // retry with the same requestId
            Instruction again = heavyYow.awaitDecision(10_000);
            ctx.check("3", "retried rejected request gets the identical answer", rejected != null && rejected.equals(again),
                    String.valueOf(again));

            Message directReply = direct.call("k1", new Reserve("s2-direct-heavy", "S2-DIRECT", "YOW", Operation.LANDING,
                    SizeClass.HEAVY, false), 10_000);
            ctx.check("2", "HEAVY at YOW is REJECTED directly by the allocator",
                    directReply instanceof Reservation r && r.state() == ReservationState.REJECTED, String.valueOf(directReply));

            for (AircraftClient c : new AircraftClient[]{smallYow, mediumYow}) {
                SizeClass size = c == smallYow ? SizeClass.SMALL : SizeClass.MEDIUM;
                c.send(request(c, "YOW", size));
                Instruction d = c.awaitDecision(10_000);
                row(w, "YOW", c, size, d);
                ctx.check("2", size + " at YOW is ASSIGNED", d != null && d.status() == InstructionStatus.ASSIGNED, String.valueOf(d));
                if (d != null && d.status() == InstructionStatus.ASSIGNED) {
                    c.send(new Ack(d.requestId(), d.aircraftId()));
                }
            }

            heavyYyz.send(request(heavyYyz, "YYZ", SizeClass.HEAVY));
            Instruction yyz = heavyYyz.awaitDecision(10_000);
            row(w, "YYZ", heavyYyz, SizeClass.HEAVY, yyz);
            ctx.check("2", "the same HEAVY aircraft type IS assigned at YYZ (long runways)",
                    yyz != null && yyz.status() == InstructionStatus.ASSIGNED, String.valueOf(yyz));
        }
        Thread.sleep(300);
        long rejectedRecords = CheckLog.readHistory(ctx.allocatorLog()).stream()
                .filter(r -> r.requestId().equals("s2-S2-HEAVY-YOW") && r.state() == ReservationState.REJECTED).count();
        ctx.check("3", "the rejection was decided (logged) exactly once", rejectedRecords == 1, rejectedRecords + " record(s)");
        ctx.logInvariantChecks(ctx.allocatorLog());
    }

    private static RunwayRequest request(AircraftClient c, String airport, SizeClass size) {
        return new RunwayRequest("s2-" + c.aircraftId, c.aircraftId, airport, Operation.LANDING, size, false,
                System.currentTimeMillis());
    }

    private static void row(PrintWriter w, String airport, AircraftClient c, SizeClass size, Instruction d) {
        w.printf("%s,%s,%s,%s,%s,\"%s\"%n", airport, c.aircraftId, size, d == null ? "NO_ANSWER" : d.status(),
                d == null || d.runwayId() == null ? "" : d.runwayId(), d == null || d.reason() == null ? "" : d.reason());
    }
}
