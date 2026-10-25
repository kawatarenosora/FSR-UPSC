package fsrupsc;

import java.nio.ByteBuffer;
import java.util.HashMap;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;

/**
 * GL 4.3 compute pipeline for FSR 2 (port of fsr-reloaded's FSR2Pipeline).
 *
 * <p>M1 revision: the "zero motion" configuration. prevViewProj is never
 * available in PZ yet, so every frame takes the MV-clear path and the
 * accumulation runs with uCamStill=1 / uCamStillExact=1 / uUseDepth=0 and a
 * neutral 1x1 R32F depth texture (farDepth=1.0). Visually this lands close to
 * FSR1 quality (fresh-sample accumulation with a standard Lanczos upscale);
 * its purpose is to prove the compute pipeline in-game (windup for M2).
 *
 * <p>Resource rules honored:
 * - runs only on the render thread (resolve points are hook-free GL islands);
 * - failures call {@link #fault()} (session-permanent fsr1 fallback), never
 *   RenderScale.disable, so Phase 1 fsr1 stays intact;
 * - GL state (program / texture units / framebuffers / viewport / scissor)
 *   is saved and restored around every dispatch.
 */
public final class Fsr2Compute {

    // ------------------------------------------------------------------ probe

    private static volatile boolean probed;
    private static volatile boolean available;
    private static volatile int glMajor;
    private static volatile int glMinor;

    public static void probeOnce() {
        if (probed) return;
        probed = true;
        try {
            String version = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
            String renderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            if (version == null || version.isEmpty()) {
                FsrLog.log("fsr2 probe: GL_VERSION unavailable, compute unavailable");
                available = false;
                return;
            }
            int major = 0;
            int minor = 0;
            try {
                String[] parts = version.trim().split("[\\.\\s]+");
                major = Integer.parseInt(parts[0]);
                minor = Integer.parseInt(parts[1]);
            } catch (Throwable ignored) {}
            glMajor = major;
            glMinor = minor;

            boolean gl43 = false;
            boolean gl44 = false;
            try {
                Object caps = org.lwjgl.opengl.GL.getCapabilities();
                gl43 = caps != null && caps.getClass().getField("OpenGL43").getBoolean(caps);
                gl44 = caps != null && caps.getClass().getField("OpenGL44").getBoolean(caps);
            } catch (Throwable ignored) {}

            available = major > 4 || (major == 4 && minor >= 3);
            FsrLog.log("fsr2 probe: " + version
                + " renderer=" + (renderer == null ? "?" : renderer)
                + " gl43=" + gl43 + " gl44=" + gl44
                + " compute=" + (available ? "available" : "unavailable"));
            if (renderer != null
                    && renderer.toLowerCase(java.util.Locale.ROOT).contains("intel")) {
                // fsr-reloaded denylists Intel iGPUs for GL compute.
                FsrLog.log("fsr2 probe: Intel renderer detected, compute path will be disabled");
                available = false;
            }
        } catch (Throwable t) {
            FsrLog.log("fsr2 probe failed: " + t);
            available = false;
        }
    }

    private static void probeIfIdle() {
        // A resolve point always runs on the render thread with a current
        // context; the renderLoop probe usually ran first, but be safe.
        if (!probed) probeOnce();
    }

    public static boolean available() {
        return available;
    }

    public static String probeLine() {
        return "gl=" + glMajor + "." + glMinor
            + " compute=" + (available ? "yes" : "no");
    }

    // ---------------- FG (FgPresent) accessors ----------------------------

    /**
     * True when an MV field from a successfully dispatched frame exists.
     * The compute path kept its destructors away from the texture handles
     * only while {@code available} holds, so a faulted session reports no.
     */
    public static boolean fgMvAvailable() {
        return available && previousDilatedDataTex != 0;
    }

    /**
     * Latest dispatched frame's dilated MV texture: RGBA16F render res,
     * {@code rg} = motion vector in UV units ({@code prevUv = uv + rg}).
     * At present-hook time (between frames) the end-of-frame ping-pong of
     * {@link #dispatch} has already moved the just-written field into
     * {@code previousDilatedDataTex}, so that is the handle to sample.
     */
    public static int fgMvTexture() {
        return previousDilatedDataTex;
    }

    /** Render resolution of the MV field (matches its UV space). */
    public static int fgMvWidth() { return inW; }

    /** Render resolution of the MV field (matches its UV space). */
    public static int fgMvHeight() { return inH; }

    /**
     * Increments on every completed compute dispatch. FgPresent compares
     * this across presents: an unchanged value means no fresh world frame
     * was produced since the last present (loading screen, paused render
     * bypass, etc.) and the retained MV/camera state is stale.
     */
    public static long fgDispatchEpoch() { return dispatchEpoch; }


    // ------------------------------------------------------- program booking

    /** Compiled compute program with lazy cached uniform locations. */
    private static final class Prog {
        int id;
        final HashMap<String, Integer> locs = new HashMap<String, Integer>();

        int u(String name) {
            Integer l = locs.get(name);
            if (l == null) {
                l = GL43C.glGetUniformLocation(id, name);
                if (l < 0) missing.add(name); // dead uniform (mod glue removed); silently ignored
                locs.put(name, l);
            }
            return l;
        }

        void reportMissing(String tag) {
            if (missing.isEmpty()) return;
            StringBuilder sb = new StringBuilder("fsr2: dead uniforms in ").append(tag).append(":");
            for (String n : missing) sb.append(' ').append(n);
            FsrLog.log(sb.toString());
            missing.clear();
        }

        final java.util.ArrayList<String> missing = new java.util.ArrayList<String>();
    }

    private static Prog mvGen;
    private static Prog mvOverride;
    private static Prog reconstruct;
    private static Prog depthClip;
    private static Prog lock;
    private static Prog accumulate;
    private static Prog rcas;
    private static Prog autoReactive;
    private static Prog shadingChange;             // fsr3 only
    private static boolean compiled;
    private static boolean failedCompile;
    /** program set provenance: which shader family is currently compiled */
    private static boolean compiledFs3;

    // -------------------------------------------------------- texture booking

    private static int motionVectorTex;            // render RG16F NEAREST
    private static int reconstructedPrevDepthTex;  // render R32UI NEAREST
    private static int dilatedDataTex;             // render RGBA16F LINEAR
    private static int dilatedReactiveMasksTex;    // render RG8 LINEAR
    private static int previousDilatedDataTex;     // render RGBA16F LINEAR
    private static int preparedInputColorTex;      // render RGBA16F LINEAR
    private static int lockStatusCurrTex;          // display RGBA8 NEAREST
    private static int lockStatusHistoryTex;       // display RGBA8 NEAREST
    private static int newLocksTex;                // display R8 NEAREST
    private static int internalUpscaledTex;        // display R11F_G11F_B10F LINEAR
    private static int internalUpscaledWriteTex;   // display R11F_G11F_B10F LINEAR
    private static int upscaledOutputTex;          // display RGBA8 LINEAR
    private static int neutralDepthTex;            // 1x1 R32F NEAREST = farDepth
    private static int autoReactiveTex;            // render R8 NEAREST (M3 mask)
    private static int prevColorTex;               // render, same fmt as colorTex (M3 prev color)
    private static int shadingChangeTex;           // render RG8 LINEAR (M4 fsr3)
    private static int previousShadingChangeTex;   // render RG8 LINEAR (M4 fsr3)
    private static int clearMvFbo;
    private static int neutralDepthFbo;
    private static int mvUbo;                      // std140 MotionBuf (entity MVs)

    // ---- M3 auto-reactive state
    /** Default ON: pure reactivity addition, does not change MV/jitter semantics. */
    private static volatile int autoReactiveOn = 1;
    private static boolean hasPrevColor;
    private static boolean prevColorFmtFail;
    /** previous frame's stillExact, read before CameraMotion.begin() re-computes */
    private static boolean lastStillExact = true;

    /**
     * M3.2 camera-motion reactive gain (the fsr-reloaded uCamReactive floor,
     * calibrated for the iso camera): 0 = off (all frames pass 0, current
     * behavior); 1..5 = slope multiplier on a clamp((vRenderPx - 0.3)/2.0, 0,
     * 0.9) ramp, where vRenderPx is the jitter-excluded camera speed in
     * render px/frame. Dampens the multi-frame history blend while the
     * camera pans (motion smear), automatically 0 at exact still.
     */
    private static volatile int camReactiveGain = 1;

    public static void setFsr2CamReactive(int v) {
        camReactiveGain = Math.max(0, Math.min(5, v));
        FsrLog.log("fsr2 cam-reactive gain = " + camReactiveGain);
    }

    private static int inW;
    private static int inH;
    /** FG freshness marker; see fgDispatchEpoch(). */
    private static long dispatchEpoch;
    private static int outW;
    private static int outH;
    private static int currentFrameIndex;

    private static long dispatchOk;
    private static long dispatchFail;
    private static long glErrorRuns;
    private static long drainedTotal;
    private static int drainReports;
    private static int nextDrainK = 5;
    private static String lastStatus = "";

    // M1 zero-motion uniform constants (see docs/PHASE2-FSR3-GLSLDESIGN.md 4.2).
    private static final float[] DTV_NEUTRAL = {0.5f, 0f, 1f, 1f};

    // -------------------------------------------------------------- lifecycle

    /** which mode the next dispatch should compile/run: fsr2 (false) or fsr3 (true) */
    private static volatile boolean fsr3Mode;
    /** fsr3 program set is only compiled when a shading-change history exists */
    private static boolean hasPrevShading;

    public static void setFsr3Mode(boolean v) {
        fsr3Mode = v;
    }

    public static void resetHistory() {
        currentFrameIndex = 0;
    }

    /** M3 auto-reactive toggle: 0=off 1=on (default). */
    public static void setFsr2AutoReactive(int v) {
        autoReactiveOn = v > 0 ? 1 : 0;
        FsrLog.log("fsr2 auto-reactive = " + (autoReactiveOn == 1 ? "on" : "off"));
    }

    /** Session-permanent compute disable; fsr1 keeps serving afterwards. */
    public static void fault(Throwable t) {
        available = false;
        if (t != null) FsrLog.log("fsr2 compute fault: " + t);
        FsrLog.log("fsr2 compute disabled for this session (fsr1 resumes)");
        // fsr-1 serving means nobody consumes jitter anymore: stop injecting
        // so the output does not shimmer ±0.5px.
        try { CameraJitter.forceOff(); } catch (Throwable ignored) {}
        try { destroy(); } catch (Throwable ignored) {}
        probed = true; // prevent re-probing into a broken context
    }

    private static void destroy() {
        // Deleting GL objects must happen on the render thread; the resolve
        // path guarantees that. Keep calls defensive regardless.
        try {
            if (mvGen != null) GL43C.glDeleteProgram(mvGen.id);
            if (mvOverride != null) GL43C.glDeleteProgram(mvOverride.id);
            if (reconstruct != null) GL43C.glDeleteProgram(reconstruct.id);
            if (depthClip != null) GL43C.glDeleteProgram(depthClip.id);
            if (lock != null) GL43C.glDeleteProgram(lock.id);
            if (accumulate != null) GL43C.glDeleteProgram(accumulate.id);
            if (rcas != null) GL43C.glDeleteProgram(rcas.id);
            if (autoReactive != null) GL43C.glDeleteProgram(autoReactive.id);
            if (shadingChange != null) GL43C.glDeleteProgram(shadingChange.id);
        } catch (Throwable ignored) {}
        reconstruct = depthClip = lock = accumulate = rcas = null;
        mvGen = null;
        mvOverride = null;
        autoReactive = null;
        shadingChange = null;
        try {
            if (mvUbo != 0) GL43C.glDeleteBuffers(mvUbo);
        } catch (Throwable ignored) {}
        mvUbo = 0;
        try { DepthProxy.dispose(); } catch (Throwable ignored) {}
        compiled = false;
        deleteTextures();
        try {
            if (clearMvFbo != 0) GL43C.glDeleteFramebuffers(clearMvFbo);
            if (neutralDepthFbo != 0) GL43C.glDeleteFramebuffers(neutralDepthFbo);
        } catch (Throwable ignored) {}
        clearMvFbo = 0;
        neutralDepthFbo = 0;
    }

    private static boolean ensureShaders() {
        if (compiled && compiledFs3 != fsr3Mode) {
            // mode switch (fsr2 <-> fsr3): recompile from scratch, all
            // history dies (the two families are not shape-compatible)
            destroy();
        }
        if (compiled) return true;
        if (failedCompile) return false;
        probeIfIdle();
        if (!available) return false;
        boolean fs3 = fsr3Mode;
        if (fs3) {
            mvGen = compile(Fsr3Shaders.MV_GEN, "fsr3/MV_GEN");
            mvOverride = compile(Fsr2Shaders.MV_OVERRIDE, "MV_OVERRIDE");
            reconstruct = compile(Fsr3Shaders.RECONSTRUCT, "fsr3/RECONSTRUCT");
            depthClip = compile(Fsr3Shaders.DEPTH_CLIP, "fsr3/DEPTH_CLIP");
            lock = compile(Fsr3Shaders.LOCK, "fsr3/LOCK");
            accumulate = compile(Fsr3Shaders.ACCUMULATE, "fsr3/ACCUMULATE");
            rcas = compile(Fsr3Shaders.RCAS, "fsr3/RCAS");
            autoReactive = compile(Fsr2Shaders.AUTO_REACTIVE, "AUTO_REACTIVE");
            shadingChange = compile(Fsr3Shaders.SHADING_CHANGE, "fsr3/SHADING_CHANGE");
            if (mvGen == null || mvOverride == null || reconstruct == null
                    || depthClip == null || lock == null || accumulate == null
                    || rcas == null || autoReactive == null || shadingChange == null) {
                FsrLog.log("fsr2: shader compilation failed (fsr3 set, 9 programs)");
                failedCompile = true;
                return false;
            }
            compiledFs3 = true;
            compiled = true;
            FsrLog.log("fsr2: 9 compute programs compiled (fsr3 M4 set: SHADING_CHANGE on)");
            return true;
        }
        mvGen = compile(Fsr2Shaders.MV_GEN, "MV_GEN");
        mvOverride = compile(Fsr2Shaders.MV_OVERRIDE, "MV_OVERRIDE");
        reconstruct = compile(Fsr2Shaders.RECONSTRUCT, "RECONSTRUCT");
        depthClip = compile(Fsr2Shaders.DEPTH_CLIP, "DEPTH_CLIP");
        lock = compile(Fsr2Shaders.LOCK, "LOCK");
        accumulate = compile(Fsr2Shaders.ACCUMULATE, "ACCUMULATE");
        rcas = compile(Fsr2Shaders.RCAS, "RCAS");
        autoReactive = compile(Fsr2Shaders.AUTO_REACTIVE, "AUTO_REACTIVE");
        if (mvGen == null || mvOverride == null || reconstruct == null || depthClip == null
                || lock == null || accumulate == null || rcas == null || autoReactive == null) {
            FsrLog.log("fsr2: shader compilation failed (8 programs)");
            failedCompile = true;
            return false;
        }
        compiledFs3 = false;
        compiled = true;
        FsrLog.log("fsr2: 8 compute programs compiled (M3 auto-reactive set)");
        return true;
    }

    private static Prog compile(String src, String tag) {
        int id = Gl.compileCompute(src, "fsr2/" + tag);
        if (id == 0) return null;
        Prog p = new Prog();
        p.id = id;
        return p;
    }

    // ---------------------------------------------------------------- textures

    private static int createTex2D(int w, int h, int fmt, int minF, int magF) {
        int tex = GL43C.glGenTextures();
        GL43C.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL43C.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, fmt, w, h);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minF);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magF);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL43C.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    private static void ensureTextures() {
        if (motionVectorTex == 0)
            motionVectorTex = createTex2D(inW, inH, GL30.GL_RG16F, GL11.GL_NEAREST, GL11.GL_NEAREST);
        if (reconstructedPrevDepthTex == 0)
            reconstructedPrevDepthTex = createTex2D(inW, inH, GL30.GL_R32UI, GL11.GL_NEAREST, GL11.GL_NEAREST);
        if (dilatedDataTex == 0)
            dilatedDataTex = createTex2D(inW, inH, GL30.GL_RGBA16F, GL11.GL_LINEAR, GL11.GL_LINEAR);
        if (dilatedReactiveMasksTex == 0)
            dilatedReactiveMasksTex = createTex2D(inW, inH, GL30.GL_RG8, GL11.GL_LINEAR, GL11.GL_LINEAR);
        if (previousDilatedDataTex == 0)
            previousDilatedDataTex = createTex2D(inW, inH, GL30.GL_RGBA16F, GL11.GL_LINEAR, GL11.GL_LINEAR);
        if (preparedInputColorTex == 0)
            preparedInputColorTex = createTex2D(inW, inH, GL30.GL_RGBA16F, GL11.GL_LINEAR, GL11.GL_LINEAR);
        if (lockStatusCurrTex == 0)
            lockStatusCurrTex = createTex2D(outW, outH, GL11.GL_RGBA8, GL11.GL_NEAREST, GL11.GL_NEAREST);
        if (lockStatusHistoryTex == 0)
            lockStatusHistoryTex = createTex2D(outW, outH, GL11.GL_RGBA8, GL11.GL_NEAREST, GL11.GL_NEAREST);
        if (newLocksTex == 0)
            newLocksTex = createTex2D(outW, outH, GL30.GL_R8, GL11.GL_NEAREST, GL11.GL_NEAREST);
        if (internalUpscaledTex == 0)
            internalUpscaledTex = createTex2D(outW, outH, GL30.GL_R11F_G11F_B10F, GL11.GL_LINEAR, GL11.GL_LINEAR);
        if (internalUpscaledWriteTex == 0)
            internalUpscaledWriteTex = createTex2D(outW, outH, GL30.GL_R11F_G11F_B10F, GL11.GL_LINEAR, GL11.GL_LINEAR);
        if (upscaledOutputTex == 0)
            upscaledOutputTex = createTex2D(outW, outH, GL11.GL_RGBA8, GL11.GL_LINEAR, GL11.GL_LINEAR);
        if (autoReactiveTex == 0)
            autoReactiveTex = createTex2D(inW, inH, GL30.GL_R8, GL11.GL_NEAREST, GL11.GL_NEAREST);
        if (fsr3Mode) {
            if (shadingChangeTex == 0)
                shadingChangeTex = createTex2D(inW, inH, GL30.GL_RG8, GL11.GL_LINEAR, GL11.GL_LINEAR);
            if (previousShadingChangeTex == 0)
                previousShadingChangeTex = createTex2D(inW, inH, GL30.GL_RG8, GL11.GL_LINEAR, GL11.GL_LINEAR);
        }
        if (neutralDepthTex == 0) {
            neutralDepthTex = createTex2D(1, 1, GL30.GL_R32F, GL11.GL_NEAREST, GL11.GL_NEAREST);
            neutralDepthFbo = GL43C.glGenFramebuffers();
            int prevDraw = GL43C.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            GL43C.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, neutralDepthFbo);
            GL43C.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, neutralDepthTex, 0);
            boolean scissor = false;
            try { scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST); } catch (Throwable ignored) {}
            if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL43C.glClearBufferfv(GL11.GL_COLOR, 0, new float[]{1.0f}); // farDepth
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL43C.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        }
    }

    private static void deleteTextures() {
        int[] texs = {
            motionVectorTex, reconstructedPrevDepthTex, dilatedDataTex,
            dilatedReactiveMasksTex, previousDilatedDataTex, preparedInputColorTex,
            lockStatusCurrTex, lockStatusHistoryTex, newLocksTex,
            internalUpscaledTex, internalUpscaledWriteTex, upscaledOutputTex,
            neutralDepthTex, autoReactiveTex, prevColorTex,
            shadingChangeTex, previousShadingChangeTex
        };
        for (int t : texs) {
            if (t != 0) {
                try { GL43C.glDeleteTextures(t); } catch (Throwable ignored) {}
            }
        }
        motionVectorTex = 0;
        reconstructedPrevDepthTex = 0;
        dilatedDataTex = 0;
        dilatedReactiveMasksTex = 0;
        previousDilatedDataTex = 0;
        preparedInputColorTex = 0;
        lockStatusCurrTex = 0;
        lockStatusHistoryTex = 0;
        newLocksTex = 0;
        internalUpscaledTex = 0;
        internalUpscaledWriteTex = 0;
        upscaledOutputTex = 0;
        neutralDepthTex = 0;
        autoReactiveTex = 0;
        prevColorTex = 0;
        shadingChangeTex = 0;
        previousShadingChangeTex = 0;
        hasPrevColor = false;
        hasPrevShading = false;
    }

    // ---------------------------------------------------------------- dispatch

    private static void setCommonUniforms(Prog p,
            int uRS, int uDS, int uMRS, int uDTV, int uJit, int uMVS,
            int uDSF, int uMVJC, int uPreExp, int uPrevPreExp, int uVSMF, int uFIdx,
            float[] dtv, float downscaleX, float downscaleY) {
        GL43C.glUniform2i(uRS, inW, inH);
        GL43C.glUniform2i(uDS, outW, outH);
        GL43C.glUniform2i(uMRS, outW, outH);
        GL43C.glUniform4f(uDTV, dtv[0], dtv[1], dtv[2], dtv[3]);
        // M2.5: real injected jitter in render px (0,0 when the jitter opt-in
        // is off); jitter cancellation stays 0 because our MVs are generated
        // from camera offsets that already include the jitter step.
        GL43C.glUniform2f(uJit, CameraJitter.jitCurX(), CameraJitter.jitCurY());
        GL43C.glUniform2f(uMVS, 1f / inW, 1f / inH);
        GL43C.glUniform2f(uDSF, downscaleX, downscaleY);
        GL43C.glUniform2f(uMVJC, 0f, 0f);
        GL43C.glUniform1f(uPreExp, 1f);
        GL43C.glUniform1f(uPrevPreExp, 1f);
        GL43C.glUniform1f(uVSMF, 1f);
        GL43C.glUniform1i(uFIdx, currentFrameIndex);
    }

    private static void ensureMvUbo() {
        if (mvUbo != 0) return;
        mvUbo = GL43C.glGenBuffers();
        GL43C.glBindBuffer(GL43.GL_UNIFORM_BUFFER, mvUbo);
        GL43C.glBufferData(GL43.GL_UNIFORM_BUFFER,
            MotionField.MAX * 8 * 4L, GL15.GL_DYNAMIC_DRAW);
        GL43C.glBindBuffer(GL43.GL_UNIFORM_BUFFER, 0);
    }

    private static void unbindImageUnits() {
        // Sweep any lingering image bindings so later draws (or mods) never
        // see our textures via the image unit namespace.
        for (int unit = 0; unit < 15; unit++) {
            GL43C.glBindImageTexture(unit, 0, 0, false, 0,
                GL43.GL_READ_WRITE, GL11.GL_RGBA8);
        }
    }

    /**
     * Localized error tracer for diagnosing zink's 0x500: drains stale game
     * errors at dispatch start and tags each check point with the pass name.
     */
    private static void checkErr(String where) {
        int err = GL11.glGetError();
        if (err != 0) {
            FsrLog.log("fsr2: GL error 0x" + Integer.toHexString(err) + " at " + where);
        }
    }

    private static void drainErrors() {
        int drained = 0;
        while (GL11.glGetError() != 0 && drained < 32) drained++;
        if (drained > 0) {
            drainedTotal += drained;
            // Quick early lanes are noisy at 1/frame; log the first two
            // events, then every ~5000 drained.
            if (drainReports++ < 2 || drainedTotal / 1000 >= nextDrainK) {
                FsrLog.log("fsr2: drained " + drainedTotal
                    + " stale GL errors at dispatch start (game-generated, ignored)");
                nextDrainK = (int) (drainedTotal / 1000) + 5;
            }
        }
    }

    public static boolean dispatch(int colorTex, int glFbo, int w, int h, int dw, int dh, float sharpness) {
        return dispatch(colorTex, glFbo, w, h, dw, dh, sharpness, false);
    }

    /**
     * One frame of FSR2/FSR3. Input = the downscaled world color texture;
     * on success the upscaled result is published through ResolveOutput,
     * matching the fsr1 path.
     *
     * @param glFbo the GL name of the currently bound (world) framebuffer,
     *        used by the optional DepthProxy (M2c) to blit the depth
     *        attachment; pass 0/none to skip the probe.
     * @param fsr3 run the M4 FSR3 set (adds the SHADING_CHANGE pass and its
     *        RG8 ping-pong; all other passes use the mod's fsr3 sources)
     * @return true on success; false to fall back to fsr1 this frame.
     */
    public static boolean dispatch(int colorTex, int glFbo, int w, int h, int dw, int dh,
            float sharpness, boolean fsr3) {
        fsr3Mode = fsr3;
        if (!ensureShaders()) return false;
        if (colorTex <= 0 || w <= 0 || h <= 0 || dw <= 0 || dh <= 0) return false;
        try {
            if (w != inW || h != inH || dw != outW || dh != outH) {
                inW = w; inH = h; outW = dw; outH = dh;
                deleteTextures();
                CameraJitter.reset();
                currentFrameIndex = 0;
            }
            ensureTextures();
            drainErrors();
            checkErr("textures-baseline");
            CameraMotion.begin();
            if (CameraMotion.resetRequested()) currentFrameIndex = 0;
            // M3.1 stop-transition reset (moving -> exact still): ACCUMULATE
            // forces reactiveFactor = 0 at camStillExact (line 300), so
            // motion-path-blurred history can never be washed out while the
            // camera rests — it only clears on zoom change (reset). Fire the
            // same reset the instant the camera settles; the still frames
            // then accumulate from an exact-position basis and re-sharpen.
            boolean stillExactNow = CameraMotion.stillExact();
            if (!lastStillExact && stillExactNow) {
                currentFrameIndex = 0;
                FsrLog.log("fsr2: stop-reset (moving -> still)");
            }
            lastStillExact = stillExactNow;
            boolean mvGenRun = CameraMotion.hasPrev();

            // ---- save GL state the mod's dispatch also touches (units 0..15
            // to be friendlier to PZ than the mod's 6) + scissor.
            int prevProg = GL43C.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int prevActive = GL43C.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int[] prevTex = new int[16];
            for (int i = 0; i < prevTex.length; i++) {
                GL43C.glActiveTexture(GL13.GL_TEXTURE0 + i);
                prevTex[i] = GL43C.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            int prevDrawFbo = GL43C.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int prevReadFbo = GL43C.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int[] prevViewport = new int[4];
            GL43C.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
            boolean scissorOn = false;
            try { scissorOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST); } catch (Throwable ignored) {}

            GL43C.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL43C.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);

            // ---- M2c DepthProxy (optional): probe once, then blit the world
            // depth attachment into an R32F proxy when the console toggle is
            // on. Off = today's neutral-1x1-constant-depth behavior, byte
            // identical.
            DepthProxy.prepare(glFbo, w, h);
            boolean useDepth = DepthProxy.useDepth();
            int depthTexIn = useDepth ? DepthProxy.inputTex() : neutralDepthTex;
            float[] dtv = DepthProxy.dtv();

            // ---- MV path (M2): reproject with the synthetic iso ortho VP pair
            // when the camera state chains; clear otherwise (first frame /
            // zoom change / pause gap).
            if (mvGenRun) {
                IsoViewProjection.compose(
                    CameraMotion.curOffX(), CameraMotion.curOffY(),
                    CameraMotion.prevOffX(), CameraMotion.prevOffY(),
                    0f, 0f, 0f, 0f,
                    inW, inH, CameraMotion.nativeW(), CameraMotion.nativeH());
                GL43C.glUseProgram(mvGen.id);
                GL43C.glUniform2i(mvGen.u("uRenderSize"), inW, inH);
                GL43C.glUniformMatrix4fv(mvGen.u("uInvCurrVP"), false, IsoViewProjection.INVCURR);
                GL43C.glUniformMatrix4fv(mvGen.u("uPrevVP"), false, IsoViewProjection.PREV);
                GL43C.glUniform2f(mvGen.u("uJitter"), 0f, 0f);
                GL43C.glUniform2f(mvGen.u("uPrevJitter"), 0f, 0f);
                GL43C.glUniform1f(mvGen.u("uFarDepth"), 1.0f);
                GL43C.glUniform4fv(mvGen.u("uDeviceToViewDepth"), DTV_NEUTRAL);
                GL43C.glUniform1f(mvGen.u("uFarViewZ"), 0f);
                GL43C.glUniform1i(mvGen.u("uUseDepth"), 0);
                GL43C.glUniform3f(mvGen.u("uCameraDelta"),
                    IsoViewProjection.OFFSIGN * CameraMotion.deltaX(),
                    IsoViewProjection.OFFSIGN * CameraMotion.deltaY(), 0f);
                GL43C.glUniform1i(mvGen.u("uZZeroToOne"), 0);
                GL43C.glUniform1i(mvGen.u("uFlipY"), 0);
                GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 0);
                GL43C.glBindTexture(GL11.GL_TEXTURE_2D, neutralDepthTex);
                GL43C.glBindImageTexture(1, motionVectorTex, 0, false, 0,
                    GL43.GL_WRITE_ONLY, GL30.GL_RG16F);
                GL43C.glDispatchCompute((inW + 7) / 8, (inH + 7) / 8, 1);
                checkErr("mv-gen");
            } else {
                if (clearMvFbo == 0) clearMvFbo = GL43C.glGenFramebuffers();
                GL43C.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, clearMvFbo);
                GL43C.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, motionVectorTex, 0);
                if (scissorOn) GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL43C.glClearBufferfv(GL11.GL_COLOR, 0, new float[]{0f, 0f, 0f, 0f});
                if (scissorOn) GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL43C.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
                checkErr("mv-clear");
            }

            // ---- entity motion override (M2b): ObjectMotion rects overwrite
            // camera MVs inside their rects. Fetch barrier comes right after:
            // RECONSTRUCT samples the combined MV texture next.
            int mvEnt = MotionField.refresh(inW, inH);
            if (mvEnt > 0) {
                ensureMvUbo();
                // indexed binding-3 save for UBO interleaving safety
                int[] prevUbo3 = new int[1];
                try {
                    GL43C.glGetIntegeri_v(GL43.GL_UNIFORM_BUFFER, 3, prevUbo3);
                } catch (Throwable unused) {
                    prevUbo3[0] = 0;
                }
                GL43C.glBindBuffer(GL43.GL_UNIFORM_BUFFER, mvUbo);
                GL43C.glBufferSubData(GL43.GL_UNIFORM_BUFFER, 0, MotionField.data());
                GL43C.glBindBuffer(GL43.GL_UNIFORM_BUFFER, 0);
                GL43C.glBindBufferBase(GL43.GL_UNIFORM_BUFFER, 3, mvUbo);
                GL43C.glUseProgram(mvOverride.id);
                GL43C.glUniform1i(mvOverride.u("uCount"), mvEnt);
                GL43C.glBindImageTexture(1, motionVectorTex, 0, false, 0,
                    GL43.GL_WRITE_ONLY, GL30.GL_RG16F);
                GL43C.glDispatchCompute((inW + 7) / 8, (inH + 7) / 8, 1);
                GL43C.glBindBufferBase(GL43.GL_UNIFORM_BUFFER, 3, prevUbo3[0]);
                checkErr("mv-override n=" + mvEnt);
            }
            GL43C.glMemoryBarrier(GL43.GL_TEXTURE_FETCH_BARRIER_BIT);
            checkErr("mv-clear");

            float downscaleX = (float) inW / outW;
            float downscaleY = (float) inH / outH;

            // ---- clear reconstructedPrevDepth (R32UI -> 0)
            GL44C.glClearTexImage(reconstructedPrevDepthTex, 0,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
            checkErr("rpd-clear");

            // ---- M4 fsr3 SHADING_CHANGE: 8x8 tile luma-ratio vs the previous
            // frame's tile map; feeds ACCUMULATE binding 8 (confidence damp)
            // and DEPTH_CLIP binding 6 (declared, unused in the mod's port).
            // Runs after the MV path like fsr-reloaded's FSR3Pipeline.
            if (fsr3Mode) {
                GL43C.glUseProgram(shadingChange.id);
                GL43C.glUniform2i(shadingChange.u("uRenderSize"), inW, inH);
                GL43C.glUniform1f(shadingChange.u("uThreshold"), 0.5f);
                GL43C.glUniform1f(shadingChange.u("uFarDepth"), DepthProxy.farDepth());
                GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 0);
                GL43C.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
                GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 1);
                GL43C.glBindTexture(GL11.GL_TEXTURE_2D, depthTexIn);
                GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 2);
                if (hasPrevShading) {
                    GL43C.glBindTexture(GL11.GL_TEXTURE_2D, previousShadingChangeTex);
                } else {
                    GL43C.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                }
                GL43C.glBindImageTexture(3, shadingChangeTex, 0, false, 0,
                    GL43.GL_WRITE_ONLY, GL30.GL_RG8);
                GL43C.glDispatchCompute((inW + 7) / 8, (inH + 7) / 8, 1);
                GL43C.glMemoryBarrier(GL43.GL_TEXTURE_FETCH_BARRIER_BIT);
                hasPrevShading = true;
                checkErr("shading-change");
            }

            // ---- RECONSTRUCT
            GL43C.glUseProgram(reconstruct.id);
            setCommonUniforms(reconstruct,
                reconstruct.u("uRenderSize"), reconstruct.u("uDisplaySize"),
                reconstruct.u("uMaxRenderSize"), reconstruct.u("uDeviceToViewDepth"),
                reconstruct.u("uJitter"), reconstruct.u("uMotionVectorScale"),
                reconstruct.u("uDownscaleFactor"), reconstruct.u("uMotionVectorJitterCancellation"),
                reconstruct.u("uPreExposure"), reconstruct.u("uPreviousFramePreExposure"),
                reconstruct.u("uViewSpaceToMetersFactor"), reconstruct.u("uFrameIndex"),
                dtv, downscaleX, downscaleY);
            GL43C.glUniform1f(reconstruct.u("uFarDepth"), DepthProxy.farDepth());
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 0);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 1);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, motionVectorTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 2);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, depthTexIn);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 3);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL43C.glBindImageTexture(4, reconstructedPrevDepthTex, 0, false, 0,
                GL43.GL_READ_WRITE, GL30.GL_R32UI);
            GL43C.glBindImageTexture(5, dilatedDataTex, 0, false, 0,
                GL43.GL_WRITE_ONLY, GL30.GL_RGBA16F);
            GL43C.glDispatchCompute((inW + 7) / 8, (inH + 7) / 8, 1);
            GL43C.glMemoryBarrier(GL43.GL_TEXTURE_FETCH_BARRIER_BIT);
            checkErr("reconstruct");

            // ---- M3 AUTO_REACTIVE: prev-color diff term (FSR4 T&C port).
            // Runs after RECONSTRUCT (needs the dilated MV field), before
            // DEPTH_CLIP, which maxes the R8 mask into its reactive output
            // through uReactiveMask (binding 8). Skipped while no prev color
            // frame exists yet (first frame / after size change).
            boolean arRun = autoReactiveOn == 1 && hasPrevColor && prevColorTex != 0;
            if (arRun) {
                GL43C.glUseProgram(autoReactive.id);
                GL43C.glUniform2i(autoReactive.u("uRenderSize"), inW, inH);
                GL43C.glUniform1i(autoReactive.u("uHavePrev"), 1);
                GL43C.glUniform1i(autoReactive.u("uUseDepth"), useDepth ? 1 : 0);
                GL43C.glUniform1i(autoReactive.u("uCamMotion"),
                    CameraMotion.still() ? 0 : 1);
                GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 0);
                GL43C.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
                GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 1);
                GL43C.glBindTexture(GL11.GL_TEXTURE_2D, prevColorTex);
                GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 2);
                GL43C.glBindTexture(GL11.GL_TEXTURE_2D, dilatedDataTex);
                GL43C.glBindImageTexture(3, autoReactiveTex, 0, false, 0,
                    GL43.GL_WRITE_ONLY, GL30.GL_R8);
                GL43C.glDispatchCompute((inW + 7) / 8, (inH + 7) / 8, 1);
                GL43C.glMemoryBarrier(GL43.GL_TEXTURE_FETCH_BARRIER_BIT);
                checkErr("auto-reactive");
            }

            // ---- DEPTH_CLIP
            GL43C.glUseProgram(depthClip.id);
            setCommonUniforms(depthClip,
                depthClip.u("uRenderSize"), depthClip.u("uDisplaySize"),
                depthClip.u("uMaxRenderSize"), depthClip.u("uDeviceToViewDepth"),
                depthClip.u("uJitter"), depthClip.u("uMotionVectorScale"),
                depthClip.u("uDownscaleFactor"), depthClip.u("uMotionVectorJitterCancellation"),
                depthClip.u("uPreExposure"), depthClip.u("uPreviousFramePreExposure"),
                depthClip.u("uViewSpaceToMetersFactor"), depthClip.u("uFrameIndex"),
                dtv, downscaleX, downscaleY);
            GL43C.glUniform1f(depthClip.u("uExposureVal"), 1.0f);
            GL43C.glUniform1f(depthClip.u("uFarDepth"), DepthProxy.farDepth());
            GL43C.glUniform1f(depthClip.u("uFarViewZ"), DepthProxy.farViewZ());
            GL43C.glUniform1i(depthClip.u("uUseDepth"), useDepth ? 1 : 0);
            // M3: gate for the restored uReactiveMask input; when the pass did
            // not run the bound autoReactiveTex holds the previous frame's
            // mask (or garbage on frame 1), so the gate must be explicit.
            GL43C.glUniform1i(depthClip.u("uAutoReactive"), arRun ? 1 : 0);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 0);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 1);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, depthTexIn);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 2);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, motionVectorTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 3);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, reconstructedPrevDepthTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 4);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, dilatedDataTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 5);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, previousDilatedDataTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 8);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, autoReactiveTex);
            if (fsr3Mode) {
                // fsr3 parity: uShadingChange at binding 6 (declared in the
                // mod's port; it samples nothing there but keep the bind)
                GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 6);
                GL43C.glBindTexture(GL11.GL_TEXTURE_2D, shadingChangeTex);
            }
            GL43C.glBindImageTexture(10, preparedInputColorTex, 0, false, 0,
                GL43.GL_WRITE_ONLY, GL30.GL_RGBA16F);
            GL43C.glBindImageTexture(11, dilatedReactiveMasksTex, 0, false, 0,
                GL43.GL_WRITE_ONLY, GL30.GL_RG8);
            GL43C.glDispatchCompute((inW + 7) / 8, (inH + 7) / 8, 1);
            GL43C.glMemoryBarrier(GL43.GL_TEXTURE_FETCH_BARRIER_BIT);
            checkErr("depth-clip");
            GL43C.glUseProgram(0);

            // ---- clear newLocks (R8 -> 0)
            GL44C.glClearTexImage(newLocksTex, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            checkErr("newlocks-clear");

            // ---- LOCK
            GL43C.glUseProgram(lock.id);
            GL43C.glUniform2i(lock.u("uRenderSize"), inW, inH);
            GL43C.glUniform2i(lock.u("uDisplaySize"), outW, outH);
            GL43C.glUniform2f(lock.u("uDownscaleFactor"), downscaleX, downscaleY);
            GL43C.glUniform2f(lock.u("uJitter"), CameraJitter.jitCurX(), CameraJitter.jitCurY());
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 0);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, dilatedDataTex);
            GL43C.glBindImageTexture(1, reconstructedPrevDepthTex, 0, false, 0,
                GL43.GL_WRITE_ONLY, GL30.GL_R32UI);
            GL43C.glBindImageTexture(2, newLocksTex, 0, false, 0,
                GL43.GL_WRITE_ONLY, GL30.GL_R8);
            GL43C.glDispatchCompute((inW + 7) / 8, (inH + 7) / 8, 1);
            GL43C.glMemoryBarrier(GL43.GL_TEXTURE_FETCH_BARRIER_BIT);
            checkErr("lock");

            // ---- ACCUMULATE (display res)
            GL43C.glUseProgram(accumulate.id);
            setCommonUniforms(accumulate,
                accumulate.u("uRenderSize"), accumulate.u("uDisplaySize"),
                accumulate.u("uMaxRenderSize"), accumulate.u("uDeviceToViewDepth"),
                accumulate.u("uJitter"), accumulate.u("uMotionVectorScale"),
                accumulate.u("uDownscaleFactor"), accumulate.u("uMotionVectorJitterCancellation"),
                accumulate.u("uPreExposure"), accumulate.u("uPreviousFramePreExposure"),
                accumulate.u("uViewSpaceToMetersFactor"), accumulate.u("uFrameIndex"),
                dtv, downscaleX, downscaleY);
            GL43C.glUniform1f(accumulate.u("uExposureVal"), 1.0f);
            GL43C.glUniform1f(accumulate.u("uFarDepth"), DepthProxy.farDepth());
            GL43C.glUniform1f(accumulate.u("uFarViewZ"), DepthProxy.farViewZ());
            GL43C.glUniform1i(accumulate.u("uDebugMode"), 0);
            GL43C.glUniform1i(accumulate.u("uUseDepth"), useDepth ? 1 : 0);
            GL43C.glUniform1i(accumulate.u("uCamStill"), CameraMotion.still() ? 1 : 0);
            // M3.2 camera-motion reactive floor (fsr-reloaded camReactive
            // port): jitter-excluded speed scaled to render px/frame, ramped
            // and gain-multiplied. At exact still ACCUMULATE zeroes the
            // reactiveFactor anyway (stop-reset handles that case).
            float vNative = Math.max(Math.abs(CameraMotion.deltaRelX()),
                Math.abs(CameraMotion.deltaRelY()));
            float vRenderPx = vNative * downscaleX;
            float camReactive = camReactiveGain <= 0 ? 0f
                : clamp((vRenderPx - 0.3f) / 2.0f * (float) camReactiveGain, 0f, 0.9f);
            GL43C.glUniform1f(accumulate.u("uCamReactive"), camReactive);
            GL43C.glUniform1i(accumulate.u("uCamStillExact"), CameraMotion.stillExact() ? 1 : 0);
            GL43C.glUniform1i(accumulate.u("uDebugMode"), debugMode);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 0);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 1);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, depthTexIn);
            // unit 2 deliberately NOT rebound (uExposure left as-is, verbatim mod port)
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 3);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, preparedInputColorTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 4);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, dilatedDataTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 5);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, dilatedReactiveMasksTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 6);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, internalUpscaledTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 7);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, lockStatusHistoryTex);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 9);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, newLocksTex);
            if (fsr3Mode) {
                // fsr3 parity: uShadingChange at binding 8 (tile diff, G ch)
                GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 8);
                GL43C.glBindTexture(GL11.GL_TEXTURE_2D, shadingChangeTex);
            }
            GL43C.glBindImageTexture(10, internalUpscaledWriteTex, 0, false, 0,
                GL43.GL_WRITE_ONLY, GL30.GL_R11F_G11F_B10F);
            GL43C.glBindImageTexture(11, lockStatusCurrTex, 0, false, 0,
                GL43.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL43C.glBindImageTexture(14, upscaledOutputTex, 0, false, 0,
                GL43.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL43C.glDispatchCompute((outW + 15) / 16, (outH + 3) / 4, 1);
            GL43C.glMemoryBarrier(GL43.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            checkErr("accumulate");

            // ---- RCAS (optional)
            int rcasSharpness = Math.max(0, Math.min(1, Math.round(clamp(sharpness, 0f, 1f) * 7f)));
            if (rcasSharpness > 0) {
                GL43C.glUseProgram(rcas.id);
                GL43C.glUniform4ui(rcas.u("uRcasConfig"), rcasSharpness, 0, 0, 0);
                GL43C.glUniform2i(rcas.u("uOutputSize"), outW, outH);
                GL43C.glBindImageTexture(0, upscaledOutputTex, 0, false, 0,
                    GL43.GL_READ_WRITE, GL11.GL_RGBA8);
                GL43C.glDispatchCompute((outW + 15) / 16, (outH + 3) / 4, 1);
                GL43C.glBindImageTexture(0, 0, 0, false, 0,
                    GL43.GL_READ_WRITE, GL11.GL_RGBA8);
                checkErr("rcas");
            }
            unbindImageUnits();
            checkErr("image-sweep");

            // ---- M3: capture this frame's input color as next frame's prev
            // color (FSR4 MV_GEN's Java-side glCopyImageSubData practice).
            // Needs format compatibility, so prevColorTex is allocated with
            // the internal format queried from colorTex (RGBA8 expected);
            // on any unexpected format auto-reactive stays disabled.
            if (autoReactiveOn == 1 && !prevColorFmtFail) {
                if (prevColorTex == 0) {
                    int fmt = queryInternalFormat(colorTex);
                    if (fmt == GL11.GL_RGBA8 || fmt == GL30.GL_RGBA16F) {
                        prevColorTex = createTex2D(inW, inH, fmt,
                            GL11.GL_LINEAR, GL11.GL_LINEAR);
                        FsrLog.log("fsr2: prevColorTex " + inW + "x" + inH
                            + " fmt=0x" + Integer.toHexString(fmt).toUpperCase());
                    } else {
                        prevColorFmtFail = true;
                        FsrLog.log("fsr2: unexpected color fmt 0x"
                            + (fmt > 0 ? Integer.toHexString(fmt).toUpperCase() : "?")
                            + ", auto-reactive disabled");
                    }
                }
                if (prevColorTex != 0) {
                    GL43C.glCopyImageSubData(colorTex, GL11.GL_TEXTURE_2D,
                        0, 0, 0, 0, prevColorTex, GL11.GL_TEXTURE_2D,
                        0, 0, 0, 0, inW, inH, 1);
                    hasPrevColor = true;
                }
            }

            // ---- ping-pong swaps
            int tmp = internalUpscaledTex;
            internalUpscaledTex = internalUpscaledWriteTex;
            internalUpscaledWriteTex = tmp;
            int tmp2 = lockStatusHistoryTex;
            lockStatusHistoryTex = lockStatusCurrTex;
            lockStatusCurrTex = tmp2;
            int tmp3 = previousDilatedDataTex;
            previousDilatedDataTex = dilatedDataTex;
            dilatedDataTex = tmp3;
            if (fsr3Mode) {
                int tmp4 = previousShadingChangeTex;
                previousShadingChangeTex = shadingChangeTex;
                shadingChangeTex = tmp4;
            }
            currentFrameIndex++;
            // FG (FgPresent) freshness marker: bumped only by a completed
            // dispatch. A present without a matching bump means the world
            // pipeline did not produce a new frame (loading/pause/cut) --
            // the MV field and camera delta would be stale, so the FG must
            // fall back to a duplicate for that present.
            dispatchEpoch++;

            // ---- GL error check (cheap, once per frame; per-pass traces
            // above already tag any 0x5xx source)
            int err = GL11.glGetError();
            if (err != 0) {
                glErrorRuns++;
                FsrLog.log("fsr2: GL error 0x" + Integer.toHexString(err)
                    + " after dispatch (run " + glErrorRuns + ")");
                if (glErrorRuns >= 30) { throw new IllegalStateException("persistent GL errors"); }
            }

            // ---- restore state
            GL43C.glUseProgram(prevProg);
            for (int i = 0; i < prevTex.length; i++) {
                GL43C.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL43C.glBindTexture(GL11.GL_TEXTURE_2D, prevTex[i]);
            }
            GL43C.glActiveTexture(prevActive);
            GL43C.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            GL43C.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL43C.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            if (dispatchOk == 0) {
                reconstruct.reportMissing("RECONSTRUCT");
                depthClip.reportMissing("DEPTH_CLIP");
                lock.reportMissing("LOCK");
                accumulate.reportMissing("ACCUMULATE");
                rcas.reportMissing("RCAS");
                autoReactive.reportMissing("AUTO_REACTIVE");
            }

            // ---- publish (mirrors Resolve's ResolveOutput.set)
            ResolveOutput.set(upscaledOutputTex, outW, outH);
            CameraMotion.end();

            dispatchOk++;
            if (dispatchOk <= 3) {
                FsrLog.log("fsr2 dispatch #" + dispatchOk + " in=" + inW + "x" + inH
                    + " out=" + outW + "x" + outH + " frame=" + (currentFrameIndex - 1));
            }
            return true;
        } catch (Throwable t) {
            dispatchFail++;
            FsrLog.log("fsr2 dispatch failed: " + t);
            try { destroy(); } catch (Throwable ignored) {}
            fault(t);
            return false;
        }
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** GL_TEXTURE_INTERNAL_FORMAT of a texture (needs the active context). */
    private static int queryInternalFormat(int tex) {
        try {
            int prevActive = GL43C.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 15);
            int prevBind = GL43C.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            int fmt = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                GL11.GL_TEXTURE_INTERNAL_FORMAT);
            GL43C.glBindTexture(GL11.GL_TEXTURE_2D, prevBind);
            GL43C.glActiveTexture(prevActive);
            return fmt;
        } catch (Throwable t) {
            FsrLog.log("fsr2: internal-format query failed: " + t);
            return 0;
        }
    }

    /** ACCUMULATE debug visualizer (see ACCUMULATE_CS.glsl lines 410-456). */
    public static volatile int debugMode;

    public static void setDebugMode(int v) {
        debugMode = Math.max(0, Math.min(10, v));
        FsrLog.log("fsr2 debug mode = " + debugMode);
    }

    public static String status() {
        lastStatus = "fsr2: " + (available ? (compiled ? (fsr3Mode ? "ready(fsr3)" : "ready") : "idle") : "faulted/fallback")
            + " ok=" + dispatchOk + " fail=" + dispatchFail
            + " frame=" + currentFrameIndex
            + " sizes=" + inW + "x" + inH + "->" + outW + "x" + outH
            + " cam=(d" + fmt1(CameraMotion.deltaX()) + "," + fmt1(CameraMotion.deltaY())
            + ") still=" + (CameraMotion.still() ? 1 : 0)
            + "/" + (CameraMotion.stillExact() ? 1 : 0)
            + " mv=" + (CameraMotion.hasPrev() ? "reproject" : "clear")
            + " ent=" + MotionField.entries()
            + " ar=" + (autoReactiveOn == 1 ? "on" : "off")
            + (hasPrevColor ? "+prev" : "")
            + " crg=" + camReactiveGain
            + " " + CameraJitter.statusLine()
            + " " + DepthProxy.statusLine();
        return lastStatus;
    }

    private static String fmt1(float v) {
        if (Math.abs(v) >= 10f) return String.valueOf(Math.round(v));
        return String.format("%.2f", v);
    }

    private Fsr2Compute() {}
}
