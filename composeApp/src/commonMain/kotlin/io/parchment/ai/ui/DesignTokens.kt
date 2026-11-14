package io.parchment.ai.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import io.parchment.ai.platform.PlatformRuntime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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

/**
 * A fixed column width (sidebar, settings list) that grows with the system text size, up to 1.6 times at 200%,
 * so labels keep whole words on one line instead of breaking inside them.
 */
@Composable
fun Modifier.textScaledWidth(base: Dp): Modifier = width(base * textGrowth())

/** How much fixed sizes grow with the system text size: 1 at 100%, at most 1.6 at 200% and above. */
@Composable
fun textGrowth(): Float = LocalDensity.current.fontScale.coerceIn(1f, MAX_TEXT_GROWTH)

private const val MAX_TEXT_GROWTH = 1.6f

/**
 * The shadow under something floating above the page. Low-memory devices skip it (shadows are among the costliest
 * things to draw); the overlay's border still separates it from the page.
 */
fun Modifier.overlayShadow(shape: Shape): Modifier =
    if (PlatformRuntime.isLowMemoryDevice) this else shadow(Elevation.overlay, shape)

/** Scrolls the list to [index], jumping instead of gliding when Reduce motion is on. */
suspend fun LazyListState.scrollToItemWithMotion(index: Int, reduceMotion: Boolean) {
    if (reduceMotion) scrollToItem(index) else animateScrollToItem(index)
}

/**
 * A callback that keeps one identity across recompositions but always runs the latest [block]. Passing these to a
 * composable lets Compose skip it when nothing else changed, even if the code inside reads state that changes
 * every frame (such as the chat being streamed).
 */
@Composable
fun <A> stableCallback(block: (A) -> Unit): (A) -> Unit {
    val latest = rememberUpdatedState(block)
    return remember { { arg: A -> latest.value(arg) } }
}

/** [stableCallback] for callbacks without an argument. */
@Composable
fun stableAction(block: () -> Unit): () -> Unit {
    val latest = rememberUpdatedState(block)
    return remember { { latest.value() } }
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
