package io.parchment.ai.ui

import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.MessageNavVisibility
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.QueuedPromptItem
import io.parchment.ai.model.SendShortcutMode
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.network.resolveModelModalityProfile
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.AgentPermissionOverrides
import io.parchment.ai.model.ViewMode
import io.parchment.ai.model.WorkspaceMode
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.protocol.O200kTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================================
// The chat page: messages, the floating message navigator and the composer with steer and queue.
// ============================================================================================
@Composable
internal fun ManuscriptViewport(
    appState: AppStateSnapshot,
    activeSession: ChatSession,
    activeAssistant: AssistantAgent,
    activeProfile: ProviderProfile,
    preferences: PreferencesConfig,
    showSessionInspector: Boolean,
    onToggleSessionInspector: () -> Unit,
    isStreaming: Boolean,
    isCurrentlyThinkingPhase: Boolean,
    streamingTargetNodeId: String,
    streamingStatus: String,
    streamingInteractionId: String,
    queuedPrompts: List<QueuedPromptItem>,
    onRemoveQueuedPrompt: (String) -> Unit,
    onSteerQueuedPromptNow: (QueuedPromptItem) -> Unit,
    userPromptInput: String,
    onUserPromptChange: (String) -> Unit,
    assistantPrefillInput: String,
    onAssistantPrefillChange: (String) -> Unit,
    showPrefillBox: Boolean,
    onTogglePrefillBox: () -> Unit,
    showComposerToolsDrawer: Boolean,
    onComposerToolsDrawerChange: (Boolean) -> Unit,
    isGoalModeEnabled: Boolean,
    onToggleGoalMode: () -> Unit,
    isLiveVoiceMode: Boolean,
    isVoiceListening: Boolean,
    onToggleLiveVoiceMode: () -> Unit,
    onTriggerVoiceInput: () -> Unit,
    pendingAttachments: List<PromptAttachment>,
    onAddAttachment: () -> Unit,
    onAttachWorkspaceFile: (String) -> Unit = {},
    onReattachExistingAttachment: (PromptAttachment) -> Unit = {},
    onPasteClipboardMedia: () -> Boolean,
    onRequestClearCurrentChat: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onInspectAttachment: (PromptAttachment) -> Unit,
    onInspectGeneratedImage: (GeneratedMediaItem) -> Unit,
    onExportChatMarkdown: () -> Unit,
    onExportChatJson: () -> Unit = {},
    onOpenPricingSettings: () -> Unit,
    onSend: () -> Unit,
    onSendGuidedDirection: () -> Unit,
    onSteerNow: () -> Unit,
    onQueuePrompt: () -> Unit,
    onImpersonateDraft: () -> Unit,
    onAutoSummarizeStory: () -> Unit,
    onCompactContext: () -> Unit,
    onRewindToNode: (Int) -> Unit,
    onForkSessionFromNode: (Int) -> Unit,
    onStopStream: () -> Unit,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onRegenerateNode: (Int) -> Unit,
    onTranslateNode: (Int) -> Unit = {},
    onContinueAssistantNode: (Int) -> Unit,
    jumpToNodeId: String = "",
    onJumpHandled: () -> Unit = {},
    onTogglePinnedReply: (nodeId: String) -> Unit = {}
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showMinimapTreeModal by remember { mutableStateOf(false) }
    var showContextBreakdownPopover by remember { mutableStateOf(false) }

    // Following the newest text:
    // 1. Scrolls to the bottom spacer with a large offset, so a long reply keeps its bottom edge in view
    //    instead of jumping to its top.
    // 2. Stops following as soon as the user scrolls up, and stays stopped until they scroll back down or tap
    //    Latest, so the page never moves away while they read.
    var userScrollPausedUntilMs by remember(activeSession.id) { mutableStateOf(0L) }
    var userDisengagedAutoFollow by remember(activeSession.id) { mutableStateOf(false) }

    suspend fun jumpToBottomEdge() {
        val totalItems = listState.layoutInfo.totalItemsCount
        val bottomSpacerIdx = (totalItems - 1).coerceAtLeast(activeSession.messages.size)
        listState.scrollToItem(bottomSpacerIdx, scrollOffset = 100_000)
    }

    // Auto-scroll to bottom when opening or switching to a chat (when enabled in Settings -> Preferences)
    LaunchedEffect(activeSession.id, preferences.autoScrollToBottomOnOpen) {
        userDisengagedAutoFollow = false
        userScrollPausedUntilMs = 0L
        if (activeSession.messages.isNotEmpty() && jumpToNodeId.isBlank()) {
            if (preferences.autoScrollToBottomOnOpen) {
                jumpToBottomEdge()
                kotlinx.coroutines.delay(16L)
                jumpToBottomEdge()
            } else {
                listState.scrollToItem(0)
            }
        }
    }

    // Opening a pinned reply: wait for the full chat (stubs hold only previews), then put that message at the top.
    LaunchedEffect(activeSession.id, jumpToNodeId, activeSession.isCompactStub) {
        if (jumpToNodeId.isBlank() || activeSession.isCompactStub) return@LaunchedEffect
        val index = activeSession.messages.indexOfFirst { it.id == jumpToNodeId }
        if (index >= 0) {
            userDisengagedAutoFollow = true
            withFrameNanos { }
            listState.scrollToItem(index + 1) // Item 0 is the top spacer.
        }
        onJumpHandled()
    }

    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            if (!listState.canScrollForward) {
                userDisengagedAutoFollow = false
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress, listState.canScrollForward) {
        if (listState.isScrollInProgress && listState.canScrollForward) {
            userDisengagedAutoFollow = true
        } else if (!listState.isScrollInProgress && !listState.canScrollForward) {
            userDisengagedAutoFollow = false
            userScrollPausedUntilMs = 0L
        }
    }

    val lastMsgContentLen = activeSession.messages.lastOrNull()?.activeVariant?.let {
        it.content.length + it.thinkingSummary.length + it.trailingThinking.length + it.toolCalls.size
    } ?: 0

    LaunchedEffect(lastMsgContentLen, isStreaming) {
        val now = PlatformBridge.currentTimeMillis()
        if (isStreaming &&
            preferences.autoFollowBottom &&
            activeSession.messages.isNotEmpty() &&
            !listState.isScrollInProgress &&
            !userDisengagedAutoFollow &&
            now >= userScrollPausedUntilMs &&
            (listState.canScrollForward || lastMsgContentLen % 24 == 0)
        ) {
            jumpToBottomEdge()
        }
    }

    // While streaming, recount the context breakdown every 128 characters rather than on every frame:
    // counting the whole history is too costly to repeat per character.
    val throttledBreakdownTrigger = if (isStreaming) {
        (lastMsgContentLen / 128) * 128 + (activeSession.messages.lastOrNull()?.activeVariant?.toolCalls?.size ?: 0)
    } else {
        lastMsgContentLen
    }
    val ownedUtilityRecords = remember(activeSession.id, activeSession.utilityUsageRecords) {
        activeSession.utilityUsageRecords.filter { it.ownerSessionId.isBlank() || it.ownerSessionId == activeSession.id }
    }
    val sessionSpend = remember(activeSession.id, activeSession.messages.size, activeSession.messages.sumOf { it.variants.size },
        throttledBreakdownTrigger, isStreaming, activeProfile, ownedUtilityRecords) {
        sessionSpendSummary(activeSession, activeProfile)
    }
    val sessionTotalCostUsd = sessionSpend.estimateUsd
    val baseContextBreakdown = remember(
        activeSession.id,
        activeSession.storySummary,
        activeSession.systemPrompt,
        activeSession.authorsNote,
        activeSession.workspaceMode,
        activeSession.workspacePath,
        activeAssistant,
        activeProfile,
        appState.globalWorldBook,
        activeSession.messages.size,
        throttledBreakdownTrigger,
        isGoalModeEnabled,
        isStreaming
    ) {
        O200kTokenizer.computeBaseContextWindowBreakdown(
            session = activeSession,
            assistant = activeAssistant,
            profile = activeProfile,
            goalModeActive = isGoalModeEnabled,
            globalWorldBook = appState.globalWorldBook
        )
    }
    val contextBreakdown = remember(
        baseContextBreakdown,
        userPromptInput,
        assistantPrefillInput,
        pendingAttachments
    ) {
        O200kTokenizer.applyComposerDraftToBreakdown(
            baseBreakdown = baseContextBreakdown,
            draftPrompt = userPromptInput,
            draftPrefill = assistantPrefillInput,
            pendingAttachments = pendingAttachments
        )
    }
    val liveContextTokens = contextBreakdown.totalUsedTokens

    // The navigator stays visible for 5 seconds after scrolling stops or after one of its buttons is used.
    var isMinimapScrollLingering by remember { mutableStateOf(false) }
    var minimapInteractionTick by remember { mutableStateOf(0) }

    LaunchedEffect(listState.isScrollInProgress, minimapInteractionTick, showMinimapTreeModal) {
        if (listState.isScrollInProgress || showMinimapTreeModal || minimapInteractionTick > 0) {
            isMinimapScrollLingering = true
            if (!listState.isScrollInProgress && !showMinimapTreeModal) {
                delay(5000L)
                isMinimapScrollLingering = false
            }
        }
    }

    val isScrolledUpFromBottom by remember(activeSession.id, activeSession.messages.size) {
        derivedStateOf {
            if (activeSession.messages.isEmpty()) {
                false
            } else if (listState.canScrollForward) {
                true
            } else {
                val layout = listState.layoutInfo
                val lastVisible = layout.visibleItemsInfo.lastOrNull()
                val total = layout.totalItemsCount
                lastVisible != null && total > 0 && (
                    lastVisible.index < total - 1 ||
                        (lastVisible.offset + lastVisible.size) > (layout.viewportEndOffset + 20)
                    )
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Chat notes and memory, opened from Notes in the top bar.
        AnimatedVisibility(visible = showSessionInspector) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .background(p.elevatedBg.copy(alpha = 0.45f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = p.maxReadingWidth)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(p.cardBg)
                        .border(0.75.dp, p.border, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BasicText(
                            text = "Chat notes & memory",
                            style = p.uiHeaderStyle.copy(fontSize = 15.sp),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (activeSession.messages.isNotEmpty()) {
                                SepiaButton(
                                    text = "Export .MD",
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = onExportChatMarkdown
                                )
                                SepiaButton(
                                    text = "Clear chat",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = onRequestClearCurrentChat
                                )
                            }
                            SepiaButton(
                                text = "×",
                                contentDescription = "Close session inspector",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = onToggleSessionInspector
                            )
                        }
                    }
                    SepiaDivider()
                    SepiaTextField(
                        value = activeSession.systemPrompt,
                        onValueChange = { sp -> onUpdateSession { it.copy(systemPrompt = sp) } },
                        label = "Extra instructions for this chat",
                        placeholder = "Optional instructions added specifically to this conversation…",
                        singleLine = false,
                        minHeight = 64.dp,
                        maxHeight = 320.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SepiaTextField(
                        value = activeSession.authorsNote,
                        onValueChange = { note -> onUpdateSession { it.copy(authorsNote = note) } },
                        label = "Style & tone reminder",
                        placeholder = "Optional reminder kept near the end of the conversation…",
                        singleLine = false,
                        minHeight = 52.dp,
                        maxHeight = 260.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BasicText(
                            text = "Chat summary & memory",
                            style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(top = 4.dp, end = 8.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val canCompactSession = activeSession.messages.count { it.includedInContext } > 1 ||
                                activeSession.messages.any { it.includedInContext && it.activeVariant.toolCalls.isNotEmpty() }
                            SepiaButton(
                                text = if (isStreaming) "✦ Summarize & Resume" else "✦ Summarize",
                                compact = true,
                                enabled = activeSession.messages.isNotEmpty(),
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = onAutoSummarizeStory
                            )
                            SepiaButton(
                                text = if (isStreaming) "⚡ Shorten and continue" else "⚡ Shorten conversation",
                                compact = true,
                                enabled = canCompactSession,
                                style = SepiaButtonStyle.SECONDARY,
                                onClick = onCompactContext
                            )
                        }
                    }
                    SepiaTextField(
                        value = activeSession.storySummary,
                        onValueChange = { sum -> onUpdateSession { it.copy(storySummary = sum) } },
                        placeholder = "Key facts or summary remembered across long chats…",
                        singleLine = false,
                        minHeight = 110.dp,
                        maxHeight = 380.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // The message list and the floating message navigator.
        val win = LocalParchmentWindowSize.current
        val userPicture = rememberFileImage(preferences.profilePicturePath).bitmap
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            val pageGutter = if (win.isCompact) 12.dp else 18.dp
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = maxOf(pageGutter, (maxWidth - p.maxReadingWidth) / 2 + pageGutter)),
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .pointerInput(activeSession.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.type == PointerEventType.Scroll) {
                                    val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    if (dy < 0f) {
                                        // Scrolling up with the wheel or trackpad pauses following for 5 seconds.
                                        userDisengagedAutoFollow = true
                                        userScrollPausedUntilMs = PlatformBridge.currentTimeMillis() + 5000L
                                    }
                                } else if (ev.type == PointerEventType.Press) {
                                    // Touch or scrollbar press while streaming — pause auto-follow so user can read
                                    if (isStreaming) {
                                        userScrollPausedUntilMs = PlatformBridge.currentTimeMillis() + 5000L
                                    }
                                }
                            }
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(
                    if (preferences.defaultViewMode == ViewMode.MANUSCRIPT) 16.dp else if (win.isCompact) 20.dp else 12.dp
                )
            ) {
                item { Spacer(Modifier.height(10.dp)) }

                if (activeSession.messages.isEmpty()) {
                    item {
                        val activeModel = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }
                        val card = activeSession.activeCard ?: activeAssistant.activeCard
                        val displayName = card?.name?.takeIf { it.isNotBlank() }
                            ?: activeAssistant.name.takeIf { it.isNotBlank() }
                            ?: "Ready"
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 44.dp)
                                .rubricMark(p.accent)
                                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (activeAssistant.iconEmoji != "💬") {
                                SepiaAvatarCircle(symbol = activeAssistant.iconEmoji, selected = true, sizeDp = 40.dp)
                            }
                            BasicText(
                                text = if (displayName == "Default Assistant") "Start with a question, a draft or a file." else displayName,
                                style = p.uiHeaderStyle.copy(fontSize = 21.sp, lineHeight = 28.sp)
                            )
                            BasicText(
                                text = activeModel.ifBlank { "Choose a model in Settings" },
                                style = p.uiLabelStyle.copy(color = p.mutedInk)
                            )
                            if (card != null && card.firstMessage.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                SepiaButton(
                                    text = "Start opening scene",
                                    compact = true,
                                    style = SepiaButtonStyle.PRIMARY,
                                    onClick = {
                                        val now = PlatformBridge.currentTimeMillis()
                                        val allGreetings = (listOf(card.firstMessage) + card.alternateGreetings).filter { it.isNotBlank() }
                                        if (allGreetings.isNotEmpty()) {
                                            val greetingNode = MessageNode(
                                                id = "node-greeting-$now",
                                                role = MessageRole.ASSISTANT,
                                                variants = allGreetings.mapIndexed { idx, g ->
                                                    MessageVariant(
                                                        id = "var-greeting-$idx-$now",
                                                        content = g.replace("{{char}}", card.name)
                                                            .replace("{{user}}", activeSession.withProfileName(preferences).userName),
                                                        modelUsed = "Card Greeting #${idx + 1}",
                                                        createdAt = now
                                                    )
                                                }
                                            )
                                            onUpdateSession { it.copy(messages = listOf(greetingNode)) }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                itemsIndexed(
                    items = activeSession.messages,
                    key = { _, node -> node.id },
                    contentType = { _, node -> node.role }
                ) { index, node ->
                    MessageTurnItem(
                        node = node,
                        nodeIndex = index,
                        isLatestTurn = index >= activeSession.messages.lastIndex - 1,
                        userPicture = userPicture,
                        viewMode = preferences.defaultViewMode,
                        preferences = preferences,
                        userName = activeSession.withProfileName(preferences).userName,
                        characterName = activeSession.activeCard?.name?.takeIf { it.isNotBlank() }
                            ?: activeAssistant.name.takeIf { activeAssistant.id.isNotBlank() }.orEmpty(),
                        assistantIconEmoji = activeAssistant.iconEmoji,
                        providerName = activeProfile.name,
                        inputPricePerM = activeProfile.inputPricePerM,
                        outputPricePerM = activeProfile.outputPricePerM,
                        isActivelyThinkingThisNode = isStreaming && isCurrentlyThinkingPhase && streamingTargetNodeId == node.id,
                        onTogglePin = { onTogglePinnedReply(node.id) },
                        onSelectVariant = { newIdx ->
                            onUpdateSession { sess ->
                                sess.copy(
                                    messages = sess.messages.mapIndexed { i, n ->
                                        if (i == index && n.variants.isNotEmpty()) {
                                            n.copy(activeVariantIndex = newIdx.coerceIn(0, n.variants.lastIndex))
                                        } else {
                                            n
                                        }
                                    }
                                )
                            }
                        },
                        onSaveEditedText = { newText, asNewBranch, alsoRegenerate ->
                            val now = PlatformBridge.currentTimeMillis()
                            onUpdateSession { sess ->
                                sess.copy(
                                    messages = sess.messages.mapIndexed { i, n ->
                                        if (i != index) n
                                        else if (asNewBranch) {
                                            val branched = n.activeVariant.copy(
                                                id = "var-$now-branch",
                                                content = newText,
                                                providerInteractionId = "",
                                                tokenMetrics = io.parchment.ai.model.TokenMetrics(),
                                                apiUsageRecords = emptyList(),
                                                createdAt = now
                                            )
                                            val updatedList = n.variants + branched
                                            n.copy(variants = updatedList, activeVariantIndex = updatedList.lastIndex)
                                        } else {
                                            val updatedList = n.variants.mapIndexed { vIdx, v ->
                                                if (vIdx == n.activeVariantIndex) v.copy(content = newText) else v
                                            }
                                            n.copy(variants = updatedList)
                                        }
                                    }
                                )
                            }
                            if (alsoRegenerate) {
                                onRegenerateNode(index)
                            }
                        },
                        onToggleContextMute = {
                            onUpdateSession { sess ->
                                sess.copy(
                                    messages = sess.messages.mapIndexed { i, n ->
                                        if (i == index) n.copy(includedInContext = !n.includedInContext) else n
                                    }
                                )
                            }
                        },
                        onDeleteCurrentVariant = {
                            onUpdateSession { sess ->
                                val target = sess.messages.getOrNull(index) ?: return@onUpdateSession sess
                                if (target.variants.size <= 1) {
                                    sess.copy(messages = sess.messages.filterIndexed { i, _ -> i != index })
                                } else {
                                    val curVarIdx = target.activeVariantIndex.coerceIn(0, target.variants.lastIndex)
                                    val remainingVars = target.variants.filterIndexed { vIdx, _ -> vIdx != curVarIdx }
                                    val nextVarIdx = curVarIdx.coerceAtMost(remainingVars.lastIndex)
                                    sess.copy(
                                        messages = sess.messages.mapIndexed { i, n ->
                                            if (i == index) n.copy(variants = remainingVars, activeVariantIndex = nextVarIdx) else n
                                        }
                                    )
                                }
                            }
                        },
                        onDeleteNode = {
                            onUpdateSession { sess ->
                                sess.copy(messages = sess.messages.filterIndexed { i, _ -> i != index })
                            }
                        },
                        onForkHere = { onForkSessionFromNode(index) },
                        onRewindHere = { onRewindToNode(index) },
                        onRegenerate = { onRegenerateNode(index) },
                        onTranslate = { onTranslateNode(index) },
                        onContinue = { onContinueAssistantNode(index) },
                        onInspectAttachment = onInspectAttachment,
                        onInspectGeneratedImage = onInspectGeneratedImage
                    )
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            val activeEffectiveModelId = activeSession.modelOverride
                .ifBlank { activeProfile.defaultModel }
            val activeModalityProfile = remember(
                activeEffectiveModelId,
                activeProfile.endpointPathOverride,
                activeProfile.customModelSpecs
            ) {
                AiProviderEngine.resolveModelModalityProfile(activeProfile, activeEffectiveModelId)
            }
            val showFloatingAudioPill = isLiveVoiceMode ||
                isVoiceListening ||
                activeModalityProfile.isLiveRealtimeAudio

            // Unified Bottom-Center Floating Stack:
            // Stacked with 8 dp between them so the live audio pill never covers the Latest pill.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(
                    visible = showFloatingAudioPill,
                    enter = slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = spring(stiffness = 580f, dampingRatio = Spring.DampingRatioNoBouncy)
                    ) + scaleIn(
                        initialScale = 0.90f,
                        animationSpec = spring(stiffness = 580f, dampingRatio = 0.80f)
                    ) + fadeIn(tween(110)),
                    exit = slideOutVertically(
                        targetOffsetY = { it / 2 },
                        animationSpec = tween(110)
                    ) + scaleOut(targetScale = 0.92f, animationSpec = tween(110)) + fadeOut(tween(100))
                ) {
                    val audioPillShape = RoundedCornerShape(999.dp)
                    Box(
                        modifier = Modifier
                            .clip(audioPillShape)
                            .background(p.canvasBg.copy(alpha = 0.96f))
                            .border(1.dp, p.accent, audioPillShape)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isVoiceListening || isStreaming || isLiveVoiceMode) {
                                KissStreamingPulseDot(color = p.accent)
                            } else {
                                KissVectorIcon(icon = KissIcon.VOLUME_ON, tint = p.accent, size = 13.dp)
                            }
                            val liveStatusLabel = when {
                                isVoiceListening -> "Listening…"
                                isStreaming -> "Live Audio Active…"
                                isLiveVoiceMode -> "Live Audio: ON"
                                else -> "Live Audio Ready"
                            }
                            BasicText(
                                text = liveStatusLabel,
                                style = p.uiLabelStyle.copy(
                                    color = p.primaryInk,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            )
                            // Mic listen / pause toggle
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (isVoiceListening) p.accent else p.sheetBg)
                                    .border(0.75.dp, p.border, RoundedCornerShape(999.dp))
                                    .kissClickable { onTriggerVoiceInput() }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    KissVectorIcon(
                                        icon = KissIcon.MIC,
                                        tint = if (isVoiceListening) onFillInk(p.accent) else p.primaryInk,
                                        size = 11.5.dp
                                    )
                                    BasicText(
                                        text = if (isVoiceListening) "Pause Mic" else "Speak",
                                        style = p.uiLabelStyle.copy(
                                            color = if (isVoiceListening) onFillInk(p.accent) else p.primaryInk,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                            // Replay / Continue latest assistant audio
                            val latestAssistantText = activeSession.messages
                                .lastOrNull { it.role == MessageRole.ASSISTANT }
                                ?.activeVariant?.content.orEmpty()
                            if (latestAssistantText.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(p.sheetBg)
                                        .border(0.75.dp, p.border, RoundedCornerShape(999.dp))
                                        .kissClickable {
                                            PlatformBridge.speakTextAloud(latestAssistantText)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.PLAY, tint = p.accent, size = 11.dp)
                                        BasicText(
                                            text = "Continue",
                                            style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.Medium, fontSize = 11.sp)
                                        )
                                    }
                                }
                            }
                            // Stop audio output / stream
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(p.sheetBg)
                                    .border(0.75.dp, p.border, RoundedCornerShape(999.dp))
                                    .kissClickable {
                                        PlatformBridge.stopSpeakingAloud()
                                        if (isStreaming) onStopStream()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    KissVectorIcon(icon = KissIcon.STOP, tint = p.danger, size = 11.dp)
                                    BasicText(
                                        text = "Stop",
                                        style = p.uiLabelStyle.copy(color = p.danger, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                    )
                                }
                            }
                            // Toggle Live Voice session on/off
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (isLiveVoiceMode) p.accent.copy(alpha = 0.16f) else p.sheetBg)
                                    .border(0.75.dp, if (isLiveVoiceMode) p.accent else p.border, RoundedCornerShape(999.dp))
                                    .kissClickable {
                                        if (isLiveVoiceMode) PlatformBridge.stopSpeakingAloud()
                                        onToggleLiveVoiceMode()
                                    }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                BasicText(
                                    text = if (isLiveVoiceMode) "End Live" else "Start Live",
                                    style = p.uiLabelStyle.copy(
                                        color = if (isLiveVoiceMode) p.accent else p.mutedInk,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }
                }

                // The Latest pill, shown when scrolled up; opaque so text behind it doesn't show through.
                AnimatedVisibility(
                    visible = isScrolledUpFromBottom,
                    enter = slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = spring(stiffness = 580f, dampingRatio = Spring.DampingRatioNoBouncy)
                    ) + scaleIn(
                        initialScale = 0.90f,
                        animationSpec = spring(stiffness = 580f, dampingRatio = 0.80f)
                    ) + fadeIn(tween(110)),
                    exit = slideOutVertically(
                        targetOffsetY = { it / 2 },
                        animationSpec = tween(110)
                    ) + scaleOut(targetScale = 0.92f, animationSpec = tween(110)) + fadeOut(tween(100))
                ) {
                    val pillOuterShape = RoundedCornerShape(999.dp)
                    val pillInnerShape = RoundedCornerShape(999.dp)
                    // Outer frosted halo shield blocks underlying manuscript text completely
                    Box(
                        modifier = Modifier
                            .clip(pillOuterShape)
                            .background(p.canvasBg.copy(alpha = 0.96f))
                            .border(1.dp, p.border, pillOuterShape)
                            .padding(3.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(pillInnerShape)
                                .background(p.accent)
                                .border(1.dp, p.cardBg.copy(alpha = 0.55f), pillInnerShape)
                                .kissClickable {
                                    userDisengagedAutoFollow = false
                                    userScrollPausedUntilMs = 0L
                                    scope.launch {
                                        jumpToBottomEdge()
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isStreaming) {
                                KissStreamingPulseDot(color = onFillInk(p.accent))
                            }
                            KissVectorIcon(
                                icon = KissIcon.ARROW_DOWN,
                                tint = onFillInk(p.accent),
                                size = 12.5.dp
                            )
                            BasicText(
                                text = "Latest",
                                style = p.uiLabelStyle.copy(
                                    color = onFillInk(p.accent),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            )
                        }
                    }
                }
            }
            // Message navigator: first, previous, branches, next and last.
            val showNavButtons = when (preferences.messageNavButtons) {
                MessageNavVisibility.ALWAYS -> activeSession.messages.isNotEmpty()
                MessageNavVisibility.WHILE_SCROLLING -> activeSession.messages.isNotEmpty() && isMinimapScrollLingering
                MessageNavVisibility.HIDDEN -> false
            }

            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
            ) {
                AnimatedVisibility(
                    visible = showNavButtons || showMinimapTreeModal,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(240))
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    // Expandable Interactive Tree & Swipe Minimap Modal with In-Chat Turn Filter
                    AnimatedVisibility(visible = showMinimapTreeModal) {
                        var mapFilterQuery by remember { mutableStateOf("") }
                        val filteredMapItems = remember(activeSession.messages, mapFilterQuery) {
                            val q = mapFilterQuery.trim()
                            activeSession.messages.mapIndexedNotNull { idx, node ->
                                if (q.isEmpty() || node.activeVariant.content.contains(q, ignoreCase = true) ||
                                    node.activeVariant.thinkingSummary.contains(q, ignoreCase = true)
                                ) {
                                    idx to node
                                } else null
                            }
                        }
                        Column(
                            modifier = Modifier
                                .width(if (win.isCompact) 255.dp else 290.dp)
                                .heightIn(max = 410.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(p.cardBg)
                                .border(1.dp, p.accent, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    KissVectorIcon(icon = KissIcon.MAP_TREE, tint = p.accent, size = 13.5.dp)
                                    BasicText(
                                        text = "Message Map (${filteredMapItems.size}/${activeSession.messages.size})",
                                        style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold)
                                    )
                                }
                                SepiaButton(
                                    text = "×",
                                    contentDescription = "Close conversation map",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { showMinimapTreeModal = false }
                                )
                            }
                            SepiaTextField(
                                value = mapFilterQuery,
                                onValueChange = { mapFilterQuery = it },
                                placeholder = "🔍 Filter turns…",
                                singleLine = true,
                                minHeight = 30.dp,
                                modifier = Modifier.fillMaxWidth()
                            )
                            SepiaDivider()
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                filteredMapItems.forEach { (idx, mNode) ->
                                    val isAsst = mNode.role == MessageRole.ASSISTANT
                                    val roleBadge = if (isAsst) "AI" else "You"
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isAsst) p.sheetBg.copy(alpha = 0.65f) else p.elevatedBg.copy(alpha = 0.45f))
                                            .kissClickable(pressScale = 0.98f) {
                                                minimapInteractionTick++
                                                scope.launch {
                                                    listState.animateScrollToItem(idx + 1)
                                                }
                                            }
                                            .padding(7.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            BasicText(
                                                text = "#${idx + 1} $roleBadge",
                                                style = p.uiLabelStyle.copy(
                                                    color = if (isAsst) p.accent else p.primaryInk,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                )
                                            )
                                            if (mNode.variants.size > 1) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    mNode.variants.forEachIndexed { vIdx, _ ->
                                                        val vSel = vIdx == mNode.activeVariantIndex
                                                        Box(
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .background(if (vSel) p.accent else p.cardBg)
                                                                .kissClickable {
                                                                    minimapInteractionTick++
                                                                    onUpdateSession { sess ->
                                                                        sess.copy(
                                                                            messages = sess.messages.mapIndexed { i, n ->
                                                                                if (i == idx) n.copy(activeVariantIndex = vIdx) else n
                                                                            }
                                                                        )
                                                                    }
                                                                    scope.launch { listState.animateScrollToItem(idx + 1) }
                                                                }
                                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                                        ) {
                                                            BasicText(
                                                                text = "v${vIdx + 1}",
                                                                style = p.uiLabelStyle.copy(
                                                                    fontSize = 11.sp,
                                                                    color = if (vSel) onFillInk(p.accent) else p.narrationInk,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        BasicText(
                                            text = stripMarkdownToPlainText(mNode.activeVariant.content).ifBlank { "(Media / Empty)" },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.narrationInk)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Compact Vertical Minimap Rail (`⤒`, `↑`, `🗺`, `↓`, `⤓`)
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(p.cardBg.copy(alpha = 0.94f))
                            .border(0.75.dp, p.border, RoundedCornerShape(999.dp))
                            .padding(vertical = 4.dp, horizontal = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Extreme Jump Top
                        SepiaButton(
                            text = "⤒",
                            contentDescription = "Jump to first message",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                minimapInteractionTick++
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                        )
                        // Jump Previous Message
                        SepiaButton(
                            text = "↑",
                            contentDescription = "Previous message",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                minimapInteractionTick++
                                scope.launch {
                                    val cur = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                                    listState.animateScrollToItem(cur)
                                }
                            }
                        )
                        // Toggle Tree & Swipe Map
                        SepiaButton(
                            text = "🗺",
                            contentDescription = "Conversation map",
                            compact = true,
                            style = if (showMinimapTreeModal) SepiaButtonStyle.PRIMARY else SepiaButtonStyle.GHOST,
                            onClick = {
                                minimapInteractionTick++
                                showMinimapTreeModal = !showMinimapTreeModal
                            }
                        )
                        // Jump Next Message
                        SepiaButton(
                            text = "↓",
                            contentDescription = "Next message",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                minimapInteractionTick++
                                scope.launch {
                                    val next = (listState.firstVisibleItemIndex + 1).coerceAtMost(activeSession.messages.size)
                                    listState.animateScrollToItem(next)
                                }
                            }
                        )
                        // Extreme Jump Bottom
                        SepiaButton(
                            text = "⤓",
                            contentDescription = "Jump to latest message",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                minimapInteractionTick++
                                userDisengagedAutoFollow = false
                                userScrollPausedUntilMs = 0L
                                scope.launch {
                                    jumpToBottomEdge()
                                }
                            }
                        )
                    }
                }
                }
            }
        }

        // ========================================================================================
        // The composer: Enter sends on desktop; while a reply runs, steer or queue the next message.
        // ========================================================================================
        val hasTypedInput = userPromptInput.isNotBlank() || assistantPrefillInput.isNotBlank() || pendingAttachments.isNotEmpty()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.canvasBg)
                .padding(horizontal = if (win.isCompact) 10.dp else 14.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val deckShape = RoundedCornerShape(12.dp)
            Column(
                modifier = Modifier
                    .widthIn(max = p.maxReadingWidth)
                    .fillMaxWidth()
                    .clip(deckShape)
                    .background(p.cardBg)
                    .border(
                        width = if (isStreaming) 1.dp else 0.75.dp,
                        color = if (isStreaming) p.accent else p.border.copy(alpha = 0.85f),
                        shape = deckShape
                    )
                    .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                // 1. Live status while streaming: the headline and its detail on separate lines.
                var statusExpanded by remember { mutableStateOf(false) }
                AnimatedVisibility(visible = isStreaming) {
                    val rawStatus = streamingStatus.ifBlank { "Generating reply…" }
                    val (statusHeadline, statusDetail) = remember(rawStatus) {
                        when {
                            rawStatus.contains('\n') -> {
                                val first = rawStatus.substringBefore('\n').trim()
                                val rest = rawStatus.substringAfter('\n').trim()
                                first to rest
                            }
                            rawStatus.contains(" — ") && rawStatus.length > 56 -> {
                                val first = rawStatus.substringBefore(" — ").trim()
                                val rest = rawStatus.substringAfter(" — ").trim()
                                first to rest
                            }
                            else -> rawStatus to ""
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.sheetBg.copy(alpha = 0.78f))
                            .border(0.75.dp, p.border.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = if (statusExpanded) "Collapse status" else "Expand status") { statusExpanded = !statusExpanded }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                KissStreamingPulseDot(color = p.accent)
                                BasicText(
                                    text = statusHeadline,
                                    maxLines = if (statusDetail.isEmpty() && statusExpanded) 6 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiBodyStyle.copy(
                                        color = p.accent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                            if (statusDetail.isNotEmpty() || statusHeadline.length > 72) {
                                KissVectorIcon(
                                    icon = if (statusExpanded) KissIcon.CHEVRON_UP else KissIcon.CHEVRON_DOWN,
                                    tint = p.mutedInk,
                                    size = 11.dp
                                )
                            }
                        }
                        if (statusDetail.isNotEmpty()) {
                            BasicText(
                                text = statusDetail,
                                maxLines = if (statusExpanded) 12 else 3,
                                overflow = TextOverflow.Ellipsis,
                                style = p.monoStyle.copy(
                                    color = p.narrationInk,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                ),
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }

                // 2. Queued Prompts Bar (`⏳ Queue` mode)
                AnimatedVisibility(visible = queuedPrompts.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.elevatedBg.copy(alpha = 0.7f))
                            .border(0.75.dp, p.accentSecondary, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        BasicText(
                            text = "Queued Next (${queuedPrompts.size} — auto-sends after reply):",
                            style = p.uiLabelStyle.copy(color = p.accentSecondary, fontWeight = FontWeight.SemiBold)
                        )
                        queuedPrompts.forEach { qItem ->
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                BasicText(
                                    text = "• ${qItem.text}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiBodyStyle.copy(fontSize = 12.sp),
                                    modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    SepiaButton(
                                        text = "⚡ Send now",
                                        compact = true,
                                        style = SepiaButtonStyle.SECONDARY,
                                        onClick = { onSteerQueuedPromptNow(qItem) }
                                    )
                                    SepiaButton(
                                        text = "×",
                                        contentDescription = "Remove queued prompt",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = { onRemoveQueuedPrompt(qItem.id) }
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Collapsible Capabilities, Workspace Mount & 16-Tool Harness Tray (`⚙ Tools`)
                var showAllHarnessToolsInComposer by remember { mutableStateOf(false) }
                val composerPermissionDefaults = AgentPermissionResolver.defaults(activeAssistant)
                val composerPermissionOverrides = activeSession.permissionOverrides ?: AgentPermissionResolver.legacyOverrides(activeSession, activeProfile)
                val composerToolPolicy = composerPermissionOverrides.applyTo(composerPermissionDefaults)
                val effectiveComposerWsPath = composerToolPolicy.workspacePath
                val effectiveComposerWsMode = composerToolPolicy.workspaceMode
                val allHarnessTools = remember { WorkspaceHarness.allTools() }
                val activeHarnessToolNames = remember(composerToolPolicy, isGoalModeEnabled) {
                    WorkspaceHarness.activeToolsForMode(
                        effectiveComposerWsMode, effectiveComposerWsPath, composerToolPolicy.allowShell,
                        goalModeActive = isGoalModeEnabled, enableWebTools = composerToolPolicy.webAccess,
                        policy = composerToolPolicy
                    ).map { it.name }.toSet()
                }

                AnimatedVisibility(
                    visible = showComposerToolsDrawer,
                    enter = expandVertically(spring(stiffness = 560f, dampingRatio = Spring.DampingRatioNoBouncy)) + fadeIn(tween(110)),
                    exit = shrinkVertically(spring(stiffness = 560f, dampingRatio = Spring.DampingRatioNoBouncy)) + fadeOut(tween(95))
                ) {
                    BoxWithConstraints {
                        val drawerMaxHeight = (maxHeight * 0.5f).coerceAtMost(420.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = drawerMaxHeight)
                                .verticalScroll(rememberScrollState())
                                .clip(RoundedCornerShape(8.dp))
                                .background(p.sheetBg.copy(alpha = 0.65f))
                                .border(0.75.dp, p.border.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AgentPermissionEditor(
                                defaults = composerPermissionDefaults, overrides = composerPermissionOverrides,
                                onChange = { value -> onUpdateSession { it.copy(permissionOverrides = value) } },
                                onReset = { onUpdateSession { it.copy(permissionOverrides = AgentPermissionOverrides()) } }
                            )
                            SepiaDivider()
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SepiaChip(
                                    label = if (isGoalModeEnabled) "🎯 Goal Mode: ON" else "🎯 Goal Mode",
                                    selected = isGoalModeEnabled,
                                    onClick = onToggleGoalMode
                                )
                                SepiaChip(
                                    label = if (isLiveVoiceMode) "🔊 Live Voice: ON" else "🎙 Live Voice",
                                    selected = isLiveVoiceMode,
                                    onClick = onToggleLiveVoiceMode
                                )
                                if (activeProfile.protocol == ProviderProtocol.GEMINI_INTERACTIONS) {
                                    SepiaChip(
                                        label = "💻 Code exec",
                                        selected = activeProfile.enableCodeExecution,
                                        onClick = { onUpdateProfile { it.copy(enableCodeExecution = !it.enableCodeExecution) } }
                                    )
                                    SepiaChip(
                                        label = "🔗 URL context",
                                        selected = activeProfile.enableUrlContext,
                                        onClick = { onUpdateProfile { it.copy(enableUrlContext = !it.enableUrlContext) } }
                                    )
                                }
                                SepiaChip(
                                    label = if (showAllHarnessToolsInComposer) {
                                        "🔧 ${activeHarnessToolNames.size}/${allHarnessTools.size} Tools ▴"
                                    } else {
                                        "🔧 ${activeHarnessToolNames.size}/${allHarnessTools.size} Tools ▾"
                                    },
                                    selected = showAllHarnessToolsInComposer || activeHarnessToolNames.isNotEmpty(),
                                    onClick = { showAllHarnessToolsInComposer = !showAllHarnessToolsInComposer }
                                )
                                SepiaChip(
                                    label = if (showPrefillBox) "▾ Start the reply with: on" else "▸ Start the reply with…",
                                    selected = showPrefillBox || assistantPrefillInput.isNotBlank(),
                                    onClick = onTogglePrefillBox
                                )
                            }
                            AnimatedVisibility(visible = showAllHarnessToolsInComposer) {
                                AgentToolRegistry(
                                    policy = composerToolPolicy, overrides = composerPermissionOverrides,
                                    onChange = { value -> onUpdateSession { it.copy(permissionOverrides = value) } },
                                    goalModeActive = isGoalModeEnabled
                                )
                            }
                            SepiaDivider()
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BasicText(
                                    text = "Co-Writing helpers:",
                                    style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.padding(top = 5.dp, end = 4.dp)
                                )
                                SepiaButton(
                                    text = "✍ Draft my reply",
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = {
                                        onComposerToolsDrawerChange(false)
                                        onImpersonateDraft()
                                    }
                                )
                                SepiaButton(
                                    text = "🧭 Guide next reply",
                                    compact = true,
                                    enabled = hasTypedInput,
                                    style = SepiaButtonStyle.SECONDARY,
                                    onClick = {
                                        onComposerToolsDrawerChange(false)
                                        onSendGuidedDirection()
                                    }
                                )
                            }
                        }
                    }
                }

                if (pendingAttachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pendingAttachments.forEach { att ->
                            val mime = att.mimeType.lowercase()
                            val lowerName = att.fileName.lowercase()
                            val isImg = mime.startsWith("image/")
                            val attKissIcon = when {
                                isImg -> KissIcon.IMAGE
                                mime.startsWith("video/") -> KissIcon.VIDEO
                                mime.startsWith("audio/") -> KissIcon.AUDIO
                                mime == "application/pdf" || lowerName.endsWith(".pdf") -> KissIcon.FILE_TEXT
                                else -> KissIcon.PAPERCLIP
                            }
                            val thumbBmp = rememberFileImage(if (isImg) att.localFilePath else "").bitmap

                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(p.cardBg)
                                    .border(0.75.dp, p.accent.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button, onClickLabel = "Open attachment") { onInspectAttachment(att) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (thumbBmp != null) {
                                    Image(
                                        bitmap = thumbBmp,
                                        contentDescription = att.fileName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .border(0.75.dp, p.border, RoundedCornerShape(6.dp))
                                    )
                                } else {
                                    KissVectorIcon(
                                        icon = attKissIcon,
                                        tint = p.accent,
                                        size = 13.5.dp
                                    )
                                }
                                BasicText(
                                    text = att.fileName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.widthIn(max = 180.dp)
                                )
                                SepiaButton(
                                    text = "×",
                                    contentDescription = "Remove attachment",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { onRemoveAttachment(att.id) }
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = showPrefillBox) {
                    SepiaTextField(
                        value = assistantPrefillInput,
                        onValueChange = onAssistantPrefillChange,
                        placeholder = "Start the AI's reply with these exact words…",
                        singleLine = false,
                        maxLines = 3,
                        minHeight = 36.dp,
                        maxHeight = 84.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Slash-command suggestions while typing `/`. They close at the first space (`/goal `), so they
                // never get in the way of typing the argument.
                val trimmedSlashPrefix = userPromptInput.trimStart()
                val showSlashSuggestions = trimmedSlashPrefix.startsWith("/") &&
                    !trimmedSlashPrefix.contains('\n') &&
                    !trimmedSlashPrefix.contains(' ')
                val matchingSlashCommands = remember(trimmedSlashPrefix, showSlashSuggestions) {
                    if (!showSlashSuggestions) emptyList()
                    else {
                        val cmdToken = trimmedSlashPrefix.lowercase()
                        BUILTIN_SLASH_COMMANDS.filter { spec ->
                            spec.command.startsWith(cmdToken)
                        }
                    }
                }
                AnimatedVisibility(
                    visible = matchingSlashCommands.isNotEmpty(),
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(130)),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(110))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 224.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.sheetBg.copy(alpha = 0.82f))
                            .border(0.75.dp, p.border.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        matchingSlashCommands.forEach { spec ->
                            val isExact = trimmedSlashPrefix.equals(spec.command, ignoreCase = true)
                            val rowInteraction = remember { MutableInteractionSource() }
                            val isHovered by rowInteraction.collectIsHoveredAsState()
                            val isPressed by rowInteraction.collectIsPressedAsState()
                            val parsedCmdIcon = remember(spec.label) {
                                parseKissLabelIcons(spec.label).leadingIcon ?: KissIcon.SPARKLES
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isExact || isPressed -> p.accent.copy(alpha = 0.14f)
                                            isHovered -> p.accent.copy(alpha = 0.08f)
                                            else -> p.cardBg.copy(alpha = 0.72f)
                                        }
                                    )
                                    .border(
                                        width = 0.75.dp,
                                        color = if (isExact || isHovered) p.accent.copy(alpha = 0.55f) else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .kissClickable(
                                        pressScale = 0.99f,
                                        hoverScale = 1.004f,
                                        interactionSource = rowInteraction,
                                        onClick = {
                                            if (spec.takesArgument) {
                                                onUserPromptChange("${spec.command} ")
                                            } else {
                                                // Commands without an argument run on a single tap.
                                                onUserPromptChange("")
                                                when (spec.command) {
                                                    "/compact" -> onCompactContext()
                                                    "/summarize" -> onAutoSummarizeStory()
                                                    "/clear" -> onRequestClearCurrentChat()
                                                    "/continue" -> {
                                                        val lastAsst = activeSession.messages.indexOfLast { it.role == MessageRole.ASSISTANT }
                                                        if (lastAsst >= 0) onContinueAssistantNode(lastAsst)
                                                    }
                                                    "/rewind" -> {
                                                        if (activeSession.messages.isNotEmpty()) {
                                                            onRewindToNode(activeSession.messages.lastIndex)
                                                        }
                                                    }
                                                }
                                            }
                                        }
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
                                    KissVectorIcon(
                                        icon = parsedCmdIcon,
                                        tint = p.accent,
                                        size = 13.5.dp
                                    )
                                    if (win.isCompact) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(1.dp)
                                        ) {
                                            BasicText(
                                                text = spec.command,
                                                style = p.monoStyle.copy(
                                                    color = p.accent,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                            )
                                            BasicText(
                                                text = spec.description,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = p.uiBodyStyle.copy(
                                                    color = p.primaryInk,
                                                    fontSize = 12.sp
                                                )
                                            )
                                        }
                                    } else {
                                        BasicText(
                                            text = spec.command,
                                            style = p.monoStyle.copy(
                                                color = p.accent,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        )
                                        BasicText(
                                            text = spec.description,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiBodyStyle.copy(
                                                color = p.primaryInk,
                                                fontSize = 12.sp
                                            )
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(p.elevatedBg.copy(alpha = 0.7f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                                    ) {
                                        KissVectorIcon(
                                            icon = if (spec.takesArgument) KissIcon.ARROW_RIGHT else KissIcon.SEND_ENTER,
                                            tint = p.narrationInk,
                                            size = 10.dp
                                        )
                                        BasicText(
                                            text = if (spec.takesArgument) "arg" else "Run",
                                            style = p.monoStyle.copy(
                                                fontSize = 11.sp,
                                                color = p.narrationInk,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // `@` suggestions: workspace files, attachments and lore entries.
                val activeAtQuery: String? = remember(userPromptInput) {
                    val lastAt = userPromptInput.lastIndexOf('@')
                    if (lastAt == -1) null
                    else if (lastAt > 0 && !userPromptInput[lastAt - 1].isWhitespace()) null
                    else {
                        val afterAt = userPromptInput.substring(lastAt + 1)
                        if (afterAt.contains(' ') || afterAt.contains('\n')) null else afterAt
                    }
                }
                val effectiveWorkspacePath = AgentPermissionResolver.resolve(activeSession, activeAssistant, activeProfile).workspacePath
                var workspaceFiles by remember(effectiveWorkspacePath) { mutableStateOf<List<String>>(emptyList()) }
                LaunchedEffect(effectiveWorkspacePath, activeAtQuery != null) {
                    if (activeAtQuery != null && effectiveWorkspacePath.isNotBlank() && workspaceFiles.isEmpty()) {
                        workspaceFiles = withContext(Dispatchers.Default) {
                            PlatformBridge.workspaceGlob(effectiveWorkspacePath, "*", maxResults = 160)
                                .getOrDefault("")
                                .lines()
                                .map { it.trim() }
                                .filter { it.isNotEmpty() && !it.startsWith("No files") && !it.endsWith("/") }
                                .map { it.substringBefore(" (").trim() }
                        }
                    }
                }
                val historyAttachments = remember(activeSession.id, activeSession.messages.size) {
                    activeSession.messages.flatMap { it.activeVariant.attachments }
                        .distinctBy { it.fileName }
                }
                val allLoreFragments = remember(appState.globalWorldBook, activeAssistant.fragments, activeSession.fragments) {
                    (appState.globalWorldBook + activeAssistant.fragments + activeSession.fragments).distinctBy { it.id }
                }
                val matchingAtItems = remember(activeAtQuery, workspaceFiles, historyAttachments, allLoreFragments) {
                    val q = activeAtQuery?.lowercase() ?: return@remember emptyList<Triple<String, String, () -> Unit>>()
                    val list = mutableListOf<Triple<String, String, () -> Unit>>()
                    fun replaceTrailingAt(replacement: String) {
                        val idx = userPromptInput.lastIndexOf('@')
                        if (idx >= 0) {
                            val nextInput = userPromptInput.substring(0, idx) + replacement
                            onUserPromptChange(nextInput)
                        }
                    }
                    if (q.isEmpty() || "file".contains(q) || "attach".contains(q) || "upload".contains(q)) {
                        list.add(
                            Triple(
                                "📎 @file",
                                "Pick & attach a local file, image, or PDF…",
                                {
                                    replaceTrailingAt("")
                                    onAddAttachment()
                                }
                            )
                        )
                    }
                    if (q.isEmpty() || "mount".contains(q) || "workspace".contains(q) || "folder".contains(q)) {
                        list.add(
                            Triple(
                                "📂 @mount",
                                if (effectiveWorkspacePath.isBlank()) {
                                    "Choose a folder for the agent to work in…"
                                } else {
                                    "Change the agent's folder ($effectiveWorkspacePath)…"
                                },
                                {
                                    replaceTrailingAt("")
                                    scope.launch {
                                        val picked = PlatformBridge.pickWorkspaceDirectory(effectiveWorkspacePath)
                                        if (!picked.isNullOrBlank()) {
                                            onUpdateSession {
                                                it.copy(
                                                    workspacePath = picked,
                                                    workspaceMode = if (it.workspaceMode == WorkspaceMode.OFF) WorkspaceMode.READ_ONLY else it.workspaceMode
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        )
                    }
                    workspaceFiles
                        .filter { q.isEmpty() || it.lowercase().contains(q) }
                        .take(18)
                        .forEach { relPath ->
                            val shortName = relPath.substringAfterLast('/')
                            list.add(
                                Triple(
                                    "📄 @$shortName",
                                    relPath,
                                    {
                                        onAttachWorkspaceFile(relPath)
                                        replaceTrailingAt("@$relPath ")
                                    }
                                )
                            )
                        }
                    historyAttachments
                        .filter { it.fileName.isNotBlank() && (q.isEmpty() || it.fileName.lowercase().contains(q)) }
                        .take(8)
                        .forEach { att ->
                            list.add(
                                Triple(
                                    "📎 @${att.fileName}",
                                    "Re-attach from this conversation (${att.mimeType})",
                                    {
                                        onReattachExistingAttachment(att)
                                        replaceTrailingAt("@${att.fileName} ")
                                    }
                                )
                            )
                        }
                    allLoreFragments
                        .filter { it.title.isNotBlank() && (q.isEmpty() || it.title.lowercase().contains(q)) }
                        .take(8)
                        .forEach { frag ->
                            list.add(
                                Triple(
                                    "📖 @${frag.title}",
                                    frag.content.take(72).replace('\n', ' '),
                                    {
                                        replaceTrailingAt("@${frag.title} ")
                                    }
                                )
                            )
                        }
                    list
                }
                AnimatedVisibility(
                    visible = matchingAtItems.isNotEmpty(),
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(130)),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(110))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 224.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.sheetBg.copy(alpha = 0.82f))
                            .border(0.75.dp, p.border.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        matchingAtItems.forEach { (badgeLabel, descText, action) ->
                            val rowInteraction = remember { MutableInteractionSource() }
                            val isHovered by rowInteraction.collectIsHoveredAsState()
                            val isPressed by rowInteraction.collectIsPressedAsState()
                            val parsedIcon = remember(badgeLabel) {
                                parseKissLabelIcons(badgeLabel).leadingIcon ?: KissIcon.FILE_TEXT
                            }
                            val cleanBadge = remember(badgeLabel) {
                                parseKissLabelIcons(badgeLabel).cleanText
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isPressed -> p.accent.copy(alpha = 0.14f)
                                            isHovered -> p.accent.copy(alpha = 0.08f)
                                            else -> p.cardBg.copy(alpha = 0.72f)
                                        }
                                    )
                                    .border(
                                        width = 0.75.dp,
                                        color = if (isHovered) p.accent.copy(alpha = 0.55f) else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .kissClickable(
                                        pressScale = 0.99f,
                                        hoverScale = 1.004f,
                                        interactionSource = rowInteraction,
                                        onClick = action
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
                                    KissVectorIcon(
                                        icon = parsedIcon,
                                        tint = p.accent,
                                        size = 13.5.dp
                                    )
                                    if (win.isCompact) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(1.dp)
                                        ) {
                                            BasicText(
                                                text = cleanBadge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = p.monoStyle.copy(
                                                    color = p.accent,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                            )
                                            BasicText(
                                                text = descText,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = p.uiBodyStyle.copy(
                                                    color = p.narrationInk,
                                                    fontSize = 11.sp
                                                )
                                            )
                                        }
                                    } else {
                                        BasicText(
                                            text = cleanBadge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.monoStyle.copy(
                                                color = p.accent,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        )
                                        BasicText(
                                            text = descText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiBodyStyle.copy(
                                                color = p.narrationInk,
                                                fontSize = 12.sp
                                            )
                                        )
                                    }
                                }
                                KissVectorIcon(
                                    icon = KissIcon.PLUS,
                                    tint = p.mutedInk,
                                    size = 11.5.dp
                                )
                            }
                        }
                    }
                }

                // 4. The text field: on desktop Enter sends and Shift+Enter adds a line.
                val targetName = activeSession.activeCard?.name?.takeIf { it.isNotBlank() }
                    ?: activeAssistant.name.takeIf { it.isNotBlank() }
                    ?: activeSession.modelOverride.ifBlank { activeProfile.defaultModel }.ifBlank { "AI" }
                SepiaTextField(
                    value = userPromptInput,
                    onValueChange = onUserPromptChange,
                    placeholder = if (isStreaming) {
                        "Type a follow-up to send now or queue for later…"
                    } else {
                        "Message $targetName…"
                    },
                    singleLine = false,
                    borderless = true,
                    maxLines = 6,
                    minHeight = 40.dp,
                    maxHeight = 148.dp,
                    modifier = Modifier.fillMaxWidth(),
                    onEnterKeySend = if (!PlatformBridge.isAndroid) {
                        { isCtrlOrMeta, isShift ->
                            val shouldTrigger = when (preferences.sendShortcut) {
                                SendShortcutMode.ENTER -> !isShift && !isCtrlOrMeta
                                SendShortcutMode.CTRL_ENTER -> isCtrlOrMeta && !isShift
                                SendShortcutMode.SHIFT_ENTER -> isShift && !isCtrlOrMeta
                            }
                            if (shouldTrigger) {
                                if (hasTypedInput) {
                                    if (isStreaming) onSteerNow() else onSend()
                                }
                                true
                            } else {
                                false
                            }
                        }
                    } else null,
                    onClipboardPaste = onPasteClipboardMedia
                )

                // Interactive Context Window Breakdown Popover (Opened by clicking the Circular Context Ring)
                AnimatedVisibility(visible = showContextBreakdownPopover) {
                    val canCompactNow = activeSession.messages.count { it.includedInContext } > 1 ||
                        activeSession.messages.any { it.includedInContext && it.activeVariant.toolCalls.isNotEmpty() }
                    ContextWindowBreakdownPopover(
                        breakdown = contextBreakdown,
                        sessionTotalCostUsd = sessionTotalCostUsd,
                        utilityUsageRecords = ownedUtilityRecords,
                        sessionCostLabel = sessionSpend.label,
                        isStreaming = isStreaming,
                        canCompact = canCompactNow,
                        canSummarize = activeSession.messages.isNotEmpty(),
                        onCompactContext = onCompactContext,
                        onSummarizeStory = onAutoSummarizeStory,
                        onOpenPricingSettings = onOpenPricingSettings,
                        onClose = { showContextBreakdownPopover = false }
                    )
                }
                SepiaDivider()

                // 5. Composer footer, sized for phone and desktop widths.
                val hasActiveTools = activeProfile.enableWebSearch ||
                    activeProfile.enableCodeExecution ||
                    activeProfile.enableUrlContext ||
                    activeSession.workspaceMode != WorkspaceMode.OFF ||
                    isGoalModeEnabled ||
                    isLiveVoiceMode ||
                    showPrefillBox

                val rawEffort = activeProfile.thinkingEffort.ifBlank { "default" }.lowercase()
                val thinkingLabel = if (preferences.showReasoningLevelOnButton) {
                    "🧠 ${rawEffort.replaceFirstChar { it.uppercase() }}"
                } else {
                    "🧠"
                }

                val toolsChipLabel = if (win.isCompact) {
                    if (hasActiveTools) "🔧 •" else "🔧"
                } else {
                    if (hasActiveTools) "🔧 Tools •" else "🔧 Tools"
                }

                val activeComposerModelId = activeSession.modelOverride
                    .ifBlank { activeProfile.defaultModel }
                val activeModalityProfile = remember(
                    activeComposerModelId,
                    activeProfile.endpointPathOverride,
                    activeProfile.customModelSpecs
                ) {
                    AiProviderEngine.resolveModelModalityProfile(activeProfile, activeComposerModelId)
                }
                val leftChipsComposable: @Composable (Modifier) -> Unit = { rowMod ->
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = rowMod,
                        horizontalArrangement = Arrangement.spacedBy(if (win.isCompact) 3.dp else 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SepiaChip(
                            label = "📎",
                            contentDescription = "Attach files",
                            selected = pendingAttachments.isNotEmpty(),
                            onClick = onAddAttachment
                        )
                        SepiaChip(
                            label = "🎙",
                            contentDescription = "Voice input",
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
                                label = if (activeProfile.videoGenerateAudio) "🔊 Audio" else "🔇 Mute",
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
                            contentDescription = "Tools",
                            hint = "Web search, workspace and other tools for this chat",
                            selected = showComposerToolsDrawer || hasActiveTools,
                            onClick = { onComposerToolsDrawerChange(!showComposerToolsDrawer) }
                        )
                    }
                }

                if (win.isCompact && isStreaming) {
                    // When streaming on compact mobile, use 2 full-width SpaceBetween rows so Stop/Queue/Steer never cramp or leave dead space
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            leftChipsComposable(Modifier.weight(1f))
                            if (preferences.showTokenAndContextStats) {
                                Spacer(Modifier.width(6.dp))
                                SepiaContextGaugeRing(
                                    usedTokens = liveContextTokens,
                                    contextLimitTokens = contextBreakdown.contextLimitTokens,
                                    sessionCostUsd = sessionTotalCostUsd,
                                    sessionCostLabel = sessionSpend.label,
                                    onClick = { showContextBreakdownPopover = !showContextBreakdownPopover }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SepiaButton(
                                text = "■ Stop",
                                hint = "Stop this reply",
                                compact = true,
                                style = SepiaButtonStyle.DANGER,
                                onClick = onStopStream
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SepiaButton(
                                    text = "⏳ Queue",
                                    hint = "Send this after the current reply finishes",
                                    compact = true,
                                    enabled = hasTypedInput,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = onQueuePrompt
                                )
                                SepiaButton(
                                    text = "⚡ Send now ↵",
                                    hint = "Send this now to guide the current reply",
                                    compact = true,
                                    enabled = hasTypedInput,
                                    style = SepiaButtonStyle.PRIMARY,
                                    onClick = onSteerNow
                                )
                            }
                        }
                    }
                } else {
                    // One full-width row on desktop, and on phones while idle.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        leftChipsComposable(Modifier.weight(1f))

                        Spacer(Modifier.width(6.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (preferences.showTokenAndContextStats) {
                                SepiaContextGaugeRing(
                                    usedTokens = liveContextTokens,
                                    contextLimitTokens = contextBreakdown.contextLimitTokens,
                                    sessionCostUsd = sessionTotalCostUsd,
                                    sessionCostLabel = sessionSpend.label,
                                    onClick = { showContextBreakdownPopover = !showContextBreakdownPopover }
                                )
                            }

                            if (isStreaming) {
                                SepiaButton(
                                    text = "■ Stop",
                                    hint = "Stop this reply",
                                    compact = true,
                                    style = SepiaButtonStyle.DANGER,
                                    onClick = onStopStream
                                )
                                SepiaButton(
                                    text = "⏳ Queue",
                                    hint = "Send this after the current reply finishes",
                                    compact = true,
                                    enabled = hasTypedInput,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = onQueuePrompt
                                )
                                SepiaButton(
                                    text = "⚡ Send now ↵",
                                    hint = "Send this now to guide the current reply",
                                    compact = true,
                                    enabled = hasTypedInput,
                                    style = SepiaButtonStyle.PRIMARY,
                                    onClick = onSteerNow
                                )
                            } else {
                                SepiaButton(
                                    text = "Send ↵",
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
        }
    }
}
