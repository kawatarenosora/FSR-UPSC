package fsrupsc;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * S-1..S-3 spike: software frame generation on the present path.
 *
 * Hook point = org.lwjglx.opengl.Display#swapBuffers() (render thread): the
 * single GLFW swap PZ presents every frame through. In test mode each real
 * present is preceded by an inserted present, done hook-side so PZ's loop
 * renders nothing extra:
 *
 * <pre>
 *   1. copy the pending frame N (back buffer) into tex[cur]
 *   2. draw the inserted frame:
 *        mode 1: solid magenta (proof the extra present lands)
 *        mode 2: replay of tex[cur] (duplicate; pure cadence test)
 *        mode 3: interpolation of tex[prev] and tex[cur] -- per-pixel when
 *        the fsr2/fsr3 compute pass provides a usable MV field
 *        (FgPresent#fgMv*), global camera shift otherwise
 *      (mode 3 falls back to a duplicate while still / zoomChanged /
 *      resetRequested / no valid previous copy -- those states change the
 *      meaning of the camera delta)
 *   3. re-entrant inner swap     -> inserted frame on screen
 *   4. redraw tex[cur]           -> real frame back in the new back buffer
 *   5. the original swap presents the real frame unchanged
 * </pre>
 *
 * tex[prev] = the copy made at frame N-1, ping-ponged across frames: the
 * retention the later per-pixel MV/FG_INTERPOLATION work (S-3+) consumes.
 *
 * Camera shift sources for mode 3, in priority order (S-4): (a) per-pixel --
 * the fsr2/fsr3 compute pass's dilated MV field (rg = mv in UV units,
 * prevUv = uv + rg, jitter-cancelled), sampled at the same UV and halved;
 * (b) global camera shift -- OFFSIGN * CameraMotion.deltaRel (native screen
 * px) converted to framebuffer px by fbW/nativeW, halved; (c) duplicate
 * fallback. The mv sign toggle (fsr2FgMvSign) flips the warp direction.
 * A mismatch-weighted blend leans the sample toward the current copy when
 * the two disagree (cheap disocclusion suppression), so independently
 * moving entities were ghosting in the global-only S-3 builds and are
 * handled by the per-pixel field now. UI still reads one frame old on the
 * inserted frame (both copies carry the previous UI composite).
 *
 * Each swap waits one vsync interval, so with vsync on PZ's loop halves and
 * the screen shows one inserted frame per real frame at full display rate.
 * The PZ in-game FPS counter will read roughly half; that is expected.
 *
 * GL rules respected: everything runs on the render thread, stale
 * game-generated GL errors are drained before any of our owned-error
 * accounting, three own-error strikes disable the insert for the session,
 * and any fault or linkage error leaves PZ's own present untouched.
 */
public final class FgPresent {

    /** Insert style for the test toggle (FsrControl#fsr2FgTest). */
    private static volatile int testMode;
    /** Reentry guard: the inner swap re-enters the instrumented method. */
    private static volatile boolean inFlight;
    /** Lockdown after repeated own-faults: never break PZ's present. */
    private static volatile boolean disabled;

    private static long realPresents;
    private static long insertedPresents;
    private static long insertionSkips; // geometry/assets-failed frames
    private static long interpFrames;   // mode-3 frames that actually interpolated
    private static long interpPx;       // interpolated frames that used the per-pixel MV field
    private static int insertErrors;    // strikes raised by our own GL calls
    /** Inserts that had to drain game-left GL errors before starting. */
    private static long staleDrains;
    /** Empirical sign of the screen shift per camera offset unit. */
    private static int mvSign = IsoViewProjection.OFFSIGN;
    /**
     * fgDispatchEpoch() value at the previous present. Interpolation only
     * runs when this differs, i.e. a fresh world frame was dispatched since
     * then; otherwise the retained MV field / camera delta describe stale
     * content (loading screens, pause without dispatch) and the insert must
     * be a plain duplicate. MIN_VALUE = no baseline yet.
     */
    private static long lastDispatchEpoch = Long.MIN_VALUE;

    /**
     * Experimental VSync-off pacing (S-5): post-insert exposure wait in ns,
     * 0 = off (default; VSync ON is assumed to give the inserted frame its
     * scanout). With VSync off, both our swaps complete in microseconds, so
     * the display would only ever see the last-presented (real) frame. Here
     * we instead: (a) delay the insert flip so the previous real frame keeps
     * roughly one exposure window, (b) wait one exposure window after the
     * inner swap before restoring the real frame. The loop is thereby
     * throttled to ~2 exposure windows per real present -- a manual
     * substitute for the vblank the VSync path normally provides. No scanout
     * alignment is controlled, so mild tearing on either boundary remains
     * possible; that is the nature of VSync-off presentation.
     */
    private static volatile long paceWaitNs;
    /** Time of the previous real swap's enter (set by countInterval). */
    private static long prevRealSwapNanos;
    private static long lastNanos;

    // -- GL assets (render thread only; lazy at the first insert) ----------
    private static boolean ready;
    private static int prog;
    private static int vao;
    /** Ping-pong copies of the presented frames: tex1 = older, tex2 = newer. */
    private static int tex1;
    private static int tex2;
    /** 1 or 2: slot that received the newest copy. */
    private static int curSlot;
    /** True once a copy from an earlier frame is retained in the other slot. */
    private static boolean prevValid;
    private static int copyW;
    private static int copyH;

    private static final String VERT_SRC =
        "#version 330\n" +
        "layout(location=0) in vec3 aPosition;\n" +
        "layout(location=1) in vec4 aColor;\n" +
        "layout(location=2) in vec2 aUV1;\n" +
        "out vec4 vColor;\n" +
        "out vec2 vUV1;\n" +
        "void main(void)\n" +
        "{\n" +
        "    gl_Position = vec4(aPosition.xy, 0.0, 1.0);\n" +
        "    vUV1 = aUV1;\n" +
        "    vColor = aColor;\n" +
        "}\n";

    // uMode 0: solid magenta (S-1 beacon). 1: uA replay (duplicate insert).
    // 2: per-pixel MV interpolation: uMV = the compute pass's dilated MV
    // field (render res, rg = mv in UV units, prevUv = uv + rg). Probe
    // finding (2026-10-25): the field's magnitude is exactly 2x the
    // camera's per-frame delta on BOTH axes (sign matches camRel), so the
    // half shift is 0.25 * field. uHalfScale carries that factor and the
    // user sign toggle; prev copy warped +sh, current copy warped -sh,
    // mismatch-weighted blend (lean current on disagreement
    // so disocclusions do not trail ghosts). 3: global camera-MV variant of
    // the same warp (uHalf = the precomputed half shift), the fallback when
    // no MV field exists; independently moving entities ghost (documented
    // S-3 limitation).
    //
    // uFlags bit5 (uHudSnap, fsr2FgHudSnap): in the screen-static replay
    // branch below, HUD content that CHANGED between the two real frames
    // (inventory lines, dashboard digits, moodles re-laying out at speed)
    // is sampled from the current copy alone instead of blended. The old
    // 50/50-ish blend printed a double ghost on the inserted frame while
    // the real frame showed crisp text -- the alternating crisp/ghost is
    // the reported inventory/dashboard flicker at speed.
    private static final String FRAG_SRC =
        "#version 330\n" +
        "uniform sampler2D uA;\n" +
        "uniform sampler2D uB;\n" +
        "uniform sampler2D uMV;\n" +
        "uniform int uMode;\n" +
        "uniform int uFlags;\n" +
        "uniform vec2 uHalf;\n" +
        "uniform vec2 uHalfScale;\n" +
        // Experiment transform of the sampled MV field (fsr2FgMvFlip):
        // bit0 = sample at (x, 1-y) (texture v-orientation mismatch),
        // bit1 = negate mv.x, bit2 = negate mv.y (axis sign conventions).
        // 0 is the unchanged behavior.
        "vec2 mvField(vec2 uv)\n" +
        "{\n" +
        "    if ((uFlags & 1) != 0) uv = vec2(uv.x, 1.0 - uv.y);\n" +
        "    vec2 mv = texture2D(uMV, uv, 0.0).rg;\n" +
        "    if ((uFlags & 2) != 0) mv.x = -mv.x;\n" +
        "    if ((uFlags & 4) != 0) mv.y = -mv.y;\n" +
        "    // bits 8/16: extra x0.5 on that axis (magnitude experiments --\n" +
        "    // the axis born as the generator's native-to-render conversion).\n" +
        "    if ((uFlags & 8) != 0) mv.x = mv.x * 0.5;\n" +
        "    if ((uFlags & 16) != 0) mv.y = mv.y * 0.5;\n" +
        "    return mv;\n" +
        "}\n" +
        "in vec4 vColor;\n" +
        "in vec2 vUV1;\n" +
        "vec3 warpAvg(vec2 sh)\n" +
        "{\n" +
        "    vec3 pa = texture2D(uA, vUV1 + sh, 0.0).rgb;\n" +
        "    vec3 pb = texture2D(uB, vUV1 - sh, 0.0).rgb;\n" +
        "    float diff = max(max(abs(pa.r - pb.r), abs(pa.g - pb.g)),\n" +
        "                     abs(pa.b - pb.b));\n" +
        "    // Screen-static content (HUD composited over the moving world;\n" +
        "    // the MV field is world-only, so HUD pixels get warped by the\n        // camera motion and smear worst at running speed): the unshifted\n" +
        "    // pair matches while the +-sh pair disagrees. Replay such pixels\n" +
        "    // unwarped (moving world differs by more than a px here, flat\n" +
        "    // areas that do not mismatch are harmless either way).\n" +
        "    vec3 za = texture2D(uA, vUV1, 0.0).rgb;\n" +
        "    vec3 zb = texture2D(uB, vUV1, 0.0).rgb;\n" +
        "    float dz = max(max(abs(za.r - zb.r), abs(za.g - zb.g)),\n" +
        "                   abs(za.b - zb.b));\n" +
        "    if (dz + 0.02 < diff) {\n" +
        "        // Static-changed HUD (text redrawn, digits ticked, layout\n" +
        "        // refreshed): with the snap bit on, show the current copy\n" +
        "        // alone. The frame blending two text generations double-\n" +
        "        // prints glyphs and flashes against the crisp real frame.\n" +
        "        // Threshold 0.05 keeps sub-visible noise gradients blended.\n" +
        "        if ((uFlags & 32) != 0 && dz > 0.05) return zb;\n" +
        "        float w0 = 0.5 * clamp(1.0 - dz * 4.0, 0.3, 1.0);\n" +
        "        return za * w0 + zb * (1.0 - w0);\n" +
        "    }\n" +
        "    float w = 0.5 * clamp(1.0 - diff * 4.0, 0.3, 1.0);\n" +
        "    return pa * w + pb * (1.0 - w);\n" +
        "}\n" +
        "void main()\n" +
        "{\n" +
        "    if (uMode == 0) { gl_FragColor = vec4(1.0, 0.0, 1.0, 1.0); return; }\n" +
        "    if (uMode == 1) { gl_FragColor = texture2D(uA, vUV1, 0.0); return; }\n" +
        "    if (uMode == 2) { gl_FragColor = vec4(warpAvg(uHalfScale *\n" +
        "        mvField(vUV1)), 1.0); return; }\n" +
        "    gl_FragColor = vec4(warpAvg(uHalf), 1.0);\n" +
        "}\n";

    private FgPresent() {}

    /** Advice entry (Display#swapBuffers enter). */
    public static void onSwapEnter() {
        if (inFlight) return;           // our own inner swap: nothing to do
        countInterval();
        realPresents++;
        if (testMode == 0 || disabled) return;
        try {
            insertPresent(testMode);
        } catch (Throwable t) {
            // Includes linkage failures for org.lwjglx -- the PZ present path
            // must keep running regardless of what the insert path breaks on.
            disabled = true;
            FsrLog.log("fg present insert failed once, test off for session: " + t);
        }
    }

    /** Lua: fsr2FgTest(0|1|2|3). See the class comment for mode semantics. */
    public static void setTest(int v) {
        int m = v;
        if (m < 0 || m > 3) m = 0;
        if (m != testMode) {
            testMode = m;
            FsrLog.log("fg present test " + switch (m) {
                case 1 -> "1 (magenta insert)";
                case 2 -> "2 (duplicate insert)";
                case 3 -> "3 (camera-MV interpolation)";
                default -> "off";
            } + " real=" + realPresents
                + (disabled ? " [DISABLED: insert skipped, see prior strike log]"
                            : ""));
        }
    }

    /**
     * Console re-arm path (FsrControl#fsrReenable): clear the session
     * disable plus the strike counter so the insert works again; a
     * genuinely broken insert re-fails and stands down on its own.
     */
    public static void reEnable() {
        if (disabled) {
            FsrLog.log("fg present re-enabled from console");
        }
        disabled = false;
        insertErrors = 0;
    }

    /** Lua: fsr2FgMvSign(-1|1) — flips the interpolation shift direction. */
    public static void setMvSign(int s) {
        int v = s >= 0 ? 1 : -1;
        if (v != mvSign) {
            mvSign = v;
            FsrLog.log("fg present mvSign = " + v);
        }
    }

    private static volatile int mvFlip;

    /** uFlags bit5 of the FRAG_SRC HUD-replay branch: snap CHANGED
     *  screen-static HUD pixels (inventory text, dashboard digits) to the
     *  current copy instead of blending both frames. Default on -- the
     *  blend it replaces is the reported inventory/dashboard flicker at
     *  speed. Console only (fsr2FgHudSnap), not persisted. */
    private static volatile boolean hudSnap = true;
    private static final int HUD_SNAP_BIT = 32;

    private static int uHudFlags() {
        return hudSnap ? HUD_SNAP_BIT : 0;
    }

    /** Lua console: fsr2FgHudSnap(0|1). 0 restores the two-frame HUD
     *  blend (the pre-fix double-ghost behavior), 1 snaps changed static
     *  HUD pixels to the current copy. */
    public static void setHudSnap(int v) {
        boolean on = v != 0;
        if (on != hudSnap) {
            hudSnap = on;
            FsrLog.log("fg present hudSnap = " + on);
        }
    }

    /**
     * Lua console: fsr2FgMvFlip(0..31) — per-pixel MV field experiment
     * transform for the axis-asymmetric interpolation artifacts.
     * Bit0 mirrors the sample v, bit1 negates mv.x, bit2 negates mv.y,
     * bit3 halves the x warp magnitude, bit4 halves the y warp magnitude.
     * 0 = stock behavior. Pure experiment toggle, not persisted.
     */
    public static void setMvFlip(int n) {
        int v = Math.max(0, Math.min(31, n));
        if (v != mvFlip) {
            mvFlip = v;
            FsrLog.log("fg present mvFlip = " + v);
        }
    }

    static int mvFlip() {
        return mvFlip;
    }

    /** Half-shift scale on the mv field. Probe-validated default 0.25
     * (= half the per-frame camera delta; the field itself is 2x the
     * delta — see FRAG_SRC). fsr2FgMvScale tunes it live for fast-motion
     * residuals. Not persisted. */
    private static volatile float mvScale = 0.25f;

    /** Lua console: fsr2FgMvScale(0.05..1.0) — live fine-tune of the
     * half-shift factor. 0.25 = probe-validated midpoint default; the
     * raised range (up to 1.0 = full field) lets faster-warp experiments
     * trade correctness for perceived motion rate. */
    public static void setMvScale(float f) {
        float v = Math.max(0.05f, Math.min(1.0f, f));
        if (Math.abs(v - mvScale) > 1e-5f) {
            mvScale = v;
            FsrLog.log("fg present mvScale = " + v);
        }
    }

    static float mvScale() {
        return mvScale;
    }

    /** Lua: fsr2FgMult(m) — interpolated inserts per real frame.
     * 1 = stock 2x FG (one insert between two real frames). 2 = two
     * inserts at t=1/3 and 2/3 (3x scan rate), 3 = three inserts (4x).
     * Pair with a matching exposure: real-present spacing becomes
     * (m+1) * exposure, met by the insert loop's pre-wait. Not persisted. */
    private static volatile int fgMult = 1;

    public static void setMult(int v) {
        int n = Math.max(1, Math.min(3, v));
        if (n != fgMult) {
            fgMult = n;
            FsrLog.log("fg present mult = " + n);
        }
    }

    /**
     * Lua: fsr2FgPace(0|-1|-2|-3|-4|-5|N). Experimental VSync-off pacing.
     * 0 = off (default; VSync ON assumed). -1 = auto-safe: one full display
     * refresh (from the current Display mode's frequency) of exposure --
     * tear-free, but the loop throttles to displayRate/2. -2 = auto-fast:
     * half a refresh of exposure -- the loop runs near displayRate, but
     * partial scanouts (tearing/reg cut frames) become likely. -3/-4/-5 =
     * quarter/eighth/sixteenth refresh exposures for the high-multiplier
     * modes (fsr2FgMult 2/3) and fast-lobe experiments. N &gt; 0 = manual
     * exposure in tenths of a millisecond. The real-present spacing is
     * (mult+1) x the exposure, kept by the insert loop's pre-wait so every
     * present of a cycle gets a comparable scanout window.
     */
    public static void setPace(int v) {
        long ns;
        if (v == 0) {
            ns = 0;
        } else if (v == -1 || v == -2) {
            int freq = 60;
            try {
                freq = Math.max(30,
                    org.lwjglx.opengl.Display.getDisplayMode().getFrequency());
            } catch (Throwable ignored) {}
            ns = v == -1 ? 1_000_000_000L / freq
                         : 500_000_000L / freq;
        } else if (v <= -3) {
            int freq = 60;
            try {
                freq = Math.max(30,
                    org.lwjglx.opengl.Display.getDisplayMode().getFrequency());
            } catch (Throwable ignored) {}
            int div = 2 << (-v - 2); // -3 = 4, -4 = 8, -5 = 16
            ns = (1_000_000_000L / freq) / div;
        } else {
            ns = Math.max(1, v) * 100_000L; // tenths of ms → ns
        }
        if (ns != paceWaitNs) {
            paceWaitNs = ns;
            if (ns == 0) {
                FsrLog.log("fg present pace = off (vsync assumed)");
            } else {
                String label;
                if (v == -1) label = "safe: 1 refresh";
                else if (v == -2) label = "fast: 1/2 refresh";
                else if (v <= -3) label = "auto: 1/" + (2 << (-v - 2)) + " refresh";
                else label = "manual";
                FsrLog.log("fg present pace = " + String.format(
                    "%.2f ms exposure, %.2f ms real-present spacing (%s)",
                    ns / 1e6, ns * (fgMult + 1) / 1e6, label));
            }
        }
    }

    /** Status fragment for FsrControl#status(). */
    public static String status() {
        return "fgtest=" + testMode
            + (fgMult != 1 ? " mult=" + fgMult : "")
            + " real=" + realPresents
            + " inserted=" + insertedPresents
            + (testMode == 3 ? " interp=" + interpFrames + " pxmv=" + interpPx : "")
            + (insertionSkips != 0 ? " skipped=" + insertionSkips : "")
            + (insertErrors != 0 ? " err=" + insertErrors : "")
            + (paceWaitNs != 0
                ? String.format(" pace=%.2fms", paceWaitNs / 1e6)
                : "")
            + (disabled ? " DISABLED" : "");
    }

    private static void countInterval() {
        long now = System.nanoTime();
        prevRealSwapNanos = lastNanos;
        lastNanos = now;
    }

    // -- the inserted present sequence -------------------------------------

    private static void insertPresent(int mode) {
        // The game (zink in particular) raises GL errors of its own between
        // frames -- a steady 0x500 steam. Without this drain a game error
        // sitting in the queue at swap entry is popped by the strike check
        // after the insert, three frames in a row cost three strikes and the
        // whole session flipped to disabled with mode toggles doing nothing.
        // Only faults of OUR OWN calls are allowed to count as strikes, so
        // clear everything that predates the insert here.
        int stale = 0;
        while (GL11.glGetError() != 0 && stale < 32) stale++;
        if (stale > 0) {
            long total = ++staleDrains;
            if (total <= 8 || total % 900 == 0) {
                FsrLog.log("fg present: drained " + stale + " stale GL error(s) before insert (total " + total + ")");
            }
        }
        int[] vp = new int[4];
        Gl.getViewport(vp);
        if (vp[2] <= 0 || vp[3] <= 0) {
            insertionSkips++;
            return;
        }
        // The copy must match the actual back-buffer pixels: PZ's LWJGLX
        // shim tracks the GLFW framebuffer size; the GL viewport at swap
        // time may be a leftover from the last draw (ui/scoped) and is only
        // a fallback.
        int w = 0;
        int h = 0;
        try {
            w = org.lwjglx.opengl.Display.getFramebufferWidth();
            h = org.lwjglx.opengl.Display.getFramebufferHeight();
        } catch (Throwable ignored) {}
        if (w <= 0 || h <= 0) {
            w = vp[2];
            h = vp[3];
        }
        if (!ensureAssets(w, h)) {
            insertionSkips++;
            return;
        }

        // Pre-insert wait (pacing): each insert gets its own exposure
        // window and the real-present spacing is (mult+1) * exposure, so
        // the first insert waits until the previous real frame has had
        // its remaining mult exposure windows before flipping over it.
        long pace = paceWaitNs;
        if (pace > 0 && prevRealSwapNanos != 0) {
            long rest = prevRealSwapNanos + fgMult * pace
                - System.nanoTime();
            if (rest > 0) paceSleep(rest);
        }

        // Mode-3 validity: the camera delta describes prev->current only
        // when the temporal pipeline itself trusts it. Otherwise duplicate.
        boolean interp = false;
        boolean perPixel = false;
        float hx = 0f;
        float hy = 0f;
        if (mode == 3 && prevValid) {
            // FG gate: only a true stop needs a duplicate. The loose
            // still() threshold (d < 0.5 px/frame) exists for the fsr2
            // reactive semantics; at high present rates a walking camera
            // moves under it in bursts, and duplicating the insert then
            // freezes the frame while the next real frame jumps two
            // deltas -- the reported "whole-screen vibration". Below
            // stillExact (d < 0.01 px) the mv field is zeroed anyway, so
            // the duplicate is legitimate only there.
            boolean stillBad = CameraMotion.stillExact();
            boolean zoomBad = CameraMotion.zoomChanged();
            boolean resetBad = CameraMotion.resetRequested();
            boolean noPrevBad = !CameraMotion.hasPrev();
            boolean epochBad = Fsr2Compute.fgDispatchEpoch() == lastDispatchEpoch;
            boolean motionUsable = !stillBad && !zoomBad && !resetBad
                && !noPrevBad && !epochBad;
            if (motionUsable && Fsr2Compute.fgMvAvailable()) {
                // Per-pixel: the compute pass's dilated MV field already
                // carries the validated OFFSIGN/jitter-cancelled semantics;
                // only the half factor and the user sign toggle apply.
                perPixel = true;
                interp = true;
            } else if (motionUsable) {
                int natW = Math.max(64, CameraMotion.nativeW());
                int natH = Math.max(64, CameraMotion.nativeH());
                float fx = (float) w / natW;
                float fy = (float) h / natH;
                float sign = mvSign;
                // Screen shift between N-1 and N (fb px), halved, as UV.
                hx = 0.5f * sign * CameraMotion.deltaRelX() * fx / w;
                hy = 0.5f * sign * CameraMotion.deltaRelY() * fy / h;
                interp = true;
            }
        }

        // Save everything the copy/draw touches.
        int prevFbo = Gl.boundFbo();
        int prevProg = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevRead = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

        int cur = curSlot == 1 ? tex1 : tex2;
        int prv = curSlot == 1 ? tex2 : tex1;

        try {
            // 1. Copy the pending frame into tex[cur].
            Gl.bindFbo(0);
            GL11.glReadBuffer(GL11.GL_BACK);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cur);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0,
                    0, 0, copyW, copyH);

            // 2-3. Inserts + inner swaps: fgMult interpolated (or duplicate)
            // frames per real frame. Positions t_i = i/(mult+1) across the
            // prev->cur delta; tScale = 2*t_i multiplies the shift (1 = the
            // stock midpoint at mult 1). Each insert gets one exposure
            // window (pace) before the next flip.
            for (int i = 1; i <= fgMult; i++) {
                if (interp) {
                    float tScale = fgMult == 1 ? 1.0f
                        : 2.0f * i / (fgMult + 1);
                    drawInterp(prv, cur, perPixel, hx, hy, tScale);
                    interpFrames++;
                    if (perPixel) interpPx++;
                } else if (mode == 1) {
                    drawStyled(0); // solid magenta: proof the extra present lands
                } else {
                    drawStyled(1); // duplicate replay of the current copy
                }
                innerSwap();
                // Exposure window: with VSync off the display needs CPU-side
                // time to scan the inserted frame before the next flip is
                // committed. Pure sleep (flip stays, nothing is drawn) --
                // GL state stays idle for the duration.
                if (pace > 0) paceSleep(pace);
            }

            // 4. Restore the real frame in the new back buffer.
            drawStyled(1);

            // Retention: this frame's copy becomes the next frame's prev.
            prevValid = true;
            curSlot = curSlot == 1 ? 2 : 1;
            // Freshness baseline for the next present: remember the newest
            // epoch so interpolation runs only when a dispatch ran since.
            lastDispatchEpoch = Fsr2Compute.fgDispatchEpoch();
        } finally {
            // Restore GL state so PZ's own swap/present logic is unaffected.
            if (blend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL20.glUseProgram(prevProg);
            GL30.glBindVertexArray(prevVao);
            Gl.setViewport(vp[0], vp[1], vp[2], vp[3]);
            GL11.glReadBuffer(prevRead);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(prevActive);
            Gl.bindFbo(prevFbo);
            // Anything queued between the asset-build drain and here was
            // raised by our own calls: count it with a strike budget.
            int err = GL11.glGetError();
            if (err != 0) {
                insertErrors++;
                FsrLog.log("fg present: GL error 0x" + Integer.toHexString(err)
                    + " after insert (strike " + insertErrors + "/3)");
                if (insertErrors >= 3) {
                    disabled = true;
                    FsrLog.log("fg present: 3 insert errors, off for session");
                }
            }
        }
    }

    /**
     * Interpolated insert: uA = previous copy (+half warp), uB = current
     * copy (-half warp). perPixel selects shader uMode 2 (the compute
     * pass's per-pixel MV field, sampled at the same UV; uHalfScale carries
     * the 0.5x factor and the user sign toggle) over shader uMode 3 (the
     * precomputed global camera shift in uHalf).
     */
    private static void drawInterp(int prevTex, int curTex,
                                   boolean perPixel, float hx, float hy,
                                   float tScale) {
        Gl.bindFbo(0);
        Gl.setViewport(0, 0, copyW, copyH);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL20.glUseProgram(prog);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        GL20.glUniform1i(Gl.uniform(prog, "uA"), 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, curTex);
        GL20.glUniform1i(Gl.uniform(prog, "uB"), 1);
        if (perPixel) {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, Fsr2Compute.fgMvTexture());
            GL20.glUniform1i(Gl.uniform(prog, "uMV"), 2);
            GL20.glUniform1i(Gl.uniform(prog, "uMode"), 2);
            GL20.glUniform1i(Gl.uniform(prog, "uFlags"), mvFlip | uHudFlags());
            GL20.glUniform2f(Gl.uniform(prog, "uHalfScale"), mvSign * mvScale * tScale,
                mvSign * mvScale * tScale);
        } else {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL20.glUniform1i(Gl.uniform(prog, "uMV"), 2);
            GL20.glUniform1i(Gl.uniform(prog, "uMode"), 3);
            GL20.glUniform1i(Gl.uniform(prog, "uFlags"), uHudFlags());
        }
        GL20.glUniform2f(Gl.uniform(prog, "uHalf"), hx * tScale, hy * tScale);
        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Duplicate insert: fullscreen texture replay (uMode 1), or the
     *  magenta beacon (uMode 0) for the mode-1 cadence test. */
    private static void drawStyled(int uMode) {
        Gl.bindFbo(0);
        Gl.setViewport(0, 0, copyW, copyH);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL20.glUseProgram(prog);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, curSlot == 1 ? tex1 : tex2);
        GL20.glUniform1i(Gl.uniform(prog, "uA"), 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL20.glUniform1i(Gl.uniform(prog, "uB"), 1);
        GL20.glUniform1i(Gl.uniform(prog, "uMode"), uMode);
        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Hybrid wait for pacing: park for the bulk, then busy-spin the final
     * couple of milliseconds -- Windows timers alone are ~1ms+ granular and
     * the exposure window needs a sub-ms sharp edge.
     */
    private static void paceSleep(long nanos) {
        long end = System.nanoTime() + nanos;
        long bulk = nanos - 2_000_000L;
        if (bulk > 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(bulk);
        }
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    private static void innerSwap() {
        inFlight = true;
        try {
            org.lwjglx.opengl.Display.swapBuffers();
        } catch (org.lwjglx.LWJGLException e) {
            // The original call would have thrown the same exception through
            // PZ's loop; here it means the insert path is unstable -- surface
            // it so onSwapEnter flips the one-strike disabled flag.
            throw new RuntimeException(e);
        } finally {
            inFlight = false;
        }
        insertedPresents++;
    }

    /** Compile program, quad VAO and copy textures once; resize rebuilds. */
    private static boolean ensureAssets(int w, int h) {
        if (ready && copyW == w && copyH == h) return true;
        // PZ generates stale GL errors of its own (zink in particular; fsr2
        // already drains them at dispatch start). Clear the queue first so
        // the asset check below can only ever count faults of ours.
        int stale = 0;
        while (GL11.glGetError() != 0 && stale < 32) stale++;
        if (stale > 0) {
            FsrLog.log("fg present: drained " + stale + " stale GL error(s) before asset build");
        }
        if (ready && (copyW != w || copyH != h)) {
            Gl.deleteTex(tex1);
            Gl.deleteTex(tex2);
            tex1 = 0;
            tex2 = 0;
            prevValid = false;
        }
        if (prog == 0) {
            prog = Gl.compileProgram(VERT_SRC, FRAG_SRC, "fgPresent");
            vao = Gl.createQuadVao();
        }
        if (tex1 == 0) {
            copyW = w;
            copyH = h;
            tex1 = Gl.createRgba8(w, h);
            tex2 = Gl.createRgba8(w, h);
            curSlot = 1;
            prevValid = false;
        }
        boolean ok = prog != 0 && vao != 0 && tex1 != 0 && tex2 != 0
            && Gl.check("fgPresent");
        if (!ok) {
            disabled = true;
            FsrLog.log("fg present assets failed (prog=" + prog + " vao=" + vao
                + " tex=" + tex1 + "/" + tex2 + "), test off for session");
            return false;
        }
        ready = true;
        return true;
    }
}
