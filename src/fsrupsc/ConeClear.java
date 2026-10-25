package fsrupsc;

import net.bytebuddy.asm.Advice;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * ViewConeTextureFBO.startDrawing exit: erase the cone.
 *
 * <p>{@code startDrawing()} binds the cone framebuffer and sets its viewport;
 * on return the freshly bound buffer still holds last frame's cone. With the
 * cone draw suppressed (see {@link ConeSuppress}) nothing would overwrite it,
 * so the stale overlay would persist -- and a stale cone is worse than none,
 * because it would be one frame out of place instead of absent.
 *
 * <p>Cleared to opaque white: the cone texture multiplies the world, so all
 * ones means "fully lit, nothing occluded", which is what an absent cone should
 * look like.
 *
 * <p>Runs on the render thread, which is the only thread that may touch GL.
 * PZ's own {@code TextureFBO.getBufferId()} is an internal counter rather than
 * the GL name, so the name is taken from {@code TextureFBO.lastID} that PZ
 * itself just set while binding.
 */
public final class ConeClear {
    private ConeClear() {}

    @Advice.OnMethodExit
    public static void exit() {
        try {
            if (!fsrupsc.RenderScale.coneSuppressed()) return;
            int id = zombie.core.textures.TextureFBO.lastID;
            if (id == 0) return;
            int savedDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int savedRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int savedColorMask = -1;
            try {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, id);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL11.glViewport(0, 0, 2048, 2048);
                // Scissor would clip the clear; the cone pass disables it, but
                // a previous pass may have left it on.
                boolean hadScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
                if (hadScissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                if (hadScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedRead);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDraw);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
            // never propagate into the game's own code
        }
    }
}
