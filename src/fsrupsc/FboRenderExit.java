package fsrupsc;

import net.bytebuddy.asm.Advice;

/** MultiTextureFBO2#render exit: final composite done. */
public final class FboRenderExit {
    private FboRenderExit() {}

    @Advice.OnMethodExit
    public static void exit() {
        try {
            FsrHooks.postComposite();
        } catch (Throwable ignored) {}
    }
}
