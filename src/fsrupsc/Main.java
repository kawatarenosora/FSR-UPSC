package fsrupsc;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * ZombieBuddy edition entry shim (this branch only).
 *
 * <p>ZombieBuddy loads this mod's jar and runs {@code fsrupsc.Main#main} in
 * its own transformed-jar loader. That loader serves neither the jar's
 * resources nor its class bytes, and fsrupsc state initialized inside it
 * would be a second, invisible copy next to the one the inlined advice
 * bodies resolve through (the system classloader). So this class stays a
 * dumb shim: locate the jar file, append it to the system classloader
 * search, then re-enter the real boot ({@link ZbEntry}) through the SYSTEM
 * classloader -- every fsrupsc class involved in state and advice exists in
 * exactly one copy there. The manifest carries no Premain-Class; this build
 * is ZombieBuddy-only.
 */
public final class Main {

    public static void main(String[] args) {
        try {
            File jar = AgentJar.find(null);
            if (jar == null) {
                System.out.println("[fsr-upsc] agent jar not located, running stock");
                return;
            }
            Instrumentation inst = zbInstrumentation();
            if (inst == null) {
                System.out.println("[fsr-upsc] ZombieBuddy instrumentation unavailable, running stock");
                return;
            }
            try {
                inst.appendToSystemClassLoaderSearch(new JarFile(jar));
            } catch (Throwable t) {
                System.out.println("[fsr-upsc] system search append failed: " + t);
            }
            Class<?> entry = Class.forName("fsrupsc.ZbEntry", true,
                    ClassLoader.getSystemClassLoader());
            entry.getMethod("run", Instrumentation.class, File.class)
                    .invoke(null, inst, jar);
        } catch (Throwable t) {
            System.out.println("[fsr-upsc] zb entry failed, running stock: " + t);
        }
    }

    /**
     * ZombieBuddy holds the instrumentation it registered in its own premain
     * in {@code Loader.g_instrumentation} (package-private static, identical
     * field in 2.x and 3.x). Reflection keeps us compile-independent.
     */
    private static Instrumentation zbInstrumentation() {
        try {
            Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
            java.lang.reflect.Field f = loader.getDeclaredField("g_instrumentation");
            f.setAccessible(true);
            return (Instrumentation) f.get(null);
        } catch (Throwable t) {
            System.out.println("[fsr-upsc] zb instrumentation lookup failed: " + t);
            return null;
        }
    }

    private Main() {}
}
