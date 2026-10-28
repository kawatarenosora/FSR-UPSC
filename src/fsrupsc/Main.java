package fsrupsc;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;

/**
 * ZombieBuddy edition entry point (this branch only).
 *
 * <p>ZombieBuddy loads this mod's jar from mod.info's {@code javaJarFile} and
 * runs {@code fsrupsc.Main#main} when Java mods are loaded. The manifest of
 * this build carries no {@code Premain-Class}, so {@link FsrAgent#premain}
 * never runs here; the same boot sequence is mirrored below with one
 * difference: installation happens while the game is already running, so the
 * transformer is installed with ByteBuddy's retransformation strategy to also
 * cover classes loaded before us. Every hook in this agent is a code-only
 * rewrite (advice inline or member substitution), so retransformation is
 * legal; nothing adds fields or methods.
 *
 * <p>No ZombieBuddy API is referenced at compile time: the instrumentation
 * instance is read from ZombieBuddy's {@code Loader.g_instrumentation} by
 * reflection (same package-private static field in 2.x and 3.x), which keeps
 * a single jar compatible with both editions.
 */
public final class Main {

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            System.out.println("[fsr-upsc] zb entry failed, running stock: " + t);
        }
    }

    private static void run() {
        // The -javaagent edition (master branch mod, id FSR_UPSC) must not be
        // active at the same time: two transformers would apply the hooks
        // twice (double presents, double pacing). Its -javaagent line is
        // visible in the JVM's input arguments, which is version-proof: older
        // published builds of that edition predate any marker we could set.
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.contains("javaagent") && a.contains("fsr-upsc-agent")) {
                System.out.println("[fsr-upsc] -javaagent edition detected, zb entry skipped");
                return;
            }
        }

        // Revision gate first, then instrumentation. The gate reads the
        // revision straight off the game's constants class -- no class-bytes
        // scanning and no mod jar resources, both of which ZombieBuddy's
        // transformed-jar loader hides. mod.info's versionMin/versionMax is
        // the primary gate (mod list refuses other builds); this hash check
        // is the hotfix insurance, since a same-label build can carry a
        // different revision.
        if (!zbRevMatches()) {
            return;
        }

        Instrumentation inst = zbInstrumentation();
        if (inst == null) {
            System.out.println("[fsr-upsc] ZombieBuddy instrumentation unavailable, running stock");
            return;
        }

        // Same order as FsrAgent.premain: install first (Display is already
        // loaded by now and is covered by the retransform pass), then the FG
        // pacing restore, whose auto modes read the live display mode.
        FsrSettings.load();
        FsrConfig.sync();
        RenderScale.reconfigure();
        FsrTransformer.install(inst, true);
        try { FgPresent.setTest(FsrConfig.FG_TEST); } catch (Throwable ignored) {}
        try { FgPresent.setPace(FsrConfig.FG_PACE); } catch (Throwable ignored) {}

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

    /**
     * ZombieBuddy holds the instrumentation it registered in its own premain
     * in {@code Loader.g_instrumentation} (package-private static, identical
     * field in 2.x and 3.x). Reflection keeps us compile-independent.
     */
    private static Instrumentation zbInstrumentation() {
        try {
            Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
            java.lang.reflect.Field f = loader.getDeclaredField("g_instrumentation");
            f.setAccessible(true);
            return (Instrumentation) f.get(null);
        } catch (Throwable t) {
            System.out.println("[fsr-upsc] zb instrumentation lookup failed: " + t);
            return null;
        }
    }

    /** Direct Lua registration for the late-load case (see run()). */
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

    private Main() {}
}
