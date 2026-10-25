package fsrupsc;

import net.bytebuddy.asm.Advice;

/** RenderThread#renderLoop enter: render-context thread is alive. */
public final class RenderLoopEnter {
    private RenderLoopEnter() {}

    @Advice.OnMethodEnter
    public static void enter() {
        try {
            FsrHooks.onRenderLoop();
        } catch (Throwable ignored) {}
    }
}
