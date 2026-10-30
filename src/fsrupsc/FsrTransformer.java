package fsrupsc;

import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.asm.MemberSubstitution.Substitution;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/**
 * Load-time transformer for the upscaler frame path.
 * Observe-only in this iteration (counters + once-per-hook logging,
 * no behavior change). Advice classes reference only agent/fsrupsc
 * helpers, never game types (LinkageError lesson from Z-UET).
 */
public final class FsrTransformer {

    /**
     * Late-load mode (ZombieBuddy entry): retransform classes that are
     * already loaded when install runs. Every hook in this agent is a
     * code-only rewrite (advice inline or member substitution), so
     * retransformation is legal; the flag changes only when hooks can fire,
     * never the rewritten bytes.
     */
    private static boolean lateMode;

    /**
     * Byte locator for advice weaving. Null = the advice class's own
     * classloader (javaagent premain: resources resolve fine). The ZombieBuddy
     * entry passes a locator over the jar file because its loader hides both
     * resources and class bytes ("Could not locate class file").
     */
    private static volatile ClassFileLocator adviceLocator;

    /** Advice entry point with the active byte locator. */
    static Advice advice(Class<?> a) {
        return Advice.to(a, adviceLocator != null
                ? adviceLocator
                : ClassFileLocator.ForClassLoader.of(a.getClassLoader()));
    }

    /** Install all hook transformers. Failures are per-target and logged. */
    public static void install(Instrumentation inst) {
        install(inst, false, null);
    }

    /** javaagent form (no custom locator; see install(inst, late, locator)). */
    public static void install(Instrumentation inst, boolean late) {
        install(inst, late, null);
    }

    /**
     * Install all hook transformers. With {@code late} set, also retransform
     * matching classes that are loaded already; without it (javaagent
     * premain) nothing is loaded yet and the default strategy applies.
     * {@code locator} overrides where advice class bytes are read from.
     */
    public static void install(Instrumentation inst, boolean late, ClassFileLocator locator) {
        lateMode = late;
        adviceLocator = locator;
        StringBuilder done = new StringBuilder();
        if (FsrHooks.wantLoop()
                && patch(inst, "zombie.core.opengl.RenderThread", "renderLoop", RenderLoopEnter.class)) {
            done.append("RenderThread.renderLoop,");
        }
        // renderLoop is entered once and never returns: a retransform while the
        // loop is already running leaves the live frame on the old bytecode, so
        // the advice there stays dead until the JVM restarts (seen in the
        // ZombieBuddy late entry: "Transformed RenderThread" but the hook never
        // fired, renderThread stayed null and the world-end resolve silently
        // starved). renderStep is the per-frame call inside that loop, so its
        // advice goes live on the very next frame. At class-load time
        // (javaagent) both apply before the loop starts and the extra
        // per-frame re-arm is a no-op.
        if (FsrHooks.wantLoop()
                && patch(inst, "zombie.core.opengl.RenderThread", "renderStep", RenderLoopEnter.class)) {
            done.append("RenderThread.renderStep,");
        }
        if (FsrHooks.wantInternal()
                && patch(inst, "zombie.GameWindow", "renderInternal", RenderInternalEnter.class)) {
            done.append("GameWindow.renderInternal,");
        }
        // S-1 FG spike: present control. Display#swapBuffers is the one GLFW
        // swap PZ presents every frame through; the intercept adds optional
        // inserted presents (FgPresent) plus interval bookkeeping.
        if (FsrHooks.wantPresent()
                && patch(inst, "org.lwjglx.opengl.Display", "swapBuffers", SwapBuffersEnter.class)) {
            done.append("Display.swapBuffers,");
        }
        if (FsrHooks.wantFbo() && patchFboRender(inst)) {
            done.append("MultiTextureFBO2.render,");
        }
        if (FsrHooks.wantFboBegin() && patchFboBegin(inst)) {
            done.append("TextureFBO.startDrawing/endDrawing,");
        }
        if (FsrHooks.wantFboAlloc() && patchFboAlloc(inst)) {
            done.append("MultiTextureFBO2.create,");
        }
        if (FsrHooks.wantCompositeSwap() && patchComposite(inst)) {
            done.append("TextureFBO.getTexture,");
        }
        if (FsrHooks.wantVisCone() && patchVisConeDisplay(inst)) {
            done.append("VisibilityPolygon2.renderToScreen[rescale],");
        }
        // The cone marker now means the vertex scale only. The endFrame/overlay
        // substitutions it used to carry are retired: the 02:06 run on 42.22
        // (e013dbb529) applied both cleanly and the cone vanished. endFrame's
        // getOffscreenWidth/Height locals also feed glDoStartFrame(NoZoom)(w, h) --
        // the frame bounds of the whole chunk-composite pass -- and MemberSubstitution
        // cannot scope by use site, so reframing the cone reframes the pass too.
        if (FsrHooks.wantConeVertex() && patchConeVertexScale(inst)) {
            done.append("VisibilityPolygon2$Drawer cone vertex scale,");
        }
        // The renderMain advice traces were retired 2026-10-30: Byte Buddy could
        // not resolve their advice classes ("Could not locate class file for
        // fsrupsc.ConeTraceEnter") even though the classes are in the jar, and
        // the burial question they existed to answer was settled by the
        // conedepth probe. The live vertex-value probe now rides inside
        // ConeVertexScale.x/y (member substitution, no advice bytecode needed).
        if (FsrHooks.wantConeDepth() && patchConeDepth(inst)) {
            done.append("Drawer.renderNew depth relax,");
        }
        // the reticle hold rides along in patchFboRender: one install per type
        if (FsrHooks.wantConeHold() && patchConeHold(inst)) {
            done.append("cone viewport hold,");
        }
        if (FsrHooks.wantConeSuppress() && patchConeSuppression(inst)) {
            done.append("IsoWorld.DrawPlayerCone,");
        }
        // Lua control exposure: rides on the Lua environment setup so that
        // fsrupsc.FsrControl is reachable from mod Lua on every launch
        // (see FsrExposeHook). Independent of the hook groups.
        if (patch(inst, "zombie.Lua.LuaManager$Exposer", "exposeAll", FsrExpose.class)) {
            done.append("LuaManager$Exposer[exposeAll],");
        }

        if (FsrHooks.wantViewport() && patchCameraScreenSize(inst)) {
            done.append("IsoCamera.getScreenWidth/Height,");
        }
        if (FsrHooks.wantDraw() && patchDrawModel(inst)) {
            done.append("TextureDraw.drawModel,");
        }
        if (FsrHooks.wantViewport() && patchViewport(inst)) {
            done.append("TextureDraw.glViewport,");
        }
        String targets = "[fsr-upsc] transformer registered targets=" + done;
        // System.out is only captured into console.txt after premain, so the
        // agent log is the authoritative record of which hooks installed.
        System.out.println(targets);
        try {
            fsrupsc.FsrLog.log(targets);
        } catch (Throwable ignored) {}
    }

    /** Single enter-advice transform. */
    static boolean patch(Instrumentation inst, String type, String method, Class<?> advice) {
        try {
            base()
                    .type(ElementMatchers.named(type))
                    .transform((b, td, cl, m, pd) -> b.visit(
                            advice(advice).on(ElementMatchers.named(method))))
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed " + type + ": ", t);
            return false;
        }
    }

    /** Final composite: one transformer visiting render() enter and exit. */
    static boolean patchFbo(Instrumentation inst) {
        return patchFboRender(inst) && patchFboBegin(inst);
    }

    /**
     * Group "fbo": MultiTextureFBO2#render, plus the reticle hold on
     * {@code renderCursor} when it is enabled.
     *
     * <p>One AgentBuilder per target type. A second install on a type the agent has
     * already transformed is skipped without a word, so the reticle hook was
     * silently absent while the composite counter worked -- the type appeared
     * transformed and the hook did not exist. Same family of mistake as chaining two
     * {@code .type()} calls, which reported only the last matcher's result.
     */
    static boolean patchFboRender(Instrumentation inst) {
        try {
            final boolean reticle = ReticleHold.enabled();
            base()
                    .type(ElementMatchers.named("zombie.core.textures.MultiTextureFBO2"))
                    .transform((b, td, cl, mod, pd) -> {
                        var v = b.visit(advice(FboRenderEnter.class).on(
                                        ElementMatchers.named("render")
                                                .and(ElementMatchers.not(ElementMatchers.isStatic()))))
                                .visit(advice(FboRenderExit.class).on(
                                        ElementMatchers.named("render")
                                                .and(ElementMatchers.not(ElementMatchers.isStatic()))));
                        if (reticle) {
                            v = v.visit(advice(ReticleHold.class).on(
                                            ElementMatchers.named("renderCursor")
                                                    .and(ElementMatchers.takesArguments(
                                                            zombie.characters.IsoPlayer.class))))
                                    .visit(advice(ReticleHold.End.class).on(
                                            ElementMatchers.named("renderCursor")
                                                    .and(ElementMatchers.takesArguments(
                                                            zombie.characters.IsoPlayer.class))));
                        }
                        return v;
                    })
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("MultiTextureFBO2.render", t);
            return false;
        }
    }

    /**
     * Group "fbobegin": TextureFBO#startDrawing/#endDrawing.
     * Split out from "fbo" because these fire on every FBO pass of every
     * frame (menu included) and therefore are the prime suspect when the
     * process dies on the first world frame.
     */
    static boolean patchFboBegin(Instrumentation inst) {
        try {
            base()
                    .type(ElementMatchers.named("zombie.core.textures.TextureFBO"))
                    .transform((b, td, cl, mod, pd) -> {
                        var v = b
                                .visit(advice(FboBeginEnter.class).on(
                                        ElementMatchers.named("startDrawing")))
                                .visit(advice(FboEndEnter.class).on(
                                        ElementMatchers.named("endDrawing")));
                        return v;
                    })
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed TextureFBO begin/end: ", t);
            return false;
        }
    }

    /**
     * IsoCamera.getScreenWidth / getScreenHeight: report the shrunken size
     * during the world pass, so the camera frames the area the viewport
     * actually covers.
     *
     * <p>The camera's world extent comes from the screen size, never from the
     * viewport:
     *
     * <pre>
     *   Core.getOffscreenWidth -&gt; MultiTextureFBO2.getWidth
     *     -&gt; IsoCamera.getScreenWidth * zoom * (tileScale / 2)
     * </pre>
     *
     * <p>Shrinking only the viewport therefore leaves the camera framing
     * Core.width pixels into a narrower viewport: the right and top of the
     * world are never drawn, and the upscale stretches the crop -- which
     * displaces the view up and to the right.
     *
     * <p>Guarded to the render thread inside the advice, because the composite
     * reads the same accessors for its destination rectangle and must see the
     * real size. Scaling there too would scale the UI.
     */
    static boolean patchCameraScreenSize(Instrumentation inst) {
        try {
            base()
                    .type(ElementMatchers.named("zombie.iso.IsoCamera"))
                    .transform((b, td, cl, mod, pd) -> b
                            .visit(advice(IsoCameraScreenSize.class).on(
                                    ElementMatchers.named("getScreenWidth")
                                            .and(ElementMatchers.takesArguments(1))
                                            .and(ElementMatchers.isStatic())))
                            .visit(advice(IsoCameraScreenHeight.class).on(
                                    ElementMatchers.named("getScreenHeight")
                                            .and(ElementMatchers.takesArguments(1))
                                            .and(ElementMatchers.isStatic()))))
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed IsoCamera screen size: ", t);
            return false;
        }
    }

    

    /**
     * VisibilityPolygon2$Drawer.renderToScreen: scale the view cone's
     * displaySize.
     *
     * <p>That method hands the blur shader a screenSize and a displayOrigin
     * taken from IsoCamera (so the camera override shrinks them) but a
     * displaySize read straight off {@code TextureFBO.getWidth()/getHeight()},
     * i.e. the framebuffer allocation. The two disagree and the cone is
     * displaced from the player.
     *
     * <p>Only those two call sites are substituted. Hooking
     * {@code Vector2.set} to achieve the same thing scaled every vector the
     * world pass computes and double-drew the player -- the cone's values are
     * one of four, not all of them.
     *
     * <p>renderToScreen is private on a package-private class, which rules out
     * Advice (it inlines into the target class and would fail with
     * IllegalAccessError) but not MemberSubstitution, which rewrites the
     * method body rather than adding a call to a neighbour.
     */
    /**
     * IsoWorld.DrawPlayerCone / DrawPlayerCone2: skip the cone, and erase its
     * texture so no stale overlay survives.
     *
     * <p>The cone is a rendered lighting overlay; the visibility data it draws
     * from (VisibilityPolygon2) is untouched, so gameplay is unchanged. It is
     * also the only part of the world pass whose scale basis does not follow
     * the camera -- its texture is fixed at Core/4 and its blur shader's
     * displaySize is the framebuffer allocation -- which is why the
     * displacement tracked the zoom multiplier. Suppressing it removes the
     * inconsistency instead of trying to reconcile four scale bases.
     */
    static boolean patchConeSuppression(Instrumentation inst) {
        try {
            base()
                    .type(ElementMatchers.named("zombie.iso.IsoWorld"))
                    .transform((b, td, cl, mod, pd) -> b
                            .visit(advice(ConeSuppress.class).on(
                                    ElementMatchers.named("DrawPlayerCone")
                                            .and(ElementMatchers.takesNoArguments())
                                            .and(ElementMatchers.not(
                                                    ElementMatchers.isStatic()))))
                            .visit(advice(ConeSuppress.Second.class).on(
                                    ElementMatchers.named("DrawPlayerCone2")
                                            .and(ElementMatchers.takesNoArguments())
                                            .and(ElementMatchers.not(
                                                    ElementMatchers.isStatic())))))
                    .type(ElementMatchers.named(
                            "zombie.viewCone.ViewConeTextureFBO"))
                    .transform((b, td, cl, mod, pd) -> b
                            .visit(advice(ConeClear.class).on(
                                    ElementMatchers.named("startDrawing")
                                            .and(ElementMatchers.takesNoArguments())
                                            .and(ElementMatchers.not(
                                                    ElementMatchers.isStatic())))))
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed cone suppression: ", t);
            return false;
        }
    }
    private static net.bytebuddy.description.method.MethodDescription declared(Class<?> owner, String name) {
        net.bytebuddy.description.method.MethodList<? extends net.bytebuddy.description.method.MethodDescription> ms = new net.bytebuddy.description.type.TypeDescription.ForLoadedType(owner).getDeclaredMethods();
        for (int i = 0; i < ms.size(); i++) {
            if (ms.get(i).getActualName().equals(name)) return ms.get(i);
        }
        throw new IllegalStateException("no method " + name + " on " + owner);
    }
    static boolean patchVisConeDisplay(Instrumentation inst) {
        try {
            net.bytebuddy.description.method.MethodDescription w =
                    declared(fsrupsc.VisConeDisplay.class, "displayWidth");
            net.bytebuddy.description.method.MethodDescription h =
                    declared(fsrupsc.VisConeDisplay.class, "displayHeight");
            // Restrict to TextureFBO. Without this the matcher also takes
            // Texture.getWidth()/getHeight(), whose receiver is a Texture, and
            // the substitution then fails for the whole class:
            //   "Cannot invoke displayWidth(TextureFBO) on parameter 0 of type
            //    zombie.core.textures.Texture"
            // which is what silently disabled the hook before.
            ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription> texFbo =
                    ElementMatchers
                            .<net.bytebuddy.description.type.TypeDescription>
                            named("zombie.core.textures.TextureFBO");
            ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> mWidth =
                    ElementMatchers.<net.bytebuddy.description.method.MethodDescription>
                            named("getWidth").and(ElementMatchers.takesNoArguments())
                            .and(ElementMatchers.isDeclaredBy(texFbo));
            ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> mHeight =
                    ElementMatchers.<net.bytebuddy.description.method.MethodDescription>
                            named("getHeight").and(ElementMatchers.takesNoArguments())
                            .and(ElementMatchers.isDeclaredBy(texFbo));
            base()
                    .type(ElementMatchers.named(
                            "zombie.vispoly.VisibilityPolygon2$Drawer"))
                    .transform((b, td, cl, mod, pd) -> b
                            .visit(MemberSubstitution.relaxed()
                                    .method(mWidth)
                                    .replaceWith(new Substitution.ForMethodInvocation
                                            .OfGivenMethod(
                                                    w))
                                    .method(mHeight)
                                    .replaceWith(new Substitution.ForMethodInvocation
                                            .OfGivenMethod(
                                                    h))
                                    .on(ElementMatchers.named("renderToScreen"))))
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed visConeDisplay: ", t);
            return false;
        }
    }

    /**
 * {@code FBORenderChunkManager.endFrame}: make the cone's rectangle the one the
 * world was actually drawn at.
 *
 * <p>This is the cone's real code. Nine earlier attempts targeted the old
 * implementation and every one of them was dead on this install:
 *
 * <ul>
 *   <li>{@code IsoWorld.DrawPlayerCone} / {@code DrawPlayerCone2} -- zero call
 *       sites across 23829 classes; each name appears once in
 *       {@code IsoWorld.class}, i.e. only its own declaration.
 *   <li>{@code VisibilityPolygon2.renderToScreen} -- the renderNew path, disabled.
 *   <li>{@code VisibilityPolygon2.renderMain} -- behind a developer option that is
 *       off; an unconditional entry log proved it was installed and never entered.
 *   <li>{@code ViewConeTextureFBO} -- only the dead methods above reference it, so
 *       the class is never loaded and cannot be instrumented at all.
 * </ul>
 *
 * <p>The live one, found by following {@code SceneShaderStore.chunkRenderShader}:
 *
 * <pre>
 *   FBORenderChunkManager.endFrame()
 *     Core.getOffscreenWidth/Height      ← cone basis: 1920 * zoom * tileScale/2 = 2048
 *     combinedTexture.getWidth/Height    ← the cone lightmap
 *     SceneShaderStore.chunkRenderShader
 *     Core.width / Core.height           ← 1920x1080
 * </pre>
 *
 * <p>Two different sizes are mixed in one method. The world is drawn at 1600x896
 * while the cone is laid out against 2048, which is the displacement. Substituting
 * the scaled screen rect gives 1600x896 and the two agree.
 *
 * <p>Scoped to this method. {@code getOffscreenWidth} has 65 call sites spanning
 * culling, input clamping, UI layout and weather, so the shared return value is
 * untouched.
 *
 * <p>This is the first attempt at a method the runs demonstrate is reached: the
 * existing {@code fbo} group already instruments it and logs every frame.
 */
static boolean patchConeEndFrame(Instrumentation inst) {
        try {
            net.bytebuddy.description.method.MethodDescription qw =
                    declared(fsrupsc.ConeVertexScale.class, "quadWidth");
            net.bytebuddy.description.method.MethodDescription qh =
                    declared(fsrupsc.ConeVertexScale.class, "quadHeight");
            ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription>
                    coreType =
                    ElementMatchers
                            .<net.bytebuddy.description.type.TypeDescription>
                            named("zombie.core.Core");
            ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription>
                    onCore = ElementMatchers.isDeclaredBy(coreType)
                    .and(ElementMatchers.takesArguments(int.class))
                    .and(ElementMatchers.returns(int.class));

            base()
                    .type(ElementMatchers.named(
                            "zombie.iso.fboRenderChunk.FBORenderChunkManager"))
                    .transform((b, td, cl, mod, pd) -> b
                            .visit(net.bytebuddy.asm.MemberSubstitution.relaxed()
                                    .method(ElementMatchers.<net.bytebuddy.description.method.MethodDescription>
                                                    named("getOffscreenWidth").and(onCore))
                                    .replaceWith(new net.bytebuddy.asm.MemberSubstitution.Substitution
                                            .ForMethodInvocation.OfGivenMethod(qw))
                                    .method(ElementMatchers.<net.bytebuddy.description.method.MethodDescription>
                                                    named("getOffscreenHeight").and(onCore))
                                    .replaceWith(new net.bytebuddy.asm.MemberSubstitution.Substitution
                                            .ForMethodInvocation.OfGivenMethod(qh))
                                    .on(ElementMatchers.named("endFrame")
                                            .and(ElementMatchers.takesNoArguments()))))
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("coneEndFrame", t);
            return false;
        }
    }

    /**
 * SpriteRenderer#render: log any draw that carries a quarter-resolution texture.
 *
 * <p>Profiling established the two live cone entry points, both of which end in
 * SpriteRenderer:
 *
 * <pre>
 *   IsoWorld.DrawPlayerCone()  -&gt; SpriteRenderer.glBuffer()
 *   IsoWorld.DrawPlayerCone2() -&gt; SpriteRenderer.render(Texture, ...)
 * </pre>
 *
 * <p>{@code render} has 113 call sites, so the cone cannot be identified by caller,
 * but it can by texture: the cone lightmap is a quarter of the screen resolution
 * (Core/4, so 480x270 at 1920x1080) and no ordinary sprite shares that size. The
 * rectangle logged next to it is what the draw asks the viewport to cover, which is
 * the number that has to agree with the 1600x896 world pass.
 *
 * <p>One install per overload, each with its own advice class. Combining overloads
 * into one class made Byte Buddy apply the second advice to the first's method and
 * abort the transform with "Cannot assign void to class java.lang.Object" -- no
 * hook, no log, and a run that looked identical to "the fix did nothing".
 *
 * <p>Enter-only: all of these overloads return void, so a {@code @Advice.Return}
 * binding would fail the same way.
 */
/**
 * ViewConeTextureFBO#startDrawing / stopDrawing: hold the viewport funnel off for
 * the duration of the cone lightmap draw.
 *
 * <p>This is the live cone path. A reachability walk over the jar (22990 classes,
 * 654428 call sites, descriptors compared exactly) shows:
 *
 * <pre>
 *   IsoWorld.DrawPlayerCone / DrawPlayerCone2  public, zero reachable callers
 *   VisibilityPolygon2$Drawer.render()          zero reachable callers
 *   VisibilityPolygon2.renderMain(int)          reachable, but gated on the
 *                                              developer option renderVisionPolygon
 *   ViewConeTextureFBO.startDrawing()           reachable from TextureDraw#run()
 * </pre>
 *
 * <p>So the cone draws inside the world pass through TextureDraw, and its viewport
 * request goes through the same funnel as the world's. Its vertices are native
 * 1920x1080 screen coordinates from {@code XToScreenExact}, so a 1600x896 viewport
 * pushes only the cone out of frame; the world's own draws agree with it via the
 * projection matrix.
 *
 * <p>Two advice classes because Byte Buddy permits one {@code @Advice} method each
 * and applying a return-bearing exit advice to a void method aborts the transform
 * with "Cannot assign void to class java.lang.Object" -- silently, since the
 * failure happens inside the builder.
 */
/**
 * MultiTextureFBO2#renderCursor: report the scaled screen size to the aim
 * reticle, which otherwise lays itself out for a 1920-wide frame while it is drawn
 * into a 1600-wide viewport.
 *
 * <p>This is the cone. Twelve earlier fixes were aimed at IsoWorld.DrawPlayerCone,
 * VisibilityPolygon2 and the visibilityBlur shader; profiling with exact
 * descriptors showed all three are unreachable on this install, and that the only
 * live caller of IsoReticle#render is renderCursor. See ReticleHold.
 *
 * <p>Two advice classes: Byte Buddy allows one {@code @Advice} method each, and a
 * return-bearing exit advice applied to a void method aborts the transform with
 * "Cannot assign void to class java.lang.Object".
 */

    static boolean patchConeHold(Instrumentation inst) {
        try {
            base()
                    .type(ElementMatchers.named("zombie.viewCone.ViewConeTextureFBO"))
                    .transform((b, td, cl, mod, pd) -> b
                            .visit(advice(ConeViewportHold.class).on(
                                    ElementMatchers.named("startDrawing")
                                            .and(ElementMatchers.takesNoArguments())))
                            .visit(advice(ConeViewportHold.End.class).on(
                                    ElementMatchers.named("stopDrawing")
                                            .and(ElementMatchers.takesNoArguments()))))
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("coneHold", t);
            return false;
        }
    }

    /**
     * VisibilityPolygon2#renderMain: let the cone's screen-rect viewport through the
     * funnel unscaled.
 *
 * <p>This is the cone's real entry point. The hooks aimed at
 * {@code IsoWorld.DrawPlayerCone} / {@code DrawPlayerCone2} had zero call sites
 * in the whole jar, and {@code renderToScreen} belongs to the renderNew path,
 * which this install does not take. See ConeViewportPass for the full chain.
 *
 * <p>One type, one install, so a failure is attributable to this name. Chaining
 * two .type() calls on one builder reports only the last matcher's result, which
 * made a silently skipped install look like a successful one.
 */
/**
     * Cone overlay size: rewrite {@code Core.getOffscreenWidth/Height} inside
     * {@code IsoWorld.DrawPlayerCone2} so the cone quad matches the viewport.
     *
     * <p>This is the path the player sees. {@code DrawPlayerCone2} draws the cone
     * texture as a quad sized by those accessors, which report the camera's zoomed
     * world extent -- neither Core.width (1920) nor the viewport (1600). The
     * earlier vertex-scale substitution on renderOneTriangle / addVertex belonged
     * to {@code renderOld}'s triangle path and did not touch this overlay.
     *
     * <p>The accessor has 65 call sites across culling, input, UI and weather, so
     * the substitution is scoped to this one method by {@code .on(named(...))}.
     */
    static boolean patchConeOverlay(Instrumentation inst) {
        try {
            net.bytebuddy.description.method.MethodDescription qw =
                    declared(fsrupsc.ConeVertexScale.class, "quadWidth");
            net.bytebuddy.description.method.MethodDescription qh =
                    declared(fsrupsc.ConeVertexScale.class, "quadHeight");
            ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription>
                    coreType =
                    ElementMatchers
                            .<net.bytebuddy.description.type.TypeDescription>
                            named("zombie.core.Core");
            ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription>
                    onCore = ElementMatchers.isDeclaredBy(coreType)
                    .and(ElementMatchers.takesArguments(int.class))
                    .and(ElementMatchers.returns(int.class));

            base()
                    .type(ElementMatchers.named("zombie.iso.IsoWorld"))
                    .transform((b, td, cl, mod, pd) -> b
                            .visit(net.bytebuddy.asm.MemberSubstitution.relaxed()
                                    .method(ElementMatchers.<net.bytebuddy.description.method.MethodDescription>
                                                    named("getOffscreenWidth").and(onCore))
                                    .replaceWith(new net.bytebuddy.asm.MemberSubstitution.Substitution
                                            .ForMethodInvocation.OfGivenMethod(qw))
                                    .method(ElementMatchers.<net.bytebuddy.description.method.MethodDescription>
                                                    named("getOffscreenHeight").and(onCore))
                                    .replaceWith(new net.bytebuddy.asm.MemberSubstitution.Substitution
                                            .ForMethodInvocation.OfGivenMethod(qh))
                                    .on(ElementMatchers.named("DrawPlayerCone2"))))
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed coneOverlay: ", t);
            return false;
        }
    }

    /**
     * Cone vertex scale: rewrite XToScreenExact / YToScreenExact <em>inside the
     * cone's polygon builder</em> so the cone's vertices land in the shrunken
     * viewport's coordinate space.
     *
     * <p>42.22 (e013dbb529) bytecode settled the path: useNewVisibility defaults
     * true (Visibility.UseNew) and renderVisionPolygon is a debug-only option
     * whose getValue returns the default (true) in non-debug runs, so the live
     * chain is renderMain -&gt; Drawer.render -&gt; renderNew -&gt; renderPolygons -&gt;
     * triangulatePolygons -&gt; renderOneTriangle. The earlier "renderOld" reading
     * was wrong. The vertex coordinate is the only thing that decides the
     * cone's position; see ConeVertexScale for the full reading.
     *
     * <p>The substitution is scoped by both the target method and the declaring
     * type. Without the type filter the matcher also takes unrelated overloads
     * and the whole class transform fails with "Cannot invoke ... on parameter 0
     * of type ...", which disables the hook silently.
     */
    static boolean patchConeVertexScale(Instrumentation inst) {
        try {
            net.bytebuddy.description.method.MethodDescription sx =
                    declared(fsrupsc.ConeVertexScale.class, "x");
            net.bytebuddy.description.method.MethodDescription sy =
                    declared(fsrupsc.ConeVertexScale.class, "y");
            ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription>
                    playerCamera =
                    ElementMatchers
                            .<net.bytebuddy.description.type.TypeDescription>
                            named("zombie.iso.PlayerCamera");
            ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription>
                    onCamera = ElementMatchers.<net.bytebuddy.description.method.MethodDescription>
                    named("XToScreenExact").or(ElementMatchers.named("YToScreenExact"))
                    .and(ElementMatchers.isDeclaredBy(playerCamera))
                    .and(ElementMatchers.takesArguments(
                            float.class, float.class, float.class, int.class));

            // Two builders: one per method being substituted. A single builder
            // chaining two .type() calls reports only the last matcher's result,
            // so a skipped install looked like a successful one.
            boolean okT = installConeVertex(inst,
                    "zombie.vispoly.VisibilityPolygon2$Drawer",
                    ElementMatchers.named("renderOneTriangle"), onCamera, sx, sy);
            boolean okA = installConeVertex(inst,
                    "zombie.vispoly.VisibilityPolygon2$Drawer$PolygonData",
                    ElementMatchers.named("addVertex"), onCamera, sx, sy);
            if (!okT || !okA) {
                FsrHooks.hookFailed("coneVertex partial triangle=" + okT + " addVertex=" + okA,
                        new IllegalStateException("one of the two cone vertex substitutions did not install"));
            }
            return okT && okA;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed coneVertexScale: ", t);
            return false;
        }
    }

    private static boolean installConeVertex(Instrumentation inst,
                                             String typeName,
                                             ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription>
                                                     onMethods,
                                             ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription>
                                                     onCamera,
                                             net.bytebuddy.description.method.MethodDescription sx,
                                             net.bytebuddy.description.method.MethodDescription sy) {
        try {
            base()
                    .type(ElementMatchers.named(typeName))
                    .transform((b, td, cl, mod, pd) -> b
                            .visit(net.bytebuddy.asm.MemberSubstitution.relaxed()
                                    .method(ElementMatchers.<net.bytebuddy.description.method.MethodDescription>
                                            named("XToScreenExact").and(onCamera))
                                    .replaceWith(new net.bytebuddy.asm.MemberSubstitution.Substitution
                                            .ForMethodInvocation.OfGivenMethod(sx))
                                    .method(ElementMatchers.<net.bytebuddy.description.method.MethodDescription>
                                            named("YToScreenExact").and(onCamera))
                                    .replaceWith(new net.bytebuddy.asm.MemberSubstitution.Substitution
                                            .ForMethodInvocation.OfGivenMethod(sy))
                                    .on(onMethods)))
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("coneVertex on " + typeName, t);
            return false;
        }
    }

    /**
     * Diagnostic: retarget glDepthFunc / glDepthMask inside Drawer.renderNew
     * to ConeDepth (GL_ALWAYS, no depth writes) behind fsr-upsc-conedepth.
     * See ConeDepth for the burial hypothesis this probes.
     */
    static boolean patchConeDepth(Instrumentation inst) {
        try {
            net.bytebuddy.description.method.MethodDescription df =
                    declared(fsrupsc.ConeDepth.class, "func");
            net.bytebuddy.description.method.MethodDescription dm =
                    declared(fsrupsc.ConeDepth.class, "mask");
            ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription>
                    gl11 = ElementMatchers
                    .<net.bytebuddy.description.type.TypeDescription>
                    named("org.lwjgl.opengl.GL11");
            base()
                    .type(ElementMatchers.named("zombie.vispoly.VisibilityPolygon2$Drawer"))
                    .transform((b, td, cl, mod, pd) -> b
                            .visit(net.bytebuddy.asm.MemberSubstitution.relaxed()
                                    .method(ElementMatchers.<net.bytebuddy.description.method.MethodDescription>
                                            named("glDepthFunc").and(ElementMatchers.isDeclaredBy(gl11)))
                                    .replaceWith(new net.bytebuddy.asm.MemberSubstitution.Substitution
                                            .ForMethodInvocation.OfGivenMethod(df))
                                    .method(ElementMatchers.<net.bytebuddy.description.method.MethodDescription>
                                            named("glDepthMask").and(ElementMatchers.isDeclaredBy(gl11)))
                                    .replaceWith(new net.bytebuddy.asm.MemberSubstitution.Substitution
                                            .ForMethodInvocation.OfGivenMethod(dm))
                                    .on(ElementMatchers.named("renderNew"))))
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("coneDepth", t);
            return false;
        }
    }

    static boolean patchConeViewport(Instrumentation inst) {
        try {
            base()
                    .type(ElementMatchers.named("zombie.vispoly.VisibilityPolygon2"))
                    .transform((b, td, cl, mod, pd) -> b
                            .visit(advice(ConeViewportPass.class).on(
                                    ElementMatchers.named("renderMain")
                                            .and(ElementMatchers.takesArguments(int.class)))))
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed coneViewport: ", t);
            return false;
        }
    }

    /**
     * TextureFBO#getTexture: substitute the resolve output during the composite.
     *
     * The composite blit is {@code MultiTextureFBO2#render} loading
     * {@code current.getTexture()} and handing it to
     * {@code Texture#rendershader2}, which draws the receiver and so cannot be
     * redirected through an argument. Returning a different texture here is the
     * only seam that works, and it is argument-free and GL-free.
     */
    static boolean patchComposite(Instrumentation inst) {
        try {
            base()
                    .type(ElementMatchers.named("zombie.core.textures.TextureFBO"))
                    .transform((b, td, cl, mod, pd) -> b
                            .visit(advice(FboTextureGetter.class).on(
                                    ElementMatchers.named("getTexture")
                                            .and(ElementMatchers.takesNoArguments()))))
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed TextureFBO.getTexture: ", t);
            return false;
        }
    }

    /**
     * Group "fboalloc": MultiTextureFBO2#create(int,int).
     *
     * Scales the world offscreen buffer allocation. Runs a handful of times
     * per session (world load, resolution change), never per frame, and
     * issues no GL itself -- it only rewrites two arguments.
     */
    static boolean patchFboAlloc(Instrumentation inst) {
        try {
            base()
                    .type(ElementMatchers.named("zombie.core.textures.MultiTextureFBO2"))
                    .transform((b, td, cl, mod, pd) -> b
                            .visit(advice(MtfboCreateEnter.class).on(
                                    ElementMatchers.named("create")
                                            .and(ElementMatchers.takesArguments(2))
                                            .and(ElementMatchers.not(ElementMatchers.isStatic()))))
                            )
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed MultiTextureFBO2.create: ", t);
            return false;
        }
    }

    /**
     * Object motion tagging: static drawModel with 2 args.
     * Argument-count matching keeps game classes out of linkage.
     * Enter records, exit closes the stencil tag (one transformer).
     */
    static boolean patchDrawModel(Instrumentation inst) {
        try {
            base()
                    .type(ElementMatchers.named("zombie.core.textures.TextureDraw"))
                    .transform((b, td, cl, mod, pd) -> {
                        var v = b
                                .visit(advice(DrawModelEnter.class).on(
                                        ElementMatchers.named("drawModel")
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.takesArguments(2))))
                                .visit(advice(DrawModelExit.class).on(
                                        ElementMatchers.named("drawModel")
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.takesArguments(2))));
                        if (FsrHooks.wantViewport()) {
                            v = v.visit(advice(
                                            TextureDrawViewportEnter.class).on(
                                            ElementMatchers.named("glViewport")
                                                    .and(ElementMatchers.isStatic())
                                                    .and(ElementMatchers.takesArguments(5))));
                        }
                        return v;
                    })
                    .installOn(inst);
            return true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed TextureDraw: ", t);
            return false;
        }
    }

    /**
     * Viewport funnel: game routes world viewports through
     * SpriteRenderer#glViewport (instance, 4 args). TextureDraw#glViewport
     * is kept as a secondary funnel.
     */
    static boolean patchViewport(Instrumentation inst) {
        boolean ok = false;
        try {
            base()
                    .type(ElementMatchers.named("org.lwjgl.opengl.GL11"))
                    .transform((b, td, cl, mod, pd) -> {
                        var v = b.visit(
                                advice(RawViewportEnter.class).on(
                                        ElementMatchers.named("glViewport")
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.takesArguments(4))));
                        return v;
                    })
                    .installOn(inst);
            ok = true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed GL11.glViewport: ", t);
        }
        try {
            base()
                    .type(ElementMatchers.named("zombie.core.sprite.GenericSpriteRenderState"))
                    .transform((b, td, cl, mod, pd) -> {
                        var v = b.visit(
                                advice(ViewportEnter.class).on(
                                        ElementMatchers.named("glViewport")
                                                .and(ElementMatchers.not(ElementMatchers.isStatic()))
                                                .and(ElementMatchers.takesArguments(4))));
                        return v;
                    })
                    .installOn(inst);
            ok = true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed GenericSpriteRenderState.glViewport: ", t);
        }
        try {
            base()
                    .type(ElementMatchers.named("zombie.core.SpriteRenderer"))
                    .transform((b, td, cl, mod, pd) -> {
                        var v = b.visit(
                                advice(ViewportEnter.class).on(
                                        ElementMatchers.named("glViewport")
                                                .and(ElementMatchers.not(ElementMatchers.isStatic()))
                                                .and(ElementMatchers.takesArguments(4))));
                        return v;
                    })
                    .installOn(inst);
            ok = true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed SpriteRenderer.glViewport: ", t);
        }
        try {
            ok = true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed TextureDraw.glViewport: ", t);
        }
        try {
            base()
                    .type(ElementMatchers.named("zombie.core.SpriteRenderer"))
                    .transform((b, td, cl, mod, pd) -> b.visit(
                            advice(StartFrameExit.class).on(
                                    ElementMatchers.named("glDoStartFrame")
                                            .and(ElementMatchers.not(ElementMatchers.isStatic()))
                                            .and(ElementMatchers.takesArguments(4)))))
                    .installOn(inst);
            ok = true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed SpriteRenderer.glDoStartFrame: ", t);
        }
        try {
            base()
                    .type(ElementMatchers.named("zombie.core.SpriteRenderer"))
                    .transform((b, td, cl, mod, pd) -> b.visit(
                            advice(EndFrameExit.class).on(
                                    ElementMatchers.named("glDoEndFrame")
                                            .and(ElementMatchers.not(ElementMatchers.isStatic()))
                                            .and(ElementMatchers.takesArguments(0)))))
                    .installOn(inst);
            ok = true;
        } catch (Throwable t) {
            FsrHooks.hookFailed("failed SpriteRenderer.glDoEndFrame: ", t);
        }
        return ok;
    }

    /** Compare zombie/GitVersion REVISION against the supported whitelist (AgentRev). */
    static boolean revisionMatches() {
        try {
            byte[] bytes;
            try (java.io.InputStream in = FsrTransformer.class.getClassLoader()
                    .getResourceAsStream("zombie/GitVersion.class")) {
                if (in == null) return false;
                bytes = in.readAllBytes();
            }
            String ascii = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("\\b[0-9a-f]{10}\\b").matcher(ascii);
            while (m.find()) {
                if (AgentRev.isSupported(m.group())) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Common AgentBuilder head: logging listener, plus the retransformation
     * strategy in late mode (ZombieBuddy entry) so classes loaded before us
     * are picked up. Redefinition errors are reported per batch without
     * failing the remaining installs.
     *
     * <p>Late mode also disables class format changes: AgentBuilder's
     * defaults (REBASE type strategy + Nexus initialization) inject a
     * synthetic {@code <clinit>} into every transformed class, which the JVM
     * rejects for a redefinition with "attempted to add a method" (a
     * {@code <clinit>} counts as a method). Offline reproduction confirmed
     * the injected clinit is a reflective call into
     * {@code net.bytebuddy.dynamic.Nexus}. Load-time transforms (javaagent
     * premain) are unaffected: a {@code <clinit>} added at definition is
     * legal, so the published javaagent behavior stays byte-identical. */
    private static AgentBuilder base() {
        AgentBuilder b = new AgentBuilder.Default().with(new LogListener());
        if (lateMode) {
            b = b.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(new RedefLog())
                    .disableClassFormatChanges();
        }
        return b;
    }

    static final class LogListener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onTransformation(TypeDescription td, ClassLoader cl,
                JavaModule m, boolean loaded, DynamicType dt) {
            System.out.println("[fsr-upsc] Transformed " + td.getName());
        }

        @Override
        public void onError(String name, ClassLoader cl,
                JavaModule m, boolean loaded, Throwable t) {
            FsrHooks.hookFailed("failed " + name + ": ", t);
        }
    }

    /** Retransform pass: report batch failures with the class names, keep going. */
    static final class RedefLog implements AgentBuilder.RedefinitionStrategy.Listener {
        private static String names(java.util.List<Class<?>> batch) {
            if (batch == null) return "[]";
            StringBuilder sb = new StringBuilder("[");
            for (Class<?> c : batch) {
                if (sb.length() > 1) sb.append(',');
                sb.append(c.getName());
            }
            return sb.append(']').toString();
        }

        @Override
        public void onBatch(
                int index, java.util.List<Class<?>> batch,
                java.util.List<Class<?>> types) {
        }

        @Override
        public Iterable<? extends java.util.List<Class<?>>> onError(
                int index, java.util.List<Class<?>> batch,
                Throwable throwable, java.util.List<Class<?>> types) {
            FsrHooks.hookFailed("retransform batch " + index + " " + names(types) + ": ", throwable);
            return java.util.Collections.emptyList();
        }

        @Override
        public void onComplete(int index, java.util.List<Class<?>> batch,
                java.util.Map<java.util.List<Class<?>>, Throwable> causes) {
            if (causes != null) {
                for (java.util.Map.Entry<java.util.List<Class<?>>, Throwable> e : causes.entrySet()) {
                    FsrHooks.hookFailed("retransform " + names(e.getKey()) + ": ", e.getValue());
                }
            }
        }
    }

    private FsrTransformer() {}
}
