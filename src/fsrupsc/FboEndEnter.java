package fsrupsc;

import net.bytebuddy.asm.Advice;

/** TextureFBO#endDrawing enter. */
public final class FboEndEnter {
    private FboEndEnter() {}

    @Advice.OnMethodEnter
    public static void enter(@Advice.This Object fbo) {
        try {
            FsrHooks.onFboEnd(fbo);
        } catch (Throwable ignored) {}
    }
}
