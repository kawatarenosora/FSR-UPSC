package fsrupsc;

import net.bytebuddy.asm.Advice;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code VisibilityPolygon2.renderMain} entry/exit: draw the view cone at native
 * size while the world is being drawn at the reduced size.
 *
 * <p>Establishing where the cone is actually drawn took six wrong guesses. The
 * path is:
 *
 * <pre>
 *   FBORenderCell.performRenderTiles(...)
 *     -&gt; VisibilityPolygon2.renderMain(int)          ← gated on a debug option
 *          SpriteRenderer.drawGeneric(drawer)          ← GenericDrawer, not drawGeneric queue
 *             Drawer.render()
 *               if (DebugOptions.useNewVisibility) renderNew()   ← false on this install
 *               else                             renderOld()   ← runs
 *                 triangulatePolygons -&gt; pc.XToScreenExact(...)  ← native 1920x1080
 *                 VBORenderer.flush()
 * </pre>
 *
 * <p>Not {@code IsoWorld.DrawPlayerCone} / {@code DrawPlayerCone2}: a full scan
 * of the jar (23829 classes) found zero call sites for either, so the hooks aimed
 * at them could never fire. Nor {@code renderToScreen}: that belongs to
 * {@code renderNew}, which this install does not take.
 *
 * <p>The displacement itself: the cone's vertices come from
 * {@code PlayerCamera.XToScreenExact}, which returns native screen coordinates,
 * and they are written straight into the VBO. The world's own draws go through
 * the projection matrix, which already agrees with the 1600x896 viewport, so only
 * the cone lands off. Scaling its vertices (an earlier attempt) did not help
 * because the quad is not the only contributor -- the viewport the cone is
 * rasterised into is still the shrunken one.
 *
 * <p>So: hold the viewport funnel off for the duration of {@code renderMain}. The
 * cone then rasterises into the full 1920x1080 area and lands where its geometry
 * says. Cost: the cone is drawn at native resolution while the ground under it is
 * upscaled from 1600x896, so its edge is sharper than the terrain. That is the
 * trade for having it in the right place.
 *
 * <p>Scope is this one method. The world pass, the UI and every other draw are
 * untouched.
 *
 * <p>Opt-in via {@code fsrupsc-coneviewport}: it changes how the cone is drawn,
 * so it waits for the marker rather than becoming the default.
 */
public final class ConeViewportPass {
    private ConeViewportPass() {}

    private static final AtomicBoolean ON = new AtomicBoolean(markerOn());
    private static final java.util.concurrent.atomic.AtomicInteger SEEN =
            new java.util.concurrent.atomic.AtomicInteger();

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

    public static boolean enabled() { return ON.get(); }

    /**
     * Where the cone actually draws. Bisected, not guessed.
     *
     * <p>Established by elimination: with the mod off (no viewport shrink) the cone
     * is correct; with the shrink on and every cone hook removed it is still
     * displaced. So the shrink is the cause and the question is whether the cone is
     * drawn <em>inside</em> the shrunken world pass or <em>outside</em> it.
     *
     * <p>If it draws inside, the cone shares the 1600x896 viewport with the world and
     * is displaced for a different reason. If it draws outside -- after the world
     * FBO was unbound and the viewport restored -- its geometry was computed against
     * the world rectangle while it is rasterised at native size, which is exactly
     * the reported displacement.
     *
     * <p>Logs the viewport and the bound FBO, once, unconditionally at method entry
     * so a hook that never fires is distinguishable from one that fires.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
        if (SEEN.incrementAndGet() == 1) {
            try {
                int[] vp = {
                    org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_VIEWPORT),
                    org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_VIEWPORT + 1),
                    org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_VIEWPORT + 2),
                    org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_VIEWPORT + 3),
                };
                zombie.core.Core core = zombie.core.Core.getInstance();
                FsrLog.log("cone renderMain ENTER: viewport=" + vp[0] + "," + vp[1]
                        + "," + vp[2] + "," + vp[3]
                        + " worldFboBound=" + fsrupsc.RenderScale.worldFboBound()
                        + " screen=" + core.width + "x" + core.height
                        + " offW=" + core.getOffscreenWidth(0)
                        + " offH=" + core.getOffscreenHeight(0)
                        + "  [" + Thread.currentThread().getName() + "]");
            } catch (Throwable ignored) {
            }
        }
        if (!ON.get()) return;
        try {
            fsrupsc.RenderScale.setConeViewportPassThrough(true);
        } catch (Throwable ignored) {}
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit() {
        if (!ON.get()) return;
        try {
            fsrupsc.RenderScale.setConeViewportPassThrough(false);
        } catch (Throwable ignored) {}
    }
}