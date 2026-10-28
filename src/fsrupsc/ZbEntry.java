package fsrupsc;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import net.bytebuddy.dynamic.ClassFileLocator;

/**
 * ZombieBuddy edition boot. Runs in the SYSTEM classloader (entered via
 * {@code Class.forName(..., systemLoader)} from the {@link Main} shim), so
 * every fsrupsc class touched here -- settings, transformer state, advice
 * helpers -- lives in the single copy the inlined advice bodies resolve
 * through. Nothing in here may be referenced from the shim side.
 */
public final class ZbEntry {

    public static void run(Instrumentation inst, File jar) {
        try {
            run0(inst, jar);
        } catch (Throwable t) {
            System.out.println("[fsr-upsc] zb entry failed, running stock: " + t);
        }
    }

    private static void run0(Instrumentation inst, File jar) {
        // One armed install per JVM. The "fsrupsc.armed" marker is set by
        // FsrAgent.premain (the -javaagent mode) and by us after a successful
        // install; a second copy of this mod on ZombieBuddy's java mod list
        // would otherwise register the whole transformer set twice (double
        // presents, double pacing). The -javaagent check below is kept as the
        // version-proof backstop: older published javaagent builds predate
        // the marker.
        if ("1".equals(System.getProperty("fsrupsc.armed"))) {
            System.out.println("[fsr-upsc] already armed in this JVM, zb entry skipped");
            return;
        }
        // The -javaagent launch mode must not be active at the same time: two
        // transformers would apply the hooks twice. Its -javaagent line is
        // visible in the JVM's input arguments, which catches builds that
        // never set the marker.
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.contains("javaagent") && a.contains("fsr-upsc-agent")) {
                System.out.println("[fsr-upsc] -javaagent edition detected, zb entry skipped");
                return;
            }
        }

        // Revision gate: reads the revision straight off the game's constants
        // class -- no class-bytes scanning and no mod jar resources, both of
        // which ZombieBuddy's transformed-jar loader hides. mod.info's
        // versionMin/versionMax is the primary gate (the mod list refuses
        // other builds); this hash check is the hotfix insurance, since a
        // same-label build can carry a different revision.
        if (!zbRevMatches()) {
            return;
        }

        // Same order as FsrAgent.premain: install first (Display is already
        // loaded by now and is covered by the retransform pass), then the FG
        // pacing restore, whose auto modes read the live display mode.
        ClassFileLocator locator = AgentJar.prepare(inst, jar);
        FsrSettings.load();
        FsrConfig.sync();
        RenderScale.reconfigure();
        FsrTransformer.install(inst, true, locator);
        try { FgPresent.setTest(FsrConfig.FG_TEST); } catch (Throwable ignored) {}
        try { FgPresent.setPace(FsrConfig.FG_PACE); } catch (Throwable ignored) {}
        System.setProperty("fsrupsc.armed", "1");

        // If Lua init already ran before ZombieBuddy loaded us, the
        // LuaManager$Exposer.exposeAll advice missed the current env; register
        // directly. Only do it when LuaManager is already loaded, so we never
        // force the game's Lua bootstrap out of order -- otherwise the advice
        // fires at the real moment (and on every env rebuild after that).
        exposeIfReady(inst);

        String armed = "[fsr-upsc] zb entry armed for " + AgentRev.REVISION
            + " hooks=" + FsrHooks.hookSpec()
            + " downscale=" + RenderScale.downscaleEnabled();
        System.out.println(armed);
        try {
            fsrupsc.FsrLog.log(armed);
        } catch (Throwable ignored) {}
    }

    /**
     * ZB path revision gate: compare the built target (BuildRev constant via
     * {@link AgentRev}) against {@code zombie.GitVersion#REVISION} read
     * reflectively. GitVersion is a tiny constants holder; loading and
     * initializing it is side-effect free. On mismatch, print both sides so
     * the console line alone diagnoses a build/mod pairing mistake.
     */
    private static boolean zbRevMatches() {
        String game = gameRevision();
        String target = AgentRev.REVISION;
        if (game != null && game.equals(target)) {
            return true;
        }
        System.out.println("[fsr-upsc] revision mismatch, running stock (no transform)"
            + " target=" + (target.isEmpty() ? "<unreadable>" : target)
            + " game=" + (game == null || game.isEmpty() ? "<unreadable>" : game));
        return false;
    }

    private static String gameRevision() {
        try {
            return (String) Class.forName("zombie.GitVersion")
                .getField("REVISION").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Direct Lua registration for the late-load case (see run0()). */
    private static void exposeIfReady(Instrumentation inst) {
        try {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if ("zombie.Lua.LuaManager".equals(c.getName())) {
                    if (c.getField("env").get(null) != null) {
                        FsrExposeHook.register();
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            System.err.println("[fsr-upsc] expose check failed: " + t);
        }
    }

    private ZbEntry() {}
}
