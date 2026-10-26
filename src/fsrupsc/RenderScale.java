package fsrupsc;

import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.textures.TextureFBO;
import zombie.iso.IsoCamera;

/**
 * World-only render-scale gate. World pass renders small,
 * UI/text/screen shader stay full-res.
 *
 * Dynamic (volatile fields + generation), mirroring upstream's design:
 * settings changes via reconfigure() take effect without restart once
 * the frame-path hooks run. Until the resolve lands, values apply at
 * restart because the agent reads settings once at premain.
 */
public final class RenderScale {
    private static volatile String MODE = "fsr1";
    private static volatile float SCALE = 0.58f;
    private static volatile boolean ACTIVE = true;
    private static volatile boolean disabled;
    private static volatile int generation;
    private static volatile float jitterX;
    private static volatile float jitterY;

    /**
     * Shrink generation rendezvous (upstream SUSPEND/RESUME in spirit):
     * the StartFrame drawer bumps it on the render thread; the resolve
     * drawer only runs when it sees a generation it has not consumed.
     * No ambient GL reads: those lie on this driver (stale 640x480).
     */
    private static volatile long shrinkGen;

    public static void noteShrunk() {
        shrinkGen++;
    }

    public static long shrunkGen() {
        return shrinkGen;
    }

    // Back-compat constant for already-compiled callers.
    public static final float SCALE_CONST = 0.58f;

    /**
     * Master switch for the whole world downscale path.
     *
     * On by default: the experiment-time hazard this switch guarded against
     * (the viewport funnel rewriting glViewport for every pass, which traced
     * back to a world-load crash at 14:31) was resolved by the FBO sizing
     * path, which is verified in production since M0-M4. With this off the
     * mod is completely inert and the game runs stock.
     *
     * Priority channels (first hit wins), for explicitly disabling:
     *   env FSRUPSC_DOWNSCALE  >  %USERPROFILE%\Zomboid\fsr-upsc-downscale
     *   >  -Dfsrupsc.downscale
     * A bare -Dfsrupsc.downscale (no value) counts as enable: flag semantics.
     */
    private static final boolean DOWNSCALE = downspec();

    /**
     * Master switch, read the same way as the hook mask: the JAM Launcher
     * silently drops -D properties it does not know, so a property-only
     * switch never reaches the game.
     */
    private static boolean downspec() {
        String v = null;
        try {
            v = System.getenv("FSRUPSC_DOWNSCALE");
        } catch (Throwable ignored) {}
        if (v == null || v.trim().isEmpty()) {
            try {
                java.io.File f = new java.io.File(
                    System.getProperty("user.home"), "Zomboid/fsr-upsc-downscale");
                if (f.isFile()) {
                    byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                    v = new String(b, java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {}
        }
        if (v == null || v.trim().isEmpty()) {
            v = System.getProperty("fsrupsc.downscale"); // absent = default ON
        }
        String t = v == null ? "" : v.trim();
        if (t.isEmpty()) return true;
        if (t.equalsIgnoreCase("off") || t.equalsIgnoreCase("false")
                || t.equalsIgnoreCase("0") || t.equalsIgnoreCase("no")) {
            return false;
        }
        return true;
    }

    public static boolean downscaleEnabled() { return DOWNSCALE; }

    /**
     * Exact world offscreen allocation target, or null to leave the create
     * alone. Replaces the old single permille, which could not express the
     * 1600x900 preset: its width needs 833.6 permille, its height 833.2 --
     * no single integer serves both. Both axes now come from
     * {@link #scaledRect(int,int)}, so allocation, viewport and resolve all
     * read the same numbers (and the old 78 % drift -- buffer 1494x840 vs
     * rect 1488x832 -- disappears with it).
     */
    public static int[] worldFboTargetSize(Object self, int w, int h) {
        try {
            if (!active() || self == null) return null;
            if (self != Core.getInstance().offscreenBuffer) return null;
            if (w <= 0 || h <= 0) return null;
            float s = SCALE;
            if (s <= 0.05f || s >= 0.999f) return null;
            return scaledRect(w, h);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Scale in permille for the world offscreen buffer allocation, or 0
     * when the allocation must be left alone. Applies only to
     * Core.offscreenBuffer so save thumbnails and other MultiTextureFBO2
     * users keep their own size.
     */
    public static int worldFboScalePermille(Object self) {
        try {
            if (!active()) return 0;
            if (self == null) return 0;
            if (self != Core.getInstance().offscreenBuffer) return 0;
            float s = SCALE;
            if (s <= 0.05f || s >= 0.999f) return 0;
            return Math.round(s * 1000f);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String mode() { return MODE; }
    public static boolean active() { return DOWNSCALE && ACTIVE && !disabled; }
    public static float scale() { return SCALE; }
    public static float scaleX() { return SCALE; }
    public static float scaleY() { return SCALE; }

    /** Re-read settings into the live fields. */
    public static void reconfigure() {
        String m = FsrConfig.getUpscaler();
        if (m == null) m = "fsr1";
        MODE = m;
        ACTIVE = !"off".equalsIgnoreCase(m);
        SCALE = computeScale();
        generation++;
    }

    /**
     * Render-size presets, named by the size they produce.
     *
     * <p>A ratio like 0.67 is what produced the odd 1286x724: both the viewport
     * and the resolve target must be whole pixels, so the ratio gets rounded and
     * the exact size lands on a value nobody chose. Naming the size directly
     * removes the rounding step -- the ratio is derived from it instead.
     *
     * <pre>
     *   quality      960x540   1/4 pixels   fastest
     *   performance 1280x720   ~44 %
     *   high        1600x896   ~69 %
     *   native      1920x1080  100 %
     * </pre>
     *
     * <p>Heights snap to a multiple of 16, so 900 becomes 896. A 0.4 %
     * difference is invisible in motion and keeps the target stride clean.
     *
     * <p>"high" exists because FSR's edge-adaptive sharpening cannot invent
     * detail that was never rendered: at 960x540 the 2x upscale stays visibly
     * soft no matter the sharpness setting. 1600x896 keeps 69 % of the pixels.
     *
     * <p>An explicit percentage still overrides, for measurement.
     */
    /**
     * Render-size presets. FidelityFX preset ratios (the fraction of the
     * window that gets rendered), the mc-fsr naming:
     *
     * FidelityFX preset factors (ffx_upscale quality presets), the mc-fsr
     * naming:
     *
     * <pre>
     *   ultra_quality  1/1.285 = 0.778  (FIDELITYFX ULTRA QUALITY)
     *   quality        1/1.444 = 0.693  (FIDELITYFX QUALITY)
     *   balanced       1/1.667 = 0.600  (FIDELITYFX BALANCED)
     *   performance    1/2.0   = 0.500  (FIDELITYFX PERFORMANCE)
     *   native         1.0 = no downscale, RCAS-only
     * </pre>
     *
     * Heights still snap to a multiple of 16 -- see scaledRect().
     */
    private static float computeScale() {
        int pct = FsrConfig.getUpscalerScalePct();
        if (pct >= 10 && pct <= 100) {
            // 83 = the exact 1600x900 preset: rendered ratio 1600/1920, and
            // scaledRect pins the 1080p result to the labelled size instead of
            // letting the 16px height snap turn 900 into 896.
            pinned900 = (pct == 83);
            return pct == 83 ? (1600f / 1920f) : pct / 100.0f;
        }
        pinned900 = false;
        String q = FsrConfig.getUpscalerQuality();
        if (q == null) return 0.694f;
        switch (q) {
            case "ultra_quality": return 0.7782f;  // 1/1.285 FIDELITYFX ULTRA QUALITY
            case "quality":       return 0.6925f;  // 1/1.444 FIDELITYFX QUALITY (default)
            case "balanced":      return 0.6f;     // 1/1.667 FIDELITYFX BALANCED
            case "performance":   return 0.5f;     // 1/2.0   FIDELITYFX PERFORMANCE
            case "native":        return 1.0f;
            // Old saved settings still name these; map them to what they meant.
            case "high":  return 0.777f;
            case "ultra": return 0.777f;
            default: return 0.694f;
        }
    }

    /** Permanent fail-safe: fall back to stock rendering with a reason. */
    public static void disable(String reason) {
        if (!disabled) {
            disabled = true;
            System.out.println("[fsr-upsc] render-scale disabled (" + reason + "); running stock");
            try {
                fsrupsc.FsrLog.log("render-scale disabled (" + reason + "); running stock");
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Console-only re-arm after a session-scoped disable. Clears both the
     * disabled flag and any stale resolve output (world frame textures are
     * destroyed and re-created, so the old output GL name is dead); if the
     * original failure repeats, the same fail-safe stands it down again.
     */
    public static void reEnable() {
        if (!disabled) {
            System.out.println("[fsr-upsc] render-scale is not disabled ("
                + describe() + ")");
            return;
        }
        disabled = false;
        try {
            ResolveOutput.clear();
        } catch (Throwable ignored) {}
        System.out.println("[fsr-upsc] render-scale re-enabled (console); "
            + describe());
        try {
            fsrupsc.FsrLog.log("render-scale re-enabled (console); " + describe());
        } catch (Throwable ignored) {}
    }

    public static String describe() {
        return "mode=" + MODE + " scale=" + SCALE + " active=" + active() + " gen=" + generation;
    }

    /**
     * Viewport funnel: game calls TextureDraw.glViewport with a full-size
     * rect; while the world FBO is bound we substitute the scaled rect.
     * Returns true when out[] was filled with the replacement.
     */
    public static boolean requestedViewport(int x, int y, int w, int h, int[] out) {
        if (!active()) return false;
        float s = SCALE;
        if (s >= 0.999f) return false;
        // The cone's composite is a screen-space effect: its shader is told
        // screenSize = 1920x1080 and fills glViewport(screenOrigin, screenSize).
        // Shrinking only that viewport puts the cone in the top-left 1600x896
        // while the shader still maps the full screen, which is the offset.
        if (CONE_VP.get() && isScreenRect(x, y, w, h)) return false;
        if (!isScreenRect(x, y, w, h)) return false;
        // Upstream gates this on the same world-pass window as the camera
        // override. Without it the funnel also shrinks draws that are not
        // the world pass.
        if (!worldFboBound()) return false;
        // The outline's endFrame seams serve a pass whose mask is drawn in
        // region coordinates (same camera basis as the world pass), so NONE
        // of its four viewports may be squeezed: the line-234 blit must map
        // fboA 1:1 into the world buffer or the outline lands twice-scaled.
        if (OUTLINE_VP_RAW && endFrameRawVp()) return false;
        // scaledRect, not w * s: the resolve reads the size this produces, and
        // rounding twice is how a 1-pixel disagreement turns into a shifted
        // picture instead of an error.
        int[] sz = scaledRect(w, h);
        out[0] = x;
        out[1] = y;
        out[2] = sz[0];
        out[3] = sz[1];
        return true;
    }

    /**
     * True while this glViewport request is ModelOutlines.endFrame's
     * own seam (any line inside endFrame). endFrame re-uses one local
     * rect (IsoCamera screenLeft/Top/Width/Height) for all four of its
     * viewports, so the funnel's per-line choice decides nothing about
     * argument validity -- it decides how fboA's content is mapped.
     *
     * Confirmed fix (2026-10-26): the world-iso model pass (line 215)
     * renders the outline mask at the same camera basis the world pass
     * rendered the tiles at, i.e. in region coordinates, not native
     * ones -- the user symptom was exactly one SCALE shrink (0.775x =
     * 1488/1920) anchored at the screen corner. Squeezing the line-234
     * blit therefore applied a second, wrong squeeze; with every
     * endFrame viewport raw the blit maps fboA 1:1 and the outline
     * aligns with the world content.
     *
     * Stack-walk: the first non-helper, non-agent frame above the
     * (inlined) glViewport layer is the seam caller. Kept as a
     * package method so the funnel test is one call.
     */
    static boolean endFrameRawVp() {
        try {
            StackTraceElement[] es = Thread.currentThread().getStackTrace();
            for (int i = 1; i < es.length; i++) {
                String cn = es[i].getClassName();
                String mn = es[i].getMethodName();
                if (mn.equals("getStackTrace") || mn.equals("glViewport")
                        || cn.startsWith("fsrupsc.")
                        || cn.startsWith("net.bytebuddy.")) continue;
                return cn.equals("zombie.core.skinnedmodel.model.ModelOutlines")
                        && mn.equals("endFrame");
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isScreenRect(int x, int y, int w, int h) {
        try {
            if (x == 0 && y == 0 && w == Core.width && h == Core.height) return true;
            int pi = IsoCamera.frameState.playerIndex;
            if (x == IsoCamera.getScreenLeft(pi) && y == IsoCamera.getScreenTop(pi)
                    && w == IsoCamera.getScreenWidth(pi) && h == IsoCamera.getScreenHeight(pi)) {
                return true;
            }
            } catch (Throwable ignored) {}
        return false;
    }

    // Note: the view cone is NOT part of this funnel.
    //
    // IsoWorld.DrawPlayerCone2() does glViewport(0, 0, cone.getWidth(),
    // cone.getHeight()) where the cone texture is always Core/4 (480x270 at
    // 1920x1080). That rectangle is the cone lightmap FBO's own resolution, not
    // a destination inside the world buffer: the cone is baked into its own FBO
    // while it is bound. Scaling it to match the world rect was tried and
    // over-magnified the cone, because the lightmap is then applied at a
    // resolution it was not rendered at. Left stock deliberately.

    /** Code default: ALL ModelOutlines.endFrame viewports stay raw
     *  (fix on, confirmed 2026-10-26). fsr2OutlineFix(0) reverts to
     *  squeezing the seams like the world pass. */
    private static volatile boolean OUTLINE_VP_RAW = true;

    /** Console switch state (fsr2OutlineFix). */
    public static void setOutlineVpRaw(boolean on) {
        OUTLINE_VP_RAW = on;
        fsrupsc.FsrLog.log("outline vpRaw=" + on);
    }

    /** True when the currently bound FBO is a world offscreen buffer. */
    public static boolean worldFboBound() {
        try {
            int cur = TextureFBO.getCurrentID();
            if (cur == 0) return false;
            Core core = Core.getInstance();
            int n = Math.max(1, IsoPlayer.numPlayers);
            for (int i = 0; i < n; i++) {
                TextureFBO off;
                try {
                    off = core.getOffscreenBuffer(i);
                } catch (Throwable ignored) {
                    continue;
                }
                if (off != null && off.getBufferId() == cur) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** One-time diagnostic: why a viewport was (not) substituted. */
    public static String debugViewport(int x, int y, int w, int h) {
        try {
            int cur = -1;
            try {
                cur = TextureFBO.getCurrentID();
            } catch (Throwable ignored) {}
            StringBuilder off = new StringBuilder("[");
            try {
                Core core = Core.getInstance();
                int n = Math.max(1, IsoPlayer.numPlayers);
                for (int i = 0; i < n; i++) {
                    try {
                        TextureFBO o = core.getOffscreenBuffer(i);
                        if (o != null) off.append(i).append(":").append(o.getBufferId()).append(" ");
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            off.append("]");
            return "vp xywh=" + x + "," + y + "," + w + "," + h
                + " cur=" + cur + " off=" + off
                + " screen=" + Core.width + "x" + Core.height
                + " active=" + active() + " scale=" + SCALE;
        } catch (Throwable t) {
            return "vp debug failed: " + t;
        }
    }
    /**
     * The one place the shrunk world size is decided.
     *
     * <p>The viewport funnel and the resolve target both read this. Computing it
     * twice let them drift apart, and a mismatch there shows up as a displaced
     * image rather than an error, so it is deliberately a single function.
     *
     * <p>Sizes are snapped down to a multiple of 16. The render targets are
     * pitch-aligned, so an odd height buys nothing and costs a stride; 900
     * becomes 896, a 0.4 % difference nobody will ever see.
     */
    public static int[] scaledRect(int fullW, int fullH) {
        float s = SCALE;
        int w = Math.round(fullW * s);
        int h = Math.round(fullH * s);
        // Snap only when actually downscaling. At 1.0 the "snapped" size would be
        // smaller than the real one (1080 -> 1072), which is wrong for native.
        if (s < 0.999f) {
            if (pinned900 && fullW == 1920 && fullH == 1080) {
                // The explicit 83 % option: the exact 1600x900 label is the
                // contract, not a rounded ratio.
                return new int[] { 1600, 900 };
            }
            w = snap(w);
            h = snap(h);
        }
        return new int[] { Math.max(1, w), Math.max(1, h) };
    }

    /** True when the explicit 83 % option pinned scaledRect to 1600x900. */
    private static volatile boolean pinned900;

    /**
     * Round down to a multiple of 16, unless that would cost more than 1 % of
     * the size -- for very small targets the step is too coarse.
     */
    

    /**
     * Scale a pixel value that a render-thread draw uses as a screen size.
     *
     * <p>For the view-cone blur's display size. Its screenSize / displayOrigin
     * / viewport all go through {@code IsoCamera} and shrink with the camera
     * override; its displaySize does not, because it reads the framebuffer's
     * allocation instead. Scaling it here puts the two back in agreement --
     * upstream's {@code RenderScale.viewPx()}, applied to the cone only.
     *
     * <p>Untouched outside a scaled world pass.
     */
    /**
 * Remove the rendered vision cone.
 *
 * <p>Off unless the {@code fsrupsc-nocone} marker says otherwise.
 *
 * <p>The cone is the one part of the world pass whose scale basis does not
 * follow the camera: its texture is always Core/4 and its blur shader's
 * displaySize is the framebuffer allocation, while the world is framed from
 * the screen size times the zoom. That mismatch is why the displacement
 * tracked the zoom multiplier -- change the zoom and the error cancels, set it
 * back and it returns. Suppressing the cone removes the inconsistent basis
 * rather than trying to make four of them agree.
 *
 * <p>What it costs: the cone's rendered lighting overlay disappears. What it
 * does not cost: the visibility data. The polygon's coverage lives in
 * {@code zombie.vispoly.VisibilityPolygon2}, untouched here, so what the player
 * sees and what zombies can path to are unchanged.
 */
public static boolean coneSuppressed() {
    return markerOn("fsrupsc-nocone");
}

/**
 * Suppress {@code IsoWorld.DrawPlayerCone} (the lightmap bake) on its own.
 *
 * <p>Split from {@link #cone2Suppressed()} because the two draws are separate
 * operations and it was never established which one puts the displaced cone on
 * screen. {@code fsrupsc-nocone} still suppresses both.
 */
public static boolean cone1Suppressed() {
    return markerOn("fsrupsc-nocone") || markerOn("fsrupsc-nocone1");
}

/**
 * Suppress {@code IsoWorld.DrawPlayerCone2} (the on-screen quad) on its own.
 *
 * <p>This is the draw sized by {@code Core.getOffscreenWidth/Height}, the one
 * whose basis is the camera's zoomed world extent rather than the viewport.
 */
public static boolean cone2Suppressed() {
    return markerOn("fsrupsc-nocone") || markerOn("fsrupsc-nocone2");
}

/**
 * The scale to apply to view-cone values, or 1 for none.
 *
 * <p>Deliberately <em>not</em> {@link #scaledView()}: that asks "am I inside
 * the world pass right now", and the cone blur runs outside it, so it would
 * always answer false and the substitution would silently do nothing. What
 * matters here is only whether render scaling is in effect at all.
 */
public static float coneScale() {
    try {
        return (active() && SCALE < 0.999f) ? SCALE : 1.0f;
    } catch (Throwable t) {
        return 1.0f;
    }
}

/**
 * True while the view cone is setting up its screen composite.
 *
 * <p>Opt-in via {@code fsrupsc-coneviewport}. The cone's blur shader is fed
 * {@code screenSize = IsoCamera.getScreenWidth/Height} (native 1920x1080) and
 * writes a full-screen quad into {@code glViewport(screenOrigin, screenSize)}.
 * The viewport funnel scales that call like any other screen rect, so the cone
 * lands in the top-left 1600x896 of the frame while the shader still believes it
 * is covering 1920x1080.
 *
 * <p>While this flag is set, {@link #requestedViewport} lets the cone's own
 * screen rect through unscaled. Nothing else changes: the world still draws at
 * 1600x896 and still upscales.
 */
private static final java.util.concurrent.atomic.AtomicBoolean CONE_VP =
        new java.util.concurrent.atomic.AtomicBoolean();

public static boolean coneViewportPassThrough() { return CONE_VP.get(); }

public static void setConeViewportPassThrough(boolean on) { CONE_VP.set(on); }

public static float viewPx(float screenPixels) {
        return scaledView() ? screenPixels * SCALE : screenPixels;
    }

    /** viewPx for a vertical size. */
    public static float viewPxY(float screenPixels) {
        return scaledView() ? screenPixels * SCALE : screenPixels;
    }

    private static int snap(int v) {
        int down = (v / 16) * 16;
        if (down < 16) return v;
        if ((v - down) * 100 > v) return v; // >1 % lost
        return down;
    }

    public static void setJitter(float jx, float jy) {
        jitterX = jx;
        jitterY = jy;
    }

    public static float frameJitterX() { return jitterX; }
    public static float frameJitterY() { return jitterY; }

    // -------------------------------------------------------------------------
    // Camera frame scaling.
    // -------------------------------------------------------------------------

    /**
     * The size the camera should frame while the world pass runs, or -1 to
     * report the real one.
     *
     * <p>Only during the world pass. The composite asks the same accessors for
     * its destination rectangle and must get the real screen size, so this
     * keys off the thread: the render thread is named "main", the composite
     * runs on "MainThread".
     *
     * <p>Off unless {@code fsrupsc-camera-scale} is "on". It changes what the
     * game believes its resolution is during rendering, which is a wide blast
     * radius, so it stays an explicit opt-in until it has been seen correct.
     */
  /** True while the aim reticle is being drawn; see {@link #setReticleHold}. */
  private static final java.util.concurrent.atomic.AtomicBoolean RETICLE_HOLD =
          new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * Force the scaled screen size for the aim reticle, outside the world pass.
   *
   * <p>{@code MultiTextureFBO2.renderCursor} draws {@code IsoReticle}, the
   * cone-shaped aiming overlay, while the world framebuffer is still bound and its
   * viewport is still 1600x896 -- but the reticle sizes itself from
   * {@code IsoCamera.getScreenWidth/Height}. Outside the world pass
   * {@link #scaledView()} is false, so the override reports 1920x1080 and the
   * overlay is laid out for a frame 20% wider than the one it is drawn into.
   *
   * <p>Profiling settled this: {@code renderCursor} is the only live caller of
   * {@code IsoReticle#render}, and the reticle is a screen-space overlay that is
   * not part of the world image -- with the composite texture swap disabled the
   * ground moves to 83% in the corner while the cone stays put, which is what put
   * it on this path after a dozen wrong ones.
   */
  public static void setReticleHold(boolean on) { RETICLE_HOLD.set(on); }

public static int cameraScreenWidth() {
          if (!CAMERA_SCALE && !RETICLE_HOLD.get()) return -1;
          if (!scaledView() && !RETICLE_HOLD.get()) return -1;
        int[] s = scaledRect(Core.width, Core.height);
          return s[0];
      }

    public static int cameraScreenHeight() {
        if (!CAMERA_SCALE && !RETICLE_HOLD.get()) return -1;
        if (!scaledView() && !RETICLE_HOLD.get()) return -1;
        int[] s = scaledRect(Core.width, Core.height);
        return s[1];
    }

    // -------------------------------------------------------------------------
    // The world-pass window.
    // -------------------------------------------------------------------------

    /** PZ's internal id of the world offscreen buffer, captured while bound. */
    private static volatile int worldFboId = -1;
    private static volatile boolean worldPass;

    /** The GL buffer id of the world offscreen buffer (Trace compare use). */
    public static int worldFboBufferId() { return worldFboId; }

    /**
     * True while the render thread is inside a scaled world pass.
     *
     * <p>All three conditions are load-bearing, and dropping any one of them
     * produced the double-rendered frame:
     *
     * <ul>
     *   <li><b>worldPass</b> -- the bracket opened by the world's startDrawing
     *       and closed by its endDrawing. Without it the screen size stays
     *       shrunken for draws that happen outside the world pass.
     *   <li><b>FBO identity</b> -- {@code TextureFBO.lastID == worldFboId}.
     *       Furniture tiles, particles and the UI draw with other buffers
     *       bound; reporting the shrunken size to those is what made them render
     *       at the pre-scale size on top of the correctly scaled ground.
     *   <li><b>render thread</b> -- the composite runs on the game thread and
     *       reads these accessors for its destination rectangle.
     * </ul>
     *
     * <p>Mirrors upstream PZ_Optimization's {@code RenderScale.scaledView()}.
     */
    public static boolean scaledView() {
        return worldPass && active() && fsrupsc.FsrHooks.isRenderThread()
                && worldFboBoundNow();
    }

    private static boolean worldFboBoundNow() {
        int id = worldFboId;
        if (id == -1) return false;
        try {
            return zombie.core.textures.TextureFBO.lastID == id;
        } catch (Throwable t) {
            return false;
        }
    }

    /** World pass opened (startDrawing on the world buffer). */
    public static void enterWorldPass(Object fbo) {
        if (fbo == null) return;
        try {
            if (!isWorldFbo(fbo)) return;
            int id = ((zombie.core.textures.TextureFBO) fbo).getBufferId();
            worldFboId = id;
            worldPass = true;
            if (CAMERA_SCALE && worldPassLogged < 3) {
                worldPassLogged++;
                fsrupsc.FsrHooks.emit("worldPass enter id=" + id
                    + " cameraScreen=" + scaledRect(Core.width, Core.height)[0]
                    + "x" + scaledRect(Core.width, Core.height)[1]);
            }
        } catch (Throwable ignored) {}
    }

    /** World pass closed (endDrawing on the world buffer). */
    public static void exitWorldPass(Object fbo) {
        if (fbo == null) return;
        try {
            if (fbo != worldFboRef) return;
            worldPass = false;
        } catch (Throwable ignored) {}
    }

    private static volatile int worldPassLogged;

    private static boolean isWorldFbo(Object fbo) {
        try {
            Core core = Core.getInstance();
            int n = Math.max(1, zombie.characters.IsoPlayer.numPlayers);
            for (int i = 0; i < n; i++) {
                zombie.core.textures.TextureFBO off = core.getOffscreenBuffer(i);
                if (off != null && off == fbo) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** The world offscreen buffer object, or null. Used for identity checks. */
    private static volatile Object worldFboRef;

    public static void bindWorldFboRef(Object fbo) {
        worldFboRef = fbo;
    }

    private static final boolean CAMERA_SCALE = markerOn("fsrupsc-camera-scale");

    private static boolean markerOn(String name) {
        try {
            java.io.File f = new java.io.File(
                System.getProperty("user.home"), "Zomboid/" + name);
            if (!f.isFile()) return false;
            String v = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).trim();
            return v.equalsIgnoreCase("on") || v.equals("1") || v.equals("true");
        } catch (Throwable t) {
            return false;
        }
    }

    public static void applyWorldViewport(int w, int h) { /* TODO (N2 direct-color path) */ }
    public static void afterStartFrame(int player) { /* reserved */ }
    public static void afterModelDraw() { /* reserved */ }
    public static void afterEndFrame() { /* reserved */ }

    private RenderScale() {}
}
