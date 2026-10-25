package fsrupsc;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * File logger for the agent. The PZ JAM Launcher does not redirect the
 * game process stdout anywhere persistent, so console lines alone are
 * lost on shutdown. Everything diagnostic goes here too.
 */
public final class FsrLog {
    private static volatile PrintWriter out;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static synchronized void log(String line) {
        try {
            if (out == null) {
                String home = System.getProperty("user.home", ".");
                java.nio.file.Path p;
                try {
                    p = Paths.get(home, "Zomboid", "fsr-upsc-agent.log");
                } catch (Exception e) {
                    p = Paths.get("fsr-upsc-agent.log");
                }
                out = new PrintWriter(new FileWriter(p.toFile(), true), true);
                out.println("=== fsr-upsc agent log started "
                        + LocalDateTime.now().format(TS) + " ===");
            }
            out.println("[" + LocalDateTime.now().format(TS) + "] " + line);
        } catch (Throwable ignored) {}
    }

    private FsrLog() {}
}
