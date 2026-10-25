package fsrupsc;

/**
 * Synthetic screen-space ortho view-projection pair for the PZ world pass (M2).
 *
 * <p>PZ's world sprites are drawn with CPU-transformed quads, so the game's
 * PlayerCamera matrices do not represent the world render mapping. The actual
 * mapping is affine and known: a native-screen pixel {@code u} (IsoUtils
 * screen space, camera offset excluded) lands at
 *
 * <pre>   ndc(u) = 2 * (u - cameraOff) / nativeSize - 1</pre>
 *
 * because the shrink is viewport-based (renderPx = (u - off) * k with
 * k = inW / nativeW collapses out of the ndc math).
 *
 * <p>Matrices are hand-built column-major float[16] (z column zeroed:
 * reprojection depth is irrelevant while uUseDepth=0) and uploaded with
 * glUniformMatrix4fv(transpose=false).
 *
 * <p>Convention matches fsr-reloaded: MVP -&gt; MV_GEN stores
 * {@code mv = (prevUv - uv) * renderSize}; the Java side feeds
 * {@code uCameraDelta = (offCurr - offPrev)} which maps
 * {@code world = u - offCurr} onto the previous frame's device space
 * ({@code prevVP(u) = ndc(u - offPrev)}).
 *
 * <p>If in-game verification (debug visualizer mode 3) shows the pan direction
 * reversed, flip {@link #OFFSIGN}.
 */
public final class IsoViewProjection {

    /** +1 or -1: empirical sign of "device screen pos = IsoUtilsPx - cameraOff". */
    public static int OFFSIGN = +1;

    /** Current-frame VP (jitter applied as clip translation). */
    public static final float[] CURR = new float[16];
    /** Inverse of CURRENT (maps ndc back to frame-local px {@code u - offCurr}). */
    public static final float[] INVCURR = new float[16];
    /** Previous-frame VP (the one MV_GEN reprojects with, after uCameraDelta). */
    public static final float[] PREV = new float[16];

    private IsoViewProjection() {}

    /**
     * Fill the three matrices. Jitter is in render pixels; it enters as a
     * clip-space translation, matching the mod's
     * {@code currVP.translate(jx * 2/renderW, jy * 2/renderH, 0)}.
     */
    public static void compose(
            float curOffX, float curOffY,
            float prevOffX, float prevOffY,
            float curJx, float curJy, float prevJx, float prevJy,
            int inW, int inH, int nativeW, int nativeH) {
        if (nativeW < 16 || nativeH < 16 || inW < 16 || inH < 16) return;
        float ax = 2f / nativeW;
        float ay = 2f / nativeH;
        float jtx = 2f * curJx / inW;
        float jty = 2f * curJy / inH;
        float pjtx = 2f * prevJx / inW;
        float pjty = 2f * prevJy / inH;

        // forward: ndc = ax*(u - OFFSIGN*offCur) - 1 (+ jitter clip shift)
        fillForward(CURR, ax, ay, OFFSIGN * curOffX, OFFSIGN * curOffY, jtx, jty);
        fillForward(PREV, ax, ay, OFFSIGN * prevOffX, OFFSIGN * prevOffY, pjtx, pjty);

        // inverse: u - OFFSIGN*offCur = (ndc - m30) / a  ->  world = u - OFFSIGN*offCur
        fillInverse(INVCURR, ax, ay, OFFSIGN * curOffX, OFFSIGN * curOffY, jtx, jty);
    }

    /** ndc(u - o) = a*u + (t) where t = -a*o - 1 (+ clip jitter). */
    private static void fillForward(float[] m, float ax, float ay,
                                    float sx, float sy, float jtx, float jty) {
        java.util.Arrays.fill(m, 0f);
        m[0] = ax;
        m[5] = ay;
        m[12] = -ax * sx - 1f + jtx;
        m[13] = -ay * sy - 1f + jty;
        m[15] = 1f;
    }

    /** world = (ndc - t)/a; t = -a*sx - 1 + jtx. */
    private static void fillInverse(float[] m, float ax, float ay,
                                    float sx, float sy, float jtx, float jty) {
        java.util.Arrays.fill(m, 0f);
        m[0] = 1f / ax;
        m[5] = 1f / ay;
        m[12] = -((jtx - ax * sx - 1f)) / ax;
        m[13] = -((jty - ay * sy - 1f)) / ay;
        m[15] = 1f;
    }
}
