package atc.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal timestamped logger: "2026-11-25 13:40:01.123 [allocator] INFO  message".
 * Logs are evidence for the report, so every line carries time and component.
 */
public final class Log {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final String component;

    public Log(String component) {
        this.component = component;
    }

    public void info(String fmt, Object... args) {
        write(System.out, "INFO ", fmt, args);
    }

    public void warn(String fmt, Object... args) {
        write(System.out, "WARN ", fmt, args);
    }

    public void error(String fmt, Object... args) {
        write(System.err, "ERROR", fmt, args);
    }

    private void write(java.io.PrintStream out, String level, String fmt, Object... args) {
        String text = args.length == 0 ? fmt : String.format(fmt, args);
        out.printf("%s [%s] %s %s%n", LocalDateTime.now().format(FMT), component, level, text);
    }
}
