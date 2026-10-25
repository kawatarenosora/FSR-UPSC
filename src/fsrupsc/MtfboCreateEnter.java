package fsrupsc;

import net.bytebuddy.asm.Advice;

/**
 * MultiTextureFBO2#create(int,int) enter.
 *
 * The stock composite already stretches whatever the offscreen buffer
 * holds across the whole screen, so the only thing an upscaler has to do
 * is make that buffer physically smaller. Scaling the allocation is the
 * whole feature: the game then sets its own viewport from the FBO size,
 * the world fills the buffer instead of a corner, and render() performs
 * the upscale. No custom shaders, no in-place rewrite, and nothing runs
 * on the composite's thread (which is the game thread and cannot issue
 * GL).
 */
public final class MtfboCreateEnter {
    private MtfboCreateEnter() {}

    @Advice.OnMethodEnter
    public static void enter(@Advice.This Object self,
            @Advice.Argument(value = 0, readOnly = false) int w,
            @Advice.Argument(value = 1, readOnly = false) int h) {
        try {
            int[] tgt = FsrHooks.worldFboTargetSize(self, w, h);
            if (tgt == null) {
                FsrHooks.noteFboCreateDeclined(self, w, h);
                return;
            }
            w = tgt[0];
            h = tgt[1];
            FsrHooks.noteWorldFboCreated(w, h);
        } catch (Throwable t) {
            try {
                fsrupsc.FsrLog.log("create hook threw: " + t);
            } catch (Throwable ignored) {}
        }
    }

    /** After the textures exist: report what was really allocated. */
    @Advice.OnMethodExit
    public static void exit(@Advice.This Object self) {
        try {
            if (FsrHooks.worldFboScalePermille(self) <= 0) return;
            zombie.core.textures.MultiTextureFBO2 m =
                    (zombie.core.textures.MultiTextureFBO2) self;
            StringBuilder sb = new StringBuilder("created[");
            for (int i = 0; i < 4; i++) {
                sb.append(i).append('=').append(m.getWidth(i)).append('x')
                  .append(m.getHeight(i)).append(' ');
            }
            zombie.core.textures.TextureFBO cur = m.current;
            if (cur != null) {
                sb.append("tex=").append(cur.getWidth()).append('x').append(cur.getHeight());
            }
            sb.append(']');
            FsrHooks.emit(sb.toString());
        } catch (Throwable t) {
            try {
                fsrupsc.FsrLog.log("create exit probe failed: " + t);
            } catch (Throwable ignored) {}
        }
    }
}