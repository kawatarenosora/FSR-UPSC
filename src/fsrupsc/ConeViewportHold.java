package fsrupsc;

import net.bytebuddy.asm.Advice;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hold the viewport funnel off while the view cone's lightmap is being drawn.
 *
 * <p>Established by a reachability walk over the whole jar (22990 classes,
 * 654428 call sites, descriptors compared exactly), rooted at the game's real
 * entry points rather than a guessed one:
 *
 * <pre>
 *   IsoWorld.DrawPlayerCone / DrawPlayerCone2   public, zero reachable callers
 *   VisibilityPolygon2$Drawer.render()           zero reachable callers
 *     renderNew() / renderOld()                  reachable only from render()
 *   VisibilityPolygon2.renderMain(int)           reachable from FBORenderCell,
 *                                               but gated on the developer option
 *                                               renderVisionPolygon (off)
 *   ViewConeTextureFBO.startDrawing()            reachable from TextureDraw#run()
 * </pre>
 *
 * <p>So the whole VisibilityPolygon2 path is dead on this install, and those two
 * IsoWorld methods are leftovers from before B42 moved to the lightmap pipeline.
 * Eleven earlier fixes aimed at them; the reachability walk is what finally
 * settled it, because the earlier scanner matched on owner+name and ignored the
 * descriptor, and so reported calls to overloads that do not exist.
 *
 * <p>The live cone draws inside the world pass, through TextureDraw, which means
 * its viewport request goes through the same funnel as the world's. That is the
 * displacement: the cone's vertices come from
 * {@code PlayerCamera.XToScreenExact}, which returns native 1920x1080 screen
 * coordinates, while the viewport they are rasterised into has been shrunk to
 * 1600x896. The world's own draws go through the projection matrix, which already
 * agrees with the shrunken viewport, so only the cone falls outside.
 *
 * <p>Letting the cone's viewport through keeps it inside the frame it was computed
 * for. It is drawn at native resolution inside a world pass that is upscaled, so
 * its edge is sharper than the terrain around it -- the cost of having it in the
 * right place.
 *
 * <p>Scope: the flag is raised by the cone's own start/stop pair and consumed by
 * {@code RenderScale.requestedViewport}. Nothing else changes.
 *
 * <p>Opt-in via {@code fsrupsc-coneviewport}.
 */
public final class ConeViewportHold {
    private ConeViewportHold() {}

    private static final AtomicInteger ENTERS = new AtomicInteger();

    private static boolean markerOn() {
        try {
            java.io.File f = new java.io.File(System.getProperty("user.home"),
                "Zomboid/fsrupsc-coneviewport");
            if (!f.isFile()) return false;
            String v = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).trim();
            return v.equalsIgnoreCase("on") || v.equals("1") || v.equals("true");
        } catch (Throwable t) {
            return false;
        }
    }

    private static final boolean ON = markerOn();

    public static boolean enabled() { return ON; }

    /** startDrawing: the cone lightmap begins. Log the first one unconditionally. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
        if (!ON) return;
        if (ENTERS.incrementAndGet() == 1) {
            try {
                FsrLog.log("cone hold: startDrawing #1 on "
                        + Thread.currentThread().getName());
            } catch (Throwable ignored) {}
        }
        try {
            fsrupsc.RenderScale.setConeViewportPassThrough(true);
        } catch (Throwable ignored) {}
    }

    /** stopDrawing lives in its own class: see End below. */

    /**
     * stopDrawing. Byte Buddy allows a single {@code @Advice} method per advice
     * class, and folding both entry points into one class made it apply the
     * return-bearing exit advice to the void method and abort the whole transform
     * with "Cannot assign void to class java.lang.Object" -- silently, because the
     * failure happens inside the builder.
     */
    public static final class End {
        private End() {}

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit() {
            if (!ON) return;
            try {
                fsrupsc.RenderScale.setConeViewportPassThrough(false);
            } catch (Throwable ignored) {}
        }
    }
}