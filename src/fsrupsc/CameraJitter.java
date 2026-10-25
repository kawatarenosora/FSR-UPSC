package fsrupsc;

import zombie.iso.IsoCamera;
import zombie.core.Core;

/**
 * M2.5: sub-pixel jitter injection into the iso camera (opt-in).
 *
 * <p>Per world pass, advances the Halton(2,3) sequence and adds the jitter
 * (converted to native screen px) to {@code IsoCamera.frameState.offX/offY}
 * before any world sprite position is computed, and restores the exact value
 * at world pass end (after the FSR2 dispatch).
 *
 * <p>Unit chain (see docs/PHASE2-FSR3-GLSLDESIGN.md 4.4):
 * jitter is produced in render px [-0.5, 0.5) (JitterHalton). Native offset
 * step = jx / kx, kx = inW / nativeW (per-axis; render-space px for a native
 * screen px is k). Injection direction mirrors the AMD de-jitter contract
 * (ACCUMULATE: lrUv = hrUv - uJitter/renderSize, LOCK: hrPosFromLrPos), so
 *   offX += jx/kx   (content shifts -jx in u)
 *   offY -= jy/ky   (texture v is flipped vs screen y).
 *
 * <p>Restore safety: frameState may either keep our value or be rebuilt
 * in place/by new instance. Restore only when the instance is identity-equal
 * AND the float value still exactly matches the value we wrote.
 *
 * <p>Everything is off by default; {@code FsrControl.fsr2Jitter(1)} enables
 * it for the session. All Throwable-guarded, render-thread only.
 */
final class CameraJitter {

    private static int level;                 // 0=off 1=on
    private static final JitterHalton seq = new JitterHalton();
    private static boolean hasCur;
    private static float curX, curY;          // render px, this frame
    private static float prevX, prevY;        // render px, previous dispatched frame
    private static float injCurX, injCurY;    // native px, this frame
    private static float injPrevX, injPrevY;  // native px, previous dispatched frame
    private static Object stateRef;           // frameState instance we wrote into
    private static float wroteX, wroteY;      // exact values we wrote
    private static boolean injected;

    private CameraJitter() {}

    /** Console runtime toggle: 0=off 1=on. */
    static void setLevel(int v) {
        int nv = Math.max(0, Math.min(1, v));
        if (nv != level) reset();
        level = nv;
        FsrLog.log("fsr2 camera jitter = " + (nv > 0 ? "on" : "off"));
    }

    static int level() { return level; }

    static boolean active() { return level > 0; }

    /** Hard off without logging (used when the compute path faults). */
    static void forceOff() {
        level = 0;
        reset();
    }

    /** Current-frame jitter in render px (0,0 when off). */
    static float jitCurX() { return hasCur ? curX : 0f; }
    static float jitCurY() { return hasCur ? curY : 0f; }

    static String statusLine() {
        if (level == 0) return "jit=off";
        return "jit=on cur=(" + fmt(curX) + "," + fmt(curY) + ")"
            + " prev=(" + fmt(prevX) + "," + fmt(prevY) + ")";
    }

    private static String fmt(float v) { return String.format("%.2f", v); }

    // ----------------------------------------------------------- pass hooks

    /**
     * World pass begin (world FBO only): advance the sequence, inject into
     * the camera offsets. Called on the render thread before any world
     * sprite is drawn this frame.
     */
    static void beginWorldPass() {
        if (level <= 0 || injected) return;
        try {
            if (!RenderScale.active()
                    || !("fsr2".equalsIgnoreCase(RenderScale.mode())
                        || "fsr3".equalsIgnoreCase(RenderScale.mode()))) return;
            int nativeW = Math.max(64, Core.width);
            int nativeH = Math.max(64, Core.height);
            int[] in = RenderScale.scaledRect(nativeW, nativeH);
            int inW = in[0];
            int inH = in[1];
            if (inW <= 0 || inH <= 0) return;
            float[] out = new float[2];
            seq.next(inW, nativeW, out);
            curX = out[0];
            curY = out[1];

            float kx = in[0] / (float) nativeW;
            float ky = in[1] / (float) nativeH;
            if (kx <= 0.01f || ky <= 0.01f) return;
            injCurX = curX / kx;
            injCurY = curY / ky;

            IsoCamera.frameState.offX += injCurX;
            IsoCamera.frameState.offY -= injCurY;
            stateRef = IsoCamera.frameState;
            wroteX = IsoCamera.frameState.offX;
            wroteY = IsoCamera.frameState.offY;
            injected = true;
        } catch (Throwable t) {
            injected = false;
            hasCur = false;
            curX = curY = 0f;
            injCurX = injCurY = 0f;
        }
    }

    /**
     * World pass end: undo the injection when the frame state still carries
     * our value. Called right after the world resolve (or its early return).
     */
    static void endWorldPass() {
        if (!injected) return;
        injected = false;
        try {
            if (IsoCamera.frameState == stateRef
                    && IsoCamera.frameState.offX == wroteX
                    && IsoCamera.frameState.offY == wroteY) {
                IsoCamera.frameState.offX -= injCurX;
                IsoCamera.frameState.offY += injCurY;
            }
        } catch (Throwable ignored) {}
        stateRef = null;
        wroteX = wroteY = 0f;
        // leave curX/curY/injCur intact for the still check + uniforms; they
        // are promoted by CameraMotion.end() only on a successful dispatch.
    }

    /** Promote this frame's jitter to prev — call from CameraMotion.end(). */
    static void promote() {
        prevX = curX;
        prevY = curY;
        injPrevX = injCurX;
        injPrevY = injCurY;
        hasCur = false;
        curX = curY = 0f;
        injCurX = injCurY = 0f;
    }

    /** Reset the sequence and bookkeeping (resolution/level change). */
    static void reset() {
        seq.reset();
        hasCur = false;
        curX = curY = 0f;
        prevX = prevY = 0f;
        injCurX = injCurY = 0f;
        injPrevX = injPrevY = 0f;
        injected = false;
        stateRef = null;
        wroteX = wroteY = 0f;
    }

    // sizes for MotionField-style conversions live here as accessors
    static float injCurNativeX() { return injCurX; }
    static float injCurNativeY() { return injCurY; }
    static float injPrevNativeX() { return injPrevX; }
    static float injPrevNativeY() { return injPrevY; }
}
