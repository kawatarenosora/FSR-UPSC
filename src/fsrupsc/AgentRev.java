package fsrupsc;

/**
 * Target game revision for this build.
 *
 * <p>Sourced from the generated {@code fsrupsc.BuildRev} constant (stamped by
 * scripts/build-agent.ps1 at build time). A class constant is the only form
 * that survives every loading context: ZombieBuddy's transformed-jar loader
 * does not serve mod jar resources (the same reason BU's ZB edition reports
 * "unversioned"), while a compile-time constant inlines into the classes that
 * read it. The same revision is still written to
 * {@code fsrupsc/build-info.properties} in the jar for external loaders (PZ
 * JAM Launcher's revision gate). If the generated class is missing (jar built
 * without the generation step), REVISION stays empty and the agent refuses to
 * arm (stock, safe side).
 */
public final class AgentRev {
    public static final String REVISION = readRevision();

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
