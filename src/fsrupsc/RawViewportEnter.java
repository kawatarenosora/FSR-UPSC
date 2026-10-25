package fsrupsc;

import net.bytebuddy.asm.Advice;

/**
 * Raw LWJGL glViewport funnel (static, 4 args). The game sets viewports
 * mostly via direct GL11 calls (ModelManager sky/particles, world passes),
 * bypassing every PZ-level funnel. This catches them all.
 * Suppressed while our own resolve runs (reentry guard).
 */
public final class RawViewportEnter {
    private RawViewportEnter() {}

    @Advice.OnMethodEnter
    public static void enter(
            @Advice.Argument(value = 0, readOnly = false) int x,
            @Advice.Argument(value = 1, readOnly = false) int y,
            @Advice.Argument(value = 2, readOnly = false) int w,
            @Advice.Argument(value = 3, readOnly = false) int h) {
        try {
            if (FsrHooks.suppressViewport()) return;
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
