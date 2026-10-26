package fsrupsc;

import fsrupsc.FsrSettings;

/** Minimal kill-switches. Values read from FsrSettings. */
public final class FsrConfig {
    public static final String REVISION = "4a0e9546ec";

    // upscaler: off | bicubic | fsr1 | fsr2 | fsr3
    public static String getUpscaler() { return FsrSettings.get().upscaler; }
    // quality: quality | balanced | performance | ultra | native
    public static String getUpscalerQuality() { return FsrSettings.get().upscalerQuality; }
    public static int getUpscalerScalePct() { return FsrSettings.get().upscalerScalePct; }
    public static int getFsrSharpnessPct() { return FsrSettings.get().fsrSharpnessPct; }
    public static boolean isUpscalerObjectMv() { return FsrSettings.get().upscalerObjectMv; }
    public static int getFgTest() { return FsrSettings.get().fgTest; }
    public static int getFgPace() { return FsrSettings.get().fgPace; }

    // Compat getters/fields for existing code (though they should ideally use getters)
    public static String UPSCALER = getUpscaler();
    public static String UPSCALER_QUALITY = getUpscalerQuality();
    public static int UPSCALER_SCALE_PCT = getUpscalerScalePct();
    public static int FSR_SHARPNESS_PCT = getFsrSharpnessPct();
    public static boolean UPSCALER_OBJECT_MV = isUpscalerObjectMv();
    /** Mirrored FG test style; sync() also pushes it into FgPresent. */
    public static int FG_TEST = getFgTest();
    /** Mirrored FG pacing mode (0 off / -1 safe / -2 fast). */
    public static int FG_PACE = getFgPace();

    public static void sync() {
        UPSCALER = getUpscaler();
        UPSCALER_QUALITY = getUpscalerQuality();
        UPSCALER_SCALE_PCT = getUpscalerScalePct();
        FSR_SHARPNESS_PCT = getFsrSharpnessPct();
        UPSCALER_OBJECT_MV = isUpscalerObjectMv();
        FG_TEST = getFgTest();
        FG_PACE = getFgPace();
    }

    private FsrConfig() {}
}
