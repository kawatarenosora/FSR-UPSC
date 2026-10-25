package fsrupsc;

import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureFBO;

/**
 * FSR resolve dispatcher.
 *
 * Modes (FsrSettings.upscaler):
 *   off      stock render
 *   bicubic  passthrough downscale (no EASU/RCAS)
 *   fsr1     spatial EASU + RCAS resolve
 *   fsr2     temporal (M3 auto-reactive compute path)
 *   fsr3     temporal (M4 FSR3 compute path: + SHADING_CHANGE)
 *   ngx      dormant DLSS passthrough (internal, not exposed in the UI)
 */
public final class Upscaler {
    private static volatile boolean temporalNote;

    public static String mode() {
        String m = FsrConfig.getUpscaler();
        if (m == null) return "fsr1";
        String lower = m.toLowerCase(java.util.Locale.ROOT);
        // Temporal modes run on the fsr1 spatial path for now; a
        // once-per-session note explains the fallback (mc-fsr does the same
        // for drivers without temporal support).
        if ("fsr2".equalsIgnoreCase(lower) || "fsr3".equalsIgnoreCase(lower)) {
            if (Fsr2Compute.available()) {
                return lower; // fsr2 = M3 auto-reactive path; fsr3 = M4 SHADING_CHANGE path
            }
            if (!temporalNote) {
                temporalNote = true;
                String probe;
                try { probe = Fsr2Compute.probeLine(); } catch (Throwable t) { probe = ""; }
                crumb(m + " requested: GL compute unavailable, resolving with fsr1 "
                    + probe);
            }
            return "fsr1";
        }
        if ("ngx".equalsIgnoreCase(lower)) return "fsr1"; // dormant until the bridge exists
        if ("xess".equalsIgnoreCase(lower)) return "fsr1";
        return lower;
    }

    /** True when the resolve produces an output texture. */
    public static boolean drawsOutput() {
        String m = mode();
        if ("off".equalsIgnoreCase(m)) return false;
        if ("bicubic".equalsIgnoreCase(m)) return false;
        return RenderScale.active();
    }

    /**
 * Game-thread entry (world end): kept for API compatibility. The resolve
 * now runs at the composite instead, where the world texture is complete
 * and not yet sampled.
 */
public static void resolveWorld() {
    }

    // ---------------------------------------------------------------------------
// Composite-path resolve (the working path).
//
// Runs inline at MultiTextureFBO2#render entry, i.e. after the world is
// complete and before the composite samples it. Constraints learned the hard
// way: no GL queries, no allocations and no logging on this path -- group
// "fbo" was proven to kill the process on the first world frame when its
// hook body did any of those. Everything is cached and recomputed only when
// the output geometry actually changes.
// ---------------------------------------------------------------------------

private static final class Params {
        int worldTex;
        int worldFbo;
        int inW;
        int inH;
        int outW;
        int outH;
        float sharp;
        Object worldFboRef;
    }

    private static volatile Params params;

    /**
     * Mode-aware resolve: fsr2 goes through the GL 4.3 compute pipeline
     * (Phase 2 M1); anything else keeps the proven fsr1 blit path. A compute
     * failure falls back to fsr1 for the frame and permanently disables the
     * compute path (Fsr2Compute.fault) so the mod degrades, never breaks.
     */
    private static boolean runResolve(int tex, int glFbo, int inW, int inH,
            int outW, int outH, float sharp) {
        String m = mode();
        if ("fsr2".equalsIgnoreCase(m) || "fsr3".equalsIgnoreCase(m)) {
            boolean fs3 = "fsr3".equalsIgnoreCase(m);
            try {
                if (Fsr2Compute.dispatch(tex, glFbo, inW, inH, outW, outH, sharp, fs3)) {
                    return true;
                }
            } catch (Throwable t) {
                Fsr2Compute.fault(t);
            }
            crumb((fs3 ? "fsr3" : "fsr2") + " compute unavailable, resolving with fsr1");
            return Resolve.fsr(tex, glFbo, inW, inH, outW, outH, sharp);
        }
        return Resolve.fsr(tex, glFbo, inW, inH, outW, outH, sharp);
    }

    private static Params params() {
        Params p = params;
        if (!RenderScale.active()) return null;
        String m = mode();
        if (!"fsr1".equalsIgnoreCase(m) && !"ngx".equalsIgnoreCase(m)
                && !"fsr2".equalsIgnoreCase(m) && !"fsr3".equalsIgnoreCase(m)) return null;
        float s = RenderScale.scale();
        if (s >= 0.999f) return null;
        int outW = Core.width;
        int outH = Core.height;
        if (outW <= 0 || outH <= 0) return null;
        // Single source of truth for the shrunk size: the same function the
        // viewport funnel uses. Computing it here as outW * s is how the two
        // drifted apart before.
        int[] in = RenderScale.scaledRect(outW, outH);
        int inW = in[0];
        int inH = in[1];
        float sharp = Math.max(0, Math.min(100, FsrConfig.getFsrSharpnessPct())) / 100.0f;
        if (p != null && p.outW == outW && p.outH == outH
                && p.inW == inW && p.inH == inH && p.sharp == sharp) {
            return p;
        }
        if (p != null) {
            Resolve.invalidate();
        }
        try {
            TextureFBO fbo = Core.getInstance().getOffscreenBuffer(0);
            if (fbo == null) return null;
            Object t = fbo.getTexture();
            if (!(t instanceof Texture)) return null;
            int tex = ((Texture) t).getID();
            int fid = fbo.getBufferId();
            if (tex == 0 || fid == 0) return null;
            p = new Params();
            p.worldTex = tex;
            p.worldFbo = fid;
            p.inW = inW;
            p.inH = inH;
            p.outW = outW;
            p.outH = outH;
            p.sharp = sharp;
            p.worldFboRef = fbo;
            params = p;
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolve insertion point: TextureFBO#endDrawing for the world buffer.
     *
     * Runs on the render thread ("main"), after the world pass completed
     * and before the composite samples it. Only this thread can safely
     * issue GL here -- the composite hook is game-thread only and drawing
     * from it killed the JVM (fsr enter ... on MainThread, then nothing).
     */
    public static void resolveAtWorldEnd(Object fbo, int glDrawFbo, int glReadFbo) {
        try {
            if (!RenderScale.active()) return;
            // The two resolve positions are mutually exclusive, and this one is
            // wrong: TextureFBO#endDrawing fires BEFORE the world pass draws
            // anything (probe: source region reads all zeros), so the upscale
            // input is empty and the published output is a black frame. It also
            // consumed the shared shrink generation, so the composite path then
            // saw "already resolved this generation" and never ran.
            //
            // With resolve=composite the drawer path owns the resolve entirely.
            if (fsrupsc.FsrHooks.resolveAtComposite()) return;
            worldEndRuns++;
            if (!fsrupsc.FsrHooks.isRenderThread()) return;
            Params p = params();
            if (p == null) return;
            if (p.worldFboRef != fbo) {
                worldEndMismatch++;
                // PZ re-creates the world buffer (resolution change, scale
                // apply, scene load) while Params still holds the previous
                // object; params() only rebuilds on a geometry change, so the
                // resolve would then silently skip the real world buffer for
                // the rest of the session (mismatch runaway, seen after the
                // second "viewport shrunk" following a live scale apply).
                // If this endDrawing really is the world buffer (by object
                // identity through Core), refresh Params to follow it.
                try {
                    if (Core.getInstance().getOffscreenBuffer(0) == fbo) {
                        params = null;
                        p = params();
                        if (p == null || p.worldFboRef != fbo) return;
                        if (refrefreshLog < 8) {
                            refrefreshLog++;
                            crumb("world buffer re-created; params refreshed ("
                                + refrefreshLog + ")");
                        }
                        // fall through: proceed like a matched frame
                    } else {
                        return;
                    }
                } catch (Throwable t) {
                    return;
                }
            }
            if (!Resolve.isReady()) {
                worldEndNotReady++;
                return;
            }
            long gen = RenderScale.shrunkGen();
            if (gen == 0L || gen == lastShrinkGen) {
                worldEndNoGen++;
                return;
            }
            lastShrinkGen = gen;
            // PZ's getBufferId() is an internal counter, not the GL name;
            // use what is actually bound right now (the hook runs inside
            // PZ's own endDrawing, immediately after its bind).
            int target = (glReadFbo > 0) ? glReadFbo : glDrawFbo;
            if (target <= 0) {
                worldEndNoFbo++;
                return;
            }
            worldEndFbo = target;
            if (!runResolve(p.worldTex, target,
                    p.inW, p.inH, p.outW, p.outH, p.sharp)) {
                RenderScale.disable("world-end resolve failed");
                return;
            }
            worldEndOk++;
            if (worldEndOk <= 3) {
                crumb("world-end resolve #" + worldEndOk
                    + " in=" + p.inW + "x" + p.inH + " out=" + p.outW + "x" + p.outH
                    + " tex=" + p.worldTex
                    + " pzFbo=" + p.worldFbo + " glFbo=" + target);
            }
        } catch (Throwable t) {
            RenderScale.disable("world-end resolve: " + t);
        }
    }

    private static volatile long worldEndNoFbo;
    private static volatile int worldEndFbo = -1;
    private static volatile int refrefreshLog;

    /** PZ's internal FBO counter (diagnostic: differs from the GL name). */
    public static int pzFboId() {
        Params p = params;
        return p == null ? -1 : p.worldFbo;
    }

    /** True when this TextureFBO is the world offscreen buffer. */
    public static boolean isWorldFbo(Object fbo) {
        Params p = params;
        return p != null && p.worldFboRef == fbo;
    }

    /**
     * True once ANY resolve attempt has actually run for a world frame
     * (world-end path or the composite drawer). The main-menu background
     * also composites every frame but there is no world pass there and
     * never will be, so starvation on the menu must not count toward the
     * stand-down threshold -- it otherwise disables the whole session
     * before a world even exists (seen as "composite starved" at f:39:
     * 120 menu composites, zero resolve chances, mod stock all session).
     */
    public static boolean resolvePipelineSeen() {
        return worldEndRuns > 0 || drawerOks > 0;
    }

    private static volatile long worldEndRuns;
    private static volatile long worldEndOk;
    private static volatile long worldEndNoGen;
    private static volatile long worldEndNotReady;

    /**
     * Queue the resolve from MultiTextureFBO2#render (game thread), upstream style.
     *
     * <p>No GL is issued here. The drawer is appended to SpriteRenderer's queue
     * and executed later on the render thread, which is the only thread that can
     * safely touch GL. This is exactly how upstream PZ_Optimization avoids the
     * "GL on the game thread kills the JVM" trap while still resolving at the
     * semantically correct point.
     */
    public static void resolveAtComposite() {
        try {
            compositeRuns++;
            if (!RenderScale.active()) return;
            if (!Resolve.isReady()) {
                compositeNotReady++;
                return;
            }
            Params p = params();
            if (p == null) {
                compositeMiss++;
                return;
            }
            long gen = RenderScale.shrunkGen();
            if (gen == 0L || gen == lastShrinkGen) {
                compositeNoGen++;
                return;
            }
            lastShrinkGen = gen;
            Resolver r = RESOLVERS[nextResolver++ & (RESOLVERS.length - 1)];
            r.tex = p.worldTex;
            r.fbo = p.worldFbo;
            r.inW = p.inW;
            r.inH = p.inH;
            r.outW = p.outW;
            r.outH = p.outH;
            r.sharp = p.sharp;
            zombie.core.SpriteRenderer.instance.drawGeneric(r);
        } catch (Throwable t) {
            RenderScale.disable("queue resolve: " + t);
        }
    }

    private static final Resolver[] RESOLVERS = {
        new Resolver(), new Resolver(), new Resolver(), new Resolver()};
    private static int nextResolver;

    /** Render-thread drawer carrying one frame's resolve parameters. */
    private static final class Resolver
            extends zombie.core.textures.TextureDraw.GenericDrawer {
        int tex;
        int fbo;
        int inW;
        int inH;
        int outW;
        int outH;
        float sharp;

        @Override
        public void render() {
            try {
                if (!fsrupsc.FsrHooks.isRenderThread()) {
                    offThreadDraws++;
                    return;
                }
                if (runResolve(tex, fbo, inW, inH, outW, outH, sharp)) {
                    drawerOks++;
                    if (drawerOks <= 3) {
                        crumb("resolve ok #" + drawerOks
                            + " in=" + inW + "x" + inH + " out=" + outW + "x" + outH
                            + " " + fsrupsc.ResolveOutput.describe());
                    }
                } else {
                    RenderScale.disable("resolve drawer failed");
                }
            } catch (Throwable t) {
                RenderScale.disable("resolve drawer: " + t);
            }
        }
    }

    private static volatile long offThreadDraws;
    private static volatile long drawerOks;

    private static volatile long compositeNotReady;

    private static volatile long compositeRuns;
    private static volatile long compositeOk;
    private static volatile long compositeMiss;
    private static volatile long compositeNoGen;
    private static volatile long compositeOffThread;
    private static volatile long lastShrinkGen;

    /** Counters for the composite path (diagnostics only). */
    public static String compositeStatus() {
        long ok = worldEndOk;
        if (ok != lastStatusOk) {
            lastStatusOk = ok;
            crumb("worldEnd status ok=" + ok + " runs=" + worldEndRuns
                + " noGen=" + worldEndNoGen + " notReady=" + worldEndNotReady
                + " mismatch=" + worldEndMismatch);
        }
        return "worldEnd[ok=" + worldEndOk + " runs=" + worldEndRuns
            + " noGen=" + worldEndNoGen + " notReady=" + worldEndNotReady
            + " mismatch=" + worldEndMismatch + "]";
    }

    private static volatile long lastStatusOk;
    private static volatile long worldEndMismatch;


    static String glIdentity() {
        try {
            return org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR) + " | "
                + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER) + " | "
                + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
        } catch (Throwable t) {
            return "glGetString failed: " + t;
        }
    }

    static void crumb(String line) {
        try {
            fsrupsc.FsrLog.log(line);
        } catch (Throwable ignored) {}
    }

    public static void queueResolve() { /* TODO (N2 DLSS direct-color) */ }
    static void resolve(Object frame) { /* TODO (N2 DLSS direct-color) */ }

    private Upscaler() {}
}
