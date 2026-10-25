package fsrupsc;

import net.bytebuddy.asm.Advice;

/** SpriteRenderer#glDoEndFrame exit: world draws queued (game thread). */
public final class EndFrameExit {
    private EndFrameExit() {}

    @Advice.OnMethodExit
    public static void exit() {
        try {
            FsrHooks.onWorldEnd();
        } catch (Throwable ignored) {}
    }
}
