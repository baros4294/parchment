package io.parchment.ai.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AuthHeaderMode
import io.parchment.ai.model.CharacterOrScenarioCard
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.ContextFragment
import io.parchment.ai.model.FragmentActivationMode
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.MediaKind
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.ModelPricingSpec
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.SystemPromptPreset
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.model.ViewMode
import io.parchment.ai.model.WorkspaceMode
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.ModelsDevClient
import io.parchment.ai.network.OtaReleaseInfo
import io.parchment.ai.network.OtaUpdateEngine
import io.parchment.ai.network.OtaUpdateState
import io.parchment.ai.network.StreamUpdate
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.protocol.CardAndPromptCodec
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

enum class ActiveModalTab {
    NONE,
    PROMPTS_AND_CARDS,
    PROVIDERS_AND_PROXY,
    THEME_AND_STORAGE
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun ParchmentApp() {
    val scope = rememberCoroutineScope()

    var appState by remember { mutableStateOf(loadOrCreateDefaultAppState()) }
    var activeModal by remember { mutableStateOf(ActiveModalTab.NONE) }
    var showSidebar by remember { mutableStateOf(!PlatformBridge.isAndroid) }
    var showSessionInspector by remember { mutableStateOf(false) }
    var bannerNotification by remember { mutableStateOf("") }

    // OTA Update state
    var otaState by remember { mutableStateOf<OtaUpdateState>(OtaUpdateState.Idle) }
    var dismissOtaBanner by remember { mutableStateOf(false) }

    // Streaming state
    var isStreaming by remember { mutableStateOf(false) }
    var streamingStatus by remember { mutableStateOf("") }
    var streamingInteractionId by remember { mutableStateOf("") }
    var activeStreamJob by remember { mutableStateOf<Job?>(null) }

    // Composer inputs & attachments
    var userPromptInput by remember { mutableStateOf("") }
    var assistantPrefillInput by remember { mutableStateOf("") }
    var showPrefillBox by remember { mutableStateOf(false) }
    var pendingAttachments by remember { mutableStateOf<List<PromptAttachment>>(emptyList()) }

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

    // Automatically check for OTA updates on startup if enabled
    LaunchedEffect(appState.autoCheckOtaOnStartup, appState.otaGithubRepo) {
        if (appState.autoCheckOtaOnStartup) {
            otaState = OtaUpdateState.Checking
            OtaUpdateEngine.checkForUpdates(appState.otaGithubRepo).fold(
                onSuccess = { rel ->
                    otaState = if (rel.isNewerThanCurrent) {
                        OtaUpdateState.Available(rel)
                    } else {
                        OtaUpdateState.UpToDate(OtaUpdateEngine.currentAppVersion, rel)
                    }
                },
                onFailure = {
                    otaState = OtaUpdateState.Idle
                }
            )
        }
    }

    fun triggerOtaCheckManual() {
        dismissOtaBanner = false
        otaState = OtaUpdateState.Checking
        scope.launch {
            OtaUpdateEngine.checkForUpdates(appState.otaGithubRepo).fold(
                onSuccess = { rel ->
                    otaState = if (rel.isNewerThanCurrent) {
                        OtaUpdateState.Available(rel)
                    } else {
                        OtaUpdateState.UpToDate(OtaUpdateEngine.currentAppVersion, rel)
                    }
                },
                onFailure = { err ->
                    otaState = OtaUpdateState.Error(err.message ?: "Failed to check GitHub Releases")
                }
            )
        }
    }

    fun triggerOtaDownloadAndInstall(release: OtaReleaseInfo) {
        dismissOtaBanner = false
        scope.launch {
            OtaUpdateEngine.downloadAndInstall(release).collect { st ->
                otaState = st
            }
        }
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

    // Automatically look up pricing & context window from models.dev when active model changes
    LaunchedEffect(activeProfile.id, activeProfile.defaultModel) {
        val currentModel = activeProfile.defaultModel.trim()
        if (currentModel.isNotEmpty()) {
            val savedCustom = activeProfile.customModelSpecs[currentModel]
            if (savedCustom != null) {
                updateActiveProfile {
                    it.copy(
                        contextWindowTokens = savedCustom.contextWindowTokens,
                        maxOutputTokens = savedCustom.maxOutputTokens,
                        inputPricePerM = savedCustom.inputPricePerM,
                        outputPricePerM = savedCustom.outputPricePerM,
                        cacheReadPricePerM = savedCustom.cacheReadPricePerM,
                        cacheWritePricePerM = savedCustom.cacheWritePricePerM,
                        reasoningPricePerM = savedCustom.reasoningPricePerM
                    )
                }
            } else {
                ModelsDevClient.fetchModelSpec(currentModel).onSuccess { spec ->
                    updateActiveProfile {
                        it.copy(
                            contextWindowTokens = spec.contextWindowTokens,
                            maxOutputTokens = spec.maxOutputTokens,
                            inputPricePerM = spec.inputPricePerM,
                            outputPricePerM = spec.outputPricePerM,
                            cacheReadPricePerM = spec.cacheReadPricePerM,
                            cacheWritePricePerM = spec.cacheWritePricePerM,
                            reasoningPricePerM = spec.reasoningPricePerM
                        )
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
            val accumulatedMedia = mutableListOf<GeneratedMediaItem>()
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
                    is StreamUpdate.MediaGenerated -> {
                        accumulatedMedia.add(update.item)
                        streamingStatus = "Generated ${update.item.kind.name.lowercase()} (${accumulatedMedia.size})"
                        patchTargetVariant { v ->
                            v.copy(mediaItems = accumulatedMedia.toList())
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
                                mediaItems = update.mediaItems,
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
        val currentAtts = pendingAttachments
        if (trimmedUser.isEmpty() && prefill.isEmpty() && currentAtts.isEmpty()) return

        val now = PlatformBridge.currentTimeMillis()
        val updatedMessages = activeSession.messages.toMutableList()

        if (trimmedUser.isNotEmpty() || currentAtts.isNotEmpty()) {
            val userVar = MessageVariant(
                id = "var-$now-u",
                content = trimmedUser,
                attachments = currentAtts,
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
        pendingAttachments = emptyList()

        triggerGeneration(
            historyForCall = historySnapshot,
            targetAssistantNodeId = assistantNodeId,
            targetVariantId = assistantVarId,
            continuePrefix = prefill
        )
    }

    // Guided Scene Direction (Errata / SillyBunny style): directs the next AI turn without adding a user chat bubble
    fun sendGuidedDirection() {
        val direction = userPromptInput.trim()
        val prefill = assistantPrefillInput.trim()
        if (direction.isEmpty() && prefill.isEmpty()) return

        val now = PlatformBridge.currentTimeMillis()
        val updatedMessages = activeSession.messages.toMutableList()

        // Create a temporary synthetic direction node for the API call only (not saved into manuscript messages)
        val syntheticDirectionNode = MessageNode(
            id = "node-dir-$now",
            role = MessageRole.USER,
            variants = listOf(
                MessageVariant(
                    id = "var-dir-$now",
                    content = "[Scene Direction / Plot Guidance for next passage: $direction\nWrite the next narrative passage directly following this direction without acknowledging these instructions.]",
                    createdAt = now
                )
            )
        )
        val historyForCall = updatedMessages + syntheticDirectionNode

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

        updateActiveSession { it.copy(messages = updatedMessages) }
        userPromptInput = ""
        assistantPrefillInput = ""

        triggerGeneration(
            historyForCall = historyForCall,
            targetAssistantNodeId = assistantNodeId,
            targetVariantId = assistantVarId,
            continuePrefix = prefill
        )
    }

    // Impersonate / Draft User Turn (SillyTavern / Errata style): drafts {{user}}'s next response into the composer box
    fun triggerImpersonateDraft() {
        if (isStreaming) return
        val now = PlatformBridge.currentTimeMillis()
        val seedHint = userPromptInput.trim()
        val instruction = if (seedHint.isNotEmpty()) {
            "[Write the next turn from ${activeSession.userName}'s perspective incorporating this idea: \"$seedHint\". Write only ${activeSession.userName}'s dialogue and actions.]"
        } else {
            "[Write the next natural response turn from ${activeSession.userName}'s perspective based on the current scene. Write only ${activeSession.userName}'s dialogue and actions.]"
        }

        val syntheticNode = MessageNode(
            id = "node-imp-$now",
            role = MessageRole.USER,
            variants = listOf(MessageVariant(id = "var-imp-$now", content = instruction, createdAt = now))
        )
        val historyForCall = activeSession.messages + syntheticNode

        activeStreamJob?.cancel()
        isStreaming = true
        streamingStatus = "Drafting ${activeSession.userName}'s turn…"
        userPromptInput = ""

        activeStreamJob = scope.launch {
            val buf = StringBuilder()
            AiProviderEngine.streamGeneration(
                profile = activeProfile,
                session = activeSession,
                historyNodes = historyForCall
            ).collect { update ->
                when (update) {
                    is StreamUpdate.ContentDelta -> {
                        buf.append(update.delta)
                        userPromptInput = buf.toString()
                    }
                    is StreamUpdate.Completed -> {
                        userPromptInput = update.fullContent.trim()
                        isStreaming = false
                        streamingStatus = ""
                    }
                    is StreamUpdate.Error -> {
                        bannerNotification = "Impersonate error: ${update.message}"
                        isStreaming = false
                        streamingStatus = ""
                    }
                    else -> {}
                }
            }
        }
    }

    // Auto-Summarize Story / Chat into Rolling Continuity Memory (Errata Librarian / SillyBunny Mewmory)
    fun triggerAutoSummarizeStory() {
        if (isStreaming || activeSession.messages.isEmpty()) return
        val now = PlatformBridge.currentTimeMillis()
        val summarizeNode = MessageNode(
            id = "node-sum-$now",
            role = MessageRole.USER,
            variants = listOf(
                MessageVariant(
                    id = "var-sum-$now",
                    content = "[Provide a concise, high-signal continuity summary of all key events, character states, unresolved threads, and established facts in the conversation so far. Use bullet points.]",
                    createdAt = now
                )
            )
        )
        val historyForCall = activeSession.messages + summarizeNode

        activeStreamJob?.cancel()
        isStreaming = true
        streamingStatus = "Summarizing story continuity into memory…"

        activeStreamJob = scope.launch {
            val buf = StringBuilder()
            AiProviderEngine.streamGeneration(
                profile = activeProfile,
                session = activeSession,
                historyNodes = historyForCall
            ).collect { update ->
                when (update) {
                    is StreamUpdate.ContentDelta -> {
                        buf.append(update.delta)
                        updateActiveSession { it.copy(storySummary = buf.toString()) }
                    }
                    is StreamUpdate.Completed -> {
                        updateActiveSession { it.copy(storySummary = update.fullContent.trim()) }
                        isStreaming = false
                        streamingStatus = ""
                        bannerNotification = "Updated Rolling Story Summary."
                    }
                    is StreamUpdate.Error -> {
                        bannerNotification = "Summarize error: ${update.message}"
                        isStreaming = false
                        streamingStatus = ""
                    }
                    else -> {}
                }
            }
        }
    }

    ProvideParchmentTheme(config = appState.theme) {
        val p = LocalParchmentPalette.current

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(p.sheetBg)
        ) {
            val isCompactScreen = maxWidth < 760.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .background(p.canvasBg)
            ) {
                // 1. Top Editorial Bar
                TopEditorialBar(
                    activeSession = activeSession,
                    activeProfile = activeProfile,
                    isCompactScreen = isCompactScreen,
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
                        if (isCompactScreen) showSidebar = false
                    }
                )

                SepiaDivider()

                // Top In-App OTA Update Banner (when update is available or actively downloading/installing)
                when (val currentOta = otaState) {
                    is OtaUpdateState.Available -> {
                        if (!dismissOtaBanner) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(p.cardBg)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val sizeMb = (currentOta.release.assetSizeBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                                Column(modifier = Modifier.weight(1f)) {
                                    BasicText(
                                        text = "✦ OTA Update Available: ${currentOta.release.tagName} (${currentOta.release.assetName} • $sizeMb MB)",
                                        style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                                    )
                                    BasicText(
                                        text = "Installed: v${OtaUpdateEngine.currentAppVersion} — Update directly inside Parchment with zero browser steps.",
                                        style = p.uiLabelStyle
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SepiaButton(
                                        text = "⬇ Download & Install Now",
                                        compact = true,
                                        style = SepiaButtonStyle.PRIMARY,
                                        onClick = { triggerOtaDownloadAndInstall(currentOta.release) }
                                    )
                                    SepiaButton(
                                        text = "Later",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = { dismissOtaBanner = true }
                                    )
                                }
                            }
                            SepiaDivider()
                        }
                    }

                    is OtaUpdateState.Downloading -> {
                        val downMb = (currentOta.downloadedBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                        val totalMb = (currentOta.totalBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(p.cardBg)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                text = "⬇ Downloading OTA Update ${currentOta.release.tagName}: $downMb MB / $totalMb MB (${currentOta.progressPercent}%)…",
                                style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                            )
                        }
                        SepiaDivider()
                    }

                    is OtaUpdateState.Installing -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(p.cardBg)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                text = "⚙ ${currentOta.message}",
                                style = p.uiBodyStyle.copy(color = p.accentSecondary, fontWeight = FontWeight.SemiBold)
                            )
                        }
                        SepiaDivider()
                    }

                    else -> {}
                }

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
                            modifier = if (isCompactScreen) {
                                Modifier.fillMaxSize()
                            } else {
                                Modifier.width(290.dp).fillMaxHeight()
                            },
                            onSelectSession = { id ->
                                persist(appState.copy(activeSessionId = id))
                                if (isCompactScreen) showSidebar = false
                            },
                            onNewSession = {
                                val now = PlatformBridge.currentTimeMillis()
                                val newSess = ChatSession(
                                    id = "sess-$now",
                                    title = "New Chat",
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
                                    val nextActive = if (appState.activeSessionId == targetId) remaining.first().id else appState.activeSessionId
                                    persist(
                                        appState.copy(
                                            sessions = remaining,
                                            activeSessionId = nextActive
                                        )
                                    )
                                } else {
                                    val now = PlatformBridge.currentTimeMillis()
                                    val fresh = ChatSession(
                                        id = "sess-$now",
                                        title = "New Chat",
                                        createdAt = now,
                                        updatedAt = now,
                                        profileId = activeProfile.id,
                                        modelOverride = activeProfile.defaultModel
                                    )
                                    persist(
                                        appState.copy(
                                            sessions = listOf(fresh),
                                            activeSessionId = fresh.id
                                        )
                                    )
                                }
                            },
                            onDeleteAllSessions = {
                                val now = PlatformBridge.currentTimeMillis()
                                val fresh = ChatSession(
                                    id = "sess-$now",
                                    title = "New Chat",
                                    createdAt = now,
                                    updatedAt = now,
                                    profileId = activeProfile.id,
                                    modelOverride = activeProfile.defaultModel
                                )
                                persist(
                                    appState.copy(
                                        sessions = listOf(fresh),
                                        activeSessionId = fresh.id
                                    )
                                )
                                bannerNotification = "Deleted all chats."
                            },
                            onQuickSwitchProfile = { profId ->
                                persist(appState.copy(activeProfileId = profId))
                            },
                            onDeleteProfile = { targetProfId ->
                                val remaining = appState.profiles.filterNot { it.id == targetProfId }
                                if (remaining.isNotEmpty()) {
                                    val nextProfId = if (appState.activeProfileId == targetProfId) remaining.first().id else appState.activeProfileId
                                    persist(
                                        appState.copy(
                                            profiles = remaining,
                                            activeProfileId = nextProfId
                                        )
                                    )
                                } else {
                                    val now = PlatformBridge.currentTimeMillis()
                                    val fallbackProf = ProviderProfile(
                                        id = "prof-$now",
                                        name = "Default Profile",
                                        protocol = ProviderProtocol.GEMINI_INTERACTIONS
                                    )
                                    persist(
                                        appState.copy(
                                            profiles = listOf(fallbackProf),
                                            activeProfileId = fallbackProf.id
                                        )
                                    )
                                }
                            }
                        )
                        if (!isCompactScreen) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(p.border)
                            )
                        }
                    }

                    // Right Pane: Active Modal Drawer OR Manuscript Viewport (hidden on mobile when full-screen sidebar is open)
                    if (!isCompactScreen || !showSidebar) {
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
                                    onDeletePresetFromLibrary = { presetId ->
                                        persist(appState.copy(systemPromptPresets = appState.systemPromptPresets.filterNot { it.id == presetId }))
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
                                            name = "${protocol.displayName.substringBefore(" ")} Profile",
                                            protocol = protocol
                                        )
                                        persist(
                                            appState.copy(
                                                profiles = appState.profiles + newProf,
                                                activeProfileId = newProf.id
                                            )
                                        )
                                    },
                                    onDeleteProfile = { targetProfId ->
                                        val remaining = appState.profiles.filterNot { it.id == targetProfId }
                                        if (remaining.isNotEmpty()) {
                                            val nextProfId = if (appState.activeProfileId == targetProfId) remaining.first().id else appState.activeProfileId
                                            persist(
                                                appState.copy(
                                                    profiles = remaining,
                                                    activeProfileId = nextProfId
                                                )
                                            )
                                            bannerNotification = "Deleted connection profile."
                                        } else {
                                            val now = PlatformBridge.currentTimeMillis()
                                            val fallbackProf = ProviderProfile(
                                                id = "prof-$now",
                                                name = "Default Profile",
                                                protocol = ProviderProtocol.GEMINI_INTERACTIONS
                                            )
                                            persist(
                                                appState.copy(
                                                    profiles = listOf(fallbackProf),
                                                    activeProfileId = fallbackProf.id
                                                )
                                            )
                                            bannerNotification = "Reset to clean connection profile."
                                        }
                                    },
                                    onNotify = { bannerNotification = it },
                                    onClose = { activeModal = ActiveModalTab.NONE }
                                )
                            }

                            ActiveModalTab.THEME_AND_STORAGE -> {
                                ThemeAndStoragePanel(
                                    appState = appState,
                                    theme = appState.theme,
                                    otaGithubRepo = appState.otaGithubRepo,
                                    autoCheckOta = appState.autoCheckOtaOnStartup,
                                    otaState = otaState,
                                    onUpdateOtaSettings = { repo, autoCheck ->
                                        persist(appState.copy(otaGithubRepo = repo, autoCheckOtaOnStartup = autoCheck))
                                    },
                                    onCheckOtaNow = ::triggerOtaCheckManual,
                                    onInstallOtaRelease = ::triggerOtaDownloadAndInstall,
                                    onUpdateTheme = { newTheme -> persist(appState.copy(theme = newTheme)) },
                                    onRestoreFullBackup = { restoredState ->
                                        persist(restoredState)
                                        bannerNotification = "Restored full Parchment backup (${restoredState.sessions.size} chats, ${restoredState.profiles.size} profiles)."
                                    },
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
                                    pendingAttachments = pendingAttachments,
                                    onAddAttachment = {
                                        scope.launch {
                                            val picked = PlatformBridge.pickFileForImport(
                                                listOf("png", "jpg", "jpeg", "webp", "md", "txt", "json", "kt", "py", "ts", "js", "tex")
                                            ) ?: return@launch
                                            val now = PlatformBridge.currentTimeMillis()
                                            val lower = picked.fileName.lowercase()
                                            val isImg = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")
                                            val att = if (isImg) {
                                                val mime = when {
                                                    lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                                                    lower.endsWith(".webp") -> "image/webp"
                                                    else -> "image/png"
                                                }
                                                val savedPath = PlatformBridge.saveGeneratedMediaFile("att-$now-${picked.fileName}", picked.bytes)
                                                PromptAttachment(
                                                    id = "att-$now",
                                                    fileName = picked.fileName,
                                                    mimeType = mime,
                                                    base64Data = Base64.encode(picked.bytes),
                                                    localFilePath = savedPath
                                                )
                                            } else {
                                                PromptAttachment(
                                                    id = "att-$now",
                                                    fileName = picked.fileName,
                                                    mimeType = "text/plain",
                                                    textContent = picked.bytes.decodeToString()
                                                )
                                            }
                                            pendingAttachments = pendingAttachments + att
                                        }
                                    },
                                    onRemoveAttachment = { attId ->
                                        pendingAttachments = pendingAttachments.filterNot { it.id == attId }
                                    },
                                    onExportChatMarkdown = {
                                        scope.launch {
                                            val md = buildString {
                                                appendLine("# ${activeSession.title}")
                                                appendLine()
                                                for (n in activeSession.messages) {
                                                    val roleHeader = if (n.role == MessageRole.ASSISTANT) {
                                                        activeSession.activeCard?.name?.ifBlank { n.activeVariant.modelUsed.ifBlank { "Assistant" } } ?: "Assistant"
                                                    } else {
                                                        activeSession.userName.ifBlank { "User" }
                                                    }
                                                    appendLine("### $roleHeader")
                                                    if (n.activeVariant.thinkingSummary.isNotBlank()) {
                                                        appendLine("<details><summary>Reasoning</summary>")
                                                        appendLine()
                                                        appendLine(n.activeVariant.thinkingSummary)
                                                        appendLine("</details>")
                                                        appendLine()
                                                    }
                                                    appendLine(n.activeVariant.content)
                                                    appendLine()
                                                    appendLine("---")
                                                    appendLine()
                                                }
                                            }
                                            val safeTitle = activeSession.title.replace(Regex("[^a-zA-Z0-9-_]"), "_").ifBlank { "chat" }
                                            val saved = PlatformBridge.saveFileForExport("$safeTitle.md", md.encodeToByteArray())
                                            if (saved != null) bannerNotification = "Exported chat Markdown to: $saved"
                                        }
                                    },
                                    onOpenPricingSettings = {
                                        activeModal = ActiveModalTab.PROVIDERS_AND_PROXY
                                    },
                                    onSend = ::sendUserTurn,
                                    onSendGuidedDirection = ::sendGuidedDirection,
                                    onImpersonateDraft = ::triggerImpersonateDraft,
                                    onAutoSummarizeStory = ::triggerAutoSummarizeStory,
                                    onForkSessionFromNode = { nodeIndex ->
                                        val now = PlatformBridge.currentTimeMillis()
                                        val slicedMessages = activeSession.messages.subList(0, (nodeIndex + 1).coerceAtMost(activeSession.messages.size))
                                        val forkedSession = activeSession.copy(
                                            id = "sess-fork-$now",
                                            title = "${activeSession.title} (Fork #${nodeIndex + 1})",
                                            createdAt = now,
                                            updatedAt = now,
                                            messages = slicedMessages
                                        )
                                        persist(
                                            appState.copy(
                                                sessions = listOf(forkedSession) + appState.sessions,
                                                activeSessionId = forkedSession.id
                                            )
                                        )
                                        bannerNotification = "Forked chat at turn #${nodeIndex + 1} into '${forkedSession.title}'."
                                    },
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
}

private val THINKING_EFFORT_PRESETS = listOf(
    "minimal",
    "none",
    "low",
    "medium",
    "high",
    "xhigh",
    "max"
)

@Composable
private fun TopEditorialBar(
    activeSession: ChatSession,
    activeProfile: ProviderProfile,
    isCompactScreen: Boolean,
    showSidebar: Boolean,
    activeModal: ActiveModalTab,
    onToggleSidebar: () -> Unit,
    onToggleViewMode: () -> Unit,
    onCycleWorkspaceMode: () -> Unit,
    onSelectModal: (ActiveModalTab) -> Unit
) {
    val p = LocalParchmentPalette.current
    val activeModel = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }
    val modelLabel = activeModel.ifBlank { "Tap to select model" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.sheetBg)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Row 1: Folio Button + Session Title / Active Model + (On Desktop: Nav Chips)
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
                SepiaButton(
                    text = if (showSidebar) "◂ Folio" else "☰ Folio",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = onToggleSidebar
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .clickable { onSelectModal(ActiveModalTab.PROVIDERS_AND_PROXY) }
                ) {
                    BasicText(
                        text = activeSession.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = p.uiHeaderStyle.copy(fontSize = 15.sp)
                    )
                    BasicText(
                        text = "${activeProfile.name} • $modelLabel",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = p.uiLabelStyle.copy(color = p.accent)
                    )
                }
            }

            if (!isCompactScreen) {
                Spacer(Modifier.width(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopNavChipsRow(
                        activeSession = activeSession,
                        activeModal = activeModal,
                        onToggleViewMode = onToggleViewMode,
                        onCycleWorkspaceMode = onCycleWorkspaceMode,
                        onSelectModal = onSelectModal
                    )
                }
            }
        }

        // Row 2 (On Mobile / Compact Screen): Horizontally Scrollable Navigation & Mode Chips
        if (isCompactScreen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopNavChipsRow(
                    activeSession = activeSession,
                    activeModal = activeModal,
                    onToggleViewMode = onToggleViewMode,
                    onCycleWorkspaceMode = onCycleWorkspaceMode,
                    onSelectModal = onSelectModal
                )
            }
        }
    }
}

@Composable
private fun TopNavChipsRow(
    activeSession: ChatSession,
    activeModal: ActiveModalTab,
    onToggleViewMode: () -> Unit,
    onCycleWorkspaceMode: () -> Unit,
    onSelectModal: (ActiveModalTab) -> Unit
) {
    SepiaChip(
        label = if (activeSession.viewMode == ViewMode.MANUSCRIPT) "📖 Manuscript" else "▤ Turns",
        selected = activeModal == ActiveModalTab.NONE && activeSession.viewMode == ViewMode.MANUSCRIPT,
        onClick = {
            if (activeModal != ActiveModalTab.NONE) {
                onSelectModal(ActiveModalTab.NONE)
            } else {
                onToggleViewMode()
            }
        }
    )

    SepiaChip(
        label = activeSession.workspaceMode.label,
        selected = activeSession.workspaceMode != WorkspaceMode.OFF,
        onClick = onCycleWorkspaceMode
    )

    SepiaChip(
        label = "✦ Prompts & Cards",
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

@Composable
private fun SessionSidebar(
    appState: AppStateSnapshot,
    activeSession: ChatSession,
    activeProfile: ProviderProfile,
    modifier: Modifier = Modifier,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onDeleteAllSessions: () -> Unit,
    onQuickSwitchProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    var searchQuery by remember { mutableStateOf("") }

    val filteredSessions = remember(appState.sessions, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            appState.sessions
        } else {
            appState.sessions.filter { sess ->
                sess.title.contains(q, ignoreCase = true) ||
                    sess.messages.any { node ->
                        node.variants.any { v -> v.content.contains(q, ignoreCase = true) }
                    }
            }
        }
    }

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
                text = "Chats",
                style = p.uiHeaderStyle.copy(fontSize = 14.5.sp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SepiaButton(
                    text = "+ New",
                    compact = true,
                    style = SepiaButtonStyle.PRIMARY,
                    onClick = onNewSession
                )
                SepiaButton(
                    text = "Delete All",
                    compact = true,
                    style = SepiaButtonStyle.DANGER,
                    onClick = onDeleteAllSessions
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Instant Global Search Bar (titles + message content)
        SepiaTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Search chats & messages…",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        // Quick Provider Switcher
        BasicText(
            text = "Connection Profiles",
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (prof.apiKey.isNotBlank()) {
                            BasicText(
                                text = "●",
                                style = p.uiLabelStyle.copy(color = p.accentSecondary, fontSize = 10.sp)
                            )
                        }
                        SepiaButton(
                            text = "×",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onDeleteProfile(prof.id) }
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
            itemsIndexed(filteredSessions, key = { _, s -> s.id }) { _, sess ->
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
    pendingAttachments: List<PromptAttachment>,
    onAddAttachment: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onExportChatMarkdown: () -> Unit,
    onOpenPricingSettings: () -> Unit,
    onSend: () -> Unit,
    onSendGuidedDirection: () -> Unit,
    onImpersonateDraft: () -> Unit,
    onAutoSummarizeStory: () -> Unit,
    onForkSessionFromNode: (Int) -> Unit,
    onStopStream: () -> Unit,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onRegenerateNode: (Int) -> Unit,
    onContinueAssistantNode: (Int) -> Unit
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Compute live session-wide stats & current context window usage
    val activeContextNodes = remember(activeSession.messages) {
        activeSession.messages.filter { it.includedInContext }
    }
    val sessionTotalInputTok = remember(activeSession.messages) {
        activeSession.messages.sumOf { it.activeVariant.tokenMetrics.inputTokens }
    }
    val sessionTotalCachedTok = remember(activeSession.messages) {
        activeSession.messages.sumOf { it.activeVariant.tokenMetrics.cachedInputTokens }
    }
    val sessionTotalReasoningTok = remember(activeSession.messages) {
        activeSession.messages.sumOf { it.activeVariant.tokenMetrics.reasoningTokens }
    }
    val sessionTotalOutputTok = remember(activeSession.messages) {
        activeSession.messages.sumOf { it.activeVariant.tokenMetrics.outputTokens }
    }
    val sessionTotalTokens = sessionTotalInputTok + sessionTotalReasoningTok + sessionTotalOutputTok
    val sessionTotalCostUsd = remember(activeSession.messages, activeProfile) {
        activeSession.messages.sumOf { node ->
            val m = node.activeVariant.tokenMetrics
            if (m.estimatedCostUsd > 0.0) m.estimatedCostUsd else activeProfile.calculateTurnCostUsd(m)
        }
    }

    // Live context token estimation (includes system prompt, card, active fragments, summary, history, and current typed input)
    val liveContextTokens = remember(
        activeSession,
        activeContextNodes,
        userPromptInput,
        assistantPrefillInput,
        pendingAttachments
    ) {
        val baseChars = activeSession.systemPrompt.length +
            (activeSession.activeCard?.buildCompiledInstructions(activeSession.userName)?.length ?: 0) +
            activeSession.storySummary.length +
            activeSession.authorsNote.length +
            activeSession.fragments.filter { it.mode != FragmentActivationMode.MUTED }.sumOf { it.content.length } +
            activeContextNodes.sumOf { it.activeVariant.content.length } +
            userPromptInput.length +
            assistantPrefillInput.length +
            pendingAttachments.sumOf { it.textContent.length }
        val lastReportedContext = activeContextNodes.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.activeVariant?.tokenMetrics?.let { it.inputTokens + it.outputTokens } ?: 0
        val typedDraftTokens = (userPromptInput.length + assistantPrefillInput.length) / 4
        maxOf(baseChars / 4, lastReportedContext + typedDraftTokens)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Collapsible Session Setup & Cumulative Stats Bar
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
                    ?: "No Card"
                val statsSummary = if (sessionTotalTokens > 0) {
                    " • In: ${formatCompactTokens(sessionTotalInputTok)} (Cache: ${formatCompactTokens(sessionTotalCachedTok)}) • Think: ${formatCompactTokens(sessionTotalReasoningTok)} • Out: ${formatCompactTokens(sessionTotalOutputTok)} • Total: ${formatCompactTokens(sessionTotalTokens)} • Cost: ${formatUsdCost(sessionTotalCostUsd)}"
                } else ""
                BasicText(
                    text = "✦ $cardSummary$statsSummary",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    style = p.uiLabelStyle.copy(color = p.narrationInk)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (activeSession.messages.isNotEmpty()) {
                        SepiaButton(
                            text = "Export .MD",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onExportChatMarkdown
                        )
                        SepiaButton(
                            text = "Clear Chat",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onUpdateSession { it.copy(messages = emptyList()) } }
                        )
                    }
                    SepiaButton(
                        text = if (showSessionInspector) "Hide Direction & Note ▲" else "Scene Direction & Author's Note ▼",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = onToggleSessionInspector
                    )
                }
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

                    // Rolling Story Summary / Continuity Memory (Errata Librarian / SillyBunny Mewmory)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = "Rolling Story Summary / Continuity Memory",
                            style = p.uiLabelStyle.copy(color = p.narrationInk, fontWeight = FontWeight.SemiBold)
                        )
                        SepiaButton(
                            text = if (isStreaming) "Summarizing…" else "✦ Auto-Summarize Chat into Memory",
                            compact = true,
                            enabled = !isStreaming && activeSession.messages.isNotEmpty(),
                            style = SepiaButtonStyle.SECONDARY,
                            onClick = onAutoSummarizeStory
                        )
                    }
                    SepiaTextField(
                        value = activeSession.storySummary,
                        onValueChange = { sum -> onUpdateSession { it.copy(storySummary = sum) } },
                        placeholder = "Rolling summary of key plot points, character states, or research findings...",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )

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

            if (activeSession.messages.isEmpty() && activeSession.activeCard?.firstMessage?.isNotBlank() == true) {
                item {
                    val card = activeSession.activeCard
                    SepiaButton(
                        text = "Insert Opening Scene from '${card.name}'",
                        style = SepiaButtonStyle.SECONDARY,
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
                    onForkHere = { onForkSessionFromNode(index) },
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

        // Bottom Literary Composer (Responsive Mobile + Desktop Layout)
        var showThinkingEditorRow by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.sheetBg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = p.maxReadingWidth)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Row 1: Quick Bleeding-Edge Tool, Thinking Level & Decode Toggles
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (activeProfile.protocol == ProviderProtocol.BYTEDANCE_ARK) {
                        SepiaChip(
                            label = if (!activeProfile.enableVideoGeneration) "🖼 Seedream Image: ON" else "🖼 Seedream Image",
                            selected = !activeProfile.enableVideoGeneration,
                            onClick = { onUpdateProfile { it.copy(enableVideoGeneration = false, enableImageGeneration = true) } }
                        )
                        SepiaChip(
                            label = if (activeProfile.enableVideoGeneration) "🎬 Seedance Video: ON" else "🎬 Seedance Video",
                            selected = activeProfile.enableVideoGeneration,
                            onClick = { onUpdateProfile { it.copy(enableVideoGeneration = true) } }
                        )
                        SepiaChip(
                            label = "Ratio: ${activeProfile.mediaAspectRatio}",
                            selected = true,
                            onClick = {
                                val ratios = listOf("1:1", "16:9", "9:16", "4:3", "3:4", "21:9")
                                val next = ratios[(ratios.indexOf(activeProfile.mediaAspectRatio) + 1) % ratios.size]
                                onUpdateProfile { it.copy(mediaAspectRatio = next) }
                            }
                        )
                        if (activeProfile.enableVideoGeneration) {
                            SepiaChip(
                                label = "Duration: ${activeProfile.videoDurationSeconds}s",
                                selected = true,
                                onClick = {
                                    val durs = listOf(5, 10)
                                    val next = durs[(durs.indexOf(activeProfile.videoDurationSeconds) + 1) % durs.size]
                                    onUpdateProfile { it.copy(videoDurationSeconds = next) }
                                }
                            )
                            SepiaChip(
                                label = if (activeProfile.videoGenerateAudio) "🔊 Audio: ON" else "🔇 Audio: OFF",
                                selected = activeProfile.videoGenerateAudio,
                                onClick = { onUpdateProfile { it.copy(videoGenerateAudio = !it.videoGenerateAudio) } }
                            )
                        }
                    } else {
                        // Cycle through minimal/none/low/medium/high/xhigh/max OR expand manual input
                        SepiaChip(
                            label = "Thinking: ${activeProfile.thinkingEffort.ifBlank { "default" }.uppercase()}",
                            selected = activeProfile.thinkingEffort.isNotBlank() && !activeProfile.thinkingEffort.equals("none", ignoreCase = true),
                            onClick = {
                                val cur = activeProfile.thinkingEffort.lowercase()
                                val idx = THINKING_EFFORT_PRESETS.indexOf(cur)
                                val next = if (idx >= 0) {
                                    THINKING_EFFORT_PRESETS[(idx + 1) % THINKING_EFFORT_PRESETS.size]
                                } else {
                                    "medium"
                                }
                                onUpdateProfile { it.copy(thinkingEffort = next) }
                            }
                        )

                        SepiaChip(
                            label = if (showThinkingEditorRow) "▾ Level" else "✎ Level",
                            selected = showThinkingEditorRow,
                            onClick = { showThinkingEditorRow = !showThinkingEditorRow }
                        )

                        if (activeProfile.protocol == ProviderProtocol.GEMINI_INTERACTIONS ||
                            activeProfile.protocol == ProviderProtocol.OPENAI_RESPONSES
                        ) {
                            SepiaChip(
                                label = if (activeProfile.enableImageGeneration) "🖼 Image Gen: ON (${activeProfile.mediaAspectRatio})" else "🖼 Image Gen",
                                selected = activeProfile.enableImageGeneration,
                                onClick = { onUpdateProfile { it.copy(enableImageGeneration = !it.enableImageGeneration) } }
                            )
                            if (activeProfile.enableImageGeneration) {
                                SepiaChip(
                                    label = "Ratio: ${activeProfile.mediaAspectRatio}",
                                    selected = true,
                                    onClick = {
                                        val ratios = listOf("1:1", "16:9", "9:16", "4:3", "3:4")
                                        val next = ratios[(ratios.indexOf(activeProfile.mediaAspectRatio) + 1) % ratios.size]
                                        onUpdateProfile { it.copy(mediaAspectRatio = next) }
                                    }
                                )
                            }
                        }

                        if (activeProfile.protocol == ProviderProtocol.GEMINI_INTERACTIONS) {
                            SepiaChip(
                                label = if (activeProfile.useBackgroundLongDecode) "⚡ Background Poll: ON" else "⚡ Stream SSE",
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
                            label = if (showPrefillBox) "▾ Prefill: ON" else "▸ Prefill",
                            selected = showPrefillBox || assistantPrefillInput.isNotBlank(),
                            onClick = onTogglePrefillBox
                        )

                        if (activeSession.workspaceMode == WorkspaceMode.READ_WRITE) {
                            SepiaChip(
                                label = if (activeProfile.allowShellExecution) "⚡ Shell Exec: ON" else "⚡ Shell Exec: OFF",
                                selected = activeProfile.allowShellExecution,
                                onClick = { onUpdateProfile { it.copy(allowShellExecution = !it.allowShellExecution) } }
                            )
                        }
                    }
                }

                // Expandable Inline Thinking Level Presets + Manual Input Row
                if (showThinkingEditorRow) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(p.cardBg)
                            .border(1.dp, p.border, RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(text = "Presets:", style = p.uiLabelStyle)
                            THINKING_EFFORT_PRESETS.forEach { preset ->
                                SepiaChip(
                                    label = preset,
                                    selected = activeProfile.thinkingEffort.equals(preset, ignoreCase = true),
                                    onClick = { onUpdateProfile { it.copy(thinkingEffort = preset) } }
                                )
                            }
                        }
                        SepiaTextField(
                            value = activeProfile.thinkingEffort,
                            onValueChange = { custom -> onUpdateProfile { it.copy(thinkingEffort = custom) } },
                            label = "Manual Thinking / Effort Level (Argon: low/medium/high • GPT-6.1 Sol & Opus 5.5: low/medium/high/xhigh/max)",
                            placeholder = "Type any custom level (e.g. minimal, none, low, medium, high, xhigh, max, ultra_high)...",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (pendingAttachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pendingAttachments.forEach { att ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                SepiaChip(
                                    label = "📎 ${att.fileName}",
                                    selected = true,
                                    onClick = {}
                                )
                                SepiaButton(
                                    text = "×",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { onRemoveAttachment(att.id) }
                                )
                            }
                        }
                    }
                }

                if (showPrefillBox) {
                    SepiaTextField(
                        value = assistantPrefillInput,
                        onValueChange = onAssistantPrefillChange,
                        placeholder = "Optional Assistant Opening Line / Prefill…",
                        singleLine = false,
                        maxLines = 3,
                        minHeight = 40.dp,
                        maxHeight = 96.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Row 2: Dedicated Full-Width Prompt Input Box (Never squished on mobile!)
                SepiaTextField(
                    value = userPromptInput,
                    onValueChange = onUserPromptChange,
                    placeholder = "Write dialogue/prose, or type a scene direction and tap '🎬 Direct'…",
                    singleLine = false,
                    maxLines = 5,
                    minHeight = 46.dp,
                    maxHeight = 140.dp,
                    modifier = Modifier.fillMaxWidth()
                )

                // Row 3: Action Buttons + Circular Token Gauge Ring (Horizontally scrollable on narrow mobile screens)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SepiaButton(
                            text = "📎 Attach",
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = onAddAttachment
                        )

                        SepiaContextGaugeRing(
                            usedTokens = liveContextTokens,
                            contextLimitTokens = activeProfile.contextWindowTokens,
                            sessionCostUsd = sessionTotalCostUsd,
                            onClick = onOpenPricingSettings
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SepiaButton(
                            text = "✍ Impersonate",
                            compact = true,
                            enabled = !isStreaming,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = onImpersonateDraft
                        )

                        SepiaButton(
                            text = "🎬 Direct",
                            compact = true,
                            enabled = !isStreaming && (userPromptInput.isNotBlank() || assistantPrefillInput.isNotBlank()),
                            style = SepiaButtonStyle.SECONDARY,
                            onClick = onSendGuidedDirection
                        )

                        SepiaButton(
                            text = if (isStreaming) "Writing…" else "Inscribe ↵",
                            compact = true,
                            enabled = !isStreaming && (userPromptInput.isNotBlank() || assistantPrefillInput.isNotBlank() || pendingAttachments.isNotEmpty()),
                            style = SepiaButtonStyle.PRIMARY,
                            onClick = onSend
                        )
                    }
                }
            }
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
    onForkHere: () -> Unit,
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
            if (activeVar.content.isNotBlank() || activeVar.mediaItems.isEmpty()) {
                LiteraryProseRenderer(
                    text = activeVar.content.ifBlank { "…" }
                )
            }
        }

        // Inline Generated Images (Google Gemini, OpenAI GPT Image, ByteDance Seedream)
        // & Generated Videos (ByteDance Seedance)
        if (activeVar.mediaItems.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                activeVar.mediaItems.forEach { media ->
                    when (media.kind) {
                        MediaKind.IMAGE -> {
                            val bmp = remember(media.localFilePath) {
                                PlatformBridge.loadImageBitmapFromFile(media.localFilePath)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(p.cardBg)
                                    .border(1.dp, p.border, RoundedCornerShape(8.dp))
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
                                            .clickable {
                                                val target = media.localFilePath.ifBlank { media.remoteUrl }
                                                if (target.isNotBlank()) PlatformBridge.openOrPlayMedia(target)
                                            }
                                    )
                                } else {
                                    BasicText(
                                        text = "🖼 Generated Image (${media.localFilePath.ifBlank { media.remoteUrl }})",
                                        style = p.uiBodyStyle.copy(color = p.accent)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicText(
                                        text = "🖼 ${media.mimeType}${if (media.aspectRatio.isNotBlank()) " • ${media.aspectRatio}" else ""}",
                                        style = p.uiLabelStyle
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val openTarget = media.localFilePath.ifBlank { media.remoteUrl }
                                        if (openTarget.isNotBlank()) {
                                            SepiaButton(
                                                text = "Open Full Image",
                                                compact = true,
                                                style = SepiaButtonStyle.OUTLINE,
                                                onClick = { PlatformBridge.openOrPlayMedia(openTarget) }
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
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(p.cardBg)
                                    .border(1.dp, p.accentSecondary, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                BasicText(
                                    text = "🎬 Generated Video (${media.mimeType}${if (media.aspectRatio.isNotBlank()) " • ${media.aspectRatio}" else ""})",
                                    style = p.uiHeaderStyle.copy(fontSize = 14.5.sp, color = p.accent)
                                )
                                if (media.localFilePath.isNotBlank()) {
                                    BasicText(
                                        text = "Saved locally: ${media.localFilePath}",
                                        style = p.uiLabelStyle.copy(fontFamily = p.codeFontFamily)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val playTarget = media.localFilePath.ifBlank { media.remoteUrl }
                                    if (playTarget.isNotBlank()) {
                                        SepiaButton(
                                            text = "▶ Play Video (.MP4)",
                                            compact = true,
                                            style = SepiaButtonStyle.PRIMARY,
                                            onClick = { PlatformBridge.openOrPlayMedia(playTarget) }
                                        )
                                    }
                                    if (media.remoteUrl.isNotBlank() && media.localFilePath.isNotBlank()) {
                                        SepiaButton(
                                            text = "Open Remote Stream URL",
                                            compact = true,
                                            style = SepiaButtonStyle.OUTLINE,
                                            onClick = { PlatformBridge.openOrPlayMedia(media.remoteUrl) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
                    text = "⑂ Fork Chat",
                    compact = true,
                    style = SepiaButtonStyle.GHOST,
                    onClick = onForkHere
                )
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
                val cacheStr = if (m.cachedInputTokens > 0) " (Cache: ${m.cachedInputTokens})" else ""
                val thinkStr = if (m.reasoningTokens > 0) " • Think: ${m.reasoningTokens}" else ""
                val costStr = if (m.estimatedCostUsd > 0.0) " • Cost: ${formatUsdCost(m.estimatedCostUsd)}" else ""
                BasicText(
                    text = "TTFT ${m.ttftMs}ms • ${m.tokensPerSec} tok/s • In: ${m.inputTokens}$cacheStr$thinkStr • Out: ${m.outputTokens} • Total: ${m.totalTokens} tok$costStr",
                    style = p.uiLabelStyle.copy(fontSize = 10.5.sp, fontFamily = p.codeFontFamily)
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
    onDeletePresetFromLibrary: (String) -> Unit,
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
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                BasicText(
                    text = "Card & System Prompt Protocol (.png & .json)",
                    style = p.uiHeaderStyle
                )
                BasicText(
                    text = "Import/export .png files with instructions hardcoded inside (tEXt/iTXt chara/ccv3/parchment_prompt) or standalone .json files.",
                    style = p.uiLabelStyle
                )
            }
            SepiaButton(text = "✕ Back", compact = true, style = SepiaButtonStyle.OUTLINE, onClick = onClose)
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
            title = "System Prompt (Import / Export / Saved Presets)",
            subtitle = "Configure or import/export your System Prompt"
        ) {
            if (appState.systemPromptPresets.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    appState.systemPromptPresets.forEach { preset ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            SepiaButton(
                                text = preset.name,
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    onUpdateSession {
                                        it.copy(
                                            systemPrompt = preset.systemPrompt,
                                            authorsNote = preset.authorsNoteDefault.ifBlank { it.authorsNote }
                                        )
                                    }
                                    onNotify("Applied System Prompt '${preset.name}'.")
                                }
                            )
                            SepiaButton(
                                text = "×",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = { onDeletePresetFromLibrary(preset.id) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            SepiaTextField(
                value = activeSession.systemPrompt,
                onValueChange = { sp -> onUpdateSession { it.copy(systemPrompt = sp) } },
                label = "Active Session System Prompt",
                placeholder = "Enter system instructions (or leave empty)...",
                singleLine = false,
                minHeight = 90.dp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SepiaButton(
                    text = "+ Save Current to Presets",
                    compact = true,
                    enabled = activeSession.systemPrompt.isNotBlank(),
                    style = SepiaButtonStyle.SECONDARY,
                    onClick = {
                        val now = PlatformBridge.currentTimeMillis()
                        val preset = SystemPromptPreset(
                            id = "prompt-$now",
                            name = activeSession.title.ifBlank { "Preset" },
                            systemPrompt = activeSession.systemPrompt,
                            authorsNoteDefault = activeSession.authorsNote
                        )
                        onSavePresetToLibrary(preset)
                        onNotify("Saved preset '${preset.name}'.")
                    }
                )
                SepiaButton(
                    text = "📤 Export System Prompt as .JSON",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = {
                        scope.launch {
                            val preset = SystemPromptPreset(
                                id = "prompt-${PlatformBridge.currentTimeMillis()}",
                                name = activeSession.title.ifBlank { "System Prompt" },
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
                                name = activeSession.title.ifBlank { "System Prompt" },
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

        // Composable Context Fragments & Card Lorebook (Errata / SillyTavern character_book)
        FragmentsAndLorebookCard(
            activeSession = activeSession,
            onUpdateSession = onUpdateSession
        )
    }
}

@Composable
private fun FragmentsAndLorebookCard(
    activeSession: ChatSession,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit
) {
    val p = LocalParchmentPalette.current
    var newFragTitle by remember { mutableStateOf("") }
    var newFragKeywords by remember { mutableStateOf("") }
    var newFragContent by remember { mutableStateOf("") }

    val cardFragments = activeSession.activeCard?.embeddedFragments.orEmpty()

    SepiaSectionCard(
        title = "Composable Context Fragments & Lorebook (${activeSession.fragments.size + cardFragments.size})",
        subtitle = "Pin character notes, style rules, outlines, or keyword-triggered lore entries (auto-extracted from .png/.json character_book)"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SepiaTextField(
                    value = newFragTitle,
                    onValueChange = { newFragTitle = it },
                    label = "Fragment Title",
                    placeholder = "e.g. Chapter Outline / Magic System / Research Source",
                    modifier = Modifier.weight(1f)
                )
                SepiaTextField(
                    value = newFragKeywords,
                    onValueChange = { newFragKeywords = it },
                    label = "Trigger Keywords (Comma-separated, optional)",
                    placeholder = "Leave empty for Pinned, or e.g. morpeth, scriptorium",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                SepiaTextField(
                    value = newFragContent,
                    onValueChange = { newFragContent = it },
                    label = "Fragment Content",
                    placeholder = "Write the lore entry, character note, or guideline...",
                    singleLine = false,
                    modifier = Modifier.weight(1f)
                )
                SepiaButton(
                    text = "+ Add Fragment",
                    style = SepiaButtonStyle.PRIMARY,
                    enabled = newFragContent.isNotBlank(),
                    onClick = {
                        val now = PlatformBridge.currentTimeMillis()
                        val kwList = newFragKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val frag = ContextFragment(
                            id = "frag-$now",
                            title = newFragTitle.trim().ifBlank { "Fragment #${activeSession.fragments.size + 1}" },
                            content = newFragContent.trim(),
                            keywords = kwList,
                            mode = if (kwList.isEmpty()) FragmentActivationMode.PINNED else FragmentActivationMode.KEYWORD
                        )
                        onUpdateSession { it.copy(fragments = it.fragments + frag) }
                        newFragTitle = ""
                        newFragKeywords = ""
                        newFragContent = ""
                    }
                )
            }

            if (activeSession.fragments.isNotEmpty() || cardFragments.isNotEmpty()) {
                SepiaDivider()
            }

            // Session fragments
            activeSession.fragments.forEach { frag ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(p.elevatedBg.copy(alpha = 0.45f))
                        .border(1.dp, p.border, RoundedCornerShape(6.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val kwTag = if (frag.keywords.isNotEmpty()) " • Keys: ${frag.keywords.joinToString(", ")}" else ""
                        BasicText(
                            text = "${frag.title}$kwTag",
                            style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold)
                        )
                        BasicText(
                            text = frag.content.take(180),
                            style = p.uiLabelStyle
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SepiaChip(
                            label = frag.mode.label,
                            selected = frag.mode != FragmentActivationMode.MUTED,
                            onClick = {
                                val order = FragmentActivationMode.entries
                                val next = order[(order.indexOf(frag.mode) + 1) % order.size]
                                onUpdateSession { sess ->
                                    sess.copy(
                                        fragments = sess.fragments.map {
                                            if (it.id == frag.id) it.copy(mode = next) else it
                                        }
                                    )
                                }
                            }
                        )
                        SepiaButton(
                            text = "×",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                onUpdateSession { sess ->
                                    sess.copy(fragments = sess.fragments.filterNot { it.id == frag.id })
                                }
                            }
                        )
                    }
                }
            }

            // Card embedded character_book fragments
            if (cardFragments.isNotEmpty()) {
                BasicText(
                    text = "Embedded Card Lorebook Entries (${cardFragments.size}):",
                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                )
                cardFragments.forEach { frag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(p.elevatedBg.copy(alpha = 0.35f))
                            .border(1.dp, p.border, RoundedCornerShape(6.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val kwTag = if (frag.keywords.isNotEmpty()) " • Keys: ${frag.keywords.joinToString(", ")}" else ""
                            BasicText(
                                text = "${frag.title}$kwTag",
                                style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold)
                            )
                            BasicText(
                                text = frag.content.take(180),
                                style = p.uiLabelStyle
                            )
                        }
                        SepiaChip(
                            label = frag.mode.label,
                            selected = frag.mode != FragmentActivationMode.MUTED,
                            onClick = {
                                val order = FragmentActivationMode.entries
                                val next = order[(order.indexOf(frag.mode) + 1) % order.size]
                                onUpdateSession { sess ->
                                    val c = sess.activeCard ?: return@onUpdateSession sess
                                    sess.copy(
                                        activeCard = c.copy(
                                            embeddedFragments = c.embeddedFragments.map {
                                                if (it.id == frag.id) it.copy(mode = next) else it
                                            }
                                        )
                                    )
                                }
                            }
                        )
                    }
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
    onDeleteProfile: (String) -> Unit,
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
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                BasicText(
                    text = "Bleeding-Edge Providers, Model Fetcher & Custom Proxy",
                    style = p.uiHeaderStyle
                )
                BasicText(
                    text = "Native drivers for Google Gemini Interactions API, OpenAI Responses API, and Anthropic Messages API. Zero hardcoded models.",
                    style = p.uiLabelStyle
                )
            }
            SepiaButton(text = "✕ Back", compact = true, style = SepiaButtonStyle.OUTLINE, onClick = onClose)
        }

        // Profile Selector & Creator
        SepiaSectionCard(
            title = "Connection Profiles",
            subtitle = "Select, create, or delete connection profiles",
            headerAction = {
                SepiaButton(
                    text = "Delete Active Profile",
                    compact = true,
                    style = SepiaButtonStyle.DANGER,
                    onClick = { onDeleteProfile(activeProfile.id) }
                )
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                appState.profiles.forEach { prof ->
                    val modelTag = prof.defaultModel.ifBlank { "unset" }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SepiaChip(
                            label = "${prof.name} ($modelTag)",
                            selected = prof.id == activeProfile.id,
                            onClick = { onSelectProfile(prof.id) }
                        )
                        SepiaButton(
                            text = "×",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onDeleteProfile(prof.id) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SepiaButton(
                    text = "+ New Gemini Interactions Profile",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = { onAddProfile(ProviderProtocol.GEMINI_INTERACTIONS) }
                )
                SepiaButton(
                    text = "+ New OpenAI Responses Profile",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = { onAddProfile(ProviderProtocol.OPENAI_RESPONSES) }
                )
                SepiaButton(
                    text = "+ New Anthropic Messages Profile",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = { onAddProfile(ProviderProtocol.ANTHROPIC_MESSAGES) }
                )
                SepiaButton(
                    text = "+ New ByteDance Seedream / Seedance Profile",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = { onAddProfile(ProviderProtocol.BYTEDANCE_ARK) }
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

        // Model Pricing & Context Window (models.dev Auto-Fetch + Manual Override)
        ModelsDevPricingCard(
            activeProfile = activeProfile,
            onUpdateProfile = onUpdateProfile,
            onNotify = onNotify
        )

        // Image & Video Generation Configuration (Google Gemini Image, OpenAI GPT Image, ByteDance Seedream & Seedance)
        SepiaSectionCard(
            title = "Image & Video Generation (Google Gemini, OpenAI GPT Image, ByteDance Seedream / Seedance)",
            subtitle = "Configure image and video generation parameters for the active profile"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SepiaChip(
                        label = if (activeProfile.enableImageGeneration) "🖼 Image Generation: ON" else "🖼 Image Generation: OFF",
                        selected = activeProfile.enableImageGeneration,
                        onClick = { onUpdateProfile { it.copy(enableImageGeneration = !it.enableImageGeneration) } }
                    )

                    if (activeProfile.protocol == ProviderProtocol.BYTEDANCE_ARK) {
                        SepiaChip(
                            label = if (activeProfile.enableVideoGeneration) "🎬 Seedance Video Mode: ON" else "🎬 Seedance Video Mode: OFF",
                            selected = activeProfile.enableVideoGeneration,
                            onClick = { onUpdateProfile { it.copy(enableVideoGeneration = !it.enableVideoGeneration) } }
                        )
                        SepiaChip(
                            label = if (activeProfile.videoGenerateAudio) "🔊 Seedance Audio: ON" else "🔇 Seedance Audio: OFF",
                            selected = activeProfile.videoGenerateAudio,
                            onClick = { onUpdateProfile { it.copy(videoGenerateAudio = !it.videoGenerateAudio) } }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(text = "Aspect Ratio:", style = p.uiLabelStyle)
                    listOf("1:1", "16:9", "9:16", "4:3", "3:4", "21:9").forEach { ratio ->
                        SepiaChip(
                            label = ratio,
                            selected = activeProfile.mediaAspectRatio == ratio,
                            onClick = { onUpdateProfile { it.copy(mediaAspectRatio = ratio) } }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SepiaTextField(
                        value = activeProfile.mediaResolution,
                        onValueChange = { v -> onUpdateProfile { it.copy(mediaResolution = v) } },
                        label = "Resolution / Size (e.g. 1K, 2K, 1024x1024, 2048x2048, 720p, 1080p)",
                        monospace = true,
                        modifier = Modifier.weight(1.4f)
                    )
                    if (activeProfile.protocol == ProviderProtocol.BYTEDANCE_ARK) {
                        SepiaTextField(
                            value = activeProfile.videoDurationSeconds.toString(),
                            onValueChange = { v ->
                                v.toIntOrNull()?.let { sec -> onUpdateProfile { it.copy(videoDurationSeconds = sec.coerceIn(2, 15)) } }
                            },
                            label = "Seedance Video Duration (sec)",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
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
                        ProviderProtocol.BYTEDANCE_ARK -> "ByteDance Ark /api/v3"
                    }
                    if (activeProfile.protocol != ProviderProtocol.ANTHROPIC_MESSAGES) {
                        SepiaChip(
                            label = fallbackLabel,
                            selected = activeProfile.useLegacyFallbackEndpoint,
                            onClick = { onUpdateProfile { it.copy(useLegacyFallbackEndpoint = !it.useLegacyFallbackEndpoint) } }
                        )
                    }
                }

                // Thinking / Reasoning Level Presets (minimal/none/low/medium/high/xhigh/max) + Manual Input
                BasicText(
                    text = "Thinking / Reasoning Effort Level (Gemini 4 Argon: low/medium/high • GPT-6.1 Sol & Opus 5.5: low/medium/high/xhigh/max):",
                    style = p.uiLabelStyle
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    THINKING_EFFORT_PRESETS.forEach { level ->
                        SepiaChip(
                            label = level,
                            selected = activeProfile.thinkingEffort.equals(level, ignoreCase = true),
                            onClick = { onUpdateProfile { it.copy(thinkingEffort = level) } }
                        )
                    }
                }
                SepiaTextField(
                    value = activeProfile.thinkingEffort,
                    onValueChange = { v -> onUpdateProfile { it.copy(thinkingEffort = v) } },
                    label = "Manual Thinking Level Value (Type any custom value if default options don't match your model/proxy)",
                    placeholder = "minimal, none, low, medium, high, xhigh, max, ultra_high...",
                    monospace = true
                )

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
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        label = "Thinking Budget Tokens (0 = Adaptive)",
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

@Composable
private fun ModelsDevPricingCard(
    activeProfile: ProviderProfile,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    var isFetchingModelsDev by remember { mutableStateOf(false) }
    var modelsDevStatus by remember(activeProfile.id, activeProfile.defaultModel) { mutableStateOf("") }

    fun saveCurrentManualSpec(updatedProfile: ProviderProfile): ProviderProfile {
        val mId = updatedProfile.defaultModel.trim()
        if (mId.isEmpty()) return updatedProfile
        val spec = ModelPricingSpec(
            modelId = mId,
            inputPricePerM = updatedProfile.inputPricePerM,
            outputPricePerM = updatedProfile.outputPricePerM,
            cacheReadPricePerM = updatedProfile.cacheReadPricePerM,
            cacheWritePricePerM = updatedProfile.cacheWritePricePerM,
            reasoningPricePerM = updatedProfile.reasoningPricePerM,
            contextWindowTokens = updatedProfile.contextWindowTokens,
            maxOutputTokens = updatedProfile.maxOutputTokens
        )
        return updatedProfile.copy(customModelSpecs = updatedProfile.customModelSpecs + (mId to spec))
    }

    SepiaSectionCard(
        title = "Model Pricing & Context Window (models.dev Auto-Fetch & Manual Input)",
        subtitle = "Active Model: ${activeProfile.defaultModel.ifBlank { "None selected" }} — Automatically fetch from models.dev or input values manually",
        headerAction = {
            SepiaButton(
                text = if (isFetchingModelsDev) "Fetching models.dev…" else "↻ Fetch from models.dev",
                compact = true,
                enabled = !isFetchingModelsDev && activeProfile.defaultModel.isNotBlank(),
                style = SepiaButtonStyle.PRIMARY,
                onClick = {
                    isFetchingModelsDev = true
                    modelsDevStatus = "Querying https://models.dev/api.json for '${activeProfile.defaultModel}'…"
                    scope.launch {
                        ModelsDevClient.fetchModelSpec(activeProfile.defaultModel, forceRefresh = false).fold(
                            onSuccess = { spec ->
                                onUpdateProfile { prof ->
                                    saveCurrentManualSpec(
                                        prof.copy(
                                            contextWindowTokens = spec.contextWindowTokens,
                                            maxOutputTokens = spec.maxOutputTokens,
                                            inputPricePerM = spec.inputPricePerM,
                                            outputPricePerM = spec.outputPricePerM,
                                            cacheReadPricePerM = spec.cacheReadPricePerM,
                                            cacheWritePricePerM = spec.cacheWritePricePerM,
                                            reasoningPricePerM = spec.reasoningPricePerM
                                        )
                                    )
                                }
                                modelsDevStatus = "Loaded from models.dev (${spec.providerName}): Context ${formatCompactTokens(spec.contextWindowTokens)}, In $${spec.inputPricePerM}/M, Out $${spec.outputPricePerM}/M, Cache $${spec.cacheReadPricePerM}/M"
                                onNotify("Fetched '${spec.displayName}' pricing & context window from models.dev.")
                            },
                            onFailure = { err ->
                                modelsDevStatus = err.message ?: "Not found in models.dev — enter values manually below."
                            }
                        )
                        isFetchingModelsDev = false
                    }
                }
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (modelsDevStatus.isNotBlank()) {
                BasicText(
                    text = modelsDevStatus,
                    style = p.uiLabelStyle.copy(color = p.accent)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaTextField(
                    value = activeProfile.contextWindowTokens.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { num ->
                            onUpdateProfile { saveCurrentManualSpec(it.copy(contextWindowTokens = num.coerceAtLeast(1024))) }
                        }
                    },
                    label = "Context Window (tokens)",
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
                SepiaTextField(
                    value = activeProfile.maxOutputTokens.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { num ->
                            onUpdateProfile { saveCurrentManualSpec(it.copy(maxOutputTokens = num.coerceAtLeast(256))) }
                        }
                    },
                    label = "Max Output Limit (tokens)",
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaTextField(
                    value = activeProfile.inputPricePerM.toString(),
                    onValueChange = { v ->
                        v.toDoubleOrNull()?.let { num ->
                            onUpdateProfile { saveCurrentManualSpec(it.copy(inputPricePerM = num.coerceAtLeast(0.0))) }
                        }
                    },
                    label = "Input ($ / 1M tok)",
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
                SepiaTextField(
                    value = activeProfile.outputPricePerM.toString(),
                    onValueChange = { v ->
                        v.toDoubleOrNull()?.let { num ->
                            onUpdateProfile { saveCurrentManualSpec(it.copy(outputPricePerM = num.coerceAtLeast(0.0))) }
                        }
                    },
                    label = "Output ($ / 1M tok)",
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaTextField(
                    value = activeProfile.cacheReadPricePerM.toString(),
                    onValueChange = { v ->
                        v.toDoubleOrNull()?.let { num ->
                            onUpdateProfile { saveCurrentManualSpec(it.copy(cacheReadPricePerM = num.coerceAtLeast(0.0))) }
                        }
                    },
                    label = "Cache Read ($ / 1M)",
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
                SepiaTextField(
                    value = activeProfile.cacheWritePricePerM.toString(),
                    onValueChange = { v ->
                        v.toDoubleOrNull()?.let { num ->
                            onUpdateProfile { saveCurrentManualSpec(it.copy(cacheWritePricePerM = num.coerceAtLeast(0.0))) }
                        }
                    },
                    label = "Cache Write ($ / 1M)",
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
                SepiaTextField(
                    value = activeProfile.reasoningPricePerM.toString(),
                    onValueChange = { v ->
                        v.toDoubleOrNull()?.let { num ->
                            onUpdateProfile { saveCurrentManualSpec(it.copy(reasoningPricePerM = num.coerceAtLeast(0.0))) }
                        }
                    },
                    label = "Reasoning ($ / 1M)",
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ============================================================================================
// PANEL 3: SEPIA & CUSTOM THEME STUDIO + IN-APP OTA UPDATER + ZERO-TRACE STORAGE CONTROL
// ============================================================================================
@Composable
private fun ThemeAndStoragePanel(
    appState: AppStateSnapshot,
    theme: ThemeConfig,
    otaGithubRepo: String,
    autoCheckOta: Boolean,
    otaState: OtaUpdateState,
    onUpdateOtaSettings: (String, Boolean) -> Unit,
    onCheckOtaNow: () -> Unit,
    onInstallOtaRelease: (OtaReleaseInfo) -> Unit,
    onUpdateTheme: (ThemeConfig) -> Unit,
    onRestoreFullBackup: (AppStateSnapshot) -> Unit,
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
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                BasicText(
                    text = "Custom Sepia & Typography Studio",
                    style = p.uiHeaderStyle
                )
                BasicText(
                    text = "100% custom Compose Foundation UI. Customize every surface, ink tone, font, and margin, or import/export .theme.json.",
                    style = p.uiLabelStyle
                )
            }
            SepiaButton(text = "✕ Back", compact = true, style = SepiaButtonStyle.OUTLINE, onClick = onClose)
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
                    listOf("Inter", "Serif", "LaTeX", "JetBrainsMono Nerd").forEach { fam ->
                        SepiaChip(
                            label = if (fam == "Inter") "Inter (Bundled Default)" else fam,
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
                    listOf("JetBrainsMono Nerd", "Monospace", "Inter", "LaTeX").forEach { fam ->
                        SepiaChip(
                            label = if (fam == "JetBrainsMono Nerd") "JetBrainsMono Nerd (Bundled Default)" else fam,
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

                // 3. Size, Line Height & Page Geometry (2-column responsive layout)
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
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

        // Hex Color Token Customizer (2-column responsive rows)
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
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

        // In-App Zero-Browser OTA Auto-Updater Section
        SepiaSectionCard(
            title = "In-App OTA Updates (Android .APK & Windows .EXE)",
            subtitle = "Installed Version: v${OtaUpdateEngine.currentAppVersion} • Target Platform: ${PlatformBridge.platformName}"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaTextField(
                    value = otaGithubRepo,
                    onValueChange = { repo -> onUpdateOtaSettings(repo, autoCheckOta) },
                    label = "GitHub Release Repository (owner/repo)",
                    placeholder = "baros4294/parchment",
                    monospace = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SepiaChip(
                        label = if (autoCheckOta) "Auto-Check on Launch: ON" else "Auto-Check on Launch: OFF",
                        selected = autoCheckOta,
                        onClick = { onUpdateOtaSettings(otaGithubRepo, !autoCheckOta) }
                    )
                    SepiaButton(
                        text = if (otaState is OtaUpdateState.Checking) "Checking…" else "↻ Check for Update Now",
                        compact = true,
                        style = SepiaButtonStyle.PRIMARY,
                        enabled = otaState !is OtaUpdateState.Checking && otaState !is OtaUpdateState.Downloading,
                        onClick = onCheckOtaNow
                    )
                }

                when (otaState) {
                    is OtaUpdateState.Idle -> {
                        BasicText(
                            text = "Click '↻ Check for Update Now' to query GitHub Releases directly inside the app.",
                            style = p.uiLabelStyle
                        )
                    }
                    is OtaUpdateState.Checking -> {
                        BasicText(
                            text = "Querying GitHub Releases API ($otaGithubRepo)…",
                            style = p.uiBodyStyle.copy(color = p.accent)
                        )
                    }
                    is OtaUpdateState.Available -> {
                        val rel = otaState.release
                        val sizeMb = (rel.assetSizeBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                        BasicText(
                            text = "New Release Available: ${rel.tagName} (${rel.assetName} • $sizeMb MB)",
                            style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                        )
                        if (rel.releaseNotes.isNotBlank()) {
                            BasicText(
                                text = rel.releaseNotes.take(400),
                                style = p.uiLabelStyle
                            )
                        }
                        SepiaButton(
                            text = "⬇ Download & Install ${rel.tagName} In-App",
                            style = SepiaButtonStyle.PRIMARY,
                            onClick = { onInstallOtaRelease(rel) }
                        )
                    }
                    is OtaUpdateState.UpToDate -> {
                        BasicText(
                            text = "You are on the latest version (v${otaState.currentVersion}).",
                            style = p.uiBodyStyle.copy(color = p.accentSecondary, fontWeight = FontWeight.Medium)
                        )
                        otaState.latestRelease?.let { rel ->
                            SepiaButton(
                                text = "Force Re-Download & Re-Install ${rel.tagName} (${rel.assetName})",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = { onInstallOtaRelease(rel) }
                            )
                        }
                    }
                    is OtaUpdateState.Downloading -> {
                        val downMb = (otaState.downloadedBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                        val totalMb = (otaState.totalBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                        BasicText(
                            text = "Downloading ${otaState.release.assetName}: $downMb MB / $totalMb MB (${otaState.progressPercent}%)…",
                            style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                        )
                    }
                    is OtaUpdateState.Installing -> {
                        BasicText(
                            text = otaState.message,
                            style = p.uiBodyStyle.copy(color = p.accentSecondary, fontWeight = FontWeight.SemiBold)
                        )
                    }
                    is OtaUpdateState.Error -> {
                        BasicText(
                            text = "OTA Status: ${otaState.message}",
                            style = p.uiBodyStyle.copy(color = p.danger)
                        )
                    }
                }
            }
        }

        // Zero-Trace Storage, Portable Backup & Clean Uninstall Section
        SepiaSectionCard(
            title = "Portable Full Backup & Zero-Trace Storage",
            subtitle = "All local state lives in a single isolated directory: ${PlatformBridge.getAppStorageDirectoryPath()}"
        ) {
            BasicText(
                text = "Export a single-file backup to transfer all chats, cards, fragments, profiles, and themes between Windows and Android, or wipe all local data cleanly.",
                style = p.uiBodyStyle
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SepiaButton(
                    text = "📤 Export Full Backup (.json)",
                    style = SepiaButtonStyle.PRIMARY,
                    onClick = {
                        scope.launch {
                            val bytes = CardAndPromptCodec.prettyJson.encodeToString(AppStateSnapshot.serializer(), appState).encodeToByteArray()
                            val saved = PlatformBridge.saveFileForExport("parchment-full-backup.json", bytes)
                            if (saved != null) onNotify("Exported full backup to: $saved")
                        }
                    }
                )
                SepiaButton(
                    text = "📥 Restore Full Backup (.json)",
                    style = SepiaButtonStyle.SECONDARY,
                    onClick = {
                        scope.launch {
                            val picked = PlatformBridge.pickFileForImport(listOf("json")) ?: return@launch
                            runCatching {
                                CardAndPromptCodec.prettyJson.decodeFromString<AppStateSnapshot>(picked.bytes.decodeToString())
                            }.fold(
                                onSuccess = { restored -> onRestoreFullBackup(restored) },
                                onFailure = { err -> onNotify("Backup restore failed: ${err.message}") }
                            )
                        }
                    }
                )
                SepiaButton(
                    text = "Wipe All Local Data & Reset",
                    style = SepiaButtonStyle.DANGER,
                    onClick = onWipeAllData
                )
            }
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

private val LEGACY_HARDCODED_PRESET_IDS = setOf(
    "preset-literary-cowriter",
    "preset-immersive-rp",
    "preset-nonfiction-synthesis",
    "preset-workspace-agent"
)

private const val LEGACY_HARDCODED_SYS_PROMPT =
    "You are a master literary collaborator, researcher, and prose stylist. Prioritize vivid sensory grounding, authentic dialogue, psychological depth, and rhythmic sentence craft. Avoid repetitive clichés, purple melodrama, or unprompted moralizing summaries at the end of scenes."

private fun loadOrCreateDefaultAppState(): AppStateSnapshot {
    val raw = PlatformBridge.loadAppStateJson()
    if (!raw.isNullOrBlank()) {
        runCatching {
            val loaded = CardAndPromptCodec.prettyJson.decodeFromString<AppStateSnapshot>(raw)
            if (loaded.profiles.isNotEmpty() && loaded.sessions.isNotEmpty()) {
                val cleanedProfiles = loaded.profiles.map { prof ->
                    val strippedSaved = if (prof.savedModels.isNotEmpty() && prof.savedModels.all { it in LEGACY_HARDCODED_MODELS }) {
                        emptyList()
                    } else prof.savedModels
                    val strippedDefault = if (strippedSaved.isEmpty() && prof.defaultModel in LEGACY_HARDCODED_MODELS) {
                        ""
                    } else prof.defaultModel
                    val strippedBeta = if (prof.anthropicBetaHeaders == "interleaved-thinking-2025-05-14,output-128k-2025-02-19") {
                        ""
                    } else prof.anthropicBetaHeaders
                    prof.copy(
                        defaultModel = strippedDefault,
                        savedModels = strippedSaved,
                        anthropicBetaHeaders = strippedBeta
                    )
                }
                val cleanedSessions = loaded.sessions.map { sess ->
                    sess.copy(
                        title = if (sess.title == "Folio I — New Manuscript" || sess.title == "Untitled Manuscript") "New Chat" else sess.title,
                        userName = if (sess.userName == "Traveler") "User" else sess.userName,
                        systemPrompt = if (sess.systemPrompt == LEGACY_HARDCODED_SYS_PROMPT) "" else sess.systemPrompt,
                        modelOverride = if (sess.modelOverride in LEGACY_HARDCODED_MODELS) "" else sess.modelOverride
                    )
                }
                val cleanedPresets = loaded.systemPromptPresets.filterNot { it.id in LEGACY_HARDCODED_PRESET_IDS }
                val withByteDanceDefault = if (cleanedProfiles.none { it.protocol == ProviderProtocol.BYTEDANCE_ARK }) {
                    cleanedProfiles + ProviderProfile(
                        id = "prof-bytedance-ark",
                        name = "ByteDance Seedream / Seedance",
                        protocol = ProviderProtocol.BYTEDANCE_ARK,
                        enableImageGeneration = true
                    )
                } else cleanedProfiles

                val upgradedTheme = loaded.theme.let { th ->
                    val newProse = if (th.customProseFontPath.isBlank() && th.proseFontFamily == "Serif") "Inter" else th.proseFontFamily
                    val newCode = if (th.customCodeFontPath.isBlank() && th.codeFontFamily == "Monospace") "JetBrainsMono Nerd" else th.codeFontFamily
                    th.copy(proseFontFamily = newProse, codeFontFamily = newCode)
                }

                return loaded.copy(
                    profiles = withByteDanceDefault,
                    sessions = cleanedSessions,
                    systemPromptPresets = cleanedPresets,
                    theme = upgradedTheme
                )
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
    val byteDanceProfile = ProviderProfile(
        id = "prof-bytedance-ark",
        name = "ByteDance Seedream / Seedance",
        protocol = ProviderProtocol.BYTEDANCE_ARK,
        enableImageGeneration = true
    )

    val initialSession = ChatSession(
        id = "sess-initial",
        title = "New Chat",
        createdAt = now,
        updatedAt = now,
        profileId = geminiProfile.id
    )

    return AppStateSnapshot(
        profiles = listOf(geminiProfile, openAiProfile, anthropicProfile, byteDanceProfile),
        activeProfileId = geminiProfile.id,
        sessions = listOf(initialSession),
        activeSessionId = initialSession.id,
        systemPromptPresets = emptyList(),
        savedCards = emptyList(),
        theme = ThemeConfig()
    )
}
