package fsrupsc;

import net.bytebuddy.asm.Advice;

/**
 * TextureFBO#getTexture exit: substitute the resolve output.
 *
 * <p>Inside {@code MultiTextureFBO2#render} the composite loads its texture
 * with {@code current.getTexture()} (bytecode offset 161) and immediately draws
 * it through {@code Texture#rendershader2}, which renders {@code this} and so
 * cannot be redirected by an argument swap. Returning a different texture here
 * is the cleanest available seam: the rectangle maths inside
 * {@code rendershader2} normalises against the receiver's
 * {@code getWidthHW()}, so a 1920x1080 wrapper is sampled in full instead of
 * the 2048x2048 world texture's lower-left region.
 *
 * <p>Pure Java: no GL is issued, which matters because the composite only ever
 * runs on the game thread, where GL cannot be issued at all.
 *
 * <p>Gated by {@link fsrupsc.ResolveOutput#inWindow()} so nothing outside the
 * composite -- the world pass, chunk rendering, weather masks -- is affected.
 */
public final class FboTextureGetter {
    private FboTextureGetter() {}

    @Advice.OnMethodExit
    public static void exit(
            @Advice.Return(readOnly = false) zombie.interfaces.ITexture ret) {
        try {
            ret = fsrupsc.ResolveOutput.compositeTexture(ret);
        } catch (Throwable ignored) {
            // never propagate into the game's own code
        }
    }
}