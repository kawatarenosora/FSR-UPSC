package fsrupsc;

import java.nio.FloatBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/**
 * Minimal LWJGL-backed GL helpers for the resolve path.
 * Render thread only. Callers must stay exception-proof; every method
 * here throws on GL failure so Resolve can fail-safe to stock rendering.
 */
public final class Gl {
    public static final int UNIT = 0;

    public static void getViewport(int[] v) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            java.nio.IntBuffer b = s.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, b);
            v[0] = b.get(0); v[1] = b.get(1); v[2] = b.get(2); v[3] = b.get(3);
        }
    }

    public static void setViewport(int x, int y, int w, int h) {
        GL11.glViewport(x, y, Math.max(1, w), Math.max(1, h));
    }

    public static int boundFbo() {
        return GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
    }

    public static void bindFbo(int fbo) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
    }

    public static void bindReadFbo(int fbo) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
    }

    public static void bindDrawFbo(int fbo) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
    }

    public static int compileProgram(String vert, String frag, String tag) {
        crumb("compile " + tag + ":vert len=" + vert.length());
        int vs = compile(GL20.GL_VERTEX_SHADER, vert, tag + ":vert");
        if (vs == 0) return 0;
        crumb("compile " + tag + ":vert ok");
        crumb("compile " + tag + ":frag len=" + frag.length());
        int fs = compile(GL20.GL_FRAGMENT_SHADER, frag, tag + ":frag");
        if (fs == 0) {
            GL20.glDeleteShader(vs);
            return 0;
        }
        crumb("compile " + tag + ":frag ok, linking");
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) {
            String log = GL20.glGetProgramInfoLog(prog);
            System.out.println("[fsr-upsc] gl: link failed " + tag + ": " + log);
            crumb("gl: link failed " + tag + ": " + log);
            GL20.glDeleteProgram(prog);
            return 0;
        }
        crumb("compile " + tag + ":link ok prog=" + prog);
        return prog;
    }

    static void crumb(String line) {
        try {
            fsrupsc.FsrLog.log(line);
        } catch (Throwable ignored) {}
    }

    private static int compile(int type, String src, String tag) {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(sh);
            System.out.println("[fsr-upsc] gl: compile failed " + tag + ": " + log);
            try {
                fsrupsc.FsrLog.log("gl: compile failed " + tag + ": " + log);
            } catch (Throwable ignored) {}
            GL20.glDeleteShader(sh);
            return 0;
        }
        return sh;
    }

    public static int uniform(int prog, String name) {
        return GL20.glGetUniformLocation(prog, name);
    }

    /** Compile a GL 4.3 compute program (same logging as compileProgram). */
    public static int compileCompute(String src, String tag) {
        int sh = GL20.glCreateShader(org.lwjgl.opengl.GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(sh);
            System.out.println("[fsr-upsc] gl: compute compile failed " + tag + ": " + log);
            try {
                fsrupsc.FsrLog.log("gl: compute compile failed " + tag + ": " + log);
            } catch (Throwable ignored) {}
            GL20.glDeleteShader(sh);
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, sh);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(sh);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) {
            String log = GL20.glGetProgramInfoLog(prog);
            System.out.println("[fsr-upsc] gl: compute link failed " + tag + ": " + log);
            crumb("gl: compute link failed " + tag + ": " + log);
            GL20.glDeleteProgram(prog);
            return 0;
        }
        crumb("gl: compute ok " + tag + " prog=" + prog);
        return prog;
    }

    /** Fullscreen quad with its own VAO (never touches game VAO/attribute state). */
    public static int createQuadVao() {
        float[] v = {
            -1, -1, 0,  1, 1, 1, 1,  0, 0,
             1, -1, 0,  1, 1, 1, 1,  1, 0,
            -1,  1, 0,  1, 1, 1, 1,  0, 1,
             1,  1, 0,  1, 1, 1, 1,  1, 1,
        };
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        int vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        FloatBuffer fb = org.lwjgl.system.MemoryUtil.memAllocFloat(v.length);
        fb.put(v).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fb, GL15.GL_STATIC_DRAW);
        org.lwjgl.system.MemoryUtil.memFree(fb);
        int stride = 9 * 4;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, stride, 3 * 4);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, stride, 7 * 4);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return vao;
    }

    public static void deleteVao(int vao) {
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
    }

    /**
     * Fullscreen quad matching {@code QUAD_VERT}'s three attributes.
     *
     * <p>The vertex shader declares {@code aPosition} as vec3 at location 0,
     * {@code aColor} as vec4 at location 1 and {@code aUV1} as vec2 at
     * location 2. Supplying only location 0 as a vec2 left 1 and 2 undefined,
     * so {@code vUV1} was garbage: the draw succeeded with no error and no
     * pixels, which is exactly what the output probe kept showing.
     *
     * <p>Interleaved: vec3 position (z = 0), vec4 colour (all 1), vec2 uv.
     * Triangle strip, four vertices, covering clip space.
     */
    public static int createVec2Quad() {
        try {
            float[] v = {
                //  x     y     z     r     g     b     a     u     v
                -1, -1,  0,    1,    1,    1,    1,    0,    0,
                 1, -1,  0,    1,    1,    1,    1,    1,    0,
                -1,  1,  0,    1,    1,    1,    1,    0,    1,
                 1,  1,  0,    1,    1,    1,    1,    1,    1,
            };
            final int stride = 9 * 4;
            int vao = GL30.glGenVertexArrays();
            if (vao == 0) return 0;
            GL30.glBindVertexArray(vao);
            int vbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            FloatBuffer fb = org.lwjgl.system.MemoryUtil.memAllocFloat(v.length);
            fb.put(v).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fb, GL15.GL_STATIC_DRAW);
            org.lwjgl.system.MemoryUtil.memFree(fb);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, stride, 3 * 4);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, stride, 7 * 4);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            if (GL11.glGetError() != 0) {
                GL30.glDeleteVertexArrays(vao);
                GL15.glDeleteBuffers(vbo);
                return 0;
            }
            return vao;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static int createRgba8(int w, int h) {
        int tex = GL11.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    public static int createFbo(int tex) {
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, tex, 0);
        int st = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        if (st != GL30.GL_FRAMEBUFFER_COMPLETE) {
            System.out.println("[fsr-upsc] gl: fbo incomplete 0x" + Integer.toHexString(st));
            GL30.glDeleteFramebuffers(fbo);
            return 0;
        }
        return fbo;
    }

    public static void deleteTex(int tex) {
        if (tex != 0) GL11.glDeleteTextures(tex);
    }

    public static void deleteFbo(int fbo) {
        if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
    }

    public static void deleteProgram(int prog) {
        if (prog != 0) GL20.glDeleteProgram(prog);
    }

    public static void deleteVbo(int vbo) {
        if (vbo != 0) GL15.glDeleteBuffers(vbo);
    }

    /** Non-zero glGetError drained; returns false and logs on error. */
    public static boolean check(String where) {
        int e = GL11.glGetError();
        if (e != 0) {
            System.out.println("[fsr-upsc] gl error 0x" + Integer.toHexString(e) + " at " + where);
            try {
                fsrupsc.FsrLog.log("gl error 0x" + Integer.toHexString(e) + " at " + where);
            } catch (Throwable ignored) {}
            return false;
        }
        return true;
    }

    private Gl() {}
}
