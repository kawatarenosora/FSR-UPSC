package fsrupsc;

import net.bytebuddy.asm.Advice;

/** TextureDraw#drawModel exit: close the stencil tag. */
public final class DrawModelExit {
    private DrawModelExit() {}

    @Advice.OnMethodExit
    public static void exit() {
        try {
            FsrHooks.untagObject();
        } catch (Throwable ignored) {}
    }
}
