package atc.scenario;

import atc.common.Ack;
import atc.common.Instruction;
import atc.common.InstructionStatus;
import atc.common.Message;
import atc.common.Operation;
import atc.common.RunwayRequest;
import atc.common.SizeClass;
import org.zeromq.ZMQ;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scenario 1: many aircraft ask for a runway at the same moment. The allocator serializes the
 * decisions, so no two reservations may overlap (invariant 1) and none may be ineligible
 * (invariant 2). Also measures end-to-end decision latency through the control service.
 */
final class S1Simultaneous implements Scenario {
    static final int AIRCRAFT = 40;

    @Override
    public String id() {
        return "s1-simultaneous";
    }

    @Override
    public String title() {
        return "Simultaneous runway requests (" + AIRCRAFT + " aircraft at once)";
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        ctx.startAllocator("allocator", ctx.allocatorLog());
        ctx.startControl("YYZ", 5000, true);

        SizeClass[] sizes = SizeClass.values();
        List<AircraftClient> clients = new ArrayList<>();
        for (int i = 0; i < AIRCRAFT; i++) {
            clients.add(new AircraftClient(ctx.zmq, ctx.controlRouter("YYZ"), String.format("S1-AC%02d", i + 1)));
        }
        Thread.sleep(500); // let every DEALER finish connecting so the burst is really simultaneous

        long[] sentNanos = new long[AIRCRAFT];
        for (int i = 0; i < AIRCRAFT; i++) {
            AircraftClient c = clients.get(i);
            sentNanos[i] = System.nanoTime();
            c.send(new RunwayRequest("s1-" + c.aircraftId, c.aircraftId, "YYZ", Operation.LANDING,
                    sizes[i % sizes.length], false, System.currentTimeMillis()));
        }

        Instruction[] decision = new Instruction[AIRCRAFT];
        double[] latencyMs = new double[AIRCRAFT];
        ZMQ.Poller poller = ctx.zmq.createPoller(AIRCRAFT);
        clients.forEach(c -> poller.register(c.socket(), ZMQ.Poller.POLLIN));
        long deadline = System.currentTimeMillis() + 20_000;
        int decided = 0;
        while (decided < AIRCRAFT && System.currentTimeMillis() < deadline) {
            if (poller.poll(100) <= 0) {
                continue;
            }
            for (int i = 0; i < AIRCRAFT; i++) {
                if (!poller.pollin(i)) {
                    continue;
                }
                Message m = clients.get(i).recv(1);
                if (m instanceof Instruction ins && ins.status() != InstructionStatus.HELD && decision[i] == null) {
                    decision[i] = ins;
                    latencyMs[i] = (System.nanoTime() - sentNanos[i]) / 1e6;
                    decided++;
                    if (ins.status() == InstructionStatus.ASSIGNED) {
                        clients.get(i).send(new Ack(ins.requestId(), ins.aircraftId()));
                    }
                }
            }
        }
        poller.close();
        for (AircraftClient c : clients) {
            c.drain(50); // ack receipts
        }
        Thread.sleep(500); // let the CONFIRMs reach the log

        Stats stats = new Stats();
        Map<String, Integer> byStatus = new TreeMap<>();
        Map<String, Integer> byRunway = new TreeMap<>();
        try (PrintWriter w = ctx.csv("requests.csv", "aircraft,size,status,runway,slot_start,latency_ms")) {
            for (int i = 0; i < AIRCRAFT; i++) {
                Instruction d = decision[i];
                if (d != null) {
                    stats.add(latencyMs[i]);
                    byStatus.merge(d.status().name(), 1, Integer::sum);
                    if (d.runwayId() != null) {
                        byRunway.merge(d.runwayId(), 1, Integer::sum);
                    }
                }
                w.printf("%s,%s,%s,%s,%s,%.3f%n", clients.get(i).aircraftId, sizes[i % sizes.length],
                        d == null ? "NO_ANSWER" : d.status(), d == null || d.runwayId() == null ? "" : d.runwayId(),
                        d == null || d.slotStart() == null ? "" : d.slotStart(), d == null ? -1 : latencyMs[i]);
            }
        }
        ctx.check("liveness", "every simultaneous request got a decision", decided == AIRCRAFT,
                decided + "/" + AIRCRAFT + " decided");
        ctx.logInvariantChecks(ctx.allocatorLog());
        ctx.note("outcomes: %s; runways used: %s", byStatus, byRunway);
        ctx.note("decision latency (aircraft -> control -> allocator -> aircraft): %s", stats.summary());
        clients.forEach(AircraftClient::close);
    }
}
