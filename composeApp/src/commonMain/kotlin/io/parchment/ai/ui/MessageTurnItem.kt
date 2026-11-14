package io.parchment.ai.ui

import androidx.compose.ui.layout.layout
import io.parchment.ai.strings.Strings
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.hoverable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.parchment.ai.model.turnTextLayout
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.MediaKind
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.model.ViewMode
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.protocol.O200kTokenizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// One message: its reasoning (open while the model thinks, folded when done), the text, copy actions,
// and the footer, where actions sit above the token counts so More never hides buttons on a phone.
private const val MAX_SHRINKABLE_BUBBLE_CHARS = 800
private const val SENT_BUBBLE_WIDTH_FRACTION = 0.84f
private const val REPLY_BUBBLE_WIDTH_FRACTION = 0.94f

/** A messenger bubble: yours on the right, the assistant's on the left, each with one tucked corner. */
private fun Modifier.messageBubble(fromUser: Boolean, shrink: Boolean, fill: Color, edge: Color): Modifier {
    val shape = RoundedCornerShape(16.dp, 16.dp, if (fromUser) 4.dp else 16.dp, if (fromUser) 16.dp else 4.dp)
    return layout { measurable, constraints ->
        val capped = constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth * if (fromUser) SENT_BUBBLE_WIDTH_FRACTION else REPLY_BUBBLE_WIDTH_FRACTION).toInt())
        val bubble = measurable.measure(capped)
        layout(bubble.width, bubble.height) { bubble.place(0, 0) }
    }
        .then(if (shrink) Modifier.width(IntrinsicSize.Max) else Modifier)
        .clip(shape)
        .background(fill)
        .border(LineWidth.hairline, edge, shape)
        .padding(horizontal = 13.dp, vertical = 9.dp)
}

@Composable
internal fun MessageTurnItem(
    node: MessageNode,
    nodeIndex: Int,
    isLatestTurn: Boolean = false,
    userPicture: ImageBitmap? = null,
    viewMode: ViewMode,
    preferences: PreferencesConfig,
    userName: String,
    characterName: String,
    assistantIconEmoji: String = "💬",
    providerName: String,
    inputPricePerM: Double = 0.0,
    outputPricePerM: Double = 0.0,
    isActivelyThinkingThisNode: Boolean,
    onSelectVariant: (Int) -> Unit,
    onSaveEditedText: (String, Boolean, Boolean) -> Unit,
    onToggleContextMute: () -> Unit,
    onTogglePin: () -> Unit = {},
    onDeleteCurrentVariant: () -> Unit = {},
    onDeleteNode: () -> Unit,
    onForkHere: () -> Unit,
    onRewindHere: () -> Unit = {},
    onRegenerate: () -> Unit,
    onTranslate: () -> Unit = {},
    onContinue: () -> Unit,
    onInspectAttachment: (PromptAttachment) -> Unit = {},
    onInspectGeneratedImage: (GeneratedMediaItem) -> Unit = {}
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val activeVar = node.activeVariant
    val isAssistant = node.role == MessageRole.ASSISTANT

    var isEditing by remember(node.id, node.activeVariantIndex) { mutableStateOf(false) }
    var editBuffer by remember(node.id, node.activeVariantIndex, activeVar.content) { mutableStateOf(activeVar.content) }
    var manualThinkingToggle by remember(node.id, node.activeVariantIndex) { mutableStateOf<Boolean?>(null) }
    var showRawWireDrawer by remember(node.id) { mutableStateOf(false) }
    var showMoreActions by remember(node.id) { mutableStateOf(false) }
    var openUsageDetailsId by remember(node.id, node.activeVariantIndex) { mutableStateOf<String?>(null) }
    var showSourcesDrawer by remember(node.id, node.activeVariantIndex) { mutableStateOf(false) }
    var highlightedSourceLineText by remember(node.id, node.activeVariantIndex) { mutableStateOf("") }
    var copiedBadge by remember(node.id, node.activeVariantIndex) { mutableStateOf("") }
    val enableMd = if (isAssistant) preferences.renderAssistantMessagesWithMarkdown else preferences.renderUserMessagesWithMarkdown
    // How reasoning is shown:
    // - While the model reasons: all thinking and tool steps are open by default; Hide switches to a peek
    //   (`isLivePeekOnly`) that shows only the latest thought.
    // - Once it finishes: Hide (or `autoCollapseThinking`) folds the thinking away entirely.
    val isReasoningExpanded = when {
        isActivelyThinkingThisNode -> manualThinkingToggle != false
        manualThinkingToggle != null -> manualThinkingToggle == true
        !preferences.autoCollapseThinking && activeVar.thinkingSummary.isNotBlank() -> true
        else -> false
    }
    val isLivePeekOnly = isActivelyThinkingThisNode && manualThinkingToggle == false
    val turnText = remember(activeVar.content, activeVar.toolCalls) { turnTextLayout(activeVar.content, activeVar.toolCalls) }
    val stepsOnScreen = isReasoningExpanded && !isLivePeekOnly &&
        ((preferences.showThinkingCards && activeVar.thinkingSummary.isNotBlank()) || (preferences.showToolCards && activeVar.toolCalls.isNotEmpty()))
    // Text written between tools sits in the timeline when it is open, and joins the reply when it is not.
    val replyText = when {
        !turnText.hasSteps -> activeVar.content
        stepsOnScreen -> turnText.finalText
        else -> turnText.joinedText
    }

    // The stock assistant name says nothing the model name does not; characters keep theirs.
    val namedCharacter = characterName.takeUnless { it == "Default Assistant" }.orEmpty()
    val speakerTitle = if (isAssistant) {
        characterName.ifBlank { activeVar.modelUsed.ifBlank { "Assistant" } }
    } else {
        userName.ifBlank { Strings.user }
    }

    var variantSwipeDelta by remember(node.id, node.activeVariantIndex) { mutableStateOf(0f) }

    // On pointer devices the quiet footer appears with the turn being read; touch has no hover, so it stays.
    val turnHoverSource = remember { MutableInteractionSource() }
    val turnHovered by turnHoverSource.collectIsHoveredAsState()
    var footerHasFocus by remember { mutableStateOf(false) }
    val footerVisible = win.isCompact || turnHovered || footerHasFocus || showMoreActions || openUsageDetailsId != null || activeVar.isStreaming
    val footerAlpha by animateFloatAsState(if (footerVisible) 1f else 0f, motionTween(Motion.QUICK))
    val showBubbles = viewMode == ViewMode.EDITORIAL_TURNS
    val isSentBubble = showBubbles && !isAssistant

    // No size animation while streaming: text grows every frame, and a spring animating each new line lags behind it.
    val baseContainerModifier = if (viewMode == ViewMode.MANUSCRIPT) {
        Modifier
            .fillMaxWidth()
            .hoverable(turnHoverSource)
            .then(
                if (!node.includedInContext) Modifier.background(p.elevatedBg.copy(alpha = 0.35f), RoundedCornerShape(Radius.r8))
                else Modifier
            )
            .then(
                if (!activeVar.isStreaming) Modifier.animateContentSize(animationSpec = motionSpring())
                else Modifier
            )
            .padding(vertical = Space.s6)
    } else {
        Modifier
            .fillMaxWidth()
            .hoverable(turnHoverSource)
            .then(if (!node.includedInContext) Modifier.alpha(0.62f) else Modifier)
            .then(
                if (!activeVar.isStreaming) Modifier.animateContentSize(animationSpec = motionSpring())
                else Modifier
            )
    }
    val containerModifier = if (node.variants.size > 1 && !isEditing) {
        baseContainerModifier.pointerInput(node.id, node.activeVariantIndex, node.variants.size, isEditing) {
            detectHorizontalDragGestures(
                onDragStart = { variantSwipeDelta = 0f },
                onHorizontalDrag = { _, dragAmount -> variantSwipeDelta += dragAmount },
                onDragEnd = {
                    if (variantSwipeDelta < -64.dp.toPx() && node.activeVariantIndex < node.variants.lastIndex) {
                        onSelectVariant(node.activeVariantIndex + 1)
                    } else if (variantSwipeDelta > 64.dp.toPx() && node.activeVariantIndex > 0) {
                        onSelectVariant(node.activeVariantIndex - 1)
                    }
                    variantSwipeDelta = 0f
                }
            )
        }
    } else {
        baseContainerModifier
    }

    Column(modifier = containerModifier, horizontalAlignment = if (isSentBubble) Alignment.End else Alignment.Start) {
        // Header and version bar; a wrapping row so the name, model and version pills never overlap on a phone.
        val showTurnHeader = isAssistant || preferences.showUserAvatar || preferences.showUserName ||
            preferences.showUserTimestamp || node.variants.size > 1 || !node.includedInContext
        if (showTurnHeader) @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Space.s6)
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false).padding(top = Space.s2, end = Space.s8),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isAssistant && preferences.showModelIcon) {
                    if (namedCharacter.isNotBlank() && assistantIconEmoji != "💬") {
                        SepiaAvatarCircle(
                            symbol = assistantIconEmoji,
                            selected = true,
                            sizeDp = 22.dp
                        )
                    } else {
                        ModelLabSvgIcon(
                            modelOrProvider = activeVar.modelUsed.ifBlank { providerName },
                            tint = p.mutedInk,
                            size = 13.dp,
                            fallbackProtocolName = providerName
                        )
                    }
                } else if (!isAssistant && preferences.showUserAvatar) {
                    ProfilePicture(userPicture, 22.dp)
                }

                val showTurnTimestamp = (isAssistant && preferences.showModelTimestamp) || (!isAssistant && preferences.showUserTimestamp)
                val timeSuffix = if (showTurnTimestamp && activeVar.createdAt > 0L) {
                    formatCompactTimestamp(activeVar.createdAt)
                } else ""

                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (isAssistant && preferences.showModelName) {
                            val provSuffix = if (preferences.showProviderAfterModelName && namedCharacter.isBlank()) " ($providerName)" else ""
                            val primaryHeader = if (namedCharacter.isNotBlank()) {
                                namedCharacter
                            } else {
                                "${activeVar.modelUsed.ifBlank { "Assistant" }}$provSuffix"
                            }
                            BasicText(
                                text = primaryHeader,
                                maxLines = if (win.isCompact) 2 else 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                                style = p.uiLabelStyle.copy(color = p.narrationInk, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            )
                        } else if (!isAssistant && preferences.showUserName) {
                            BasicText(
                                text = speakerTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                                style = p.uiLabelStyle.copy(color = p.narrationInk, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            )
                        }
                        if (timeSuffix.isNotEmpty()) {
                            BasicText(
                                text = timeSuffix,
                                maxLines = 1,
                                style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                            )
                        }
                        if (!node.includedInContext) {
                            BasicText(
                                text = Strings.leftOutOfContext,
                                style = p.uiLabelStyle.copy(color = p.danger, fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }

                    if (isAssistant && preferences.showModelName && namedCharacter.isNotBlank() && activeVar.modelUsed.isNotBlank()) {
                        val provSuffix = if (preferences.showProviderAfterModelName) " • $providerName" else ""
                        val modelDisplay = if (win.isCompact && activeVar.modelUsed.contains('/')) {
                            activeVar.modelUsed.substringAfterLast('/')
                        } else {
                            activeVar.modelUsed
                        }
                        BasicText(
                            text = "$modelDisplay$provSuffix",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiLabelStyle.copy(fontSize = 11.sp)
                        )
                    }
                }
            }

            // Swipe / Branch Variant Selector (< 1 / 3 >) + Reasoning Accordion Trigger
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.s4),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (node.variants.size > 1) {
                    SepiaButton(
                        text = "←",
                        contentDescription = Strings.previousVersion,
                        compact = true,
                        enabled = node.activeVariantIndex > 0,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { onSelectVariant(node.activeVariantIndex - 1) }
                    )
                    BasicText(
                        text = "${node.activeVariantIndex + 1}/${node.variants.size}",
                        style = p.uiLabelStyle.copy(color = p.accentSecondary, fontWeight = FontWeight.SemiBold)
                    )
                    SepiaButton(
                        text = "→",
                        contentDescription = Strings.nextVersion,
                        compact = true,
                        enabled = node.activeVariantIndex < node.variants.lastIndex,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { onSelectVariant(node.activeVariantIndex + 1) }
                    )
                }

                val hasThinking = preferences.showThinkingCards && activeVar.thinkingSummary.isNotBlank()
                val hasTools = preferences.showToolCards && activeVar.toolCalls.isNotEmpty()
                val hasRunningToolInVar = activeVar.toolCalls.any { it.isRunning }
                val isCurrentlyToolStep = hasRunningToolInVar ||
                    (isActivelyThinkingThisNode && hasTools && activeVar.trailingThinking.isBlank())
                if (hasThinking || hasTools) {
                    val sec = if (activeVar.thinkingDurationMs > 0) " ${formatDurationFromMs(activeVar.thinkingDurationMs)}" else ""
                    val toolBadge = if (hasTools) {
                        val tools = activeVar.toolCalls.size
                        val prefix = if (activeVar.thinkingSummary.isNotBlank()) ", " else " "
                        "$prefix$tools tool${if (tools == 1) "" else "s"}"
                    } else ""
                    val chipText = when {
                        isLivePeekOnly && isCurrentlyToolStep -> Strings.toolPeek(sec)
                        isLivePeekOnly -> Strings.peek(sec)
                        isActivelyThinkingThisNode && isCurrentlyToolStep -> Strings.working(sec, toolBadge)
                        isActivelyThinkingThisNode -> Strings.thinking2(sec, toolBadge)
                        isReasoningExpanded -> Strings.thought2(sec, toolBadge)
                        else -> Strings.thought3(sec, toolBadge)
                    }
                    SepiaButton(
                        text = chipText,
                        compact = true,
                        hint = Strings.showOrHideTheThinking,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            manualThinkingToggle = if (isActivelyThinkingThisNode) {
                                if (isLivePeekOnly) true else false
                            } else {
                                !isReasoningExpanded
                            }
                        }
                    )
                }
            }
        }

        // Animated Collapsible Thinking & Tool Timeline (Full Timeline OR Compact Thought/Tool Peek while Working)
        val showDrawer = (isReasoningExpanded || isLivePeekOnly) &&
            ((preferences.showThinkingCards && activeVar.thinkingSummary.isNotBlank()) ||
                (preferences.showToolCards && activeVar.toolCalls.isNotEmpty()))

        AnimatedVisibility(
            visible = showDrawer,
            enter = expandVertically(motionSpring()) + fadeIn(motionTween(Motion.STANDARD)),
            exit = shrinkVertically(motionSpring()) + fadeOut(motionTween(Motion.QUICK))
        ) {
            val livePeekInfo = remember(isLivePeekOnly, activeVar.thinkingSummary, activeVar.trailingThinking, activeVar.toolCalls) {
                if (isLivePeekOnly) extractLatestAgentPeek(activeVar) else LiveAgentPeekInfo(isToolPeek = false, title = "", body = "")
            }
            Column(
                modifier = Modifier
                    .padding(top = Space.s8)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.r10))
                    .background(p.sheetBg.copy(alpha = 0.58f))
                    .border(LineWidth.hairline, if (isActivelyThinkingThisNode) p.accent.copy(alpha = 0.8f) else p.border.copy(alpha = 0.45f), RoundedCornerShape(Radius.r10))
                    .padding(Space.s12),
                verticalArrangement = Arrangement.spacedBy(Space.s8)
            ) {
                // Header Row for Thinking and tool steps Drawer
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(Space.s6)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.s6),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false).padding(top = Space.s2, end = Space.s6)
                    ) {
                        if (isActivelyThinkingThisNode) {
                            KissStreamingPulseDot(color = p.accent)
                        } else {
                            KissVectorIcon(icon = KissIcon.BRAIN, tint = p.accent, size = IconSize.md)
                        }
                        val wordCount = countWordsFast(activeVar.thinkingSummary)
                        val toolCount = activeVar.toolCalls.size
                        val headerSummary = buildString {
                            if (isLivePeekOnly) {
                                append(if (livePeekInfo.isToolPeek) Strings.latestTool else Strings.latestThought)
                            } else if (isActivelyThinkingThisNode) {
                                append(if (livePeekInfo.isToolPeek) Strings.runningToolsAndThinking else Strings.thinking)
                            } else if (win.isCompact) {
                                append(Strings.reasoning)
                            } else {
                                append(Strings.thinkingAndToolSteps)
                            }
                            if (wordCount > 0) append(" ($wordCount words")
                            if (toolCount > 0) {
                                if (wordCount > 0) append(" • $toolCount tools)") else append(" ($toolCount tools)")
                            } else if (wordCount > 0) {
                                append(")")
                            }
                        }
                        BasicText(
                            text = headerSummary,
                            style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                        )
                    }
                    SepiaButton(
                        text = if (isLivePeekOnly) "▾" else "▴",
                        contentDescription = if (isLivePeekOnly) Strings.showThinking else Strings.hideThinking,
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            manualThinkingToggle = if (isLivePeekOnly) true else false
                        }
                    )
                }

                if (isLivePeekOnly) {
                    // Compact Single-Thought OR Active-Tool Peek Window while the agent is working
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Space.s2),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (livePeekInfo.title.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.s6)
                            ) {
                                if (livePeekInfo.isToolPeek) {
                                    KissVectorIcon(
                                        icon = resolveToolKissIcon(livePeekInfo.toolName),
                                        tint = p.accent,
                                        size = 13.dp
                                    )
                                }
                                BasicText(
                                    text = livePeekInfo.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = p.uiBodyStyle.copy(
                                        color = p.primaryInk,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                )
                                if (livePeekInfo.isToolRunning) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(Radius.r4))
                                            .background(p.accent.copy(alpha = 0.16f))
                                            .padding(horizontal = Space.s6, vertical = Space.s2)
                                    ) {
                                        BasicText(
                                            text = Strings.running,
                                            style = p.monoStyle.copy(
                                                color = p.accent,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        if (livePeekInfo.body.isNotEmpty()) {
                            BasicText(
                                text = livePeekInfo.body,
                                maxLines = if (livePeekInfo.isToolPeek) 4 else 3,
                                overflow = TextOverflow.Ellipsis,
                                style = if (livePeekInfo.isToolPeek) {
                                    p.monoStyle.copy(
                                        color = p.narrationInk,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                } else {
                                    p.uiBodyStyle.copy(
                                        color = p.narrationInk,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                            )
                        }
                    }
                } else {
                val hasInterleavedSteps = activeVar.toolCalls.isNotEmpty() &&
                    (activeVar.toolCalls.any { it.precedingThinking.isNotBlank() } || activeVar.trailingThinking.isNotBlank() || turnText.hasSteps)

                @Composable
                fun RenderToolExecutionCard(tc: ToolCallRecord) {
                    val lowerTool = tc.name.lowercase()
                    if (lowerTool == "fs_edit" || lowerTool == "fs_write" || lowerTool == "fs_multi_edit") {
                        val parsedObj: JsonObject? = remember(tc.argumentsJson) {
                            runCatching { compactStateJson.parseToJsonElement(tc.argumentsJson) as? JsonObject }.getOrNull()
                        }
                        val pathStr = parsedObj?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val (oldTxt, newTxt) = remember(lowerTool, parsedObj) {
                            when (lowerTool) {
                                "fs_edit" -> {
                                    val o = parsedObj?.get("old_text")?.jsonPrimitive?.contentOrNull.orEmpty()
                                    val n = parsedObj?.get("new_text")?.jsonPrimitive?.contentOrNull.orEmpty()
                                    o to n
                                }
                                "fs_multi_edit" -> {
                                    val editsArr = parsedObj?.get("edits") as? JsonArray
                                    val o = editsArr?.mapNotNull { (it as? JsonObject)?.get("old_text")?.jsonPrimitive?.contentOrNull }
                                        ?.joinToString("\n...\n").orEmpty()
                                    val n = editsArr?.mapNotNull { (it as? JsonObject)?.get("new_text")?.jsonPrimitive?.contentOrNull }
                                        ?.joinToString("\n...\n").orEmpty()
                                    o to n
                                }
                                else -> {
                                    "" to parsedObj?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                                }
                            }
                        }
                        SepiaFileDiffCard(
                            filePath = pathStr,
                            actionLabel = when (lowerTool) {
                                "fs_edit" -> Strings.edited
                                "fs_multi_edit" -> Strings.multiEdited
                                else -> Strings.wrote
                            },
                            oldText = oldTxt,
                            newText = newTxt,
                            resultSummary = tc.resultText,
                            isError = tc.isError
                        )
                        return
                    }

                    var isToolResultExpanded by remember(tc.id) { mutableStateOf(false) }
                    val effectiveExpanded = isToolResultExpanded || tc.isRunning
                    val canExpandTool = tc.resultText.length > 160 || tc.argumentsJson.length > 80
                    val toolIcon = remember(tc.name) { resolveToolKissIcon(tc.name) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.r8))
                            .background(p.cardBg)
                            .border(
                                width = if (tc.isRunning) 1.dp else LineWidth.hairline,
                                color = when {
                                    tc.isError -> p.danger.copy(alpha = 0.7f)
                                    tc.isRunning -> p.accent
                                    else -> p.border.copy(alpha = 0.75f)
                                },
                                shape = RoundedCornerShape(Radius.r8)
                            )
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (canExpandTool) Modifier.clickable(role = Role.Button, onClickLabel = if (isToolResultExpanded) Strings.collapseToolResult else Strings.expandToolResult) { isToolResultExpanded = !isToolResultExpanded } else Modifier),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = Space.s6),
                                horizontalArrangement = Arrangement.spacedBy(Space.s6),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (tc.isRunning) {
                                    KissStreamingPulseDot(color = p.accent)
                                } else {
                                    KissVectorIcon(
                                        icon = toolIcon,
                                        tint = if (tc.isError) p.danger else p.accentSecondary,
                                        size = 13.dp
                                    )
                                }
                                BasicText(
                                    text = "${tc.name}(${tc.argumentsJson})",
                                    maxLines = if (effectiveExpanded) 8 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.monoStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                if (tc.isRunning) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(Radius.r4))
                                            .background(p.accent.copy(alpha = 0.16f))
                                            .padding(horizontal = Space.s6, vertical = Space.s2)
                                    ) {
                                        BasicText(
                                            text = Strings.running,
                                            style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                        )
                                    }
                                }
                                if (canExpandTool) {
                                    SepiaButton(
                                        text = if (effectiveExpanded) "▴" else "▾",
                                        contentDescription = if (effectiveExpanded) Strings.collapseToolResult else Strings.expandToolResult,
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = { isToolResultExpanded = !isToolResultExpanded }
                                    )
                                }
                            }
                        }
                        if ((preferences.showToolResultSummary || tc.isRunning) && tc.resultText.isNotBlank()) {
                            val shownResult = if (effectiveExpanded) tc.resultText.take(6000) else tc.resultText.take(360)
                            BasicText(
                                text = if (!effectiveExpanded && tc.resultText.length > 360) "$shownResult…" else shownResult,
                                style = p.monoStyle.copy(
                                    color = when {
                                        tc.isError -> p.danger
                                        tc.isRunning -> p.primaryInk
                                        else -> p.narrationInk
                                    },
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }

                if (hasInterleavedSteps) {
                    // Thinking and tool steps in the order they happened. Long runs show a window of steps so
                    // dozens of text renderers are never composed at once.
                    val totalToolSteps = activeVar.toolCalls.size
                    var showAllInterleavedSteps by remember(node.id, node.activeVariantIndex) { mutableStateOf(false) }
                    val maxVisibleTailSteps = if (isActivelyThinkingThisNode) 12 else 24
                    val hiddenStepCount = if (!showAllInterleavedSteps && totalToolSteps > maxVisibleTailSteps) {
                        totalToolSteps - maxVisibleTailSteps
                    } else 0
                    if (hiddenStepCount > 0) {
                        SepiaButton(
                            text = Strings.showEarlierToolStepsTotal(hiddenStepCount, totalToolSteps),
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = { showAllInterleavedSteps = true }
                        )
                    }
                    val visibleTools = if (hiddenStepCount > 0) {
                        activeVar.toolCalls.subList(hiddenStepCount, totalToolSteps)
                    } else {
                        activeVar.toolCalls
                    }
                    var thoughtStepCounter = hiddenStepCount
                    visibleTools.forEachIndexed { visibleIndex, tc ->
                        key(tc.id) {
                        if (preferences.showThinkingCards && tc.precedingThinking.isNotBlank()) {
                            thoughtStepCounter++
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Space.s2),
                                verticalArrangement = Arrangement.spacedBy(Space.s4)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    KissVectorIcon(icon = KissIcon.SPARKLES, tint = p.accent, size = 11.dp)
                                    BasicText(
                                        text = Strings.thought(thoughtStepCounter),
                                        style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                    )
                                }
                                LiteraryProseRenderer(
                                    text = tc.precedingThinking,
                                    compactReasoningMode = true,
                                    enableMarkdown = preferences.renderReasoningWithMarkdown,
                                    enableMath = preferences.mathFormulaRendering,
                                    enableInlineDollarMath = preferences.inlineDollarMathRendering,
                                    autoCollapseCodeBlocks = preferences.autoCollapseCodeBlocks
                                )
                            }
                        }
                        val stepText = turnText.beforeTool.getOrElse(hiddenStepCount + visibleIndex) { "" }.trim()
                        if (stepText.isNotEmpty()) {
                            LiteraryProseRenderer(
                                text = stepText,
                                enableMarkdown = enableMd,
                                enableMath = preferences.mathFormulaRendering,
                                enableInlineDollarMath = preferences.inlineDollarMathRendering,
                                autoCollapseCodeBlocks = preferences.autoCollapseCodeBlocks
                            )
                        }
                        if (preferences.showToolCards) {
                            RenderToolExecutionCard(tc)
                        }
                        }
                    }

                    if (preferences.showThinkingCards && activeVar.trailingThinking.isNotBlank()) {
                        thoughtStepCounter++
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Space.s2),
                            verticalArrangement = Arrangement.spacedBy(Space.s4)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                KissVectorIcon(icon = KissIcon.SPARKLES, tint = p.accent, size = 11.dp)
                                BasicText(
                                    text = Strings.thought(thoughtStepCounter),
                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                )
                            }
                            LiteraryProseRenderer(
                                text = activeVar.trailingThinking,
                                compactReasoningMode = true,
                                enableMarkdown = preferences.renderReasoningWithMarkdown,
                                enableMath = preferences.mathFormulaRendering,
                                enableInlineDollarMath = preferences.inlineDollarMathRendering,
                                autoCollapseCodeBlocks = preferences.autoCollapseCodeBlocks
                            )
                        }
                    }
                } else {
                    // Single-step thinking or legacy turn format
                    if (preferences.showThinkingCards && activeVar.thinkingSummary.isNotBlank()) {
                        LiteraryProseRenderer(
                            text = activeVar.thinkingSummary,
                            compactReasoningMode = true,
                            enableMarkdown = preferences.renderReasoningWithMarkdown,
                            enableMath = preferences.mathFormulaRendering,
                            enableInlineDollarMath = preferences.inlineDollarMathRendering,
                            autoCollapseCodeBlocks = preferences.autoCollapseCodeBlocks
                        )
                    }
                    if (preferences.showToolCards && activeVar.toolCalls.isNotEmpty()) {
                        activeVar.toolCalls.forEach { tc ->
                            RenderToolExecutionCard(tc)
                        }
                    }
                }
                }

                if (!isLivePeekOnly && activeVar.thinkingSignature.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        KissVectorIcon(
                            icon = KissIcon.SHIELD_CHECK,
                            tint = p.accent,
                            size = 12.dp
                        )
                        BasicText(
                            text = Strings.thinkingSavedForTheNext,
                            style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFamily = p.codeFontFamily)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Space.s8))

        // Live Autonomous Goal & Todo Checklist Card
        val goalTracker = activeVar.goalTracker
        if (goalTracker != null && (goalTracker.goalObjective.isNotBlank() || goalTracker.todos.isNotEmpty())) {
            val doneCount = goalTracker.completedCount
            val totalCount = goalTracker.todos.size
            val statusIcon = when {
                goalTracker.isCompleted -> KissIcon.CHECK
                totalCount > 0 -> KissIcon.REFRESH
                else -> KissIcon.TARGET
            }
            val statusText = when {
                goalTracker.isCompleted -> Strings.doneOf(doneCount, totalCount)
                totalCount > 0 -> "$doneCount of $totalCount done"
                else -> Strings.planning
            }
            Column(
                modifier = Modifier
                    .padding(bottom = Space.s8)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.r8))
                    .background(p.sheetBg.copy(alpha = 0.78f))
                    .border(
                        width = LineWidth.hairline,
                        color = if (goalTracker.isCompleted) p.accentSecondary else p.accent,
                        shape = RoundedCornerShape(Radius.r8)
                    )
                    .padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s6)
            ) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(Space.s4)
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s6)
                    ) {
                        KissVectorIcon(icon = KissIcon.TARGET, tint = p.accent, size = IconSize.md)
                        BasicText(
                            text = Strings.goal(goalTracker.goalObjective.ifBlank { Strings.autonomousTaskChecklist }),
                            style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s4)
                    ) {
                        KissVectorIcon(
                            icon = statusIcon,
                            tint = if (goalTracker.isCompleted) p.accentSecondary else p.accent,
                            size = 12.dp
                        )
                        BasicText(
                            text = statusText,
                            style = p.uiLabelStyle.copy(
                                color = if (goalTracker.isCompleted) p.accentSecondary else p.accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
                if (goalTracker.todos.isNotEmpty()) {
                    SepiaDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                        goalTracker.todos.forEach { item ->
                            val st = item.status.lowercase()
                            val rowIcon = when (st) {
                                "completed" -> KissIcon.CHECK
                                "in_progress" -> KissIcon.REFRESH
                                "cancelled" -> KissIcon.MINUS
                                else -> KissIcon.CIRCLE_EMPTY
                            }
                            val itemColor = when (st) {
                                "completed" -> p.mutedInk
                                "in_progress" -> p.accent
                                else -> p.primaryInk
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.s6)
                            ) {
                                KissVectorIcon(icon = rowIcon, tint = itemColor, size = IconSize.sm)
                                BasicText(
                                    text = "[${item.id}] ${item.content}",
                                    style = p.uiBodyStyle.copy(
                                        fontSize = 13.sp,
                                        color = itemColor,
                                        fontWeight = if (st == "in_progress") FontWeight.SemiBold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                    }
                }
                if (goalTracker.verificationSummary.isNotBlank()) {
                    SepiaDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        KissVectorIcon(icon = KissIcon.CHECK, tint = p.accent, size = 12.dp)
                        BasicText(
                            text = Strings.verified(goalTracker.verificationSummary),
                            style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Medium)
                        )
                    }
                }
            }
        }

        // Main Prose Content OR In-Place Editor
        val shrinkBubble = !isEditing && !activeVar.isStreaming && activeVar.attachments.isEmpty() &&
            activeVar.mediaItems.isEmpty() && activeVar.content.length < MAX_SHRINKABLE_BUBBLE_CHARS
        val contentModifier = when {
            showBubbles -> Modifier.messageBubble(fromUser = !isAssistant, shrink = shrinkBubble, fill = if (isAssistant) p.cardBg else p.accent.copy(alpha = 0.16f), edge = if (isAssistant) p.border else Color.Transparent)
            isAssistant -> Modifier
            else -> Modifier.rubricMark(p.accent).padding(start = 14.dp, top = Space.s2, bottom = Space.s2)
        }
        Column(contentModifier) {
        if (isEditing) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                SepiaTextField(
                    value = editBuffer,
                    onValueChange = { editBuffer = it },
                    singleLine = false,
                    minHeight = 120.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.s8),
                    verticalArrangement = Arrangement.spacedBy(Space.s6)
                ) {
                    if (!isAssistant) {
                        SepiaButton(
                            text = Strings.saveRegenerate,
                            compact = true,
                            style = SepiaButtonStyle.PRIMARY,
                            onClick = {
                                val asBranch = !preferences.deletePreviousMessageWhenRegenerating
                                onSaveEditedText(editBuffer, asBranch, true)
                                isEditing = false
                            }
                        )
                    }
                    SepiaButton(
                        text = Strings.save,
                        compact = true,
                        style = if (isAssistant) SepiaButtonStyle.PRIMARY else SepiaButtonStyle.OUTLINE,
                        onClick = {
                            onSaveEditedText(editBuffer, false, false)
                            isEditing = false
                        }
                    )
                    SepiaButton(
                        text = Strings.saveAsNewVersion,
                        compact = true,
                        style = SepiaButtonStyle.SECONDARY,
                        onClick = {
                            onSaveEditedText(editBuffer, true, false)
                            isEditing = false
                        }
                    )
                    SepiaButton(
                        text = Strings.cancel,
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            editBuffer = activeVar.content
                            isEditing = false
                        }
                    )
                }
            }
        } else {
            val hasNonEmptyProseOrIdle = replyText.isNotBlank() ||
                (!isActivelyThinkingThisNode && activeVar.mediaItems.isEmpty() && activeVar.attachments.isEmpty())
            if (hasNonEmptyProseOrIdle) {
                val isLongCollapsible = preferences.collapseLongMessages && !activeVar.isStreaming && replyText.length > 1800
                var isLongExpanded by remember(node.id, node.activeVariantIndex) { mutableStateOf(false) }
                val displayedProse = if (isLongCollapsible && !isLongExpanded) {
                    replyText.take(1400) + "…"
                } else {
                    replyText.ifBlank { if (activeVar.isStreaming && !turnText.hasSteps) "…" else "" }
                }
                LiteraryProseRenderer(
                    text = displayedProse,
                    enableMarkdown = enableMd,
                    enableMath = preferences.mathFormulaRendering,
                    enableInlineDollarMath = preferences.inlineDollarMathRendering,
                    autoCollapseCodeBlocks = preferences.autoCollapseCodeBlocks,
                    highlightedLineSubstring = highlightedSourceLineText
                )
                if (isLongCollapsible) {
                    Spacer(Modifier.height(Space.s4))
                    SepiaButton(
                        text = if (isLongExpanded) Strings.collapseLongMessage else Strings.readFullMessageChars(activeVar.content.length),
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { isLongExpanded = !isLongExpanded }
                    )
                }
            }
        }

        // Pasted / Attached Images, Videos, PDFs, Audio, & Text/Code Files in Chat Turns
        if (preferences.showFilesBelowReplies && activeVar.attachments.isNotEmpty()) {
            Spacer(Modifier.height(Space.s8))
            val imageAtts = activeVar.attachments.filter {
                val m = it.mimeType.lowercase()
                val l = it.fileName.lowercase()
                m.startsWith("image/") || l.endsWith(".png") || l.endsWith(".jpg") ||
                    l.endsWith(".jpeg") || l.endsWith(".webp") || l.endsWith(".gif") || l.endsWith(".bmp")
            }
            val otherAtts = activeVar.attachments.filterNot { it in imageAtts }

            Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                if (imageAtts.isNotEmpty()) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.s10),
                        verticalArrangement = Arrangement.spacedBy(Space.s10)
                    ) {
                        imageAtts.forEach { att ->
                            val bmp = rememberFileImage(att.localFilePath).bitmap
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 360.dp)
                                    .clip(RoundedCornerShape(Radius.r10))
                                    .background(p.cardBg)
                                    .border(LineWidth.hairline, p.border, RoundedCornerShape(Radius.r10))
                                    .clickable(role = Role.Button, onClickLabel = Strings.openAttachment) { onInspectAttachment(att) }
                                    .padding(Space.s8),
                                verticalArrangement = Arrangement.spacedBy(Space.s6)
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = att.fileName,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .heightIn(max = 260.dp)
                                            .clip(RoundedCornerShape(Radius.r6))
                                    )
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.IMAGE, tint = p.accent, size = 14.dp)
                                        BasicText(
                                            text = att.fileName,
                                            style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s8)
                                ) {
                                    BasicText(
                                        text = att.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiLabelStyle,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.SEARCH, tint = p.accent, size = IconSize.sm)
                                        BasicText(
                                            text = Strings.zoom,
                                            style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (otherAtts.isNotEmpty()) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.s8),
                        verticalArrangement = Arrangement.spacedBy(Space.s8)
                    ) {
                        otherAtts.forEach { att ->
                            val mime = att.mimeType.lowercase()
                            val lower = att.fileName.lowercase()
                            val isPdf = mime == "application/pdf" || lower.endsWith(".pdf")
                            val isVid = mime.startsWith("video/") || lower.endsWith(".mp4") ||
                                lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".mkv")
                            val isAud = mime.startsWith("audio/") || lower.endsWith(".mp3") ||
                                lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".flac")
                            val iconAndAction = when {
                                isPdf -> KissIcon.FILE_TEXT to Strings.openPdf
                                isVid -> KissIcon.VIDEO to Strings.playVideo
                                isAud -> KissIcon.AUDIO to Strings.playAudio
                                else -> {
                                    val stats = if (att.textContent.isNotBlank()) {
                                        "${att.textContent.lines().size} lines"
                                    } else Strings.file
                                    KissIcon.FILE_TEXT to Strings.view(stats)
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.r8))
                                    .background(p.cardBg)
                                    .border(LineWidth.hairline, p.border, RoundedCornerShape(Radius.r8))
                                    .clickable(role = Role.Button, onClickLabel = Strings.openAttachment) { onInspectAttachment(att) }
                                    .padding(horizontal = Space.s10, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                KissVectorIcon(icon = iconAndAction.first, tint = p.accent, size = 14.dp)
                                BasicText(
                                    text = att.fileName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiBodyStyle.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
                                    modifier = Modifier.widthIn(max = 220.dp)
                                )
                                BasicText(
                                    text = "• ${iconAndAction.second}",
                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Inline Generated Images & Videos
        if (activeVar.mediaItems.isNotEmpty()) {
            Spacer(Modifier.height(Space.s10))
            Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                activeVar.mediaItems.forEach { media ->
                    when (media.kind) {
                        MediaKind.IMAGE -> {
                            val bmp = rememberFileImage(media.localFilePath).bitmap
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.r10))
                                    .background(p.cardBg)
                                    .border(LineWidth.hairline, p.border, RoundedCornerShape(Radius.r10))
                                    .padding(Space.s10),
                                verticalArrangement = Arrangement.spacedBy(Space.s8)
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = media.revisedPrompt.ifBlank { "Generated Image" },
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 520.dp)
                                            .clip(RoundedCornerShape(Radius.r6))
                                            .clickable(role = Role.Button) {
                                                onInspectGeneratedImage(media)
                                            }
                                    )
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.IMAGE, tint = p.accent, size = IconSize.md)
                                        BasicText(
                                            text = Strings.generatedImage(media.localFilePath.ifBlank { media.remoteUrl }),
                                            style = p.uiBodyStyle.copy(color = p.accent)
                                        )
                                    }
                                }
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalArrangement = Arrangement.spacedBy(Space.s6)
                                ) {
                                    BasicText(
                                        text = "${media.mimeType}${if (media.aspectRatio.isNotBlank()) " • ${media.aspectRatio}" else ""}",
                                        style = p.uiLabelStyle,
                                        modifier = Modifier.padding(top = Space.s4)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
                                        if (bmp != null) {
                                            SepiaButton(
                                                text = Strings.viewZoom,
                                                compact = true,
                                                style = SepiaButtonStyle.PRIMARY,
                                                onClick = { onInspectGeneratedImage(media) }
                                            )
                                        }
                                        val openTarget = media.localFilePath.ifBlank { media.remoteUrl }
                                        if (openTarget.isNotBlank()) {
                                            SepiaButton(
                                                text = Strings.openInOs,
                                                compact = true,
                                                style = SepiaButtonStyle.OUTLINE,
                                                onClick = { PlatformBridge.openOrPlayMedia(openTarget) }
                                            )
                                        }
                                        if (media.localFilePath.isNotBlank()) {
                                            SepiaButton(
                                                text = Strings.copyImage,
                                                compact = true,
                                                style = SepiaButtonStyle.OUTLINE,
                                                onClick = {
                                                    val bytes = PlatformBridge.readBinaryFileBytes(media.localFilePath)
                                                    if (bytes != null) {
                                                        PlatformBridge.copyImageToClipboard(bytes)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        MediaKind.VIDEO -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.r10))
                                    .background(p.cardBg)
                                    .border(LineWidth.hairline, p.accentSecondary, RoundedCornerShape(Radius.r10))
                                    .padding(Space.s12),
                                verticalArrangement = Arrangement.spacedBy(Space.s8)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                ) {
                                    KissVectorIcon(icon = KissIcon.VIDEO, tint = p.accent, size = 15.dp)
                                    BasicText(
                                        text = Strings.generatedVideo(media.mimeType, if (media.aspectRatio.isNotBlank()) " • ${media.aspectRatio}" else ""),
                                        style = p.uiHeaderStyle.copy(fontSize = 15.sp, color = p.accent)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                                    val playTarget = media.localFilePath.ifBlank { media.remoteUrl }
                                    if (playTarget.isNotBlank()) {
                                        SepiaButton(
                                            text = Strings.playVideoMp4,
                                            compact = true,
                                            style = SepiaButtonStyle.PRIMARY,
                                            onClick = { PlatformBridge.openOrPlayMedia(playTarget) }
                                        )
                                    }
                                }
                            }
                        }

                        MediaKind.AUDIO -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.r10))
                                    .background(p.cardBg)
                                    .border(LineWidth.hairline, p.accentSecondary, RoundedCornerShape(Radius.r10))
                                    .padding(Space.s12),
                                verticalArrangement = Arrangement.spacedBy(Space.s8)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                ) {
                                    KissVectorIcon(icon = KissIcon.AUDIO, tint = p.accent, size = 15.dp)
                                    BasicText(
                                        text = Strings.generatedAudio(media.mimeType),
                                        style = p.uiHeaderStyle.copy(fontSize = 15.sp, color = p.accent)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                                    val playTarget = media.localFilePath.ifBlank { media.remoteUrl }
                                    if (playTarget.isNotBlank()) {
                                        SepiaButton(
                                            text = Strings.playAudioMp3,
                                            compact = true,
                                            style = SepiaButtonStyle.PRIMARY,
                                            onClick = { PlatformBridge.openOrPlayMedia(playTarget) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        }

        Spacer(Modifier.height(Space.s6))

        // Footer: actions on the first row, token counts on the second, so More never squeezes the buttons
        // on a narrow phone.
        if (isAssistant || preferences.showUserMessageActions) {
            Column(
                modifier = Modifier.fillMaxWidth().alpha(footerAlpha).onFocusChanged { footerHasFocus = it.hasFocus },
                verticalArrangement = Arrangement.spacedBy(Space.s4)
            ) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isSentBubble) Arrangement.spacedBy(4.dp, Alignment.End) else Arrangement.spacedBy(Space.s4),
                    verticalArrangement = Arrangement.spacedBy(Space.s4)
                ) {
                    var isSpeakingThisTurn by remember(node.id, node.activeVariantIndex) { mutableStateOf(false) }

                    // On touch only the newest turns show every action; older ones open from the More button.
                    if (!win.isCompact || isLatestTurn || showMoreActions) {
                    SepiaButton(
                        text = if (copiedBadge == "txt") "✓" else "📋",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            clipboard.setText(AnnotatedString(stripMarkdownToPlainText(activeVar.content)))
                            copiedBadge = "txt"
                        }
                    )
                    if (activeVar.content.isNotBlank()) {
                        SepiaButton(
                            text = if (isSpeakingThisTurn) "🔇" else "🔊",
                            contentDescription = if (isSpeakingThisTurn) Strings.stopReadingAloud else Strings.readAloud,
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                if (isSpeakingThisTurn) {
                                    PlatformBridge.stopSpeakingAloud()
                                    isSpeakingThisTurn = false
                                } else {
                                    isSpeakingThisTurn = true
                                    PlatformBridge.speakTextAloud(
                                        text = stripMarkdownToPlainText(activeVar.content),
                                        onFinished = { isSpeakingThisTurn = false }
                                    )
                                }
                            }
                        )
                    }
                    SepiaButton(
                        text = "↻",
                        contentDescription = Strings.regenerateResponse,
                        compact = true,
                        enabled = !activeVar.isStreaming,
                        style = SepiaButtonStyle.GHOST,
                        onClick = onRegenerate
                    )
                    }
                    // A pinned reply always shows its pin, so the state is visible even where other actions are folded away.
                    if (isAssistant && (!win.isCompact || isLatestTurn || showMoreActions || node.pinned)) {
                        SepiaButton(
                            text = "📌",
                            contentDescription = if (node.pinned) Strings.unpinReply else Strings.pinReply,
                            hint = if (node.pinned) Strings.removeFromPinnedInTheSidebar else Strings.keepThisReplyInPinnedIn,
                            compact = true,
                            style = if (node.pinned) SepiaButtonStyle.OUTLINE else SepiaButtonStyle.GHOST,
                            onClick = onTogglePin
                        )
                    }
                    SepiaButton(
                        text = if (showMoreActions) "▴" else "⋯",
                        contentDescription = if (showMoreActions) Strings.hideMoreActions else Strings.moreActions,
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { showMoreActions = !showMoreActions }
                    )
                    if (isAssistant && activeVar.webSources.isNotEmpty()) {
                        val srcCount = activeVar.webSources.size
                        val srcLabel = if (srcCount == 1) "🌐 1 Source" else "🌐 $srcCount Sources"
                        val chevron = if (showSourcesDrawer) " ▴" else " ▾"
                        SepiaChip(
                            label = "$srcLabel$chevron",
                            selected = showSourcesDrawer,
                            onClick = {
                                showSourcesDrawer = !showSourcesDrawer
                                if (!showSourcesDrawer) highlightedSourceLineText = ""
                            }
                        )
                    }

                    if (showMoreActions) {
                        SepiaButton(
                            text = if (copiedBadge == "md") "✓ Markdown" else "📋 Markdown",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                clipboard.setText(AnnotatedString(activeVar.content))
                                copiedBadge = "md"
                            }
                        )
                        if (isAssistant) {
                            SepiaButton(
                                text = Strings.continueLabel2,
                                compact = true,
                                enabled = !activeVar.isStreaming,
                                style = SepiaButtonStyle.GHOST,
                                onClick = onContinue
                            )
                        }
                        SepiaButton(
                            text = "✎",
                            contentDescription = Strings.editMessage,
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                editBuffer = activeVar.content
                                isEditing = !isEditing
                            }
                        )
                        if (activeVar.content.isNotBlank()) {
                            SepiaButton(
                                text = "🌐",
                                contentDescription = Strings.translateMessage,
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = onTranslate
                            )
                        }
                        SepiaButton(
                            text = Strings.branch,
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onForkHere
                        )
                        SepiaButton(
                            text = Strings.rewind,
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onRewindHere
                        )
                        SepiaButton(
                            text = if (node.includedInContext) "🙈" else "👁",
                            contentDescription = if (node.includedInContext) Strings.excludeFromContext else Strings.includeInContext,
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onToggleContextMute
                        )
                        if (activeVar.rawRequestPreview.isNotBlank()) {
                            SepiaButton(
                                text = "‹/›",
                                contentDescription = Strings.rawRequestAndResponse,
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = { showRawWireDrawer = !showRawWireDrawer }
                            )
                        }
                        if (node.variants.size > 1) {
                            SepiaButton(
                                text = "🗑 ${node.activeVariantIndex + 1}/${node.variants.size}",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = onDeleteCurrentVariant
                            )
                            SepiaButton(
                                text = "🗑",
                                contentDescription = Strings.deleteMessage,
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = onDeleteNode
                            )
                        } else {
                            SepiaButton(
                                text = "🗑",
                                contentDescription = Strings.deleteMessage,
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = onDeleteNode
                            )
                        }
                    }
                }

                // Token counts for a reply, with written text counted apart from tool payloads.
                val m = activeVar.tokenMetrics
                if (isAssistant && preferences.showTokenAndContextStats && (!win.isCompact || showMoreActions) &&
                    (activeVar.content.isNotBlank() || activeVar.toolCalls.isNotEmpty() || m.outputTokens > 0)
                ) {
                    val proseTokenKey = if (activeVar.isStreaming) activeVar.content.length / 64 else activeVar.content.hashCode()
                    val outProseTok = remember(proseTokenKey, activeVar.isStreaming, activeVar.attachments, m.outputTokens) {
                        val counted = O200kTokenizer.countMessageVariantTokens(
                            role = MessageRole.ASSISTANT,
                            variant = activeVar,
                            includeReasoningAndTools = false
                        )
                        if (activeVar.content.isNotBlank()) counted.toLong() else m.outputTokens
                    }
                    val toolPayloadTok = remember(activeVar.toolCalls, activeVar.isStreaming) {
                        activeVar.toolCalls.sumOf { tc ->
                            val tcLive = activeVar.isStreaming || tc.isRunning
                            6 + O200kTokenizer.countTokens(tc.name) +
                                (if (tcLive) O200kTokenizer.estimateTokensFast(tc.argumentsJson) else O200kTokenizer.countTokens(tc.argumentsJson)) +
                                (if (tcLive) O200kTokenizer.estimateTokensFast(tc.resultText) else O200kTokenizer.countTokens(tc.resultText))
                        }
                    }
                    val thinkTokenKey = if (activeVar.isStreaming) activeVar.thinkingSummary.length / 64 else activeVar.thinkingSummary.hashCode()
                    val thinkTok = remember(thinkTokenKey, activeVar.isStreaming, m.reasoningTokens) {
                        if (activeVar.thinkingSummary.isNotBlank()) {
                            if (activeVar.isStreaming) {
                                O200kTokenizer.estimateTokensFast(activeVar.thinkingSummary).toLong()
                            } else {
                                O200kTokenizer.countTokens(activeVar.thinkingSummary).toLong()
                            }
                        } else {
                            m.reasoningTokens
                        }
                    }
                    val costVal = if (m.reportedRequests > 0 || m.estimatedCostUsd > 0.0) {
                        m.estimatedCostUsd
                    } else {
                        0.0
                    }
                    val hasReportedRequests = m.reportedRequests > 0
                    val costUnavailable = hasReportedRequests && !m.costIsAvailable && costVal == 0.0
                    val costIsKnown = costVal > 0.0 || (hasReportedRequests && m.costIsAvailable)
                    val isPartial = hasReportedRequests && (!m.usageIsComplete || !m.costIsAvailable || m.unpricedCostComponents.isNotEmpty())
                    val isReportedExact = hasReportedRequests && m.reportedCostRequests == m.reportedRequests &&
                        m.costIsAvailable && m.usageIsComplete && m.unpricedCostComponents.isEmpty()
                    val hasWholeTurnCounts = m.inputTokens > 0L || m.outputTokens > 0L ||
                        m.cachedInputTokens > 0L || m.cacheCreationInputTokens > 0L
                    val showCostUnavailableBadge = costUnavailable && !activeVar.isStreaming
                    val usageDetailLines = remember(
                        costUnavailable, hasWholeTurnCounts, hasReportedRequests,
                        m.inputTokens, m.outputTokens, m.cachedInputTokens, m.cacheCreationInputTokens,
                        m.reportedRequests, m.usageIsComplete, m.costIsAvailable, m.reportedCostRequests,
                        costVal, m.outputIncludesUnreportedReasoning, m.unpricedCostComponents
                    ) {
                        buildList {
                            if (costUnavailable) add(Strings.costUnavailable)
                            if (hasWholeTurnCounts || hasReportedRequests) {
                                add(Strings.inputOutput(formatCompactTokens(m.inputTokens), formatCompactTokens(m.outputTokens)))
                            }
                            if (m.cachedInputTokens > 0L || m.cacheCreationInputTokens > 0L) {
                                add(Strings.reusedSavedForReuse(formatCompactTokens(m.cachedInputTokens), formatCompactTokens(m.cacheCreationInputTokens)))
                            }
                            if (hasReportedRequests) {
                                val status = when {
                                    !m.usageIsComplete || !m.costIsAvailable -> Strings.usagePartialSuffix
                                    m.reportedCostRequests == m.reportedRequests -> Strings.usageReportedCostSuffix
                                    else -> Strings.usageEstimateSuffix
                                }
                                add(Strings.request(m.reportedRequests, if (m.reportedRequests == 1) "" else "s") + status)
                            } else if (costVal > 0.0) {
                                add(Strings.estimatedCost)
                            }
                            if (m.outputIncludesUnreportedReasoning) add(Strings.thinkingCountedInOutput)
                            if (m.unpricedCostComponents.isNotEmpty()) {
                                add(Strings.unpriced(m.unpricedCostComponents.sorted().joinToString(", ")))
                            }
                        }
                    }

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = Space.s2),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(Space.s4),
                        itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pill 1: output tokens, with reasoning and tool payload tokens shown separately.
                        Box(
                            modifier = Modifier
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.s4)
                            ) {
                                BasicText(
                                    text = Strings.tokens(formatCompactTokens(outProseTok)),
                                    style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.narrationInk, fontWeight = FontWeight.Medium)
                                )
                                if (thinkTok > 0) {
                                    BasicText(
                                        text = "•",
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk)
                                    )
                                    KissVectorIcon(
                                        icon = KissIcon.BRAIN,
                                        tint = p.accent,
                                        size = IconSize.xs
                                    )
                                    BasicText(
                                        text = formatCompactTokens(thinkTok),
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.narrationInk, fontWeight = FontWeight.Medium)
                                    )
                                }
                                if (toolPayloadTok > 0) {
                                    BasicText(
                                        text = "•",
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk)
                                    )
                                    KissVectorIcon(
                                        icon = KissIcon.WRENCH,
                                        tint = p.accentSecondary,
                                        size = 10.dp
                                    )
                                    BasicText(
                                        text = formatCompactTokens(toolPayloadTok),
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk, fontWeight = FontWeight.Medium)
                                    )
                                }
                            }
                        }

                        // Pill 2: Speed & Duration
                        if (m.tokensPerSec > 0.0 || m.durationMs > 0L) {
                            val durationSuffix = buildString {
                                if (m.durationMs > 0L) {
                                    if (m.tokensPerSec > 0.0) append(" • ")
                                    append(formatDurationFromMs(m.durationMs))
                                }
                                if ((showMoreActions || preferences.showTokensForEntireTurn) && m.ttftMs > 0L) {
                                    append(" (TTFT ${formatDurationFromMs(m.ttftMs)})")
                                }
                            }
                            Box(
                                modifier = Modifier
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                ) {
                                    if (m.tokensPerSec > 0.0) {
                                        KissVectorIcon(
                                            icon = KissIcon.ZAP,
                                            tint = p.accentSecondary,
                                            size = 10.dp
                                        )
                                        BasicText(
                                            text = Strings.tokS(m.tokensPerSec, durationSuffix),
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk)
                                        )
                                    } else {
                                        BasicText(
                                            text = durationSuffix,
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk)
                                        )
                                    }
                                }
                            }
                        }

                        // Pill 3: Cost / Unpriced Warning Badge (full breakdown on hover or tap)
                        if (costIsKnown || showCostUnavailableBadge) {
                            UsageDetailsPill(
                                details = usageDetailLines,
                                isOpen = openUsageDetailsId == "cost",
                                onToggle = { openUsageDetailsId = if (openUsageDetailsId == "cost") null else "cost" },
                                onDismiss = { if (openUsageDetailsId == "cost") openUsageDetailsId = null },
                                accessibilitySummary = if (!costIsKnown) usageDetailLines.joinToString(", ") else null
                            ) { highlighted ->
                                if (costIsKnown) {
                                    BasicText(
                                        text = (if (isReportedExact) "" else "~") + formatUsdCost(costVal),
                                        style = p.uiLabelStyle.copy(
                                            fontSize = 11.sp,
                                            fontFeatureSettings = "tnum",
                                            color = if (highlighted) p.accent else p.narrationInk,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                                if (showCostUnavailableBadge || (isPartial && !activeVar.isStreaming)) {
                                    KissVectorIcon(
                                        icon = KissIcon.WARNING,
                                        tint = if (highlighted) p.accent else p.accentSecondary,
                                        size = IconSize.sm
                                    )
                                }
                            }
                        }

                        // Pill 4: Compact single-line input/output/cache summary when expanded
                        if ((showMoreActions || preferences.showTokensForEntireTurn) && hasWholeTurnCounts) {
                            UsageDetailsPill(
                                details = usageDetailLines,
                                isOpen = openUsageDetailsId == "turn",
                                onToggle = { openUsageDetailsId = if (openUsageDetailsId == "turn") null else "turn" },
                                onDismiss = { if (openUsageDetailsId == "turn") openUsageDetailsId = null }
                            ) { highlighted ->
                                val tint = if (highlighted) p.accent else p.mutedInk
                                val textColor = if (highlighted) p.accent else p.mutedInk
                                KissVectorIcon(icon = KissIcon.ARROW_UP, tint = tint, size = IconSize.xs)
                                BasicText(
                                    text = formatCompactTokens(m.inputTokens),
                                    style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = textColor)
                                )
                                BasicText(
                                    text = "•",
                                    style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                )
                                KissVectorIcon(icon = KissIcon.ARROW_DOWN, tint = tint, size = IconSize.xs)
                                BasicText(
                                    text = formatCompactTokens(m.outputTokens),
                                    style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = textColor)
                                )
                                if (m.cachedInputTokens > 0L) {
                                    BasicText(
                                        text = "•",
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                    )
                                    KissVectorIcon(
                                        icon = KissIcon.REFRESH,
                                        tint = if (highlighted) p.accent else p.accentSecondary,
                                        size = IconSize.xs
                                    )
                                    BasicText(
                                        text = formatCompactTokens(m.cachedInputTokens),
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = textColor)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Clean User Message Token & Price Pill Row (Shown below every User message when token stats are enabled)
        if (!isAssistant && !win.isCompact && preferences.showTokenAndContextStats &&
            (activeVar.content.isNotBlank() || activeVar.attachments.isNotEmpty())
        ) {
            val userTok = remember(activeVar.content, activeVar.attachments) {
                O200kTokenizer.countMessageVariantTokens(MessageRole.USER, activeVar)
            }
            val userCost = (userTok / 1_000_000.0) * inputPricePerM
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = Space.s4).alpha(footerAlpha),
                horizontalArrangement = if (isSentBubble) Arrangement.spacedBy(5.dp, Alignment.End) else Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s4)
            ) {
                val attSuffix = if (activeVar.attachments.isNotEmpty()) {
                    " • ${activeVar.attachments.size} file${if (activeVar.attachments.size > 1) "s" else ""}"
                } else ""
                Box(
                    modifier = Modifier
                ) {
                    BasicText(
                        text = Strings.tok(formatCompactTokens(userTok), attSuffix),
                        style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk, fontWeight = FontWeight.Medium)
                    )
                }
                if (userCost > 0.0) {
                    Box(
                        modifier = Modifier
                    ) {
                        BasicText(
                            text = formatUsdCost(userCost),
                            style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.narrationInk, fontWeight = FontWeight.Medium)
                        )
                    }
                }
            }
        }

        // Web sources, opened from the Sources pill in the footer; each source shows the lines it supports.
        AnimatedVisibility(visible = isAssistant && showSourcesDrawer && activeVar.webSources.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .padding(top = Space.s8)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.r10))
                    .background(p.sheetBg.copy(alpha = 0.85f))
                    .border(LineWidth.hairline, p.accent.copy(alpha = 0.65f), RoundedCornerShape(Radius.r10))
                    .padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s8)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s6),
                        modifier = Modifier.weight(1f)
                    ) {
                        KissVectorIcon(icon = KissIcon.GLOBE, tint = p.accent, size = IconSize.md)
                        BasicText(
                            text = Strings.groundedWebSourcesTapASource(activeVar.webSources.size),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        )
                    }
                    SepiaButton(
                        text = "×",
                        contentDescription = Strings.closeSources,
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            showSourcesDrawer = false
                            highlightedSourceLineText = ""
                        }
                    )
                }

                SepiaDivider()

                Column(verticalArrangement = Arrangement.spacedBy(Space.s6)) {
                    activeVar.webSources.forEach { src ->
                        val firstGroundedLine = src.groundedLines.firstOrNull().orEmpty()
                        val isSelectedLine = highlightedSourceLineText.isNotBlank() &&
                            highlightedSourceLineText == firstGroundedLine
                        val lineBadgeText = if (src.groundedLineNumbers.isNotEmpty()) {
                            if (src.groundedLineNumbers.size == 1) {
                                Strings.line(src.groundedLineNumbers.first())
                            } else {
                                Strings.lines2(src.groundedLineNumbers.joinToString(", "))
                            }
                        } else {
                            Strings.grounded
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.r8))
                                .background(if (isSelectedLine) p.accent.copy(alpha = 0.12f) else p.cardBg)
                                .border(
                                    width = if (isSelectedLine) 1.dp else LineWidth.hairline,
                                    color = if (isSelectedLine) p.accent else p.border,
                                    shape = RoundedCornerShape(Radius.r8)
                                )
                                .clickable(role = Role.Button) {
                                    if (firstGroundedLine.isNotBlank()) {
                                        highlightedSourceLineText = if (isSelectedLine) "" else firstGroundedLine
                                    } else {
                                        openWebLink(src.url)
                                    }
                                }
                                .padding(horizontal = Space.s10, vertical = Space.s8),
                            verticalArrangement = Arrangement.spacedBy(Space.s4)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s6),
                                    modifier = Modifier.weight(1f).padding(end = Space.s6)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(Radius.r4))
                                            .background(p.accent.copy(alpha = 0.18f))
                                            .padding(horizontal = 5.dp, vertical = Space.s2)
                                    ) {
                                        BasicText(
                                            text = "[${src.index}]",
                                            style = p.monoStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        )
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(1.dp)
                                    ) {
                                        BasicText(
                                            text = src.title.ifBlank { src.domain.ifBlank { src.url } },
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        )
                                        if (src.domain.isNotBlank()) {
                                            BasicText(
                                                text = src.domain,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = p.uiLabelStyle.copy(color = p.mutedInk, fontSize = 11.sp)
                                            )
                                        }
                                    }
                                }
                                SepiaButton(
                                    text = "↗",
                                    contentDescription = Strings.openSource,
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { openWebLink(src.url) }
                                )
                            }

                            if (firstGroundedLine.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(Radius.r4))
                                            .background(if (isSelectedLine) p.accent.copy(alpha = 0.24f) else p.elevatedBg)
                                            .padding(horizontal = Space.s6, vertical = Space.s2)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                        ) {
                                            KissVectorIcon(
                                                icon = KissIcon.CORNER_DOWN_RIGHT,
                                                tint = p.accent,
                                                size = IconSize.xs
                                            )
                                            BasicText(
                                                text = lineBadgeText,
                                                style = p.uiLabelStyle.copy(
                                                    color = p.accent,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                )
                                            )
                                        }
                                    }
                                    BasicText(
                                        text = "\"$firstGroundedLine\"",
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiLabelStyle.copy(
                                            color = if (isSelectedLine) p.primaryInk else p.narrationInk,
                                            fontStyle = FontStyle.Italic,
                                            fontSize = 11.sp
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = showRawWireDrawer && activeVar.rawRequestPreview.isNotBlank()) {
            var copiedRawWire by remember(node.id, node.activeVariantIndex) { mutableStateOf("") }
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .padding(top = Space.s6)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.r8))
                        .background(p.sheetBg)
                        .border(LineWidth.hairline, p.border, RoundedCornerShape(Radius.r8))
                        .padding(Space.s10),
                    verticalArrangement = Arrangement.spacedBy(Space.s6)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(text = Strings.requestSent, style = p.uiLabelStyle.copy(fontWeight = FontWeight.Bold))
                        SepiaButton(
                            text = if (copiedRawWire == "req") "✓" else "📋",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                clipboard.setText(AnnotatedString(activeVar.rawRequestPreview))
                                copiedRawWire = "req"
                            }
                        )
                    }
                    BasicText(text = activeVar.rawRequestPreview.take(4000), style = p.monoStyle.copy(fontSize = 11.sp))
                    if (activeVar.rawResponsePreview.isNotBlank()) {
                        SepiaDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(text = Strings.latestResponse, style = p.uiLabelStyle.copy(fontWeight = FontWeight.Bold))
                            SepiaButton(
                                text = if (copiedRawWire == "resp") "✓" else "📋",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = {
                                    clipboard.setText(AnnotatedString(activeVar.rawResponsePreview))
                                    copiedRawWire = "resp"
                                }
                            )
                        }
                        BasicText(text = activeVar.rawResponsePreview.take(4000), style = p.monoStyle.copy(fontSize = 11.sp))
                    }
                }
            }
        }

        if (viewMode == ViewMode.MANUSCRIPT) {
            Spacer(Modifier.height(Space.s8))
            SepiaDivider()
        }
    }
}

@Composable
private fun UsageDetailsPill(
    details: List<String>,
    isOpen: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    accessibilitySummary: String? = null,
    content: @Composable RowScope.(highlighted: Boolean) -> Unit
) {
    val p = LocalParchmentPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val detailsText = remember(details) { details.joinToString("\n") }
    val semanticSummary = remember(details) { details.joinToString(", ") }
    val shape = RoundedCornerShape(Radius.r4)

    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (hovered || isOpen) p.accent.copy(alpha = 0.08f) else Color.Transparent)
            .kissClickable(interactionSource = interaction, onClick = onToggle)
            .then(
                if (accessibilitySummary != null) Modifier.accessibilityLabel(accessibilitySummary)
                else Modifier.semantics { if (semanticSummary.isNotBlank()) stateDescription = semanticSummary }
            )
            .padding(horizontal = Space.s4, vertical = Space.s2),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s4)
        ) {
            content(hovered || isOpen)
        }
        HoverHint(detailsText.takeIf { it.isNotBlank() }, hovered && !pressed && !isOpen)
        if (isOpen && details.isNotEmpty()) {
            Popup(
                popupPositionProvider = HoverHintPosition,
                onDismissRequest = onDismiss,
                properties = PopupProperties(focusable = true)
            ) {
                val cardShape = RoundedCornerShape(Radius.r8)
                Column(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .overlayShadow(cardShape)
                        .clip(cardShape)
                        .background(p.cardBg)
                        .border(LineWidth.hairline, p.border, cardShape)
                        .padding(horizontal = Space.s10, vertical = Space.s8),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    details.forEachIndexed { index, line ->
                        BasicText(
                            text = line,
                            style = p.uiLabelStyle.copy(
                                fontSize = 11.sp,
                                fontFeatureSettings = "tnum",
                                color = if (index == 0) p.primaryInk else p.mutedInk,
                                fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal
                            )
                        )
                    }
                }
            }
        }
    }
}
