package fsrupsc;

import java.util.IdentityHashMap;

import org.lwjgl.opengl.GL11;

import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.skinnedmodel.ModelManager;
import zombie.iso.IsoCamera;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoUtils;
import zombie.iso.PlayerCamera;

/**
 * Per-character/vehicle motion for temporal upscalers.
 * Logic mirrors upstream (xD3I/PZ_Optimization ObjectMotion.java):
 * game thread records via TextureDraw.drawModel, the resolve reads
 * the sealed frame. FSR path ignores the data.
 */
public final class ObjectMotion {
    static final int MAX_IDS = 126;
    private static final int SLOTS = 4;

    /** One frame's entries: screen-pixel rects (x, y, w, h; y down) + motion (dx, dy). */
    public static final class Frame {
        public int count;
        public final float[] rect = new float[MAX_IDS * 4];
        public final float[] motion = new float[MAX_IDS * 2];
        public long frameNo;

        void clear() {
            count = 0;
        }
    }

    private static final Frame[] FRAMES = new Frame[SLOTS];
    private static int slot;
    private static long frameNo;
    private static boolean stencilOn;
    private static final IdentityHashMap<Object, float[]> LAST = new IdentityHashMap<>();
    private static long lastPruned;

    static {
        for (int i = 0; i < SLOTS; i++) {
            FRAMES[i] = new Frame();
        }
    }

    static boolean enabled() {
        return FsrConfig.isUpscalerObjectMv() && RenderScale.active()
            && Upscaler.drawsOutput() && !"fsr1".equalsIgnoreCase(RenderScale.mode());
    }

    /**
     * Records the model's object, returns its stencil id (0 = skipped).
     * Never throws (hook-safe).
     */
    public static int record(Object slotObj) {
        try {
            if (!enabled()) return 0;
            if (!(slotObj instanceof ModelManager.ModelSlot)) return 0;
            ModelManager.ModelSlot ms = (ModelManager.ModelSlot) slotObj;
            IsoMovingObject object = ms.character;
            if (object == null && ms.model != null) {
                try {
                    object = ms.model.object;
                } catch (Throwable ignored) {
                    return 0;
                }
            }
            if (object == null) return 0;
            Frame f = FRAMES[slot];
            if (f.count >= MAX_IDS) return 0;
            int playerIndex;
            PlayerCamera camera;
            float zoom;
            try {
                playerIndex = IsoCamera.frameState.playerIndex;
                camera = IsoCamera.cameras[playerIndex];
                zoom = camera.zoom <= 0 ? 1 : camera.zoom;
            } catch (Throwable ignored) {
                return 0;
            }
            float x, y, z;
            try {
                x = object.getX();
                y = object.getY();
                z = object.getZ();
            } catch (Throwable ignored) {
                return 0;
            }
            float sx, sy;
            try {
                sx = (IsoUtils.XToScreen(x, y, z, 0) - camera.getOffX()) / zoom;
                sy = (IsoUtils.YToScreen(x, y, z, 0) - camera.getOffY()) / zoom;
            } catch (Throwable ignored) {
                return 0;
            }
            float[] last = LAST.get(object);
            boolean known = last != null && last[2] == frameNo - 1;
            float dx = 0, dy = 0;
            if (known) {
                dx = last[0] - sx;
                dy = last[1] - sy;
            }
            if (last == null) {
                last = new float[3];
                LAST.put(object, last);
            }
            last[0] = sx;
            last[1] = sy;
            last[2] = frameNo;
            if (!known) return 0; // first sight: no motion yet
            float tile = 64.0f * Core.tileScale;
            float halfW, up, down;
            if (object instanceof IsoGameCharacter) {
                halfW = tile * 0.6f;
                up = tile * 1.7f;
                down = tile * 0.3f;
            } else {
                halfW = tile * 2.6f;
                up = tile * 1.8f;
                down = tile * 1.6f;
            }
            int i = f.count++;
            f.rect[i * 4] = sx - halfW / zoom;
            f.rect[i * 4 + 1] = sy - up / zoom;
            f.rect[i * 4 + 2] = 2.0f * halfW / zoom;
            f.rect[i * 4 + 3] = (up + down) / zoom;
            f.motion[i * 2] = dx;
            f.motion[i * 2 + 1] = dy;
            return i + 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * The in-progress frame slot (models currently being drawn this world
     * pass). Read by MotionField at the resolve point, which runs inside the
     * same world pass, after the model draws but before endFrame seals.
     */
    static Frame current() {
        return FRAMES[slot];
    }

    /** Seal the frame and start a fresh slot. Render thread reads the return. */
    static Frame endFrame() {
        try {
            Frame done = FRAMES[slot];
            done.frameNo = frameNo;
            frameNo++;
            slot = (slot + 1) % SLOTS;
            FRAMES[slot].clear();
            if (frameNo - lastPruned > 600) {
                lastPruned = frameNo;
                LAST.values().removeIf(v -> v[2] < frameNo - 120);
            }
            return done;
        } catch (Throwable ignored) {
            return FRAMES[0];
        }
    }

    /** Before a model draw with an id: tag covered pixels in the stencil. */
    public static void beginStencil(int id) {
        try {
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glStencilMask(0x7F);
            GL11.glStencilFunc(GL11.GL_ALWAYS, id & 0x7F, 0x7F);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
            stencilOn = true;
        } catch (Throwable ignored) {}
    }

    /** After it: put the game's cached stencil state back (only if we began). */
    public static void endStencil() {
        if (!stencilOn) return;
        stencilOn = false;
        try {
            GLStateRenderThread.StencilFunc.restore();
            GLStateRenderThread.StencilOp.restore();
            GLStateRenderThread.StencilMask.restore();
            GLStateRenderThread.StencilTest.restore();
        } catch (Throwable ignored) {}
    }

    static long waterTagged, waterOff, waterElsewhere;
    static int waterTaggedThisFrame;

    /** Clears everything (resolution change, mode switch). */
    static void reset() {
        try {
            for (Frame f : FRAMES) f.clear();
            LAST.clear();
        } catch (Throwable ignored) {}
    }

    private ObjectMotion() {}
}
