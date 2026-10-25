package fsrupsc;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice inlined at the exit of
 * {@code zombie.Lua.LuaManager$Exposer#exposeAll}.
 *
 * <p>At that point the Kahlua environment is fully built (env + exposer live),
 * so this is the first hook point where reflective registration can succeed.
 * The advice body itself stays minimal: one guarded static call. Any failure
 * is swallowed here and logged from the hook instead -- an exception escaping
 * advice would surface inside the game's own Exposer#exposeAll.
 *
 * <p>Keep this class free of game types and field access (LinkageError rule);
 * all game reflection happens in {@link FsrExposeHook}.
 */
public final class FsrExpose {

    private FsrExpose() {}

    @Advice.OnMethodExit
    public static void exit() {
        try {
            fsrupsc.FsrExposeHook.register();
        } catch (Throwable ignored) {}
    }
}
