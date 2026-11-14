package io.parchment.ai.ui

import androidx.compose.foundation.Canvas
import io.parchment.ai.strings.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.key
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.network.ModelModalityProfile

/** A named stop on a slider and the exact value it sends to the provider. */
internal data class SliderStop(val label: String, val value: String)

internal val THINKING_STOPS = listOf(
    SliderStop(Strings.off, "none"), SliderStop(Strings.low, "low"), SliderStop(Strings.medium, "medium"),
    SliderStop(Strings.high, "high"), SliderStop(Strings.extra, "xhigh"), SliderStop(Strings.max, "max")
)
internal val IMAGE_ASPECTS = listOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "21:9")
internal val VIDEO_ASPECTS = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")
private val THINKING_AUTOMATIC = listOf(Strings.adaptive to "adaptive", Strings.automatic to "auto")
private val SPEECH_SPEEDS = listOf(0.5, 0.75, 0.85, 1.0, 1.25, 1.5, 2.0)
private val VOICES = listOf("alloy", "nova", "echo", "onyx", "shimmer", "coral", "sage", "Puck", "Kore", "Fenrir", "Aoede")
private const val MIN_VIDEO_SECONDS = 4
private const val MAX_VIDEO_SECONDS = 15
private const val MIN_SPEECH_SPEED = 0.25
private const val MAX_SPEECH_SPEED = 4.0

/** A width and height from text like `21:9`, or null if it is not a usable ratio. */
internal fun parseAspect(ratio: String): Pair<Float, Float>? {
    val parts = ratio.trim().split(':', 'x', 'X')
    if (parts.size != 2) return null
    val width = parts[0].trim().toFloatOrNull()?.takeIf { it > 0f && it.isFinite() } ?: return null
    val height = parts[1].trim().toFloatOrNull()?.takeIf { it > 0f && it.isFinite() } ?: return null
    return width to height
}

internal fun sliderIndexAt(x: Float, width: Float, inset: Float, stepCount: Int): Int {
    val usable = (width - inset * 2f).coerceAtLeast(1f)
    return (((x - inset) / usable).coerceIn(0f, 1f) * (stepCount - 1)).roundToInt()
}

private object AboveAnchor : PopupPositionProvider {
    private const val GAP = 6
    private const val MARGIN = 8
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width - MARGIN).coerceAtLeast(MARGIN)
        val above = anchorBounds.top - popupContentSize.height - GAP
        return IntOffset(x, if (above >= MARGIN) above else anchorBounds.bottom + GAP)
    }
}

/** A composer chip that opens its control above itself. */
@Composable
private fun PopoverChip(label: String, hint: String, selected: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalParchmentPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        SepiaChip(label = label, hint = hint, selected = selected || open, onClick = { open = !open })
        if (open) {
            Popup(popupPositionProvider = AboveAnchor, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                Column(
                    Modifier.width(290.dp).clip(RoundedCornerShape(Radius.r12)).background(p.cardBg)
                        .border(LineWidth.hairline, p.border, RoundedCornerShape(Radius.r12)).padding(Space.s12),
                    verticalArrangement = Arrangement.spacedBy(Space.s10),
                    content = content
                )
            }
        }
    }
}

/** A slider with fixed stops, usable by mouse, touch, keyboard and screen reader. */
@Composable
internal fun StepSlider(stepCount: Int, selected: Int, description: String, valueText: String, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalParchmentPalette.current
    val choose by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(selected)
    var width by remember { mutableStateOf(1f) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { 11.dp.toPx() }
    val last = stepCount - 1
    Canvas(
        modifier.fillMaxWidth().height(32.dp).onSizeChanged { width = it.width.toFloat() }
            .semantics {
                role = Role.DropdownList
                contentDescription = description
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(selected.coerceAtLeast(0).toFloat(), 0f..last.toFloat(), steps = (stepCount - 2).coerceAtLeast(0))
                setProgress { target ->
                    choose(target.roundToInt().coerceIn(0, last))
                    true
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val next = when (event.key) {
                    Key.DirectionLeft, Key.DirectionDown -> current - 1
                    Key.DirectionRight, Key.DirectionUp -> current + 1
                    else -> return@onPreviewKeyEvent false
                }
                choose(next.coerceIn(0, last))
                true
            }
            .focusable(interactionSource = interaction)
            .pointerInput(stepCount) { detectTapGestures { choose(sliderIndexAt(it.x, width, insetPx, stepCount)) } }
            .pointerInput(stepCount) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    choose(sliderIndexAt(change.position.x, width, insetPx, stepCount))
                }
            }
    ) {
        val y = size.height / 2f
        val usable = size.width - insetPx * 2f
        fun xAt(index: Int) = insetPx + usable * index / last.coerceAtLeast(1)
        drawLine(p.border, Offset(insetPx, y), Offset(size.width - insetPx, y), strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        if (selected > 0) drawLine(p.accent, Offset(insetPx, y), Offset(xAt(selected), y), strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        if (stepCount <= 8) repeat(stepCount) { index -> drawCircle(if (index <= selected) p.accent else p.border, 3.5.dp.toPx(), Offset(xAt(index), y)) }
        if (selected >= 0) {
            drawCircle(p.accent, 9.dp.toPx(), Offset(xAt(selected), y))
            drawCircle(p.cardBg, 4.dp.toPx(), Offset(xAt(selected), y))
        }
        if (focused) drawCircle(p.accent.copy(alpha = 0.3f), 13.dp.toPx(), Offset(xAt(selected.coerceAtLeast(0)), y))
    }
}

/** Typed values are sent exactly as written, so models with their own settings keep working. */
@Composable
private fun CustomValueField(value: String, placeholder: String, current: String? = null, onChange: (String) -> Unit) {
    var draft by remember { mutableStateOf(value) }
    LaunchedEffect(current) {
        if (current != null && current.trim() != draft.trim()) draft = value
    }
    SepiaTextField(
        value = draft,
        onValueChange = { typed -> draft = typed; onChange(typed.trim()) },
        placeholder = placeholder,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PopoverHeading(title: String, value: String) {
    val p = LocalParchmentPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText(title, style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold))
        BasicText(value, style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(choices: List<Pair<String, String>>, current: String, onChoose: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s6)) {
        choices.forEach { (label, value) ->
            SepiaChip(label = label, selected = value.equals(current, ignoreCase = true), onClick = { onChoose(value) })
        }
    }
}

@Composable
internal fun ThinkingControl(chipLabel: String, value: String, onChange: (String) -> Unit) {
    val p = LocalParchmentPalette.current
    val normalized = value.trim().lowercase()
    val index = THINKING_STOPS.indexOfFirst { it.value == normalized }
    val shown = THINKING_STOPS.getOrNull(index)?.label
        ?: THINKING_AUTOMATIC.firstOrNull { it.second == normalized }?.first
        ?: value.ifBlank { Strings.default }
    PopoverChip(chipLabel, "How much the model thinks before it answers", normalized.isNotEmpty() && normalized != "none") {
        PopoverHeading(Strings.thinking, shown)
        StepSlider(THINKING_STOPS.size, index, Strings.thinkingLevel, shown, { onChange(THINKING_STOPS[it].value) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(Strings.faster, style = p.uiLabelStyle)
            BasicText(Strings.moreThorough, style = p.uiLabelStyle)
        }
        ChoiceRow(THINKING_AUTOMATIC, normalized, onChange)
        CustomValueField(if (index < 0 && THINKING_AUTOMATIC.none { it.second == normalized }) value else "", "Custom value", value, onChange)
    }
}

@Composable
private fun AspectTile(ratio: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalParchmentPalette.current
    val (w, h) = parseAspect(ratio) ?: (1f to 1f)
    Column(
        Modifier.kissClickable(selected = selected, onClick = onClick).clip(RoundedCornerShape(Radius.r8))
            .background(if (selected) p.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = Space.s6, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Canvas(Modifier.size(38.dp)) {
            val scale = size.minDimension / maxOf(w, h)
            val shape = Size(w * scale, h * scale)
            drawRoundRect(
                color = if (selected) p.accent else p.mutedInk,
                topLeft = Offset((size.width - shape.width) / 2f, (size.height - shape.height) / 2f),
                size = shape,
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
        BasicText(ratio, style = p.uiLabelStyle.copy(color = if (selected) p.accent else p.primaryInk))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AspectControl(icon: String, value: String, presets: List<String>, onChange: (String) -> Unit) {
    PopoverChip("$icon $value", Strings.theShapeOfThePictureOr, true) {
        PopoverHeading(Strings.shape, value)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            presets.forEach { ratio -> AspectTile(ratio, ratio == value) { onChange(ratio) } }
        }
        CustomValueField(if (value in presets) "" else value, Strings.customShapeLike54, value, onChange)
    }
}

@Composable
internal fun ResolutionControl(value: String, presets: List<String>, highlighted: Boolean, onChange: (String) -> Unit) {
    PopoverChip(value, Strings.howDetailedTheResultIs, highlighted) {
        PopoverHeading(Strings.quality, value)
        ChoiceRow(presets.map { it to it }, value, onChange)
        CustomValueField(if (value in presets) "" else value, Strings.customValue, value, onChange)
    }
}

@Composable
internal fun DurationControl(seconds: Int, onChange: (Int) -> Unit) {
    val current = seconds.coerceIn(MIN_VIDEO_SECONDS, MAX_VIDEO_SECONDS)
    val p = LocalParchmentPalette.current
    PopoverChip("${current}s", Strings.howLongTheVideoRuns, current > 5) {
        PopoverHeading(Strings.length, "$current seconds")
        StepSlider(MAX_VIDEO_SECONDS - MIN_VIDEO_SECONDS + 1, current - MIN_VIDEO_SECONDS, Strings.videoLength, "$current seconds", { onChange(it + MIN_VIDEO_SECONDS) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("$MIN_VIDEO_SECONDS s", style = p.uiLabelStyle)
            BasicText("$MAX_VIDEO_SECONDS s", style = p.uiLabelStyle)
        }
        CustomValueField("", Strings.secondsFromTo(MIN_VIDEO_SECONDS, MAX_VIDEO_SECONDS)) { typed ->
            typed.toIntOrNull()?.let { onChange(it.coerceIn(MIN_VIDEO_SECONDS, MAX_VIDEO_SECONDS)) }
        }
    }
}

@Composable
internal fun VoiceControl(value: String, onChange: (String) -> Unit) {
    PopoverChip("🗣 $value", Strings.theVoiceUsedForSpeech, true) {
        PopoverHeading(Strings.voice, value)
        ChoiceRow(VOICES.map { it to it }, value, onChange)
        CustomValueField(if (VOICES.any { it.equals(value, ignoreCase = true) }) "" else value, Strings.customVoice, value, onChange)
    }
}

@Composable
internal fun SpeechSpeedControl(speed: Double, onChange: (Double) -> Unit) {
    val index = SPEECH_SPEEDS.indexOf(speed)
    PopoverChip("${speed}x", "How fast the voice speaks", speed != 1.0) {
        PopoverHeading(Strings.speed, "${speed}x")
        StepSlider(SPEECH_SPEEDS.size, index, Strings.speechSpeed, "${speed}x", { onChange(SPEECH_SPEEDS[it]) })
        CustomValueField(if (index >= 0) "" else speed.toString(), Strings.customFromTo(MIN_SPEECH_SPEED, MAX_SPEECH_SPEED)) { typed ->
            typed.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { onChange(it.coerceIn(MIN_SPEECH_SPEED, MAX_SPEECH_SPEED)) }
        }
    }
}

/** The composer's bottom row: attach, voice, media, thinking and Tools on the left; usage and Send or Stop on the right. */
@Composable
internal fun ComposerToolbar(
    activeProfile: ProviderProfile,
    activeModalityProfile: ModelModalityProfile,
    preferences: PreferencesConfig,
    pendingAttachments: List<PromptAttachment>,
    isVoiceListening: Boolean,
    isLiveVoiceMode: Boolean,
    isStreaming: Boolean,
    hasTypedInput: Boolean,
    hasActiveTools: Boolean,
    showComposerToolsDrawer: Boolean,
    thinkingLabel: String,
    toolsChipLabel: String,
    liveContextTokens: Int,
    contextLimitTokens: Int,
    sessionTotalCostUsd: Double,
    sessionCostLabel: String,
    onAddAttachment: () -> Unit,
    onTriggerVoiceInput: () -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onComposerToolsDrawerChange: (Boolean) -> Unit,
    onToggleContextBreakdown: () -> Unit,
    onStopStream: () -> Unit,
    onQueuePrompt: () -> Unit,
    onSteerNow: () -> Unit,
    onSend: () -> Unit
) {
    val win = LocalParchmentWindowSize.current
    val leftChipsComposable: @Composable (Modifier) -> Unit = { rowMod ->
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = rowMod,
            horizontalArrangement = Arrangement.spacedBy(if (win.isCompact) 3.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s4)
        ) {
            SepiaChip(
                label = "📎",
                contentDescription = Strings.attachFiles,
                selected = pendingAttachments.isNotEmpty(),
                onClick = onAddAttachment
            )
            SepiaChip(
                label = "🎙",
                contentDescription = Strings.voiceInput,
                selected = isVoiceListening || isLiveVoiceMode,
                onClick = onTriggerVoiceInput
            )
            if (activeModalityProfile.isAnyImageModel) {
                AspectControl("🖼", activeProfile.mediaAspectRatio.ifBlank { "1:1" }, IMAGE_ASPECTS) { ratio -> onUpdateProfile { it.copy(mediaAspectRatio = ratio) } }
                val currentRes = activeProfile.mediaResolution.ifBlank { "1K" }
                ResolutionControl(currentRes, listOf("1K", "2K", "4K"), currentRes in listOf("2K", "4K")) { res -> onUpdateProfile { it.copy(mediaResolution = res) } }
            }
            if (activeModalityProfile.isAnyVideoModel) {
                AspectControl("🎬", activeProfile.mediaAspectRatio.ifBlank { "16:9" }, VIDEO_ASPECTS) { ratio -> onUpdateProfile { it.copy(mediaAspectRatio = ratio) } }
                val currentRes = when (activeProfile.mediaResolution) {
                    "2K" -> "1080p"
                    "", "512", "1K" -> "720p"
                    else -> activeProfile.mediaResolution
                }
                ResolutionControl(currentRes, listOf("720p", "1080p", "4K"), currentRes in listOf("1080p", "4K")) { res -> onUpdateProfile { it.copy(mediaResolution = res) } }
                DurationControl(activeProfile.videoDurationSeconds) { seconds -> onUpdateProfile { it.copy(videoDurationSeconds = seconds) } }
                SepiaChip(
                    label = if (activeProfile.videoGenerateAudio) Strings.audio2 else Strings.mute,
                    selected = activeProfile.videoGenerateAudio,
                    onClick = {
                        onUpdateProfile { it.copy(videoGenerateAudio = !it.videoGenerateAudio) }
                    }
                )
            }
            if (activeModalityProfile.isAnyAudioModel) {
                VoiceControl(activeProfile.audioVoice.ifBlank { "alloy" }) { voice -> onUpdateProfile { it.copy(audioVoice = voice) } }
                if (activeModalityProfile.isDedicatedAudioTts) {
                    SpeechSpeedControl(activeProfile.audioSpeed) { speed -> onUpdateProfile { it.copy(audioSpeed = speed) } }
                }
            }
            if (!activeModalityProfile.isDedicatedImage &&
                !activeModalityProfile.isDedicatedVideo &&
                !activeModalityProfile.isDedicatedAudioTts
            ) {
                ThinkingControl(thinkingLabel, activeProfile.thinkingEffort) { level -> onUpdateProfile { it.copy(thinkingEffort = level) } }
            }
            SepiaChip(
                label = toolsChipLabel,
                contentDescription = Strings.tools,
                hint = Strings.webSearchWorkspaceAndOther,
                selected = showComposerToolsDrawer || hasActiveTools,
                onClick = { onComposerToolsDrawerChange(!showComposerToolsDrawer) }
            )
        }
    }

    // Phones use two full-width rows while streaming (Stop, Queue and Send now need the room) and at large
    // text sizes, where one row would push single chips onto a line of their own.
    val largeText = textGrowth() > LARGE_TEXT_GROWTH
    if (win.isCompact && (isStreaming || largeText)) {
        val contextRing: @Composable () -> Unit = {
            if (preferences.showTokenAndContextStats) {
                SepiaContextGaugeRing(
                    usedTokens = liveContextTokens,
                    contextLimitTokens = contextLimitTokens,
                    sessionCostUsd = sessionTotalCostUsd,
                    sessionCostLabel = sessionCostLabel,
                    onClick = { onToggleContextBreakdown() }
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.s6)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                leftChipsComposable(Modifier.weight(1f))
                if (isStreaming && !largeText && preferences.showTokenAndContextStats) {
                    Spacer(Modifier.width(Space.s6))
                    contextRing()
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isStreaming) {
                    contextRing()
                    Spacer(Modifier.weight(1f))
                    SepiaButton(
                        text = Strings.send,
                        compact = true,
                        enabled = hasTypedInput,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = onSend
                    )
                } else {
                    SepiaButton(
                        text = Strings.stop,
                        hint = Strings.stopThisReply,
                        compact = true,
                        style = SepiaButtonStyle.DANGER,
                        onClick = onStopStream
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.s6),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (largeText) contextRing()
                        SepiaButton(
                            text = Strings.queue,
                            hint = Strings.sendThisAfterTheCurrent,
                            compact = true,
                            enabled = hasTypedInput,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = onQueuePrompt
                        )
                        SepiaButton(
                            text = Strings.sendNow,
                            hint = Strings.sendThisNowToGuide,
                            compact = true,
                            enabled = hasTypedInput,
                            style = SepiaButtonStyle.PRIMARY,
                            onClick = onSteerNow
                        )
                    }
                }
            }
        }
    } else {
        // One full-width row on desktop, and on phones at normal text sizes while idle.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            leftChipsComposable(Modifier.weight(1f))

            Spacer(Modifier.width(Space.s6))

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (preferences.showTokenAndContextStats) {
                    SepiaContextGaugeRing(
                        usedTokens = liveContextTokens,
                        contextLimitTokens = contextLimitTokens,
                        sessionCostUsd = sessionTotalCostUsd,
                        sessionCostLabel = sessionCostLabel,
                        onClick = { onToggleContextBreakdown() }
                    )
                }

                if (isStreaming) {
                    SepiaButton(
                        text = Strings.stop,
                        hint = Strings.stopThisReply,
                        compact = true,
                        style = SepiaButtonStyle.DANGER,
                        onClick = onStopStream
                    )
                    SepiaButton(
                        text = Strings.queue,
                        hint = Strings.sendThisAfterTheCurrent,
                        compact = true,
                        enabled = hasTypedInput,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = onQueuePrompt
                    )
                    SepiaButton(
                        text = Strings.sendNow,
                        hint = Strings.sendThisNowToGuide,
                        compact = true,
                        enabled = hasTypedInput,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = onSteerNow
                    )
                } else {
                    SepiaButton(
                        text = Strings.send,
                        compact = true,
                        enabled = hasTypedInput,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = onSend
                    )
                }
            }
        }
    }
}

/** System text at 130% or more counts as large for layouts that switch to fewer items per row. */
private const val LARGE_TEXT_GROWTH = 1.3f
