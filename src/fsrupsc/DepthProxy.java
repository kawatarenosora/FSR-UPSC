package fsrupsc;

import java.util.HashMap;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL43C;

/**
 * M2c DepthProxy: turn the world FBO's depth attachment into an FSR2 input.
 *
 * <p>The PZ world buffer carries a depth attachment (used for model sorting),
 * but no public API exposes it as a texture. So, at the resolve point:
 *
 * <ol>
 *   <li><b>probe</b> it once per FBO id:
 *       {@code glGetFramebufferAttachmentParameteriv(GL_DEPTH_ATTACHMENT,
 *       OBJECT_TYPE/NAME)} + internal format classification;</li>
 *   <li><b>blit</b> every dispatch:
 *       {@code glBlitFramebuffer(GL_DEPTH_BUFFER_BIT)} world FBO -> our own
 *       depth-texture FBO (identical internal format, the blit-match rule);</li>
 *   <li><b>convert</b> with {@code Fsr2Shaders.DEPTH_PROXY} into a monotonic
 *       R32F device-depth proxy ({@code viewZ = 1/d}, background = far);</li>
 *   <li>RECONSTRUCT/DEPTH_CLIP/ACCUMULATE bind the proxy with
 *       {@code uUseDepth=1}, {@code uDeviceToViewDepth=(0,1,0,0)},
 *       {@code uFarDepth=1/50}, {@code uFarViewZ=50}.</li>
 * </ol>
 *
 * <p>Policy:
 * <ul>
 *   <li>off by default; {@code FsrControl.fsr2Depth(0/1/2)} switches it for
 *       the session (1 = auto, needs a depth-write-healthy readback; 2 =
 *       force on even when the readback looked uniform);</li>
 *   <li>a one-time two-row depth readback classifies reversed-z (flip) and
 *       a "depth attached but never written" uniform buffer (stays
 *       {@code uUseDepth=0});</li>
 *   <li>any failure inside the proxy degrades this frame/session to
 *       {@code uUseDepth=0} 窶・it never calls {@code Fsr2Compute.fault()},
 *       so the compute path and fsr1 keep running;</li>
 *   <li>no per-frame readback after detection (no per-frame sync stall).</li>
 * </ul>
 */
final class DepthProxy {

    /** Proxy transform constants (mirror DEPTH_PROXY_CS.glsl). */
    static final float PROXY_V0 = 1f;
    static final float PROXY_V1 = 50f;

    /** Mode of the depth attachment probe. */
    private static final int MODE_UNPROBED = 0;
    private static final int MODE_NONE = 1;      // no usable depth attachment
    private static final int MODE_READY = 2;     // blit + copy available
    private static final int MODE_FAIL = 3;      // runtime failure, session off

    private static final float[] DTV_NEUTRAL = {0.5f, 0f, 1f, 1f};
    private static final float[] DTV_PROXY = {0f, 1f, 0f, 0f};

    private static int mode;
    private static int forcedLevel;              // 0=off 1=on(auto) 2=on(force)
    private static boolean healthy = true;
    private static int flip;                     // 1 = reversed-z source
    private static int internalFmt;
    private static String probeLine = "unprobed";

    private static int depthTex;                 // blit target (src internal format)
    private static int proxyFbo;
    private static int outTex;                   // converted R32F render-size
    private static int prog;                     // DEPTH_PROXY copy program
    private static boolean detected;
    private static int texW;
    private static int texH;
    private static int probedFbo = -1;

    private static final HashMap<String, Integer> locs = new HashMap<String, Integer>();

    private DepthProxy() {}

    // ------------------------------------------------------------- controls

    /** Console runtime toggle: 0=off 1=on(auto) 2=on(force). */
    static void setLevel(int v) {
        forcedLevel = Math.max(0, Math.min(2, v));
        FsrLog.log("fsr2 depth proxy = "
            + (forcedLevel == 0 ? "off"
                : forcedLevel == 1 ? "on(auto)" : "on(force)"));
    }

    private static boolean requested() {
        return forcedLevel > 0;
    }

    /** uUseDepth for the pipeline this frame. */
    static boolean useDepth() {
        return requested() && mode == MODE_READY && (healthy || forcedLevel >= 2);
    }

    static float farDepth() {
        return useDepth() ? 1f / PROXY_V1 : 1f;
    }

    static float farViewZ() {
        return useDepth() ? PROXY_V1 : 0f;
    }

    static float[] dtv() {
        return useDepth() ? DTV_PROXY : DTV_NEUTRAL;
    }

    /** The converted R32F proxy texture to bind as uInputDepth while on. */
    static int inputTex() {
        return useDepth() ? outTex : 0;
    }

    static String statusLine() {
        String m = mode == MODE_UNPROBED ? "unprobed"
            : mode == MODE_NONE ? "none"
            : mode == MODE_READY ? "ready"
            : mode == MODE_FAIL ? "fail" : "?";
        String lvl = forcedLevel == 0 ? "off" : forcedLevel == 1 ? "auto" : "force";
        return "depth=" + m + " lvl=" + lvl
            + (mode == MODE_READY ? " useDepth=" + (useDepth() ? "1" : "0") : "")
            + " probe=" + probeLine;
    }

    // --------------------------------------------------------------- probe

    /**
     * Probes once per glFbo id; runs the blit + conversion pass only when the
     * depth path is requested and healthy. Throwable-guarded: any problem
     * degrades to uUseDepth=0 for the frame (and usually the session).
     */
    static void prepare(int glFbo, int w, int h) {
        if (glFbo <= 0 || w <= 0 || h <= 0) return;
        if (mode == MODE_UNPROBED || probedFbo != glFbo) {
            if (probedFbo == glFbo && mode == MODE_FAIL) return;
            probe(glFbo);
        }
        if (!useDepth() || mode != MODE_READY) return;
        if (!ensureProgram() || !ensureBuffers(w, h)) {
            mode = MODE_FAIL;
            return;
        }
        try {
            blitAndCopy(glFbo, w, h);
        } catch (Throwable t) {
            modeFail(t);
        }
    }

    private static void modeFail(Throwable t) {
        if (t != null) FsrLog.log("fsr2 depth proxy failed: " + t);
        FsrLog.log("fsr2 depth proxy disabled for this session (uUseDepth=0)");
        mode = MODE_FAIL;
        disposeGl();
    }

    private static void probe(int glFbo) {
        probedFbo = glFbo;
        int prevDraw = 0;
        int prevRead = 0;
        int prevActive = 0;
        int prevTex15 = 0;
        try {
            prevDraw = GL43C.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            prevRead = GL43C.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            prevActive = GL43C.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, glFbo);

            int[] type = new int[1];
            GL30.glGetFramebufferAttachmentParameteriv(GL30.GL_READ_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, type);
            String kind;
            if (type[0] == GL11.GL_NONE) {
                mode = MODE_NONE;
                probeLine = "no depth attachment";
                FsrLog.log("fsr2 depth probe: " + probeLine + " (uUseDepth stays 0)");
                return;
            } else if (type[0] == GL11.GL_TEXTURE) {
                int[] name = new int[1];
                GL30.glGetFramebufferAttachmentParameteriv(GL30.GL_READ_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, name);
                kind = "tex#" + name[0];
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + 15);
                prevTex15 = GL43C.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                GL43C.glBindTexture(GL11.GL_TEXTURE_2D, name[0]);
                int[] fmt = new int[1];
                GL11.glGetTexLevelParameteriv(GL11.GL_TEXTURE_2D, 0,
                    GL30.GL_TEXTURE_INTERNAL_FORMAT, fmt);
                internalFmt = fmt[0];
            } else if (type[0] == GL30.GL_RENDERBUFFER) {
                int[] name = new int[1];
                GL30.glGetFramebufferAttachmentParameteriv(GL30.GL_READ_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, name);
                kind = "rbo#" + name[0];
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name[0]);
                int[] fmt = new int[1];
                GL30.glGetRenderbufferParameteriv(GL30.GL_RENDERBUFFER,
                    GL30.GL_RENDERBUFFER_INTERNAL_FORMAT, fmt);
                internalFmt = fmt[0];
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
            } else {
                mode = MODE_NONE;
                probeLine = "unknown operand type 0x" + Integer.toHexString(type[0]);
                FsrLog.log("fsr2 depth probe: " + probeLine);
                return;
            }

            String fmtName = fmtName(internalFmt);
            if (fmtName == null) {
                mode = MODE_NONE;
                probeLine = kind + " fmt=0x" + Integer.toHexString(internalFmt) + " (unsupported)";
                FsrLog.log("fsr2 depth probe: " + probeLine
                    + " (blit fetch unsupported; uUseDepth stays 0)");
                return;
            }
            // Depth-only or depth+stencil known formats: the destination FBO
            // carries the identical format, so glBlitFramebuffer(GL_DEPTH...)
            // satisfies the exact-match rule. RBO sources work too: the blit
            // copies depth regardless of the attachment being an RBO.
            mode = MODE_READY;
            probeLine = kind + " fmt=" + fmtName;
            FsrLog.log("fsr2 depth probe: " + probeLine
                + " 窶・enable with FsrControl.fsr2Depth(1)");
        } catch (Throwable t) {
            probeLine = "exception: " + t;
            FsrLog.log("fsr2 depth probe failed: " + t);
            mode = MODE_NONE; // probe problems keep the neutral path
        } finally {
            try {
                if (prevActive != 0) GL43C.glActiveTexture(prevActive);
                if (prevTex15 != 0) {
                    GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 15);
                    GL43C.glBindTexture(GL11.GL_TEXTURE_2D, prevTex15);
                    GL43C.glActiveTexture(prevActive != 0 ? prevActive : GL13.GL_TEXTURE0);
                }
                if (prevRead != 0) GL43C.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
                if (prevDraw != 0) GL43C.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            } catch (Throwable ignored) {}
        }
    }

    private static String fmtName(int fmt) {
        switch (fmt) {
            case 0x81A5: return "DEPTH_COMPONENT16";
            case 0x81A6: return "DEPTH_COMPONENT24";
            case 0x81A7: return "DEPTH_COMPONENT32";
            case 0x8CAC: return "DEPTH_COMPONENT32F";
            case 0x88F0: return "DEPTH24_STENCIL8";
            case 0x8CAD: return "DEPTH32F_STENCIL8";
            default: return null;   // e.g. float-stencil variants; not blit-safe here
        }
    }

    // ------------------------------------------------------- blit + convert

    private static boolean ensureProgram() {
        if (prog != 0) return true;
        int id = Gl.compileCompute(Fsr2Shaders.DEPTH_PROXY, "fsr2/DEPTH_PROXY");
        if (id == 0) {
            FsrLog.log("fsr2: DEPTH_PROXY copy compile failed");
            return false;
        }
        prog = id;
        return true;
    }

    private static boolean ensureBuffers(int w, int h) {
        if (depthTex != 0 && texW == w && texH == h) return true;
        disposeGl();
        texW = w;
        texH = h;
        if (internalFmt == 0) return false;

        depthTex = GL43C.glGenTextures();
        GL43C.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        GL43C.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, internalFmt, w, h);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_MODE, GL30.GL_NONE);
        GL43C.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        proxyFbo = GL43C.glGenFramebuffers();
        int prevDraw = GL43C.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL43C.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, proxyFbo);
        GL43C.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,
            GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex, 0);
        int status = GL43C.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
        GL43C.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            FsrLog.log("fsr2 depth proxy: own depth FBO incomplete (0x"
                + Integer.toHexString(status) + ")");
            disposeGl();
            return false;
        }

        outTex = GL43C.glGenTextures();
        GL43C.glBindTexture(GL11.GL_TEXTURE_2D, outTex);
        GL43C.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R32F, w, h);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL43C.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL43C.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return true;
    }

    private static void blitAndCopy(int glFbo, int w, int h) {
        int prevDraw = GL43C.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL43C.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean scissorOn = false;
        try { scissorOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST); } catch (Throwable ignored) {}

        GL43C.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, glFbo);
        GL43C.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, proxyFbo);
        if (scissorOn) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
            GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        int err = GL11.glGetError();
        if (err != 0) {
            FsrLog.log("fsr2 depth proxy: blit GL error 0x"
                + Integer.toHexString(err) + " (disable)");
            if (scissorOn) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL43C.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL43C.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            mode = MODE_FAIL;
            return;
        }

        // One-time classification: read two rows of the RAW blitted depth
        // (proxyFbo is bound for drawing; make it the read source first).
        if (!detected) {
            GL43C.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, proxyFbo);
            detect(w, h);
        }

        // Convert: sample the blitted depth -> monotonic R32F proxy.
        GL43C.glUseProgram(prog);
        GL43C.glUniform1i(u("uFlip"), flip);
        GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 0);
        GL43C.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        GL43C.glBindImageTexture(1, outTex, 0, false, 0,
            GL43.GL_WRITE_ONLY, GL30.GL_R32F);
        GL43C.glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
        GL43C.glMemoryBarrier(GL43.GL_TEXTURE_FETCH_BARRIER_BIT);
        GL43C.glBindImageTexture(1, 0, 0, false, 0, GL43.GL_WRITE_ONLY, GL30.GL_R32F);
        GL43C.glActiveTexture(GL13.GL_TEXTURE0 + 0);
        GL43C.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL43C.glUseProgram(0);

        if (scissorOn) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL43C.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL43C.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
    }

    private static int u(String name) {
        Integer l = locs.get(name);
        if (l == null) {
            l = GL43C.glGetUniformLocation(prog, name);
            locs.put(name, l);
        }
        return l;
    }

    /**
     * Classifies raw hardware depth from 2 full rows (30%/70% height):
     * reversed-z detection and the "attached but never written" uniform case.
     */
    private static void detect(int w, int h) {
        detected = true;
        flip = 0;
        try {
            int rowLen = Math.max(1, w);
            float[] a = new float[rowLen];
            float[] b = new float[rowLen];
            int yA = Math.min(h - 1, h * 3 / 10);
            int yB = Math.min(h - 1, h * 7 / 10);
            GL11.glReadPixels(0, yA, rowLen, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, a);
            GL11.glReadPixels(0, yB, rowLen, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, b);
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            int low = 0;
            int high = 0;
            int total = 0;
            for (float[] row : new float[][]{a, b}) {
                for (float v : row) {
                    if (v != v) continue; // NaN guard
                    if (v < min) min = v;
                    if (v > max) max = v;
                    if (v <= 0.001f) low++;
                    if (v >= 0.999f) high++;
                    total++;
                }
            }
            boolean uniform = (max - min) < 1e-4f;
            healthy = !uniform;
            if (low > high && (low + high) * 2 > total) flip = 1; // reversed-z
            FsrLog.log("fsr2 depth detect: min=" + String.format("%.4f", min)
                + " max=" + String.format("%.4f", max)
                + " low=" + low + " high=" + high
                + " flip=" + flip
                + (uniform ? " UNIFORM (probably never written; stays uUseDepth=0)" : ""));
        } catch (Throwable t) {
            FsrLog.log("fsr2 depth detect failed: " + t);
        }
    }

    // -------------------------------------------------------------- dispose

    private static void disposeGl() {
        try {
            if (depthTex != 0) GL43C.glDeleteTextures(depthTex);
            if (outTex != 0) GL43C.glDeleteTextures(outTex);
            if (proxyFbo != 0) GL43C.glDeleteFramebuffers(proxyFbo);
        } catch (Throwable ignored) {}
        depthTex = 0;
        outTex = 0;
        proxyFbo = 0;
        texW = 0;
        texH = 0;
    }

    static void dispose() {
        disposeGl();
        try {
            if (prog != 0) GL43C.glDeleteProgram(prog);
        } catch (Throwable ignored) {}
        prog = 0;
        locs.clear();
    }
}

