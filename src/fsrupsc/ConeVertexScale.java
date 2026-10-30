package fsrupsc;

/**
 * Scale the view cone's screen-space vertex coordinates.
 *
 * <p>Stands in for {@code PlayerCamera.XToScreenExact} / {@code YToScreenExact}
 * <em>as called from the cone's triangle path only</em>.
 *
 * <p>42.22 bytecode settled the live path: renderMain -&gt; Drawer.render -&gt;
 * renderNew -&gt; renderPolygons -&gt; triangulatePolygons -&gt;
 * renderOneTriangle. The vertex coordinate is what decides the cone's position:
 *
 * <pre>
 *   renderOneTriangle(...)          // six calls, results into slots 10..15
 *     pc.XToScreenExact(x, y, pz, 0)    -&gt; native 1920x1080 space
 *   PolygonData.addVertex(...)
 *     pc.XToScreenExact(x, y, z, 0)     -&gt; native 1920x1080 space
 *       ... pushed into the vertex FloatBuffer ...
 * </pre>
 *
 * Both conversions reduce to {@code IsoUtils.XToScreen / YToScreen minus the
 * camera's offX / offY}, i.e. coordinates whose zero is the camera's top-left.
 * Multiplying by the funnel scale puts them into the same space the world pass
 * was drawn into, so the cone stays where the geometry says it should.
 *
 * <p>Scoped by caller, not by method: {@code XToScreenExact} has 18 call sites
 * and the other 16 belong to aiming, world markers, pathfinding debug lines and
 * the inverse transforms. Only the two inside the cone's polygon builder are
 * substituted, so nothing else in the game sees a scaled coordinate.
 */
public final class ConeVertexScale {

    private ConeVertexScale() {}

    /** Marker-gated; see RenderScale.coneScale(). */
    public static float scale() {
        try {
            return RenderScale.coneScale();
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    private static volatile int probeGate;            // 0 unread, 1 on, -1 off
    private static volatile long nextProbeX, nextProbeY, nextProbeM;
    private static java.nio.FloatBuffer mvBuf, pjBuf;

    private static boolean probeOn() {
        int g = probeGate;
        if (g == 0) {
            try {
                g = FsrHooks.wantConeProbe() ? 1 : -1;
            } catch (Throwable t) {
                g = -1;
            }
            probeGate = g;
        }
        return g == 1;
    }

    /**
     * Once per 2 s: read the modelview / projection Y rows and translation
     * terms active at the vertex-conversion point. The cone's vertices flow
     * through whatever matrix the deferred flush applies, and the world's
     * sprites flow through the camera's own matrix -- logging the Y row of
     * both shows directly whether the two vertical mappings diverge, and by
     * how much at each zoom level. GL queried only here, marker-gated, render
     * thread, once per 2 s.
     */
    private static void matrixProbe() {
        try {
            long now = System.nanoTime();
            if (now < nextProbeM) return;
            nextProbeM = now + 2_000_000_000L;
            org.lwjgl.opengl.GL11 gl = null;
            if (mvBuf == null) {
                mvBuf = org.lwjgl.BufferUtils.createFloatBuffer(16);
                pjBuf = org.lwjgl.BufferUtils.createFloatBuffer(16);
            }
            org.lwjgl.opengl.GL11.glGetFloatv(
                    org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX, mvBuf);
            org.lwjgl.opengl.GL11.glGetFloatv(
                    org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX, pjBuf);
            float m1 = mvBuf.get(1), m5 = mvBuf.get(5), m9 = mvBuf.get(9),
                    m13 = mvBuf.get(13), m12 = mvBuf.get(12), m14 = mvBuf.get(14);
            float p1 = pjBuf.get(1), p5 = pjBuf.get(5), p9 = pjBuf.get(9),
                    p13 = pjBuf.get(13);
            float mx = mvBuf.get(0);
            FsrHooks.emit("cone mtx: mvY=" + m1 + "," + m5 + "," + m9 + "," + m13
                    + " mvT=" + m12 + "," + m13 + "," + m14
                    + " mvX=" + mx + "," + mvBuf.get(4) + "," + mvBuf.get(8)
                    + " pjY=" + p1 + "," + p5 + "," + p9 + "," + p13);
        } catch (Throwable ignored) {}
    }

    /**
     * Diagnostic behind {@code fsr-upsc-coneprobe}: logs the cone's native and
     * scaled coordinates plus the camera basis, one line per axis per 2 s.
     * {@code z} here is {@code Drawer.pz} (the camera's floor), so the
     * player-centred apex can be reconstructed exactly the way the cone's own
     * conversion does it:
     *
     * <pre>
     *   apexX_native = (cx - cy) * 64         - offX
     *   apexY_native = (cx + cy) * 32 - 192*z - offY
     * </pre>
     *
     * If the scaled apex tracks the viewport centre the vertex chain is right
     * and the residual happens downstream (flush/viewport position); if it does
     * not, the residual lives in this substitution and the numbers give the
     * term to correct.
     */
    private static void probeLine(zombie.iso.PlayerCamera camera, float nativeV,
                                  float s, char axis, float worldX, float worldY,
                                  float z, int pz) {
        try {
            long now = System.nanoTime();
            if (axis == 'x' ? now < nextProbeX : now < nextProbeY) return;
            if (axis == 'x') nextProbeX = now + 2_000_000_000L;
            else nextProbeY = now + 2_000_000_000L;
            zombie.iso.IsoCamera.FrameState fs = zombie.iso.IsoCamera.frameState;
            float cx = fs.camCharacterX, cy = fs.camCharacterY;
            float ax = (cx - cy) * 64f - camera.getOffX();
            float ay = (cx + cy) * 32f - 192f * z - camera.getOffY();
            FsrHooks.emit("cone vtx " + axis + ": cam=" + cx + "," + cy
                    + " z=" + z + " off=" + camera.getOffX() + "," + camera.getOffY()
                    + " apexN=" + ax + "," + ay
                    + " apexS=" + (ax * s) + "," + (ay * s)
                    + " vN=" + nativeV + " vS=" + (nativeV * s));
        } catch (Throwable ignored) {}
    }

    /**
     * Stands in for {@code XToScreenExact(float, float, float, int)}.
     *
     * <p>The receiver comes first and is ignored. {@code XToScreenExact} is an
     * instance method on {@code PlayerCamera}, and a MemberSubstitution carries
     * the receiver across as the leading argument. A static replacement that
     * omitted it failed to resolve:
     *
     * <pre>
     *   Cannot invoke ConeVertexScale.x(float,float,float,int,float)
     *     on parameter 0 of type class zombie.iso.PlayerCamera
     * </pre>
     *
     * <p>The remaining four parameters match the original, and the native result
     * comes last so the body can scale it.
     *
     * <p>The scale is about the frame origin, so it applies to the raw
     * coordinate rather than to a difference from the screen centre.
     */
    public static float x(zombie.iso.PlayerCamera camera,
                           float worldX, float worldY, float z, int pz) {
        float nativeV = camera.XToScreenExact(worldX, worldY, z, pz);
        float s = scale();
        if (probeOn()) probeLine(camera, nativeV, s, 'x', worldX, worldY, z, pz);
        return nativeV * s;
    }

    /** Stands in for {@code YToScreenExact(float, float, float, int)}. */
    public static float y(zombie.iso.PlayerCamera camera,
                           float worldX, float worldY, float z, int pz) {
        float nativeV = camera.YToScreenExact(worldX, worldY, z, pz);
        float s = scale();
        if (probeOn()) {
            probeLine(camera, nativeV, s, 'y', worldX, worldY, z, pz);
            matrixProbe();
        }
        // Constant native-vertical-shift correction (the cone's IsoUtils Y and
        // Vertical correction, settled empirically. The cone's IsoUtils Y and
        // the CPU-transformed world sprite pipeline disagree by an offset that
        // is proportional to the height the downscale removes, times the
        // camera zoom: user dial at s = 0.8333 (1600x900) was 40 at q=0.25,
        // ~180 at q=1, 450 at q=2.5, and 1080*(1-0.8333)*q = 180*q hits all
        // three. At s = 1 (no downscale) the law gives zero correction, i.e.
        // the vanilla chain is intact and nothing should be added -- with a
        // fixed C the correction leaked into the native view as a downward
        // shift, which matched what the user saw. Smaller render resolutions
        // dial up, matching the growing upward offset there. C stays
        // live-tunable via coneyadd. C itself is the native display height in
        // px, read at runtime (1080 on the user's screen): the offset is the
        // vertical extent the downscale removes, so on 4K/2K/HD monitors the
        // same law should hold with that monitor's height.
        float zq = camera.zoom;
        float nativeH;
        try { nativeH = zombie.core.Core.height; }
        catch (Throwable t) { nativeH = 1080f; }
        float add = FsrHooks.coneYConst(nativeH) * (1f - s) * zq;
        if (probeOn() && add != 0f) {
            FsrHooks.emit("cone yadd: q=" + zq + " s=" + s
                    + " C=" + FsrHooks.coneYConst(nativeH) + " add=" + add);
        }
        return nativeV * s + add;
    }
}
