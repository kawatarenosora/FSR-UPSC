package fsrupsc;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * FSR resolve: copy small world region, EASU up, RCAS sharpen,
 * blit back into the world texture. In-place final result, so the
 * composite path needs no changes. Any failure disables the scaler.
 */
public final class Resolve {
    // Mirrors 42/media/shaders/fsr_blit.vert + fsr_easu.frag +
    // fsr_rcas.frag (same math; initializers stripped: illegal pre-420).
    static final String QUAD_VERT =
        "#version 330\n" +
        "layout (location = 0) in vec3 aPosition;\n" +
        "layout (location = 1) in vec4 aColor;\n" +
        "layout (location = 2) in vec2 aUV1;\n" +
        "out vec4 vColor;\n" +
        "out vec2 vUV1;\n" +
        "void main (void)\n" +
        "{\n" +
        // No MVP uniform. A fullscreen quad is already in clip space, and a
        // uniform the compiler cannot see any use of is removed: its value is
        // then never set, defaults to all zeros, and every vertex collapses to
        // gl_Position = (0,0,0,0). That is a degenerate draw -- no error, no
        // pixels, and the output probe reads all zeros while everything else
        // (source texture, FBO, viewport, GL state) looks perfect.
        "    gl_Position = vec4(aPosition.xy, 0.0, 1.0);\n" +
        "    vUV1.xy = aUV1.xy;\n" +
        "    vColor = aColor;\n" +
        "}\n";

    // Mirrors 42/media/shaders/fsr_easu.frag.
    static final String EASU_FRAG =
        "#version 330\n" +
        "uniform sampler2D DIFFUSE;\n" +
        "uniform vec2 InputSize;\n" +
        "uniform vec2 OutputSize;\n" +
        "uniform vec2 Jitter;\n" +
        "in vec4 vColor;\n" +
        "in vec2 vUV1;\n" +
        "float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }\n" +
        "float lanczos2(float x) {\n" +
        "    float ax = abs(x);\n" +
        "    if (ax >= 2.0) return 0.0;\n" +
        "    if (ax < 1e-5) return 1.0;\n" +
        "    float px = 3.14159265 * x;\n" +
        "    return (sin(px) / px) * (sin(px * 0.5) / (px * 0.5));\n" +
        "}\n" +
        "vec3 tap(vec2 inPx) { return texture2D(DIFFUSE, inPx / InputSize, 0.0).rgb; }\n" +
        "float tapIdx(float i) { return i < 0.5 ? -1.5 : (i < 1.5 ? -0.5 : (i < 2.5 ? 0.5 : 1.5)); }\n" +
        "void main()\n" +
        "{\n" +
        "    vec2 outPx = vUV1 * OutputSize;\n" +
        "    vec2 ratio = InputSize / OutputSize;\n" +
        "    vec2 pp = outPx * ratio + Jitter;\n" +
        "    vec2 base = floor(pp - 0.5) + 0.5;\n" +
        "    float l00 = luma(tap(base));\n" +
        "    float l10 = luma(tap(base + vec2(1.0, 0.0)));\n" +
        "    float l20 = luma(tap(base + vec2(2.0, 0.0)));\n" +
        "    float l30 = luma(tap(base + vec2(3.0, 0.0)));\n" +
        "    float l01 = luma(tap(base + vec2(0.0, 1.0)));\n" +
        "    float l11 = luma(tap(base + vec2(1.0, 1.0)));\n" +
        "    float l21 = luma(tap(base + vec2(2.0, 1.0)));\n" +
        "    float l31 = luma(tap(base + vec2(3.0, 1.0)));\n" +
        "    float l02 = luma(tap(base + vec2(0.0, 2.0)));\n" +
        "    float l12 = luma(tap(base + vec2(1.0, 2.0)));\n" +
        "    float l22 = luma(tap(base + vec2(2.0, 2.0)));\n" +
        "    float l32 = luma(tap(base + vec2(3.0, 2.0)));\n" +
        "    float l03 = luma(tap(base + vec2(0.0, 3.0)));\n" +
        "    float l13 = luma(tap(base + vec2(1.0, 3.0)));\n" +
        "    float l23 = luma(tap(base + vec2(2.0, 3.0)));\n" +
        "    float l33 = luma(tap(base + vec2(3.0, 3.0)));\n" +
        "    float gx = (l20 + 2.0 * l21 + l22) - (l00 + 2.0 * l01 + l02);\n" +
        "    float gy = (l02 + 2.0 * l12 + l22) - (l00 + 2.0 * l10 + l20);\n" +
        "    float gl = length(vec2(gx, gy));\n" +
        "    vec2 edge = vec2(1.0, 0.0);\n" +
        "    float strength = 0.0;\n" +
        "    if (gl > 1e-4) { edge = vec2(-gy, gx) / gl; strength = clamp(gl * 1.5, 0.0, 1.0); }\n" +
        "    vec2 grad = vec2(-edge.y, edge.x);\n" +
        "    float foot = min(min(ratio.x, ratio.y), 1.0);\n" +
        "    float wcs = mix(1.0, 0.55, strength);\n" +
        "    vec3 acc = vec3(0.0);\n" +
        "    vec3 mn = vec3(1e9);\n" +
        "    vec3 mx = vec3(-1e9);\n" +
        "    float wsum = 0.0;\n" +
        "    for (int ai = 0; ai < 4; ai++) {\n" +
        "        float a = tapIdx(float(ai));\n" +
        "        for (int ci = 0; ci < 4; ci++) {\n" +
        "            float c = tapIdx(float(ci));\n" +
        "            vec2 pos = pp + (a * edge + c * grad) * foot;\n" +
        "            float w = lanczos2(a) * lanczos2(c / wcs);\n" +
        "            vec3 t = tap(pos);\n" +
        "            acc += t * w;\n" +
        "            mn = min(mn, t);\n" +
        "            mx = max(mx, t);\n" +
        "            wsum += w;\n" +
        "        }\n" +
        "    }\n" +
        "    vec3 v = acc / max(wsum, 1e-6);\n" +
        "    gl_FragColor = vec4(clamp(v, mn, mx), 1.0);\n" +
        "}\n";

    // Mirrors 42/media/shaders/fsr_rcas.frag.
    static final String RCAS_FRAG =
        "#version 330\n" +
        "uniform sampler2D DIFFUSE;\n" +
        "uniform vec2 InputSize;\n" +
        "uniform float Sharp;\n" +
        "in vec4 vColor;\n" +
        "in vec2 vUV1;\n" +
        "float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }\n" +
        "void main()\n" +
        "{\n" +
        "    vec2 px = vec2(1.0) / InputSize;\n" +
        "    vec2 uv = vUV1;\n" +
        "    vec3 b = texture2D(DIFFUSE, uv, 0.0).rgb;\n" +
        "    vec3 n = texture2D(DIFFUSE, uv + vec2(0.0, -px.y), 0.0).rgb;\n" +
        "    vec3 s = texture2D(DIFFUSE, uv + vec2(0.0, px.y), 0.0).rgb;\n" +
        "    vec3 w = texture2D(DIFFUSE, uv + vec2(-px.x, 0.0), 0.0).rgb;\n" +
        "    vec3 e = texture2D(DIFFUSE, uv + vec2(px.x, 0.0), 0.0).rgb;\n" +
        "    vec3 d0 = texture2D(DIFFUSE, uv + vec2(-px.x, -px.y), 0.0).rgb;\n" +
        "    vec3 d1 = texture2D(DIFFUSE, uv + vec2(px.x, -px.y), 0.0).rgb;\n" +
        "    vec3 d2 = texture2D(DIFFUSE, uv + vec2(-px.x, px.y), 0.0).rgb;\n" +
        "    vec3 d3 = texture2D(DIFFUSE, uv + vec2(px.x, px.y), 0.0).rgb;\n" +
        "    vec3 mn3 = min(min(min(b, n), min(s, w)), min(min(e, d0), min(d1, min(d2, d3))));\n" +
        "    vec3 mx3 = max(max(max(b, n), max(s, w)), max(max(e, d0), max(d1, max(d2, d3))));\n" +
        "    float gate = smoothstep(1.0 / 255.0, 8.0 / 255.0, luma(mx3) - luma(mn3));\n" +
        "    float k = clamp(Sharp, 0.0, 1.0) * gate;\n" +
        "    vec3 blur = (n + s + w + e) * 0.25;\n" +
        "    vec3 v = b * (1.0 + k) - blur * k;\n" +
        "    gl_FragColor = vec4(clamp(v, mn3, mx3), 1.0);\n" +
        "}\n";

    private static int easuProg;
    private static int rcasProg;
    private static int quad;
    private static long frameCount;
    private static int smallTex;
    private static int smallFbo;
    private static int smallW;
    private static int smallH;
    private static int fullTexA;
    private static int fullFboA;
    private static int fullTexB;
    private static int fullFboB;
    private static int fullW;
    private static int fullH;
    private static boolean failed;

    // ------------------------------------------------------------------
// Preparation happens on the RENDER thread ("main"), never at the
// composite. MultiTextureFBO2#render is reached only from the game
// thread, and compiling the FSR programs there killed the JVM inside
// glCreateShader with no hs_err. The composite itself only draws with
// objects that were already built here.
// ------------------------------------------------------------------

private static volatile boolean ready;

    /** True once the programs and render targets exist and match the geometry. */
    public static boolean isReady() { return ready && !failed; }

    /**
     * Render-thread ("main") entry, called from the viewport funnel. Builds
     * the FSR programs, the quad VAO and the intermediate targets. Idempotent
     * and cheap once ready.
     */
    public static boolean ensureReady(int outW, int outH) {
        if (failed || ready) return ready;
        if (!fsrupsc.FsrHooks.isRenderThread()) return false;
        try {
            // Derive the input with the same function the viewport funnel uses,
            // so the render viewport and the resolve source cannot disagree by a
            // rounding step. Computing it as round(outW * scale) here produced
            // 1600x900 where the funnel produced 1600x896.
            int[] in = RenderScale.scaledRect(outW, outH);
            int inW = in[0];
            int inH = in[1];
            crumb("prepare on " + Thread.currentThread().getName()
                + " out=" + outW + "x" + outH + " in=" + inW + "x" + inH);
            if (!ensurePrograms(true)) return fail("programs");
            if (!ensureTargets(inW, inH, outW, outH)) return fail("targets");
            cacheUniforms(easuProg, easuLoc, true);
            cacheUniforms(rcasProg, rcasLoc, false);
            ready = true;
            crumb("prepared easu=" + easuProg + " rcas=" + rcasProg + " quad=" + quad
                + " small=" + smallW + "x" + smallH + " full=" + fullW + "x" + fullH);
            return true;
        } catch (Throwable t) {
            return fail("prepare: " + t);
        }
    }

    /**
     * Read-back probe, trace frames only.
     *
     * Reads a few pixels from the world texture at the far corners of the
     * region we just wrote. Answers the one question the logs could not:
     * did the upscale actually land in the texture the composite samples?
     *   all zero  -> the write went nowhere (wrong FBO/texture)
     *   non zero  -> the write is there and the composite reads something else
     */
    private static void verifyOutput(int outW, int outH) {
        try {
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain */ }
            int fbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int prog = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

            int selfTest = probeSelfTest(outW, outH);
            // Isolate rasterisation from sampling: draw a constant colour with
            // no texture fetch at all. Non-zero output here means the VAO, the
            // program and the FBO all work, and the fault is in the EASU/RCAS
            // sampling math. Zero means the draw never rasterised.
            probeConstantDraw(fullFboB, outW, outH);

            // Completeness of the world FBO. An incomplete FBO turns every
            // draw into a silent no-op: no error, no pixels -- exactly the
            // symptom. Self-test above proves the probe works, so this is
            // the last unchecked precondition.
            int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fullFboB);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            // The valid query is GL_DRAW_BUFFER / GL_DRAW_BUFFER0. GL_COLOR_ATTACHMENT0
            // (0x8CE0) is a draw-buffer enum, not a glGetInteger query: asking
            // for it raises GL_INVALID_ENUM and yields 0, which reads exactly
            // like "no colour attachment enabled" and sent the previous
            // diagnosis down the wrong path entirely.
            int drawBuf = GL11.glGetInteger(GL30.GL_DRAW_BUFFER);
            crumb("fboState[id=" + fullFboB
                + " status=0x" + Integer.toHexString(status)
                + " complete=0x" + Integer.toHexString(GL30.GL_FRAMEBUFFER_COMPLETE)
                + " drawBuffer=0x" + Integer.toHexString(drawBuf)
                + " drawBefore=" + draw + " readBefore=" + read + "]");
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                crumb("INCOMPLETE FBO " + fullFboB + " status=0x"
                    + Integer.toHexString(status));
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

            // Probe by blitting the corners of the written region into a
            // tiny RGBA8 target and reading it back: works on every driver
            // without depending on pixel-pack alignment.
            if (probeFbo == 0 || probeW != outW || probeH != outH) {
                Gl.deleteFbo(probeFbo);
                Gl.deleteTex(probeTex);
                probeW = outW;
                probeH = outH;
                probeTex = Gl.createRgba8(4, 1);
                probeFbo = Gl.createFbo(probeTex);
            }
            int[] px = new int[4];
            StringBuilder sb = new StringBuilder("verify[");
            int[][] spots = {
                {0, 0}, {outW / 2, outH / 2}, {outW - 8, outH - 8},
                {inWProbe, outH - 8}, {outW - 8, inHProbe},
            };
            for (int[] s : spots) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fullFboB);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, probeFbo);
                GL30.glBlitFramebuffer(s[0], s[1], s[0] + 4, s[1] + 4,
                        0, 0, 4, 1, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, probeFbo);
                java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(4);
                GL11.glReadPixels(0, 0, 4, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
                int v = ((buf.get(3) & 0xFF) << 24) | ((buf.get(2) & 0xFF) << 16)
                      | ((buf.get(1) & 0xFF) << 8) | (buf.get(0) & 0xFF);
                org.lwjgl.system.MemoryUtil.memFree(buf);
                sb.append('(').append(s[0]).append(',').append(s[1]).append(")=")
                  .append(String.format("%08X", v)).append(' ');
            }
            sb.append(" outTex=").append(fullTexB)
              .append(" outFbo=").append(fullFboB)
              .append(" selftest=").append(String.format("%08X", selfTest))
              .append(" err=0x").append(Integer.toHexString(GL11.glGetError()))
              .append(']');
            crumb(sb.toString());

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GL13.glActiveTexture(unit);
            GL20.glUseProgram(prog);
        } catch (Throwable t) {
            crumb("verify failed: " + t);
        }
    }

    /**
     * Draw-buffer selection, via the single-value form GL11.glDrawBuffer(int).
     *
     * Two earlier attempts failed and both are worth remembering:
     *  - GL20.glDrawBuffers(new int[]{...}) each frame: allocation churn, and
     *    on this driver it left the buffer unset (att0 stayed 0) while raising
     *    GL_INVALID_ENUM.
     *  - GL20.glDrawBuffers(IntBuffer) with a reusable buffer: flipped once in a
     *    static initialiser then written with absolute puts, which threw
     *    IndexOutOfBoundsException on the first frame.
     * glDrawBuffer(int) needs no buffer object and takes effect immediately
     * after glBindFramebuffer.
     */

    /**
     * Draw a flat colour through the real quad VAO and program, no sampling.
     *
     * <p>Separates "the draw never rasterised" from "it rasterised but sampled
     * nothing". Read-only: the probe runs on a scratch framebuffer, never on
     * the target the composite samples.
     */
    private static void probeConstantDraw(int fbo, int w, int h) {
        if (fbo == 0 || constantProg == 0 || quad == 0) return;
        try {
            int savedDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int savedRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int savedProg = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int savedVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int[] vp = new int[4];
            Gl.getViewport(vp);

            if (probeFbo == 0) {
                probeTex = Gl.createRgba8(1, 1);
                probeFbo = Gl.createFbo(probeTex);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            Gl.setViewport(0, 0, w, h);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL20.glUseProgram(constantProg);
            GL30.glBindVertexArray(quad);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            GL30.glBindVertexArray(0);

            int v;
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(4);
            GL11.glReadPixels(w / 2, h / 2, 1, 1,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            v = ((buf.get(3) & 0xFF) << 24) | ((buf.get(2) & 0xFF) << 16)
                  | ((buf.get(1) & 0xFF) << 8) | (buf.get(0) & 0xFF);
            org.lwjgl.system.MemoryUtil.memFree(buf);

            crumb("raster[" + (v == 0 ? "BLACK (quad did not rasterise)"
                    : String.format("%08X (quad rasterises, sampling is the fault)", v))
                + "]");

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDraw);
            GL20.glUseProgram(savedProg);
            GL30.glBindVertexArray(savedVao);
            Gl.setViewport(vp[0], vp[1], vp[2], vp[3]);
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain */ }
        } catch (Throwable t) {
            crumb("raster probe failed: " + t);
        }
    }

    /** Flat colour program: proves the quad rasterises with no sampling. */
    private static final String CONSTANT_FRAG =
        "#version 330\n" +
        "out vec4 fragColor;\n" +
        "void main() { fragColor = vec4(0.2, 0.6, 0.9, 1.0); }\n";
    private static int constantProg;

        private static void probeWorldSource(int worldFbo, int inW, int inH) {
        try {
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain */ }
            if (probeFbo == 0 || probeW != inW || probeH != inH) {
                Gl.deleteFbo(probeFbo);
                Gl.deleteTex(probeTex);
                probeW = inW;
                probeH = inH;
                probeTex = Gl.createRgba8(4, 1);
                probeFbo = Gl.createFbo(probeTex);
            }
            if (probeFbo == 0) { crumb("probeWorldSource: no probe target"); return; }
            int savedDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int savedRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

            // The world pass draws into the 2048x2048 buffer wherever the
            // camera scroll puts it, not necessarily at the origin. Sampling
            // (0,0,inW,inH) blindly picks up whatever happens to be there,
            // which is why the view cone lands off-centre. Scan a coarse grid
            // to find where the drawn region actually starts.
            int[] xs = {Math.max(0, inW / 2 - 2), 2, Math.min(2047, inW + 2)};
            StringBuilder sb = new StringBuilder("source[");
            for (int sx : xs) {
                int sy = Math.max(0, inH / 2 - 2);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, worldFbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, probeFbo);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL30.glBlitFramebuffer(sx, sy, sx + 4, sy + 4, 0, 0, 4, 1,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, probeFbo);
                java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(4);
                GL11.glReadPixels(0, 0, 4, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
                int v = ((buf.get(3) & 0xFF) << 24) | ((buf.get(2) & 0xFF) << 16)
                      | ((buf.get(1) & 0xFF) << 8) | (buf.get(0) & 0xFF);
                org.lwjgl.system.MemoryUtil.memFree(buf);
                sb.append('x').append(sx).append('=')
                  .append(String.format("%08X", v)).append(' ');
            }
            sb.append("in=").append(inW).append('x').append(inH)
              .append(" err=0x").append(Integer.toHexString(GL11.glGetError()))
              .append(']');
            crumb(sb.toString());

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDraw);
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain */ }
        } catch (Throwable t) {
            crumb("probeWorldSource failed: " + t);
        }
    }

    /**
     * Read the small source region we are about to upscale, without touching
     * it. Answers: at this point in the frame, has the world pass written
     * into worldFbo yet?
     *   non-zero -> the input is ready, endDrawing is late enough
     *   zero     -> the input is empty, endDrawing fires too early
     */
    public static void probeSource(int worldFbo, int inW, int inH) {
        try {
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain */ }
            if (probeFbo == 0 || probeW != inW || probeH != inH) {
                Gl.deleteFbo(probeFbo);
                Gl.deleteTex(probeTex);
                probeW = inW;
                probeH = inH;
                probeTex = Gl.createRgba8(4, 1);
                probeFbo = Gl.createFbo(probeTex);
            }
            if (probeFbo == 0) { crumb("probeSource: no probe target"); return; }
            int savedDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int savedRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

            // Sample the centre of the region the world pass draws into.
            int sx = Math.max(0, inW / 2 - 2);
            int sy = Math.max(0, inH / 2 - 2);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fullFboB);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, probeFbo);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBlitFramebuffer(sx, sy, sx + 4, sy + 4,
                    0, 0, 4, 1, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, probeFbo);
            java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(4);
            GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            int v = ((buf.get(3) & 0xFF) << 24) | ((buf.get(2) & 0xFF) << 16)
                  | ((buf.get(1) & 0xFF) << 8) | (buf.get(0) & 0xFF);
            org.lwjgl.system.MemoryUtil.memFree(buf);
            crumb("probeSource[at=" + sx + "," + sy + " of " + inW + "x" + inH
                + " px=" + String.format("%08X", v)
                + " " + (v == 0 ? "EMPTY (world pass has not written yet)"
                                : "populated") + "]");

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDraw);
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain */ }
        } catch (Throwable t) {
            crumb("probeSource failed: " + t);
        }
    }

    private static int probeTex;
    private static int probeFbo;
    private static int probeW;
    private static int probeH;
    private static int inWProbe = 480;
    private static int inHProbe = 270;

    /**
     * Probe self-test: clear the probe target to a known colour and read it
     * back through the exact same path the real probe uses. A zero here
     * means the probe (not the upscale) is at fault, which invalidates every
     * previous "verify=00000000" reading.
     */
    private static int probeSelfTest(int outW, int outH) {
        try {
            if (probeFbo == 0 || probeW != outW || probeH != outH) {
                Gl.deleteFbo(probeFbo);
                Gl.deleteTex(probeTex);
                probeW = outW;
                probeH = outH;
                probeTex = Gl.createRgba8(4, 1);
                probeFbo = Gl.createFbo(probeTex);
            }
            if (probeFbo == 0) return -1;
            int savedDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int savedRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, probeFbo);
            GL11.glViewport(0, 0, 4, 1);
            GL11.glClearColor(0.25f, 0.5f, 0.75f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, probeFbo);
            java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(4);
            GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            int v = ((buf.get(3) & 0xFF) << 24) | ((buf.get(2) & 0xFF) << 16)
                  | ((buf.get(1) & 0xFF) << 8) | (buf.get(0) & 0xFF);
            org.lwjgl.system.MemoryUtil.memFree(buf);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDraw);
            return v;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Drop readiness so a geometry change rebuilds the targets. */
    public static void invalidate() { ready = false; }

    /**
     * GL error gate. The queue is drained before the pass, so anything seen
     * here is ours -- but a single transient error must not permanently
     * disable the scaler, otherwise one bad frame throws away a working
     * pipeline. Three consecutive failures do disable.
     */
    private static boolean step(String where) {
        if (Gl.check(where)) {
            if (glErrors != 0) {
                glErrors = 0;
                crumb("resolve recovered after transient error");
            }
            return true;
        }
        if (++glErrors >= 3) {
            fail(where + " gl error x" + glErrors);
        } else {
            crumb("resolve step " + where + " error ("
                + glErrors + "/3), skipping frame");
        }
        return false;
    }

    private static int glErrors;

    /**
     * EASU small->full then RCAS full->OUTPUT, into a dedicated texture.
     * False on any failure.
     *
     * The result deliberately does NOT overwrite the world texture. Writing it
     * back in place is destructive and left no valid execution point: the world
     * pass has not produced anything yet at TextureFBO#endDrawing, and the
     * composite runs on a thread that cannot issue GL at all. A separate output
     * texture, published through {@link ResolveOutput}, removes the ordering
     * constraint entirely -- see that class for the measurements.
     *
     * Render thread ("main") only. Issuing GL from the game thread kills the
     * JVM with no hs_err.
     */
    public static boolean fsr(int worldTex, int worldFbo, int inW, int inH,
            int outW, int outH, float sharp01) {
        if (failed) return false;
        if (!ready) return false;
        boolean trace = frameCount < 4;
        frameCount++;
        fsrupsc.FsrHooks.suppressViewport(true);
        try {
            if (trace) {
                crumb("fsr enter worldTex=" + worldTex + " worldFbo=" + worldFbo
                    + " on " + Thread.currentThread().getName()
                    + " fboBegin=" + fsrupsc.FsrHooks.fboBeginCount()
                    + " fboEnd=" + fsrupsc.FsrHooks.fboEndCount()
                    + " fboRender=" + fsrupsc.FsrHooks.fboRenderCount()
                    + " compositeExit=" + fsrupsc.FsrHooks.compositeExitCount()
                    + " drawModel=" + fsrupsc.FsrHooks.drawModelCount()
                    + " viewports=" + fsrupsc.FsrHooks.viewportCount());
                // Decisive: is the source region populated at the moment we
                // read it? An all-zero output probe says nothing until this
                // separates "the input is empty" from "the passes do not write".
                probeWorldSource(worldFbo, inW, inH);
                // The resolve is queued from the composite but executed by the
                // sprite ring buffer, so the frame it samples may not be the
                // frame being composited. drawModel=0 with
                // fboRender==compositeExit says the composite issued no draws,
                // which is consistent with the drawer running somewhere else
                // entirely -- a stale frame would displace the view cone.
                crumb("easu[in=" + inW + "x" + inH + " out=" + outW + "x" + outH
                    + " jitter=" + RenderScale.frameJitterX() + ","
                    + RenderScale.frameJitterY()
                    + " ratio=" + ((float) inW / (float) outW) + ","
                    + ((float) inH / (float) outH)
                    + " fboRender=" + fsrupsc.FsrHooks.fboRenderCount()
                    + " compositeExit=" + fsrupsc.FsrHooks.compositeExitCount()
                    + " drawModel=" + fsrupsc.FsrHooks.drawModelCount()
                    + " publishGen=" + ResolveOutput.generation() + "]");
            }
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain */ }
            int[] vp = new int[4];
            Gl.getViewport(vp);
            int savedDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int savedRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int savedProg = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int savedVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int savedUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            boolean hadScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            boolean hadStencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            boolean hadDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean hadBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColorMask(true, true, true, true);

            // Draw-buffer handling: read what the game has, set ours
            // explicitly, put theirs back.
            //
            // The earlier "never touch it" stance came from Mesa/Zink, where
            // glGetInteger(GL_COLOR_ATTACHMENT0) reads 0 and every explicit
            // form returns GL_INVALID_ENUM. We now run native NVIDIA GL
            // (OpenGL 4.6.0 NVIDIA 617.14), where the spec applies: a
            // framebuffer whose draw buffer was never set discards every
            // fragment. Measured proof, and the cause of the black screen:
            //
            //   fboState[... colorAttEnabled=0 ...]
            //   verify[... all five probe pixels 00000000 ...]
            //
            // err=0x500 is GL_INVALID_ENUM, i.e. one of our own calls was
            // rejected, not a driver fault. Fix the state properly and check
            // the error after each change so a future regression names itself.
            int savedDrawBuf = GL11.glGetInteger(GL30.GL_DRAW_BUFFER);
            int savedReadBuf = GL11.glGetInteger(GL30.GL_READ_BUFFER);
            // Rasteriser discard makes every draw a silent no-op with no error
            // and no pixels -- the exact symptom, and it is never enabled by
            // PZ itself so nothing else would have turned it off.
            boolean hadDiscard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
            if (hadDiscard) GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);

            // 1. small region worldTex -> smallTex (blit, same orientation).
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, worldFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, smallFbo);
            drawBufferColor0();
            if (!step("blit")) return false;
            GL30.glBlitFramebuffer(0, 0, inW, inH, 0, 0, inW, inH,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            if (!step("blit2")) return false;

            // 2. EASU smallTex -> fullA.
            Gl.bindFbo(fullFboA);
            drawBufferColor0();
            Gl.setViewport(0, 0, outW, outH);
            runPass(easuProg, smallTex, inW, inH, outW, outH, 0f, true);
            if (!step("easu")) return false;

            // 3. RCAS fullA -> output texture (dedicated, not the world).
            Gl.bindFbo(fullFboB);
            drawBufferColor0();
            Gl.setViewport(0, 0, outW, outH);
            runPass(rcasProg, fullTexA, outW, outH, outW, outH, sharp01, false);
            if (!step("rcas")) return false;
            // Publish for the composite. The world texture is untouched, so
            // this is safe to run at any point in the frame.
            ResolveOutput.set(fullTexB, outW, outH);
            if (trace) {
                crumb("draws issued=" + draws
                    + " vao=" + quad + " prog=" + rcasProg
                    + " fullTexA=" + fullTexA + " outTex=" + fullTexB
                    + " worldTex(untouched)=" + worldTex);
            }

            // Restore exactly what we found, in reverse order. The game's own
            // targets keep GL_NONE as their draw buffer (that is the state
            // PZ itself runs with), so put that back.
            GL13.glActiveTexture(savedUnit);
            GL30.glBindVertexArray(savedVao);
            GL20.glUseProgram(savedProg);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDraw);
            // Put back exactly what was there. Hard-coding GL_NONE here is
            // what left the game's own targets with no draw buffer: GL_NONE
            // is a legal value but not the value PZ renders with.
            if (savedDrawBuf != GL30.GL_NONE) {
                GL11.glDrawBuffer(savedDrawBuf);
            }
            if (savedReadBuf != GL30.GL_NONE) {
                GL11.glReadBuffer(savedReadBuf);
            }
            Gl.setViewport(vp[0], vp[1], vp[2], vp[3]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            if (hadScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            if (hadStencil) GL11.glEnable(GL11.GL_STENCIL_TEST);
            if (hadDepth) GL11.glEnable(GL11.GL_DEPTH_TEST);
            if (hadBlend) GL11.glEnable(GL11.GL_BLEND);
            if (hadDiscard) GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            long alive = ++resolveAlive;
            if (trace) {
                crumb("resolve alive n=" + alive + " in=" + inW + "x" + inH
                    + " draw=" + savedDraw + " read=" + savedRead
                    + " " + ResolveOutput.describe());
                verifyOutput(outW, outH);
            }
            // Everything above has already read the world texture, and the
            // The erase-and-rescan is gone. It ran at the end of fsr, which is still
            // BEFORE the world pass of the frame about to be composited, so it
            // wiped the very texture that pass was about to draw into and the
            // scan correctly reported "nothing drawn". Measuring the drawn
            // region this way cannot separate it from the frame it describes.
            return true;
        } catch (Throwable t) {
            try {
                fsrupsc.FsrLog.log("fsr threw: " + t);
            } catch (Throwable ignored) {}
            return fail("" + t);
        } finally {
            fsrupsc.FsrHooks.suppressViewport(false);
        }
    }

    static void crumb(String line) {
        try {
            fsrupsc.FsrLog.log(line);
        } catch (Throwable ignored) {}
    }

    /**
     * Point the bound draw framebuffer's draw buffer 0 at colour attachment 0.
     *
     * <p>A framebuffer that has never had its draw buffers set discards every
     * fragment -- no error, no pixels. PZ works because it renders through its
     * own targets with an implicit state that happens to be usable; our own
     * targets have no such history, so they must be told.
     */
    private static void drawBufferColor0() {
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
    }

    private static void runPass(int prog, int inputTex, int inW, int inH,
            int outW, int outH, float sharp, boolean easu) {
        // Query-free in steady state: uniform locations are resolved once
        // and cached, and the VAO binding is restored to the default (0)
        // instead of being queried -- the composite's own draws bind their
        // own VAO. GL queries from inside the composite were proven fatal.
        int[] loc = easu ? easuLoc : rcasLoc;
        if (loc[0] == UNRESOLVED) cacheUniforms(prog, loc, easu);
        GL20.glUseProgram(prog);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + Gl.UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, inputTex);
        if (loc[0] >= 0) GL20.glUniform1i(loc[0], Gl.UNIT);
        if (loc[1] >= 0) GL20.glUniform2f(loc[1], inW, inH);
        if (easu) {
            if (loc[2] >= 0) GL20.glUniform2f(loc[2], outW, outH);
            if (loc[3] >= 0) {
                GL20.glUniform2f(loc[3],
                        RenderScale.frameJitterX(), RenderScale.frameJitterY());
            }
        } else if (loc[2] >= 0) {
            GL20.glUniform1f(loc[2], sharp);
        }
        GL30.glBindVertexArray(quad);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        draws++;
    }

    private static long draws;

    /** Sentinel: "location not looked up yet" (a real one may be -1). */
    private static final int UNRESOLVED = Integer.MIN_VALUE;
    /** easu: DIFFUSE, InputSize, OutputSize, Jitter. */
    private static final int[] easuLoc = new int[4];
    /** rcas: DIFFUSE, InputSize, Sharp. */
    private static final int[] rcasLoc = new int[3];

    static {
        java.util.Arrays.fill(easuLoc, UNRESOLVED);
        java.util.Arrays.fill(rcasLoc, UNRESOLVED);
    }

    private static void cacheUniforms(int prog, int[] loc, boolean easu) {
        loc[0] = Gl.uniform(prog, "DIFFUSE");
        loc[1] = Gl.uniform(prog, "InputSize");
        if (easu) {
            loc[2] = Gl.uniform(prog, "OutputSize");
            loc[3] = Gl.uniform(prog, "Jitter");
        } else {
            loc[2] = Gl.uniform(prog, "Sharp");
        }
        // A uniform the compiler eliminated resolves to -1. Writing to -1 is
        // silently ignored, so the pass keeps the default. Keep -1 as-is and let
        // the caller skip it: mapping it onto another slot would corrupt that
        // uniform instead (slot 0 is the sampler).
        for (int i = 0; i < loc.length; i++) {
            if (loc[i] == UNRESOLVED) loc[i] = -1;
        }
        for (int i = 0; i < loc.length; i++) {
            if (loc[i] == -1 && once.compareAndSet(false, true)) {
                crumb("uniform slot " + i
                    + (easu ? " (OutputSize)" : " (Sharp)")
                    + " was eliminated by the compiler; skipped");
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean once =
            new java.util.concurrent.atomic.AtomicBoolean();




    private static boolean ensurePrograms(boolean trace) {
        try {
            if (easuProg == 0) {
                if (trace) crumb("ensurePrograms: compiling easu");
                easuProg = Gl.compileProgram(QUAD_VERT, EASU_FRAG, "fsr_easu");
                if (easuProg == 0) return false;
            }
            if (rcasProg == 0) {
                if (trace) crumb("ensurePrograms: compiling rcas");
                rcasProg = Gl.compileProgram(QUAD_VERT, RCAS_FRAG, "fsr_rcas");
                if (rcasProg == 0) return false;
            }
            if (quad == 0) {
                if (trace) crumb("ensurePrograms: making quad");
                quad = Gl.createQuadVao();
            }
            if (constantProg == 0) {
                constantProg = Gl.compileProgram(QUAD_VERT, CONSTANT_FRAG, "fsr_flat");
                if (trace) crumb("ensurePrograms: flat prog=" + constantProg);
            }
            return quad != 0;
        } catch (Throwable t) {
            return fail("ensurePrograms: " + t);
        }
    }

    private static boolean ensureTargets(int inW, int inH, int outW, int outH) {
        try {
            if (inW != smallW || inH != smallH) {
                Gl.deleteFbo(smallFbo);
                Gl.deleteTex(smallTex);
                smallTex = Gl.createRgba8(inW, inH);
                smallFbo = Gl.createFbo(smallTex);
                if (smallFbo == 0) return false;
                smallW = inW;
                smallH = inH;
            }
            if (outW != fullW || outH != fullH) {
                Gl.deleteFbo(fullFboA);
                Gl.deleteTex(fullTexA);
                Gl.deleteFbo(fullFboB);
                Gl.deleteTex(fullTexB);
                ResolveOutput.clear();
                fullTexA = Gl.createRgba8(outW, outH);
                fullFboA = Gl.createFbo(fullTexA);
                if (fullFboA == 0) return false;
                // Dedicated resolve output. Never written back over the world
                // texture, so the composite can sample it on its own.
                fullTexB = Gl.createRgba8(outW, outH);
                fullFboB = Gl.createFbo(fullTexB);
                if (fullFboB == 0) return false;
                fullW = outW;
                fullH = outH;
            }
            return true;
        } catch (Throwable t) {
            return fail("ensureTargets: " + t);
        }
    }

    private static boolean fail(String why) {
        if (!failed) {
            failed = true;
            System.out.println("[fsr-upsc] resolve failed (" + why + ")");
            try {
                fsrupsc.FsrLog.log("resolve failed (" + why + ")");
            } catch (Throwable ignored) {}
            RenderScale.disable("resolve: " + why);
        }
        return false;
    }

    /** Render-thread drawer: shrinks the world viewport. Ordered before world draws. */
    public static final class ViewportDrawer extends zombie.core.textures.TextureDraw.GenericDrawer {
        private final int sw;
        private final int sh;

        public ViewportDrawer(int sw, int sh) {
            this.sw = sw;
            this.sh = sh;
        }

        @Override
        public void render() {
            try {
                // Fresh world check on the render thread (game-thread GL
                // reads are stale): only shrink the actual world target.
                if (!RenderScale.worldFboBound()) return;
                Gl.setViewport(0, 0, sw, sh);
                RenderScale.noteShrunk();
                if (vpSet < 2) {
                    vpSet++;
                    crumb("viewport shrunk to " + sw + "x" + sh + " on " + Thread.currentThread().getName());
                }
            } catch (Throwable t) {
                RenderScale.disable("viewport drawer: " + t);
            }
        }
    }

    private static volatile long vpSet;
    private static volatile long resolveAlive;
    private static volatile long drawerRuns;
    private static volatile boolean vpSkipLogged;

    /** Render-thread drawer: runs the in-place FSR resolve in stream order. */
    public static final class Resolver extends zombie.core.textures.TextureDraw.GenericDrawer {
        private final int worldTex;
        private final int worldFbo;
        private final int inW;
        private final int inH;
        private final int outW;
        private final int outH;
        private final float sharp;

        public Resolver(int worldTex, int worldFbo, int inW, int inH,
                int outW, int outH, float sharp) {
            this.worldTex = worldTex;
            this.worldFbo = worldFbo;
            this.inW = inW;
            this.inH = inH;
            this.outW = outW;
            this.outH = outH;
            this.sharp = sharp;
        }

        @Override
        public void render() {
            try {
                // Safety: only resolve what was actually shrunk. If the game
                // overrode the viewport (raw GL), passthrough instead of
                // magnifying a crop.
                int[] vp = new int[4];
                Gl.getViewport(vp);
                int[] exp = RenderScale.scaledRect(outW, outH);
                if (vp[2] != exp[0] || vp[3] != exp[1]) {
                    if (!vpWarned) {
                        vpWarned = true;
                        crumb("viewport not shrunk (actual " + vp[2] + "x" + vp[3]
                            + "), passthrough");
                    }
                    return;
                }
                Resolve.fsr(worldTex, worldFbo, inW, inH, outW, outH, sharp);
            } catch (Throwable t) {
                RenderScale.disable("resolver: " + t);
            }
        }

        private static volatile boolean vpWarned;
    }

    private Resolve() {}
}
