package fsrupsc;

import zombie.iso.IsoCamera;

/**
 * Frame-to-frame camera state for the FSR2 MV path (M2).
 *
 * <p>{@link #begin()} runs on the render thread right before our dispatch.
 * It snapshots IsoCamera.frameState (offX/offY/zoom/frameCount) and derives:
 * - the constant camera delta for MV_GEN (native screen px),
 * - stillness flags for ACCUMULATE ({@code uCamStill} / {@code uCamStillExact}),
 * - reset conditions (zoom change, resume-from-pause gap) that throw history.
 *
 * <p>{@link #end()} seals the frame: it promotes the snapshot to "prev",
 * exactly like ObjectMotion's frame sealing convention.
 *
 * <p>All reads are Throwable-guarded (hook-safe), matching Phase 1 rules.
 */
public final class CameraMotion {

    private static float curOffX;
    private static float curOffY;
    private static float curZoom;
    private static float prevOffX;
    private static float prevOffY;
    private static float prevZoom;
    private static int nativeW;
    private static int nativeH;
    private static boolean hasPrev;
    private static long lastFrameNo = Long.MIN_VALUE;
    private static long frameNo;

    /** uCameraDelta = (offCur - offPrev) in native screen px (z = 0). */
    private static float deltaX;
    private static float deltaY;
    /** jitter-excluded (real camera motion) deltas, native screen px. */
    private static float deltaRelX;
    private static float deltaRelY;
    private static boolean still;
    private static boolean stillExact;
    private static boolean zoomChanged;
    /** set when begin() detected a condition that demands history reset */
    private static boolean reset;

    private CameraMotion() {}

    static void begin() {
        try {
            frameNo = IsoCamera.frameState.frameCount;
            curOffX = IsoCamera.frameState.offX;
            curOffY = IsoCamera.frameState.offY;
            curZoom = IsoCamera.frameState.zoom;
            nativeW = zombie.core.Core.width;
            nativeH = zombie.core.Core.height;
        } catch (Throwable t) {
            FsrLog.log("fsr2 camera read failed: " + t);
            markNoPrev();
            return;
        }
        reset = false;
        long gap = frameNo - lastFrameNo;
        if (!hasPrev || (lastFrameNo != Long.MIN_VALUE && (gap > 3 || gap < -3))) {
            // first dispatch, world re-entry, or frame skip: no usable prev
            hasPrev = false;
        }
        zoomChanged = hasPrev && Math.abs(curZoom - prevZoom) > 1e-4f;
        if (zoomChanged) {
            hasPrev = false;
            reset = true;
        }
        if (hasPrev) {
            deltaX = curOffX - prevOffX;
            deltaY = curOffY - prevOffY;
            // stillness must ignore the injected jitter step (M2.5): the
            // base offsets are what "the camera actually moved". The raw
            // delta above keeps the jitter steps for MV/reprojection.
            float dxr = (curOffX - CameraJitter.injCurNativeX())
                - (prevOffX - CameraJitter.injPrevNativeX());
            float dyr = (curOffY - CameraJitter.injCurNativeY())
                - (prevOffY - CameraJitter.injPrevNativeY());
            float d = Math.max(Math.abs(dxr), Math.abs(dyr));
            stillExact = d < 0.01f;
            still = d < 0.5f;
            deltaRelX = dxr;
            deltaRelY = dyr;
        } else {
            markNoPrev();
        }
    }

    private static void markNoPrev() {
        // behaves like M1: cleared MVs + fresh accumulation
        deltaX = 0f;
        deltaY = 0f;
        deltaRelX = 0f;
        deltaRelY = 0f;
        still = true;
        stillExact = true;
        hasPrev = false;
    }

    // read accessors used by Fsr2Compute#dispatch
    static float curOffX() { return curOffX; }
    static float curOffY() { return curOffY; }
    static float zoom() { return curZoom; }
    static float prevOffX() { return prevOffX; }
    static float prevOffY() { return prevOffY; }
    static int nativeW() { return Math.max(64, nativeW); }
    static int nativeH() { return Math.max(64, nativeH); }

    static void end() {
        hasPrev = true;
        prevOffX = curOffX;
        prevOffY = curOffY;
        prevZoom = curZoom;
        lastFrameNo = frameNo;
        CameraJitter.promote();
    }

    static boolean hasPrev() {
        return hasPrev;
    }

    static boolean resetRequested() {
        return reset;
    }

    static float deltaX() { return deltaX; }
    static float deltaY() { return deltaY; }
    static float deltaRelX() { return deltaRelX; }
    static float deltaRelY() { return deltaRelY; }
    static boolean still() { return still; }
    static boolean stillExact() { return stillExact; }
    static boolean zoomChanged() { return zoomChanged; }
}
