package fsrupsc;

/**
 * Registers {@link FsrControl} into the Kahlua Lua environment.
 *
 * <p>Mechanism (verified against the in-house BU agent, same revision):
 * on {@code LuaManager$Exposer#exposeAll} exit,
 * <ul>
 *   <li>{@code LuaManager.exposer} and {@code LuaManager.env} exist,</li>
 *   <li>{@code exposer.setExposed(cls)} makes the class a known Kahlua JavaClass,</li>
 *   <li>{@code exposer.exposeLikeJavaRecursively(cls, env)} builds the
 *       package-shaped table {@code _G.fsrupsc.FsrControl},</li>
 *   <li>a top-level alias {@code _G.FsrControl} is rawset for convenience,
 *       mirroring vanilla's {@code BUControl} ergonomics.</li>
 * </ul>
 *
 * <p>Registration is per-env, not per-process: PZ rebuilds the Lua
 * environment when a world starts (a second Exposer#exposeAll with a fresh
 * LuaManager.env). A once-only guard left that new env without FsrControl
 * and the debug console resolved it to null, so re-register whenever the
 * env instance changed and skip only when it is the same instance.
 *
 * <p>Fully reflective, never fatal: any failure is logged and the mod keeps
 * working through the ModOptions.ini restart path.
 */
public final class FsrExposeHook {

    /** Identity of the env last registered (KahluaTable instance ==). */
    private static volatile Object registeredEnv;

    public static void register() {
        try {
            Class<?> lm = Class.forName("zombie.Lua.LuaManager");
            Object exposer = lm.getField("exposer").get(null);
            Object env = lm.getField("env").get(null);
            if (exposer == null || env == null) {
                System.err.println(
                    "[fsr-upsc] expose failed: LuaManager.exposer/env is null");
                return;
            }
            if (env == registeredEnv) {
                return; // same env instance: already registered
            }
            Object cls = fsrupsc.FsrControl.class;
            Class<?> exposerClass = exposer.getClass();
            exposerClass.getMethod("setExposed", Class.class).invoke(exposer, cls);
            Class<?> kahluaTable = Class.forName("se.krka.kahlua.vm.KahluaTable");
            exposerClass
                .getMethod("exposeLikeJavaRecursively", java.lang.reflect.Type.class, kahluaTable)
                .invoke(exposer, cls, env);
            aliasTopLevel(env);
            registeredEnv = env;
            String msg = "[fsr-upsc] exposed fsrupsc.FsrControl (+FsrControl alias) env#"
                + Integer.toHexString(System.identityHashCode(env));
            System.out.println(msg);
            fsrupsc.FsrLog.log(msg);
        } catch (Throwable t) {
            System.err.println("[fsr-upsc] expose failed: " + t);
        }
    }

    /** _G.fsrupsc.FsrControl -> _G.FsrControl (KahluaTable.rawget/rawset). */
    private static void aliasTopLevel(Object env) {
        try {
            Class<?> c = env.getClass();
            java.lang.reflect.Method rawget =
                c.getMethod("rawget", Object.class);
            java.lang.reflect.Method rawset =
                c.getMethod("rawset", Object.class, Object.class);
            Object ns = rawget.invoke(env, "fsrupsc");
            if (ns == null) {
                System.err.println("[fsr-upsc] alias skipped: no _G.fsrupsc table");
                return;
            }
            Object control = rawget.invoke(ns, "FsrControl");
            if (control == null) {
                System.err.println("[fsr-upsc] alias skipped: no _G.fsrupsc.FsrControl");
                return;
            }
            rawset.invoke(env, "FsrControl", control);
        } catch (Throwable t) {
            System.err.println("[fsr-upsc] alias failed: " + t);
        }
    }

    private FsrExposeHook() {}
}
