package fsrupsc;

import net.bytebuddy.asm.Advice;

/** SpriteRenderer#glDoStartFrame exit: world frame setup done (game thread). */
public final class StartFrameExit {
    private StartFrameExit() {}

    @Advice.OnMethodExit
    public static void exit(
            @Advice.Argument(0) int w,
            @Advice.Argument(1) int h) {
        try {
            FsrHooks.onWorldStart(w, h);
        } catch (Throwable ignored) {}
    }
}
