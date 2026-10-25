package fsrupsc;

import net.bytebuddy.asm.Advice;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hold the scaled screen size across {@code MultiTextureFBO2.renderCursor}, which
 * draws the aim reticle.
 *
 * <p>Twelve fixes aimed at the view cone before this one, all at code that turned
 * out to be dead on this install. Profiling with exact descriptors over the whole
 * jar (22990 classes, 654428 call sites, including invokedynamic) established what
 * is actually live:
 *
 * <pre>
 *   IsoWorld.DrawPlayerCone / DrawPlayerCone2       zero reachable callers
 *   VisibilityPolygon2$Drawer.render()              zero reachable callers
 *     renderNew() / renderOld() / renderToScreen()  reachable only from render()
 *   VisibilityPolygon2.renderMain(int)              reachable, but behind the
 *                                                   developer option renderVisionPolygon
 *   ViewConeTextureFBO                             the class is never loaded
 *   media/shaders/visibilityBlur.frag              referenced only by that Drawer
 * </pre>
 *
 * <p>So the cone the player sees is not any of those. It is
 * {@code IsoReticle} -- the cone-shaped aiming overlay with its own
 * {@code isoreticle} shader -- and the only live caller of its render is here:
 *
 * <pre>
 *   MultiTextureFBO2#renderCursor(IsoPlayer)
 *     IsoReticle#render()   sizes itself from IsoCamera.getScreenWidth/Height
 *     IsoCursor#render(int)
 * </pre>
 *
 * <p>It runs while the world framebuffer is still bound and its viewport is still
 * 1600x896, but {@link fsrupsc.RenderScale#scaledView()} is false there, so the
 * camera override reports 1920x1080 and the overlay is laid out for a frame 20%
 * wider than the one it is drawn into.
 *
 * <p>The visual test that put it here: with the composite texture swap disabled the
 * ground moves to 83% in the corner and the cone stays where it was. The cone is a
 * separate screen-space layer, not part of the world image, which no amount of
 * viewport or quad reasoning inside the world pass could produce.
 *
 * <p>Scope: one method. Nothing else reads the camera size during it.
 *
 * <p>Opt-in via {@code fsr-upsc-reticle}.
 */
public final class ReticleHold {
    private ReticleHold() {}

    private static final AtomicInteger ENTERS = new AtomicInteger();

    private static boolean markerOn() {
        try {
            java.io.File f = new java.io.File(System.getProperty("user.home"),
                "Zomboid/fsr-upsc-reticle");
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

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
        if (!ON) return;
        if (ENTERS.incrementAndGet() == 1) {
            try {
                FsrLog.log("reticle hold: renderCursor ENTER #1 on "
                        + Thread.currentThread().getName());
            } catch (Throwable ignored) {}
        }
        try {
            fsrupsc.RenderScale.setReticleHold(true);
        } catch (Throwable ignored) {}
    }

    /**
     * stop, in its own class: Byte Buddy allows one {@code @Advice} method per
     * advice class, and folding both into one made it apply the return-bearing exit
     * advice to the void method and abort the transform with "Cannot assign void to
     * class java.lang.Object" -- silently, because the failure is inside the
     * builder.
     */
    public static final class End {
        private End() {}

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit() {
            if (!ON) return;
            try {
                fsrupsc.RenderScale.setReticleHold(false);
            } catch (Throwable ignored) {}
        }
    }
}
