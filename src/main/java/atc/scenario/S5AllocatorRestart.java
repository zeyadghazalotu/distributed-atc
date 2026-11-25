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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scenario 5: the allocator process is hard-killed mid-run and restarted from its log
 * (invariants 5, 6, 7). Two aircraft are deliberately slow to ack, so their bookings are
 * PENDING_ACK at the moment of the kill and must come back UNCERTAIN and be reconciled.
 */
final class S5AllocatorRestart implements Scenario {
    static final int CONTROL_TIMEOUT_MS = 2000; // ack timeout = 3x = 6 s

    private final List<String[]> timeline = new ArrayList<>();
    private long t0;

    @Override
    public String id() {
        return "s5-allocator-restart";
    }

    @Override
    public String title() {
        return "Allocator hard kill + restart mid-run";
    }

    private void mark(String event) {
        timeline.add(new String[]{String.valueOf(System.currentTimeMillis() - t0), event});
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        t0 = System.currentTimeMillis();
        ServiceProcess allocator = ctx.startAllocator("allocator-1", ctx.allocatorLog());
        ctx.startControl("YYZ", CONTROL_TIMEOUT_MS, true);
        mark("allocator + control up");

        Map<String, AircraftClient> ac = new LinkedHashMap<>();
        for (String id : List.of("S5-A1", "S5-A2", "S5-A3", "S5-U1", "S5-U2", "S5-H1")) {
            ac.put(id, new AircraftClient(ctx.zmq, ctx.controlRouter("YYZ"), id));
        }
        Thread.sleep(300);

        // A1-A3: assigned and acked (CONFIRMED). U1, U2: assigned, NOT acked yet (PENDING_ACK).
        Map<String, Instruction> preKill = new LinkedHashMap<>();
        for (String id : List.of("S5-A1", "S5-A2", "S5-A3", "S5-U1", "S5-U2")) {
            AircraftClient c = ac.get(id);
            c.send(request(id));
            Instruction d = c.awaitDecision(10_000);
            preKill.put(id, d);
            if (id.startsWith("S5-A") && d != null) {
                c.send(new Ack(d.requestId(), id));
                c.drain(100);
            }
        }
        mark("5 aircraft assigned; A1-A3 acked, U1/U2 not acked");
        Thread.sleep(500); // CONFIRMs for A1-A3 reach the log

        allocator.kill();
        long killedAt = System.currentTimeMillis();
        mark("allocator HARD KILLED");

        Thread.sleep(500);
        ac.get("S5-H1").send(request("S5-H1")); // a new request during the outage
        mark("H1 requests during outage");
        Thread.sleep(200);
        ac.get("S5-U1").send(new Ack("req-S5-U1", "S5-U1")); // U1 finally acks, while the allocator is down
        mark("U1 acks during outage");

        // Collect everything H1 hears until the restart: must never be ASSIGNED.
        long restartAt = killedAt + 3000;
        while (System.currentTimeMillis() < restartAt) {
            ac.get("S5-H1").recv(restartAt - System.currentTimeMillis());
        }
        ac.get("S5-U1").drain(10);
        List<Instruction> h1DuringOutage = new ArrayList<>(ac.get("S5-H1").instructions());

        long restartedAt = System.currentTimeMillis(); // process launch; includes JVM start + log replay
        ctx.startAllocator("allocator-2", ctx.allocatorLog());
        mark("allocator restarted from its log");

        Instruction h1Assigned = ac.get("S5-H1").await(i -> i.status() == InstructionStatus.ASSIGNED, 15_000);
        long h1AssignedAt = System.currentTimeMillis();
        mark("H1 ASSIGNED after restart");
        if (h1Assigned != null) {
            ac.get("S5-H1").send(new Ack(h1Assigned.requestId(), "S5-H1"));
        }

        // Wait for reconciliation of U1 (CONFIRMED) and U2's ack timeout (RELEASED).
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline
                && !(finalState(ctx, "req-S5-U1") == ReservationState.CONFIRMED
                && finalState(ctx, "req-S5-U2") == ReservationState.RELEASED)) {
            Thread.sleep(200);
        }
        mark("U1 reconciled, U2 released");
        ac.values().forEach(c -> {
            try {
                c.drain(100);
            } catch (Exception ignored) {
                // best effort
            }
        });

        List<ReservationRecord> history = CheckLog.readHistory(ctx.allocatorLog());

        // Invariant 5: every assignment an aircraft saw before the kill is in the log (the process was
        // killed without any chance to flush, so it must have been forced to disk before the reply).
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Instruction> e : preKill.entrySet()) {
            Instruction i = e.getValue();
            boolean logged = i != null && history.stream().anyMatch(r -> r.requestId().equals(i.requestId())
                    && i.runwayId().equals(r.runwayId()) && i.slotStart().equals(r.slotStart()));
            if (!logged) {
                missing.add(e.getKey());
            }
        }
        ctx.check("5", "every assignment seen before the hard kill was already durable", missing.isEmpty() && preKill.size() == 5,
                missing.isEmpty() ? preKill.size() + " assignments found in the log" : "missing: " + missing);

        // Invariant 6: rebuilt from the log; PENDING_ACK -> UNCERTAIN -> reconciled.
        List<ReservationState> u1 = states(history, "req-S5-U1");
        List<ReservationState> u2 = states(history, "req-S5-U2");
        ctx.check("6", "U1 (acked during the outage): PENDING_ACK -> UNCERTAIN -> CONFIRMED",
                u1.equals(List.of(ReservationState.PENDING_ACK, ReservationState.UNCERTAIN, ReservationState.CONFIRMED)), u1.toString());
        ctx.check("6", "U2 (never acked): PENDING_ACK -> UNCERTAIN -> RELEASED (only after reconciliation + ack timeout)",
                u2.equals(List.of(ReservationState.PENDING_ACK, ReservationState.UNCERTAIN, ReservationState.RELEASED)), u2.toString());
        boolean aStable = List.of("req-S5-A1", "req-S5-A2", "req-S5-A3").stream()
                .allMatch(id -> states(history, id).equals(List.of(ReservationState.PENDING_ACK, ReservationState.CONFIRMED)));
        ctx.check("6", "confirmed bookings survive the restart unchanged", aStable, "");
        String controlLog = Files.readString(ctx.dir.resolve("control-YYZ.log"));
        ctx.check("6", "control service reconciled after detecting the restart",
                controlLog.contains("RECONCILE allocator restarted") && controlLog.contains("RECONCILED S5-U2"), "");

        // Invariant 7: no new assignment while the allocator was unreachable.
        boolean heldOnly = !h1DuringOutage.isEmpty() && h1DuringOutage.stream().allMatch(i -> i.status() == InstructionStatus.HELD);
        ctx.check("7", "request during the outage was HELD, never ASSIGNED", heldOnly, h1DuringOutage.size() + " instruction(s) during outage: "
                + h1DuringOutage.stream().map(i -> i.status().name()).toList());
        ctx.check("7", "the held request was assigned after the restart", h1Assigned != null, String.valueOf(h1Assigned));

        ctx.logInvariantChecks(ctx.allocatorLog());
        ctx.note("outage %d ms (kill -> relaunch); relaunch -> held request ASSIGNED in %d ms (JVM start, log replay, "
                + "reconnect, retried RESERVE)", restartedAt - killedAt, h1AssignedAt - restartedAt);
        try (PrintWriter w = ctx.csv("timeline.csv", "t_ms,event")) {
            timeline.forEach(t -> w.printf("%s,%s%n", t[0], t[1]));
        }
        ac.values().forEach(AircraftClient::close);
    }

    private static RunwayRequest request(String aircraft) {
        return new RunwayRequest("req-" + aircraft, aircraft, "YYZ", Operation.LANDING, SizeClass.SMALL, false,
                System.currentTimeMillis());
    }

    private static List<ReservationState> states(List<ReservationRecord> history, String id) {
        return history.stream().filter(r -> r.requestId().equals(id)).map(ReservationRecord::state).toList();
    }

    private static ReservationState finalState(ScenarioContext ctx, String id) throws Exception {
        List<ReservationState> s = states(CheckLog.readHistory(ctx.allocatorLog()), id);
        return s.isEmpty() ? null : s.get(s.size() - 1);
    }
}
