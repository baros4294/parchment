package io.parchment.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AuthHeaderMode
import io.parchment.ai.model.CharacterOrScenarioCard
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.SystemPromptPreset
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.model.ViewMode
import io.parchment.ai.model.WorkspaceMode
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.StreamUpdate
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.protocol.CardAndPromptCodec
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class ActiveModalTab {
    NONE,
    PROMPTS_AND_CARDS,
    PROVIDERS_AND_PROXY,
    THEME_AND_STORAGE
}

@Composable
fun ParchmentApp() {
    val scope = rememberCoroutineScope()

    var appState by remember { mutableStateOf(loadOrCreateDefaultAppState()) }
    var activeModal by remember { mutableStateOf(ActiveModalTab.NONE) }
    var showSidebar by remember { mutableStateOf(!PlatformBridge.isAndroid) }
    var showSessionInspector by remember { mutableStateOf(false) }
    var bannerNotification by remember { mutableStateOf("") }

    // Streaming state
    var isStreaming by remember { mutableStateOf(false) }
    var streamingStatus by remember { mutableStateOf("") }
    var streamingInteractionId by remember { mutableStateOf("") }
    var activeStreamJob by remember { mutableStateOf<Job?>(null) }

    // Composer inputs
    var userPromptInput by remember { mutableStateOf("") }
    var assistantPrefillInput by remember { mutableStateOf("") }
    var showPrefillBox by remember { mutableStateOf(false) }

    fun persist(updated: AppStateSnapshot) {
        appState = updated
        runCatching {
            val encoded = CardAndPromptCodec.prettyJson.encodeToString(AppStateSnapshot.serializer(), updated)
            PlatformBridge.saveAppStateJson(encoded)
        }
    }

    val activeProfile = appState.profiles.find { it.id == appState.activeProfileId }
        ?: appState.profiles.first()
    val activeSession = appState.sessions.find { it.id == appState.activeSessionId }
        ?: appState.sessions.first()

    fun updateActiveSession(transform: (ChatSession) -> ChatSession) {
        val newSessions = appState.sessions.map {
            if (it.id == activeSession.id) transform(it).copy(updatedAt = PlatformBridge.currentTimeMillis()) else it
        }
        persist(appState.copy(sessions = newSessions))
    }

    fun updateActiveProfile(transform: (ProviderProfile) -> ProviderProfile) {
        val newProfiles = appState.profiles.map {
            if (it.id == activeProfile.id) transform(it) else it
        }
        persist(appState.copy(profiles = newProfiles))
    }

    // Automatically fetch models when API key or custom proxy Base URL is configured and savedModels is empty
    LaunchedEffect(activeProfile.id, activeProfile.baseUrl, activeProfile.apiKey, activeProfile.autoFetchModels) {
        val hasCredentialsOrCustomProxy = activeProfile.apiKey.isNotBlank() ||
            activeProfile.baseUrl.trimEnd('/') != activeProfile.protocol.defaultBaseUrl.trimEnd('/')
        if (activeProfile.autoFetchModels && hasCredentialsOrCustomProxy && activeProfile.savedModels.isEmpty()) {
            AiProviderEngine.fetchAvailableModels(activeProfile).onSuccess { fetched ->
                if (fetched.isNotEmpty()) {
                    val chosenDefault = activeProfile.defaultModel.ifBlank { fetched.first() }
                    updateActiveProfile {
                        it.copy(
                            savedModels = fetched,
                            defaultModel = chosenDefault
                        )
                    }
                    if (activeSession.modelOverride.isBlank()) {
                        updateActiveSession { it.copy(modelOverride = chosenDefault) }
                    }
                }
            }
        }
    }

    fun triggerGeneration(
        historyForCall: List<MessageNode>,
        targetAssistantNodeId: String,
        targetVariantId: String,
        continuePrefix: String = ""
    ) {
        activeStreamJob?.cancel()
        isStreaming = true
        streamingStatus = "Connecting to ${activeProfile.protocol.displayName}..."
        streamingInteractionId = ""

        val modelName = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }

        activeStreamJob = scope.launch {
            var accumulatedContent = continuePrefix
            var accumulatedThinking = ""
            var accumulatedSignature = ""
            val accumulatedTools = mutableListOf<ToolCallRecord>()
            var rawReq = ""
            var rawResp = ""

            fun patchTargetVariant(updater: (MessageVariant) -> MessageVariant) {
                updateActiveSession { sess ->
                    sess.copy(
                        messages = sess.messages.map { node ->
                            if (node.id != targetAssistantNodeId) node
                            else {
                                val updatedVariants = node.variants.map { v ->
                                    if (v.id == targetVariantId) updater(v) else v
                                }
                                node.copy(variants = updatedVariants)
                            }
                        }
                    )
                }
            }

            AiProviderEngine.streamGeneration(
                profile = activeProfile,
                session = activeSession,
                historyNodes = historyForCall,
                continueFromAssistantPrefix = continuePrefix
            ).collect { update ->
                when (update) {
                    is StreamUpdate.Status -> {
                        streamingStatus = update.message
                        if (update.interactionId.isNotBlank()) {
                            streamingInteractionId = update.interactionId
                        }
                    }
                    is StreamUpdate.ThinkingDelta -> {
                        accumulatedThinking += update.delta
                        accumulatedSignature += update.signatureDelta
                        val wordCount = accumulatedContent.split(Regex("\\s+")).count { it.isNotBlank() }
                        streamingStatus = "Thinking & drafting ($modelName • $wordCount words)..."
                        patchTargetVariant { v ->
                            v.copy(
                                thinkingSummary = accumulatedThinking,
                                thinkingSignature = accumulatedSignature
                            )
                        }
                    }
                    is StreamUpdate.ContentDelta -> {
                        accumulatedContent += update.delta
                        val wordCount = accumulatedContent.split(Regex("\\s+")).count { it.isNotBlank() }
                        streamingStatus = "Writing ($modelName • +$wordCount words)..."
                        patchTargetVariant { v ->
                            v.copy(content = accumulatedContent)
                        }
                    }
                    is StreamUpdate.ToolCallEvent -> {
                        accumulatedTools.add(update.toolCall)
                        streamingStatus = "Executed workspace tool: ${update.toolCall.name}"
                        patchTargetVariant { v ->
                            v.copy(toolCalls = accumulatedTools.toList())
                        }
                    }
                    is StreamUpdate.RawWireSnapshot -> {
                        rawReq = update.requestJson
                        rawResp = update.latestResponseChunk
                        patchTargetVariant { v ->
                            v.copy(
                                rawRequestPreview = rawReq,
                                rawResponsePreview = rawResp
                            )
                        }
                    }
                    is StreamUpdate.Completed -> {
                        patchTargetVariant { v ->
                            v.copy(
                                content = update.fullContent,
                                thinkingSummary = update.fullThinking,
                                thinkingSignature = update.thinkingSignature,
                                toolCalls = update.toolCalls,
                                providerInteractionId = update.providerInteractionId,
                                providerProtocol = activeProfile.protocol,
                                modelUsed = modelName,
                                tokenMetrics = update.metrics,
                                rawRequestPreview = update.rawRequest,
                                rawResponsePreview = update.rawResponse
                            )
                        }
                        isStreaming = false
                        streamingStatus = ""
                    }
                    is StreamUpdate.Error -> {
                        patchTargetVariant { v ->
                            val errorNotice = if (accumulatedContent.isBlank()) {
                                "**[Provider Error: ${update.message}]**\n\nCheck your API Key, Base URL Proxy (`${activeProfile.baseUrl}`), or model ID (`$modelName`) in **Providers & Proxy**."
                            } else {
                                "$accumulatedContent\n\n*[Stream interrupted: ${update.message}]*"
                            }
                            v.copy(
                                content = errorNotice,
                                rawRequestPreview = rawReq,
                                rawResponsePreview = update.rawDetails.ifBlank { rawResp }
                            )
                        }
                        isStreaming = false
                        streamingStatus = ""
                    }
                }
            }
        }
    }

    fun sendUserTurn() {
        val trimmedUser = userPromptInput.trim()
        val prefill = assistantPrefillInput.trim()
        if (trimmedUser.isEmpty() && prefill.isEmpty()) return

        val now = PlatformBridge.currentTimeMillis()
        val updatedMessages = activeSession.messages.toMutableList()

        if (trimmedUser.isNotEmpty()) {
            val userVar = MessageVariant(
                id = "var-$now-u",
                content = trimmedUser,
                createdAt = now
            )
            updatedMessages.add(
                MessageNode(
                    id = "node-$now-u",
                    role = MessageRole.USER,
                    variants = listOf(userVar),
                    activeVariantIndex = 0
                )
            )
        }

        val historySnapshot = updatedMessages.toList()
        val assistantVarId = "var-${now + 1}-a"
        val assistantNodeId = "node-${now + 1}-a"
        val modelName = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }

        val initialAssistantVar = MessageVariant(
            id = assistantVarId,
            content = prefill,
            modelUsed = modelName,
            providerProtocol = activeProfile.protocol,
            createdAt = now + 1
        )
        updatedMessages.add(
            MessageNode(
                id = assistantNodeId,
                role = MessageRole.ASSISTANT,
                variants = listOf(initialAssistantVar),
                activeVariantIndex = 0
            )
        )

        val newTitle = if (activeSession.messages.isEmpty() && trimmedUser.isNotEmpty()) {
            trimmedUser.take(42).let { if (trimmedUser.length > 42) "$it…" else it }
        } else activeSession.title

        updateActiveSession {
            it.copy(title = newTitle, messages = updatedMessages)
        }

        userPromptInput = ""
        assistantPrefillInput = ""

        triggerGeneration(
            historyForCall = historySnapshot,
            targetAssistantNodeId = assistantNodeId,
            targetVariantId = assistantVarId,
            continuePrefix = prefill
        )
    }

    ProvideParchmentTheme(config = appState.theme) {
        val p = LocalParchmentPalette.current

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(p.canvasBg)
        ) {
            val isCompactScreen = maxWidth < 760.dp

            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Top Editorial Bar
                TopEditorialBar(
                    activeSession = activeSession,
                    activeProfile = activeProfile,
                    showSidebar = showSidebar,
                    activeModal = activeModal,
                    onToggleSidebar = { showSidebar = !showSidebar },
                    onToggleViewMode = {
                        val next = if (activeSession.viewMode == ViewMode.MANUSCRIPT) {
                            ViewMode.EDITORIAL_TURNS
                        } else {
                            ViewMode.MANUSCRIPT
                        }
                        updateActiveSession { it.copy(viewMode = next) }
                    },
                    onCycleWorkspaceMode = {
                        val next = when (activeSession.workspaceMode) {
                            WorkspaceMode.OFF -> WorkspaceMode.READ_ONLY
                            WorkspaceMode.READ_ONLY -> WorkspaceMode.READ_WRITE
                            WorkspaceMode.READ_WRITE -> WorkspaceMode.OFF
                        }
                        updateActiveSession { it.copy(workspaceMode = next) }
                        if (next != WorkspaceMode.OFF && activeSession.workspacePath.isBlank()) {
                            scope.launch {
                                val picked = PlatformBridge.pickWorkspaceDirectory("")
                                if (!picked.isNullOrBlank()) {
                                    updateActiveSession { it.copy(workspacePath = picked) }
                                }
                            }
                        }
                    },
                    onSelectModal = { tab ->
                        activeModal = if (activeModal == tab) ActiveModalTab.NONE else tab
                    }
                )

                SepiaDivider()

                if (bannerNotification.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(p.elevatedBg)
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = bannerNotification,
                            style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.Medium)
                        )
                        SepiaButton(
                            text = "Dismiss",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { bannerNotification = "" }
                        )
                    }
                    SepiaDivider()
                }

                // 2. Main Content Area (Sidebar + Manuscript Canvas / Active Modal)
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (showSidebar) {
                        SessionSidebar(
                            appState = appState,
                            activeSession = activeSession,
                            activeProfile = activeProfile,
                            modifier = Modifier
                                .width(if (isCompactScreen) 265.dp else 290.dp)
                                .fillMaxHeight(),
                            onSelectSession = { id ->
                                persist(appState.copy(activeSessionId = id))
                                if (isCompactScreen) showSidebar = false
                            },
                            onNewSession = {
                                val now = PlatformBridge.currentTimeMillis()
                                val newSess = ChatSession(
                                    id = "sess-$now",
                                    title = "Untitled Manuscript",
                                    createdAt = now,
                                    updatedAt = now,
                                    profileId = activeProfile.id,
                                    modelOverride = activeProfile.defaultModel,
                                    activeCard = activeSession.activeCard
                                )
                                val initialMessages = if (newSess.activeCard?.firstMessage?.isNotBlank() == true) {
                                    val firstGreeting = newSess.activeCard.firstMessage
                                    val allGreetings = listOf(firstGreeting) + newSess.activeCard.alternateGreetings
                                    listOf(
                                        MessageNode(
                                            id = "node-greeting-$now",
                                            role = MessageRole.ASSISTANT,
                                            variants = allGreetings.mapIndexed { idx, g ->
                                                MessageVariant(
                                                    id = "var-greeting-$idx-$now",
                                                    content = g.replace("{{char}}", newSess.activeCard.name)
                                                        .replace("{{user}}", newSess.userName),
                                                    modelUsed = "Card Greeting #${idx + 1}",
                                                    createdAt = now
                                                )
                                            }
                                        )
                                    )
                                } else emptyList()

                                val created = newSess.copy(messages = initialMessages)
                                persist(
                                    appState.copy(
                                        sessions = listOf(created) + appState.sessions,
                                        activeSessionId = created.id
                                    )
                                )
                                if (isCompactScreen) showSidebar = false
                            },
                            onDeleteSession = { targetId ->
                                val remaining = appState.sessions.filterNot { it.id == targetId }
                                if (remaining.isNotEmpty()) {
                                    persist(
                                        appState.copy(
                                            sessions = remaining,
                                            activeSessionId = remaining.first().id
                                        )
                                    )
                                }
                            },
                            onQuickSwitchProfile = { profId ->
                                persist(appState.copy(activeProfileId = profId))
                            }
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(p.border)
                        )
                    }

                    // Right Pane: Active Modal Drawer OR Manuscript Viewport
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (activeModal) {
                            ActiveModalTab.PROMPTS_AND_CARDS -> {
                                PromptsAndCardsPanel(
                                    appState = appState,
                                    activeSession = activeSession,
                                    onUpdateSession = ::updateActiveSession,
                                    onSaveCardToLibrary = { card ->
                                        val updatedCards = listOf(card) + appState.savedCards.filterNot { it.name == card.name }
                                        persist(appState.copy(savedCards = updatedCards))
                                    },
                                    onSavePresetToLibrary = { preset ->
                                        val updatedPresets = listOf(preset) + appState.systemPromptPresets.filterNot { it.id == preset.id }
                                        persist(appState.copy(systemPromptPresets = updatedPresets))
                                    },
                                    onApplyTheme = { importedTheme ->
                                        persist(appState.copy(theme = importedTheme))
                                    },
                                    onNotify = { bannerNotification = it },
                                    onClose = { activeModal = ActiveModalTab.NONE }
                                )
                            }

                            ActiveModalTab.PROVIDERS_AND_PROXY -> {
                                ProvidersAndProxyPanel(
                                    appState = appState,
                                    activeProfile = activeProfile,
                                    activeSession = activeSession,
                                    onSelectProfile = { id -> persist(appState.copy(activeProfileId = id)) },
                                    onUpdateProfile = ::updateActiveProfile,
                                    onUpdateSession = ::updateActiveSession,
                                    onAddProfile = { protocol ->
                                        val now = PlatformBridge.currentTimeMillis()
                                        val newProf = ProviderProfile(
                                            id = "prof-$now",
                                            name = "Custom ${protocol.displayName.substringBefore(" ")} Proxy",
                                            protocol = protocol
                                        )
                                        persist(
                                            appState.copy(
                                                profiles = appState.profiles + newProf,
                                                activeProfileId = newProf.id
                                            )
                                        )
                                    },
                                    onNotify = { bannerNotification = it },
                                    onClose = { activeModal = ActiveModalTab.NONE }
                                )
                            }

                            ActiveModalTab.THEME_AND_STORAGE -> {
                                ThemeAndStoragePanel(
                                    theme = appState.theme,
                                    onUpdateTheme = { newTheme -> persist(appState.copy(theme = newTheme)) },
                                    onWipeAllData = {
                                        PlatformBridge.wipeAllLocalData()
                                        appState = createDefaultAppState()
                                        bannerNotification = "All local data wiped cleanly. Reset to fresh Parchment defaults."
                                    },
                                    onNotify = { bannerNotification = it },
                                    onClose = { activeModal = ActiveModalTab.NONE }
                                )
                            }

                            ActiveModalTab.NONE -> {
                                ManuscriptViewport(
                                    activeSession = activeSession,
                                    activeProfile = activeProfile,
                                    showSessionInspector = showSessionInspector,
                                    onToggleSessionInspector = { showSessionInspector = !showSessionInspector },
                                    isStreaming = isStreaming,
                                    streamingStatus = streamingStatus,
                                    streamingInteractionId = streamingInteractionId,
                                    userPromptInput = userPromptInput,
                                    onUserPromptChange = { userPromptInput = it },
                                    assistantPrefillInput = assistantPrefillInput,
                                    onAssistantPrefillChange = { assistantPrefillInput = it },
                                    showPrefillBox = showPrefillBox,
                                    onTogglePrefillBox = { showPrefillBox = !showPrefillBox },
                                    onSend = ::sendUserTurn,
                                    onStopStream = {
                                        activeStreamJob?.cancel()
                                        if (streamingInteractionId.isNotBlank() && activeProfile.useBackgroundLongDecode) {
                                            scope.launch {
                                                AiProviderEngine.cancelBackgroundInteraction(activeProfile, streamingInteractionId)
                                            }
                                        }
                                        isStreaming = false
                                        streamingStatus = ""
                                    },
                                    onUpdateSession = ::updateActiveSession,
                                    onUpdateProfile = ::updateActiveProfile,
                                    onRegenerateNode = { nodeIndex ->
                                        val targetNode = activeSession.messages.getOrNull(nodeIndex) ?: return@ManuscriptViewport
                                        if (targetNode.role != MessageRole.ASSISTANT) return@ManuscriptViewport
                                        val historyBefore = activeSession.messages.subList(0, nodeIndex)
                                        val now = PlatformBridge.currentTimeMillis()
                                        val newVarId = "var-$now-regen"
                                        val newVar = MessageVariant(
                                            id = newVarId,
                                            content = "",
                                            modelUsed = activeSession.modelOverride.ifBlank { activeProfile.defaultModel },
                                            providerProtocol = activeProfile.protocol,
                                            createdAt = now
                                        )
                                        val newVariants = targetNode.variants + newVar
                                        val newIdx = newVariants.lastIndex
                                        updateActiveSession { sess ->
                                            sess.copy(
                                                messages = sess.messages.mapIndexed { idx, n ->
                                                    if (idx == nodeIndex) n.copy(variants = newVariants, activeVariantIndex = newIdx) else n
                                                }
                                            )
                                        }
                                        triggerGeneration(
                                            historyForCall = historyBefore,
                                            targetAssistantNodeId = targetNode.id,
                                            targetVariantId = newVarId
                                        )
                                    },
                                    onContinueAssistantNode = { nodeIndex ->
                                        val targetNode = activeSession.messages.getOrNull(nodeIndex) ?: return@ManuscriptViewport
                                        if (targetNode.role != MessageRole.ASSISTANT) return@ManuscriptViewport
                                        val historyBefore = activeSession.messages.subList(0, nodeIndex)
                                        val existingText = targetNode.activeVariant.content
                                        triggerGeneration(
                                            historyForCall = historyBefore,
                                            targetAssistantNodeId = targetNode.id,
                                            targetVariantId = targetNode.activeVariant.id,
                                            continuePrefix = existingText
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopEditorialBar(
    activeSession: ChatSession,
    activeProfile: ProviderProfile,
    showSidebar: Boolean,
    activeModal: ActiveModalTab,
    onToggleSidebar: () -> Unit,
    onToggleViewMode: () -> Unit,
    onCycleWorkspaceMode: () -> Unit,
    onSelectModal: (ActiveModalTab) -> Unit
) {
    val p = LocalParchmentPalette.current
    val activeModel = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.sheetBg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SepiaButton(
                text = if (showSidebar) "◂ Folio" else "☰ Folio",
                compact = true,
                style = SepiaButtonStyle.OUTLINE,
                onClick = onToggleSidebar
            )

            Column(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clickable { onSelectModal(ActiveModalTab.PROVIDERS_AND_PROXY) }
            ) {
                BasicText(
                    text = activeSession.title,
                    style = p.uiHeaderStyle.copy(fontSize = 15.sp)
                )
                val modelLabel = activeModel.ifBlank { "No model selected (click to fetch or enter)" }
                BasicText(
                    text = "${activeProfile.name} • $modelLabel",
                    style = p.uiLabelStyle.copy(color = p.accent)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SepiaChip(
                label = if (activeSession.viewMode == ViewMode.MANUSCRIPT) "📖 Manuscript" else "▤ Editorial Turns",
                selected = activeSession.viewMode == ViewMode.MANUSCRIPT,
                onClick = onToggleViewMode
            )

            SepiaChip(
                label = activeSession.workspaceMode.label,
                selected = activeSession.workspaceMode != WorkspaceMode.OFF,
                onClick = onCycleWorkspaceMode
            )

            SepiaChip(
                label = "✦ Prompts & Cards (.png/.json)",
                selected = activeModal == ActiveModalTab.PROMPTS_AND_CARDS,
                onClick = { onSelectModal(ActiveModalTab.PROMPTS_AND_CARDS) }
            )

            SepiaChip(
                label = "⚙ Providers & Proxy",
                selected = activeModal == ActiveModalTab.PROVIDERS_AND_PROXY,
                onClick = { onSelectModal(ActiveModalTab.PROVIDERS_AND_PROXY) }
            )

            SepiaChip(
                label = "🎨 Sepia & Theme",
                selected = activeModal == ActiveModalTab.THEME_AND_STORAGE,
                onClick = { onSelectModal(ActiveModalTab.THEME_AND_STORAGE) }
            )
        }
    }
}

@Composable
private fun SessionSidebar(
    appState: AppStateSnapshot,
    activeSession: ChatSession,
    activeProfile: ProviderProfile,
    modifier: Modifier = Modifier,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onQuickSwitchProfile: (String) -> Unit
) {
    val p = LocalParchmentPalette.current

    Column(
        modifier = modifier
            .background(p.sheetBg)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = "Manuscripts & Sessions",
                style = p.uiHeaderStyle.copy(fontSize = 14.5.sp)
            )
            SepiaButton(
                text = "+ New",
                compact = true,
                style = SepiaButtonStyle.PRIMARY,
                onClick = onNewSession
            )
        }

        Spacer(Modifier.height(10.dp))

        // Quick Provider Switcher
        BasicText(
            text = "Active Wire Protocol",
            style = p.uiLabelStyle
        )
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            appState.profiles.forEach { prof ->
                val isSelected = prof.id == activeProfile.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (isSelected) p.cardBg else p.elevatedBg.copy(alpha = 0.45f))
                        .border(1.dp, if (isSelected) p.accent else p.border, RoundedCornerShape(5.dp))
                        .clickable { onQuickSwitchProfile(prof.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = prof.name,
                            style = p.uiBodyStyle.copy(
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) p.accent else p.primaryInk
                            )
                        )
                        BasicText(
                            text = "${prof.defaultModel.ifBlank { "no model set" }} • ${prof.protocol.defaultEndpointPath}",
                            style = p.uiLabelStyle.copy(fontSize = 10.5.sp)
                        )
                    }
                    if (prof.apiKey.isNotBlank()) {
                        BasicText(
                            text = "● Key",
                            style = p.uiLabelStyle.copy(color = p.accentSecondary, fontSize = 10.sp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SepiaDivider()
        Spacer(Modifier.height(10.dp))

        // Session list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(appState.sessions, key = { _, s -> s.id }) { _, sess ->
                val selected = sess.id == activeSession.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) p.cardBg else p.elevatedBg.copy(alpha = 0.35f))
                        .border(1.dp, if (selected) p.accentSecondary else p.border, RoundedCornerShape(6.dp))
                        .clickable { onSelectSession(sess.id) }
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = sess.title,
                            style = p.uiBodyStyle.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        )
                        val cardTag = sess.activeCard?.name?.takeIf { it.isNotBlank() }?.let { "Card: $it • " } ?: ""
                        BasicText(
                            text = "${cardTag}${sess.messages.size} turns",
                            style = p.uiLabelStyle.copy(fontSize = 11.sp)
                        )
                    }
                    if (appState.sessions.size > 1) {
                        SepiaButton(
                            text = "×",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onDeleteSession(sess.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManuscriptViewport(
    activeSession: ChatSession,
    activeProfile: ProviderProfile,
    showSessionInspector: Boolean,
    onToggleSessionInspector: () -> Unit,
    isStreaming: Boolean,
    streamingStatus: String,
    streamingInteractionId: String,
    userPromptInput: String,
    onUserPromptChange: (String) -> Unit,
    assistantPrefillInput: String,
    onAssistantPrefillChange: (String) -> Unit,
    showPrefillBox: Boolean,
    onTogglePrefillBox: () -> Unit,
    onSend: () -> Unit,
    onStopStream: () -> Unit,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onRegenerateNode: (Int) -> Unit,
    onContinueAssistantNode: (Int) -> Unit
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Collapsible Session Setup Bar (System Prompt, Author's Note, Active Card, Mounted Workspace)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.elevatedBg.copy(alpha = 0.65f))
                .padding(horizontal = 16.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.widthIn(max = p.maxReadingWidth).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val cardSummary = activeSession.activeCard?.name?.takeIf { it.isNotBlank() }
                    ?: "No Card Attached"
                val wsSummary = if (activeSession.workspacePath.isNotBlank()) {
                    " • Workspace: ${activeSession.workspacePath.takeLast(28)}"
                } else ""
                BasicText(
                    text = "✦ $cardSummary • Author's Note Depth: ${activeSession.authorsNoteDepth}$wsSummary",
                    style = p.uiLabelStyle.copy(color = p.narrationInk)
                )
                SepiaButton(
                    text = if (showSessionInspector) "Hide Direction & Note ▲" else "Scene Direction & Author's Note ▼",
                    compact = true,
                    style = SepiaButtonStyle.GHOST,
                    onClick = onToggleSessionInspector
                )
            }

            if (showSessionInspector) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .widthIn(max = p.maxReadingWidth)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(p.cardBg)
                        .border(1.dp, p.border, RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SepiaTextField(
                            value = activeSession.title,
                            onValueChange = { title -> onUpdateSession { it.copy(title = title) } },
                            label = "Manuscript / Session Title",
                            modifier = Modifier.weight(1f)
                        )
                        SepiaTextField(
                            value = activeSession.userName,
                            onValueChange = { uName -> onUpdateSession { it.copy(userName = uName) } },
                            label = "Your Persona ({{user}})",
                            modifier = Modifier.width(160.dp)
                        )
                    }

                    SepiaTextField(
                        value = activeSession.systemPrompt,
                        onValueChange = { sp -> onUpdateSession { it.copy(systemPrompt = sp) } },
                        label = "Session System Prompt (Cached Top Prefix)",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        SepiaTextField(
                            value = activeSession.authorsNote,
                            onValueChange = { note -> onUpdateSession { it.copy(authorsNote = note) } },
                            label = "Dynamic Author's Note / Current Scene Pacing (Injected near bottom to preserve cache)",
                            placeholder = "e.g. Keep the dialogue taut and understated; emphasize the rain on the slate roof...",
                            singleLine = false,
                            modifier = Modifier.weight(1f)
                        )
                        SepiaTextField(
                            value = activeSession.authorsNoteDepth.toString(),
                            onValueChange = { d ->
                                val parsed = d.toIntOrNull()?.coerceIn(0, 20) ?: 2
                                onUpdateSession { it.copy(authorsNoteDepth = parsed) }
                            },
                            label = "Depth",
                            modifier = Modifier.width(70.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = if (activeSession.workspacePath.isBlank()) {
                                "Local Agent Workspace: Not mounted"
                            } else {
                                "Workspace Root: ${activeSession.workspacePath}"
                            },
                            style = p.uiLabelStyle.copy(fontFamily = p.codeFontFamily)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SepiaButton(
                                text = "Mount Folder…",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    scope.launch {
                                        val picked = PlatformBridge.pickWorkspaceDirectory(activeSession.workspacePath)
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
                        }
                    }
                }
            }
        }

        SepiaDivider()

        // Main Manuscript Scrollable Stream
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .widthIn(max = p.maxReadingWidth)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(
                if (activeSession.viewMode == ViewMode.MANUSCRIPT) 14.dp else 18.dp
            )
        ) {
            item { Spacer(Modifier.height(10.dp)) }

            if (activeSession.messages.isEmpty()) {
                item {
                    EmptyManuscriptWelcomeCard(
                        activeProfile = activeProfile,
                        activeSession = activeSession,
                        onStartWithCardGreeting = {
                            val card = activeSession.activeCard ?: return@EmptyManuscriptWelcomeCard
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
                                                .replace("{{user}}", activeSession.userName),
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

            itemsIndexed(activeSession.messages, key = { _, node -> node.id }) { index, node ->
                MessageTurnItem(
                    node = node,
                    nodeIndex = index,
                    viewMode = activeSession.viewMode,
                    userName = activeSession.userName,
                    characterName = activeSession.activeCard?.name?.ifBlank { "Narrator" } ?: "Narrator",
                    onSelectVariant = { newIdx ->
                        onUpdateSession { sess ->
                            sess.copy(
                                messages = sess.messages.mapIndexed { i, n ->
                                    if (i == index) n.copy(activeVariantIndex = newIdx.coerceIn(0, n.variants.lastIndex)) else n
                                }
                            )
                        }
                    },
                    onSaveEditedText = { newText, asNewBranch ->
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
                    onDeleteNode = {
                        onUpdateSession { sess ->
                            sess.copy(messages = sess.messages.filterIndexed { i, _ -> i != index })
                        }
                    },
                    onRegenerate = { onRegenerateNode(index) },
                    onContinue = { onContinueAssistantNode(index) }
                )
            }

            item { Spacer(Modifier.height(18.dp)) }
        }

        // Live Streaming / Long Decode Status Pill
        if (isStreaming) {
            Row(
                modifier = Modifier
                    .widthIn(max = p.maxReadingWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(p.sheetBg)
                    .border(1.dp, p.accent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = streamingStatus.ifBlank { "Streaming response..." },
                        style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                    )
                    if (streamingInteractionId.isNotBlank()) {
                        BasicText(
                            text = "Stateful ID: $streamingInteractionId",
                            style = p.uiLabelStyle.copy(fontFamily = p.codeFontFamily, fontSize = 10.5.sp)
                        )
                    }
                }
                SepiaButton(
                    text = "Stop / Cancel",
                    compact = true,
                    style = SepiaButtonStyle.DANGER,
                    onClick = onStopStream
                )
            }
        }

        SepiaDivider()

        // Bottom Literary Composer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.sheetBg)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = p.maxReadingWidth)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Quick Bleeding-Edge Tool & Decode Toggles
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SepiaChip(
                        label = "Thinking: ${activeProfile.thinkingEffort.uppercase()}",
                        selected = activeProfile.thinkingEffort != "none",
                        onClick = {
                            val order = listOf("none", "low", "medium", "high", "max")
                            val next = order[(order.indexOf(activeProfile.thinkingEffort) + 1) % order.size]
                            onUpdateProfile { it.copy(thinkingEffort = next) }
                        }
                    )

                    if (activeProfile.protocol == ProviderProtocol.GEMINI_INTERACTIONS) {
                        SepiaChip(
                            label = if (activeProfile.useBackgroundLongDecode) "⚡ Argon Background Poll: ON" else "⚡ Stream SSE",
                            selected = activeProfile.useBackgroundLongDecode,
                            onClick = { onUpdateProfile { it.copy(useBackgroundLongDecode = !it.useBackgroundLongDecode) } }
                        )
                    }

                    SepiaChip(
                        label = "Stateful Chain",
                        selected = activeProfile.useStatefulChaining,
                        onClick = { onUpdateProfile { it.copy(useStatefulChaining = !it.useStatefulChaining) } }
                    )

                    SepiaChip(
                        label = "Web Search",
                        selected = activeProfile.enableWebSearch,
                        onClick = { onUpdateProfile { it.copy(enableWebSearch = !it.enableWebSearch) } }
                    )

                    if (activeProfile.protocol == ProviderProtocol.GEMINI_INTERACTIONS) {
                        SepiaChip(
                            label = "Code Exec",
                            selected = activeProfile.enableCodeExecution,
                            onClick = { onUpdateProfile { it.copy(enableCodeExecution = !it.enableCodeExecution) } }
                        )
                        SepiaChip(
                            label = "URL Context",
                            selected = activeProfile.enableUrlContext,
                            onClick = { onUpdateProfile { it.copy(enableUrlContext = !it.enableUrlContext) } }
                        )
                    }

                    SepiaChip(
                        label = if (showPrefillBox) "▾ Assistant Prefill: ON" else "▸ Assistant Prefill",
                        selected = showPrefillBox || assistantPrefillInput.isNotBlank(),
                        onClick = onTogglePrefillBox
                    )
                }

                if (showPrefillBox) {
                    SepiaTextField(
                        value = assistantPrefillInput,
                        onValueChange = onAssistantPrefillChange,
                        placeholder = "Optional Assistant Opening Line / Prefill (AI will start its response with these exact words)...",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    SepiaTextField(
                        value = userPromptInput,
                        onValueChange = onUserPromptChange,
                        placeholder = "Write your next scene direction, dialogue turn, or research prompt…",
                        singleLine = false,
                        minHeight = 52.dp,
                        modifier = Modifier.weight(1f)
                    )

                    SepiaButton(
                        text = if (isStreaming) "Writing…" else "Inscribe ↵",
                        enabled = !isStreaming && (userPromptInput.isNotBlank() || assistantPrefillInput.isNotBlank()),
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = onSend
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyManuscriptWelcomeCard(
    activeProfile: ProviderProfile,
    activeSession: ChatSession,
    onStartWithCardGreeting: () -> Unit
) {
    val p = LocalParchmentPalette.current
    val modelName = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }

    SepiaSectionCard(
        title = "Parchment — Literary & Bleeding-Edge Agent Studio",
        subtitle = "Active Protocol: ${activeProfile.protocol.displayName} • Model: $modelName"
    ) {
        LiteraryProseRenderer(
            text = """
                Welcome to **Parchment**. Every element of this workspace is rendered natively in warm Sepia tones using pure Compose Foundation primitives—zero browser or WebView bloat.
                
                *“Begin a chapter below, import a `.png` or `.json` instruction card from **Prompts & Cards**, or mount a local folder to let the built-in File System Agent read and edit your manuscript directly.”*
            """.trimIndent()
        )

        if (activeSession.activeCard != null && activeSession.activeCard.firstMessage.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            SepiaButton(
                text = "Insert Opening Scene from '${activeSession.activeCard.name}'",
                style = SepiaButtonStyle.SECONDARY,
                onClick = onStartWithCardGreeting
            )
        }
    }
}

@Composable
private fun MessageTurnItem(
    node: MessageNode,
    nodeIndex: Int,
    viewMode: ViewMode,
    userName: String,
    characterName: String,
    onSelectVariant: (Int) -> Unit,
    onSaveEditedText: (String, Boolean) -> Unit,
    onToggleContextMute: () -> Unit,
    onDeleteNode: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit
) {
    val p = LocalParchmentPalette.current
    val activeVar = node.activeVariant
    val isAssistant = node.role == MessageRole.ASSISTANT

    var isEditing by remember(node.id, node.activeVariantIndex) { mutableStateOf(false) }
    var editBuffer by remember(node.id, node.activeVariantIndex, activeVar.content) { mutableStateOf(activeVar.content) }
    var showThinkingDrawer by remember(node.id) { mutableStateOf(false) }
    var showRawWireDrawer by remember(node.id) { mutableStateOf(false) }

    val speakerTitle = if (isAssistant) {
        characterName.ifBlank { activeVar.modelUsed.ifBlank { "Narrator" } }
    } else {
        userName.ifBlank { "Author" }
    }

    val containerModifier = if (viewMode == ViewMode.MANUSCRIPT) {
        Modifier
            .fillMaxWidth()
            .then(
                if (!node.includedInContext) Modifier.background(p.elevatedBg.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                else Modifier
            )
            .padding(vertical = 4.dp)
    } else {
        val shape = RoundedCornerShape(8.dp)
        val bg = if (isAssistant) p.cardBg else p.elevatedBg.copy(alpha = 0.7f)
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, p.border, shape)
            .padding(14.dp)
    }

    Column(modifier = containerModifier) {
        // Turn Header & Branch/Swipe Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = speakerTitle.uppercase(),
                    style = p.uiLabelStyle.copy(
                        color = if (isAssistant) p.accent else p.narrationInk,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                )

                if (activeVar.modelUsed.isNotBlank() && isAssistant) {
                    BasicText(
                        text = "• ${activeVar.modelUsed}",
                        style = p.uiLabelStyle.copy(fontSize = 11.sp)
                    )
                }

                if (!node.includedInContext) {
                    BasicText(
                        text = "[Muted from API Context]",
                        style = p.uiLabelStyle.copy(color = p.danger, fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            // Swipe / Branch Variant Selector (< 1 / 3 >)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (node.variants.size > 1) {
                    SepiaButton(
                        text = "←",
                        compact = true,
                        enabled = node.activeVariantIndex > 0,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { onSelectVariant(node.activeVariantIndex - 1) }
                    )
                    BasicText(
                        text = "${node.activeVariantIndex + 1} / ${node.variants.size}",
                        style = p.uiLabelStyle.copy(color = p.accentSecondary, fontWeight = FontWeight.SemiBold)
                    )
                    SepiaButton(
                        text = "→",
                        compact = true,
                        enabled = node.activeVariantIndex < node.variants.lastIndex,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { onSelectVariant(node.activeVariantIndex + 1) }
                    )
                }

                if (activeVar.thinkingSummary.isNotBlank() || activeVar.toolCalls.isNotEmpty()) {
                    SepiaChip(
                        label = if (showThinkingDrawer) "▾ Thoughts & Tools (${activeVar.toolCalls.size})" else "▸ Thoughts & Tools (${activeVar.toolCalls.size})",
                        selected = showThinkingDrawer,
                        onClick = { showThinkingDrawer = !showThinkingDrawer }
                    )
                }
            }
        }

        // Collapsible Thinking & Workspace Agent Tool-Call Drawer
        if (showThinkingDrawer && (activeVar.thinkingSummary.isNotBlank() || activeVar.toolCalls.isNotEmpty())) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(p.sheetBg)
                    .border(1.dp, p.border, RoundedCornerShape(6.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (activeVar.thinkingSummary.isNotBlank()) {
                    BasicText(
                        text = "Reasoning / Thinking Trace",
                        style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                    )
                    BasicText(
                        text = activeVar.thinkingSummary,
                        style = p.uiBodyStyle.copy(
                            fontStyle = FontStyle.Italic,
                            color = p.narrationInk,
                            fontSize = 12.5.sp
                        )
                    )
                    if (activeVar.thinkingSignature.isNotBlank()) {
                        BasicText(
                            text = "Cryptographic Signature Preserved (${activeVar.thinkingSignature.length} chars)",
                            style = p.uiLabelStyle.copy(fontSize = 10.sp, fontFamily = p.codeFontFamily)
                        )
                    }
                }

                if (activeVar.toolCalls.isNotEmpty()) {
                    BasicText(
                        text = "Local Workspace Agent Tool Executions",
                        style = p.uiLabelStyle.copy(color = p.accentSecondary, fontWeight = FontWeight.SemiBold)
                    )
                    activeVar.toolCalls.forEach { tc ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(p.cardBg)
                                .border(1.dp, p.border, RoundedCornerShape(4.dp))
                                .padding(8.dp)
                        ) {
                            BasicText(
                                text = "⚙ ${tc.name}(${tc.argumentsJson})",
                                style = p.monoStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
                            )
                            Spacer(Modifier.height(4.dp))
                            BasicText(
                                text = tc.resultText.take(1200),
                                style = p.monoStyle.copy(
                                    color = if (tc.isError) p.danger else p.narrationInk,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Main Prose Content OR In-Place Editor
        if (isEditing) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SepiaTextField(
                    value = editBuffer,
                    onValueChange = { editBuffer = it },
                    singleLine = false,
                    minHeight = 120.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SepiaButton(
                        text = "Save In-Place",
                        compact = true,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = {
                            onSaveEditedText(editBuffer, false)
                            isEditing = false
                        }
                    )
                    SepiaButton(
                        text = "Save as New Branch Variant",
                        compact = true,
                        style = SepiaButtonStyle.SECONDARY,
                        onClick = {
                            onSaveEditedText(editBuffer, true)
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
            LiteraryProseRenderer(
                text = activeVar.content.ifBlank { "…" }
            )
        }

        Spacer(Modifier.height(6.dp))

        // Quiet Editorial Footer Controls & Token/Cache HUD
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SepiaButton(
                    text = "Edit Prose",
                    compact = true,
                    style = SepiaButtonStyle.GHOST,
                    onClick = {
                        editBuffer = activeVar.content
                        isEditing = !isEditing
                    }
                )
                if (isAssistant) {
                    SepiaButton(
                        text = "Continue →",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = onContinue
                    )
                    SepiaButton(
                        text = "Swipe / Regenerate ↻",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = onRegenerate
                    )
                }
                SepiaButton(
                    text = if (node.includedInContext) "Mute Context" else "Unmute Context",
                    compact = true,
                    style = SepiaButtonStyle.GHOST,
                    onClick = onToggleContextMute
                )
                if (activeVar.rawRequestPreview.isNotBlank()) {
                    SepiaButton(
                        text = if (showRawWireDrawer) "Hide Wire JSON" else "Wire JSON",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { showRawWireDrawer = !showRawWireDrawer }
                    )
                }
                SepiaButton(
                    text = "Delete",
                    compact = true,
                    style = SepiaButtonStyle.GHOST,
                    onClick = onDeleteNode
                )
            }

            val m = activeVar.tokenMetrics
            if (m.outputTokens > 0 || m.inputTokens > 0) {
                val cacheStr = if (m.cachedInputTokens > 0) " (${m.cachedInputTokens} cached)" else ""
                val thinkStr = if (m.reasoningTokens > 0) " • ${m.reasoningTokens} think" else ""
                BasicText(
                    text = "${m.tokensPerSec} tok/s • TTFT ${m.ttftMs}ms • In: ${m.inputTokens}$cacheStr$thinkStr • Out: ${m.outputTokens}",
                    style = p.uiLabelStyle.copy(fontSize = 10.5.sp)
                )
            }
        }

        if (showRawWireDrawer && activeVar.rawRequestPreview.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(p.sheetBg)
                    .border(1.dp, p.border, RoundedCornerShape(6.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BasicText(text = "Outgoing Request Payload:", style = p.uiLabelStyle.copy(fontWeight = FontWeight.Bold))
                BasicText(text = activeVar.rawRequestPreview.take(2000), style = p.monoStyle.copy(fontSize = 11.sp))
                if (activeVar.rawResponsePreview.isNotBlank()) {
                    SepiaDivider()
                    BasicText(text = "Latest Wire Event / Response:", style = p.uiLabelStyle.copy(fontWeight = FontWeight.Bold))
                    BasicText(text = activeVar.rawResponsePreview.take(2000), style = p.monoStyle.copy(fontSize = 11.sp))
                }
            }
        }

        if (viewMode == ViewMode.MANUSCRIPT) {
            Spacer(Modifier.height(8.dp))
            SepiaDivider()
        }
    }
}

// ============================================================================================
// PANEL 1: PROMPTS & CARDS PROTOCOL (.png Embedded Instructions & .json Import / Export)
// ============================================================================================
@Composable
private fun PromptsAndCardsPanel(
    appState: AppStateSnapshot,
    activeSession: ChatSession,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onSaveCardToLibrary: (CharacterOrScenarioCard) -> Unit,
    onSavePresetToLibrary: (SystemPromptPreset) -> Unit,
    onApplyTheme: (ThemeConfig) -> Unit,
    onNotify: (String) -> Unit,
    onClose: () -> Unit
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()

    val currentCard = activeSession.activeCard ?: CharacterOrScenarioCard()
    var cardName by remember(activeSession.activeCard) { mutableStateOf(currentCard.name) }
    var cardDesc by remember(activeSession.activeCard) { mutableStateOf(currentCard.description) }
    var cardPersonality by remember(activeSession.activeCard) { mutableStateOf(currentCard.personality) }
    var cardScenario by remember(activeSession.activeCard) { mutableStateOf(currentCard.scenario) }
    var cardFirstMes by remember(activeSession.activeCard) { mutableStateOf(currentCard.firstMessage) }
    var cardSysPrompt by remember(activeSession.activeCard) { mutableStateOf(currentCard.systemPrompt) }
    var cardPostHistory by remember(activeSession.activeCard) { mutableStateOf(currentCard.postHistoryInstructions) }

    fun buildEditedCard(): CharacterOrScenarioCard = currentCard.copy(
        name = cardName.trim(),
        description = cardDesc.trim(),
        personality = cardPersonality.trim(),
        scenario = cardScenario.trim(),
        firstMessage = cardFirstMes.trim(),
        systemPrompt = cardSysPrompt.trim(),
        postHistoryInstructions = cardPostHistory.trim()
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                BasicText(
                    text = "Card & System Prompt Protocol (.png & .json)",
                    style = p.uiHeaderStyle
                )
                BasicText(
                    text = "Import/export .png files with instructions hardcoded inside (tEXt/iTXt chara/ccv3/parchment_prompt) or standalone .json files.",
                    style = p.uiLabelStyle
                )
            }
            SepiaButton(text = "Back to Manuscript ✕", style = SepiaButtonStyle.OUTLINE, onClick = onClose)
        }

        // Universal Import / Export Action Bar
        SepiaSectionCard(
            title = "Universal Protocol Importer & Exporter",
            subtitle = "Automatically detects .png embedded chunks (chara, ccv3, parchment_prompt) and .json schemas"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SepiaButton(
                    text = "📥 Import .PNG or .JSON (Card / System Prompt)",
                    style = SepiaButtonStyle.PRIMARY,
                    onClick = {
                        scope.launch {
                            val picked = PlatformBridge.pickFileForImport(listOf("png", "json")) ?: return@launch
                            CardAndPromptCodec.importFromBytes(picked.fileName, picked.bytes).fold(
                                onSuccess = { res ->
                                    when (res) {
                                        is CardAndPromptCodec.ImportResult.CardImported -> {
                                            onSaveCardToLibrary(res.card)
                                            onUpdateSession { it.copy(activeCard = res.card) }
                                            onNotify("Imported '${res.card.name}' from ${res.sourceType} and attached to active session.")
                                        }
                                        is CardAndPromptCodec.ImportResult.SystemPromptImported -> {
                                            onSavePresetToLibrary(res.preset)
                                            onUpdateSession {
                                                it.copy(
                                                    systemPrompt = res.preset.systemPrompt,
                                                    authorsNote = res.preset.authorsNoteDefault.ifBlank { it.authorsNote }
                                                )
                                            }
                                            onNotify("Imported System Prompt '${res.preset.name}' from ${res.sourceType}.")
                                        }
                                        is CardAndPromptCodec.ImportResult.ThemeImported -> {
                                            onApplyTheme(res.theme)
                                            onNotify("Imported custom Theme '${res.theme.name}'.")
                                        }
                                    }
                                },
                                onFailure = { err ->
                                    onNotify("Import failed: ${err.message}")
                                }
                            )
                        }
                    }
                )

                SepiaButton(
                    text = "📤 Export Card as .PNG (Hardcoded Instructions)",
                    style = SepiaButtonStyle.SECONDARY,
                    onClick = {
                        scope.launch {
                            val card = buildEditedCard()
                            val pngBytes = CardAndPromptCodec.exportCardToPngBytes(card)
                            val safeName = card.name.ifBlank { "parchment-card" }.replace(Regex("[^a-zA-Z0-9-_]"), "_")
                            val savedPath = PlatformBridge.saveFileForExport("$safeName.png", pngBytes)
                            if (savedPath != null) {
                                onNotify("Exported PNG with embedded tEXt instructions to: $savedPath")
                            }
                        }
                    }
                )

                SepiaButton(
                    text = "📤 Export Card as .JSON",
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = {
                        scope.launch {
                            val card = buildEditedCard()
                            val jsonBytes = CardAndPromptCodec.exportCardToJson(card).encodeToByteArray()
                            val safeName = card.name.ifBlank { "parchment-card" }.replace(Regex("[^a-zA-Z0-9-_]"), "_")
                            val savedPath = PlatformBridge.saveFileForExport("$safeName.json", jsonBytes)
                            if (savedPath != null) {
                                onNotify("Exported Card JSON to: $savedPath")
                            }
                        }
                    }
                )
            }
        }

        // System Prompt Library & Import/Export
        SepiaSectionCard(
            title = "System Prompt Presets (Import / Export)",
            subtitle = "Pure system instruction presets for fiction, non-fiction, roleplay, and workspace coding agents"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                appState.systemPromptPresets.forEach { preset ->
                    SepiaButton(
                        text = "Apply: ${preset.name}",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = {
                            onUpdateSession {
                                it.copy(
                                    systemPrompt = preset.systemPrompt,
                                    authorsNote = preset.authorsNoteDefault.ifBlank { it.authorsNote }
                                )
                            }
                            onNotify("Applied System Prompt preset '${preset.name}'.")
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            SepiaTextField(
                value = activeSession.systemPrompt,
                onValueChange = { sp -> onUpdateSession { it.copy(systemPrompt = sp) } },
                label = "Active Session System Prompt",
                singleLine = false,
                minHeight = 90.dp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SepiaButton(
                    text = "📤 Export System Prompt as .JSON",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = {
                        scope.launch {
                            val preset = SystemPromptPreset(
                                id = "prompt-${PlatformBridge.currentTimeMillis()}",
                                name = activeSession.title.ifBlank { "Custom System Prompt" },
                                systemPrompt = activeSession.systemPrompt,
                                authorsNoteDefault = activeSession.authorsNote
                            )
                            val bytes = CardAndPromptCodec.exportSystemPromptToJson(preset).encodeToByteArray()
                            val path = PlatformBridge.saveFileForExport("system-prompt.prompt.json", bytes)
                            if (path != null) onNotify("Exported System Prompt JSON to: $path")
                        }
                    }
                )
                SepiaButton(
                    text = "📤 Export System Prompt inside .PNG",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = {
                        scope.launch {
                            val preset = SystemPromptPreset(
                                id = "prompt-${PlatformBridge.currentTimeMillis()}",
                                name = activeSession.title.ifBlank { "Custom System Prompt" },
                                systemPrompt = activeSession.systemPrompt,
                                authorsNoteDefault = activeSession.authorsNote
                            )
                            val bytes = CardAndPromptCodec.exportSystemPromptToPngBytes(preset)
                            val path = PlatformBridge.saveFileForExport("system-prompt.png", bytes)
                            if (path != null) onNotify("Exported System Prompt embedded inside PNG to: $path")
                        }
                    }
                )
            }
        }

        // Character / Scenario / Co-Writing Card Editor
        SepiaSectionCard(
            title = "Active Character / Scenario / Co-Writing Card Editor",
            subtitle = "Supports {{char}} and {{user}} macros. Saved into both .json and .png tEXt chunks.",
            headerAction = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaButton(
                        text = "Apply Changes to Session",
                        compact = true,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = {
                            val updated = buildEditedCard()
                            onSaveCardToLibrary(updated)
                            onUpdateSession { it.copy(activeCard = updated) }
                            onNotify("Updated active card '${updated.name}'.")
                        }
                    )
                    if (activeSession.activeCard != null) {
                        SepiaButton(
                            text = "Detach Card",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                onUpdateSession { it.copy(activeCard = null) }
                                onNotify("Detached card from session.")
                            }
                        )
                    }
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaTextField(
                    value = cardName,
                    onValueChange = { cardName = it },
                    label = "Card / Persona / Co-Writer Name ({{char}})"
                )
                SepiaTextField(
                    value = cardDesc,
                    onValueChange = { cardDesc = it },
                    label = "Description / Core Instructions / Persona Details",
                    singleLine = false,
                    minHeight = 90.dp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SepiaTextField(
                        value = cardPersonality,
                        onValueChange = { cardPersonality = it },
                        label = "Personality / Prose Style",
                        singleLine = false,
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = cardScenario,
                        onValueChange = { cardScenario = it },
                        label = "Scenario / Setting / Outline",
                        singleLine = false,
                        modifier = Modifier.weight(1f)
                    )
                }
                SepiaTextField(
                    value = cardFirstMes,
                    onValueChange = { cardFirstMes = it },
                    label = "Opening Scene / First Message (first_mes)",
                    singleLine = false,
                    minHeight = 80.dp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SepiaTextField(
                        value = cardSysPrompt,
                        onValueChange = { cardSysPrompt = it },
                        label = "Card System Prompt Override (system_prompt)",
                        singleLine = false,
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = cardPostHistory,
                        onValueChange = { cardPostHistory = it },
                        label = "Post-History Instructions / Author's Note",
                        singleLine = false,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ============================================================================================
// PANEL 2: BLEEDING-EDGE PROVIDERS, CUSTOM BASE URL PROXY & MODEL FETCHER
// ============================================================================================
@Composable
private fun ProvidersAndProxyPanel(
    appState: AppStateSnapshot,
    activeProfile: ProviderProfile,
    activeSession: ChatSession,
    onSelectProfile: (String) -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onAddProfile: (ProviderProtocol) -> Unit,
    onNotify: (String) -> Unit,
    onClose: () -> Unit
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchStatusMessage by remember(activeProfile.id) { mutableStateOf("") }
    var manualModelToAdd by remember(activeProfile.id) { mutableStateOf("") }

    fun triggerFetchModels() {
        if (isFetchingModels) return
        isFetchingModels = true
        fetchStatusMessage = "Fetching models from ${activeProfile.baseUrl}..."
        scope.launch {
            AiProviderEngine.fetchAvailableModels(activeProfile).fold(
                onSuccess = { fetched ->
                    val merged = (fetched + activeProfile.savedModels).distinct()
                    val chosen = activeProfile.defaultModel.ifBlank { merged.firstOrNull().orEmpty() }
                    onUpdateProfile {
                        it.copy(
                            savedModels = merged,
                            defaultModel = chosen
                        )
                    }
                    if (activeSession.modelOverride.isBlank() && chosen.isNotBlank()) {
                        onUpdateSession { it.copy(modelOverride = chosen) }
                    }
                    fetchStatusMessage = "Fetched ${fetched.size} models from endpoint."
                    onNotify("Fetched ${fetched.size} models from ${activeProfile.name}.")
                },
                onFailure = { err ->
                    fetchStatusMessage = "Fetch failed: ${err.message}"
                }
            )
            isFetchingModels = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                BasicText(
                    text = "Bleeding-Edge Providers, Model Fetcher & Custom Proxy",
                    style = p.uiHeaderStyle
                )
                BasicText(
                    text = "Native drivers for Google Gemini Interactions API, OpenAI Responses API, and Anthropic Messages API. Zero hardcoded models.",
                    style = p.uiLabelStyle
                )
            }
            SepiaButton(text = "Back to Manuscript ✕", style = SepiaButtonStyle.OUTLINE, onClick = onClose)
        }

        // Profile Selector & Creator
        SepiaSectionCard(
            title = "Provider Profiles",
            subtitle = "Create multiple profiles for official endpoints or custom reverse proxies"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                appState.profiles.forEach { prof ->
                    val modelTag = prof.defaultModel.ifBlank { "unset" }
                    SepiaChip(
                        label = "${prof.name} ($modelTag)",
                        selected = prof.id == activeProfile.id,
                        onClick = { onSelectProfile(prof.id) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SepiaButton(
                    text = "+ Add Gemini Interactions Profile",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = { onAddProfile(ProviderProtocol.GEMINI_INTERACTIONS) }
                )
                SepiaButton(
                    text = "+ Add OpenAI Responses Profile",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = { onAddProfile(ProviderProtocol.OPENAI_RESPONSES) }
                )
                SepiaButton(
                    text = "+ Add Anthropic Messages Profile",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = { onAddProfile(ProviderProtocol.ANTHROPIC_MESSAGES) }
                )
            }
        }

        // Endpoint, Proxy, Auth & Dynamic Model Management
        SepiaSectionCard(
            title = "Endpoint, Custom Proxy & Model Selection (${activeProfile.protocol.displayName})",
            subtitle = "Default Endpoint: ${activeProfile.protocol.defaultBaseUrl}${activeProfile.protocol.defaultEndpointPath}"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SepiaTextField(
                        value = activeProfile.name,
                        onValueChange = { v -> onUpdateProfile { it.copy(name = v) } },
                        label = "Profile Name",
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = activeProfile.defaultModel,
                        onValueChange = { v ->
                            onUpdateProfile { it.copy(defaultModel = v) }
                            onUpdateSession { it.copy(modelOverride = v) }
                        },
                        label = "Active Model ID (Fetch automatically or type manually)",
                        placeholder = "Type any model ID or click Fetch Models below...",
                        monospace = true,
                        modifier = Modifier.weight(1.3f)
                    )
                }

                // Automatic & Manual Model Fetcher Row
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SepiaButton(
                        text = if (isFetchingModels) "Fetching Models…" else "↻ Fetch Models from Endpoint",
                        compact = true,
                        enabled = !isFetchingModels,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = ::triggerFetchModels
                    )

                    SepiaChip(
                        label = if (activeProfile.autoFetchModels) "Auto-Fetch on Key/URL: ON" else "Auto-Fetch: OFF",
                        selected = activeProfile.autoFetchModels,
                        onClick = { onUpdateProfile { it.copy(autoFetchModels = !it.autoFetchModels) } }
                    )

                    if (activeProfile.savedModels.isNotEmpty()) {
                        SepiaButton(
                            text = "Clear Model List (${activeProfile.savedModels.size})",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onUpdateProfile { it.copy(savedModels = emptyList()) } }
                        )
                    }

                    if (fetchStatusMessage.isNotBlank()) {
                        BasicText(
                            text = fetchStatusMessage,
                            style = p.uiLabelStyle.copy(color = p.accent)
                        )
                    }
                }

                // Manual Model Addition Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    SepiaTextField(
                        value = manualModelToAdd,
                        onValueChange = { manualModelToAdd = it },
                        label = "Add Model ID Manually to Saved List",
                        placeholder = "Enter a model ID to save for 1-click switching...",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    SepiaButton(
                        text = "+ Save & Select Model",
                        style = SepiaButtonStyle.SECONDARY,
                        enabled = manualModelToAdd.isNotBlank(),
                        onClick = {
                            val clean = manualModelToAdd.trim()
                            if (clean.isNotEmpty()) {
                                val updatedList = (listOf(clean) + activeProfile.savedModels).distinct()
                                onUpdateProfile { it.copy(defaultModel = clean, savedModels = updatedList) }
                                onUpdateSession { it.copy(modelOverride = clean) }
                                manualModelToAdd = ""
                            }
                        }
                    )
                }

                // Fetched & Manually Saved Model Chips
                if (activeProfile.savedModels.isNotEmpty()) {
                    BasicText(
                        text = "Available / Saved Models (${activeProfile.savedModels.size} — click to select):",
                        style = p.uiLabelStyle
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        activeProfile.savedModels.forEach { m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                SepiaChip(
                                    label = m,
                                    selected = activeProfile.defaultModel == m,
                                    onClick = {
                                        onUpdateProfile { it.copy(defaultModel = m) }
                                        onUpdateSession { it.copy(modelOverride = m) }
                                    }
                                )
                                SepiaButton(
                                    text = "×",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = {
                                        onUpdateProfile {
                                            it.copy(savedModels = it.savedModels.filterNot { item -> item == m })
                                        }
                                    }
                                )
                            }
                        }
                    }
                } else {
                    BasicText(
                        text = "No models saved yet. Enter your API Key / Base URL and click '↻ Fetch Models from Endpoint', or type a Model ID manually above.",
                        style = p.uiLabelStyle.copy(color = p.mutedInk)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SepiaTextField(
                        value = activeProfile.baseUrl,
                        onValueChange = { v -> onUpdateProfile { it.copy(baseUrl = v) } },
                        label = "Base URL (Official or Custom Proxy URL)",
                        placeholder = activeProfile.protocol.defaultBaseUrl,
                        monospace = true,
                        modifier = Modifier.weight(1.4f)
                    )
                    SepiaTextField(
                        value = activeProfile.endpointPathOverride,
                        onValueChange = { v -> onUpdateProfile { it.copy(endpointPathOverride = v) } },
                        label = "Endpoint Path Override (Optional)",
                        placeholder = activeProfile.protocol.defaultEndpointPath,
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                SepiaTextField(
                    value = activeProfile.apiKey,
                    onValueChange = { v -> onUpdateProfile { it.copy(apiKey = v) } },
                    label = "API Key / Proxy Token",
                    placeholder = "Paste your API key or proxy token here...",
                    monospace = true
                )

                // Auth Header Mode Selection
                BasicText(text = "Authentication Header Mode (for Custom Proxies):", style = p.uiLabelStyle)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AuthHeaderMode.entries.forEach { mode ->
                        SepiaChip(
                            label = mode.label,
                            selected = activeProfile.authHeaderMode == mode,
                            onClick = { onUpdateProfile { it.copy(authHeaderMode = mode) } }
                        )
                    }
                }

                SepiaTextField(
                    value = activeProfile.customHeadersRaw,
                    onValueChange = { v -> onUpdateProfile { it.copy(customHeadersRaw = v) } },
                    label = "Custom HTTP Headers (One 'Header-Name: value' per line)",
                    placeholder = "CF-Access-Client-Id: ...\nX-Custom-Proxy-Route: ...",
                    singleLine = false,
                    monospace = true
                )
            }
        }

        // Long Decode, Thinking & Wire Behavior
        SepiaSectionCard(
            title = "Long Decode, Reasoning & Wire Parameters",
            subtitle = "Fine-tune extended socket timeouts, background polling, prompt caching, and raw JSON body overrides"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SepiaChip(
                        label = "Stateful Chaining (previous_interaction_id / previous_response_id)",
                        selected = activeProfile.useStatefulChaining,
                        onClick = { onUpdateProfile { it.copy(useStatefulChaining = !it.useStatefulChaining) } }
                    )

                    if (activeProfile.protocol == ProviderProtocol.GEMINI_INTERACTIONS) {
                        SepiaChip(
                            label = "Server Background Long Decode (background=true + Poll)",
                            selected = activeProfile.useBackgroundLongDecode,
                            onClick = { onUpdateProfile { it.copy(useBackgroundLongDecode = !it.useBackgroundLongDecode) } }
                        )
                    }

                    val fallbackLabel = when (activeProfile.protocol) {
                        ProviderProtocol.GEMINI_INTERACTIONS -> "Legacy Proxy Fallback (:streamGenerateContent)"
                        ProviderProtocol.OPENAI_RESPONSES -> "Legacy Proxy Fallback (/v1/chat/completions)"
                        ProviderProtocol.ANTHROPIC_MESSAGES -> "Standard /v1/messages"
                    }
                    if (activeProfile.protocol != ProviderProtocol.ANTHROPIC_MESSAGES) {
                        SepiaChip(
                            label = fallbackLabel,
                            selected = activeProfile.useLegacyFallbackEndpoint,
                            onClick = { onUpdateProfile { it.copy(useLegacyFallbackEndpoint = !it.useLegacyFallbackEndpoint) } }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SepiaTextField(
                        value = activeProfile.temperature.toString(),
                        onValueChange = { v ->
                            v.toDoubleOrNull()?.let { num -> onUpdateProfile { it.copy(temperature = num) } }
                        },
                        label = "Temperature",
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = activeProfile.topP.toString(),
                        onValueChange = { v ->
                            v.toDoubleOrNull()?.let { num -> onUpdateProfile { it.copy(topP = num) } }
                        },
                        label = "Top-P",
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = activeProfile.maxOutputTokens.toString(),
                        onValueChange = { v ->
                            v.toIntOrNull()?.let { num -> onUpdateProfile { it.copy(maxOutputTokens = num) } }
                        },
                        label = "Max Output Tokens",
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = activeProfile.thinkingBudgetTokens.toString(),
                        onValueChange = { v ->
                            v.toIntOrNull()?.let { num -> onUpdateProfile { it.copy(thinkingBudgetTokens = num) } }
                        },
                        label = "Thinking Budget Tokens",
                        modifier = Modifier.weight(1f)
                    )
                }

                if (activeProfile.protocol == ProviderProtocol.ANTHROPIC_MESSAGES) {
                    SepiaTextField(
                        value = activeProfile.anthropicBetaHeaders,
                        onValueChange = { v -> onUpdateProfile { it.copy(anthropicBetaHeaders = v) } },
                        label = "Anthropic Beta Headers (anthropic-beta)",
                        monospace = true
                    )
                }

                SepiaTextField(
                    value = activeProfile.rawJsonExtraBody,
                    onValueChange = { v -> onUpdateProfile { it.copy(rawJsonExtraBody = v) } },
                    label = "Raw JSON Extra Body Override (Deep-merged into outgoing API payload for day-0 parameters)",
                    placeholder = """{"generation_config": {"thinking_level": "high"}}""",
                    singleLine = false,
                    monospace = true,
                    minHeight = 72.dp
                )
            }
        }
    }
}

// ============================================================================================
// PANEL 3: SEPIA & CUSTOM THEME STUDIO + ZERO-TRACE STORAGE CONTROL
// ============================================================================================
@Composable
private fun ThemeAndStoragePanel(
    theme: ThemeConfig,
    onUpdateTheme: (ThemeConfig) -> Unit,
    onWipeAllData: () -> Unit,
    onNotify: (String) -> Unit,
    onClose: () -> Unit
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                BasicText(
                    text = "Custom Sepia & Typography Studio",
                    style = p.uiHeaderStyle
                )
                BasicText(
                    text = "100% custom Compose Foundation UI. Customize every surface, ink tone, font, and margin, or import/export .theme.json.",
                    style = p.uiLabelStyle
                )
            }
            SepiaButton(text = "Back to Manuscript ✕", style = SepiaButtonStyle.OUTLINE, onClick = onClose)
        }

        // Built-in Sepia & Literary Presets
        SepiaSectionCard(
            title = "Built-In Sepia & Literary Theme Presets",
            subtitle = "Switch instantly or use as a starting point for your own custom palette"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeConfig.PRESETS.forEach { preset ->
                    SepiaChip(
                        label = preset.name,
                        selected = theme.name == preset.name,
                        onClick = { onUpdateTheme(preset) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SepiaButton(
                    text = "📤 Export Current Theme (.theme.json)",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = {
                        scope.launch {
                            val jsonBytes = CardAndPromptCodec.prettyJson.encodeToString(ThemeConfig.serializer(), theme).encodeToByteArray()
                            val path = PlatformBridge.saveFileForExport("parchment-custom.theme.json", jsonBytes)
                            if (path != null) onNotify("Exported Theme JSON to: $path")
                        }
                    }
                )
            }
        }

        // Typography, Custom .TTF/.OTF Fonts & Book Layout Controls
        SepiaSectionCard(
            title = "Literary Typography, Custom .TTF Fonts & Page Geometry",
            subtitle = "Load any custom .ttf / .otf font from your machine for Prose/UI and Code blocks"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 1. Prose Font Family & Custom .TTF Picker
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(text = "Prose Font:", style = p.uiLabelStyle)
                    listOf("Serif", "Sans", "Monospace").forEach { fam ->
                        SepiaChip(
                            label = fam,
                            selected = theme.customProseFontPath.isBlank() && theme.proseFontFamily.equals(fam, ignoreCase = true),
                            onClick = { onUpdateTheme(theme.copy(proseFontFamily = fam, customProseFontPath = "")) }
                        )
                    }
                    SepiaChip(
                        label = "Distinct Dialogue & Narration Ink Tinting",
                        selected = theme.highlightDialogueAndNarration,
                        onClick = { onUpdateTheme(theme.copy(highlightDialogueAndNarration = !theme.highlightDialogueAndNarration)) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    SepiaTextField(
                        value = theme.customProseFontPath,
                        onValueChange = { path ->
                            onUpdateTheme(theme.copy(customProseFontPath = path, proseFontFamily = if (path.isNotBlank()) "Custom" else "Serif"))
                        },
                        label = "Custom Prose / UI .TTF or .OTF Font File Path on Machine",
                        placeholder = "e.g. C:\\Windows\\Fonts\\georgia.ttf or click Browse .TTF…",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    SepiaButton(
                        text = "📂 Browse Prose .TTF…",
                        style = SepiaButtonStyle.SECONDARY,
                        onClick = {
                            scope.launch {
                                val picked = PlatformBridge.pickFontFile("Select Custom Prose .TTF or .OTF Font")
                                if (!picked.isNullOrBlank()) {
                                    onUpdateTheme(theme.copy(customProseFontPath = picked, proseFontFamily = "Custom"))
                                    onNotify("Loaded custom Prose font: $picked")
                                }
                            }
                        }
                    )
                    if (theme.customProseFontPath.isNotBlank()) {
                        SepiaButton(
                            text = "Reset",
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onUpdateTheme(theme.copy(customProseFontPath = "", proseFontFamily = "Serif")) }
                        )
                    }
                }

                SepiaDivider()

                // 2. Code Font Family & Custom Code .TTF Picker
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(text = "Code Font:", style = p.uiLabelStyle)
                    listOf("Monospace", "Sans", "Serif").forEach { fam ->
                        SepiaChip(
                            label = fam,
                            selected = theme.customCodeFontPath.isBlank() && theme.codeFontFamily.equals(fam, ignoreCase = true),
                            onClick = { onUpdateTheme(theme.copy(codeFontFamily = fam, customCodeFontPath = "")) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    SepiaTextField(
                        value = theme.customCodeFontPath,
                        onValueChange = { path ->
                            onUpdateTheme(theme.copy(customCodeFontPath = path, codeFontFamily = if (path.isNotBlank()) "Custom" else "Monospace"))
                        },
                        label = "Custom Code / Inspector .TTF or .OTF Font File Path on Machine",
                        placeholder = "e.g. C:\\Windows\\Fonts\\consola.ttf or click Browse Code .TTF…",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    SepiaButton(
                        text = "📂 Browse Code .TTF…",
                        style = SepiaButtonStyle.SECONDARY,
                        onClick = {
                            scope.launch {
                                val picked = PlatformBridge.pickFontFile("Select Custom Code .TTF or .OTF Font")
                                if (!picked.isNullOrBlank()) {
                                    onUpdateTheme(theme.copy(customCodeFontPath = picked, codeFontFamily = "Custom"))
                                    onNotify("Loaded custom Code font: $picked")
                                }
                            }
                        }
                    )
                    if (theme.customCodeFontPath.isNotBlank()) {
                        SepiaButton(
                            text = "Reset",
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onUpdateTheme(theme.copy(customCodeFontPath = "", codeFontFamily = "Monospace")) }
                        )
                    }
                }

                SepiaDivider()

                // 3. Size, Line Height & Page Geometry
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SepiaTextField(
                        value = theme.proseFontSizeSp.toString(),
                        onValueChange = { v -> v.toFloatOrNull()?.let { onUpdateTheme(theme.copy(proseFontSizeSp = it)) } },
                        label = "Prose Size (sp)",
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = theme.codeFontSizeSp.toString(),
                        onValueChange = { v -> v.toFloatOrNull()?.let { onUpdateTheme(theme.copy(codeFontSizeSp = it)) } },
                        label = "Code Size (sp)",
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = theme.proseLineHeightMultiplier.toString(),
                        onValueChange = { v -> v.toFloatOrNull()?.let { onUpdateTheme(theme.copy(proseLineHeightMultiplier = it)) } },
                        label = "Line Height (1.1 - 2.4)",
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = theme.paragraphSpacingDp.toString(),
                        onValueChange = { v -> v.toFloatOrNull()?.let { onUpdateTheme(theme.copy(paragraphSpacingDp = it)) } },
                        label = "Paragraph Spacing (dp)",
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = theme.maxReadingWidthDp.toString(),
                        onValueChange = { v -> v.toFloatOrNull()?.let { onUpdateTheme(theme.copy(maxReadingWidthDp = it)) } },
                        label = "Max Page Width (dp)",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Hex Color Token Customizer
        SepiaSectionCard(
            title = "Surface & Ink Hex Color Tokens",
            subtitle = "Edit any hex code (#RRGGBB) to customize the entire UI in real time"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SepiaTextField(
                        value = theme.canvasBgHex,
                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom Theme", canvasBgHex = it)) },
                        label = "Canvas Background",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = theme.sheetBgHex,
                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom Theme", sheetBgHex = it)) },
                        label = "Sidebar / Header Sheet",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = theme.cardBgHex,
                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom Theme", cardBgHex = it)) },
                        label = "Card Surface",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = theme.borderHex,
                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom Theme", borderHex = it)) },
                        label = "Deckle Hairline Border",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SepiaTextField(
                        value = theme.primaryInkHex,
                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom Theme", primaryInkHex = it)) },
                        label = "Primary Prose Ink",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = theme.dialogueInkHex,
                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom Theme", dialogueInkHex = it)) },
                        label = "Spoken Dialogue Ink",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = theme.narrationInkHex,
                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom Theme", narrationInkHex = it)) },
                        label = "Italic Narration Ink",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = theme.accentHex,
                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom Theme", accentHex = it)) },
                        label = "Primary Wax Accent",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Zero-Trace Storage & Clean Uninstall Section
        SepiaSectionCard(
            title = "Zero-Trace Storage & Clean Uninstallation",
            subtitle = "All local state lives in a single isolated directory: ${PlatformBridge.getAppStorageDirectoryPath()}"
        ) {
            BasicText(
                text = "Both the Windows .exe uninstaller and Android 16 package manager (allowBackup=false) are configured to remove 100% of app files, settings, and caches upon uninstallation. You can also wipe all local data immediately below.",
                style = p.uiBodyStyle
            )
            Spacer(Modifier.height(10.dp))
            SepiaButton(
                text = "Wipe All Local Data & Reset to Factory Defaults",
                style = SepiaButtonStyle.DANGER,
                onClick = onWipeAllData
            )
        }
    }
}

private val LEGACY_HARDCODED_MODELS = setOf(
    "gemini-4-argon",
    "gemini-4-argon-long-decode",
    "gemini-2.5-pro",
    "gemini-2.5-flash",
    "gpt-5",
    "o3",
    "o4-mini",
    "gpt-4.1",
    "claude-opus-4-6",
    "claude-sonnet-4-6",
    "claude-3-7-sonnet-latest"
)

private fun loadOrCreateDefaultAppState(): AppStateSnapshot {
    val raw = PlatformBridge.loadAppStateJson()
    if (!raw.isNullOrBlank()) {
        runCatching {
            val loaded = CardAndPromptCodec.prettyJson.decodeFromString<AppStateSnapshot>(raw)
            if (loaded.profiles.isNotEmpty() && loaded.sessions.isNotEmpty()) {
                // Strip any legacy hardcoded starter model lists if present from an older build
                val cleanedProfiles = loaded.profiles.map { prof ->
                    val strippedSaved = if (prof.savedModels.isNotEmpty() && prof.savedModels.all { it in LEGACY_HARDCODED_MODELS }) {
                        emptyList()
                    } else prof.savedModels
                    val strippedDefault = if (strippedSaved.isEmpty() && prof.defaultModel in LEGACY_HARDCODED_MODELS) {
                        ""
                    } else prof.defaultModel
                    prof.copy(defaultModel = strippedDefault, savedModels = strippedSaved)
                }
                val cleanedSessions = loaded.sessions.map { sess ->
                    if (sess.modelOverride in LEGACY_HARDCODED_MODELS) sess.copy(modelOverride = "") else sess
                }
                return loaded.copy(profiles = cleanedProfiles, sessions = cleanedSessions)
            }
        }
    }
    return createDefaultAppState()
}

private fun createDefaultAppState(): AppStateSnapshot {
    val now = PlatformBridge.currentTimeMillis()
    val geminiProfile = ProviderProfile(
        id = "prof-gemini-interactions",
        name = "Google Gemini Interactions",
        protocol = ProviderProtocol.GEMINI_INTERACTIONS
    )
    val openAiProfile = ProviderProfile(
        id = "prof-openai-responses",
        name = "OpenAI Responses",
        protocol = ProviderProtocol.OPENAI_RESPONSES
    )
    val anthropicProfile = ProviderProfile(
        id = "prof-anthropic-messages",
        name = "Anthropic Messages",
        protocol = ProviderProtocol.ANTHROPIC_MESSAGES
    )

    val defaultPrompts = listOf(
        SystemPromptPreset(
            id = "preset-literary-cowriter",
            name = "Literary Fiction & Co-Writing",
            description = "Rich sensory prose, natural subtext-heavy dialogue, zero clichés",
            systemPrompt = ChatSession.DEFAULT_LITERARY_SYSTEM_PROMPT,
            authorsNoteDefault = "Maintain grounded sensory detail, distinct character voices, and natural scene pacing."
        ),
        SystemPromptPreset(
            id = "preset-immersive-rp",
            name = "Immersive Roleplay Narrator",
            description = "Stays strictly in-character as {{char}} and the living world without speaking for {{user}}",
            systemPrompt = "You are portraying {{char}} and the surrounding world in an interactive narrative with {{user}}. Write exclusively from {{char}}'s perspective and the environment's sensory reality. Never write dialogue, internal thoughts, or actions for {{user}}. Leave natural openings for {{user}} to respond.",
            authorsNoteDefault = "Portray {{char}}'s reactions authentically; do not rush the scene."
        ),
        SystemPromptPreset(
            id = "preset-nonfiction-synthesis",
            name = "Non-Fiction Deep Research & Essayist",
            description = "Clear, rigorous analytical prose for long-form non-fiction and synthesis",
            systemPrompt = "You are a rigorous non-fiction writer, historian, and technical editor. Write with clarity, intellectual honesty, concrete evidence, and rhythmic narrative structure. Avoid filler transitions and generic bullet-point summaries unless requested.",
            authorsNoteDefault = "Prioritize primary mechanisms, concrete examples, and lucid prose."
        ),
        SystemPromptPreset(
            id = "preset-workspace-agent",
            name = "Hermes-Style Local Workspace Agent",
            description = "Proactively uses fs_list, fs_read, fs_search, fs_write, and fs_edit on the mounted folder",
            systemPrompt = "You are an autonomous local workspace collaborator with direct file-system tools (`fs_list`, `fs_read`, `fs_search`, `fs_write`, `fs_edit`). Inspect files before making changes, keep edits surgical and clean, and explain your work concisely.",
            authorsNoteDefault = ""
        )
    )

    val initialSession = ChatSession(
        id = "sess-initial",
        title = "Folio I — New Manuscript",
        createdAt = now,
        updatedAt = now,
        profileId = geminiProfile.id,
        modelOverride = geminiProfile.defaultModel
    )

    return AppStateSnapshot(
        profiles = listOf(geminiProfile, openAiProfile, anthropicProfile),
        activeProfileId = geminiProfile.id,
        sessions = listOf(initialSession),
        activeSessionId = initialSession.id,
        systemPromptPresets = defaultPrompts,
        savedCards = emptyList(),
        theme = ThemeConfig()
    )
}
