package fsrupsc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureFBO;
import zombie.iso.PlayerCamera;

/**
 * DLSS entry via the native shim ({@code fsrupsc_ngx64.dll} / {@code libfsrupsc_ngx64.so}).
 *
 * <p>Loads the shim with Panama FFM and drives the {@code pzngx_*} ABI from
 * {@code vendor/upstream/fsrupsc_ngx.cpp} (13 exports). Anything missing or failing
 * (no shim, no NVIDIA GPU/driver, NGX error) =&gt; {@link #ready()} is false and
 * {@link Upscaler} falls back to FSR1. All failures are logged, never thrown.
 *
 * <p>Render thread only for evaluate/create; init/optimal may run anywhere.
 * The shim itself serializes every entry point on its worker thread.
 */
public final class NgxSupport {
    // Quality index for pzngx_optimal/create (see shim quality(): 0..4).
    public static final int Q_QUALITY = 0;
    public static final int Q_BALANCED = 1;
    public static final int Q_PERFORMANCE = 2;
    public static final int Q_ULTRA = 3;
    public static final int Q_DLAA = 4;

    // Render-preset values for create flags bits 8..15 (DLSS SDK v310.9.1,
    // nvsdk_ngx_defs.h NVSDK_NGX_DLSS_Hint_Render_Preset_*).
    public static final int PRESET_DEFAULT = 0;
    public static final int PRESET_E = 5;
    public static final int PRESET_F = 6;
    public static final int PRESET_J = 10;
    public static final int PRESET_K = 11;
    public static final int PRESET_L = 12;
    public static final int PRESET_M = 13;

    private static volatile boolean tried;
    private static volatile boolean ready;
    private static volatile String detail = "not tried";

    private static Linker linker;
    private static SymbolLookup lib;
    private static MethodHandle hVersion;
    private static MethodHandle hError;
    private static MethodHandle hInit;
    private static MethodHandle hOptimal;
    private static MethodHandle hCreate;
    private static MethodHandle hImageFd;
    private static MethodHandle hImageBytes;
    private static MethodHandle hSemaphoreFd;
    private static MethodHandle hEvaluate;
    private static MethodHandle hEvaluateSet;
    private static MethodHandle hEvaluations;
    private static MethodHandle hTimes;
    private static MethodHandle hGpuUs;
    private static MethodHandle hDestroy;
    private static MethodHandle hShutdown;

    /** True once {@code pzngx_init} has succeeded. Triggers the init attempt. */
    public static synchronized boolean ready() {
        if (!tried) {
            tried = true;
            // Never load the NGX shim unless DLSS is actually selected.
            // pzngx_init reports success on Mesa/Zink, and a live NGX
            // runtime inside the process is a prime suspect for the
            // native instant death at the first world frame: with the
            // agent detached the game boots and loads a save fine.
            // Loading a native runtime we are not going to call is pure
            // risk, so gate it on the selected mode.
            String m = null;
            try {
                m = FsrConfig.getUpscaler();
            } catch (Throwable ignored) {}
            if (!"dlss".equalsIgnoreCase(m)
                    && !Boolean.getBoolean("fsrupsc.forceNgx")) {
                detail = "shim not loaded (mode=" + m + ")";
                return false;
            }
            try {
                initOnce();
            } catch (Throwable t) {
                detail = "init failed: " + t;
                System.out.println("[fsr-upsc] dlss unavailable (" + detail + "); using fsr1");
            }
        }
        return ready;
    }

    /** Last-known init state for console/Lua diagnostics. Never throws. */
    public static String status() {
        ready();
        String v = shimVersion();
        return "ready=" + ready + " shim=" + v + " detail=" + detail;
    }

    /**
     * GL capability gate for the future DLSS frame path (handle sharing).
     * Render thread only. False on macOS / missing EXT pairs.
     */
    public static boolean glReady() {
        try {
            org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
            if (caps == null) return false;
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            boolean mem = win ? caps.GL_EXT_memory_object_win32 : caps.GL_EXT_memory_object_fd;
            boolean sem = win ? caps.GL_EXT_semaphore_win32 : caps.GL_EXT_semaphore_fd;
            return mem && sem && caps.GL_ARB_viewport_array;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * DLSS per-frame path. Not wired yet (needs depth/motion inputs):
     * returns false so callers fall back to the FSR resolve.
     */
    public static boolean frame(ObjectMotion.Frame frame) {
        return false;
    }

    /** DLSS water mask path state (N2 frame stage). */
    public static boolean waterMasked() {
        return false;
    }

    /** Quality string from settings to the shim quality index. */
    public static int qualityIndex(String q) {
        if (q == null) return Q_BALANCED;
        switch (q.toLowerCase()) {
            case "quality": return Q_QUALITY;
            case "balanced": return Q_BALANCED;
            case "performance": return Q_PERFORMANCE;
            case "ultra": return Q_ULTRA;
            case "native": return Q_DLAA; // full-res DLSS
            default: return Q_BALANCED;
        }
    }

    /** Preset string from settings to the SDK preset value. */
    public static int presetValue(String p) {
        if (p == null) return PRESET_DEFAULT;
        switch (p.toLowerCase()) {
            case "default": return PRESET_DEFAULT;
            case "e": return PRESET_E;
            case "f": return PRESET_F;
            case "j": return PRESET_J;
            case "k": return PRESET_K;
            case "l": return PRESET_L;
            case "m": return PRESET_M;
            default: return PRESET_DEFAULT;
        }
    }

    /**
     * Create flags: bit 0 depth inverted, bit 1 sharpening, bit 2 auto-exposure off,
     * bit 3 HDR, bits 8..15 render preset, bit 16 two image sets.
     */
    public static int createFlags(int preset, boolean sharpen, boolean hdr) {
        int f = (preset & 0xFF) << 8;
        if (sharpen) f |= 2;
        if (hdr) f |= 8;
        return f;
    }

    /** DLSS optimal render size for an output size and quality. Returns {inW, inH} or null. */
    public static int[] optimal(int q, int outW, int outH) {
        if (!ready()) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pInW = a.allocate(ValueLayout.JAVA_INT);
            MemorySegment pInH = a.allocate(ValueLayout.JAVA_INT);
            MemorySegment pSharp = a.allocate(ValueLayout.JAVA_FLOAT);
            int rc = (int) hOptimal.invoke(q, outW, outH, pInW, pInH, pSharp);
            if (rc != 0) {
                System.out.println("[fsr-upsc] pzngx_optimal failed: " + lastError());
                return null;
            }
            return new int[] { pInW.get(ValueLayout.JAVA_INT, 0), pInH.get(ValueLayout.JAVA_INT, 0) };
        } catch (Throwable t) {
            System.out.println("[fsr-upsc] pzngx_optimal failed: " + t);
            return null;
        }
    }

    /** Create the shared images + feature. Must run on the render thread. */
    public static boolean create(int inW, int inH, int outW, int outH, int q, int flags) {
        if (!ready()) return false;
        try {
            int rc = (int) hCreate.invoke(inW, inH, outW, outH, q, flags);
            if (rc != 0) System.out.println("[fsr-upsc] pzngx_create failed: " + lastError());
            return rc == 0;
        } catch (Throwable t) {
            System.out.println("[fsr-upsc] pzngx_create failed: " + t);
            return false;
        }
    }

    /** One frame evaluate on image set 0. Must run on the render thread. */
    public static boolean evaluate(float jitterX, float jitterY, float mvScaleX, float mvScaleY,
            int reset, float sharpness, float frameMs) {
        if (!ready()) return false;
        try {
            int rc = (int) hEvaluate.invoke(jitterX, jitterY, mvScaleX, mvScaleY, reset, sharpness, frameMs);
            return rc == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static long evaluations() {
        if (!ready()) return 0;
        try {
            return (long) hEvaluations.invoke();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Mean GPU time of collected evaluations in microseconds (-1 when none timed). */
    public static double gpuUs() {
        if (!ready()) return -1;
        try {
            return (double) hGpuUs.invoke();
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---- internals ----

    private static void initOnce() throws Throwable {
        Path shim = findShim();
        if (shim == null) {
            detail = "shim not found (natives/fsrupsc_ngx64.dll or libfsrupsc_ngx64.so)";
            System.out.println("[fsr-upsc] dlss shim not present; using fsr1");
            return;
        }
        linker = Linker.nativeLinker();
        lib = SymbolLookup.libraryLookup(shim.toAbsolutePath().toString(), Arena.global());
        hVersion = downcall("pzngx_version", FunctionDescriptor.of(ValueLayout.ADDRESS));
        hError = downcall("pzngx_error", FunctionDescriptor.of(ValueLayout.ADDRESS));
        hInit = downcall("pzngx_init", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        hOptimal = downcall("pzngx_optimal", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        hCreate = downcall("pzngx_create", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        hImageFd = downcall("pzngx_image_fd",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        hImageBytes = downcall("pzngx_image_bytes",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        hSemaphoreFd = downcall("pzngx_semaphore_fd",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        hEvaluate = downcall("pzngx_evaluate", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
        hEvaluateSet = downcall("pzngx_evaluate_set", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT));
        hEvaluations = downcall("pzngx_evaluations", FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        hTimes = downcall("pzngx_times", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        hGpuUs = downcall("pzngx_gpu_us", FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE));
        hDestroy = downcall("pzngx_destroy", FunctionDescriptor.ofVoid());
        hShutdown = downcall("pzngx_shutdown", FunctionDescriptor.ofVoid());

        String appData = appDataPath();
        String dlssDir = shim.toAbsolutePath().getParent().toString();
        int rc;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pApp = a.allocateFrom(appData);
            MemorySegment pDir = a.allocateFrom(dlssDir);
            // NULL GL UUID: the shim picks the first NVIDIA Vulkan device.
            // -1: no device-index override.
            rc = (int) hInit.invoke(pApp, pDir, MemorySegment.NULL, -1);
        }
        if (rc != 0) {
            detail = lastError();
            System.out.println("[fsr-upsc] dlss unavailable (" + detail + "); using fsr1");
            return;
        }
        ready = true;
        detail = "init ok";
        System.out.println("[fsr-upsc] dlss: ready (" + shimVersion() + ", shim=" + shim + ")");
    }

    private static MethodHandle downcall(String name, FunctionDescriptor fd) {
        return linker.downcallHandle(lib.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("pzngx export missing: " + name)), fd);
    }

    private static String cstr(MemorySegment p) {
        if (p == null || p.equals(MemorySegment.NULL)) return "";
        try {
            return p.reinterpret(4096).getString(0);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String shimVersion() {
        if (hVersion == null) return "n/a";
        try {
            return cstr((MemorySegment) hVersion.invoke());
        } catch (Throwable t) {
            return "n/a";
        }
    }

    private static String lastError() {
        if (hError == null) return detail;
        try {
            String e = cstr((MemorySegment) hError.invoke());
            return e.isEmpty() ? detail : e;
        } catch (Throwable t) {
            return detail;
        }
    }

    static void resolve(Object frame) { /* legacy stub: use resolve(Frame) */ }

    // ---- DLSS frame path (v1: color + depth + camera motion) ----

    static final String DLSS_QUAD_VERT =
        "#version 330\n" +
        "layout(location = 0) in vec2 aPos;\n" +
        "void main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    static final String INPUTS_FRAG =
        "#version 330\n" +
        "uniform sampler2D SceneDepth;\n" +
        "uniform ivec2 origin;\n" +
        "uniform float constantDepth;\n" +
        "uniform vec3 cur;\n" +
        "uniform vec3 prev;\n" +
        "uniform vec4 params;\n" +
        "layout(location = 0) out float fragDepth;\n" +
        "layout(location = 1) out vec2 fragMv;\n" +
        "void main() {\n" +
        "   fragDepth = constantDepth >= 0.0 ? constantDepth : texelFetch(SceneDepth, ivec2(gl_FragCoord.xy) + origin, 0).r;\n" +
        "   float s = params.x;\n" +
        "   float inH = params.y;\n" +
        "   vec2 p = gl_FragCoord.xy;\n" +
        "   vec2 screen = vec2(p.x / s, (inH - p.y) / s);\n" +
        "   vec2 world = cur.xy + screen * cur.z;\n" +
        "   vec2 prevScreen = (world - prev.xy) / prev.z;\n" +
        "   vec2 prevP = vec2(prevScreen.x * s, inH - prevScreen.y * s);\n" +
        "   fragMv = (prevP - p) * params.z;\n" +
        "}";

    /**
     * Motion-only inputs pass (no depth texture, no float MRT): the R32F
     * depth image stays untouched, camera motion goes to the RG16F image
     * through a single-attachment FBO, depth runs on the constant path.
     * Used because dual float MRT kills some drivers with no return.
     */
    static final String MOTION_FRAG =
        "#version 330\n" +
        "uniform vec3 cur;\n" +
        "uniform vec3 prev;\n" +
        "uniform vec4 params;\n" +
        "out vec2 fragMv;\n" +
        "void main() {\n" +
        "   float s = params.x;\n" +
        "   float inH = params.y;\n" +
        "   vec2 p = gl_FragCoord.xy;\n" +
        "   vec2 screen = vec2(p.x / s, (inH - p.y) / s);\n" +
        "   vec2 world = cur.xy + screen * cur.z;\n" +
        "   vec2 prevScreen = (world - prev.xy) / prev.z;\n" +
        "   vec2 prevP = vec2(prevScreen.x * s, inH - prevScreen.y * s);\n" +
        "   fragMv = (prevP - p) * params.z;\n" +
        "}";

    /** Scene depth resample into the shared R32F image (single attachment). */
    static final String DEPTH_FRAG =
        "#version 330\n" +
        "uniform sampler2D SceneDepth;\n" +
        "uniform ivec2 origin;\n" +
        "out float fragDepth;\n" +
        "void main() {\n" +
        "   fragDepth = texelFetch(SceneDepth, ivec2(gl_FragCoord.xy) + origin, 0).r;\n" +
        "}";

    // create flags: bit0 depth inverted, bit1 sharpening, bit2 auto-exposure off, bit3 HDR.
    private static final boolean DLSS_DEPTH_INVERTED = false;
    private static final boolean DLSS_SHARPEN = false;
    private static final boolean DLSS_AUTO_EXPOSURE = false;
    private static final float DLSS_MV_SIGN = 1.0f;

    private static boolean frameFailed;
    private static boolean featureBuilt;
    private static int inW, inH, outW, outH;
    private static final int[] tex = new int[4];
    private static final int[] mem = new int[4];
    private static int colorFbo, motionFbo, depthFbo, outReadFbo;
    private static int semGlDone, semDlssDone;
    private static int vecQuad;
    private static int motionProgram;
    private static int[] motionUniforms;
    private static int depthProgram;
    private static int[] depthUniforms;
    private static int sceneDepthFbo = -1, sceneDepthTex;
    private static float dlssSharpness;
    private static boolean haveLast;
    private static float lastOffX, lastOffY, lastZoom;
    private static long lastFrameNs;
    private static long frames;
    private static long statPrep, statEval, statWait, statFrames;
    private static final int[] LAYOUTS = {
        EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT,
        EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT,
        EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT,
        EXTSemaphore.GL_LAYOUT_GENERAL_EXT,
    };
    private static final int[] NO_BUFFERS = new int[0];

    /**
     * Per-frame DLSS resolve on the render thread. Writes the DLSS output
     * back into the world framebuffer. True when DLSS produced the frame.
     */
    static boolean resolve(ObjectMotion.Frame objects) {
        if (!RenderScale.active() || IsoPlayer.numPlayers != 1) return false;
        if (!ready() || frameFailed) return false;
        try {
            int[] r = RenderScale.scaledRect(Core.width, Core.height);
            if (!featureBuilt || r[0] != inW || r[1] != inH
                    || Core.width != outW || Core.height != outH) {
                if (featureBuilt) {
                    try {
                        hDestroy.invoke();
                    } catch (Throwable ignored) {}
                    releaseGl();
                    featureBuilt = false;
                }
                if (!createFeature()) return false;
                featureBuilt = true;
            }
            return frame();
        } catch (Throwable t) {
            return fallback("resolve: " + t);
        }
    }

    /** DLSS frame unavailable: the FSR path takes over (RenderScale stays on). */
    private static boolean fallback(String why) {
        if (!frameFailed) {
            frameFailed = true;
            String line = "dlss frame off (" + why + "); fsr1 resolve takes over";
            System.out.println("[fsr-upsc] " + line);
            try {
                fsrupsc.FsrLog.log(line);
            } catch (Throwable ignored) {}
        }
        return false;
    }

    static void fcrumb(String line) {
        try {
            fsrupsc.FsrLog.log(line);
        } catch (Throwable ignored) {}
    }

    private static String glVendor = "";

    private static boolean createFeature() {
        fsrupsc.FsrHooks.suppressViewport(true);
        try {
            if (!glReady()) {
                return fallback("the GL driver has no GL_EXT_memory_object/semaphore sharing (Vulkan image sharing)");
            }
            // The DLSS frame path needs float MRT + imported-memory FBOs.
            // Verified against native NVIDIA GL only; Mesa/Zink dies inside
            // the dual-R32F/RG16F framebuffer setup (no hs_err, no return).
            if (glVendor.isEmpty()) {
                try {
                    String v = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
                    glVendor = v == null ? "unknown" : v;
                } catch (Throwable t) {
                    glVendor = "unknown";
                }
            }
            if (glVendor.toLowerCase(java.util.Locale.ROOT).contains("mesa")
                    || glVendor.toLowerCase(java.util.Locale.ROOT).contains("zink")
                    || glVendor.toLowerCase(java.util.Locale.ROOT).contains("llvmpipe")
                    || !glVendor.toLowerCase(java.util.Locale.ROOT).contains("nvidia")) {
                return fallback("native NVIDIA GL required for the DLSS frame (found: " + glVendor + ")");
            }
            int[] r = RenderScale.scaledRect(Core.width, Core.height);
            inW = r[0];
            inH = r[1];
            outW = Core.width;
            outH = Core.height;
            fcrumb("createFeature in=" + inW + "x" + inH + " out=" + outW + "x" + outH);
            try (Arena a = Arena.ofConfined()) {
                MemorySegment pW = a.allocate(ValueLayout.JAVA_INT);
                MemorySegment pH = a.allocate(ValueLayout.JAVA_INT);
                MemorySegment pS = a.allocate(ValueLayout.JAVA_FLOAT);
                int rc = (int) hOptimal.invoke(qualityIndex(FsrConfig.getUpscalerQuality()),
                        outW, outH, pW, pH, pS);
                if (rc == 0) dlssSharpness = pS.get(ValueLayout.JAVA_FLOAT, 0);
            } catch (Throwable ignored) {}
            int flags = (DLSS_DEPTH_INVERTED ? 1 : 0) | (DLSS_SHARPEN ? 2 : 0)
                | (DLSS_AUTO_EXPOSURE ? 0 : 4) | (presetValue(FsrConfig.getNgxPreset()) << 8);
            fcrumb("pzngx_create flags=0x" + Integer.toHexString(flags));
            int rc = (int) hCreate.invoke(inW, inH, outW, outH,
                    qualityIndex(FsrConfig.getUpscalerQuality()), flags);
            if (rc != 0) return fallback("create: " + lastError());
            fcrumb("pzngx_create ok");

            int[] formats = { GL11.GL_RGBA8, GL30.GL_R32F, GL30.GL_RG16F, GL11.GL_RGBA8 };
            for (int i = 0; i < 4; i++) {
                fcrumb("import image " + i);
                long fd = (long) hImageFd.invoke(i);
                long bytes = (long) hImageBytes.invoke(i);
                if (fd < 0 || bytes <= 0) return fallback("image " + i + " has no exported memory");
                int m = EXTMemoryObject.glCreateMemoryObjectsEXT();
                EXTMemoryObject.glMemoryObjectParameteriEXT(m,
                        EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, 1);
                EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(m, bytes,
                        EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, fd);
                int t = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, EXTMemoryObject.GL_TEXTURE_TILING_EXT,
                        EXTMemoryObject.GL_OPTIMAL_TILING_EXT);
                int w = (i == 3) ? outW : inW;
                int h = (i == 3) ? outH : inH;
                EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, formats[i], w, h, m, 0L);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
                if (GL11.glGetError() != 0) return fallback("importing image " + i);
                mem[i] = m;
                tex[i] = t;
            }
            fcrumb("images ok");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            colorFbo = framebufferOf(tex[0]);
            motionFbo = framebufferOf(tex[2]);
            depthFbo = framebufferOf(tex[1]);
            outReadFbo = framebufferOf(tex[3]);
            if (colorFbo == 0 || motionFbo == 0 || depthFbo == 0 || outReadFbo == 0) {
                return fallback("a framebuffer over an imported image is incomplete");
            }
            fcrumb("fbos ok");
            semGlDone = EXTSemaphore.glGenSemaphoresEXT();
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(semGlDone,
                    EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT,
                    (long) hSemaphoreFd.invoke(0));
            semDlssDone = EXTSemaphore.glGenSemaphoresEXT();
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(semDlssDone,
                    EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT,
                    (long) hSemaphoreFd.invoke(1));
            if (GL11.glGetError() != 0) return fallback("importing the semaphores");
            fcrumb("semaphores ok");
            motionProgram = Gl.compileProgram(DLSS_QUAD_VERT, MOTION_FRAG, "dlss motion");
            if (motionProgram == 0) return fallback("the motion shader was refused");
            fcrumb("motion program ok");
            motionUniforms = new int[] {
                GL20.glGetUniformLocation(motionProgram, "cur"),
                GL20.glGetUniformLocation(motionProgram, "prev"),
                GL20.glGetUniformLocation(motionProgram, "params"),
            };
            depthProgram = Gl.compileProgram(DLSS_QUAD_VERT, DEPTH_FRAG, "dlss depth");
            if (depthProgram == 0) return fallback("the depth shader was refused");
            depthUniforms = new int[] {
                GL20.glGetUniformLocation(depthProgram, "SceneDepth"),
                GL20.glGetUniformLocation(depthProgram, "origin"),
            };
            fcrumb("depth program ok");
            vecQuad = Gl.createVec2Quad();
            if (vecQuad == 0) return fallback("no vec2 quad");
            haveLast = false;
            RenderScale.setJitter(0, 0);
            String line = "dlss: feature ok, " + inW + "x" + inH + " -> " + outW + "x" + outH
                + " (" + FsrConfig.getUpscalerQuality() + ", preset " + FsrConfig.getNgxPreset() + ")";
            System.out.println("[fsr-upsc] " + line);
            try {
                fsrupsc.FsrLog.log(line);
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            return fallback("createFeature: " + t);
        } finally {
            fsrupsc.FsrHooks.suppressViewport(false);
        }
    }

    private static boolean frame() {
        fsrupsc.FsrHooks.suppressViewport(true);
        try {
            TextureFBO world = Core.getInstance().getOffscreenBuffer(0);
            if (world == null) return false;
            int worldFbo = world.getBufferId();
            PlayerCamera camera;
            try {
                camera = SpriteRenderer.instance.getRenderingPlayerCamera(0);
            } catch (Throwable ignored) {
                return false;
            }
            if (camera == null) return false;
            float s = RenderScale.scale();
            long tStart = System.nanoTime();
            fcrumb("frame worldFbo=" + worldFbo);

            int[] savedVp = new int[4];
            Gl.getViewport(savedVp);
            int savedFbo = Gl.boundFbo();
            int savedProg = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int savedVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(false);
            GL11.glColorMask(true, true, true, true);

            // 1. color: low-res region into the shared color image.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, worldFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, colorFbo);
            GL30.glBlitFramebuffer(0, 0, inW, inH, 0, 0, inW, inH,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

            // 2. scene depth resampled into the shared R32F image
            // (constant 0.5 when the world has no depth texture).
            int sceneDepth = sceneDepthTexture(worldFbo);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, depthFbo);
            GL11.glViewport(0, 0, inW, inH);
            if (sceneDepth == 0) {
                GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[] { 0.5f, 0.5f, 0.5f, 0.5f });
            } else {
                GL30.glBindVertexArray(vecQuad);
                GL20.glUseProgram(depthProgram);
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + Gl.UNIT);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneDepth);
                GL20.glUniform1i(depthUniforms[0], Gl.UNIT);
                GL20.glUniform2i(depthUniforms[1], 0, 0);
                GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
                GL30.glBindVertexArray(savedVao);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            }
            if (!Gl.check("depth")) return fallback("depth gl error");
            fcrumb("depth ok src=" + sceneDepth);

            // 3. camera motion into the single RG16F image.
            GL30.glBindVertexArray(vecQuad);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, motionFbo);
            GL11.glViewport(0, 0, inW, inH);
            float offX = camera.offX;
            float offY = camera.offY;
            float zoom = camera.zoom <= 0 ? 1 : camera.zoom;
            boolean reset = !haveLast;
            if (!haveLast) {
                lastOffX = offX;
                lastOffY = offY;
                lastZoom = zoom;
                haveLast = true;
            }
            GL20.glUseProgram(motionProgram);
            GL20.glUniform3f(motionUniforms[0], offX, offY, zoom);
            GL20.glUniform3f(motionUniforms[1], lastOffX, lastOffY, lastZoom);
            GL20.glUniform4f(motionUniforms[2], s, inH, DLSS_MV_SIGN, 0f);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            GL30.glBindVertexArray(savedVao);
            lastOffX = offX;
            lastOffY = offY;
            lastZoom = zoom;
            fcrumb("motion ok");

            // 5. hand over to Vulkan and back.
            EXTSemaphore.glSignalSemaphoreEXT(semGlDone, NO_BUFFERS, tex, LAYOUTS);
            GL11.glFlush();
            long now = System.nanoTime();
            float frameMs = lastFrameNs == 0 ? 16.7f : (now - lastFrameNs) / 1.0e6f;
            lastFrameNs = now;
            fcrumb("evaluate reset=" + (reset ? 1 : 0) + " sharp=" + dlssSharpness + " ms=" + frameMs);
            int rc = (int) hEvaluate.invoke(0f, 0f, 1f, 1f, reset ? 1 : 0, dlssSharpness, frameMs);
            if (rc != 0) return fallback("evaluate: " + lastError());
            fcrumb("evaluate ok");
            long tEval = System.nanoTime();
            EXTSemaphore.glWaitSemaphoreEXT(semDlssDone, NO_BUFFERS, tex, LAYOUTS);
            fcrumb("wait ok");

            // 6. DLSS output back into the world framebuffer (composite samples it).
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, outReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, worldFbo);
            GL30.glBlitFramebuffer(0, 0, outW, outH, 0, 0, outW, outH,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

            // 7. restore GL state the way the sprite ring expects it.
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
            Gl.setViewport(savedVp[0], savedVp[1], savedVp[2], savedVp[3]);
            Texture.lastTextureID = -1;
            GL30.glBindVertexArray(savedVao);
            GL20.glUseProgram(savedProg);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            try {
                GLStateRenderThread.restore();
            } catch (Throwable ignored) {}
            if (!Gl.check("dlss frame")) return fallback("gl error in frame");
            long tEnd = System.nanoTime();
            statPrep += now - tStart;
            statEval += tEval - now;
            statWait += tEnd - tEval;
            if (++statFrames >= 600) {
                statFrames = 0;
                double gpu = -1;
                try {
                    if (hGpuUs != null) gpu = (double) hGpuUs.invoke();
                } catch (Throwable ignored) {}
                String line = String.format(
                    "dlss: frame %d prep/eval/wait %.2f/%.2f/%.2f ms gpu %.1f us",
                    frames, statPrep / 600 / 1.0e6, statEval / 600 / 1.0e6,
                    statWait / 600 / 1.0e6, gpu);
                statPrep = statEval = statWait = 0;
                System.out.println("[fsr-upsc] " + line);
                try {
                    fsrupsc.FsrLog.log(line);
                } catch (Throwable ignored) {}
            }
            frames++;
            return true;
        } catch (Throwable t) {
            return fallback("frame: " + t);
        } finally {
            fsrupsc.FsrHooks.suppressViewport(false);
        }
    }

    /** World framebuffer depth texture, or 0 (constant depth path). */
    private static int sceneDepthTexture(int worldFbo) {
        try {
            if (worldFbo == sceneDepthFbo) return sceneDepthTex;
            sceneDepthFbo = worldFbo;
            sceneDepthTex = 0;
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, worldFbo);
            int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            if (type == GL11.GL_TEXTURE) {
                sceneDepthTex = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                        GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            }
        } catch (Throwable ignored) {}
        return sceneDepthTex;
    }

    private static int framebufferOf(int tex) {
        try {
            int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            int fbo = GL30.glGenFramebuffers();
            fcrumb("fbo gen=" + fbo + " for tex=" + tex);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, tex, 0);
            fcrumb("fbo attach tex=" + tex + " err=" + GL11.glGetError());
            int st = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
            fcrumb("fbo status tex=" + tex + " st=0x" + Integer.toHexString(st));
            if (st != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glDeleteFramebuffers(fbo);
                return 0;
            }
            return fbo;
        } catch (Throwable t) {
            try {
                fsrupsc.FsrLog.log("framebufferOf threw: " + t);
            } catch (Throwable ignored) {}
            return 0;
        }
    }

    private static int framebufferOf2(int tex0, int tex1) {
        try {
            int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            int fbo = GL30.glGenFramebuffers();
            fcrumb("fbo2 gen=" + fbo);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, tex0, 0);
            fcrumb("fbo2 attach0 err=" + GL11.glGetError());
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1,
                    GL11.GL_TEXTURE_2D, tex1, 0);
            fcrumb("fbo2 attach1 err=" + GL11.glGetError());
            GL20.glDrawBuffers(IntBuffer.wrap(new int[] {
                GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1 }));
            fcrumb("fbo2 drawbuffers err=" + GL11.glGetError());
            int st = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
            fcrumb("fbo2 status st=0x" + Integer.toHexString(st));
            if (st != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glDeleteFramebuffers(fbo);
                return 0;
            }
            return fbo;
        } catch (Throwable t) {
            try {
                fsrupsc.FsrLog.log("framebufferOf2 threw: " + t);
            } catch (Throwable ignored) {}
            return 0;
        }
    }

    private static void releaseGl() {
        try {
            for (int i = 0; i < 4; i++) {
                if (tex[i] != 0) {
                    GL11.glDeleteTextures(tex[i]);
                    tex[i] = 0;
                }
                if (mem[i] != 0) {
                    EXTMemoryObject.glDeleteMemoryObjectsEXT(mem[i]);
                    mem[i] = 0;
                }
            }
            if (motionFbo != 0) GL30.glDeleteFramebuffers(motionFbo);
            if (outReadFbo != 0) GL30.glDeleteFramebuffers(outReadFbo);
            colorFbo = motionFbo = outReadFbo = 0;
            if (semGlDone != 0) EXTSemaphore.glDeleteSemaphoresEXT(semGlDone);
            if (semDlssDone != 0) EXTSemaphore.glDeleteSemaphoresEXT(semDlssDone);
            semGlDone = semDlssDone = 0;
            if (motionProgram != 0) Gl.deleteProgram(motionProgram);
            if (depthProgram != 0) Gl.deleteProgram(depthProgram);
            if (vecQuad != 0) Gl.deleteVao(vecQuad);
            motionProgram = depthProgram = vecQuad = 0;
            sceneDepthFbo = -1;
            sceneDepthTex = 0;
            haveLast = false;
        } catch (Throwable ignored) {}
    }

    static Path findShim() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String name = win ? "fsrupsc_ngx64.dll" : "libfsrupsc_ngx64.so";
        List<Path> cands = new ArrayList<>();
        String prop = System.getProperty("fsrupsc.natives");
        if (prop != null && !prop.isEmpty()) cands.add(Paths.get(prop, name));
        cands.add(Paths.get("natives", name));
        // Known Steam locations (same machine layout as the build scripts).
        cands.add(Paths.get("E:/Program Files (x86)/Steam/steamapps/common/ProjectZomboid/natives", name));
        cands.add(Paths.get("D:/steam/steamapps/common/ProjectZomboid/natives", name));
        for (Path p : cands) {
            try {
                if (Files.exists(p)) return p;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String appDataPath() {
        try {
            String home = System.getProperty("user.home");
            if (home != null && !home.isEmpty()) {
                Path z = Paths.get(home, "Zomboid");
                if (Files.exists(z)) return z.toString();
            }
        } catch (Exception ignored) {}
        return ".";
    }

    private NgxSupport() {}
}
