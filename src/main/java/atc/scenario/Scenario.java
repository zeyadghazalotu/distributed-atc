package atc.scenario;

/** One evaluation scenario: drives real processes and records PASS/FAIL checks in the context. */
public interface Scenario {
    /** Short id used for the results folder, e.g. "s1-simultaneous". */
    String id();

    String title();

    void run(ScenarioContext ctx) throws Exception;
}
