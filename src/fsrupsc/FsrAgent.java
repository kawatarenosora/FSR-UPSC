package fsrupsc;

import java.lang.instrument.Instrumentation;
import fsrupsc.FsrSettings;
import fsrupsc.FsrConfig;
import fsrupsc.RenderScale;

/**
 * Load-time agent only. No late attach: schema-changing patches
 * (added fields/methods) are legal at define-time, illegal via
 * redefine/retransform. Mismatched game revision => register nothing.
 */
public final class FsrAgent {
    // NOTE: build/compare by jar revision hash, never by "42.x" label.
    // TARGET_REVISION is read at runtime from this jar's
    // fsrupsc/build-info.properties (see fsrupsc.AgentRev), so one source
    // tree builds any target revision without touching constants here.
    static final String TARGET_REVISION = AgentRev.REVISION;

    public static void premain(String args, Instrumentation inst) {
        // One armed install per JVM: covers listing this jar twice on the
        // -javaagent command line and the ZombieBuddy java-mod entry of the
        // same jar (ZbEntry checks the same marker).
        if ("1".equals(System.getProperty("fsrupsc.armed"))) {
            System.out.println("[fsr-upsc] already armed in this JVM, premain skipped");
            return;
        }
        if (!FsrTransformer.revisionMatches()) {
            System.out.println("[fsr-upsc] revision mismatch, running stock (no transform)"
                + " supported=" + AgentRev.SUPPORTED);
            return;
        }

        // Initialize settings
        FsrSettings.load();
        FsrConfig.sync();
        RenderScale.reconfigure();

        // Install FIRST. The FG setting restore below must not run before
        // this: FgPresent.setPace's auto modes read
        // org.lwjglx.opengl.Display.getDisplayMode(), which loads the Display
        // class -- and a class loaded before install() is never transformed
        // (byte-buddy does not retransform without a redefinition strategy),
        // so the swapBuffers hook would silently never fire and FG would do
        // nothing at all. Applying the restore after install keeps the
        // first-ever load of Display inside an armed transformer.
        FsrTransformer.install(inst);
        System.setProperty("fsrupsc.armed", "1");

        // ZombieBuddy 3.x: its own premain runs first on the -javaagent line
        // and can load hook targets (Display, LuaManager, ...) before this
        // premain runs; a cold install permanently misses those, so retrofit
        // them here. Types already transformed are skipped by Byte Buddy.
        try { FsrTransformer.retrofitLateIfLoaded(inst); } catch (Throwable ignored) {}
        // If Lua init already ran before us, register FsrControl directly
        // (same fallback the ZbEntry path uses; harmless when not ready).
        try { ZbEntry.exposeIfReady(inst); } catch (Throwable ignored) {}

        // FG insert style / pacing from ModOptions.ini/settings (pure static
        // toggles, no GL at premain); FgPresent flips them again on live
        // option apply -- where the display frequency is already available
        // for the auto pacing modes.
        try { FgPresent.setTest(FsrConfig.FG_TEST); } catch (Throwable ignored) {}
        try { FgPresent.setPace(FsrConfig.FG_PACE); } catch (Throwable ignored) {}

        String armed = "[fsr-upsc] agent armed for " + TARGET_REVISION
            + " hooks=" + FsrHooks.hookSpec()
            + " downscale=" + RenderScale.downscaleEnabled();
        System.out.println(armed);
        try {
            fsrupsc.FsrLog.log(armed);
        } catch (Throwable ignored) {}
    }

    private FsrAgent() {}
}
