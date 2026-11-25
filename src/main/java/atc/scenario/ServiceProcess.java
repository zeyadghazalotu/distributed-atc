package atc.scenario;

import java.util.concurrent.TimeUnit;

/** A component running as its own OS process (started by the ScenarioRunner). */
final class ServiceProcess {
    final String name;
    private final Process process;

    ServiceProcess(String name, Process process) {
        this.name = name;
        this.process = process;
    }

    /** Hard kill (like kill -9 / TerminateProcess): no shutdown hooks, no flushing. */
    void kill() throws InterruptedException {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    /** Polite stop (shutdown hooks run), escalating to a kill. */
    void stop() throws InterruptedException {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            kill();
        }
    }

    boolean isAlive() {
        return process.isAlive();
    }

    long pid() {
        return process.pid();
    }
}
