package fsrupsc;

import net.bytebuddy.asm.Advice;

/** org.lwjglx.opengl.Display#swapBuffers enter: the GLFW present call. */
public final class SwapBuffersEnter {
    private SwapBuffersEnter() {}

    @Advice.OnMethodEnter
    public static void enter() {
        try {
            FgPresent.onSwapEnter();
        } catch (Throwable ignored) {}
    }
}
