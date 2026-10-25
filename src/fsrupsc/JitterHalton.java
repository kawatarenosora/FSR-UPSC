package fsrupsc;

/**
 * Halton(2,3) jitter sequence, a 1:1 port of fsr-reloaded's
 * JitterManager (verified identical: phaseCount = ceil(8 * (d/r)^2),
 * values in [-0.5, 0.5) render pixels).
 *
 * <p>Phase count scales with the upscale factor squared, so bigger
 * upscales enumerate more distinct Halton values before repeating.
 */
public final class JitterHalton {

    private int index;
    private float prevX;
    private float prevY;

    private int cachedPhaseCount = -1;
    private int cachedRenderWidth;
    private int cachedDisplayWidth;

    /** result[0]=x result[1]=y, render-pixel units in [-0.5, 0.5). */
    public void next(int renderWidth, int displayWidth, float[] result) {
        int phaseCount;
        if (renderWidth == cachedRenderWidth && displayWidth == cachedDisplayWidth) {
            phaseCount = cachedPhaseCount;
        } else {
            phaseCount = (int) Math.ceil(8.0 * Math.pow((double) displayWidth / renderWidth, 2.0));
            if (phaseCount < 1) phaseCount = 1;
            cachedPhaseCount = phaseCount;
            cachedRenderWidth = renderWidth;
            cachedDisplayWidth = displayWidth;
        }
        float x = halton(index + 1, 2);
        float y = halton(index + 1, 3);
        index = (index + 1) % phaseCount;
        result[0] = x - 0.5f;
        result[1] = y - 0.5f;
        prevX = result[0];
        prevY = result[1];
    }

    /** Previous frame's jitter (uniforms uPrevJitter / jitter cancellation). */
    public float prevX() { return prevX; }
    public float prevY() { return prevY; }

    /** Reset the sequence (on scale / resolution change). */
    public void reset() {
        index = 0;
        prevX = 0f;
        prevY = 0f;
        cachedPhaseCount = -1;
    }

    private static float halton(int i, int base) {
        float result = 0f;
        float f = 1f / base;
        while (i > 0) {
            result += f * (i % base);
            i /= base;
            f /= base;
        }
        return result;
    }
}
