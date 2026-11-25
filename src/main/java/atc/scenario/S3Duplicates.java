package atc.scenario;

import atc.allocator.CheckLog;
import atc.allocator.ReservationRecord;
import atc.common.Ack;
import atc.common.Instruction;
import atc.common.InstructionStatus;
import atc.common.Message;
import atc.common.Operation;
import atc.common.ReservationState;
import atc.common.Reserve;
import atc.common.RunwayRequest;
import atc.common.SizeClass;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Scenario 3: duplicate messages and retries with the same requestId (invariant 3): burst
 * duplicates, a lazy-pirate retry after a lost reply, duplicates straight to the allocator over
 * fresh sockets, and duplicate ACKs. Every copy must get the same answer and the log must hold
 * exactly one decision per requestId.
 */
final class S3Duplicates implements Scenario {
    @Override
    public String id() {
        return "s3-duplicates";
    }

    @Override
    public String title() {
        return "Duplicate messages / retries with the same requestId";
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        ctx.startAllocator("allocator", ctx.allocatorLog());
        ctx.startControl("YYZ", 5000, true);
        Stats firstLatency = new Stats();
        Stats dedupLatency = new Stats();

        try (PrintWriter w = ctx.csv("replies.csv", "case,attempt,reply");
             AircraftClient a = new AircraftClient(ctx.zmq, ctx.controlRouter("YYZ"), "S3-AC1");
             AircraftClient b = new AircraftClient(ctx.zmq, ctx.controlRouter("YYZ"), "S3-AC2");
             AllocatorClient direct = new AllocatorClient(ctx.zmq, ctx.allocatorRouter())) {

            // (a) The same RUNWAY_REQUEST 5 times back to back, then 3 more after the decision.
            RunwayRequest r1 = new RunwayRequest("s3-r1", "S3-AC1", "YYZ", Operation.LANDING, SizeClass.MEDIUM, false, 0);
            for (int i = 0; i < 5; i++) {
                a.send(r1);
            }
            Instruction decided = a.awaitDecision(10_000);
            List<Instruction> later = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                a.send(r1);
                later.add(a.awaitDecision(5_000));
            }
            a.drain(200);
            boolean sameA = decided != null && later.stream().allMatch(decided::equals)
                    && a.instructions().stream().filter(x -> x.status() != InstructionStatus.HELD).allMatch(decided::equals);
            for (int i = 0; i < a.instructions().size(); i++) {
                w.printf("burst,%d,\"%s\"%n", i + 1, a.instructions().get(i));
            }
            ctx.check("3", "8 copies of one request -> identical answers", sameA, a.instructions().size() + " replies, decision " + decided);

            // (b) Lazy pirate: send, lose the reply (abandon the socket), resend on a new socket.
            RunwayRequest r2 = new RunwayRequest("s3-r2", "S3-AC2", "YYZ", Operation.TAKEOFF, SizeClass.SMALL, false, 0);
            b.send(r2);
            b.reconnect(); // the decision pushed to the old socket is lost
            Thread.sleep(500);
            b.send(r2);
            Instruction afterRetry = b.awaitDecision(10_000);
            w.printf("lazy-pirate,2,\"%s\"%n", afterRetry);
            ctx.check("3", "retry after a lost reply returns the decision already made",
                    afterRetry != null && afterRetry.status() == InstructionStatus.ASSIGNED, String.valueOf(afterRetry));

            // (c) 10 identical RESERVEs straight to the allocator, every 3rd on a fresh socket.
            Reserve x1 = new Reserve("s3-x1", "S3-DIRECT", "YYZ", Operation.LANDING, SizeClass.HEAVY, false);
            Message first = null;
            boolean sameC = true;
            for (int i = 0; i < 10; i++) {
                if (i > 0 && i % 3 == 0) {
                    direct.reconnect();
                }
                long t = System.nanoTime();
                Message reply = direct.call("x1-" + i, x1, 5_000);
                double ms = (System.nanoTime() - t) / 1e6;
                if (i == 0) {
                    first = reply;
                    firstLatency.add(ms);
                } else {
                    dedupLatency.add(ms);
                    sameC &= reply != null && reply.equals(first);
                }
                w.printf("direct,%d,\"%s\"%n", i + 1, reply);
            }
            ctx.check("3", "10 RESERVEs with one requestId (3 sockets) -> identical replies", sameC && first != null, String.valueOf(first));

            // (d) The ACK sent 3 times.
            if (decided != null && decided.status() == InstructionStatus.ASSIGNED) {
                for (int i = 0; i < 3; i++) {
                    a.send(new Ack("s3-r1", "S3-AC1"));
                }
                a.drain(500);
            }
        }
        Thread.sleep(300);

        List<ReservationRecord> history = CheckLog.readHistory(ctx.allocatorLog());
        for (String id : List.of("s3-r1", "s3-r2", "s3-x1")) {
            long decisions = history.stream().filter(r -> r.requestId().equals(id) && r.state() == ReservationState.PENDING_ACK).count();
            ctx.check("3", id + ": exactly one reservation decision logged", decisions == 1, decisions + " decision(s)");
        }
        long confirms = history.stream().filter(r -> r.requestId().equals("s3-r1") && r.state() == ReservationState.CONFIRMED).count();
        ctx.check("3", "3 duplicate ACKs -> one CONFIRMED transition", confirms == 1, confirms + " CONFIRMED record(s)");
        ctx.logInvariantChecks(ctx.allocatorLog());
        ctx.note("allocator call latency: first decision %s; deduplicated replay %s", firstLatency.summary(), dedupLatency.summary());
    }
}
