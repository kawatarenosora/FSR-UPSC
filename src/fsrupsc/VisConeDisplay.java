package fsrupsc;

import zombie.core.textures.TextureFBO;

/**
 * Replacement for {@code TextureFBO.getWidth()/getHeight()} inside the view
 * cone's blur draw.
 *
 * <p>{@code VisibilityPolygon2$Drawer.renderToScreen()} feeds the blur shader
 * four uniforms:
 *
 * <pre>
 *   glViewport(IsoCamera.getScreenLeft(p), IsoCamera.getScreenTop(p),
 *              IsoCamera.getScreenWidth(p), IsoCamera.getScreenHeight(p));
 *   screenSize    = (IsoCamera.getScreenWidth(p),  IsoCamera.getScreenHeight(p));
 *   displayOrigin = (IsoCamera.getScreenLeft(p),   IsoCamera.getScreenTop(p));
 *   displaySize   = (fbo.getWidth() / divX, fbo.getHeight() / divY);
 * </pre>
 *
 * <p>The first three go through {@code IsoCamera}, so the camera override
 * shrinks them together with the viewport. {@code displaySize} does not: it
 * reads the framebuffer's allocation (2048x2048) straight off the field. The
 * shader is handed a scaled screenSize beside an unscaled displaySize, and the
 * cone lands off-centre from the player.
 *
 * <p>Substituted for those two call sites only. Hooking {@code Vector2.set}
 * instead was tried and scaled every vector the world pass computes, which
 * double-drew the player -- the cone's values are one of four, not all of them.
 *
 * <p>Untouched outside a scaled world pass.
 */
public final class VisConeDisplay {
    private VisConeDisplay() {}

    /** Stands in for {@code TextureFBO.getWidth()} in renderToScreen. */
    public static int displayWidth(TextureFBO fbo) {
        int w = (fbo == null) ? fallbackWidth() : fbo.getWidth();
        int r = (int) (w * RenderScale.coneScale());
        trace(w, r);
        return r;
    }

    /** Stands in for {@code TextureFBO.getHeight()} in renderToScreen. */
    public static int displayHeight(TextureFBO fbo) {
        int h = (fbo == null) ? fallbackHeight() : fbo.getHeight();
        int r = (int) (h * RenderScale.coneScale());
        trace(h, r);
        return r;
    }

    /**
     * Prove the substitution is reached and is actually changing the value.
     *
     * <p>The earlier version of this hook asked whether it was inside the world
     * pass, which is never true here -- the cone blur runs outside it -- so it
     * returned the unscaled number and the fix did nothing at all, silently. A
     * counter line distinguishes that from "the substitution landed but the cone
     * was still wrong".
     */
    private static final java.util.concurrent.atomic.AtomicBoolean ONCE =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static void trace(int raw, int scaled) {
        if (!ONCE.compareAndSet(false, true)) return;
        try {
            fsrupsc.FsrLog.log("cone displaySize " + raw + " -> " + scaled
                    + " (scale=" + RenderScale.coneScale() + ")");
        } catch (Throwable ignored) {}
    }

    private static int fallbackWidth() {
        try {
            return zombie.core.Core.width;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int fallbackHeight() {
        try {
            return zombie.core.Core.height;
        } catch (Throwable t) {
            return 0;
        }
    }
}