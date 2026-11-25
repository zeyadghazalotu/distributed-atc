package atc.scenario;

import atc.common.Log;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the evaluation scenarios against real processes and writes results to
 * {@code results/<scenario>/} (checks.txt, CSVs, every process log, the config used) plus
 * {@code results/summary.csv}. Exit code 0 only if every check passed.
 *
 * Run: java -cp target/atc.jar atc.scenario.ScenarioRunner [all | 1 2 ... 7]
 *          [--out results] [--config config/airports.json]
 *          [--rates 100,250,500,1000,2000] [--seconds 5] [--no-fsync-compare] [--quick]
 * Needs nothing else running on ports 6555-6572 (scenario ports = normal ports + 1000).
 */
public final class ScenarioRunner {
    private static final Log LOG = new Log("scenario-runner");

    private ScenarioRunner() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of("results");
        Path config = Path.of("config/airports.json");
        int[] rates = {100, 250, 500, 1000, 2000};
        int seconds = 5;
        boolean compareFsync = true;
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[++i]);
                case "--config" -> config = Path.of(args[++i]);
                case "--rates" -> rates = Arrays.stream(args[++i].split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
                case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                case "--no-fsync-compare" -> compareFsync = false;
                case "--quick" -> {
                    rates = new int[]{100, 500};
                    seconds = 3;
                    compareFsync = false;
                }
                case "all" -> selected.clear();
                default -> {
                    if (args[i].matches("[1-7]")) {
                        selected.add(args[i]);
                    } else {
                        System.err.println("unknown argument: " + args[i]);
                        System.err.println("usage: ScenarioRunner [all | 1..7 ...] [--out dir] [--config file] "
                                + "[--rates r1,r2,...] [--seconds n] [--no-fsync-compare] [--quick]");
                        System.exit(2);
                    }
                }
            }
        }

        Map<String, Scenario> all = new LinkedHashMap<>();
        all.put("1", new S1Simultaneous());
        all.put("2", new S2Incompatible());
        all.put("3", new S3Duplicates());
        all.put("4", new S4OutOfOrder());
        all.put("5", new S5AllocatorRestart());
        all.put("6", new S6Emergency());
        all.put("7", new S7LoadTest(rates, seconds, compareFsync));
        List<String> run = selected.isEmpty() ? List.copyOf(all.keySet()) : selected;

        Files.createDirectories(out);
        List<ScenarioContext.Check> checks = new ArrayList<>();
        for (String key : run) {
            Scenario s = all.get(key);
            LOG.info("=== scenario %s: %s ===", key, s.title());
            long start = System.currentTimeMillis();
            // Real timing (scale 1); network timeouts short so failure handling shows up quickly.
            try (ScenarioContext ctx = new ScenarioContext(s.id(), out, config, 1, 2000)) {
                try {
                    s.run(ctx);
                } catch (Exception e) {
                    ctx.check("-", "scenario ran to completion", false, e.toString());
                    LOG.error("scenario %s failed: %s", key, e);
                } finally {
                    checks.addAll(ctx.checks());
                }
            }
            LOG.info("=== scenario %s done in %.1f s ===", key, (System.currentTimeMillis() - start) / 1000.0);
        }

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("summary.csv"), StandardCharsets.UTF_8))) {
            w.println("scenario,invariant,check,result,detail");
            for (ScenarioContext.Check c : checks) {
                w.printf("%s,%s,\"%s\",%s,\"%s\"%n", c.scenario(), c.invariant(), c.name().replace("\"", "'"),
                        c.pass() ? "PASS" : "FAIL", c.detail().replace("\"", "'"));
            }
        }
        long failed = checks.stream().filter(c -> !c.pass()).count();
        System.out.println();
        System.out.println("================ SCENARIO SUMMARY ================");
        String current = "";
        for (ScenarioContext.Check c : checks) {
            if (!c.scenario().equals(current)) {
                current = c.scenario();
                System.out.println(current);
            }
            System.out.printf("  %s  [inv %-8s] %s%n", c.pass() ? "PASS" : "FAIL", c.invariant(), c.name());
        }
        System.out.printf("%d checks, %d passed, %d failed. Details: %s%n", checks.size(), checks.size() - failed, failed,
                out.resolve("summary.csv"));
        System.exit(failed == 0 ? 0 : 1);
    }
}
