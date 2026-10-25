package fsrupsc;

import zombie.core.textures.Texture;
import zombie.interfaces.ITexture;

/**
 * The resolve output texture, mirroring upstream PZ_Optimization's design.
 *
 * <p>Upstream writes the upscale into a <em>dedicated</em> texture and has the
 * composite sample that instead of the offscreen buffer. Our earlier version
 * wrote back into the world texture itself, which is destructive: the frame is
 * destroyed and re-created every time the world pass runs, so the resolve had
 * to run in exactly the right window (before the world draw, or after it but
 * before the composite). Measuring showed there is no such window:
 *
 * <ul>
 *   <li>{@code TextureFBO#endDrawing} fires <em>before</em> the world pass has
 *       written anything (probe: source region reads all zeros), so the upscale
 *       produces a black frame.
 *   <li>{@code MultiTextureFBO2#render} is reached only from the game thread,
 *       and issuing GL there kills the JVM outright.
 * </ul>
 *
 * <p>A separate output texture removes both constraints. The composite then
 * becomes a plain argument substitution, which is pure Java and therefore safe
 * on the game thread where it runs.
 *
 * <h2>Where the substitution happens</h2>
 *
 * <p>{@code Texture#rendershader2} is an <em>instance</em> method -- it draws
 * {@code this} -- so it cannot be redirected by changing an argument. Its
 * receiver is loaded in {@code MultiTextureFBO2#render} at bytecode offset 161
 * as {@code current.getTexture()}, and that is the only {@code getTexture()}
 * call in that method. So the chain is:
 *
 * <pre>
 *   MultiTextureFBO2#render  enter -> open the window
 *   TextureFBO#getTexture    exit  -> return the resolve output
 *   MultiTextureFBO2#render  exit  -> close the window
 * </pre>
 *
 * <p>Nothing outside that window can be substituted: the world pass, the chunk
 * renderer and the weather masks all draw before {@code render()} is entered,
 * so they keep sampling the stock world texture.
 *
 * <h2>Why the source rectangle still lands correctly</h2>
 *
 * <p>{@code rendershader2} normalises its source rectangle against
 * {@code this.getWidthHW()} / {@code getHeightHW()}, not against a constant.
 * Stock, the world texture is 2048x2048 and the composite asks for
 * (0,0,1920,1080), i.e. (0,0)-(93.75%, 52.73%). Our wrapper reports
 * 1920x1080, so the same rectangle normalises to (0,0)-(100%, 100%) and the
 * whole upscaled frame is sampled. Destination is untouched, so the image lands
 * pixel-exact where the world used to be.
 */
public final class ResolveOutput {
    private ResolveOutput() {}

    // GL name of the texture the composite must sample (screen size).
    private static volatile int texId;
    private static volatile int width;
    private static volatile int height;
    private static volatile long generation;

    public static void set(int tex, int w, int h) {
        texId = tex;
        width = w;
        height = h;
        generation++;
    }

    public static int textureId() { return texId; }

    public static boolean hasTexture() { return texId != 0 && width > 0 && height > 0; }

    public static int getWidth() { return width; }

    public static int getHeight() { return height; }

    /** Bumped on every completed resolve, so the composite can tell stale from fresh. */
    public static long generation() { return generation; }

    public static void clear() {
        texId = 0;
        width = 0;
        height = 0;
        cached = null;
    }

    // -------------------------------------------------------------------------
    // The composite window.
    // -------------------------------------------------------------------------

    // >0 while MultiTextureFBO2#render is executing. A counter rather than a
    // boolean so nested or re-entrant composites cannot leave it stuck open.
    private static volatile int window;

    /** The thread inside the window (the composite's). The render thread
     *  runs the outline drawers concurrently and its TextureFBO.
     *  getTexture() calls (ModelOutlines fboA/fboB/fboC) must never
     *  receive the substituted texture -- outline blits sampling the
     *  world image paint whole fullscreen outline-colored frames. The
     *  thread gate is what scopes the substitution to the composite. */
    private static volatile Thread windowThread;

    /** MultiTextureFBO2#render enter. */
    public static void openWindow() {
        if (window == 0) windowThread = Thread.currentThread();
        window++;
    }

    /** MultiTextureFBO2#render exit. */
    public static void closeWindow() {
        int w = window - 1;
        window = w > 0 ? w : 0;
        if (window == 0) windowThread = null;
    }

    public static boolean inWindow() { return window > 0; }

    /** True only on the thread that opened the window. */
    private static boolean inWindowThread() {
        Thread t = windowThread;
        return window > 0 && (t == null || t == Thread.currentThread());
    }

    // -------------------------------------------------------------------------
    // Substitution.
    // -------------------------------------------------------------------------

    private static volatile long swaps;
    private static volatile long passes;
    private static volatile long starved;
    private static volatile long diagHits;

    /**
     * Diagnostic: substitute the world texture instead of the upscale.
     *
     * <p>Set with the marker file {@code fsr-upsc-diag-world} containing
     * "on". The downscale keeps running, so the composite samples the shrunken
     * world region at native coordinates -- if the image is displaced in this
     * mode too, the displacement is not ours.
     */
    private static final boolean DIAG_WORLD = diagWorld();

    private static boolean diagWorld() {
        try {
            java.io.File f = new java.io.File(
                System.getProperty("user.home"), "Zomboid/fsr-upsc-diag-world");
            if (!f.isFile()) return false;
            String v = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).trim();
            return v.equalsIgnoreCase("on") || v.equals("1") || v.equals("true");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * TextureFBO#getTexture exit: hand the composite the resolve output.
     *
     * <p>Returns {@code original} untouched unless every condition holds: we
     * are inside the composite window, the downscaler is active, a resolve has
     * landed, and the wrapper could actually be built. Any failure degrades to
     * plain render scaling rather than breaking the frame.
     *
     * <p>Pure Java, no GL -- safe on the game thread where the composite runs.
     */
    public static ITexture compositeTexture(ITexture original) {
        passes++;
        try {
            // Thread-scoped: only the composite's own thread may see the
            // substituted texture. The render thread runs the outline
            // drawers (ModelOutlines fboA/fboB/fboC getTexture) while the
            // window is open on the game thread; letting the swap leak
            // there made outline blits sample the world image and flood
            // whole frames with the outline color.
            if (!inWindowThread()) return original;
            if (!RenderScale.active() || original == null) return original;
            // Diagnostic mode: hand back the world texture untouched while the
            // downscale stays active. The composite then samples the same
            // (0,0,screen) rectangle of the 2048 world texture it always did, so
            // if the view cone is still displaced here, nothing in this mod's
            // upscale or substitution can be responsible.
            if (DIAG_WORLD) {
                diagHits++;
                if (diagHits == 1) {
                    fsrupsc.FsrLog.log("diag: composite using the WORLD texture"
                        + " (no upscale) id=" + ((original instanceof Texture)
                            ? ((Texture) original).getID() : -1));
                }
                return original;
            }
            if (!hasTexture()) {
                // The downscaled world only lives in the low-res corner of the
                // 2048 world texture, so with no resolve output the composite
                // would draw the wrong region. Stand down rather than show it.
                //
                // But only count when a world resolve pipeline is actually
                // running: the main menu composites too (its background does
                // not have a world pass behind it), and 120 of those menu
                // composites stand the session down before any world load --
                // the mod then stayed stock for the whole session.
                if (!Upscaler.resolvePipelineSeen()) return original;
                starved++;
                if (starved == 1) {
                    try {
                        fsrupsc.FsrLog.log("composite starved #1 with resolve"
                            + " pipeline active: " + Upscaler.compositeStatus());
                    } catch (Throwable ignored) {}
                }
                if (starved == 120) {
                    RenderScale.disable("composite starved: no resolve output");
                }
                return original;
            }
            starved = 0;
            ITexture out = outputTexture();
            if (out == null) return original;
            swaps++;
            if (swaps <= 3) {
                fsrupsc.FsrLog.log("composite swap #" + swaps
                    + " worldTex=" + ((original instanceof Texture)
                        ? ((Texture) original).getID() : -1)
                    + " -> outTex=" + texId + " " + describe());
            }
            return out;
        } catch (Throwable t) {
            return original;
        }
    }

    /**
     * Wrap the raw GL texture id in the game's Texture type.
     *
     * <p>Texture has no public factory for "wrap an existing GL name": its GL
     * name lives in {@code Texture.dataid -> TextureID.id}, and
     * {@code TextureID(int,int,int)} hard-codes {@code id = -1}. So the name is
     * injected reflectively. Built once and cached; any failure returns null
     * and the composite keeps drawing the stock texture.
     */
    private static ITexture outputTexture() {
        try {
            Texture out = cached;
            if (out != null && out.getID() == texId) return out;
            Object idObj = newTextureId(width, height);
            if (idObj == null) return null;
            Class<?> idClass = Class.forName("zombie.core.textures.TextureID");
            // The constructor is (width, height, flags) and leaves id = -1, and
            // it does not fill widthHw/heightHw -- which is what
            // Texture#getWidthHW/getHeightHW actually read. Set every field the
            // composite's rectangle maths depends on, then verify.
            setInt(idClass, idObj, "id", texId);
            setInt(idClass, idObj, "width", width);
            setInt(idClass, idObj, "height", height);
            setInt(idClass, idObj, "widthHw", width);
            setInt(idClass, idObj, "heightHw", height);
            out = new Texture((zombie.core.textures.TextureID) idObj, "fsrupsc-out");
            if (out.getID() != texId
                    || out.getWidthHW() != width
                    || out.getHeightHW() != height) {
                fsrupsc.FsrLog.log("output wrapper mismatch: id=" + out.getID()
                    + " " + out.getWidthHW() + "x" + out.getHeightHW()
                    + " want " + texId + " " + width + "x" + height);
                return null;
            }
            fsrupsc.FsrLog.log("output wrapper ok: id=" + out.getID()
                + " " + out.getWidthHW() + "x" + out.getHeightHW());
            cached = out;
            return out;
        } catch (Throwable t) {
            try {
                fsrupsc.FsrLog.log("outputTexture wrap failed: " + t);
            } catch (Throwable ignored) {}
            return null;
        }
    }

    private static void setInt(Class<?> c, Object target, String name, int v)
            throws Exception {
        java.lang.reflect.Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(target, v);
    }

    /** Allocate a TextureID carrying the output size; its GL name is set later. */
    private static Object newTextureId(int w, int h) throws Exception {
        Class<?> c = Class.forName("zombie.core.textures.TextureID");
        java.lang.reflect.Constructor<?> best = null;
        for (java.lang.reflect.Constructor<?> ctor : c.getConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length == 3 && p[0] == int.class && p[1] == int.class
                    && p[2] == int.class) {
                best = ctor;
                break;
            }
        }
        if (best == null) return null;
        return best.newInstance(w, h, 0);
    }

    private static volatile Texture cached;

    /** Diagnostic string for the agent log. */
    public static String describe() {
        return "output[" + texId + " " + width + "x" + height
            + " gen=" + generation + "]";
    }
}