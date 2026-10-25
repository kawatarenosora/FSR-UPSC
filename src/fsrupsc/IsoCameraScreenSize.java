package fsrupsc;

import net.bytebuddy.asm.Advice;

/**
 * IsoCamera.getScreenWidth / getScreenHeight: return the shrunken size while
 * the world pass is running.
 *
 * <p>The camera's world extent is derived from the screen size, not from the
 * viewport:
 *
 * <pre>
 *   Core.getOffscreenWidth(i)
 *     -&gt; MultiTextureFBO2.getWidth(i)
 *          -&gt; IsoCamera.getScreenWidth(i) * zoom * (tileScale / 2)
 * </pre>
 *
 * <p>so shrinking only the viewport leaves the camera still framing 1920 world
 * pixels into a 1600px viewport: the right and top of the world fall outside
 * and the upscale stretches a cropped image. That is the displacement -- the
 * visible centre lands up and to the right of where it belongs.
 *
 * <p>Scaling the reported size during the world pass makes the camera frame
 * <em>1600</em> world pixels, which the 1600px viewport then covers exactly, so
 * the same area is drawn with fewer pixels and no crop.
 *
 * <p>Restricted to the render thread ("main") on purpose. The composite runs on
 * the game thread ("MainThread") and reads the same accessors to pick its
 * destination rectangle; there the real size must come back, so the resolve
 * output still stretches across the whole window. Scaling both would also scale
 * the UI, which has to stay at native resolution.
 */
public final class IsoCameraScreenSize {
    private IsoCameraScreenSize() {}

    @Advice.OnMethodExit
    public static void exit(@Advice.Return(readOnly = false) int ret) {
        try {
            int scaled = fsrupsc.RenderScale.cameraScreenWidth();
            if (scaled > 0) ret = scaled;
        } catch (Throwable ignored) {}
    }
}
