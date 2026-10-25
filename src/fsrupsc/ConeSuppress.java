package fsrupsc;

import net.bytebuddy.asm.Advice;

/**
 * Cone suppression, selectable per draw entry point.
 *
 * <p>The vision cone is a <em>rendered lighting overlay</em>, not the visibility
 * data: the polygon's coverage comes from
 * {@code zombie.vispoly.VisibilityPolygon2}, which this does not touch. So
 * suppressing a draw removes the overlay without changing what the player can see
 * or what zombies can path to.
 *
 * <p>Two entries are suppressed separately on purpose.
 * {@code DrawPlayerCone} and {@code DrawPlayerCone2} are not one operation with a
 * shared code path: the first pushes the view through
 * {@code SpriteRenderer.pushIsoView} and bakes the lightmap, the second draws the
 * cone texture as a quad sized by {@code Core.getOffscreenWidth/Height}. Which of
 * them produces the displaced cone on screen was not established -- both were
 * assumed at different points and both assumptions turned out wrong. Suppressing
 * them one at a time identifies the responsible one in a single run, with no
 * further guessing.
 *
 * <p>Markers {@code fsrupsc-nocone} (both), {@code fsrupsc-nocone1} (only
 * {@code DrawPlayerCone}) and {@code fsrupsc-nocone2} (only
 * {@code DrawPlayerCone2}). See {@code fsrupsc.RenderScale}.
 */
public final class ConeSuppress {
    private ConeSuppress() {}

    /** DrawPlayerCone: the lightmap bake. */
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean skip() {
        try {
            return fsrupsc.RenderScale.cone1Suppressed();
        } catch (Throwable t) {
            return false;
        }
    }

    /** DrawPlayerCone2: the on-screen quad. */
    public static final class Second {
        private Second() {}

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean skip() {
            try {
                return fsrupsc.RenderScale.cone2Suppressed();
            } catch (Throwable t) {
                return false;
            }
        }
    }
}