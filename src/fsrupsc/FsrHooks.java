package fsrupsc;

import fsrupsc.ObjectMotion;

/**
 * Hook entry points called from ByteBuddy advice.
 * Every method is exception-proof and allocation-light: advice runs on
 * the render thread, so a failure here must never break the frame.
 * Bodies are functional (viewport funnel, resolve triggers, motion
 * tagging) plus one-shot startup logging; no per-frame measurement.
 */
public final class FsrHooks {
    private static final boolean HOOK_LOOP = hookOn("loop");
    private static final boolean HOOK_INTERNAL = hookOn("internal");
    private static final boolean HOOK_FBO = hookOn("fbo");
    private static final boolean HOOK_DRAW = hookOn("draw");
    private static final boolean HOOK_VIEWPORT = hookOn("viewport");
    private static final boolean HOOK_FBO_BEGIN = hookOn("fbobegin");
    private static final boolean HOOK_FBO_ALLOC = hookOn("fboalloc");
    private static final boolean HOOK_PRESENT = hookOn("present");

    /**
     * Run the resolve at MultiTextureFBO2#render (game thread) instead of
     * TextureFBO#endDrawing (render thread).
     *
     * The composite is the semantically correct place -- world complete, not
     * yet sampled -- but under Mesa/Zink any GL issued there kills the JVM.
     * With native NVIDIA GL both positions work; the composite additionally
     * removes any doubt about the pass being ordered before the world draw.
     * Off by default. Set FSRUPSC_RESOLVE=composite or put "composite" in
     * %USERPROFILE%\Zomboid\fsr-upsc-resolve to enable it.
     */
    private static final boolean RESOLVE_AT_COMPOSITE = resolveAtSpec();

    /** True when the resolve runs from the queued composite drawer, not endDrawing. */
    public static boolean resolveAtComposite() { return RESOLVE_AT_COMPOSITE; }

    private static boolean resolveAtSpec() {
        String v = null;
        try {
            v = System.getenv("FSRUPSC_RESOLVE");
        } catch (Throwable ignored) {}
        if (v == null || v.trim().isEmpty()) {
            try {
                java.io.File f = new java.io.File(
                    System.getProperty("user.home"), "Zomboid/fsr-upsc-resolve");
                if (f.isFile()) {
                    v = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {}
        }
        if (v == null || v.trim().isEmpty()) {
            v = System.getProperty("fsrupsc.resolveAt", "");
        }
        return "composite".equalsIgnoreCase(v.trim());
    }

    /**
     * Hook mask: all|none|loop,internal,fbo,fbobegin,fboalloc,draw,viewport,present
     * (default all).
     *
     * Resolution order (first hit wins):
     *   1. env FSRUPSC_HOOKS
     *   2. marker file %USERPROFILE%\Zomboid\fsr-upsc-hooks
     *   3. -Dfsrupsc.hooks
     *
     * Default "all" -- agent-only installs write no hook property, and every
     * group matters: "fbobegin" is the resolve trigger (TextureFBO#endDrawing
     * -> resolveAtWorldEnd; an earlier default list missing it made the
     * resolve never run, with the world staying small in the corner) and
     * "fboalloc" sizes the world offscreen buffer at creation.
     *
     * The marker file exists because the JAM Launcher rebuilds the command
     * line and silently drops -D properties it does not know: a
     * -Dfsrupsc.hooks=none run still installed every advice. The marker
     * file is the only channel the launcher cannot rewrite.
     */
    public static String hookSpec() {
        String v = null;
        try {
            v = System.getenv("FSRUPSC_HOOKS");
        } catch (Throwable ignored) {}
        if (v == null || v.trim().isEmpty()) {
            try {
                java.io.File f = new java.io.File(
                    System.getProperty("user.home"), "Zomboid/fsr-upsc-hooks");
                if (f.isFile()) {
                    byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                    v = new String(b, java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {}
        }
        if (v == null || v.trim().isEmpty()) {
            v = System.getProperty("fsrupsc.hooks", "all");
        }
        return v.trim();
    }

    public static boolean hookOn(String name) {
        String v = hookSpec();
        if ("all".equalsIgnoreCase(v)) return true;
        if ("none".equalsIgnoreCase(v)) return false;
        for (String s : v.split(",")) {
            if (name.equalsIgnoreCase(s.trim())) return true;
        }
        return false;
    }

    public static boolean wantLoop() { return HOOK_LOOP; }
    public static boolean wantInternal() { return HOOK_INTERNAL; }
    public static boolean wantFbo() { return HOOK_FBO; }
    public static boolean wantDraw() { return HOOK_DRAW; }
    public static boolean wantViewport() { return HOOK_VIEWPORT; }
    /** S-1 FG spike: org.lwjglx Display#swapBuffers from the FgPresent handler. */
    public static boolean wantPresent() { return HOOK_PRESENT; }

    /** Suppress the rendered vision cone (opt-in; see RenderScale.coneSuppressed). */
    public static boolean wantConeSuppress() {
        try {
            return fsrupsc.RenderScale.coneSuppressed();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The view-cone displaySize substitution is opt-in.
     *
     * <p>It is a MemberSubstitution that cannot be verified by the offline
     * TransformCheck: rewriting an invocation to a method that lives in the
     * agent jar needs a type pool which sees that jar, and the check's isolated
     * redefine does not. Rather than ship an unverified transformation as the
     * default, it waits for the marker.
     */
    public static boolean wantVisCone() { return visConeMarker(); }

    /**
     * Scale the cone's vertex coordinates (opt-in; see ConeVertexScale).
     *
     * <p>Opt-in because it is a MemberSubstitution, which the offline
     * TransformCheck cannot verify: rewriting an invocation to a method in the
     * agent jar needs a type pool that sees that jar.
     */
    public static boolean wantConeVertex() { return markerFile("fsr-upsc-conevertex"); }

    /** Hold the viewport funnel off while the cone lightmap draws. */
    public static boolean wantConeHold() { return markerFile("fsrupsc-coneviewport"); }

    /** Report the scaled screen size to the aim reticle. */
    public static boolean wantReticleHold() { return markerFile("fsr-upsc-reticle"); }

    /**
     * Whether the composite may sample the resolve output instead of the world
     * texture. On unless {@code fsr-upsc-composite-swap} says otherwise.
     *
     * <p>Exists to separate the two halves of the pipeline. With the swap off but
     * the viewport still shrunk, the world is drawn into 1600x896 of the 2048x2048
     * offscreen buffer and the stock composite blits its (0,0,1920x1080) corner, so
     * the world lands at 83% in the top-left. That is a different artifact from the
     * one being chased, and whether the view cone stays glued to the ground under it
     * answers the question the viewport experiment cannot: the cone is either part
     * of the world image or something drawn on top of it.
     */
    public static boolean wantCompositeSwap() {
        String v = markerValue("fsr-upsc-composite-swap");
        return v == null || v.equalsIgnoreCase("on") || v.equals("1")
                || v.equalsIgnoreCase("true");
    }

    /** Report a hook-install failure where it will actually be read. */
    public static void hookFailed(String name, Throwable t) {
        try {
            FsrLog.log("HOOK FAILED " + name + " : " + t);
        } catch (Throwable ignored) {}
        try {
            System.err.println("[fsr-upsc] HOOK FAILED " + name + " : " + t);
        } catch (Throwable ignored) {}
    }

    private static boolean visConeMarker() {
        return markerFile("fsr-upsc-viscone");
    }

    private static boolean markerFile(String name) {
        String v = markerValue(name);
        return v != null && (v.equalsIgnoreCase("on") || v.equals("1")
                || v.equalsIgnoreCase("true"));
    }

    /** The marker's trimmed contents, or null when the file is absent. */
    private static String markerValue(String name) {
        try {
            java.io.File f = new java.io.File(System.getProperty("user.home"),
                "Zomboid/" + name);
            if (!f.isFile()) return null;
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return null;
        }
    }
    public static boolean wantFboBegin() { return HOOK_FBO_BEGIN; }
    public static boolean wantFboAlloc() { return HOOK_FBO_ALLOC; }

    /**
     * What the agent does to the view cone, for the start-of-session log line.
     *
     * <p>"pure" is the intended state: the cone is drawn entirely by the game.
     * Two independent opt-ins used to move it, and both are known to be wrong:
     *
     * <ul>
     *   <li>{@code fsrupsc-nocone} skips {@code IsoWorld.DrawPlayerCone} and
     *       {@code DrawPlayerCone2} and clears the cone lightmap, so the cone
     *       overlay disappears entirely.
     *   <li>{@code fsr-upsc-viscone} rescales the blur shader's displaySize in
     *       {@code VisibilityPolygon2.renderToScreen}. Upstream carries the same
     *       transformation behind {@code devUpscalerStockVisBlur} and names the
     *       failure it causes: the "second view cone" bug. Observed here as a
     *       doubled cone edge, so displaySize is not a position input and must
     *       stay stock.
     * </ul>
     *
     * <p>Logged rather than assumed, because a marker left behind silently
     * changes what the player sees and nothing else reports it.
     */
    private static String coneSpec() {
        String sup = "off", vis = "off";
        try {
            if (fsrupsc.RenderScale.cone1Suppressed()) sup = "cone1-OFF";
            if (fsrupsc.RenderScale.cone2Suppressed()) sup = sup.equals("off") ? "cone2-OFF" : "BOTH-OFF";
        } catch (Throwable ignored) {}
        try { if (wantVisCone()) vis = "DISPLAYSIZE-RESCALED"; } catch (Throwable ignored) {}
        if ("off".equals(sup) && "off".equals(vis)) return "pure";
        return sup + "/" + vis;
    }

    private static volatile boolean suppress;

    public static boolean suppressViewport() { return suppress; }

    public static void suppressViewport(boolean on) { suppress = on; }

    /** GL context proxy: capabilities are per-context. Identity distinguishes contexts. */
    public static String ctxId() {
        try {
            Object caps = org.lwjgl.opengl.GL.getCapabilities();
            return caps == null ? "null" : "caps@" + Integer.toHexString(System.identityHashCode(caps));
        } catch (Throwable t) {
            return "none";
        }
    }

    private static volatile long fboLogCount;
    private static volatile long fboBeginCount;
    private static volatile long fboEndCount;

    /**
     * Frame-position counters, read by the resolve trace.
     *
     * <p>They answer "where in the frame did the resolve drawer actually run",
     * which is the open question: the drawer is queued from the game-thread
     * composite but executed by the sprite ring buffer, and until this is known
     * the input cannot be judged ready or stale.
     */
    public static long fboBeginCount() { return fboBeginCount; }
    public static long fboEndCount() { return fboEndCount; }
    public static long fboRenderCount() { return fboRender; }
    public static long compositeExitCount() { return compositeExit; }
    public static long drawModelCount() { return drawModel; }
    public static long viewportCount() { return viewports; }

    /**
     * FBO pass bookkeeping.
     *
     * onFboEnd is the resolve insertion point: endDrawing fires on the
     * render thread ("main") right after the world pass finished drawing
     * and before MultiTextureFBO2#render composites it, and GL issued
     * here was proven safe (group "fbobegin" survived even with a
     * GL-calling body). The composite itself is game-thread only, and any
     * GL there kills the JVM, so nothing may be drawn from it.
     */
    public static void onFboBegin(Object fbo) {
        fboBeginCount++;
        fboLogCount++;
        // Open the world-pass window before anything else: the camera-size
        // override must already be live while the world pass draws.
        fsrupsc.RenderScale.bindWorldFboRef(fbo);
        if (fsrupsc.Upscaler.isWorldFbo(fbo) && fsrupsc.FsrHooks.isRenderThread()) {
            fsrupsc.CameraJitter.beginWorldPass();
        }
        fsrupsc.RenderScale.enterWorldPass(fbo);
        if (fboBeginCount <= 16) {
            emit("fbo begin #" + fboBeginCount + " id=" + fsrupsc.Gl.boundFbo()
                + (fsrupsc.Upscaler.isWorldFbo(fbo) ? " WORLD" : ""));
        }
    }

    public static void onFboEnd(Object fbo) {
        fboEndCount++;
        try {
            // Close the window first: after endDrawing the next draws are not
            // the world pass and must see the native screen size.
            fsrupsc.RenderScale.exitWorldPass(fbo);
            if (fboEndCount <= 16) {
                emit("fbo end   #" + fboEndCount
                    + (fsrupsc.Upscaler.isWorldFbo(fbo) ? " WORLD" : ""));
            }
            // TextureFBO.getBufferId() is PZ's internal counter, NOT the GL
            // framebuffer name -- binding it produced an FBO that writes
            // nowhere (read-back returned all zeros). What PZ actually bound
            // is authoritative, and we run right after its own bind.
            int glFbo = -1;
            int glRead = -1;
            try {
                glFbo = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                glRead = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
            } catch (Throwable ignored) {}
            fsrupsc.Upscaler.resolveAtWorldEnd(fbo, glFbo, glRead);
            // undo the camera jitter regardless of which resolve path ran
            fsrupsc.CameraJitter.endWorldPass();
            if (fboEndCount <= 3) {
                emit("fbo end #" + fboEndCount
                    + " pzId=" + fsrupsc.Upscaler.pzFboId()
                    + " glDraw=" + glFbo + " glRead=" + glRead
                    + " " + fsrupsc.Upscaler.compositeStatus());
            }
        } catch (Throwable ignored) {}
    }

    public static String fboStatus() {
        return "fboBegin=" + fboBeginCount + " fboEnd=" + fboEndCount
            + " fboRender=" + fboRender + " compositeExit=" + compositeExit
            + " drawModel=" + drawModel
            + " viewports=" + viewports;
    }

    private static volatile long renderLoop;
    private static volatile long renderInternal;
    private static volatile long fboRender;
    private static volatile long drawModel;
    private static volatile long viewports;
    private static volatile long scaled;
    private static volatile long startFrames;
    private static volatile boolean loopLogged;
    private static volatile boolean fboLogged;
    private static volatile boolean drawLogged;

    private static volatile Thread renderThread;

    public static boolean isRenderThread() {
        return Thread.currentThread() == renderThread;
    }

    public static void onRenderLoop() {
        if (!HOOK_LOOP) return;
        renderThread = Thread.currentThread();
        renderLoop++;
        if (!loopLogged) {
            loopLogged = true;
            emit("hook fired: RenderThread.renderLoop on " + Thread.currentThread().getName());
            try {
                emit("downscale=" + fsrupsc.RenderScale.downscaleEnabled()
                    + " renderScale=" + fsrupsc.RenderScale.describe());
            } catch (Throwable t) {
            }
            try {
                // One-time GL 4.3+ compute probe (M0): version + renderer +
                // capabilities, logged once. No GL objects created.
                fsrupsc.Fsr2Compute.probeOnce();
            } catch (Throwable ignored) {}
            try {
                emit("hooks armed: " + hookSpec() + " cone=" + coneSpec());
            } catch (Throwable t) {
                emit("hooks init note: " + t);
            }
        }
    }

    /** Dual sink: console + agent log file. */
    public static void emit(String line) {
        System.out.println("[fsr-upsc] " + line);
        FsrLog.log(line);
    }

    public static void onRenderInternalEnter() {
        if (!HOOK_INTERNAL) return;
        renderInternal++;
    }

    /** World-frame start (game thread): enqueue the viewport shrink. No GL here. */
    public static void onWorldStart(int w, int h) {
        if (!HOOK_VIEWPORT) return;
        try {
            if (!fsrupsc.Upscaler.drawsOutput()) return;
            if (fsrupsc.RenderScale.scale() >= 0.999f) return;
            if (zombie.characters.IsoPlayer.numPlayers != 1) return;
            if (w != zombie.core.Core.width || h != zombie.core.Core.height) return;
            // NOTE: no worldFboBound() check here: GL state reads on the game
            // thread are stale and almost always decline. The drawer itself
            // re-checks on the render thread with fresh state.
            // scaledRect, not round(w * scale): the viewport funnel snaps to a
            // multiple of 16, so it installs 1600x896 while round() here gave
            // 1600x900. Two writers, two sizes -- the second one won and the
            // resolve source no longer matched the render viewport.
            int[] sz = fsrupsc.RenderScale.scaledRect(w, h);
            zombie.core.SpriteRenderer.instance.drawGeneric(
                new fsrupsc.Resolve.ViewportDrawer(sz[0], sz[1]));
        } catch (Throwable t) {
            FsrLog.log("onWorldStart failed: " + t);
        }
    }

    /** World-frame end (game thread): kept for counters; resolve moved to preComposite. */
    public static void onWorldEnd() {
    }

    /**
     * Composite entry (MultiTextureFBO2#render, the final world blit).
     *
     * Opens the resolve window and, when resolve-at-composite is armed,
     * queues the resolve. GL issued here (game thread) is not safe, so
     * the body only queues -- the drawer runs on the render thread.
     */
    public static void preComposite(Object fbo) {
        // Open before the counter gate: the window must be up whenever the
        // composite runs, including the frames where we only observe.
        fsrupsc.ResolveOutput.openWindow();
        if (!HOOK_FBO) return;
        fboRender++;
        // The final resolve position. MultiTextureFBO2#render is reached from
        // the game thread, where the world texture is complete and not yet
        // sampled -- exactly what an in-place upscale needs.
        //
        // Disabled by default: on Mesa/Zink this thread cannot issue GL at all
        // (it kills the JVM), and Zink is not a supported configuration anyway.
        // Enable with -Dfsrupsc.resolveAt=composite (marker file
        // fsr-upsc-resolve = composite) when running native NVIDIA GL.
        if (RESOLVE_AT_COMPOSITE) {
            try {
                // Queue only: the drawer runs later on the render thread.
                fsrupsc.Upscaler.resolveAtComposite();
                if (fboRender <= 3) {
                    emit("composite resolve #" + fboRender
                        + " on " + Thread.currentThread().getName()
                        + " " + fsrupsc.Upscaler.compositeStatus());
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void postComposite() {
        compositeExit++;
        fsrupsc.ResolveOutput.closeWindow();
    }

    // -------------------------------------------------------------------------
    // World offscreen buffer allocation (group "fboalloc").
    // -------------------------------------------------------------------------

    /**
     * Scale factor in permille for Core.offscreenBuffer's allocation, or 0
     * to leave it alone. Only the world's own buffer qualifies, so save
     * thumbnails and other MultiTextureFBO2 users keep their own size.
     */
    public static int worldFboScalePermille(Object self) {
        return fsrupsc.RenderScale.worldFboScalePermille(self);
    }

    /** Exact target allocation for the world offscreen buffer, or null. */
    public static int[] worldFboTargetSize(Object self, int w, int h) {
        return fsrupsc.RenderScale.worldFboTargetSize(self, w, h);
    }

    public static void noteWorldFboCreated(int w, int h) {
        worldFboW = w;
        worldFboH = h;
        emit("world fbo requested " + w + "x" + h
            + " (scale " + fsrupsc.RenderScale.scale() + ")");
    }

    /** One-shot: why a create() was left alone. */
    public static void noteFboCreateDeclined(Object self, int w, int h) {
        long n = ++createDeclined;
        if (n > 4) return;
        String why;
        try {
            if (!fsrupsc.RenderScale.downscaleEnabled()) {
                why = "downscale disabled";
            } else if (!fsrupsc.RenderScale.active()) {
                why = "render scale inactive";
            } else {
                Object world = zombie.core.Core.getInstance().offscreenBuffer;
                why = (self == world)
                    ? "is world buffer but scale=" + fsrupsc.RenderScale.scale()
                    : "not the world buffer";
            }
        } catch (Throwable t) {
            why = "probe failed: " + t;
        }
        emit("create declined #" + n + " " + w + "x" + h + ": " + why);
    }

    /**
     * One-line dump of every size the composite could be using. Pure Java
     * getters, safe to call from the game thread.
     */
    public static String offscreenGeometry() {
        try {
            zombie.core.Core core = zombie.core.Core.getInstance();
            zombie.core.textures.MultiTextureFBO2 m = core.offscreenBuffer;
            StringBuilder sb = new StringBuilder("geo[");
            sb.append("screen=").append(core.width).append('x').append(core.height);
            sb.append(" tileScale=").append(zombie.core.Core.tileScale);
            sb.append(" offW=").append(core.getOffscreenWidth(0));
            sb.append(" offH=").append(core.getOffscreenHeight(0));
            sb.append(" trueW=").append(core.getOffscreenTrueWidth());
            sb.append(" trueH=").append(core.getOffscreenTrueHeight());
            if (m != null) {
                sb.append(" lvl0=").append(m.getWidth(0)).append('x').append(m.getHeight(0));
                sb.append(" zoom0=").append(m.getDisplayZoom(0));
                zombie.core.textures.TextureFBO cur = m.current;
                if (cur != null) {
                    sb.append(" tex=").append(cur.getWidth()).append('x').append(cur.getHeight());
                    sb.append(" fboId=").append(cur.getBufferId());
                } else {
                    sb.append(" current=null");
                }
            } else {
                sb.append(" offscreenBuffer=null");
            }
            sb.append(']');
            return sb.toString();
        } catch (Throwable t) {
            return "geo[failed: " + t + "]";
        }
    }

    private static volatile long createDeclined;

    private static volatile int worldFboW;
    private static volatile int worldFboH;

    private static volatile long compositeExit;

    /** Viewport funnel. Returns the scaled rect or null for passthrough. */
    public static int[] scaleViewport(int x, int y, int w, int h) {
        if (!HOOK_VIEWPORT) return null;
        ++viewports;
        // Diagnostic: log every non-fullscreen request (backbone of the
        // 640x480 hunt). Fullscreen calls are counted silently.
        try {
            int sw = 1920;
            int sh = 1080;
            try {
                sw = zombie.core.Core.width;
                sh = zombie.core.Core.height;
            } catch (Throwable ignored) {}
            if (viewports <= 20 && !(x == 0 && y == 0 && w == sw && h == sh)) {
                emit("vp odd: " + fsrupsc.RenderScale.debugViewport(x, y, w, h)
                    + " ctx=" + ctxId()
                    + " on " + Thread.currentThread().getName());
            }
        } catch (Throwable ignored) {}
        try {
            int[] out = new int[4];
            if (fsrupsc.RenderScale.requestedViewport(x, y, w, h, out)) {
                long n = ++scaled;
                // Authoritative "the world is being drawn small right now"
                // signal; the composite resolve gates on this generation.
                fsrupsc.RenderScale.noteShrunk();
                // Build the FSR programs and targets here, on the render
                // thread. Doing it at the composite (game thread) killed the
                // JVM inside glCreateShader.
                //
                // ensureReady takes the OUTPUT size (the screen) and derives the input
                // itself with RenderScale.scaledRect, the same function that
                // produced out[]. Passing the viewport size here inverted the
                // two: the resolve then targeted 1600x896 and its output was
                // stretched to fill 1920x1080, a 1.44x over-magnification.
                try {
                    fsrupsc.Resolve.ensureReady(
                        zombie.core.Core.width, zombie.core.Core.height);
                } catch (Throwable ignored) {}
                if (n <= 20) {
                    emit("viewport " + x + "," + y + "," + w + "," + h
                        + " -> " + out[0] + "," + out[1] + "," + out[2] + "," + out[3]
                        + " on " + Thread.currentThread().getName());
                }
                return out;
            }
        } catch (Throwable t) {
            System.err.println("[fsr-upsc] scaleViewport failed:");
            t.printStackTrace();
            FsrLog.log("scaleViewport failed: " + t);
        }
        return null;
    }

    public static void tagObject(Object slot) {
        if (!HOOK_DRAW) return;
        drawModel++;
        if (!drawLogged) {
            drawLogged = true;
            emit("hook fired: TextureDraw.drawModel on " + Thread.currentThread().getName());
        }
        try {
            int id = fsrupsc.ObjectMotion.record(slot);
            // Stencil GL only on the render thread; record is pure Java.
            if (id != 0 && isRenderThread()) fsrupsc.ObjectMotion.beginStencil(id);
        } catch (Throwable t) {
            if (drawModel < 4) {
                System.err.println("[fsr-upsc] tagObject failed:");
                t.printStackTrace();
                FsrLog.log("tagObject failed: " + t);
            }
        }
    }

    public static void untagObject() {
        try {
            fsrupsc.ObjectMotion.endStencil();
        } catch (Throwable ignored) {}
    }

    public static String status() {
        return "hooks loop=" + renderLoop + " internal=" + renderInternal
            + " fbo=" + fboRender + " draw=" + drawModel
            + " vp=" + viewports + "/" + scaled;
    }

    private FsrHooks() {}
}
