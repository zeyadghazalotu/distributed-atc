package atc.allocator;

import atc.common.AtcConfig;
import atc.common.CodecException;
import atc.common.JsonCodec;
import atc.common.ReservationState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Offline check of an allocator log, used as evidence after live runs: replays the file
 * read-only and checks the safety invariants.
 *
 * Run: java -cp target/atc.jar atc.allocator.CheckLog data/allocator-log.jsonl [--config config/airports.json]
 *          [--time-scale n]   (use the same time scale as the run that wrote the log)
 * Exit code 0 = all PASS, 1 = a violation was found.
 */
public final class CheckLog {
    private CheckLog() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: CheckLog <allocator-log.jsonl> [--config config/airports.json] [--time-scale n]");
            System.exit(2);
        }
        Path logPath = Path.of(args[0]);
        Path configPath = Path.of("config/airports.json");
        Integer timeScale = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--config")) {
                configPath = Path.of(args[++i]);
            } else if (args[i].equals("--time-scale")) {
                timeScale = Integer.parseInt(args[++i]);
            }
        }
        AtcConfig config = AtcConfig.load(configPath);
        if (timeScale != null) {
            config = config.withTimeScale(timeScale); // buffers must match the run being checked
        }

        List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
        List<ReservationRecord> history = readHistory(logPath);
        long unreadable = lines.stream().filter(l -> !l.isBlank()).count() - history.size();
        Map<String, ReservationRecord> latest = new LinkedHashMap<>();
        history.forEach(r -> latest.put(r.requestId(), r));
        Map<ReservationState, Integer> states = new TreeMap<>();
        latest.values().forEach(r -> states.merge(r.state(), 1, Integer::sum));
        long everUncertain = history.stream().filter(r -> r.state() == ReservationState.UNCERTAIN)
                .map(ReservationRecord::requestId).distinct().count();

        System.out.printf("log %s: %d records, %d requests, %d unreadable lines%n",
                logPath, history.size(), latest.size(), unreadable);
        System.out.println("final states: " + states + "   (ever UNCERTAIN: " + everUncertain + ")");

        long bufferMs = config.timing().bufferMs();
        boolean ok = report("1 no overlapping blocking reservations (final state, incl. buffers)",
                InvariantChecker.overlapViolations(latest.values(), bufferMs));
        ok &= report("1 no simultaneous runway occupancy (entire history)",
                InvariantChecker.historicalOverlapViolations(history));
        ok &= report("2 every assigned runway eligible for its aircraft",
                InvariantChecker.eligibilityViolations(latest.values(), config));
        System.exit(ok ? 0 : 1);
    }

    /** Every readable record in file order, read-only (unlike ReservationLog.open, never truncates). */
    public static List<ReservationRecord> readHistory(Path logPath) throws java.io.IOException {
        List<ReservationRecord> history = new ArrayList<>();
        for (String line : Files.readAllLines(logPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                history.add(JsonCodec.decode(line, ReservationRecord.class));
            } catch (CodecException e) {
                // counted by the caller
            }
        }
        return history;
    }

    private static boolean report(String name, List<String> violations) {
        System.out.printf("%s  invariant %s%n", violations.isEmpty() ? "PASS" : "FAIL", name);
        violations.stream().limit(10).forEach(v -> System.out.println("      " + v));
        return violations.isEmpty();
    }
}
