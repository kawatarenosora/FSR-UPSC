package fsrupsc;

import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Target game revision, read at runtime from this jar's own
 * fsrupsc/build-info.properties (revision=<10-hex>, stamped by
 * scripts/build-agent.ps1). One jar per revision: the same source builds
 * every target without editing constants. If the property is missing or
 * malformed, REVISION stays empty and the agent refuses to arm (stock).
 * The same entry is consumed by PZ JAM Launcher's revision gate.
 */
public final class AgentRev {
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{10}$");

    public static final String REVISION = readRevision();

    private AgentRev() {}

    private static String readRevision() {
        try (InputStream in = AgentRev.class.getResourceAsStream("/fsrupsc/build-info.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String r = p.getProperty("revision");
                if (r != null) {
                    r = r.trim();
                    if (HASH.matcher(r).matches()) return r;
                }
            }
        } catch (Throwable ignored) {
            // fall through: empty revision = agent stays stock
        }
        return "";
    }
}
