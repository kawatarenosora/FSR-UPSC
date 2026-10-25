package fsrupsc;

import net.bytebuddy.asm.Advice;

/** GameWindow#renderInternal enter: game-thread frame boundary. */
public final class RenderInternalEnter {
    private RenderInternalEnter() {}

    @Advice.OnMethodEnter
    public static void enter() {
        try {
            FsrHooks.onRenderInternalEnter();
        } catch (Throwable ignored) {}
    }
}
