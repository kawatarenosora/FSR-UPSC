package fsrupsc;

import net.bytebuddy.asm.Advice;

/** TextureFBO#startDrawing enter: track bound FBOs per pass. */
public final class FboBeginEnter {
    private FboBeginEnter() {}

    @Advice.OnMethodEnter
    public static void enter(@Advice.This Object fbo) {
        try {
            FsrHooks.onFboBegin(fbo);
        } catch (Throwable ignored) {}
    }
}
