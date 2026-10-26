package fsrupsc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Settings holder for FSR-UPSC.
 */
public final class FsrSettings {
    private static final Path PATH = Paths.get("FsrUpscSettings.json");

    public String upscaler = "fsr1";
    public String upscalerQuality = "quality";
    public int upscalerScalePct = 0;
    public int fsrSharpnessPct = 80;
    /** FG test insert style (FgPresent semantics): 0 off / 1 magenta /
     *  2 duplicate / 3 interpolation. */
    public int fgTest = 0;
    /** FG VSync-off pacing ("FgPresent#setPace"): 0 off (VSync assumed),
     *  -1 auto-safe (1 refresh exposure), -2 auto-fast (1/2 refresh). */
    public int fgPace = 0;
    public boolean upscalerObjectMv = true;

    private static FsrSettings INSTANCE = new FsrSettings();

    public static FsrSettings get() { return INSTANCE; }

    public static void load() {
        // Priority (low -> high): defaults -> FsrUpscSettings.json
        //   -> Zomboid/Lua/ModOptions.ini (in-game Mod Options) -> -D system properties
        INSTANCE.upscaler = "fsr1";
        INSTANCE.upscalerQuality = "quality";
        INSTANCE.upscalerScalePct = 0;
        INSTANCE.fsrSharpnessPct = 80;
        INSTANCE.upscalerObjectMv = true;
        INSTANCE.fgTest = 0;
        INSTANCE.fgPace = 0;

        // Then override with file if exists
        if (Files.exists(PATH)) {
            try {
                String txt = Files.readString(PATH);
                // Very simple parsing to avoid adding heavy dependencies
                String v = extractJsonString(txt, "\"upscaler\"");
                if (v != null) INSTANCE.upscaler = v;

                v = extractJsonString(txt, "\"upscalerQuality\"");
                if (v != null) INSTANCE.upscalerQuality = v;

                String spct = extractNumber(txt, "\"upscalerScalePct\"");
                if (spct != null) INSTANCE.upscalerScalePct = Integer.parseInt(spct);

                String fsharp = extractNumber(txt, "\"fsrSharpnessPct\"");
                if (fsharp != null) INSTANCE.fsrSharpnessPct = Integer.parseInt(fsharp);

                String fg = extractNumber(txt, "\"fgTest\"");
                if (fg != null) INSTANCE.fgTest = Math.max(0, Math.min(3, Integer.parseInt(fg)));

                String fp = extractNumber(txt, "\"fgPace\"");
                if (fp != null) {
                    int p = Integer.parseInt(fp);
                    // 0 off, -1 safe, -2 fast, positive = manual exposure
                    // in tenths of a millisecond (console-only tuning).
                    INSTANCE.fgPace = (p == -1 || p == -2 || p > 0) ? p : 0;
                }

                String omv = extractJsonBool(txt, "\"upscalerObjectMv\"");
                if (omv != null) INSTANCE.upscalerObjectMv = Boolean.parseBoolean(omv);

            } catch (Exception e) {
                System.err.println("[fsr-upsc] settings load failed: " + e.getMessage());
            }
        }

        // In-game Mod Options (B42 native PZAPI.ModOptions -> Zomboid/Lua/ModOptions.ini).
        // Format: type|FSR_UPSC|OptionId|value ; combobox value = 1-based index.
        loadModOptionsIni();

        // Explicit -D flags win (debugging / launcher overrides).
        if (System.getProperty("fsrupsc.upscaler") != null)
            INSTANCE.upscaler = System.getProperty("fsrupsc.upscaler");
        if (System.getProperty("fsrupsc.quality") != null)
            INSTANCE.upscalerQuality = System.getProperty("fsrupsc.quality");
        if (System.getProperty("fsrupsc.scalePct") != null) {
            try { INSTANCE.upscalerScalePct = Integer.parseInt(System.getProperty("fsrupsc.scalePct")); }
            catch (NumberFormatException ignored) {}
        }
        if (System.getProperty("fsrupsc.fsrSharp") != null) {
            try { INSTANCE.fsrSharpnessPct = Integer.parseInt(System.getProperty("fsrupsc.fsrSharp")); }
            catch (NumberFormatException ignored) {}
        }
        if (System.getProperty("fsrupsc.fgTest") != null) {
            try {
                INSTANCE.fgTest = Math.max(0,
                    Math.min(3, Integer.parseInt(System.getProperty("fsrupsc.fgTest"))));
            } catch (NumberFormatException ignored) {}
        }
        if (System.getProperty("fsrupsc.fgPace") != null) {
            try {
                int p = Integer.parseInt(System.getProperty("fsrupsc.fgPace"));
                INSTANCE.fgPace = (p == -1 || p == -2 || p > 0) ? p : 0;
            } catch (NumberFormatException ignored) {}
        }
        if (System.getProperty("fsrupsc.objectMv") != null)
            INSTANCE.upscalerObjectMv = Boolean.parseBoolean(System.getProperty("fsrupsc.objectMv"));
    }

    public void save() {
        try {
            StringBuilder sb = new StringBuilder("{\n");
            sb.append("  \"upscaler\": \"").append(upscaler).append("\",\n");
            sb.append("  \"upscalerQuality\": \"").append(upscalerQuality).append("\",\n");
            sb.append("  \"upscalerScalePct\": ").append(upscalerScalePct).append(",\n");
            sb.append("  \"fsrSharpnessPct\": ").append(fsrSharpnessPct).append(",\n");
            sb.append("  \"fgTest\": ").append(fgTest).append(",\n");
            sb.append("  \"fgPace\": ").append(fgPace).append(",\n");
            sb.append("  \"upscalerObjectMv\": ").append(upscalerObjectMv).append("\n");
            sb.append("}\n");
            Files.writeString(PATH, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[fsr-upsc] settings save failed: " + e.getMessage());
        }
    }

    private static String extractJsonString(String txt, String key) {
        int i = txt.indexOf(key);
        if (i < 0) return null;
        int c = txt.indexOf(':', i);
        if (c < 0) return null;
        int s = txt.indexOf('"', c);
        if (s < 0) return null;
        int e = txt.indexOf('"', s + 1);
        if (e < 0) return null;
        return txt.substring(s + 1, e);
    }

    private static String extractJsonBool(String txt, String key) {
        int i = txt.indexOf(key);
        if (i < 0) return null;
        int c = txt.indexOf(':', i);
        if (c < 0) return null;
        int s = c + 1;
        while (s < txt.length() && Character.isWhitespace(txt.charAt(s))) s++;
        if (txt.startsWith("true", s)) return "true";
        if (txt.startsWith("false", s)) return "false";
        return extractJsonString(txt, key);
    }

    private static String extractNumber(String txt, String key) {
        int i = txt.indexOf(key);
        if (i < 0) return null;
        int c = txt.indexOf(':', i);
        if (c < 0) return null;
        int s = c + 1;
        while (s < txt.length() && Character.isWhitespace(txt.charAt(s))) s++;
        int e = s;
        while (e < txt.length() && (Character.isDigit(txt.charAt(e)) || txt.charAt(e) == '-')) e++;
        if (s == e) return null;
        return txt.substring(s, e);
    }

    private static final String[] UPSCALERS = { "off", "bicubic", "fsr1", "fsr2", "fsr3" };
    private static final String[] QUALITIES = { "ultra_quality", "quality", "balanced", "performance", "native" };
    private static final String[] SCALES = { "0", "50", "60", "69", "78" };

    /** Read B42 native ModOptions file written by FSR_UPSC_Options.lua. */
    private static void loadModOptionsIni() {
        Path ini = modOptionsIniPath();
        if (ini == null || !Files.exists(ini)) return;
        try {
            for (String raw : Files.readAllLines(ini)) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                // type|FSR_UPSC|OptionId|value
                String[] t = line.split("\\|", -1);
                if (t.length < 4) continue;
                if (!"FSR_UPSC".equals(t[1])) continue;
                String id = t[2].trim();
                String val = t[3].trim();
                try {
                    switch (id) {
                        case "Upscaler":
                            String u = pick(UPSCALERS, parseIdx(val));
                            if (u != null) INSTANCE.upscaler = u;
                            break;
                        case "UpscalerQuality":
                            String qv = pick(QUALITIES, parseIdx(val));
                            if (qv != null) INSTANCE.upscalerQuality = qv;
                            break;
                        case "UpscalerScalePct":
                            String sv = pick(SCALES, parseIdx(val));
                            if (sv != null) INSTANCE.upscalerScalePct = Integer.parseInt(sv);
                            break;
                        case "FsrSharpnessPct":
                            INSTANCE.fsrSharpnessPct = Math.max(0, Math.min(100, Integer.parseInt(val)));
                            break;
                        case "FgTest":
                            // Combo stores a 1-based index (1=off .. 4=interp);
                            // stored FG style is 0-based.
                            INSTANCE.fgTest = Math.max(0, Math.min(3, Integer.parseInt(val) - 1));
                            break;
                        case "FgPace":
                            // Combo stores a 1-based index (1=off, 2=safe=-1,
                            // 3=fast=-2); manual N stays console-only.
                            int pi = Integer.parseInt(val);
                            INSTANCE.fgPace = pi == 2 ? -1 : pi == 3 ? -2 : 0;
                            break;
                        case "UpscalerObjectMv":
                            if ("true".equalsIgnoreCase(val) || "false".equalsIgnoreCase(val))
                                INSTANCE.upscalerObjectMv = Boolean.parseBoolean(val);
                            break;
                        default:
                            break;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("[fsr-upsc] ModOptions.ini load failed: " + e.getMessage());
        }
    }

    private static int parseIdx(String v) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return -1; }
    }

    private static String pick(String[] arr, int idx1) {
        if (idx1 >= 1 && idx1 <= arr.length) return arr[idx1 - 1];
        return null;
    }

    private static Path modOptionsIniPath() {
        // Vanilla PZAPI.ModOptions:save/load uses Zomboid/Lua/ModOptions.ini
        // under the user home on all OSes.
        try {
            String home = System.getProperty("user.home");
            if (home != null && !home.isEmpty()) {
                Path p = Paths.get(home, "Zomboid", "Lua", "ModOptions.ini");
                if (Files.exists(p)) return p;
            }
        } catch (Exception ignored) {}
        try {
            Path p2 = Paths.get("Zomboid", "Lua", "ModOptions.ini");
            if (Files.exists(p2)) return p2;
        } catch (Exception ignored) {}
        return null;
    }
}
