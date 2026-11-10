package io.parchment.ai.ui

import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.hoverable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// ============================================================================================
// MESSAGE TURN ITEM WITH:
// - Auto-Opening Live Markdown/LaTeX Reasoning Box while Thinking -> Auto-Collapse when Done
// - 1-Tap `📋 Copy` (Plain Text) & `📋 Markdown` (Markdown) Buttons
// - Vertical Stacking of Action Buttons & Telemetry so Portrait `⋯ More` NEVER Hides Buttons!
// ============================================================================================
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
        .border(0.75.dp, edge, shape)
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
    var showSourcesDrawer by remember(node.id, node.activeVariantIndex) { mutableStateOf(false) }
    var highlightedSourceLineText by remember(node.id, node.activeVariantIndex) { mutableStateOf("") }
    var copiedBadge by remember(node.id, node.activeVariantIndex) { mutableStateOf("") }
    val enableMd = if (isAssistant) preferences.renderAssistantMessagesWithMarkdown else preferences.renderUserMessagesWithMarkdown
    // Live Reasoning Expansion & "Single-Thought Peek" State Machine:
    // - While actively reasoning (`isActivelyThinkingThisNode == true`):
    //   - Default (`manualThinkingToggle == null` or `true`): Full Thinking and tool steps open.
    //   - When user presses `Hide ▲` (`manualThinkingToggle == false`): switches to Compact Single-Thought Peek
    //     (`isLivePeekOnly = true`) showing ONLY the latest single thought block!
    // - After reasoning finishes (`isActivelyThinkingThisNode == false`):
    //   - Pressing `Hide ▲` (or auto-collapsing when `autoCollapseThinking` is ON) hides all thinking completely!
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
        userName.ifBlank { "User" }
    }

    var variantSwipeDelta by remember(node.id, node.activeVariantIndex) { mutableStateOf(0f) }

    // On pointer devices the quiet footer appears with the turn being read; touch has no hover, so it stays.
    val turnHoverSource = remember { MutableInteractionSource() }
    val turnHovered by turnHoverSource.collectIsHoveredAsState()
    var footerHasFocus by remember { mutableStateOf(false) }
    val footerVisible = win.isCompact || turnHovered || footerHasFocus || showMoreActions || activeVar.isStreaming
    val footerAlpha by animateFloatAsState(if (footerVisible) 1f else 0f, tween(120))
    val showBubbles = viewMode == ViewMode.EDITORIAL_TURNS
    val isSentBubble = showBubbles && !isAssistant

    // Disable outer `.animateContentSize` while `activeVar.isStreaming` is true so 60/120fps VSync character
    // streaming never fights a lagging spring height animation on every wrapped line!
    val baseContainerModifier = if (viewMode == ViewMode.MANUSCRIPT) {
        Modifier
            .fillMaxWidth()
            .hoverable(turnHoverSource)
            .then(
                if (!node.includedInContext) Modifier.background(p.elevatedBg.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                else Modifier
            )
            .then(
                if (!activeVar.isStreaming) Modifier.animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                else Modifier
            )
            .padding(vertical = 6.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .hoverable(turnHoverSource)
            .then(if (!node.includedInContext) Modifier.alpha(0.62f) else Modifier)
            .then(
                if (!activeVar.isStreaming) Modifier.animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
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
        // Turn Header & Branch/Swipe Bar (FlowRow so Speaker/Model and Swipe/Thought pills never overlap on mobile!)
        val showTurnHeader = isAssistant || preferences.showUserAvatar || preferences.showUserName ||
            preferences.showUserTimestamp || node.variants.size > 1 || !node.includedInContext
        if (showTurnHeader) @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false).padding(top = 2.dp, end = 8.dp),
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
                                text = "Left out of context",
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (node.variants.size > 1) {
                    SepiaButton(
                        text = "←",
                        contentDescription = "Previous version",
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
                        contentDescription = "Next version",
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
                        isLivePeekOnly && isCurrentlyToolStep -> "🔧 Tool Peek$sec…"
                        isLivePeekOnly -> "🧠 Peek$sec…"
                        isActivelyThinkingThisNode && isCurrentlyToolStep -> "🔧 Working$sec$toolBadge…"
                        isActivelyThinkingThisNode -> "🧠 Thinking$sec$toolBadge…"
                        isReasoningExpanded -> "▾ Thought$sec$toolBadge"
                        else -> "▸ Thought$sec$toolBadge"
                    }
                    SepiaButton(
                        text = chipText,
                        compact = true,
                        hint = "Show or hide the thinking and tool steps",
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
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(150)),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(120))
        ) {
            val livePeekInfo = remember(isLivePeekOnly, activeVar.thinkingSummary, activeVar.trailingThinking, activeVar.toolCalls) {
                if (isLivePeekOnly) extractLatestAgentPeek(activeVar) else LiveAgentPeekInfo(isToolPeek = false, title = "", body = "")
            }
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.sheetBg.copy(alpha = 0.58f))
                    .border(0.75.dp, if (isActivelyThinkingThisNode) p.accent.copy(alpha = 0.8f) else p.border.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header Row for Thinking and tool steps Drawer
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false).padding(top = 2.dp, end = 6.dp)
                    ) {
                        if (isActivelyThinkingThisNode) {
                            KissStreamingPulseDot(color = p.accent)
                        } else {
                            KissVectorIcon(icon = KissIcon.BRAIN, tint = p.accent, size = 13.5.dp)
                        }
                        val wordCount = countWordsFast(activeVar.thinkingSummary)
                        val toolCount = activeVar.toolCalls.size
                        val headerSummary = buildString {
                            if (isLivePeekOnly) {
                                append(if (livePeekInfo.isToolPeek) "Latest Tool" else "Latest Thought")
                            } else if (isActivelyThinkingThisNode) {
                                append(if (livePeekInfo.isToolPeek) "Running tools and thinking" else "Thinking")
                            } else if (win.isCompact) {
                                append("Reasoning")
                            } else {
                                append("Thinking and tool steps")
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
                        contentDescription = if (isLivePeekOnly) "Show thinking" else "Hide thinking",
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
                            .padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (livePeekInfo.title.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(p.accent.copy(alpha = 0.16f))
                                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                                    ) {
                                        BasicText(
                                            text = "Running",
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
                                "fs_edit" -> "Edited"
                                "fs_multi_edit" -> "Multi-Edited"
                                else -> "Wrote"
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
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.cardBg)
                            .border(
                                width = if (tc.isRunning) 1.dp else 0.75.dp,
                                color = when {
                                    tc.isError -> p.danger.copy(alpha = 0.7f)
                                    tc.isRunning -> p.accent
                                    else -> p.border.copy(alpha = 0.75f)
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (canExpandTool) Modifier.clickable(role = Role.Button, onClickLabel = if (isToolResultExpanded) "Collapse tool result" else "Expand tool result") { isToolResultExpanded = !isToolResultExpanded } else Modifier),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(p.accent.copy(alpha = 0.16f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        BasicText(
                                            text = "Running",
                                            style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                        )
                                    }
                                }
                                if (canExpandTool) {
                                    SepiaButton(
                                        text = if (effectiveExpanded) "▴" else "▾",
                                        contentDescription = if (effectiveExpanded) "Collapse tool result" else "Expand tool result",
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
                    // Chronological Interleaved Timeline: think1 -> tool1 -> think2 -> tool2 -> think3 -> tool3
                    // Windowed during massive 25+ tool runs so Compose never mounts 100+ ProseRenderer trees simultaneously!
                    val totalToolSteps = activeVar.toolCalls.size
                    var showAllInterleavedSteps by remember(node.id, node.activeVariantIndex) { mutableStateOf(false) }
                    val maxVisibleTailSteps = if (isActivelyThinkingThisNode) 12 else 24
                    val hiddenStepCount = if (!showAllInterleavedSteps && totalToolSteps > maxVisibleTailSteps) {
                        totalToolSteps - maxVisibleTailSteps
                    } else 0
                    if (hiddenStepCount > 0) {
                        SepiaButton(
                            text = "▾ Show $hiddenStepCount earlier tool steps (${totalToolSteps} total)",
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
                                    .padding(vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    KissVectorIcon(icon = KissIcon.SPARKLES, tint = p.accent, size = 11.dp)
                                    BasicText(
                                        text = "Thought #$thoughtStepCounter",
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
                                .padding(vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                KissVectorIcon(icon = KissIcon.SPARKLES, tint = p.accent, size = 11.dp)
                                BasicText(
                                    text = "Thought #$thoughtStepCounter",
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
                            text = "Thinking saved for the next turn",
                            style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFamily = p.codeFontFamily)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

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
                goalTracker.isCompleted -> "Done, $doneCount of $totalCount"
                totalCount > 0 -> "$doneCount of $totalCount done"
                else -> "Planning"
            }
            Column(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.sheetBg.copy(alpha = 0.78f))
                    .border(
                        width = 0.75.dp,
                        color = if (goalTracker.isCompleted) p.accentSecondary else p.accent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KissVectorIcon(icon = KissIcon.TARGET, tint = p.accent, size = 13.5.dp)
                        BasicText(
                            text = "Goal: ${goalTracker.goalObjective.ifBlank { "Autonomous Task Checklist" }}",
                            style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                KissVectorIcon(icon = rowIcon, tint = itemColor, size = 12.5.dp)
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
                            text = "Verified: ${goalTracker.verificationSummary}",
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
            else -> Modifier.rubricMark(p.accent).padding(start = 14.dp, top = 2.dp, bottom = 2.dp)
        }
        Column(contentModifier) {
        if (isEditing) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SepiaTextField(
                    value = editBuffer,
                    onValueChange = { editBuffer = it },
                    singleLine = false,
                    minHeight = 120.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!isAssistant) {
                        SepiaButton(
                            text = "↻ Save & regenerate",
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
                        text = "Save",
                        compact = true,
                        style = if (isAssistant) SepiaButtonStyle.PRIMARY else SepiaButtonStyle.OUTLINE,
                        onClick = {
                            onSaveEditedText(editBuffer, false, false)
                            isEditing = false
                        }
                    )
                    SepiaButton(
                        text = "Save as new version",
                        compact = true,
                        style = SepiaButtonStyle.SECONDARY,
                        onClick = {
                            onSaveEditedText(editBuffer, true, false)
                            isEditing = false
                        }
                    )
                    SepiaButton(
                        text = "Cancel",
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
                    Spacer(Modifier.height(4.dp))
                    SepiaButton(
                        text = if (isLongExpanded) "▾ Collapse Long Message" else "▸ Read Full Message (${activeVar.content.length} chars)",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { isLongExpanded = !isLongExpanded }
                    )
                }
            }
        }

        // Pasted / Attached Images, Videos, PDFs, Audio, & Text/Code Files in Chat Turns
        if (preferences.showFilesBelowReplies && activeVar.attachments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val imageAtts = activeVar.attachments.filter {
                val m = it.mimeType.lowercase()
                val l = it.fileName.lowercase()
                m.startsWith("image/") || l.endsWith(".png") || l.endsWith(".jpg") ||
                    l.endsWith(".jpeg") || l.endsWith(".webp") || l.endsWith(".gif") || l.endsWith(".bmp")
            }
            val otherAtts = activeVar.attachments.filterNot { it in imageAtts }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (imageAtts.isNotEmpty()) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        imageAtts.forEach { att ->
                            val bmp = rememberFileImage(att.localFilePath).bitmap
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 360.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(p.cardBg)
                                    .border(0.75.dp, p.border, RoundedCornerShape(10.dp))
                                    .clickable(role = Role.Button, onClickLabel = "Open attachment") { onInspectAttachment(att) }
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = att.fileName,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .heightIn(max = 260.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.SEARCH, tint = p.accent, size = 11.5.dp)
                                        BasicText(
                                            text = "Zoom",
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                isPdf -> KissIcon.FILE_TEXT to "Open PDF"
                                isVid -> KissIcon.VIDEO to "Play Video"
                                isAud -> KissIcon.AUDIO to "Play Audio"
                                else -> {
                                    val stats = if (att.textContent.isNotBlank()) {
                                        "${att.textContent.lines().size} lines"
                                    } else "File"
                                    KissIcon.FILE_TEXT to "View ($stats)"
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(p.cardBg)
                                    .border(0.75.dp, p.border, RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button, onClickLabel = "Open attachment") { onInspectAttachment(att) }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
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
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                activeVar.mediaItems.forEach { media ->
                    when (media.kind) {
                        MediaKind.IMAGE -> {
                            val bmp = rememberFileImage(media.localFilePath).bitmap
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(p.cardBg)
                                    .border(0.75.dp, p.border, RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = media.revisedPrompt.ifBlank { "Generated Image" },
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 520.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(role = Role.Button) {
                                                onInspectGeneratedImage(media)
                                            }
                                    )
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.IMAGE, tint = p.accent, size = 14.5.dp)
                                        BasicText(
                                            text = "Generated Image (${media.localFilePath.ifBlank { media.remoteUrl }})",
                                            style = p.uiBodyStyle.copy(color = p.accent)
                                        )
                                    }
                                }
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    BasicText(
                                        text = "${media.mimeType}${if (media.aspectRatio.isNotBlank()) " • ${media.aspectRatio}" else ""}",
                                        style = p.uiLabelStyle,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (bmp != null) {
                                            SepiaButton(
                                                text = "🔍 View & zoom",
                                                compact = true,
                                                style = SepiaButtonStyle.PRIMARY,
                                                onClick = { onInspectGeneratedImage(media) }
                                            )
                                        }
                                        val openTarget = media.localFilePath.ifBlank { media.remoteUrl }
                                        if (openTarget.isNotBlank()) {
                                            SepiaButton(
                                                text = "Open in OS",
                                                compact = true,
                                                style = SepiaButtonStyle.OUTLINE,
                                                onClick = { PlatformBridge.openOrPlayMedia(openTarget) }
                                            )
                                        }
                                        if (media.localFilePath.isNotBlank()) {
                                            SepiaButton(
                                                text = "Copy image",
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
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(p.cardBg)
                                    .border(0.75.dp, p.accentSecondary, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    KissVectorIcon(icon = KissIcon.VIDEO, tint = p.accent, size = 15.dp)
                                    BasicText(
                                        text = "Generated Video (${media.mimeType}${if (media.aspectRatio.isNotBlank()) " • ${media.aspectRatio}" else ""})",
                                        style = p.uiHeaderStyle.copy(fontSize = 15.sp, color = p.accent)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val playTarget = media.localFilePath.ifBlank { media.remoteUrl }
                                    if (playTarget.isNotBlank()) {
                                        SepiaButton(
                                            text = "▶ Play video (.MP4)",
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
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(p.cardBg)
                                    .border(0.75.dp, p.accentSecondary, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    KissVectorIcon(icon = KissIcon.AUDIO, tint = p.accent, size = 15.dp)
                                    BasicText(
                                        text = "Generated Audio (${media.mimeType})",
                                        style = p.uiHeaderStyle.copy(fontSize = 15.sp, color = p.accent)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val playTarget = media.localFilePath.ifBlank { media.remoteUrl }
                                    if (playTarget.isNotBlank()) {
                                        SepiaButton(
                                            text = "▶ Play audio (.MP3)",
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

        Spacer(Modifier.height(6.dp))

        // ========================================================================================
        // VERTICALLY STACKED MESSAGE FOOTER (Buttons on Row 1, Telemetry on Row 2)
        // Guarantees that expanding `⋯ More` on portrait phones NEVER crushes or hides buttons!
        // ========================================================================================
        if (isAssistant || preferences.showUserMessageActions) {
            Column(
                modifier = Modifier.fillMaxWidth().alpha(footerAlpha).onFocusChanged { footerHasFocus = it.hasFocus },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isSentBubble) Arrangement.spacedBy(4.dp, Alignment.End) else Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                            contentDescription = if (isSpeakingThisTurn) "Stop reading aloud" else "Read aloud",
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
                        contentDescription = "Regenerate response",
                        compact = true,
                        enabled = !activeVar.isStreaming,
                        style = SepiaButtonStyle.GHOST,
                        onClick = onRegenerate
                    )
                    }
                    SepiaButton(
                        text = if (showMoreActions) "▴" else "⋯",
                        contentDescription = if (showMoreActions) "Hide more actions" else "More actions",
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
                                text = "→ Continue",
                                compact = true,
                                enabled = !activeVar.isStreaming,
                                style = SepiaButtonStyle.GHOST,
                                onClick = onContinue
                            )
                        }
                        SepiaButton(
                            text = "✎",
                            contentDescription = "Edit message",
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
                                contentDescription = "Translate message",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = onTranslate
                            )
                        }
                        SepiaButton(
                            text = "⑂ Branch",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onForkHere
                        )
                        SepiaButton(
                            text = "↺ Rewind",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onRewindHere
                        )
                        SepiaButton(
                            text = if (node.includedInContext) "🙈" else "👁",
                            contentDescription = if (node.includedInContext) "Exclude from context" else "Include in context",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onToggleContextMute
                        )
                        if (activeVar.rawRequestPreview.isNotBlank()) {
                            SepiaButton(
                                text = "‹/›",
                                contentDescription = "Raw request and response",
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
                                contentDescription = "Delete message",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = onDeleteNode
                            )
                        } else {
                            SepiaButton(
                                text = "🗑",
                                contentDescription = "Delete message",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = onDeleteNode
                            )
                        }
                    }
                }

                // Clean, Scannable Pill Telemetry for Assistant Messages (Separates Output Prose Tokens from Tool Input Payloads!)
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

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Pill 1: Output Prose Tokens (+ Reasoning Tokens + Tool Payload Tokens clearly separated!)
                        Box(
                            modifier = Modifier
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                BasicText(
                                    text = "~${formatCompactTokens(outProseTok)} tokens",
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
                                        size = 10.5.dp
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
                                    horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                                ) {
                                    if (m.tokensPerSec > 0.0) {
                                        KissVectorIcon(
                                            icon = KissIcon.ZAP,
                                            tint = p.accentSecondary,
                                            size = 10.dp
                                        )
                                        BasicText(
                                            text = "${m.tokensPerSec} tok/s$durationSuffix",
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

                        // Pill 3: Cost Badge
                        if (costVal > 0.0 || m.reportedRequests > 0) {
                            Box(
                                modifier = Modifier
                            ) {
                                BasicText(
                                    text = if (m.reportedRequests > 0) SessionSpendSummary(m, 0.0, 0).label else "~" + formatUsdCost(costVal),
                                    style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.narrationInk, fontWeight = FontWeight.Medium)
                                )
                            }
                        }

                        if ((showMoreActions || preferences.showTokensForEntireTurn) && (m.inputTokens > 0 || m.reportedRequests > 0)) {
                            Box(
                                modifier = Modifier
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    BasicText(
                                        text = "${formatCompactTokens(m.inputTokens)} input • ${formatCompactTokens(m.outputTokens)} output",
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk)
                                    )
                                    if (m.cachedInputTokens > 0 || m.cacheCreationInputTokens > 0) {
                                        BasicText(
                                            text = "${formatCompactTokens(m.cachedInputTokens)} reused • ${formatCompactTokens(m.cacheCreationInputTokens)} saved for reuse",
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk)
                                        )
                                    }
                                    if (m.reportedRequests > 0) {
                                        BasicText(
                                            text = "${m.reportedRequests} request${if (m.reportedRequests == 1) "" else "s"}" +
                                                if (!m.usageIsComplete || !m.costIsAvailable) " • partial" else if (m.reportedCostRequests == m.reportedRequests) " • reported cost" else " • estimate",
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk)
                                        )
                                    }
                                    if (m.outputIncludesUnreportedReasoning) BasicText(
                                        text = "Thinking counted in output", style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk)
                                    )
                                    if (m.reportedRequests > 0 && !m.costIsAvailable && costVal == 0.0) BasicText(
                                        text = "Cost unavailable", style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk)
                                    )
                                    if (m.unpricedCostComponents.isNotEmpty()) BasicText(
                                        text = "Unpriced: ${m.unpricedCostComponents.sorted().joinToString(", ")}",
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk)
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
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).alpha(footerAlpha),
                horizontalArrangement = if (isSentBubble) Arrangement.spacedBy(5.dp, Alignment.End) else Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val attSuffix = if (activeVar.attachments.isNotEmpty()) {
                    " • ${activeVar.attachments.size} file${if (activeVar.attachments.size > 1) "s" else ""}"
                } else ""
                Box(
                    modifier = Modifier
                ) {
                    BasicText(
                        text = "${formatCompactTokens(userTok)} tok$attSuffix",
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

        // ========================================================================================
        // EXPANDABLE LINE-GROUNDED WEB SOURCES DRAWER (Toggled by `🌐 N Sources` pill in footer)
        // Clearly shows which exact line(s) in the assistant message each source grounds!
        // ========================================================================================
        AnimatedVisibility(visible = isAssistant && showSourcesDrawer && activeVar.webSources.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.sheetBg.copy(alpha = 0.85f))
                    .border(0.75.dp, p.accent.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                    .padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        KissVectorIcon(icon = KissIcon.GLOBE, tint = p.accent, size = 13.5.dp)
                        BasicText(
                            text = "Grounded Web Sources (${activeVar.webSources.size}) — Tap a source to highlight its line",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        )
                    }
                    SepiaButton(
                        text = "×",
                        contentDescription = "Close sources",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            showSourcesDrawer = false
                            highlightedSourceLineText = ""
                        }
                    )
                }

                SepiaDivider()

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    activeVar.webSources.forEach { src ->
                        val firstGroundedLine = src.groundedLines.firstOrNull().orEmpty()
                        val isSelectedLine = highlightedSourceLineText.isNotBlank() &&
                            highlightedSourceLineText == firstGroundedLine
                        val lineBadgeText = if (src.groundedLineNumbers.isNotEmpty()) {
                            if (src.groundedLineNumbers.size == 1) {
                                "Line ${src.groundedLineNumbers.first()}"
                            } else {
                                "Lines ${src.groundedLineNumbers.joinToString(", ")}"
                            }
                        } else {
                            "Grounded"
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelectedLine) p.accent.copy(alpha = 0.12f) else p.cardBg)
                                .border(
                                    width = if (isSelectedLine) 1.dp else 0.75.dp,
                                    color = if (isSelectedLine) p.accent else p.border,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(role = Role.Button) {
                                    if (firstGroundedLine.isNotBlank()) {
                                        highlightedSourceLineText = if (isSelectedLine) "" else firstGroundedLine
                                    } else {
                                        openWebLink(src.url)
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f).padding(end = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(p.accent.copy(alpha = 0.18f))
                                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
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
                                    contentDescription = "Open source",
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { openWebLink(src.url) }
                                )
                            }

                            if (firstGroundedLine.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isSelectedLine) p.accent.copy(alpha = 0.24f) else p.elevatedBg)
                                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                                        ) {
                                            KissVectorIcon(
                                                icon = KissIcon.CORNER_DOWN_RIGHT,
                                                tint = p.accent,
                                                size = 9.5.dp
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
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(p.sheetBg)
                        .border(0.75.dp, p.border, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(text = "Request sent:", style = p.uiLabelStyle.copy(fontWeight = FontWeight.Bold))
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
                            BasicText(text = "Latest response:", style = p.uiLabelStyle.copy(fontWeight = FontWeight.Bold))
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
            Spacer(Modifier.height(8.dp))
            SepiaDivider()
        }
    }
}
