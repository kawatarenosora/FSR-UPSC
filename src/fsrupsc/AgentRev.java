package fsrupsc;

import java.util.Arrays;
import java.util.List;

/**
 * Target game revision(s) for this build.
 *
 * <p>{@link #REVISION} is the PRIMARY target: the game revision this jar was
 * compiled against (generated {@code fsrupsc.BuildRev} constant stamped by
 * scripts/build-agent.ps1 at build time). It names the jar in the manifest
 * (Target-Revision) and in fsrupsc/build-info.properties, and appears in the
 * "armed" console line.
 *
 * <p>{@link #SUPPORTED} is the whitelist of game revisions this source tree
 * is verified against; the runtime gate (premain and ZombieBuddy entries)
 * accepts any of them, so one jar can serve the current build and rollback
 * builds alike. It is a source constant, curated per release: before adding
 * an entry, re-verify the hook targets against that game build (see the
 * build script header) and keep the list short - an entry only stays while
 * the current advice is known to be compatible with that build's bytecode.
 *
 * <p>A class constant is the only revision form that survives every loading
 * context: ZombieBuddy's transformed-jar loader does not serve mod jar
 * resources (the same reason BU's ZB edition reports "unversioned"), while
 * a compile-time constant inlines into the classes that read them. The
 * primary revision is still written to fsrupsc/build-info.properties in the
 * jar for external loaders (PZ JAM Launcher's revision gate). If the
 * generated class is missing (jar built without the generation step),
 * REVISION stays empty and the agent refuses to arm (stock, safe side).
 */
public final class AgentRev {
    public static final String REVISION = readRevision();

    /** Game revisions accepted by the runtime gate (primary first). */
    public static final List<String> SUPPORTED = Arrays.asList(
            "e013dbb529",   // B42.22 (primary: compiled against this game jar)
            "4a0e9546ec"    // B42.21 (verified: hook targets + field shapes)
    );

    /** Whitelist membership for a game revision hash (null/empty = false). */
    public static boolean isSupported(String game) {
        return game != null && !game.isEmpty() && SUPPORTED.contains(game);
    }

    private AgentRev() {}

    private static String readRevision() {
        try {
            return BuildRev.REVISION;
        } catch (Throwable t) {
            // missing generated class: empty revision = agent stays stock
            return "";
        }
    }
}
