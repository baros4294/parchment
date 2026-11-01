package io.parchment.ai.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
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
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
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
    minHeight: Dp = 36.dp
) {
    val p = LocalParchmentPalette.current
    val shape = RoundedCornerShape(6.dp)
    val textStyle = if (monospace) p.monoStyle else p.uiBodyStyle

    Column(modifier = modifier) {
        if (label.isNotBlank()) {
            BasicText(
                text = label,
                style = p.uiLabelStyle.copy(color = p.narrationInk, fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(4.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = textStyle,
            cursorBrush = SolidColor(p.accent),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (singleLine) minHeight else Dp.Unspecified)
                        .clip(shape)
                        .background(p.cardBg)
                        .border(1.dp, p.border, shape)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        BasicText(
                            text = placeholder,
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
