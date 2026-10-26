package fsrupsc;

/**
 * Lua control facade (called from FSR_UPSC_Options.lua and the console).
 *
 * <p>Every setter persists immediately and re-syncs the live config, so a
 * change from the Mods-options screen takes effect at the next frame that
 * recomputes geometry -- no restart.
 */
public final class FsrControl {

    public static String getVersion() {
        return FsrConfig.REVISION;
    }

    public static void setUpscaler(String v) {
        FsrSettings.get().upscaler = v;
        FsrSettings.get().save();
        FsrConfig.sync();
        RenderScale.reconfigure();
    }

    public static void setUpscalerQuality(String v) {
        FsrSettings.get().upscalerQuality = v;
        FsrSettings.get().save();
        FsrConfig.sync();
        RenderScale.reconfigure();
    }

    public static void setUpscalerScalePct(int n) {
        FsrSettings.get().upscalerScalePct = n;
        FsrSettings.get().save();
        FsrConfig.sync();
        RenderScale.reconfigure();
    }

    public static void setFsrSharpnessPct(int n) {
        FsrSettings.get().fsrSharpnessPct = n;
        FsrSettings.get().save();
        FsrConfig.sync();
    }

    public static void setUpscalerObjectMv(boolean v) {
        FsrSettings.get().upscalerObjectMv = v;
        FsrSettings.get().save();
        FsrConfig.sync();
    }

    /** FSR2 compute debug visualizer: 0=off 2=newSample 3=mv 4=accumWeight 7=reprojDelta. */
    public static void setFsr2Debug(int v) {
        Fsr2Compute.setDebugMode(v);
    }

    /**
     * M2c DepthProxy: 0=off (constant depth, default) 1=on(auto; needs a
     * depth-write-healthy world FBO readback) 2=on(force).
     */
    public static void fsr2Depth(int v) {
        DepthProxy.setLevel(v);
    }

    /** M2.5 camera jitter: 0=off (default) 1=on (Halton(2,3) injection). */
    public static void fsr2Jitter(int v) {
        CameraJitter.setLevel(v);
    }

    /**
     * M3 auto-reactive: 0=off 1=on (default ON; prev-color diff -> fresh
     * sample weight for pixels the MV field cannot track).
     */
    public static void fsr2AutoReactive(int v) {
        Fsr2Compute.setFsr2AutoReactive(v);
    }

    /** M3.2 motion-smear dampener (camera reactive floor): 0=off, 1=mild
     * (default) .. 5=aggressive. Freshens the blend proportional to camera
     * pan speed; no effect at exact still.
     */
    public static void fsr2CamReactive(int v) {
        Fsr2Compute.setFsr2CamReactive(v);
    }

    /**
     * S-1..S-3 FG present spike (FgPresent): 0=off (default) 1=magenta
     * inserted present every swap 2=duplicate inserted present
     * 3=interpolated present (per-pixel MV field when fsr2/fsr3 compute
     * is live, camera shift fallback otherwise).
     * Intended with vsync on (or FsrControl.fsr2FgPace with vsync off);
     * the in-game FPS counter reading ~half is expected. Persists for the
     * next launch like the other live-pushed options.
     */
    public static void fsr2FgTest(int v) {
        int n = Math.max(0, Math.min(3, v));
        FsrSettings.get().fgTest = n;
        FsrSettings.get().save();
        FsrConfig.FG_TEST = n;
        FgPresent.setTest(n);
    }

    /**
     * Flip the sign of the FG interpolation screen shift (mode 3).
     * Default follows the M2-validated device sign (IsoViewProjection.OFFSIGN).
     */
    public static void fsr2FgMvSign(int s) {
        FgPresent.setMvSign(s);
    }

    /**
     * Console experiment toggle (mode 3 per-pixel MV path): 0 = stock.
     * Bit0 mirrors the MV sample v position, bit1 negates mv.x, bit2
     * negates mv.y, bit3 halve x magnitude, bit4 halve y magnitude.
     * For the axis-asymmetric interpolation artifacts (vertical trail
     * opposite the walk, horizontal ghost along it). Not persisted; a
     * finding here is meant to become the code default.
     */
    public static void fsr2FgMvFlip(int n) {
        FgPresent.setMvFlip(n);
    }

    /**
     * Console fine-tune of the FG half-shift on the mv field (probe
     * validated: the field is exactly 2x the camera per-frame delta, so
     * 0.25 = half delta = correct midpoint). Raised values warp more
     * aggressively: ~0.35-0.5 boosts the perceived motion rate during
     * fast runs at the cost of correctness; smaller values reduce
     * trailing. Not persisted.
     */
    public static void fsr2FgMvScale(float f) {
        FgPresent.setMvScale(f);
    }

    /**
     * Console: interpolated inserts per real frame in the FG pacing loop
     * (1 = stock 2x FG). 2 = two inserts at 1/3 and 2/3 of the frame
     * delta (triple scan rate), 3 = three inserts (4x). Real-present
     * spacing becomes (m+1) x the exposure automatically; pair with a
     * smaller exposure like fsr2FgPace(-3) (quarter refresh), (-4)
     * (eighth) or a manual tenths-ms value so every present keeps a
     * scanout window. Not persisted.
     */
    public static void fsr2FgMult(int n) {
        FgPresent.setMult(n);
    }

    /**
     * Experimental VSync-off pacing for the FG insert (S-5). Without VSync
     * the two swaps of the double-present complete inside one display
     * refresh, so the inserted frame is never scanned out; pacing Sleeps
     * the render thread to give each present its own exposure window.
     * 0 = off (default, VSync ON assumed), -1 = auto-safe (one display
     * refresh; loop = displayRate/2, tear-free), -2 = auto-fast (half a
     * refresh; loop ~= displayRate, tearing likely), N &gt; 0 = manual
     * exposure in tenths of a millisecond (167 ≈ 60 Hz, 69 ≈ 144 Hz).
     * No scanout alignment is controlled: tearing on frame boundaries is
     * the nature of VSync-off presentation. off/safe/fast persist and are
     * selectable in ModOptions; manual N stays console-only.
     */
    public static void fsr2FgPace(int v) {
        int n = (v == -1 || v == -2 || v > 0) ? v : 0;
        FsrSettings.get().fgPace = n;
        FsrSettings.get().save();
        FsrConfig.FG_PACE = n;
        FgPresent.setPace(n);
    }

    /** Flip the empirical camera-offset sign used by the M2 MV path. */
    public static void fsr2OffSign(int sign) {
        IsoViewProjection.OFFSIGN = sign >= 0 ? 1 : -1;
        FsrLog.log("fsr2 OFFSIGN = " + IsoViewProjection.OFFSIGN);
    }

    /**
     * Outline offset fix (confirmed 2026-10-26): 1 (code default) keeps
     * ALL ModelOutlines.endFrame viewports raw (line 215 model pass and
     * line 234 blit alike) so fboA maps 1:1 into the world buffer;
     * 0 reverts to squeezing the seams like the world pass.
     */
    public static void fsr2OutlineFix(int v) {
        RenderScale.setOutlineVpRaw(v != 0);
    }

    /**
     * Console-only re-arm after a fail-safe disable. The fail-safe is
     * session-scoped because its reasons have usually meant a broken
     * frame; re-arming clears the flag and the stale resolve output and
     * lets the pipeline retry. If the same failure repeats, it stands
     * down again on its own. Not an options-UI item by design.
     */
    public static void fsrReenable() {
        RenderScale.reEnable();
        try { FgPresent.reEnable(); } catch (Throwable ignored) {}
    }

    /**
     * FG HUD snap (console-only experiment): 1 (code default) replays
     * changed screen-static HUD pixels (inventory text, dashboard
     * digits) from the current copy alone on inserted frames, instead
     * of blending both real frames (the double-ghost flicker at speed);
     * 0 restores the blend.
     */
    public static void fsr2FgHudSnap(int v) {
        FgPresent.setHudSnap(v);
    }

    /** One-line status for the Lua profiler overlay and the log file. */
    public static String status() {
        try {
            return "upscaler=" + FsrConfig.getUpscaler()
                + " mode=" + Upscaler.mode()
                + " quality=" + FsrConfig.getUpscalerQuality()
                + " scalePct=" + FsrConfig.getUpscalerScalePct()
                + " " + RenderScale.describe()
                + " " + FsrHooks.status()
                + " " + FgPresent.status();
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** Human-readable resolve counters, for the on-screen debugger. */
    public static String resolveStatus() {
        try {
            return Upscaler.compositeStatus() + " " + ResolveOutput.describe();
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** Log a line from Lua (e.g. markers set from the in-game UI). */
    public static void log(String line) {
        FsrLog.log(line);
    }

    private FsrControl() {}
}
