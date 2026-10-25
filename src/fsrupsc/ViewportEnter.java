package fsrupsc;

import net.bytebuddy.asm.Advice;

/**
 * SpriteRenderer#glViewport enter: the real world-path viewport funnel
 * (delegates to GenericSpriteRenderState). Argument write-back guarded:
 * passthrough unless RenderScale accepts the rect.
 */
public final class ViewportEnter {
    private ViewportEnter() {}

    @Advice.OnMethodEnter
    public static void enter(
            @Advice.Argument(value = 0, readOnly = false) int x,
            @Advice.Argument(value = 1, readOnly = false) int y,
            @Advice.Argument(value = 2, readOnly = false) int w,
            @Advice.Argument(value = 3, readOnly = false) int h) {
        try {
            int[] r = FsrHooks.scaleViewport(x, y, w, h);
            if (r != null) {
                x = r[0];
                y = r[1];
                w = r[2];
                h = r[3];
            }
        } catch (Throwable ignored) {}
    }
}
