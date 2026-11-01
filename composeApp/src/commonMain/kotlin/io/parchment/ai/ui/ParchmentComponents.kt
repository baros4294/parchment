package io.parchment.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val shape = RoundedCornerShape(6.dp)

    val bgColor = when (style) {
        SepiaButtonStyle.PRIMARY -> if (enabled) p.accent else p.border
        SepiaButtonStyle.SECONDARY -> if (enabled) p.accentSecondary else p.border
        SepiaButtonStyle.OUTLINE -> p.cardBg
        SepiaButtonStyle.GHOST -> Color.Transparent
        SepiaButtonStyle.DANGER -> if (enabled) p.danger else p.border
    }

    val textColor = when (style) {
        SepiaButtonStyle.PRIMARY, SepiaButtonStyle.SECONDARY, SepiaButtonStyle.DANGER -> Color(0xFFFAF6EB)
        SepiaButtonStyle.OUTLINE -> if (enabled) p.primaryInk else p.mutedInk
        SepiaButtonStyle.GHOST -> if (enabled) p.narrationInk else p.mutedInk
    }

    val borderColor = when (style) {
        SepiaButtonStyle.PRIMARY -> p.accent
        SepiaButtonStyle.SECONDARY -> p.accentSecondary
        SepiaButtonStyle.OUTLINE -> p.border
        SepiaButtonStyle.GHOST -> Color.Transparent
        SepiaButtonStyle.DANGER -> p.danger
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(1.dp, borderColor, shape)
                } else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = if (compact) 9.dp else 13.dp,
                vertical = if (compact) 5.dp else 7.dp
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
                fontWeight = FontWeight.Medium
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
    val shape = RoundedCornerShape(5.dp)
    val bg = if (selected) p.accent.copy(alpha = 0.16f) else p.elevatedBg.copy(alpha = 0.65f)
    val borderCol = if (selected) p.accent else p.border
    val txtCol = if (selected) p.accent else p.narrationInk

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, borderCol, shape)
            .clickable(onClick = onClick)
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

@Composable
fun SepiaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String = "",
    singleLine: Boolean = true,
    monospace: Boolean = false,
    minHeight: Dp = 36.dp,
    maxHeight: Dp = 220.dp,
    maxLines: Int = if (singleLine) 1 else 8
) {
    val p = LocalParchmentPalette.current
    val shape = RoundedCornerShape(6.dp)
    val textStyle = if (monospace) p.monoStyle else p.uiBodyStyle

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
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight, max = if (singleLine) minHeight.coerceAtLeast(38.dp) else maxHeight)
                        .clip(shape)
                        .background(p.cardBg)
                        .border(1.dp, p.border, shape)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        BasicText(
                            text = placeholder,
                            maxLines = if (singleLine) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                            style = textStyle.copy(color = p.mutedInk.copy(alpha = 0.65f))
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
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.cardBg)
            .border(1.dp, p.border, shape)
            .padding(14.dp)
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
                            style = p.uiHeaderStyle.copy(fontSize = 15.5.sp)
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
            Spacer(Modifier.height(10.dp))
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
            .height(1.dp)
            .background(p.border.copy(alpha = 0.75f))
    )
}

/**
 * Circular Context Ring + Mini Progress Bar in the Chat Composer Area.
 * Displays live estimated token usage vs. context window limit and session cost.
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
    val fraction = (usedTokens.toFloat() / safeLimit.toFloat()).coerceIn(0f, 1f)
    val percent = (fraction * 100f).toInt()

    val ringColor = when {
        fraction >= 0.85f -> p.danger
        fraction >= 0.60f -> p.accentSecondary
        else -> p.accent
    }

    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(p.cardBg)
            .border(1.dp, p.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Round Circular Token Gauge
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(22.dp)) {
                val strokeWidth = 2.8.dp.toPx()
                drawArc(
                    color = p.border,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = (fraction * 360f).coerceAtLeast(4f),
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            BasicText(
                text = "$percent%",
                style = p.uiLabelStyle.copy(
                    fontSize = 8.sp,
                    color = p.primaryInk,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        // 2. Token Readout + Mini Horizontal Progress Bar
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val costTag = if (sessionCostUsd > 0.0) " • ${formatUsdCost(sessionCostUsd)}" else ""
            BasicText(
                text = "${formatCompactTokens(usedTokens)} / ${formatCompactTokens(safeLimit)} tok$costTag",
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = p.uiLabelStyle.copy(
                    fontFamily = p.codeFontFamily,
                    fontSize = 10.5.sp,
                    color = p.primaryInk,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Box(
                modifier = Modifier
                    .width(84.dp)
                    .height(3.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(p.border.copy(alpha = 0.6f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                        .height(3.5.dp)
                        .background(ringColor)
                )
            }
        }
    }
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
