package fsrupsc;

import net.bytebuddy.asm.Advice;

/** TextureDraw#drawModel enter: object motion tagging point (N2 records here). */
public final class DrawModelEnter {
    private DrawModelEnter() {}

    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(1) Object slot) {
        try {
            FsrHooks.tagObject(slot);
        } catch (Throwable ignored) {}
    }
}
