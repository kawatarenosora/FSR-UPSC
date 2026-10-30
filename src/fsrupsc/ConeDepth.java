package fsrupsc;

import org.lwjgl.opengl.GL11;

/**
 * Diagnostic depth relax for the cone's triangle pass (marker
 * {@code fsr-upsc-conedepth}, read once; the file must exist before launch).
 *
 * <p>The MemberSubstitution retargets {@code GL11.glDepthFunc} /
 * {@code glDepthMask} inside {@code VisibilityPolygon2$Drawer.renderNew} to
 * the methods below. With the marker set, the triangle pass tests with
 * GL_ALWAYS and writes no depth: if the cone then becomes visible on the
 * ground floor, its absence there was a depth burial -- the level's tiles
 * already wrote a nearer depth and the cone's constant triangle depth failed
 * GL_LEQUAL. The second-floor sightings fit the same reading: a floor's tiles
 * sit at a different depth from the cone's constant, so the test passes where
 * the ground-floor one fails.
 *
 * <p>Mask goes false rather than true while probing: ALWAYS + write would
 * stamp the cone's constant depth over the level's geometry and punch holes
 * in everything drawn after the flush, which would confound the very
 * observation the probe exists for.
 */
public final class ConeDepth {
    private static volatile int gate; // 0 unread, 1 on, -1 off

    private ConeDepth() {}

    private static boolean on() {
        int g = gate;
        if (g == 0) {
            try {
                g = FsrHooks.wantConeDepth() ? 1 : -1;
            } catch (Throwable t) {
                g = -1;
            }
            gate = g;
        }
        return g == 1;
    }

    /** GL_ALWAYS while probing, else the game's own value. */
    public static void func(int original) {
        GL11.glDepthFunc(on() ? 519 : original);
    }

    /** No depth writes while probing, else the game's own value. */
    public static void mask(boolean original) {
        GL11.glDepthMask(!on() && original);
    }
}
