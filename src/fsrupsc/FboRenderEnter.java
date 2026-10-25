package fsrupsc;

import net.bytebuddy.asm.Advice;

/** MultiTextureFBO2#render enter: final composite starts. */
public final class FboRenderEnter {
    private FboRenderEnter() {}

    @Advice.OnMethodEnter
    public static void enter(@Advice.This Object fbo) {
        try {
            FsrHooks.preComposite(fbo);
        } catch (Throwable ignored) {}
    }
}
