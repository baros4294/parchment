package io.parchment.ai.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// The design system in code: docs/DESIGN.md explains when to use each value. New UI takes these instead of
// literal numbers; older screens move over as each surface is reworked.

/** Spacing for padding, gaps and offsets: a 2 dp grid up to 12, then 16, 24 and 48. */
object Space {
    val s2 = 2.dp
    val s4 = 4.dp
    val s6 = 6.dp
    val s8 = 8.dp
    val s10 = 10.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s24 = 24.dp
    /** Also the smallest touch target. */
    val s48 = 48.dp
}

/** Corner radii. Bubbles use [bubble]; chips and badges that should read as pills use [pill]. */
object Radius {
    val r4 = 4.dp
    val r6 = 6.dp
    val r8 = 8.dp
    val r10 = 10.dp
    val r12 = 12.dp
    val bubble = 16.dp
    val pill = 999.dp
}

/** Line widths. */
object LineWidth {
    /** Dividers and the borders of resting surfaces. */
    val hairline = 0.75.dp
    /** Borders that must stay visible, such as inputs. */
    val emphasis = 1.dp
    /** The border of the selected item in a list of choices. */
    val selected = 1.5.dp
    /** The keyboard focus ring. */
    val focus = 2.dp
}

/** Icon sizes. Icons are drawn on a 24-unit grid, so even sizes keep their strokes on whole pixels. */
object IconSize {
    val xs = 10.dp
    val sm = 12.dp
    val md = 14.dp
    val lg = 16.dp
    val xl = 20.dp
}

/** Shadow depth. Only things that float above the page (notifications, menus) cast one; everything else is flat. */
object Elevation {
    val overlay = 8.dp
}

/**
 * Animation timings. Everything that moves takes its spec from here, so Reduce motion in Settings turns all of it
 * into an instant change.
 */
object Motion {
    /** Leaving: fades out, small feedback. */
    const val QUICK = 110
    /** Arriving: fades in, menus, hints. */
    const val STANDARD = 150
    /** Things that travel: slides, color changes. */
    const val MEDIUM = 180
    /** Overlays that leave slowly enough to be read. */
    const val SLOW = 250
    /** One half-cycle of a looping activity indicator. */
    const val LOOP = 750

    /** Stiffness of a brisk spring for things that pop in. */
    const val POP_STIFFNESS = 580f
}

/** A timed animation that is instant when the person has turned on Reduce motion. */
@Composable
fun <T> motionTween(durationMillis: Int = Motion.STANDARD): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else tween(durationMillis)

/** A spring for size and position changes; instant when Reduce motion is on. */
@Composable
fun <T> motionSpring(
    stiffness: Float = Spring.StiffnessMediumLow,
    dampingRatio: Float = Spring.DampingRatioNoBouncy
): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spring(dampingRatio = dampingRatio, stiffness = stiffness)
