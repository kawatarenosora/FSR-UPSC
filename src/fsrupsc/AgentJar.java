package fsrupsc;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;
import net.bytebuddy.dynamic.ClassFileLocator;

/**
 * Locates this build's own jar file and its class bytes.
 *
 * <p>ZombieBuddy defines mod classes from its transformed-jar loader, which
 * serves neither the jar's resources nor any class-file lookup -- the first
 * in-game run failed every hook with "Could not locate class file for
 * fsrupsc.*" because ByteBuddy's default Advice locator reads the advice
 * class bytes as a resource. Advice needs the raw bytes to inline, so we read
 * them from the jar file on disk. Appending the jar to the system classloader
 * search additionally gives the inlined advice bodies a loader that resolves
 * their fsrupsc helper calls (game classes load through the system loader,
 * and ZombieBuddy's private loader is not on that path).
 */
final class AgentJar {

    private AgentJar() {}

    /** This build's jar file, or null when it cannot be located. */
    static File find(Instrumentation inst) {
        // 1) code source of any class defined from this jar
        for (Class<?> c : new Class<?>[]{AgentJar.class, FsrTransformer.class, Main.class}) {
            File f = codeSourceJar(c);
            if (f != null) return f;
        }
        // 2) any loaded fsrupsc class (ZombieBuddy's scan may have defined
        // some already, under a different loader with a usable code source)
        if (inst != null) {
            try {
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    if (c.getName().startsWith("fsrupsc.")) {
                        File f = codeSourceJar(c);
                        if (f != null) return f;
                    }
                }
            } catch (Throwable ignored) {
                // fall through
            }
        }
        // 3) system classloader resource (jar appended to the system search)
        try {
            java.net.URL u = ClassLoader.getSystemResource("fsrupsc/FsrTransformer.class");
            if (u != null) {
                String p = u.toString();
                int bang = p.indexOf("!/");
                if (p.startsWith("jar:") && bang > 0) p = p.substring(4, bang);
                if (p.startsWith("file:")) {
                    File f = new File(java.net.URI.create(p).getPath());
                    if (f.isFile()) return f;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        // 4) known local WIP path (local testing convenience)
        File f = new File(System.getProperty("user.home"),
                "Zomboid/mods/FSR-UPSC-ZB/42/media/java/fsr-upsc-agent.jar");
        if (f.isFile()) return f;
        return null;
    }

    private static File codeSourceJar(Class<?> c) {
        try {
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File f = new File(cs.getLocation().toURI());
                if (f.isFile()) return f;
            }
        } catch (Throwable ignored) {
            // try the next source
        }
        return null;
    }

    /**
     * Makes fsrupsc classes resolvable through the system classloader (once;
     * {@link Main} appends first, this only fills in if that failed) and
     * returns a class file locator over the jar. Either part is best-effort:
     * without the append the hooks die at the first advice call, without the
     * locator at weaving time; both failures are logged by the caller.
     */
    static ClassFileLocator prepare(Instrumentation inst, File jar) {
        if (inst != null
                && ClassLoader.getSystemResource("fsrupsc/FsrTransformer.class") == null) {
            try {
                inst.appendToSystemClassLoaderSearch(new JarFile(jar));
                System.out.println("[fsr-upsc] agent jar appended to system classloader search");
            } catch (Throwable t) {
                System.out.println("[fsr-upsc] system search append failed: " + t);
            }
        }
        try {
            return ClassFileLocator.ForJarFile.of(jar);
        } catch (Throwable t) {
            System.out.println("[fsr-upsc] class file locator failed: " + t);
            return null;
        }
    }
}
