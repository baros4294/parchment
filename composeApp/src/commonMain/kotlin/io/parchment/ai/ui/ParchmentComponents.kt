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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import io.parchment.ai.model.ProviderProfile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Adaptive Window Size Breakpoints (WindowSizeClass pattern for Mobile Portrait vs Tablet vs Desktop).
 */
data class ParchmentWindowSize(
    val widthDp: Dp = 1024.dp,
    val heightDp: Dp = 768.dp
) {
    val isCompact: Boolean get() = widthDp < 600.dp
    val isVeryNarrow: Boolean get() = widthDp < 410.dp
    val isMedium: Boolean get() = widthDp in 600.dp..840.dp
    val isExpanded: Boolean get() = widthDp > 840.dp
}

val LocalParchmentWindowSize = compositionLocalOf { ParchmentWindowSize() }

/**
 * Adaptive Setting Row:
 * - On Desktop (`!isCompact`): Places `label` on the left and `content` on the right (`SpaceBetween`).
 * - On Mobile (`isCompact`): Stacks `label` on top and full-width `content` below so chips/buttons never clip or overflow!
 */
@Composable
fun SepiaAdaptiveRow(
    label: String,
    modifier: Modifier = Modifier,
    forceStackOnMobile: Boolean = true,
    content: @Composable () -> Unit
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    if (win.isCompact && forceStackOnMobile) {
        Column(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicText(
                text = label,
                style = p.uiBodyStyle.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
            )
            content()
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = label,
                style = p.uiBodyStyle.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(end = 12.dp)
            )
            content()
        }
    }
}

enum class SepiaButtonStyle {
    PRIMARY,
    SECONDARY,
    OUTLINE,
    GHOST,
    DANGER
}

/**
 * 2026 Tactile KISS Interactive Modifier:
 * - Desktop Hover: Hand cursor, subtle micro-lift (`1.018f` scale, `-0.75.dp` Y-shift).
 * - Mobile & Desktop Press: Spring compression (`0.945f` scale with bouncy return) + native Haptic Feedback.
 */
@Composable
fun Modifier.kissClickable(
    enabled: Boolean = true,
    pressScale: Float = 0.945f,
    hoverScale: Float = 1.018f,
    strongHaptic: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit
): Modifier {
    val haptics = LocalHapticFeedback.current
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val targetScale = when {
        !enabled -> 1f
        isPressed -> pressScale
        isHovered -> hoverScale
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )
    val liftY by animateFloatAsState(
        targetValue = if (isHovered && !isPressed && enabled) -1.2f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
    )
    return this
        .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationY = liftY
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = {
                runCatching {
                    haptics.performHapticFeedback(
                        if (strongHaptic) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
                    )
                }
                onClick()
            }
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
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(if (compact) 7.dp else 8.dp)

    val baseBg = when (style) {
        SepiaButtonStyle.PRIMARY -> if (enabled) p.accent else p.border.copy(alpha = 0.55f)
        SepiaButtonStyle.SECONDARY -> if (enabled) p.accentSecondary else p.border.copy(alpha = 0.55f)
        SepiaButtonStyle.OUTLINE -> if (enabled) p.cardBg else p.elevatedBg.copy(alpha = 0.4f)
        SepiaButtonStyle.GHOST -> Color.Transparent
        SepiaButtonStyle.DANGER -> if (enabled) p.danger else p.border.copy(alpha = 0.55f)
    }

    val targetBg = when {
        !enabled -> baseBg
        isPressed -> when (style) {
            SepiaButtonStyle.PRIMARY, SepiaButtonStyle.SECONDARY, SepiaButtonStyle.DANGER -> baseBg.copy(alpha = 0.82f)
            SepiaButtonStyle.OUTLINE -> p.accent.copy(alpha = 0.18f)
            SepiaButtonStyle.GHOST -> p.accent.copy(alpha = 0.16f)
        }
        isHovered -> when (style) {
            SepiaButtonStyle.PRIMARY, SepiaButtonStyle.SECONDARY, SepiaButtonStyle.DANGER -> baseBg.copy(alpha = 0.91f)
            SepiaButtonStyle.OUTLINE -> p.accent.copy(alpha = 0.09f)
            SepiaButtonStyle.GHOST -> p.accent.copy(alpha = 0.09f)
        }
        else -> baseBg
    }

    val targetText = when (style) {
        SepiaButtonStyle.PRIMARY, SepiaButtonStyle.SECONDARY, SepiaButtonStyle.DANGER ->
            if (enabled) Color(0xFFFAF6EB) else p.mutedInk
        SepiaButtonStyle.OUTLINE -> when {
            !enabled -> p.mutedInk.copy(alpha = 0.6f)
            isPressed || isHovered -> p.accent
            else -> p.primaryInk
        }
        SepiaButtonStyle.GHOST -> when {
            !enabled -> p.mutedInk.copy(alpha = 0.5f)
            isPressed || isHovered -> p.accent
            else -> p.narrationInk
        }
    }

    val targetBorder = when (style) {
        SepiaButtonStyle.PRIMARY -> if (enabled) p.accent else Color.Transparent
        SepiaButtonStyle.SECONDARY -> if (enabled) p.accentSecondary else Color.Transparent
        SepiaButtonStyle.OUTLINE -> when {
            !enabled -> p.border.copy(alpha = 0.32f)
            isPressed -> p.accent
            isHovered -> p.accent.copy(alpha = 0.65f)
            else -> p.border.copy(alpha = 0.52f)
        }
        SepiaButtonStyle.GHOST -> if (enabled && (isPressed || isHovered)) p.accent.copy(alpha = 0.28f) else Color.Transparent
        SepiaButtonStyle.DANGER -> if (enabled) p.danger else Color.Transparent
    }

    val colorSpec = spring<Color>(stiffness = if (isPressed) Spring.StiffnessHigh else Spring.StiffnessMedium)
    val bgColor by animateColorAsState(targetBg, colorSpec)
    val textColor by animateColorAsState(targetText, colorSpec)
    val borderColor by animateColorAsState(targetBorder, colorSpec)

    val win = LocalParchmentWindowSize.current
    val parsed = remember(text) { parseKissLabelIcons(text) }
    val hPad = when {
        compact && win.isCompact -> 8.dp
        compact -> 10.dp
        win.isCompact -> 11.dp
        else -> 14.dp
    }
    val fontSize = when {
        compact && win.isVeryNarrow -> 11.5.sp
        compact -> 12.sp
        win.isCompact -> 12.5.sp
        else -> 13.sp
    }

    Box(
        modifier = modifier
            .kissClickable(
                enabled = enabled,
                strongHaptic = style == SepiaButtonStyle.PRIMARY || style == SepiaButtonStyle.DANGER,
                interactionSource = interactionSource,
                onClick = onClick
            )
            .clip(shape)
            .background(bgColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(if (isPressed || isHovered) 0.9.dp else 0.65.dp, borderColor, shape)
                } else Modifier
            )
            .padding(
                horizontal = hPad,
                vertical = if (compact) 6.dp else 8.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (parsed.leadingIcon != null) {
                KissVectorIcon(
                    icon = parsed.leadingIcon,
                    tint = textColor,
                    size = if (compact) 12.5.dp else 14.5.dp
                )
            }
            if (parsed.cleanText.isNotEmpty()) {
                BasicText(
                    text = parsed.cleanText,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = p.uiBodyStyle.copy(
                        color = textColor,
                        fontSize = fontSize,
                        fontWeight = if (style == SepiaButtonStyle.PRIMARY || style == SepiaButtonStyle.SECONDARY) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Medium
                        }
                    )
                )
            }
            if (parsed.trailingIcon != null) {
                KissVectorIcon(
                    icon = parsed.trailingIcon,
                    tint = textColor,
                    size = if (compact) 11.5.dp else 13.dp
                )
            }
        }
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
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(7.dp)
    val chipColorSpec = spring<Color>(stiffness = if (isPressed) Spring.StiffnessHigh else Spring.StiffnessMedium)
    val bg by animateColorAsState(
        targetValue = when {
            isPressed -> p.accent.copy(alpha = 0.24f)
            selected -> p.accent.copy(alpha = 0.15f)
            isHovered -> p.accent.copy(alpha = 0.09f)
            else -> p.cardBg.copy(alpha = 0.78f)
        },
        animationSpec = chipColorSpec
    )
    val borderCol by animateColorAsState(
        targetValue = when {
            selected || isPressed -> p.accent
            isHovered -> p.accent.copy(alpha = 0.6f)
            else -> p.border.copy(alpha = 0.48f)
        },
        animationSpec = chipColorSpec
    )
    val txtCol by animateColorAsState(
        targetValue = if (selected || isPressed || isHovered) p.accent else p.narrationInk,
        animationSpec = chipColorSpec
    )

    val parsed = remember(label) { parseKissLabelIcons(label) }
    val win = LocalParchmentWindowSize.current

    Box(
        modifier = modifier
            .kissClickable(interactionSource = interactionSource, onClick = onClick)
            .clip(shape)
            .background(bg)
            .border(if (selected || isHovered) 0.9.dp else 0.65.dp, borderCol, shape)
            .padding(horizontal = if (win.isCompact) 7.dp else 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (parsed.leadingIcon != null) {
                KissVectorIcon(
                    icon = parsed.leadingIcon,
                    tint = txtCol,
                    size = 13.dp
                )
            }
            if (parsed.cleanText.isNotEmpty()) {
                BasicText(
                    text = parsed.cleanText,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = p.uiLabelStyle.copy(
                        color = txtCol,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                )
            }
            if (parsed.trailingIcon != null) {
                KissVectorIcon(
                    icon = parsed.trailingIcon,
                    tint = txtCol,
                    size = if (parsed.trailingIcon == KissIcon.CIRCLE_DOT) 8.dp else 11.5.dp
                )
            }
        }
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
            val parsed = remember(label) { parseKissLabelIcons(label) }
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (parsed.leadingIcon != null) {
                        KissVectorIcon(
                            icon = parsed.leadingIcon,
                            tint = tabText,
                            size = 13.dp
                        )
                    }
                    if (parsed.cleanText.isNotEmpty()) {
                        BasicText(
                            text = parsed.cleanText,
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
                    if (parsed.trailingIcon != null) {
                        KissVectorIcon(
                            icon = parsed.trailingIcon,
                            tint = tabText,
                            size = 11.5.dp
                        )
                    }
                }
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
    maxLines: Int = if (singleLine) 1 else 8,
    onEnterKeySend: ((isCtrlOrMetaPressed: Boolean, isShiftPressed: Boolean) -> Boolean)? = null,
    onClipboardPaste: (() -> Boolean)? = null
) {
    val p = LocalParchmentPalette.current
    val shape = RoundedCornerShape(8.dp)
    val textStyle = if (monospace) p.monoStyle else p.uiBodyStyle
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    var consumeNextEnterKeyUp by remember { mutableStateOf(false) }

    var textFieldValueState by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    var lastTextValue by remember { mutableStateOf(value) }
    if (value != lastTextValue) {
        // External state change (e.g. selecting a `/command ` from autocomplete or streaming a draft):
        // sync textFieldValueState and place the cursor at the END of the new text!
        lastTextValue = value
        textFieldValueState = TextFieldValue(text = value, selection = TextRange(value.length))
    }
    val textFieldValue = textFieldValueState
    val borderColor by animateColorAsState(
        targetValue = when {
            borderless -> Color.Transparent
            isFocused -> p.accent.copy(alpha = 0.85f)
            else -> p.border.copy(alpha = 0.75f)
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium)
    )

    Column(modifier = modifier) {
        if (label.isNotBlank()) {
            BasicText(
                text = label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = p.uiLabelStyle.copy(color = p.narrationInk, fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(4.dp))
        }
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newTextFieldValue ->
                textFieldValueState = newTextFieldValue
                val textChanged = lastTextValue != newTextFieldValue.text
                lastTextValue = newTextFieldValue.text
                if (textChanged) {
                    onValueChange(newTextFieldValue.text)
                }
            },
            singleLine = singleLine,
            maxLines = maxLines,
            textStyle = textStyle,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!singleLine || onEnterKeySend != null || onClipboardPaste != null) {
                        Modifier.onPreviewKeyEvent { keyEvent ->
                            // 1. Check Ctrl+V / Cmd+V / Shift+Insert for Clipboard Image / Video / File Paste
                            if (onClipboardPaste != null &&
                                keyEvent.type == KeyEventType.KeyDown &&
                                !keyEvent.isAltPressed &&
                                (((keyEvent.isCtrlPressed || keyEvent.isMetaPressed) && keyEvent.key == Key.V) ||
                                    (keyEvent.isShiftPressed && keyEvent.key == Key.Insert))
                            ) {
                                if (onClipboardPaste()) {
                                    return@onPreviewKeyEvent true
                                }
                            }

                            // 2. Check Enter / Shift+Enter / Ctrl+Enter for Send vs Newline
                            val isEnter = keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter
                            if (isEnter && !keyEvent.isAltPressed) {
                                val isCtrlOrMeta = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
                                val isShift = keyEvent.isShiftPressed
                                return@onPreviewKeyEvent when (keyEvent.type) {
                                    KeyEventType.KeyDown -> {
                                        val consumedBySend = onEnterKeySend?.invoke(isCtrlOrMeta, isShift) == true
                                        if (consumedBySend) {
                                            consumeNextEnterKeyUp = true
                                            true
                                        } else if (!singleLine && (isShift || isCtrlOrMeta)) {
                                            // Compose Foundation's defaultKeyMapping only maps unmodified Enter to NEW_LINE
                                            // and ignores Shift+Enter / Ctrl+Enter. Insert '\n' directly at the active selection.
                                            val curText = textFieldValue.text
                                            val start = textFieldValue.selection.min.coerceIn(0, curText.length)
                                            val end = textFieldValue.selection.max.coerceIn(0, curText.length)
                                            val newText = curText.substring(0, start) + "\n" + curText.substring(end)
                                            val nextCursor = start + 1
                                            val updatedValue = TextFieldValue(
                                                text = newText,
                                                selection = TextRange(nextCursor)
                                            )
                                            textFieldValueState = updatedValue
                                            if (lastTextValue != newText) {
                                                lastTextValue = newText
                                                onValueChange(newText)
                                            }
                                            consumeNextEnterKeyUp = true
                                            true
                                        } else {
                                            consumeNextEnterKeyUp = false
                                            false
                                        }
                                    }
                                    KeyEventType.KeyUp -> {
                                        if (consumeNextEnterKeyUp) {
                                            consumeNextEnterKeyUp = false
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    else -> false
                                }
                            }
                            false
                        }
                    } else Modifier
                ),
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
                    val parsedPlaceholder = remember(placeholder) { parseKissLabelIcons(placeholder) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
                    ) {
                        if (parsedPlaceholder.leadingIcon != null) {
                            KissVectorIcon(
                                icon = parsedPlaceholder.leadingIcon,
                                tint = if (isFocused) p.accent else p.mutedInk.copy(alpha = 0.75f),
                                size = 14.dp
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            if (value.isEmpty() && parsedPlaceholder.cleanText.isNotEmpty()) {
                                BasicText(
                                    text = parsedPlaceholder.cleanText,
                                    maxLines = if (singleLine) 1 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = textStyle.copy(color = p.mutedInk.copy(alpha = 0.6f))
                                )
                            }
                            innerTextField()
                        }
                    }
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
    val win = LocalParchmentWindowSize.current
    val shape = RoundedCornerShape(12.dp)
    val cardPadding = if (win.isCompact) 13.dp else 16.dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.cardBg)
            .border(0.65.dp, p.border.copy(alpha = 0.52f), shape)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                )
            )
            .padding(cardPadding)
    ) {
        if (title.isNotBlank() || headerAction != null) {
            val parsedTitle = remember(title) { parseKissLabelIcons(title) }
            @Composable
            fun RenderSectionTitle() {
                if (title.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (parsedTitle.leadingIcon != null) {
                            KissVectorIcon(
                                icon = parsedTitle.leadingIcon,
                                tint = p.accent,
                                size = 15.dp
                            )
                        }
                        BasicText(
                            text = parsedTitle.cleanText.ifEmpty { title },
                            style = p.uiHeaderStyle.copy(fontSize = 15.sp)
                        )
                    }
                }
            }

            if (win.isCompact && headerAction != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RenderSectionTitle()
                    if (subtitle.isNotBlank()) {
                        BasicText(
                            text = subtitle,
                            style = p.uiLabelStyle
                        )
                    }
                    headerAction()
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = if (headerAction != null) 8.dp else 0.dp)) {
                        RenderSectionTitle()
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
            .height(0.6.dp)
            .background(p.border.copy(alpha = 0.42f))
    )
}

/**
 * Parchment Soft Circular Avatar / Icon Badge (for Assistants, Providers, and Models).
 */
@Composable
fun SepiaAvatarCircle(
    symbol: String,
    selected: Boolean = false,
    sizeDp: Dp = 32.dp,
    modifier: Modifier = Modifier
) {
    val p = LocalParchmentPalette.current
    val bg by animateColorAsState(
        targetValue = if (selected) p.accent.copy(alpha = 0.18f) else p.sheetBg.copy(alpha = 0.85f),
        animationSpec = spring(stiffness = Spring.StiffnessMedium)
    )
    val fgColor = if (selected) p.accent else p.primaryInk
    val parsedIcon = remember(symbol) { parseKissLabelIcons(symbol).leadingIcon }

    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(bg)
            .border(0.8.dp, if (selected) p.accent.copy(alpha = 0.6f) else p.border.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (parsedIcon != null) {
            KissVectorIcon(
                icon = parsedIcon,
                tint = fgColor,
                size = (sizeDp.value * 0.50f).dp
            )
        } else {
            val cleanChars = symbol.filter { it.code < 128 && it.isLetterOrDigit() }.take(2).uppercase()
            if (cleanChars.isNotEmpty()) {
                BasicText(
                    text = cleanChars,
                    style = p.uiBodyStyle.copy(
                        fontSize = (sizeDp.value * 0.42f).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = fgColor
                    )
                )
            } else {
                KissVectorIcon(
                    icon = KissIcon.SPARKLES,
                    tint = fgColor,
                    size = (sizeDp.value * 0.48f).dp
                )
            }
        }
    }
}

/**
 * Parchment Animated Pill Toggle Switch (Pure Compose Foundation).
 */
@Composable
fun SepiaToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalParchmentPalette.current
    val trackColor by animateColorAsState(
        targetValue = if (checked) p.accent else p.border.copy(alpha = 0.7f),
        animationSpec = spring(stiffness = Spring.StiffnessMedium)
    )
    val thumbBias by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    Box(
        modifier = modifier
            .width(42.dp)
            .height(24.dp)
            .kissClickable(pressScale = 0.94f) { onCheckedChange(!checked) }
            .clip(RoundedCornerShape(999.dp))
            .background(trackColor)
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationX = thumbBias * 18.dp.toPx()
                }
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(0xFFFAF6EB))
        )
    }
}

/**
 * Parchment Settings Preference Toggle Row (Title + Subtitle on left, Switch on right).
 */
@Composable
fun SepiaToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String = "",
    modifier: Modifier = Modifier
) {
    val p = LocalParchmentPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val rowBg by animateColorAsState(
        targetValue = when {
            isPressed -> p.accent.copy(alpha = 0.12f)
            isHovered -> p.accent.copy(alpha = 0.06f)
            else -> Color.Transparent
        },
        animationSpec = spring(stiffness = if (isPressed) Spring.StiffnessHigh else Spring.StiffnessMedium)
    )
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(rowBg)
            .kissClickable(
                pressScale = 0.99f,
                hoverScale = 1f,
                interactionSource = interactionSource,
                onClick = { onCheckedChange(!checked) }
            )
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            BasicText(
                text = title,
                style = p.uiBodyStyle.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isHovered || isPressed) p.accent else p.primaryInk
                )
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                BasicText(
                    text = subtitle,
                    style = p.uiLabelStyle.copy(fontSize = 11.sp)
                )
            }
        }
        SepiaToggleSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
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
    val isUnlimited = contextLimitTokens <= 0
    val safeLimit = if (isUnlimited) 1_000_000 else contextLimitTokens
    val targetFraction = if (isUnlimited) 0.08f else (usedTokens.toFloat() / safeLimit.toFloat()).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(stiffness = Spring.StiffnessLow)
    )
    val percent = (targetFraction * 100f).toInt()

    val ringColor by animateColorAsState(
        targetValue = when {
            !isUnlimited && targetFraction >= 0.85f -> p.danger
            !isUnlimited && targetFraction >= 0.60f -> p.accentSecondary
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
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(14.dp)) {
                val strokeWidth = 2.0.dp.toPx()
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

        val win = LocalParchmentWindowSize.current
        val costTag = if (sessionCostUsd > 0.0 && !win.isCompact) " • ${formatUsdCost(sessionCostUsd)}" else ""
        val labelStr = if (win.isCompact) {
            formatCompactTokens(usedTokens)
        } else if (isUnlimited) {
            "${formatCompactTokens(usedTokens)}/∞$costTag"
        } else {
            "${formatCompactTokens(usedTokens)}/${formatCompactTokens(safeLimit)} ($percent%)$costTag"
        }
        BasicText(
            text = labelStr,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = p.uiLabelStyle.copy(
                fontFamily = p.codeFontFamily,
                fontSize = 10.sp,
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

data class SearchableModelOption(
    val profileId: String,
    val profileName: String,
    val modelId: String,
    val vendorGroup: String,
    val contextWindowTokens: Int
)

/**
 * Universal Searchable Vertical Model Selector (`SepiaSearchableModelPicker`):
 * - Search filter input at top (`Enter` immediately selects top match, `Clear` resets).
 * - Provider filter pills (when >1 provider has models) + automatic vendor-family filter pills (`deepseek-ai`, `Qwen`, `zai-org`, `google`, etc.).
 * - Bounded `LazyColumn` vertical list (`heightIn(max = maxListHeight)`) with active model pinned first when unfiltered,
 *   clean monospace model ID on the left, and provider + context window badges on the right.
 */
@Composable
fun SepiaSearchableModelPicker(
    profiles: List<ProviderProfile>,
    selectedProfileId: String,
    selectedModelId: String,
    onSelectModel: (profileId: String, modelId: String) -> Unit,
    modifier: Modifier = Modifier,
    maxListHeight: Dp = 280.dp,
    placeholder: String = "🔍 Search models (e.g. deepseek, qwen, 32b, flash)…"
) {
    val p = LocalParchmentPalette.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedProviderFilter by remember { mutableStateOf("") } // "" = All providers
    var selectedVendorFilter by remember { mutableStateOf("") }   // "" = All vendors

    val allOptions = remember(profiles) {
        profiles.flatMap { prof ->
            val models = if (prof.savedModels.isNotEmpty()) prof.savedModels
            else if (prof.defaultModel.isNotBlank()) listOf(prof.defaultModel)
            else emptyList()
            models.map { m ->
                val vendor = if (m.contains('/')) m.substringBefore('/').trim() else ""
                val ctx = prof.customModelSpecs[m]?.contextWindowTokens ?: prof.contextWindowTokens
                SearchableModelOption(
                    profileId = prof.id,
                    profileName = prof.name,
                    modelId = m,
                    vendorGroup = vendor,
                    contextWindowTokens = ctx
                )
            }
        }
    }

    val providerCounts = remember(allOptions) {
        allOptions.groupBy { it.profileId to it.profileName }
            .map { (pair, list) -> Triple(pair.first, pair.second, list.size) }
    }

    val vendorCounts = remember(allOptions, selectedProviderFilter) {
        val scoped = if (selectedProviderFilter.isEmpty()) allOptions else allOptions.filter { it.profileId == selectedProviderFilter }
        scoped.filter { it.vendorGroup.isNotEmpty() }
            .groupBy { it.vendorGroup }
            .map { (vendor, list) -> vendor to list.size }
            .sortedByDescending { it.second }
    }

    val filteredOptions = remember(allOptions, searchQuery, selectedProviderFilter, selectedVendorFilter, selectedProfileId, selectedModelId) {
        val tokens = searchQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matching = allOptions.filter { opt ->
            val provOk = selectedProviderFilter.isEmpty() || opt.profileId == selectedProviderFilter
            val vendOk = selectedVendorFilter.isEmpty() || opt.vendorGroup.equals(selectedVendorFilter, ignoreCase = true)
            val searchOk = tokens.isEmpty() || tokens.all { tok ->
                opt.modelId.lowercase().contains(tok) ||
                    opt.profileName.lowercase().contains(tok) ||
                    opt.vendorGroup.lowercase().contains(tok)
            }
            provOk && vendOk && searchOk
        }
        if (tokens.isEmpty()) {
            // Pin the currently active model at the top when unfiltered so it is immediately visible
            val activeList = matching.filter { it.profileId == selectedProfileId && it.modelId == selectedModelId }
            val restList = matching.filterNot { it.profileId == selectedProfileId && it.modelId == selectedModelId }
            activeList + restList
        } else {
            matching
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // 1. Search Bar + Clear Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SepiaTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = placeholder,
                minHeight = 34.dp,
                modifier = Modifier.weight(1f),
                onEnterKeySend = { _, _ ->
                    val firstHit = filteredOptions.firstOrNull()
                    if (firstHit != null) {
                        onSelectModel(firstHit.profileId, firstHit.modelId)
                        true
                    } else false
                }
            )
            if (searchQuery.isNotEmpty() || selectedProviderFilter.isNotEmpty() || selectedVendorFilter.isNotEmpty()) {
                SepiaButton(
                    text = "×",
                    compact = true,
                    style = SepiaButtonStyle.GHOST,
                    onClick = {
                        searchQuery = ""
                        selectedProviderFilter = ""
                        selectedVendorFilter = ""
                    }
                )
            }
        }

        // 2. Provider Filter Pills (shown when >1 provider has models — wrapped via FlowRow so pills never clip!)
        if (providerCounts.size > 1) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                SepiaChip(
                    label = "All Providers (${allOptions.size})",
                    selected = selectedProviderFilter.isEmpty(),
                    onClick = {
                        selectedProviderFilter = ""
                        selectedVendorFilter = ""
                    }
                )
                providerCounts.forEach { (profId, profName, count) ->
                    SepiaChip(
                        label = "$profName ($count)",
                        selected = selectedProviderFilter == profId,
                        onClick = {
                            selectedProviderFilter = if (selectedProviderFilter == profId) "" else profId
                            selectedVendorFilter = ""
                        }
                    )
                }
            }
        }

        // 3. Vendor Family Filter Pills (shown when >= 2 vendor families exist — wrapped via FlowRow!)
        if (vendorCounts.size >= 2) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                SepiaChip(
                    label = "All Families",
                    selected = selectedVendorFilter.isEmpty(),
                    onClick = { selectedVendorFilter = "" }
                )
                vendorCounts.forEach { (vendor, count) ->
                    SepiaChip(
                        label = "$vendor ($count)",
                        selected = selectedVendorFilter.equals(vendor, ignoreCase = true),
                        onClick = {
                            selectedVendorFilter = if (selectedVendorFilter.equals(vendor, ignoreCase = true)) "" else vendor
                        }
                    )
                }
            }
        }

        // 4. Bounded Vertical Scrollable Model List
        if (filteredOptions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.elevatedBg.copy(alpha = 0.4f))
                    .padding(12.dp)
            ) {
                BasicText(
                    text = if (allOptions.isEmpty()) {
                        "No models configured yet. Open 'Manage Providers & Models' to fetch or add models."
                    } else {
                        "No models match '$searchQuery'."
                    },
                    style = p.uiLabelStyle
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxListHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.sheetBg.copy(alpha = 0.45f))
                    .border(0.75.dp, p.border.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = filteredOptions,
                    key = { "${it.profileId}::${it.modelId}" }
                ) { opt ->
                    val isSel = opt.profileId == selectedProfileId && opt.modelId == selectedModelId
                    val rowInteraction = remember { MutableInteractionSource() }
                    val isHovered by rowInteraction.collectIsHoveredAsState()
                    val isPressed by rowInteraction.collectIsPressedAsState()
                    val rowShape = RoundedCornerShape(7.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .kissClickable(
                                pressScale = 0.99f,
                                hoverScale = 1.005f,
                                interactionSource = rowInteraction,
                                onClick = { onSelectModel(opt.profileId, opt.modelId) }
                            )
                            .clip(rowShape)
                            .background(
                                when {
                                    isSel -> p.accent.copy(alpha = 0.15f)
                                    isPressed -> p.accent.copy(alpha = 0.12f)
                                    isHovered -> p.accent.copy(alpha = 0.08f)
                                    else -> p.cardBg.copy(alpha = 0.85f)
                                }
                            )
                            .border(
                                width = if (isSel) 1.dp else 0.7.dp,
                                color = when {
                                    isSel -> p.accent
                                    isHovered -> p.accent.copy(alpha = 0.55f)
                                    else -> p.border.copy(alpha = 0.5f)
                                },
                                shape = rowShape
                            )
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ModelLabSvgIcon(
                                modelOrProvider = opt.modelId,
                                tint = if (isSel || isHovered) p.accent else p.narrationInk,
                                size = 14.5.dp,
                                fallbackProtocolName = opt.profileName
                            )
                            BasicText(
                                text = opt.modelId,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = p.monoStyle.copy(
                                    fontSize = 12.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSel || isHovered) p.accent else p.primaryInk
                                )
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (providerCounts.size > 1) {
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 105.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(p.sheetBg)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    BasicText(
                                        text = opt.profileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiLabelStyle.copy(fontSize = 10.sp, color = p.narrationInk)
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(p.elevatedBg)
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                BasicText(
                                    text = if (opt.contextWindowTokens <= 0) "∞" else formatCompactTokens(opt.contextWindowTokens),
                                    style = p.uiLabelStyle.copy(fontSize = 10.sp, fontFamily = p.codeFontFamily)
                                )
                            }
                            if (isSel) {
                                KissVectorIcon(
                                    icon = KissIcon.CHECK,
                                    tint = p.accent,
                                    size = 13.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================================
// GIT / GITHUB-STYLE GREEN & RED UNIFIED LINE DIFF ENGINE (`fs_edit`, `fs_write`, & ```diff)
// ============================================================================================
enum class DiffLineKind {
    CONTEXT,
    REMOVED,
    ADDED,
    COLLAPSED
}

data class DiffLine(
    val kind: DiffLineKind,
    val text: String
)

/**
 * Computes a clean unified line-by-line diff using LCS (Longest Common Subsequence) with
 * automatic collapsing of long unchanged context regions (> 6 lines).
 */
fun computeUnifiedLineDiff(oldText: String, newText: String): List<DiffLine> {
    val oldLines = if (oldText.isEmpty()) emptyList() else oldText.replace("\r\n", "\n").lines()
    val newLines = if (newText.isEmpty()) emptyList() else newText.replace("\r\n", "\n").lines()
    if (oldLines.isEmpty() && newLines.isEmpty()) return emptyList()
    if (oldLines.isEmpty()) {
        return newLines.take(120).map { DiffLine(DiffLineKind.ADDED, it) }
    }
    if (newLines.isEmpty()) {
        return oldLines.take(120).map { DiffLine(DiffLineKind.REMOVED, it) }
    }

    // Cap LCS matrix dimensions on huge files for 0ms UI responsiveness
    val maxDim = 180
    if (oldLines.size > maxDim || newLines.size > maxDim) {
        val removed = oldLines.take(80).map { DiffLine(DiffLineKind.REMOVED, it) }
        val added = newLines.take(80).map { DiffLine(DiffLineKind.ADDED, it) }
        return removed + added
    }

    val m = oldLines.size
    val n = newLines.size
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
        for (j in n - 1 downTo 0) {
            dp[i][j] = if (oldLines[i] == newLines[j]) {
                1 + dp[i + 1][j + 1]
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }

    val rawDiff = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < m && j < n) {
        if (oldLines[i] == newLines[j]) {
            rawDiff.add(DiffLine(DiffLineKind.CONTEXT, oldLines[i]))
            i++
            j++
        } else if (dp[i + 1][j] >= dp[i][j + 1]) {
            rawDiff.add(DiffLine(DiffLineKind.REMOVED, oldLines[i]))
            i++
        } else {
            rawDiff.add(DiffLine(DiffLineKind.ADDED, newLines[j]))
            j++
        }
    }
    while (i < m) {
        rawDiff.add(DiffLine(DiffLineKind.REMOVED, oldLines[i]))
        i++
    }
    while (j < n) {
        rawDiff.add(DiffLine(DiffLineKind.ADDED, newLines[j]))
        j++
    }

    // Collapse runs of > 6 consecutive CONTEXT lines to keep the diff focused
    val compacted = mutableListOf<DiffLine>()
    var idx = 0
    while (idx < rawDiff.size) {
        if (rawDiff[idx].kind == DiffLineKind.CONTEXT) {
            var endCtx = idx
            while (endCtx < rawDiff.size && rawDiff[endCtx].kind == DiffLineKind.CONTEXT) {
                endCtx++
            }
            val runLen = endCtx - idx
            if (runLen > 6) {
                compacted.add(rawDiff[idx])
                compacted.add(rawDiff[idx + 1])
                compacted.add(DiffLine(DiffLineKind.COLLAPSED, "⋯ ${runLen - 4} unchanged lines ⋯"))
                compacted.add(rawDiff[endCtx - 2])
                compacted.add(rawDiff[endCtx - 1])
            } else {
                for (k in idx until endCtx) compacted.add(rawDiff[k])
            }
            idx = endCtx
        } else {
            compacted.add(rawDiff[idx])
            idx++
        }
    }
    return compacted
}

@Composable
fun SepiaDiffLinesList(
    diffLines: List<DiffLine>,
    modifier: Modifier = Modifier
) {
    val p = LocalParchmentPalette.current
    val addedColor = Color(0xFF2E7D32)
    val removedColor = p.danger

    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        diffLines.forEach { line ->
            when (line.kind) {
                DiffLineKind.REMOVED -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(removedColor.copy(alpha = 0.14f))
                            .padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .height(20.dp)
                                .background(removedColor)
                        )
                        Spacer(Modifier.width(7.dp))
                        BasicText(
                            text = "- ${line.text}",
                            style = p.monoStyle.copy(color = removedColor, fontSize = 11.5.sp)
                        )
                    }
                }
                DiffLineKind.ADDED -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(addedColor.copy(alpha = 0.14f))
                            .padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .height(20.dp)
                                .background(addedColor)
                        )
                        Spacer(Modifier.width(7.dp))
                        BasicText(
                            text = "+ ${line.text}",
                            style = p.monoStyle.copy(color = addedColor, fontSize = 11.5.sp)
                        )
                    }
                }
                DiffLineKind.COLLAPSED -> {
                    BasicText(
                        text = line.text,
                        style = p.uiLabelStyle.copy(
                            color = p.mutedInk,
                            fontFamily = p.codeFontFamily,
                            fontSize = 10.5.sp
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
                DiffLineKind.CONTEXT -> {
                    BasicText(
                        text = "  ${line.text}",
                        style = p.monoStyle.copy(color = p.narrationInk, fontSize = 11.5.sp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SepiaRawDiffLinesView(
    rawDiffText: String,
    modifier: Modifier = Modifier
) {
    val parsed = remember(rawDiffText) {
        rawDiffText.lines().map { rawLine ->
            when {
                rawLine.startsWith("+") && !rawLine.startsWith("+++") ->
                    DiffLine(DiffLineKind.ADDED, rawLine.removePrefix("+").removePrefix(" "))
                rawLine.startsWith("-") && !rawLine.startsWith("---") ->
                    DiffLine(DiffLineKind.REMOVED, rawLine.removePrefix("-").removePrefix(" "))
                rawLine.startsWith("@@") ->
                    DiffLine(DiffLineKind.COLLAPSED, rawLine)
                else ->
                    DiffLine(DiffLineKind.CONTEXT, rawLine.removePrefix(" "))
            }
        }
    }
    SepiaDiffLinesList(diffLines = parsed, modifier = modifier)
}

@Composable
fun SepiaFileDiffCard(
    filePath: String,
    actionLabel: String,
    oldText: String,
    newText: String,
    resultSummary: String = "",
    isError: Boolean = false,
    modifier: Modifier = Modifier
) {
    val p = LocalParchmentPalette.current
    val diffLines = remember(oldText, newText) { computeUnifiedLineDiff(oldText, newText) }
    val addedCount = remember(diffLines) { diffLines.count { it.kind == DiffLineKind.ADDED } }
    val removedCount = remember(diffLines) { diffLines.count { it.kind == DiffLineKind.REMOVED } }
    var showDiffBody by remember { mutableStateOf(true) }
    val addedGreen = Color(0xFF2E7D32)
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.cardBg)
            .border(
                width = 0.85.dp,
                color = if (isError) p.danger.copy(alpha = 0.7f) else p.border.copy(alpha = 0.8f),
                shape = shape
            )
    ) {
        // Diff Card Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.sheetBg.copy(alpha = 0.85f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val cleanPath = filePath.replace('\\', '/').ifBlank { "workspace file" }
            val shortFileName = cleanPath.substringAfterLast('/')
            val parentDir = if (cleanPath.contains('/')) cleanPath.substringBeforeLast('/') else ""
            Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KissVectorIcon(
                    icon = KissIcon.DIFF,
                    tint = if (isError) p.danger else p.accent,
                    size = 13.5.dp
                )
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = actionLabel,
                            style = p.uiLabelStyle.copy(color = p.narrationInk, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        )
                        BasicText(
                            text = shortFileName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = p.monoStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                        )
                    }
                    if (parentDir.isNotEmpty()) {
                        BasicText(
                            text = parentDir,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = p.monoStyle.copy(color = p.mutedInk, fontSize = 9.5.sp)
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (addedCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(addedGreen.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        BasicText(
                            text = "+$addedCount",
                            style = p.monoStyle.copy(color = addedGreen, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                        )
                    }
                }
                if (removedCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(p.danger.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        BasicText(
                            text = "−$removedCount",
                            style = p.monoStyle.copy(color = p.danger, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                        )
                    }
                }
                if (diffLines.isNotEmpty()) {
                    SepiaButton(
                        text = if (showDiffBody) "▴" else "▾",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { showDiffBody = !showDiffBody }
                    )
                }
            }
        }

        if (isError && resultSummary.isNotBlank()) {
            SepiaDivider()
            BasicText(
                text = resultSummary,
                style = p.monoStyle.copy(color = p.danger, fontSize = 11.sp),
                modifier = Modifier.padding(9.dp)
            )
        } else if (showDiffBody && diffLines.isNotEmpty()) {
            SepiaDivider()
            SepiaDiffLinesList(diffLines = diffLines)
        } else if (resultSummary.isNotBlank()) {
            SepiaDivider()
            BasicText(
                text = resultSummary,
                style = p.monoStyle.copy(color = p.narrationInk, fontSize = 11.sp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}
