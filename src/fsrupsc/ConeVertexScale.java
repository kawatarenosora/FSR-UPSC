package fsrupsc;

/**
 * Scale the view cone's screen-space vertex coordinates.
 *
 * <p>Stands in for {@code PlayerCamera.XToScreenExact} / {@code YToScreenExact}
 * <em>as called from the cone's polygon builder only</em>.
 *
 * <p>Why this and not the cone shader. Reading the bytecode settles which code
 * path draws the cone:
 *
 * <pre>
 *   VisibilityPolygon2.render()
 *     if (DebugOptions.useNewVisibility)     // false on this install
 *       renderNew() -&gt; renderToScreen()     // shader + full-screen quad
 *     else
 *       renderOld()                            // &lt;-- the one that runs
 * </pre>
 *
 * {@code renderOld} never calls {@code renderToScreen}: it builds triangles and
 * hands them straight to {@code VBORenderer}. So the blur shader's uniforms
 * ({@code screenSize}, {@code displaySize}, {@code texSize}) do not exist on this
 * path, and neither does any viewport to intercept. Every hook aimed at
 * {@code renderToScreen} -- the displaySize rescale, the uniform trace, the
 * viewport pass-through -- was silently dead for exactly this reason.
 *
 * <p>What does decide the cone's position is the vertex coordinate:
 *
 * <pre>
 *   renderOneTriangle(...)          // six calls, results into slots 10..15
 *     pc.XToScreenExact(x, y, pz, 0)    -&gt; native 1920x1080 space
 *   PolygonData.addVertex(...)
 *     pc.XToScreenExact(x, y, z, 0)     -&gt; native 1920x1080 space
 *       ... pushed into the vertex FloatBuffer ...
 *   VBORenderer.flush()               // drawn into glViewport(0, 0, 1600, 896)
 * </pre>
 *
 * The vertices are in native screen coordinates while the viewport is the
 * shrunken one, so the cone is pushed off-centre. That is the displacement, and
 * it is zoom-dependent because {@code getOffX()} carries the camera offset.
 *
 * <p>Multiplying here puts the vertices into the same space the viewport is in,
 * so the cone lands where the geometry says it should.
 *
 * <p>Scoped by caller, not by method: {@code XToScreenExact} has 18 call sites
 * and the other 16 belong to aiming, world markers, pathfinding debug lines and
 * the inverse transforms. Only the two inside the cone's polygon builder are
 * substituted, so nothing else in the game sees a scaled coordinate.
 */
public final class ConeVertexScale {

    private ConeVertexScale() {}

    /** Marker-gated; see RenderScale.coneVertexScale(). */
    public static float scale() {
        try {
            return RenderScale.coneScale();
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    /**
     * Stands in for {@code Core.getOffscreenWidth(int)} inside
     * {@code IsoWorld.DrawPlayerCone2} only.
     *
     * <p>This is the overlay the player actually sees, and its size is the whole
     * problem:
     *
     * <pre>
     *   SpriteRenderer.render(coneTex,
     *       0, Core.getOffscreenHeight(pi),
     *       Core.getOffscreenWidth(pi), -Core.getOffscreenHeight(pi),
     *       1, 1, 1, 1, null)
     * </pre>
     *
     * <p>{@code getOffscreenWidth} is {@code IsoCamera.getScreenWidth *
     * getDisplayZoom * tileScale/2}, i.e. the camera's zoomed world extent. It is
     * neither {@code Core.width} (1920) nor the viewport (1600), so the quad is
     * laid out against a third size and lands off-centre. Zoom changes it, which
     * is why the displacement appeared only at the game's own starting zoom and
     * normalised when the zoom was touched.
     *
     * <p>Scaling it here matches the quad to the viewport the world was drawn
     * into. Scoped to this one method: the accessor has 65 call sites covering
     * culling, input clamping, UI layout and weather, and touching the shared
     * return value would break all of them.
     *
     * <p>The receiver is kept and passed through. MemberSubstitution carries it
     * across as the leading argument, and omitting it fails to resolve with
     * "Cannot invoke ... on parameter 0 of type class zombie.core.Core".
     */
    public static int quadWidth(zombie.core.Core core, int playerIndex) {
        // Scale the SCREEN size, not the offscreen size.
        //
        // Core.getOffscreenWidth is the camera's zoomed world extent
        // (IsoCamera.getScreenWidth * zoom * tileScale/2), which is 2048 here.
        // Scaling it by 0.833 gives 1706, and that was wrong: the cone has to match
        // the viewport the world was drawn into, which is 1600. The screen size
        // times the scale gives exactly that:
        //
        //   1920 * 0.8333 = 1600   correct
        //   2048 * 0.8333 = 1706   off by 106 px, which is the displacement
        //
        // The cone quad is laid out in screen pixels (y = offH, h = -offH is a
        // Y-up screen-space rectangle), so its width and height must be the
        // screen rectangle the world pass actually filled.
        int[] sz = RenderScale.scaledRect(core.width, core.height);
        return sz[0];
    }

    /** Stands in for {@code Core.getOffscreenHeight(int)} in the same method. */
    public static int quadHeight(zombie.core.Core core, int playerIndex) {
        int[] sz = RenderScale.scaledRect(core.width, core.height);
        return sz[1];
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
        return camera.XToScreenExact(worldX, worldY, z, pz) * scale();
    }

    /** Stands in for {@code YToScreenExact(float, float, float, int)}. */
    public static float y(zombie.iso.PlayerCamera camera,
                           float worldX, float worldY, float z, int pz) {
        return camera.YToScreenExact(worldX, worldY, z, pz) * scale();
    }
}