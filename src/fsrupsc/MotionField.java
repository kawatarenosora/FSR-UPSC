package fsrupsc;

/**
 * CPU-side entity motion field for the FSR2 MV path (M2b).
 *
 * <p>Consumes {@link ObjectMotion}'s in-progress frame at the resolve point
 * (same world pass, model draws already recorded) and converts rects/motions
 * into the layout MV_OVERRIDE_CS.glsl expects — all in render pixels, with
 * mv following the pipeline convention {@code mv = prev - cur}:
 *
 * <pre>   posm[i]  = (rectX, rectY, mvX, mvY)
 *   sizeh[i] = (w, h, 0, 0)</pre>
 *
 * <p>Unit conversions (ObjectMotion rect/motion are engine px divided by
 * zoom): device px = normalizedPx * zoom * k where k shrinks the native
 * frame; per-axis kx = inW/nativeW, ky = inH/nativeH.
 *
 * <p>All CPU-only; the GL upload/dispatch lives in {@link Fsr2Compute}.
 */
public final class MotionField {

    static final int MAX = ObjectMotion.MAX_IDS;

    /** std140 payload: 126 * (posm vec4 + sizeh vec4) floats. */
    private static final float[] DATA = new float[MAX * 8];
    private static int count;
    private static long lastUploadCount;

    private MotionField() {}

    /**
     * Refresh from the current ObjectMotion frame. Call on the render thread
     * before dispatching the override pass. Returns the entry count (0 =
     * skip the pass). Throwable-guarded: on any problem, count = 0 (entities
     * fall back to camera motion only, never crash the pipeline).
     */
    public static int refresh(int inW, int inH) {
        try {
            count = 0;
            ObjectMotion.Frame f = ObjectMotion.current();
            if (f == null || f.count <= 0) return 0;
            float zoom = CameraMotion.zoom();
            if (!(zoom > 0) || zoom != zoom) zoom = 1f;
            int nw = CameraMotion.nativeW();
            int nh = CameraMotion.nativeH();
            float kx = nw > 0 ? (float) inW / nw : 1f;
            float ky = nh > 0 ? (float) inH / nh : 1f;
            int n = Math.min(f.count, MAX);
            for (int i = 0; i < n; i++) {
                float rx = f.rect[i * 4];
                float ry = f.rect[i * 4 + 1];
                float rw = f.rect[i * 4 + 2];
                float rh = f.rect[i * 4 + 3];
                float dx = f.motion[i * 2];
                float dy = f.motion[i * 2 + 1];
                // sanity: drop degenerate/teleport entries
                if (rw <= 0 || rh <= 0 || rw > 4096 || rh > 4096) continue;
                float mLen = Math.max(Math.abs(dx), Math.abs(dy));
                if (mLen > 512f) continue;
                int o = i * 8;
                DATA[o + 0] = rx * zoom * kx;
                DATA[o + 1] = ry * zoom * ky;
                DATA[o + 2] = dx * zoom * kx;
                DATA[o + 3] = dy * zoom * ky;
                DATA[o + 4] = rw * zoom * kx;
                DATA[o + 5] = rh * zoom * ky;
                DATA[o + 6] = 0f;
                DATA[o + 7] = 0f;
                count++;
            }
            if (count < MAX) {
                java.util.Arrays.fill(DATA, count * 8, MAX * 8, 0f);
            }
            lastUploadCount = count;
            return count;
        } catch (Throwable t) {
            count = 0;
            java.util.Arrays.fill(DATA, 0f);
            return 0;
        }
    }

    // (sizes are passed as refresh(int, int) parameters)

    static float[] data() {
        return DATA;
    }

    static int entries() {
        return count;
    }

    static long lastUploadCount() {
        return lastUploadCount;
    }
}
