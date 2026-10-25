package fsrupsc;

import net.bytebuddy.asm.Advice;

/**
 * IsoCamera.getScreenHeight: the vertical half of {@link IsoCameraScreenSize}.
 *
 * <p>Separate class because Advice reads one method's return value; pairing two
 * accessors in a single advice is not possible.
 */
public final class IsoCameraScreenHeight {
    private IsoCameraScreenHeight() {}

    @Advice.OnMethodExit
    public static void exit(@Advice.Return(readOnly = false) int ret) {
        try {
            int scaled = fsrupsc.RenderScale.cameraScreenHeight();
            if (scaled > 0) ret = scaled;
        } catch (Throwable ignored) {}
    }
}
