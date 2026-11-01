package io.parchment.ai.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class SepiaButtonStyle {
    PRIMARY,
    SECONDARY,
    OUTLINE,
    GHOST,
    DANGER
}

/**
 * Tactile KISS press-scale modifier using Compose Foundation spring physics.
 */
@Composable
fun Modifier.kissClickable(
    enabled: Boolean = true,
    pressScale: Float = 0.96f,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

@Composable
fun SepiaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SepiaButtonStyle = SepiaButtonStyle.OUTLINE,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val p = LocalParchmentPalette.current
    val shape = RoundedCornerShape(if (compact) 7.dp else 8.dp)

    val targetBg = when (style) {
        SepiaButtonStyle.PRIMARY -> if (enabled) p.accent else p.border.copy(alpha = 0.55f)
        SepiaButtonStyle.SECONDARY -> if (enabled) p.accentSecondary else p.border.copy(alpha = 0.55f)
        SepiaButtonStyle.OUTLINE -> if (enabled) p.cardBg else p.elevatedBg.copy(alpha = 0.4f)
        SepiaButtonStyle.GHOST -> Color.Transparent
        SepiaButtonStyle.DANGER -> if (enabled) p.danger else p.border.copy(alpha = 0.55f)
    }

    val targetText = when (style) {
        SepiaButtonStyle.PRIMARY, SepiaButtonStyle.SECONDARY, SepiaButtonStyle.DANGER ->
            if (enabled) Color(0xFFFAF6EB) else p.mutedInk
        SepiaButtonStyle.OUTLINE -> if (enabled) p.primaryInk else p.mutedInk.copy(alpha = 0.6f)
        SepiaButtonStyle.GHOST -> if (enabled) p.narrationInk else p.mutedInk.copy(alpha = 0.5f)
    }

    val targetBorder = when (style) {
        SepiaButtonStyle.PRIMARY -> if (enabled) p.accent else Color.Transparent
        SepiaButtonStyle.SECONDARY -> if (enabled) p.accentSecondary else Color.Transparent
        SepiaButtonStyle.OUTLINE -> p.border.copy(alpha = if (enabled) 0.8f else 0.4f)
        SepiaButtonStyle.GHOST -> Color.Transparent
        SepiaButtonStyle.DANGER -> if (enabled) p.danger else Color.Transparent
    }

    val bgColor by animateColorAsState(targetBg, tween(140))
    val textColor by animateColorAsState(targetText, tween(140))
    val borderColor by animateColorAsState(targetBorder, tween(140))

    Box(
        modifier = modifier
            .kissClickable(enabled = enabled, onClick = onClick)
            .clip(shape)
            .background(bgColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(0.85.dp, borderColor, shape)
                } else Modifier
            )
            .padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 6.dp else 8.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = p.uiBodyStyle.copy(
                color = textColor,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = if (style == SepiaButtonStyle.PRIMARY || style == SepiaButtonStyle.SECONDARY) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Medium
                }
            )
        )
    }
}

@Composable
fun SepiaChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalParchmentPalette.current
    val shape = RoundedCornerShape(7.dp)
    val bg by animateColorAsState(
        targetValue = if (selected) p.accent.copy(alpha = 0.14f) else p.cardBg.copy(alpha = 0.75f),
        animationSpec = tween(150)
    )
    val borderCol by animateColorAsState(
        targetValue = if (selected) p.accent else p.border.copy(alpha = 0.65f),
        animationSpec = tween(150)
    )
    val txtCol by animateColorAsState(
        targetValue = if (selected) p.accent else p.narrationInk,
        animationSpec = tween(150)
    )

    Box(
        modifier = modifier
            .kissClickable(onClick = onClick)
            .clip(shape)
            .background(bg)
            .border(0.85.dp, borderCol, shape)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = p.uiLabelStyle.copy(
                color = txtCol,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        )
    }
}

/**
 * Clean KISS Segmented Control (inspired by iA Writer / Linear / Raycast)
 * for switching between primary views or studio tabs without pill clutter.
 */
@Composable
fun <T> SepiaSegmentedBar(
    items: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalParchmentPalette.current
    val outerShape = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .clip(outerShape)
            .background(p.sheetBg.copy(alpha = 0.85f))
            .border(0.85.dp, p.border.copy(alpha = 0.75f), outerShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (value, label) ->
            val isSelected = value == selected
            val tabBg by animateColorAsState(
                targetValue = if (isSelected) p.cardBg else Color.Transparent,
                animationSpec = tween(160)
            )
            val tabBorder by animateColorAsState(
                targetValue = if (isSelected) p.border.copy(alpha = 0.85f) else Color.Transparent,
                animationSpec = tween(160)
            )
            val tabText by animateColorAsState(
                targetValue = if (isSelected) p.accent else p.narrationInk,
                animationSpec = tween(160)
            )
            val tabShape = RoundedCornerShape(6.dp)
            Box(
                modifier = Modifier
                    .kissClickable(pressScale = 0.97f) { onSelect(value) }
                    .clip(tabShape)
                    .background(tabBg)
                    .then(
                        if (isSelected) Modifier.border(0.75.dp, tabBorder, tabShape) else Modifier
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = label,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = p.uiLabelStyle.copy(
                        color = tabText,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                    )
                )
            }
        }
    }
}

@Composable
fun SepiaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String = "",
    singleLine: Boolean = true,
    monospace: Boolean = false,
    borderless: Boolean = false,
    minHeight: Dp = 36.dp,
    maxHeight: Dp = 220.dp,
    maxLines: Int = if (singleLine) 1 else 8
) {
    val p = LocalParchmentPalette.current
    val shape = RoundedCornerShape(8.dp)
    val textStyle = if (monospace) p.monoStyle else p.uiBodyStyle
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderColor by animateColorAsState(
        targetValue = when {
            borderless -> Color.Transparent
            isFocused -> p.accent.copy(alpha = 0.85f)
            else -> p.border.copy(alpha = 0.75f)
        },
        animationSpec = tween(150)
    )

    Column(modifier = modifier) {
        if (label.isNotBlank()) {
            BasicText(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = p.uiLabelStyle.copy(color = p.narrationInk, fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(4.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            maxLines = maxLines,
            textStyle = textStyle,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight, max = if (singleLine) minHeight.coerceAtLeast(38.dp) else maxHeight)
                        .clip(shape)
                        .background(if (borderless) Color.Transparent else p.cardBg)
                        .then(
                            if (!borderless) {
                                Modifier.border(if (isFocused) 1.2.dp else 0.85.dp, borderColor, shape)
                            } else Modifier
                        )
                        .padding(
                            horizontal = if (borderless) 4.dp else 11.dp,
                            vertical = if (borderless) 6.dp else 8.dp
                        ),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        BasicText(
                            text = placeholder,
                            maxLines = if (singleLine) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                            style = textStyle.copy(color = p.mutedInk.copy(alpha = 0.6f))
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
fun SepiaSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    headerAction: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalParchmentPalette.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.cardBg)
            .border(0.85.dp, p.border.copy(alpha = 0.78f), shape)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            .padding(16.dp)
    ) {
        if (title.isNotBlank() || headerAction != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (title.isNotBlank()) {
                        BasicText(
                            text = title,
                            style = p.uiHeaderStyle.copy(fontSize = 15.sp)
                        )
                    }
                    if (subtitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            text = subtitle,
                            style = p.uiLabelStyle
                        )
                    }
                }
                if (headerAction != null) {
                    headerAction()
                }
            }
            Spacer(Modifier.height(10.dp))
            SepiaDivider()
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

@Composable
fun SepiaDivider(modifier: Modifier = Modifier) {
    val p = LocalParchmentPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.8.dp)
            .background(p.border.copy(alpha = 0.6f))
    )
}

/**
 * Breathing pulse dot for active streaming & long-horizon deep thinking.
 */
@Composable
fun KissStreamingPulseDot(
    color: Color = LocalParchmentPalette.current.accent,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition()
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val pulseScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
                alpha = pulseAlpha
            }
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * Compact Circular Context Ring + Token/Cost Pill in the Composer Toolbar.
 * Uses smooth spring animation when token counts change.
 */
@Composable
fun SepiaContextGaugeRing(
    usedTokens: Int,
    contextLimitTokens: Int,
    sessionCostUsd: Double = 0.0,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val p = LocalParchmentPalette.current
    val safeLimit = contextLimitTokens.coerceAtLeast(1000)
    val targetFraction = (usedTokens.toFloat() / safeLimit.toFloat()).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(stiffness = Spring.StiffnessLow)
    )
    val percent = (targetFraction * 100f).toInt()

    val ringColor by animateColorAsState(
        targetValue = when {
            targetFraction >= 0.85f -> p.danger
            targetFraction >= 0.60f -> p.accentSecondary
            else -> p.accent
        },
        animationSpec = tween(250)
    )

    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier = modifier
            .then(if (onClick != null) Modifier.kissClickable(onClick = onClick) else Modifier)
            .clip(shape)
            .background(p.elevatedBg.copy(alpha = 0.6f))
            .border(0.85.dp, p.border.copy(alpha = 0.65f), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(16.dp)) {
                val strokeWidth = 2.2.dp.toPx()
                drawArc(
                    color = p.border.copy(alpha = 0.6f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = (animatedFraction * 360f).coerceAtLeast(6f),
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        val costTag = if (sessionCostUsd > 0.0) " • ${formatUsdCost(sessionCostUsd)}" else ""
        BasicText(
            text = "${formatCompactTokens(usedTokens)}/${formatCompactTokens(safeLimit)} ($percent%)$costTag",
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = p.uiLabelStyle.copy(
                fontFamily = p.codeFontFamily,
                fontSize = 10.5.sp,
                color = p.narrationInk,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

/**
 * Zero-allocation O(N) word counter so streaming token deltas never allocate `Regex` or `List<String>`.
 */
fun countWordsFast(text: CharSequence): Int {
    var count = 0
    var inWord = false
    for (i in 0 until text.length) {
        if (text[i].isWhitespace()) {
            inWord = false
        } else if (!inWord) {
            inWord = true
            count++
        }
    }
    return count
}

fun formatCompactTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> {
        val m = (tokens / 100_000) / 10.0
        if (m == m.toLong().toDouble()) "${m.toLong()}M" else "${m}M"
    }
    tokens >= 10_000 -> "${tokens / 1000}k"
    tokens >= 1_000 -> {
        val k = (tokens / 100) / 10.0
        "${k}k"
    }
    else -> tokens.toString()
}

fun formatUsdCost(costUsd: Double): String {
    if (costUsd <= 0.0) return "$0.00"
    if (costUsd < 0.0001) return "<$0.0001"
    val scaled = (costUsd * 10000.0 + 0.5).toLong()
    val whole = scaled / 10000
    val frac = (scaled % 10000).toString().padStart(4, '0').trimEnd('0').let {
        if (it.length < 2) it.padEnd(2, '0') else it
    }
    return "$$whole.$frac"
}
