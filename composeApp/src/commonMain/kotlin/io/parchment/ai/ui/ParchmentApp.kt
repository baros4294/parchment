package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.CharacterOrScenarioCard
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.FragmentActivationMode
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.MediaKind
import io.parchment.ai.model.MessageNavVisibility
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.QueuedPromptItem
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Process-wide supervisor scope so switching apps on Android (OnePlus ColorOS) or recomposing UI
 * never cancels an active generation stream.
 */
private val appProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

private val compactStateJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = false
}

enum class SidebarTab {
    ASSISTANTS,
    TOPICS
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

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun ParchmentApp() {
    val uiScope = rememberCoroutineScope()

    var appState by remember { mutableStateOf(loadOrCreateDefaultAppState()) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var activeSettingsCategory by remember { mutableStateOf(SettingsCategory.PREFERENCES) }
    var showSidebar by remember { mutableStateOf(!PlatformBridge.isAndroid) }
    var sidebarTab by remember { mutableStateOf(SidebarTab.TOPICS) }
    var showSessionInspector by remember { mutableStateOf(false) }
    var showQuickModelPopover by remember { mutableStateOf(false) }
    var bannerNotification by remember { mutableStateOf("") }

    // OTA Update state
    var otaState by remember { mutableStateOf<OtaUpdateState>(OtaUpdateState.Idle) }
    var dismissOtaBanner by remember { mutableStateOf(false) }

    // Streaming & 60/120fps VSYNC Character Pacer state
    var isStreaming by remember { mutableStateOf(false) }
    var isCurrentlyThinkingPhase by remember { mutableStateOf(false) }
    var streamingTargetNodeId by remember { mutableStateOf("") }
    var streamingStatus by remember { mutableStateOf("") }
    var streamingInteractionId by remember { mutableStateOf("") }
    var activeStreamJob by remember { mutableStateOf<Job?>(null) }

    // Queued follow-up prompts for `⏳ Queue` mode
    var queuedPrompts by remember { mutableStateOf<List<QueuedPromptItem>>(emptyList()) }

    val activeProfile = appState.profiles.find { it.id == appState.activeProfileId }
        ?: appState.profiles.first()
    val activeSession = appState.sessions.find { it.id == appState.activeSessionId }
        ?: appState.sessions.first()
    val activeAssistant = appState.assistants.find { it.id == activeSession.assistantId && it.id.isNotBlank() }
        ?: appState.assistants.find { it.id == appState.activeAssistantId && it.id.isNotBlank() }
        ?: appState.assistants.firstOrNull()
        ?: AssistantAgent(id = "", name = "Zero-Shot", iconEmoji = "✦", systemPrompt = "")

    // Per-session composer inputs (synced with activeSession.draftInput so switching chats preserves drafts!)
    var userPromptInput by remember(activeSession.id) { mutableStateOf(activeSession.draftInput) }
    var assistantPrefillInput by remember(activeSession.id) { mutableStateOf(activeSession.draftPrefill) }
    var showPrefillBox by remember { mutableStateOf(false) }
    var pendingAttachments by remember { mutableStateOf<List<PromptAttachment>>(emptyList()) }
    var diskPersistJob by remember { mutableStateOf<Job?>(null) }

    // Register synchronous flush callback for Android MainActivity.onPause/onStop & Desktop Shutdown Hook
    LaunchedEffect(Unit) {
        PlatformBridge.registerStateFlushCallback {
            runCatching {
                val encoded = compactStateJson.encodeToString(AppStateSnapshot.serializer(), appState)
                PlatformBridge.saveAppStateJson(encoded)
            }
        }
    }

    fun persist(updated: AppStateSnapshot, immediateFlush: Boolean = false) {
        appState = updated
        diskPersistJob?.cancel()
        if (immediateFlush) {
            appProcessScope.launch(Dispatchers.IO) {
                runCatching {
                    val encoded = compactStateJson.encodeToString(AppStateSnapshot.serializer(), updated)
                    PlatformBridge.saveAppStateJson(encoded)
                }
            }
        } else {
            diskPersistJob = appProcessScope.launch(Dispatchers.Default) {
                delay(250L)
                runCatching {
                    val encoded = compactStateJson.encodeToString(AppStateSnapshot.serializer(), appState)
                    PlatformBridge.saveAppStateJson(encoded)
                }
            }
        }
    }

    fun updateActiveSession(immediate: Boolean = false, transform: (ChatSession) -> ChatSession) {
        val targetId = activeSession.id
        val newSessions = appState.sessions.map {
            if (it.id == targetId) transform(it).copy(updatedAt = PlatformBridge.currentTimeMillis()) else it
        }
        persist(appState.copy(sessions = newSessions), immediateFlush = immediate)
    }

    fun updateSessionById(sessionId: String, immediate: Boolean = false, transform: (ChatSession) -> ChatSession) {
        val newSessions = appState.sessions.map {
            if (it.id == sessionId) transform(it).copy(updatedAt = PlatformBridge.currentTimeMillis()) else it
        }
        persist(appState.copy(sessions = newSessions), immediateFlush = immediate)
    }

    fun updateActiveSessionInMemoryOnly(sessionId: String, transform: (ChatSession) -> ChatSession) {
        val currentState = appState
        val newSessions = currentState.sessions.map {
            if (it.id == sessionId) transform(it) else it
        }
        appState = currentState.copy(sessions = newSessions)
    }

    fun updateActiveProfile(transform: (ProviderProfile) -> ProviderProfile) {
        val targetProfId = activeProfile.id
        val newProfiles = appState.profiles.map {
            if (it.id == targetProfId) transform(it) else it
        }
        persist(appState.copy(profiles = newProfiles), immediateFlush = true)
    }

    fun updateProfileById(profileId: String, transform: (ProviderProfile) -> ProviderProfile) {
        val newProfiles = appState.profiles.map {
            if (it.id == profileId) transform(it) else it
        }
        persist(appState.copy(profiles = newProfiles), immediateFlush = true)
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
        uiScope.launch {
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
        uiScope.launch {
            OtaUpdateEngine.downloadAndInstall(release).collect { st ->
                otaState = st
            }
        }
    }

    // Automatically fetch models ONLY when savedModels is empty AND never overwrite an existing user defaultModel!
    LaunchedEffect(activeProfile.id, activeProfile.baseUrl, activeProfile.apiKey, activeProfile.autoFetchModels) {
        val hasCredentialsOrCustomProxy = activeProfile.apiKey.isNotBlank() ||
            activeProfile.baseUrl.trimEnd('/') != activeProfile.protocol.defaultBaseUrl.trimEnd('/')
        if (activeProfile.autoFetchModels && hasCredentialsOrCustomProxy && activeProfile.savedModels.isEmpty()) {
            AiProviderEngine.fetchAvailableModels(activeProfile).onSuccess { fetched ->
                if (fetched.isNotEmpty()) {
                    val existingModel = activeProfile.defaultModel.trim()
                    val mergedList = if (existingModel.isNotEmpty()) (listOf(existingModel) + fetched).distinct() else fetched
                    val chosenDefault = existingModel.ifBlank { pickBestInitialChatModel(mergedList) }
                    updateProfileById(activeProfile.id) {
                        it.copy(
                            savedModels = mergedList,
                            defaultModel = chosenDefault
                        )
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
                updateProfileById(activeProfile.id) {
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
                    updateProfileById(activeProfile.id) {
                        it.copy(
                            contextWindowTokens = spec.contextWindowTokens,
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

    /**
     * Kelivo-style Automatic & Manual AI Title Generation.
     * Respects `session.isTitleManuallySet` unless `forceRegenerate = true` (when clicking `✦`).
     */
    fun generateTopicTitle(sessionId: String, forceRegenerate: Boolean = false) {
        val targetSess = appState.sessions.find { it.id == sessionId } ?: return
        if (!forceRegenerate && targetSess.isTitleManuallySet) return
        if (targetSess.messages.isEmpty()) return

        appProcessScope.launch {
            val dm = appState.defaultModels
            val resolvedProf = if (!dm.titleFollowGlobal && dm.titleProfileId.isNotBlank()) {
                appState.profiles.find { it.id == dm.titleProfileId } ?: activeProfile
            } else {
                appState.profiles.find { it.id == targetSess.profileId } ?: activeProfile
            }
            val resolvedModel = if (!dm.titleFollowGlobal && dm.titleModelId.isNotBlank()) {
                dm.titleModelId
            } else {
                targetSess.modelOverride.ifBlank { resolvedProf.defaultModel }.ifBlank { dm.globalModelId }
            }
            if (resolvedModel.isBlank()) return@launch

            val transcriptSnippet = targetSess.messages.take(4).joinToString("\n\n") { n ->
                val who = if (n.role == MessageRole.ASSISTANT) "Assistant" else "User"
                "$who: ${n.activeVariant.content.take(450)}"
            }
            val prompt = "${dm.titlePromptTemplate}\n\nConversation:\n$transcriptSnippet"

            AiProviderEngine.generateUtilityText(resolvedProf, resolvedModel, prompt).onSuccess { genTitle ->
                val cleaned = genTitle.lines().firstOrNull()?.trim()?.removeSurrounding("\"")?.take(60).orEmpty()
                if (cleaned.isNotBlank()) {
                    updateSessionById(sessionId, immediate = true) { s ->
                        if (!forceRegenerate && s.isTitleManuallySet) s else s.copy(title = cleaned)
                    }
                }
            }
        }
    }

    /**
     * Core Generation Dispatcher with:
     * - 60/120fps VSYNC Character Pacer (`withFrameNanos`)
     * - Auto-Open / Auto-Collapse Reasoning Phase tracking
     * - 1.5s Atomic Disk Checkpoints + Cancellation/Close Preservation (Zero data loss on OnePlus!)
     * - Automatic Queue Drain (`⏳ Queue`) and First-Turn Auto-Title Generation
     */
    var dispatchNextQueuedItem: ((String, QueuedPromptItem) -> Unit)? = null

    fun triggerGeneration(
        sessionId: String,
        historyForCall: List<MessageNode>,
        targetAssistantNodeId: String,
        targetVariantId: String,
        continuePrefix: String = ""
    ) {
        activeStreamJob?.cancel()
        val sessionSnap = appState.sessions.find { it.id == sessionId } ?: activeSession
        val assistantSnap = appState.assistants.find { it.id == sessionSnap.assistantId } ?: activeAssistant
        val profileSnap = appState.profiles.find { it.id == sessionSnap.profileId } ?: activeProfile

        // Merge Assistant-level system prompt, fragments, and nullable sampling overrides
        val mergedSystemPrompt = buildString {
            if (assistantSnap.systemPrompt.isNotBlank()) {
                appendLine(assistantSnap.systemPrompt.trim())
            }
            if (sessionSnap.systemPrompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                appendLine(sessionSnap.systemPrompt.trim())
            }
        }.trim()

        val mergedSessionForApi = sessionSnap.copy(
            systemPrompt = mergedSystemPrompt,
            authorsNote = sessionSnap.authorsNote.ifBlank { assistantSnap.authorsNote },
            activeCard = sessionSnap.activeCard ?: assistantSnap.activeCard,
            fragments = (appState.globalWorldBook + assistantSnap.fragments + sessionSnap.fragments).distinctBy { it.id }
        )

        val effectiveHistory = if (assistantSnap.contextMessageLimit > 0 && historyForCall.size > assistantSnap.contextMessageLimit) {
            historyForCall.takeLast(assistantSnap.contextMessageLimit)
        } else {
            historyForCall
        }

        val effectiveProfileForApi = profileSnap.copy(
            temperature = assistantSnap.temperature ?: profileSnap.temperature,
            topP = assistantSnap.topP ?: profileSnap.topP,
            maxOutputTokens = assistantSnap.maxOutputTokens ?: profileSnap.maxOutputTokens,
            thinkingEffort = assistantSnap.thinkingEffort.ifBlank { profileSnap.thinkingEffort }
        )

        val modelName = mergedSessionForApi.modelOverride.ifBlank {
            assistantSnap.defaultModelId.ifBlank {
                effectiveProfileForApi.defaultModel.ifBlank { appState.defaultModels.globalModelId }
            }
        }

        isStreaming = true
        isCurrentlyThinkingPhase = false
        streamingTargetNodeId = targetAssistantNodeId
        streamingStatus = "Connecting to ${effectiveProfileForApi.name} ($modelName)…"
        streamingInteractionId = ""

        activeStreamJob = appProcessScope.launch {
            val networkContentTarget = StringBuilder(continuePrefix)
            val networkThinkingTarget = StringBuilder()
            val signatureSb = StringBuilder()
            var displayedContentLen = continuePrefix.length
            var displayedThinkingLen = 0
            val accumulatedTools = mutableListOf<ToolCallRecord>()
            val accumulatedMedia = mutableListOf<GeneratedMediaItem>()
            var rawReq = ""
            var rawResp = ""
            var networkDone = false
            var thinkingStartMs = 0L
            var thinkingDurationMs = 0L
            var lastDiskCheckpointMs = PlatformBridge.currentTimeMillis()

            fun patchVariantMemory(updater: (MessageVariant) -> MessageVariant) {
                updateActiveSessionInMemoryOnly(sessionId) { sess ->
                    sess.copy(
                        messages = sess.messages.map { node ->
                            if (node.id != targetAssistantNodeId) node
                            else node.copy(
                                variants = node.variants.map { v ->
                                    if (v.id == targetVariantId) updater(v) else v
                                }
                            )
                        }
                    )
                }
            }

            fun flushCheckpointToDisk(isFinal: Boolean, finalUpdater: ((MessageVariant) -> MessageVariant)? = null) {
                val curContent = if (isFinal) networkContentTarget.toString() else networkContentTarget.substring(0, displayedContentLen.coerceAtMost(networkContentTarget.length))
                val curThink = if (isFinal) networkThinkingTarget.toString() else networkThinkingTarget.substring(0, displayedThinkingLen.coerceAtMost(networkThinkingTarget.length))
                val curSig = signatureSb.toString()
                val curTools = accumulatedTools.toList()
                val curMedia = accumulatedMedia.toList()
                updateSessionById(sessionId, immediate = true) { sess ->
                    sess.copy(
                        messages = sess.messages.map { node ->
                            if (node.id != targetAssistantNodeId) node
                            else node.copy(
                                variants = node.variants.map { v ->
                                    if (v.id != targetVariantId) v
                                    else {
                                        val base = v.copy(
                                            content = curContent,
                                            thinkingSummary = curThink,
                                            thinkingSignature = curSig,
                                            thinkingDurationMs = thinkingDurationMs,
                                            toolCalls = curTools,
                                            mediaItems = curMedia,
                                            pendingBackgroundInteractionId = if (isFinal) "" else streamingInteractionId,
                                            isStreaming = !isFinal
                                        )
                                        if (finalUpdater != null) finalUpdater(base) else base
                                    }
                                }
                            )
                        }
                    )
                }
            }

            // 60fps / 120fps Hardware VSYNC Character Pacer Coroutine (`withFrameNanos`)
            val pacerJob = launch {
                while (!networkDone || displayedContentLen < networkContentTarget.length || displayedThinkingLen < networkThinkingTarget.length) {
                    withFrameNanos { _ ->
                        var changed = false
                        val targetThinkLen = networkThinkingTarget.length
                        if (displayedThinkingLen < targetThinkLen) {
                            val diff = targetThinkLen - displayedThinkingLen
                            val step = maxOf(2, diff / 4)
                            displayedThinkingLen = (displayedThinkingLen + step).coerceAtMost(targetThinkLen)
                            changed = true
                        }
                        val targetContLen = networkContentTarget.length
                        if (displayedContentLen < targetContLen) {
                            val diff = targetContLen - displayedContentLen
                            val step = maxOf(2, diff / 4)
                            displayedContentLen = (displayedContentLen + step).coerceAtMost(targetContLen)
                            changed = true
                        }
                        if (changed) {
                            val visibleCont = networkContentTarget.substring(0, displayedContentLen)
                            val visibleThink = networkThinkingTarget.substring(0, displayedThinkingLen)
                            val curSig = signatureSb.toString()
                            patchVariantMemory { v ->
                                v.copy(
                                    content = visibleCont,
                                    thinkingSummary = visibleThink,
                                    thinkingSignature = curSig,
                                    thinkingDurationMs = thinkingDurationMs,
                                    isStreaming = true
                                )
                            }
                        }
                    }
                    // Atomic background disk checkpoint every 1.5 seconds so switching apps / OnePlus battery kill NEVER loses progress
                    val now = PlatformBridge.currentTimeMillis()
                    if (now - lastDiskCheckpointMs >= 1500L) {
                        lastDiskCheckpointMs = now
                        flushCheckpointToDisk(isFinal = false)
                    }
                }
            }

            try {
                AiProviderEngine.streamGeneration(
                    profile = effectiveProfileForApi,
                    session = mergedSessionForApi,
                    historyNodes = effectiveHistory,
                    continueFromAssistantPrefix = continuePrefix
                ).collect { update ->
                    when (update) {
                        is StreamUpdate.Status -> {
                            streamingStatus = update.message
                            if (update.interactionId.isNotBlank()) {
                                streamingInteractionId = update.interactionId
                                flushCheckpointToDisk(isFinal = false)
                            }
                        }
                        is StreamUpdate.ThinkingDelta -> {
                            if (thinkingStartMs == 0L) thinkingStartMs = PlatformBridge.currentTimeMillis()
                            thinkingDurationMs = (PlatformBridge.currentTimeMillis() - thinkingStartMs).coerceAtLeast(100L)
                            isCurrentlyThinkingPhase = true
                            networkThinkingTarget.append(update.delta)
                            signatureSb.append(update.signatureDelta)
                            val thinkWords = countWordsFast(networkThinkingTarget)
                            streamingStatus = "Reasoning ($modelName • $thinkWords words • ${thinkingDurationMs / 1000.0}s)…"
                        }
                        is StreamUpdate.ContentDelta -> {
                            if (isCurrentlyThinkingPhase) {
                                if (thinkingStartMs > 0L) {
                                    thinkingDurationMs = (PlatformBridge.currentTimeMillis() - thinkingStartMs).coerceAtLeast(thinkingDurationMs)
                                }
                                isCurrentlyThinkingPhase = false
                            }
                            networkContentTarget.append(update.delta)
                            val wordCount = countWordsFast(networkContentTarget)
                            streamingStatus = "Writing ($modelName • +$wordCount words)…"
                        }
                        is StreamUpdate.ToolCallEvent -> {
                            accumulatedTools.add(update.toolCall)
                            streamingStatus = "Executed workspace tool: ${update.toolCall.name}"
                            val toolsSnap = accumulatedTools.toList()
                            patchVariantMemory { v -> v.copy(toolCalls = toolsSnap) }
                        }
                        is StreamUpdate.MediaGenerated -> {
                            accumulatedMedia.add(update.item)
                            streamingStatus = "Generated ${update.item.kind.name.lowercase()} (${accumulatedMedia.size})"
                            val mediaSnap = accumulatedMedia.toList()
                            patchVariantMemory { v -> v.copy(mediaItems = mediaSnap) }
                        }
                        is StreamUpdate.RawWireSnapshot -> {
                            if (update.requestJson.isNotEmpty()) rawReq = update.requestJson.take(4096)
                            if (update.latestResponseChunk.isNotEmpty()) rawResp = update.latestResponseChunk.take(4096)
                        }
                        is StreamUpdate.Completed -> {
                            if (update.fullContent.length > networkContentTarget.length) {
                                networkContentTarget.clear()
                                networkContentTarget.append(update.fullContent)
                            }
                            if (update.fullThinking.length > networkThinkingTarget.length) {
                                networkThinkingTarget.clear()
                                networkThinkingTarget.append(update.fullThinking)
                            }
                            networkDone = true
                            pacerJob.join()
                            flushCheckpointToDisk(isFinal = true) { v ->
                                v.copy(
                                    content = update.fullContent,
                                    thinkingSummary = update.fullThinking,
                                    thinkingSignature = update.thinkingSignature,
                                    thinkingDurationMs = thinkingDurationMs,
                                    toolCalls = update.toolCalls,
                                    mediaItems = update.mediaItems,
                                    providerInteractionId = update.providerInteractionId,
                                    pendingBackgroundInteractionId = "",
                                    isStreaming = false,
                                    providerProtocol = effectiveProfileForApi.protocol,
                                    modelUsed = modelName,
                                    tokenMetrics = update.metrics,
                                    rawRequestPreview = update.rawRequest.take(4096),
                                    rawResponsePreview = update.rawResponse.take(4096)
                                )
                            }
                        }
                        is StreamUpdate.Error -> {
                            networkDone = true
                            pacerJob.cancel()
                            val partialProse = networkContentTarget.toString()
                            flushCheckpointToDisk(isFinal = true) { v ->
                                val errorNotice = if (partialProse.isBlank()) {
                                    "**[Provider Error: ${update.message}]**\n\nCheck your API Key, Base URL (`${effectiveProfileForApi.baseUrl}`), or Model ID (`$modelName`) in **⚙ Settings → Providers**."
                                } else {
                                    "$partialProse\n\n*[Stream paused/interrupted: ${update.message} — tap '→ Continue' to resume seamlessly]*"
                                }
                                v.copy(
                                    content = errorNotice,
                                    thinkingSummary = networkThinkingTarget.toString(),
                                    thinkingSignature = signatureSb.toString(),
                                    thinkingDurationMs = thinkingDurationMs,
                                    isStreaming = false,
                                    rawRequestPreview = rawReq,
                                    rawResponsePreview = update.rawDetails.ifBlank { rawResp }.take(4096)
                                )
                            }
                        }
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                // User pressed Stop, Steer, or app closed mid-stream: ALWAYS save 100% of partial progress!
                networkDone = true
                pacerJob.cancel()
                flushCheckpointToDisk(isFinal = true)
                throw c
            } finally {
                networkDone = true
                isStreaming = false
                isCurrentlyThinkingPhase = false
                streamingStatus = ""
                streamingTargetNodeId = ""

                // Auto-generate Topic Title after the first assistant turn completes
                val updatedSess = appState.sessions.find { it.id == sessionId }
                if (updatedSess != null && !updatedSess.isTitleManuallySet && updatedSess.title == "New Chat" && updatedSess.messages.size in 2..3) {
                    generateTopicTitle(sessionId, forceRegenerate = false)
                }

                // If there are queued prompts (`⏳ Queue` mode), automatically dispatch the next one!
                if (queuedPrompts.isNotEmpty()) {
                    val nextItem = queuedPrompts.first()
                    queuedPrompts = queuedPrompts.drop(1)
                    dispatchNextQueuedItem?.invoke(sessionId, nextItem)
                }
            }
        }
    }

    fun dispatchPromptItem(
        sessionId: String,
        text: String,
        prefill: String,
        attachments: List<PromptAttachment>,
        isGuidedDirection: Boolean
    ) {
        val targetSess = appState.sessions.find { it.id == sessionId } ?: activeSession
        val now = PlatformBridge.currentTimeMillis()
        val updatedMessages = targetSess.messages.toMutableList()

        val historyForCall = if (isGuidedDirection) {
            val syntheticDirectionNode = MessageNode(
                id = "node-dir-$now",
                role = MessageRole.USER,
                variants = listOf(
                    MessageVariant(
                        id = "var-dir-$now",
                        content = "[Scene Direction / Guidance for next passage: $text\nWrite the next narrative passage directly following this direction without acknowledging these instructions.]",
                        createdAt = now
                    )
                )
            )
            updatedMessages + syntheticDirectionNode
        } else {
            if (text.isNotEmpty() || attachments.isNotEmpty()) {
                val userVar = MessageVariant(
                    id = "var-$now-u",
                    content = text,
                    attachments = attachments,
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
            updatedMessages.toList()
        }

        val assistantVarId = "var-${now + 1}-a"
        val assistantNodeId = "node-${now + 1}-a"
        val modelName = targetSess.modelOverride.ifBlank { activeProfile.defaultModel }

        val initialAssistantVar = MessageVariant(
            id = assistantVarId,
            content = prefill,
            modelUsed = modelName,
            providerProtocol = activeProfile.protocol,
            isStreaming = true,
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

        // NEVER overwrite a manually named chat title with the first message!
        updateSessionById(sessionId, immediate = true) {
            it.copy(
                messages = updatedMessages,
                draftInput = "",
                draftPrefill = ""
            )
        }

        triggerGeneration(
            sessionId = sessionId,
            historyForCall = historyForCall,
            targetAssistantNodeId = assistantNodeId,
            targetVariantId = assistantVarId,
            continuePrefix = prefill
        )
    }

    dispatchNextQueuedItem = { sessId, nextItem ->
        dispatchPromptItem(
            sessionId = sessId,
            text = nextItem.text,
            prefill = nextItem.prefill,
            attachments = nextItem.attachments,
            isGuidedDirection = nextItem.isGuidedDirection
        )
    }

    fun sendUserTurn() {
        val rawInput = userPromptInput.trim()
        val prefill = assistantPrefillInput.trim()
        var currentAtts = pendingAttachments
        if (rawInput.isEmpty() && prefill.isEmpty() && currentAtts.isEmpty()) return

        // Support Kelivo's "Paste long text as file" threshold
        val prefs = appState.preferences
        val finalText = if (prefs.pasteLongTextAsFile && rawInput.length >= prefs.pasteConversionThresholdChars) {
            val now = PlatformBridge.currentTimeMillis()
            currentAtts = currentAtts + PromptAttachment(
                id = "att-paste-$now",
                fileName = "pasted-text-${rawInput.length}chars.txt",
                mimeType = "text/plain",
                textContent = rawInput
            )
            "[Attached pasted text (${rawInput.length} characters)]"
        } else {
            rawInput
        }

        userPromptInput = ""
        assistantPrefillInput = ""
        pendingAttachments = emptyList()

        dispatchPromptItem(
            sessionId = activeSession.id,
            text = finalText,
            prefill = prefill,
            attachments = currentAtts,
            isGuidedDirection = false
        )
    }

    fun sendGuidedDirection() {
        val direction = userPromptInput.trim()
        val prefill = assistantPrefillInput.trim()
        if (direction.isEmpty() && prefill.isEmpty()) return
        userPromptInput = ""
        assistantPrefillInput = ""
        dispatchPromptItem(
            sessionId = activeSession.id,
            text = direction,
            prefill = prefill,
            attachments = emptyList(),
            isGuidedDirection = true
        )
    }

    /**
     * `⚡ Steer` Mode: Immediately stops the in-flight stream, keeps the partial AI output up to this exact millisecond,
     * appends the user's new follow-up prompt, and immediately starts generating from the new prompt!
     */
    fun triggerSteerNow() {
        val steerText = userPromptInput.trim()
        val prefill = assistantPrefillInput.trim()
        val atts = pendingAttachments
        if (steerText.isEmpty() && prefill.isEmpty() && atts.isEmpty()) return

        userPromptInput = ""
        assistantPrefillInput = ""
        pendingAttachments = emptyList()

        uiScope.launch {
            activeStreamJob?.cancel()
            activeStreamJob?.join()
            dispatchPromptItem(
                sessionId = activeSession.id,
                text = steerText,
                prefill = prefill,
                attachments = atts,
                isGuidedDirection = false
            )
        }
    }

    /**
     * `⏳ Queue` Mode: Adds the current prompt to the queue while the current reply finishes streaming.
     */
    fun enqueueFollowUpPrompt() {
        val qText = userPromptInput.trim()
        val prefill = assistantPrefillInput.trim()
        val atts = pendingAttachments
        if (qText.isEmpty() && prefill.isEmpty() && atts.isEmpty()) return

        val item = QueuedPromptItem(
            id = "q-${PlatformBridge.currentTimeMillis()}",
            text = qText,
            prefill = prefill,
            attachments = atts
        )
        queuedPrompts = queuedPrompts + item
        userPromptInput = ""
        assistantPrefillInput = ""
        pendingAttachments = emptyList()
        updateActiveSession { it.copy(draftInput = "", draftPrefill = "") }
    }

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

        activeStreamJob = appProcessScope.launch {
            val buf = StringBuilder()
            try {
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
                        }
                        is StreamUpdate.Error -> {
                            bannerNotification = "Impersonate error: ${update.message}"
                        }
                        else -> {}
                    }
                }
            } finally {
                isStreaming = false
                streamingStatus = ""
            }
        }
    }

    fun triggerAutoSummarizeStory() {
        if (isStreaming || activeSession.messages.isEmpty()) return
        val now = PlatformBridge.currentTimeMillis()
        val dm = appState.defaultModels
        val summaryProf = if (!dm.summaryFollowGlobal && dm.summaryProfileId.isNotBlank()) {
            appState.profiles.find { it.id == dm.summaryProfileId } ?: activeProfile
        } else activeProfile
        val summaryModel = if (!dm.summaryFollowGlobal && dm.summaryModelId.isNotBlank()) {
            dm.summaryModelId
        } else activeSession.modelOverride.ifBlank { summaryProf.defaultModel }

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
        streamingStatus = "Summarizing story continuity into memory ($summaryModel)…"

        activeStreamJob = appProcessScope.launch {
            val buf = StringBuilder()
            try {
                AiProviderEngine.streamGeneration(
                    profile = summaryProf.copy(defaultModel = summaryModel),
                    session = activeSession.copy(modelOverride = summaryModel),
                    historyNodes = historyForCall
                ).collect { update ->
                    when (update) {
                        is StreamUpdate.ContentDelta -> {
                            buf.append(update.delta)
                            updateActiveSession { it.copy(storySummary = buf.toString()) }
                        }
                        is StreamUpdate.Completed -> {
                            updateActiveSession(immediate = true) { it.copy(storySummary = update.fullContent.trim()) }
                            bannerNotification = "Updated Rolling Continuity Memory."
                        }
                        is StreamUpdate.Error -> {
                            bannerNotification = "Summarize error: ${update.message}"
                        }
                        else -> {}
                    }
                }
            } finally {
                isStreaming = false
                streamingStatus = ""
            }
        }
    }

    fun createNewTopicForAssistant(assistant: AssistantAgent) {
        val now = PlatformBridge.currentTimeMillis()
        val profId = assistant.defaultProfileId.ifBlank { appState.defaultModels.globalProfileId.ifBlank { activeProfile.id } }
        val modId = assistant.defaultModelId.ifBlank {
            appState.defaultModels.globalModelId.ifBlank {
                (appState.profiles.find { it.id == profId } ?: activeProfile).defaultModel
            }
        }
        val card = assistant.activeCard
        val initialMessages = if (card != null && card.firstMessage.isNotBlank()) {
            val allGreetings = (listOf(card.firstMessage) + card.alternateGreetings).filter { it.isNotBlank() }
            listOf(
                MessageNode(
                    id = "node-greeting-$now",
                    role = MessageRole.ASSISTANT,
                    variants = allGreetings.mapIndexed { idx, g ->
                        MessageVariant(
                            id = "var-greeting-$idx-$now",
                            content = g.replace("{{char}}", card.name).replace("{{user}}", "User"),
                            modelUsed = "Card Greeting #${idx + 1}",
                            createdAt = now
                        )
                    }
                )
            )
        } else emptyList()

        val newSess = ChatSession(
            id = "sess-$now",
            title = "New Chat",
            createdAt = now,
            updatedAt = now,
            profileId = profId,
            modelOverride = modId,
            assistantId = assistant.id,
            activeCard = card,
            workspacePath = assistant.workspacePath,
            workspaceMode = assistant.workspaceMode,
            messages = initialMessages
        )
        persist(
            appState.copy(
                activeAssistantId = assistant.id,
                sessions = listOf(newSess) + appState.sessions,
                activeSessionId = newSess.id
            ),
            immediateFlush = true
        )
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
                // 1. Top Editorial Header Bar
                TopEditorialBar(
                    appState = appState,
                    activeSession = activeSession,
                    activeAssistant = activeAssistant,
                    activeProfile = activeProfile,
                    isCompactScreen = isCompactScreen,
                    showSidebar = showSidebar,
                    isSettingsOpen = isSettingsOpen,
                    showQuickModelPopover = showQuickModelPopover,
                    onToggleSidebar = { showSidebar = !showSidebar },
                    onToggleQuickModelPopover = { showQuickModelPopover = !showQuickModelPopover },
                    onSelectModelQuick = { profId, modId ->
                        updateProfileById(profId) { it.copy(defaultModel = modId) }
                        updateActiveSession(immediate = true) { it.copy(profileId = profId, modelOverride = modId) }
                        persist(appState.copy(activeProfileId = profId), immediateFlush = true)
                        showQuickModelPopover = false
                    },
                    onRenameTopicInline = { newTitle ->
                        updateActiveSession(immediate = true) {
                            it.copy(title = newTitle.ifBlank { "Untitled" }, isTitleManuallySet = true)
                        }
                    },
                    onRegenerateTitleAi = { generateTopicTitle(activeSession.id, forceRegenerate = true) },
                    onToggleViewMode = {
                        val next = if (activeSession.viewMode == ViewMode.MANUSCRIPT) ViewMode.EDITORIAL_TURNS else ViewMode.MANUSCRIPT
                        updateActiveSession(immediate = true) { it.copy(viewMode = next) }
                    },
                    onOpenSettings = { cat ->
                        if (isSettingsOpen && activeSettingsCategory == cat) {
                            isSettingsOpen = false
                        } else {
                            activeSettingsCategory = cat
                            isSettingsOpen = true
                            if (isCompactScreen) showSidebar = false
                        }
                    }
                )

                SepiaDivider()

                // Top In-App OTA Banner
                when (val currentOta = otaState) {
                    is OtaUpdateState.Available -> {
                        if (!dismissOtaBanner && appState.preferences.showChatListDates) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(p.cardBg)
                                    .padding(horizontal = 16.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val sizeMb = (currentOta.release.assetSizeBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                                BasicText(
                                    text = "✦ OTA Update Available: ${currentOta.release.tagName} ($sizeMb MB)",
                                    style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SepiaButton(
                                        text = "⬇ Update Now",
                                        compact = true,
                                        style = SepiaButtonStyle.PRIMARY,
                                        onClick = { triggerOtaDownloadAndInstall(currentOta.release) }
                                    )
                                    SepiaButton(
                                        text = "×",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = { dismissOtaBanner = true }
                                    )
                                }
                            }
                            SepiaDivider()
                        }
                    }
                    else -> {}
                }

                if (bannerNotification.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(p.elevatedBg)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = bannerNotification,
                            style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.Medium),
                            modifier = Modifier.weight(1f)
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

                // 2. Main Content Area: Kelivo `[ Assistants | Topics ]` Sidebar + Settings OR Chat Viewport
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = showSidebar && (!isCompactScreen || !isSettingsOpen),
                        enter = expandHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(150)),
                        exit = shrinkHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(120))
                    ) {
                        Row(modifier = if (isCompactScreen) Modifier.fillMaxSize() else Modifier.fillMaxHeight()) {
                            KelivoSidebar(
                                appState = appState,
                                activeAssistant = activeAssistant,
                                activeSession = activeSession,
                                selectedTab = sidebarTab,
                                onSelectTab = { sidebarTab = it },
                                modifier = if (isCompactScreen) {
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier.width(285.dp).fillMaxHeight()
                                },
                                onSelectAssistant = { asst ->
                                    val assistantSessions = appState.sessions.filter { it.assistantId == asst.id }
                                    if (appState.preferences.newChatWhenSwitchingAssistants || assistantSessions.isEmpty()) {
                                        createNewTopicForAssistant(asst)
                                    } else {
                                        val nextSess = assistantSessions.first()
                                        persist(
                                            appState.copy(
                                                activeAssistantId = asst.id,
                                                activeSessionId = nextSess.id
                                            ),
                                            immediateFlush = true
                                        )
                                    }
                                    if (appState.preferences.autoSwitchToTopics) {
                                        sidebarTab = SidebarTab.TOPICS
                                    }
                                },
                                onEditAssistantInSettings = { asstId ->
                                    persist(appState.copy(activeAssistantId = asstId), immediateFlush = true)
                                    activeSettingsCategory = SettingsCategory.ASSISTANTS
                                    isSettingsOpen = true
                                    if (isCompactScreen) showSidebar = false
                                },
                                onCreateNewAssistant = {
                                    val now = PlatformBridge.currentTimeMillis()
                                    val newAsst = AssistantAgent(id = "asst-$now", name = "New Assistant", iconEmoji = "🤖")
                                    persist(
                                        appState.copy(
                                            assistants = appState.assistants + newAsst,
                                            activeAssistantId = newAsst.id
                                        ),
                                        immediateFlush = true
                                    )
                                    activeSettingsCategory = SettingsCategory.ASSISTANTS
                                    isSettingsOpen = true
                                    if (isCompactScreen) showSidebar = false
                                },
                                onSelectSession = { sessId ->
                                    val sess = appState.sessions.find { it.id == sessId }
                                    persist(
                                        appState.copy(
                                            activeSessionId = sessId,
                                            activeAssistantId = sess?.assistantId ?: appState.activeAssistantId
                                        ),
                                        immediateFlush = true
                                    )
                                    isSettingsOpen = false
                                    if (isCompactScreen) showSidebar = false
                                },
                                onNewTopic = {
                                    createNewTopicForAssistant(activeAssistant)
                                    isSettingsOpen = false
                                    if (isCompactScreen) showSidebar = false
                                },
                                onTogglePinSession = { sessId ->
                                    updateSessionById(sessId, immediate = true) { it.copy(isPinned = !it.isPinned) }
                                },
                                onRegenerateSessionTitle = { sessId ->
                                    generateTopicTitle(sessId, forceRegenerate = true)
                                },
                                onDeleteSession = { targetId ->
                                    val remaining = appState.sessions.filterNot { it.id == targetId }
                                    val remainingForAssistant = remaining.filter { it.assistantId == activeAssistant.id }
                                    if (remainingForAssistant.isNotEmpty()) {
                                        val nextActive = if (appState.activeSessionId == targetId) remainingForAssistant.first().id else appState.activeSessionId
                                        persist(appState.copy(sessions = remaining, activeSessionId = nextActive), immediateFlush = true)
                                    } else {
                                        // Deleting the last topic for this Assistant creates a fresh empty topic while keeping all Assistants intact!
                                        val now = PlatformBridge.currentTimeMillis()
                                        val fresh = ChatSession(
                                            id = "sess-$now",
                                            title = "New Chat",
                                            createdAt = now,
                                            updatedAt = now,
                                            profileId = activeProfile.id,
                                            modelOverride = activeProfile.defaultModel,
                                            assistantId = activeAssistant.id
                                        )
                                        persist(appState.copy(sessions = listOf(fresh) + remaining, activeSessionId = fresh.id), immediateFlush = true)
                                    }
                                },
                                onDeleteAllTopicsForAssistant = {
                                    val otherSessions = appState.sessions.filterNot { it.assistantId == activeAssistant.id }
                                    val now = PlatformBridge.currentTimeMillis()
                                    val fresh = ChatSession(
                                        id = "sess-$now",
                                        title = "New Chat",
                                        createdAt = now,
                                        updatedAt = now,
                                        profileId = activeProfile.id,
                                        modelOverride = activeProfile.defaultModel,
                                        assistantId = activeAssistant.id
                                    )
                                    persist(appState.copy(sessions = listOf(fresh) + otherSessions, activeSessionId = fresh.id), immediateFlush = true)
                                    bannerNotification = "Cleared all topics for '${activeAssistant.name}' (Assistant & System Prompt kept)."
                                },
                                onOpenSettings = {
                                    isSettingsOpen = true
                                    if (isCompactScreen) showSidebar = false
                                }
                            )
                            if (!isCompactScreen) {
                                Box(
                                    modifier = Modifier
                                        .width(0.85.dp)
                                        .fillMaxHeight()
                                        .background(p.border.copy(alpha = 0.7f))
                                )
                            }
                        }
                    }

                    if (!isCompactScreen || !showSidebar || isSettingsOpen) {
                        Crossfade(
                            targetState = isSettingsOpen,
                            animationSpec = tween(180),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) { settingsMode ->
                            if (settingsMode) {
                                KelivoSettingsScreen(
                                    appState = appState,
                                    activeProfile = activeProfile,
                                    activeSession = activeSession,
                                    initialCategory = activeSettingsCategory,
                                    otaState = otaState,
                                    onSelectProfile = { id -> persist(appState.copy(activeProfileId = id), immediateFlush = true) },
                                    onUpdateProfile = ::updateActiveProfile,
                                    onUpdateProfileById = ::updateProfileById,
                                    onAddProfile = { protocol ->
                                        val now = PlatformBridge.currentTimeMillis()
                                        val newProf = ProviderProfile(
                                            id = "prof-$now",
                                            name = "${protocol.displayName.substringBefore(" ")} Provider",
                                            protocol = protocol
                                        )
                                        persist(
                                            appState.copy(
                                                profiles = appState.profiles + newProf,
                                                activeProfileId = newProf.id
                                            ),
                                            immediateFlush = true
                                        )
                                    },
                                    onDeleteProfile = { targetProfId ->
                                        val remaining = appState.profiles.filterNot { it.id == targetProfId }
                                        if (remaining.isNotEmpty()) {
                                            val nextProfId = if (appState.activeProfileId == targetProfId) remaining.first().id else appState.activeProfileId
                                            persist(appState.copy(profiles = remaining, activeProfileId = nextProfId), immediateFlush = true)
                                            bannerNotification = "Deleted provider."
                                        }
                                    },
                                    onUpdateAssistants = { updatedList, activeId ->
                                        persist(appState.copy(assistants = updatedList, activeAssistantId = activeId), immediateFlush = true)
                                    },
                                    onUpdateDefaultModels = { dm ->
                                        persist(appState.copy(defaultModels = dm), immediateFlush = true)
                                    },
                                    onUpdatePreferences = { prefs ->
                                        persist(appState.copy(preferences = prefs), immediateFlush = true)
                                    },
                                    onUpdateTheme = { newTheme ->
                                        persist(appState.copy(theme = newTheme), immediateFlush = true)
                                    },
                                    onUpdateSession = { transform -> updateActiveSession(immediate = true, transform = transform) },
                                    onUpdateGlobalWorldBook = { wb ->
                                        persist(appState.copy(globalWorldBook = wb), immediateFlush = true)
                                    },
                                    onSaveCardToLibrary = { card ->
                                        val updatedCards = listOf(card) + appState.savedCards.filterNot { it.name == card.name }
                                        persist(appState.copy(savedCards = updatedCards), immediateFlush = true)
                                    },
                                    onSavePresetToLibrary = { preset ->
                                        val updatedPresets = listOf(preset) + appState.systemPromptPresets.filterNot { it.id == preset.id }
                                        persist(appState.copy(systemPromptPresets = updatedPresets), immediateFlush = true)
                                    },
                                    onDeletePresetFromLibrary = { presetId ->
                                        persist(appState.copy(systemPromptPresets = appState.systemPromptPresets.filterNot { it.id == presetId }), immediateFlush = true)
                                    },
                                    onUpdateOtaSettings = { repo, autoCheck ->
                                        persist(appState.copy(otaGithubRepo = repo, autoCheckOtaOnStartup = autoCheck), immediateFlush = true)
                                    },
                                    onCheckOtaNow = ::triggerOtaCheckManual,
                                    onInstallOtaRelease = ::triggerOtaDownloadAndInstall,
                                    onRestoreFullBackup = { restored ->
                                        persist(restored, immediateFlush = true)
                                        bannerNotification = "Restored backup."
                                    },
                                    onWipeAllData = {
                                        PlatformBridge.wipeAllLocalData()
                                        appState = createDefaultAppState()
                                        bannerNotification = "All local data wiped cleanly."
                                    },
                                    onNotify = { bannerNotification = it },
                                    onClose = { isSettingsOpen = false }
                                )
                            } else {
                                ManuscriptViewport(
                                    appState = appState,
                                    activeSession = activeSession,
                                    activeAssistant = activeAssistant,
                                    activeProfile = activeProfile,
                                    preferences = appState.preferences,
                                    showSessionInspector = showSessionInspector,
                                    onToggleSessionInspector = { showSessionInspector = !showSessionInspector },
                                    isStreaming = isStreaming,
                                    isCurrentlyThinkingPhase = isCurrentlyThinkingPhase,
                                    streamingTargetNodeId = streamingTargetNodeId,
                                    streamingStatus = streamingStatus,
                                    streamingInteractionId = streamingInteractionId,
                                    queuedPrompts = queuedPrompts,
                                    onRemoveQueuedPrompt = { qId -> queuedPrompts = queuedPrompts.filterNot { it.id == qId } },
                                    onSteerQueuedPromptNow = { item ->
                                        queuedPrompts = queuedPrompts.filterNot { it.id == item.id }
                                        uiScope.launch {
                                            activeStreamJob?.cancel()
                                            activeStreamJob?.join()
                                            dispatchPromptItem(
                                                sessionId = activeSession.id,
                                                text = item.text,
                                                prefill = item.prefill,
                                                attachments = item.attachments,
                                                isGuidedDirection = item.isGuidedDirection
                                            )
                                        }
                                    },
                                    userPromptInput = userPromptInput,
                                    onUserPromptChange = { text ->
                                        userPromptInput = text
                                        updateActiveSession { it.copy(draftInput = text) }
                                    },
                                    assistantPrefillInput = assistantPrefillInput,
                                    onAssistantPrefillChange = { pf ->
                                        assistantPrefillInput = pf
                                        updateActiveSession { it.copy(draftPrefill = pf) }
                                    },
                                    showPrefillBox = showPrefillBox,
                                    onTogglePrefillBox = { showPrefillBox = !showPrefillBox },
                                    pendingAttachments = pendingAttachments,
                                    onAddAttachment = {
                                        uiScope.launch {
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
                                                    base64Data = "",
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
                                        uiScope.launch {
                                            val md = buildString {
                                                appendLine("# ${activeSession.title}")
                                                appendLine()
                                                for (n in activeSession.messages) {
                                                    val roleHeader = if (n.role == MessageRole.ASSISTANT) {
                                                        activeSession.activeCard?.name?.ifBlank { n.activeVariant.modelUsed.ifBlank { activeAssistant.name } } ?: activeAssistant.name
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
                                            if (saved != null) bannerNotification = "Exported Markdown to $saved"
                                        }
                                    },
                                    onOpenPricingSettings = {
                                        activeSettingsCategory = SettingsCategory.PROVIDERS
                                        isSettingsOpen = true
                                    },
                                    onSend = ::sendUserTurn,
                                    onSendGuidedDirection = ::sendGuidedDirection,
                                    onSteerNow = ::triggerSteerNow,
                                    onQueuePrompt = ::enqueueFollowUpPrompt,
                                    onImpersonateDraft = ::triggerImpersonateDraft,
                                    onAutoSummarizeStory = ::triggerAutoSummarizeStory,
                                    onForkSessionFromNode = { nodeIndex ->
                                        val now = PlatformBridge.currentTimeMillis()
                                        val keepVersions = appState.preferences.keepMessageVersionsWhenForking
                                        val slicedMessages = activeSession.messages
                                            .subList(0, (nodeIndex + 1).coerceAtMost(activeSession.messages.size))
                                            .map { n ->
                                                if (keepVersions) n else n.copy(variants = listOf(n.activeVariant), activeVariantIndex = 0)
                                            }
                                        val forkedSession = activeSession.copy(
                                            id = "sess-fork-$now",
                                            title = "${activeSession.title} (Fork #${nodeIndex + 1})",
                                            isTitleManuallySet = true,
                                            createdAt = now,
                                            updatedAt = now,
                                            messages = slicedMessages
                                        )
                                        persist(
                                            appState.copy(
                                                sessions = listOf(forkedSession) + appState.sessions,
                                                activeSessionId = forkedSession.id
                                            ),
                                            immediateFlush = true
                                        )
                                        bannerNotification = "Forked topic at turn #${nodeIndex + 1}."
                                    },
                                    onStopStream = {
                                        activeStreamJob?.cancel()
                                        if (streamingInteractionId.isNotBlank() && activeProfile.useBackgroundLongDecode) {
                                            appProcessScope.launch {
                                                AiProviderEngine.cancelBackgroundInteraction(activeProfile, streamingInteractionId)
                                            }
                                        }
                                        isStreaming = false
                                        streamingStatus = ""
                                    },
                                    onUpdateSession = { transform -> updateActiveSession(immediate = true, transform = transform) },
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
                                            isStreaming = true,
                                            createdAt = now
                                        )
                                        val prefs = appState.preferences
                                        val newVariants = if (prefs.deletePreviousMessageWhenRegenerating) {
                                            listOf(newVar)
                                        } else {
                                            targetNode.variants + newVar
                                        }
                                        val newIdx = newVariants.lastIndex
                                        val baseList = if (prefs.deleteMessagesBelowWhenRegenerating) {
                                            activeSession.messages.subList(0, nodeIndex + 1)
                                        } else {
                                            activeSession.messages
                                        }
                                        updateActiveSession(immediate = true) { sess ->
                                            sess.copy(
                                                messages = baseList.mapIndexed { idx, n ->
                                                    if (idx == nodeIndex) n.copy(variants = newVariants, activeVariantIndex = newIdx) else n
                                                }
                                            )
                                        }
                                        triggerGeneration(
                                            sessionId = activeSession.id,
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
                                            sessionId = activeSession.id,
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

// ============================================================================================
// TOP EDITORIAL BAR WITH INLINE TITLE RENAME (`✎`), AI TITLE REGEN (`✦`), & MODEL POPOVER
// ============================================================================================
@Composable
private fun TopEditorialBar(
    appState: AppStateSnapshot,
    activeSession: ChatSession,
    activeAssistant: AssistantAgent,
    activeProfile: ProviderProfile,
    isCompactScreen: Boolean,
    showSidebar: Boolean,
    isSettingsOpen: Boolean,
    showQuickModelPopover: Boolean,
    onToggleSidebar: () -> Unit,
    onToggleQuickModelPopover: () -> Unit,
    onSelectModelQuick: (String, String) -> Unit,
    onRenameTopicInline: (String) -> Unit,
    onRegenerateTitleAi: () -> Unit,
    onToggleViewMode: () -> Unit,
    onOpenSettings: (SettingsCategory) -> Unit
) {
    val p = LocalParchmentPalette.current
    val activeModel = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }
    val modelLabel = activeModel.ifBlank { "Select model…" }
    var isEditingTitle by remember(activeSession.id) { mutableStateOf(false) }
    var titleBuffer by remember(activeSession.id, activeSession.title) { mutableStateOf(activeSession.title) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.sheetBg)
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                SepiaButton(
                    text = if (showSidebar) "◂" else "☰",
                    compact = true,
                    style = if (showSidebar) SepiaButtonStyle.SECONDARY else SepiaButtonStyle.OUTLINE,
                    onClick = onToggleSidebar
                )

                if (appState.preferences.showAssistantAvatarInTitleBar) {
                    SepiaAvatarCircle(
                        symbol = activeAssistant.iconEmoji,
                        selected = true,
                        sizeDp = 28.dp,
                        modifier = Modifier.clickable { onOpenSettings(SettingsCategory.ASSISTANTS) }
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    if (isEditingTitle) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SepiaTextField(
                                value = titleBuffer,
                                onValueChange = { titleBuffer = it },
                                minHeight = 30.dp,
                                modifier = Modifier.weight(1f)
                            )
                            SepiaButton(
                                text = "Save",
                                compact = true,
                                style = SepiaButtonStyle.PRIMARY,
                                onClick = {
                                    onRenameTopicInline(titleBuffer.trim())
                                    isEditingTitle = false
                                }
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            BasicText(
                                text = activeSession.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = p.uiHeaderStyle.copy(fontSize = 14.5.sp),
                                modifier = Modifier.weight(1f, fill = false).clickable { isEditingTitle = true }
                            )
                            BasicText(
                                text = "✎",
                                style = p.uiLabelStyle.copy(fontSize = 11.sp),
                                modifier = Modifier.clickable { isEditingTitle = true }
                            )
                            if (activeSession.messages.isNotEmpty()) {
                                BasicText(
                                    text = "✦",
                                    style = p.uiLabelStyle.copy(color = p.accent, fontSize = 11.5.sp),
                                    modifier = Modifier.clickable { onRegenerateTitleAi() }
                                )
                            }
                        }
                    }

                    val capsuleText = if (appState.preferences.showProviderInModelCapsule) {
                        "${activeAssistant.name} • ${activeProfile.name} • $modelLabel ▾"
                    } else {
                        "${activeAssistant.name} • $modelLabel ▾"
                    }
                    BasicText(
                        text = capsuleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = p.uiLabelStyle.copy(color = p.accent, fontSize = 11.sp),
                        modifier = Modifier.clickable { onToggleQuickModelPopover() }
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isSettingsOpen) {
                    SepiaChip(
                        label = if (activeSession.viewMode == ViewMode.MANUSCRIPT) "📖 Manuscript" else "▤ Turns",
                        selected = activeSession.viewMode == ViewMode.MANUSCRIPT,
                        onClick = onToggleViewMode
                    )
                }
                SepiaButton(
                    text = if (isSettingsOpen) "← Chat" else "⚙ Settings",
                    compact = true,
                    style = if (isSettingsOpen) SepiaButtonStyle.PRIMARY else SepiaButtonStyle.OUTLINE,
                    onClick = { onOpenSettings(SettingsCategory.PREFERENCES) }
                )
            }
        }

        // Quick Inline Model Switcher Popover
        AnimatedVisibility(visible = showQuickModelPopover) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.cardBg)
                    .border(0.85.dp, p.border, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(text = "Quick Switch Model", style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold))
                    SepiaButton(
                        text = "⚙ Manage Providers & Models",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            onToggleQuickModelPopover()
                            onOpenSettings(SettingsCategory.PROVIDERS)
                        }
                    )
                }
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    appState.profiles.forEach { prof ->
                        val models = if (prof.savedModels.isNotEmpty()) prof.savedModels
                        else if (prof.defaultModel.isNotBlank()) listOf(prof.defaultModel)
                        else emptyList()
                        models.forEach { m ->
                            val isSel = prof.id == activeProfile.id && m == activeModel
                            SepiaChip(
                                label = "${prof.name} • $m",
                                selected = isSel,
                                onClick = { onSelectModelQuick(prof.id, m) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================================
// KELIVO-STYLE `[ Assistants | Topics ]` LEFT SIDEBAR (Matching Screenshots 1 & 2)
// ============================================================================================
@Composable
private fun KelivoSidebar(
    appState: AppStateSnapshot,
    activeAssistant: AssistantAgent,
    activeSession: ChatSession,
    selectedTab: SidebarTab,
    onSelectTab: (SidebarTab) -> Unit,
    modifier: Modifier = Modifier,
    onSelectAssistant: (AssistantAgent) -> Unit,
    onEditAssistantInSettings: (String) -> Unit,
    onCreateNewAssistant: () -> Unit,
    onSelectSession: (String) -> Unit,
    onNewTopic: () -> Unit,
    onTogglePinSession: (String) -> Unit,
    onRegenerateSessionTitle: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDeleteAllTopicsForAssistant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val p = LocalParchmentPalette.current
    var searchQuery by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .background(p.sheetBg.copy(alpha = 0.65f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Top Search Pill Bar (Matching Screenshot 1: `🔍 Search assistants` / `🔍 Search topics`)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SepiaTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = if (selectedTab == SidebarTab.ASSISTANTS) "🔍 Search assistants…" else "🔍 Search topics…",
                minHeight = 36.dp,
                modifier = Modifier.weight(1f)
            )
            SepiaButton(
                text = "+",
                compact = true,
                style = SepiaButtonStyle.PRIMARY,
                onClick = {
                    if (selectedTab == SidebarTab.ASSISTANTS) onCreateNewAssistant() else onNewTopic()
                }
            )
        }

        // 2. Kelivo 2-Tab Pill Switcher (`[ Assistants | Topics ]` — Matching Screenshots 1 & 2)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .background(p.cardBg.copy(alpha = 0.7f))
                .border(0.8.dp, p.border.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(SidebarTab.ASSISTANTS to "Assistants", SidebarTab.TOPICS to "Topics").forEach { (tab, label) ->
                val isSel = selectedTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isSel) p.sheetBg else Color.Transparent)
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = label,
                        style = p.uiBodyStyle.copy(
                            fontSize = 13.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSel) p.accent else p.narrationInk
                        )
                    )
                }
            }
        }

        // 3. Tab Content
        if (selectedTab == SidebarTab.ASSISTANTS) {
            val filteredAssistants = remember(appState.assistants, searchQuery) {
                val q = searchQuery.trim()
                if (q.isEmpty()) appState.assistants
                else appState.assistants.filter {
                    it.name.contains(q, ignoreCase = true) || it.systemPrompt.contains(q, ignoreCase = true)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (filteredAssistants.isEmpty()) {
                    item {
                        BasicText(
                            text = "Pure zero-shot mode (no assistants saved). Tap '+' above to create a custom assistant.",
                            style = p.uiLabelStyle,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                itemsIndexed(filteredAssistants, key = { _, a -> a.id }) { _, asst ->
                    val isSel = asst.id == activeAssistant.id
                    val shape = RoundedCornerShape(999.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (isSel) p.sheetBg else Color.Transparent)
                            .clickable { onSelectAssistant(asst) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SepiaAvatarCircle(
                                symbol = asst.iconEmoji,
                                selected = isSel,
                                sizeDp = 32.dp
                            )
                            BasicText(
                                text = asst.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = p.uiBodyStyle.copy(
                                    fontSize = 14.sp,
                                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                    color = p.primaryInk
                                )
                            )
                        }
                        SepiaButton(
                            text = "⚙",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onEditAssistantInSettings(asst.id) }
                        )
                    }
                }
            }
        } else {
            // TOPICS TAB (Filtered by active Assistant, Pinned first, sorted by updatedAt)
            val assistantTopics = remember(appState.sessions, activeAssistant.id, searchQuery) {
                val q = searchQuery.trim()
                val forAsst = appState.sessions.filter { it.assistantId == activeAssistant.id || appState.assistants.none { a -> a.id == it.assistantId } }
                val searched = if (q.isEmpty()) forAsst else forAsst.filter { s ->
                    s.title.contains(q, ignoreCase = true) || s.messages.any { n -> n.activeVariant.content.contains(q, ignoreCase = true) }
                }
                searched.sortedWith(compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = "${activeAssistant.iconEmoji} ${activeAssistant.name} (${assistantTopics.size})",
                    style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SepiaButton(
                        text = "+ New",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = onNewTopic
                    )
                    if (assistantTopics.size > 1) {
                        SepiaButton(
                            text = "Clear All",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onDeleteAllTopicsForAssistant
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(assistantTopics, key = { _, s -> s.id }) { _, sess ->
                    val selected = sess.id == activeSession.id
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (selected) p.cardBg else p.elevatedBg.copy(alpha = 0.35f))
                            .border(0.8.dp, if (selected) p.accent else Color.Transparent, shape)
                            .clickable { onSelectSession(sess.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (sess.isPinned) {
                                    BasicText(text = "📌", style = p.uiLabelStyle.copy(fontSize = 10.sp))
                                }
                                BasicText(
                                    text = sess.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiBodyStyle.copy(
                                        fontSize = 13.5.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                )
                            }
                            BasicText(
                                text = "${sess.messages.size} turns",
                                style = p.uiLabelStyle.copy(fontSize = 10.5.sp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            SepiaButton(
                                text = "📌",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = { onTogglePinSession(sess.id) }
                            )
                            if (sess.messages.isNotEmpty()) {
                                SepiaButton(
                                    text = "✦",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { onRegenerateSessionTitle(sess.id) }
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

        SepiaDivider()

        // Bottom Sidebar Footer: Quick Settings Button
        SepiaButton(
            text = "⚙ Settings & Providers",
            style = SepiaButtonStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenSettings
        )
    }
}

// ============================================================================================
// MAIN MANUSCRIPT / CHAT VIEWPORT WITH FLOATING MINIMAP (`⤒`, `↑`, `🗺 Tree/Swipes`, `↓`, `⤓`)
// AND STEER / QUEUE COMMAND DECK COMPOSER
// ============================================================================================
@Composable
private fun ManuscriptViewport(
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
    pendingAttachments: List<PromptAttachment>,
    onAddAttachment: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onExportChatMarkdown: () -> Unit,
    onOpenPricingSettings: () -> Unit,
    onSend: () -> Unit,
    onSendGuidedDirection: () -> Unit,
    onSteerNow: () -> Unit,
    onQueuePrompt: () -> Unit,
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
    var showMinimapTreeModal by remember { mutableStateOf(false) }

    // Auto-follow bottom during 60/120fps streaming when enabled
    val lastMsgContentLen = activeSession.messages.lastOrNull()?.activeVariant?.let {
        it.content.length + it.thinkingSummary.length
    } ?: 0

    LaunchedEffect(lastMsgContentLen, isStreaming) {
        if (isStreaming && preferences.autoFollowBottom && activeSession.messages.isNotEmpty()) {
            val targetIdx = (activeSession.messages.size).coerceAtLeast(0)
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (targetIdx - lastVisible <= 3) {
                listState.scrollToItem(targetIdx)
            }
        }
    }

    val activeContextNodes = remember(activeSession.messages) {
        activeSession.messages.filter { it.includedInContext }
    }
    val sessionTotalInputTok = remember(activeSession.messages) {
        activeSession.messages.sumOf { it.activeVariant.tokenMetrics.inputTokens }
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

    val liveContextTokens = remember(
        activeSession,
        activeAssistant,
        activeContextNodes,
        userPromptInput,
        assistantPrefillInput,
        pendingAttachments
    ) {
        val baseChars = activeAssistant.systemPrompt.length +
            activeSession.systemPrompt.length +
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
        // Subtle Context & Memory Ribbon
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.elevatedBg.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.widthIn(max = p.maxReadingWidth).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val cardName = activeSession.activeCard?.name?.takeIf { it.isNotBlank() } ?: activeAssistant.name
                val statsStr = if (sessionTotalTokens > 0 && preferences.showTokenAndContextStats) {
                    " • ${formatCompactTokens(sessionTotalTokens)} tok (${formatUsdCost(sessionTotalCostUsd)})"
                } else ""
                BasicText(
                    text = "${activeAssistant.iconEmoji} $cardName$statsStr",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { onToggleSessionInspector() },
                    style = p.uiLabelStyle.copy(color = p.narrationInk, fontSize = 11.sp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (activeSession.messages.isNotEmpty()) {
                        SepiaButton(
                            text = "Export .MD",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onExportChatMarkdown
                        )
                        SepiaButton(
                            text = "Clear",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onUpdateSession { it.copy(messages = emptyList()) } }
                        )
                    }
                    SepiaButton(
                        text = if (showSessionInspector) "▲ Scene & Memory" else "▾ Scene & Memory",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = onToggleSessionInspector
                    )
                }
            }

            AnimatedVisibility(visible = showSessionInspector) {
                Column(
                    modifier = Modifier
                        .padding(top = 6.dp, bottom = 4.dp)
                        .widthIn(max = p.maxReadingWidth)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(p.cardBg)
                        .border(0.85.dp, p.border, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SepiaTextField(
                        value = activeSession.systemPrompt,
                        onValueChange = { sp -> onUpdateSession { it.copy(systemPrompt = sp) } },
                        label = "Topic-Specific System Prompt Addendum (Appended to '${activeAssistant.name}' prompt)",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SepiaTextField(
                        value = activeSession.authorsNote,
                        onValueChange = { note -> onUpdateSession { it.copy(authorsNote = note) } },
                        label = "Dynamic Author's Note / Current Scene Pacing",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(text = "Rolling Continuity Memory", style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold))
                        SepiaButton(
                            text = if (isStreaming) "Summarizing…" else "✦ Auto-Summarize Chat",
                            compact = true,
                            enabled = !isStreaming && activeSession.messages.isNotEmpty(),
                            style = SepiaButtonStyle.SECONDARY,
                            onClick = onAutoSummarizeStory
                        )
                    }
                    SepiaTextField(
                        value = activeSession.storySummary,
                        onValueChange = { sum -> onUpdateSession { it.copy(storySummary = sum) } },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        SepiaDivider()

        // Main Chat Area + Floating Message Navigation Minimap (`⤒`, `↑`, `🗺 Tree/Swipes`, `↓`, `⤓`)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = p.maxReadingWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(
                    if (activeSession.viewMode == ViewMode.MANUSCRIPT) 16.dp else 18.dp
                )
            ) {
                item { Spacer(Modifier.height(10.dp)) }

                if (activeSession.messages.isEmpty()) {
                    item {
                        val activeModel = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }
                        val card = activeSession.activeCard ?: activeAssistant.activeCard
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(p.cardBg.copy(alpha = 0.85f))
                                .border(0.85.dp, p.border.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            BasicText(
                                text = "${activeAssistant.iconEmoji} ${card?.name?.takeIf { it.isNotBlank() } ?: activeAssistant.name}",
                                style = p.uiHeaderStyle.copy(fontSize = 18.sp)
                            )
                            BasicText(
                                text = "${activeProfile.name} • ${activeModel.ifBlank { "No model selected" }} • Stateful Chain: ${if (activeProfile.useStatefulChaining) "ON" else "OFF"} • Background Poll: ${if (activeProfile.useBackgroundLongDecode) "ON" else "OFF"}",
                                style = p.uiLabelStyle.copy(color = p.accent)
                            )
                            if (activeAssistant.systemPrompt.isNotBlank()) {
                                BasicText(
                                    text = activeAssistant.systemPrompt,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiBodyStyle.copy(color = p.narrationInk, fontSize = 12.5.sp)
                                )
                            }
                            if (card != null && card.firstMessage.isNotBlank()) {
                                SepiaButton(
                                    text = "Insert Opening Scene from '${card.name}'",
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
                    }
                }

                itemsIndexed(activeSession.messages, key = { _, node -> node.id }) { index, node ->
                    MessageTurnItem(
                        node = node,
                        nodeIndex = index,
                        viewMode = activeSession.viewMode,
                        preferences = preferences,
                        userName = activeSession.userName,
                        characterName = activeSession.activeCard?.name?.ifBlank { activeAssistant.name } ?: activeAssistant.name,
                        providerName = activeProfile.name,
                        isActivelyThinkingThisNode = isStreaming && isCurrentlyThinkingPhase && streamingTargetNodeId == node.id,
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

                item { Spacer(Modifier.height(16.dp)) }
            }

            // Floating Message Navigation Minimap (`⤒`, `↑`, `🗺 Tree/Swipes`, `↓`, `⤓`)
            val showNavButtons = when (preferences.messageNavButtons) {
                MessageNavVisibility.ALWAYS -> activeSession.messages.isNotEmpty()
                MessageNavVisibility.WHILE_SCROLLING -> activeSession.messages.isNotEmpty() && listState.isScrollInProgress
                MessageNavVisibility.HIDDEN -> false
            }

            if (showNavButtons || showMinimapTreeModal) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Expandable Interactive Tree & Swipe Minimap Modal
                    AnimatedVisibility(visible = showMinimapTreeModal) {
                        Column(
                            modifier = Modifier
                                .width(290.dp)
                                .heightIn(max = 380.dp)
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
                                BasicText(
                                    text = "🗺 Message & Swipe Tree (${activeSession.messages.size})",
                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold)
                                )
                                SepiaButton(
                                    text = "×",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { showMinimapTreeModal = false }
                                )
                            }
                            SepiaDivider()
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                activeSession.messages.forEachIndexed { idx, mNode ->
                                    val isAsst = mNode.role == MessageRole.ASSISTANT
                                    val roleBadge = if (isAsst) "AI" else "You"
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(if (isAsst) p.sheetBg.copy(alpha = 0.65f) else p.elevatedBg.copy(alpha = 0.45f))
                                            .clickable {
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
                                                    fontSize = 10.5.sp
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
                                                                .clickable {
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
                                                                    fontSize = 9.5.sp,
                                                                    color = if (vSel) Color(0xFFFAF6EB) else p.narrationInk,
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
                            .background(p.cardBg.copy(alpha = 0.92f))
                            .border(0.85.dp, p.border, RoundedCornerShape(999.dp))
                            .padding(vertical = 4.dp, horizontal = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Extreme Jump Top
                        SepiaButton(
                            text = "⤒",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { scope.launch { listState.animateScrollToItem(0) } }
                        )
                        // Jump Previous Message
                        SepiaButton(
                            text = "↑",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                scope.launch {
                                    val cur = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                                    listState.animateScrollToItem(cur)
                                }
                            }
                        )
                        // Toggle Tree & Swipe Map
                        SepiaButton(
                            text = "🗺",
                            compact = true,
                            style = if (showMinimapTreeModal) SepiaButtonStyle.PRIMARY else SepiaButtonStyle.GHOST,
                            onClick = { showMinimapTreeModal = !showMinimapTreeModal }
                        )
                        // Jump Next Message
                        SepiaButton(
                            text = "↓",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                scope.launch {
                                    val next = (listState.firstVisibleItemIndex + 1).coerceAtMost(activeSession.messages.size)
                                    listState.animateScrollToItem(next)
                                }
                            }
                        )
                        // Extreme Jump Bottom
                        SepiaButton(
                            text = "⤓",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem(activeSession.messages.size.coerceAtLeast(0))
                                }
                            }
                        )
                    }
                }
            }
        }

        // ========================================================================================
        // UNIFIED FLOATING "COMMAND DECK" COMPOSER WITH STEER / QUEUE & FIXED MOBILE SCALING
        // ========================================================================================
        var showComposerToolsDrawer by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.canvasBg)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val deckShape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .widthIn(max = p.maxReadingWidth)
                    .fillMaxWidth()
                    .clip(deckShape)
                    .background(p.cardBg)
                    .border(
                        width = if (isStreaming) 1.2.dp else 0.9.dp,
                        color = if (isStreaming) p.accent else p.border.copy(alpha = 0.85f),
                        shape = deckShape
                    )
                    .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                // 1. Live Streaming Status Strip
                AnimatedVisibility(visible = isStreaming) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.sheetBg.copy(alpha = 0.8f))
                            .border(0.8.dp, p.accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
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
                                text = streamingStatus.ifBlank { "Streaming response…" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = p.uiBodyStyle.copy(color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            )
                        }
                        SepiaButton(
                            text = "■ Stop",
                            compact = true,
                            style = SepiaButtonStyle.DANGER,
                            onClick = onStopStream
                        )
                    }
                }

                // 2. Queued Prompts Bar (`⏳ Queue` mode)
                AnimatedVisibility(visible = queuedPrompts.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.elevatedBg.copy(alpha = 0.7f))
                            .border(0.8.dp, p.accentSecondary, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        BasicText(
                            text = "⏳ Queued Follow-Up Prompts (${queuedPrompts.size} — auto-sends when current reply finishes):",
                            style = p.uiLabelStyle.copy(color = p.accentSecondary, fontWeight = FontWeight.SemiBold)
                        )
                        queuedPrompts.forEach { qItem ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText(
                                    text = "• ${qItem.text}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiBodyStyle.copy(fontSize = 12.sp),
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    SepiaButton(
                                        text = "⚡ Steer Now",
                                        compact = true,
                                        style = SepiaButtonStyle.SECONDARY,
                                        onClick = { onSteerQueuedPromptNow(qItem) }
                                    )
                                    SepiaButton(
                                        text = "×",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = { onRemoveQueuedPrompt(qItem.id) }
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Collapsible Capabilities Tray
                AnimatedVisibility(visible = showComposerToolsDrawer) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(p.sheetBg.copy(alpha = 0.65f))
                            .border(0.8.dp, p.border.copy(alpha = 0.75f), RoundedCornerShape(9.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            BasicText(
                                text = "Thinking:",
                                style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(top = 4.dp, end = 2.dp)
                            )
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
                            placeholder = "Custom thinking level (e.g. minimal, none, low, medium, high, xhigh, max)…",
                            monospace = true,
                            minHeight = 34.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                        SepiaDivider()
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            SepiaChip(
                                label = "🌐 Web Search",
                                selected = activeProfile.enableWebSearch,
                                onClick = { onUpdateProfile { it.copy(enableWebSearch = !it.enableWebSearch) } }
                            )
                            if (activeProfile.protocol == ProviderProtocol.GEMINI_INTERACTIONS ||
                                activeProfile.protocol == ProviderProtocol.OPENAI_RESPONSES ||
                                activeProfile.protocol == ProviderProtocol.BYTEDANCE_ARK
                            ) {
                                SepiaChip(
                                    label = "🖼 Image Gen",
                                    selected = activeProfile.enableImageGeneration,
                                    onClick = { onUpdateProfile { it.copy(enableImageGeneration = !it.enableImageGeneration) } }
                                )
                            }
                            if (activeProfile.protocol == ProviderProtocol.BYTEDANCE_ARK) {
                                SepiaChip(
                                    label = "🎬 Video Gen",
                                    selected = activeProfile.enableVideoGeneration,
                                    onClick = { onUpdateProfile { it.copy(enableVideoGeneration = !it.enableVideoGeneration) } }
                                )
                            }
                            if (activeProfile.protocol == ProviderProtocol.GEMINI_INTERACTIONS) {
                                SepiaChip(
                                    label = "💻 Code Exec",
                                    selected = activeProfile.enableCodeExecution,
                                    onClick = { onUpdateProfile { it.copy(enableCodeExecution = !it.enableCodeExecution) } }
                                )
                                SepiaChip(
                                    label = "🔗 URL Context",
                                    selected = activeProfile.enableUrlContext,
                                    onClick = { onUpdateProfile { it.copy(enableUrlContext = !it.enableUrlContext) } }
                                )
                                SepiaChip(
                                    label = "⚡ Background Poll",
                                    selected = activeProfile.useBackgroundLongDecode,
                                    onClick = { onUpdateProfile { it.copy(useBackgroundLongDecode = !it.useBackgroundLongDecode) } }
                                )
                            }
                            SepiaChip(
                                label = "⛓ Stateful Chain",
                                selected = activeProfile.useStatefulChaining,
                                onClick = { onUpdateProfile { it.copy(useStatefulChaining = !it.useStatefulChaining) } }
                            )
                            SepiaChip(
                                label = activeSession.workspaceMode.label,
                                selected = activeSession.workspaceMode != WorkspaceMode.OFF,
                                onClick = {
                                    val next = when (activeSession.workspaceMode) {
                                        WorkspaceMode.OFF -> WorkspaceMode.READ_ONLY
                                        WorkspaceMode.READ_ONLY -> WorkspaceMode.READ_WRITE
                                        WorkspaceMode.READ_WRITE -> WorkspaceMode.OFF
                                    }
                                    onUpdateSession { it.copy(workspaceMode = next) }
                                }
                            )
                            SepiaChip(
                                label = if (showPrefillBox) "▾ Prefill: ON" else "▸ Prefill",
                                selected = showPrefillBox || assistantPrefillInput.isNotBlank(),
                                onClick = onTogglePrefillBox
                            )
                        }
                    }
                }

                if (pendingAttachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pendingAttachments.forEach { att ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                SepiaChip(label = "📎 ${att.fileName}", selected = true, onClick = {})
                                SepiaButton(text = "×", compact = true, style = SepiaButtonStyle.GHOST, onClick = { onRemoveAttachment(att.id) })
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = showPrefillBox) {
                    SepiaTextField(
                        value = assistantPrefillInput,
                        onValueChange = onAssistantPrefillChange,
                        placeholder = "Optional Assistant Opening Line / Prefill…",
                        singleLine = false,
                        maxLines = 3,
                        minHeight = 36.dp,
                        maxHeight = 84.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 4. Clean Borderless Prompt Input Area (Always editable even while streaming so you can Steer or Queue!)
                SepiaTextField(
                    value = userPromptInput,
                    onValueChange = onUserPromptChange,
                    placeholder = if (isStreaming) {
                        "Type a follow-up to '⚡ Steer' immediately or '⏳ Queue' after current reply…"
                    } else {
                        "Write dialogue, prose, or instructions (or type scene guidance and tap '🎬 Direct')…"
                    },
                    singleLine = false,
                    borderless = true,
                    maxLines = 6,
                    minHeight = 42.dp,
                    maxHeight = 148.dp,
                    modifier = Modifier.fillMaxWidth()
                )

                SepiaDivider()

                // 5. Command Deck Footer: Fixed-Width Non-Overlapping Utility Chips + Context Ring + Action/Steer/Queue Buttons
                val hasActiveTools = activeProfile.enableWebSearch ||
                    activeProfile.enableImageGeneration ||
                    activeProfile.enableVideoGeneration ||
                    activeProfile.enableCodeExecution ||
                    activeProfile.enableUrlContext ||
                    activeSession.workspaceMode != WorkspaceMode.OFF ||
                    showPrefillBox

                val thinkingLabel = if (preferences.showReasoningLevelOnButton) {
                    "🧠 ${activeProfile.thinkingEffort.ifBlank { "default" }.uppercase()}"
                } else {
                    "🧠"
                }

                val hasTypedInput = userPromptInput.isNotBlank() || assistantPrefillInput.isNotBlank() || pendingAttachments.isNotEmpty()

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isNarrowViewport = maxWidth < 620.dp

                    val utilityCluster: @Composable () -> Unit = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SepiaChip(
                                label = "📎",
                                selected = pendingAttachments.isNotEmpty(),
                                onClick = onAddAttachment
                            )
                            SepiaChip(
                                label = thinkingLabel,
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
                            // Fixed compact label so enabling search/tools NEVER expands width or overlaps the Context Gauge Ring!
                            SepiaChip(
                                label = if (hasActiveTools) "⚙ Tools •" else "⚙ Tools",
                                selected = showComposerToolsDrawer || hasActiveTools,
                                onClick = { showComposerToolsDrawer = !showComposerToolsDrawer }
                            )
                        }
                    }

                    if (isNarrowViewport) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f, fill = false)) {
                                    utilityCluster()
                                }
                                Spacer(Modifier.width(4.dp))
                                if (preferences.showTokenAndContextStats) {
                                    SepiaContextGaugeRing(
                                        usedTokens = liveContextTokens,
                                        contextLimitTokens = activeProfile.contextWindowTokens,
                                        sessionCostUsd = sessionTotalCostUsd,
                                        onClick = onOpenPricingSettings
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isStreaming) {
                                    SepiaButton(
                                        text = "■ Stop",
                                        compact = true,
                                        style = SepiaButtonStyle.DANGER,
                                        modifier = Modifier.weight(0.9f),
                                        onClick = onStopStream
                                    )
                                    SepiaButton(
                                        text = "⏳ Queue",
                                        compact = true,
                                        enabled = hasTypedInput,
                                        style = SepiaButtonStyle.OUTLINE,
                                        modifier = Modifier.weight(1f),
                                        onClick = onQueuePrompt
                                    )
                                    SepiaButton(
                                        text = "⚡ Steer ↵",
                                        compact = true,
                                        enabled = hasTypedInput,
                                        style = SepiaButtonStyle.PRIMARY,
                                        modifier = Modifier.weight(1.1f),
                                        onClick = onSteerNow
                                    )
                                } else {
                                    SepiaButton(
                                        text = "✍ Impersonate",
                                        compact = true,
                                        style = SepiaButtonStyle.OUTLINE,
                                        modifier = Modifier.weight(1f),
                                        onClick = onImpersonateDraft
                                    )
                                    SepiaButton(
                                        text = "🎬 Direct",
                                        compact = true,
                                        enabled = hasTypedInput,
                                        style = SepiaButtonStyle.SECONDARY,
                                        modifier = Modifier.weight(1f),
                                        onClick = onSendGuidedDirection
                                    )
                                    SepiaButton(
                                        text = "Inscribe ↵",
                                        compact = true,
                                        enabled = hasTypedInput,
                                        style = SepiaButtonStyle.PRIMARY,
                                        modifier = Modifier.weight(1.15f),
                                        onClick = onSend
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                utilityCluster()
                                if (preferences.showTokenAndContextStats) {
                                    SepiaContextGaugeRing(
                                        usedTokens = liveContextTokens,
                                        contextLimitTokens = activeProfile.contextWindowTokens,
                                        sessionCostUsd = sessionTotalCostUsd,
                                        onClick = onOpenPricingSettings
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isStreaming) {
                                    SepiaButton(
                                        text = "■ Stop",
                                        compact = true,
                                        style = SepiaButtonStyle.DANGER,
                                        onClick = onStopStream
                                    )
                                    SepiaButton(
                                        text = "⏳ Queue",
                                        compact = true,
                                        enabled = hasTypedInput,
                                        style = SepiaButtonStyle.OUTLINE,
                                        onClick = onQueuePrompt
                                    )
                                    SepiaButton(
                                        text = "⚡ Steer ↵",
                                        compact = true,
                                        enabled = hasTypedInput,
                                        style = SepiaButtonStyle.PRIMARY,
                                        onClick = onSteerNow
                                    )
                                } else {
                                    SepiaButton(
                                        text = "✍ Impersonate",
                                        compact = true,
                                        style = SepiaButtonStyle.OUTLINE,
                                        onClick = onImpersonateDraft
                                    )
                                    SepiaButton(
                                        text = "🎬 Direct",
                                        compact = true,
                                        enabled = hasTypedInput,
                                        style = SepiaButtonStyle.SECONDARY,
                                        onClick = onSendGuidedDirection
                                    )
                                    SepiaButton(
                                        text = "Inscribe ↵",
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
}

// ============================================================================================
// MESSAGE TURN ITEM WITH:
// - Auto-Opening Live Markdown/LaTeX Reasoning Box while Thinking -> Auto-Collapse when Done
// - 1-Tap `📋 Copy` (Plain Text) & `📋 MD` (Markdown) Buttons
// - Vertical Stacking of Action Buttons & Telemetry so Portrait `⋯ More` NEVER Hides Buttons!
// ============================================================================================
@Composable
private fun MessageTurnItem(
    node: MessageNode,
    nodeIndex: Int,
    viewMode: ViewMode,
    preferences: PreferencesConfig,
    userName: String,
    characterName: String,
    providerName: String,
    isActivelyThinkingThisNode: Boolean,
    onSelectVariant: (Int) -> Unit,
    onSaveEditedText: (String, Boolean) -> Unit,
    onToggleContextMute: () -> Unit,
    onDeleteNode: () -> Unit,
    onForkHere: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit
) {
    val p = LocalParchmentPalette.current
    val clipboard = LocalClipboardManager.current
    val activeVar = node.activeVariant
    val isAssistant = node.role == MessageRole.ASSISTANT

    var isEditing by remember(node.id, node.activeVariantIndex) { mutableStateOf(false) }
    var editBuffer by remember(node.id, node.activeVariantIndex, activeVar.content) { mutableStateOf(activeVar.content) }
    var manualThinkingToggle by remember(node.id, node.activeVariantIndex) { mutableStateOf<Boolean?>(null) }
    var showRawWireDrawer by remember(node.id) { mutableStateOf(false) }
    var showMoreActions by remember(node.id) { mutableStateOf(false) }
    var copiedBadge by remember(node.id, node.activeVariantIndex) { mutableStateOf("") }

    // Auto-open while actively thinking, then auto-collapse when prose starts (if autoCollapseThinking is ON),
    // while always respecting manual user clicks (`manualThinkingToggle`)!
    val isReasoningExpanded = manualThinkingToggle ?: when {
        isActivelyThinkingThisNode -> true
        !preferences.autoCollapseThinking && activeVar.thinkingSummary.isNotBlank() -> true
        else -> false
    }

    val speakerTitle = if (isAssistant) {
        characterName.ifBlank { activeVar.modelUsed.ifBlank { "Assistant" } }
    } else {
        userName.ifBlank { "User" }
    }

    val containerModifier = if (viewMode == ViewMode.MANUSCRIPT) {
        Modifier
            .fillMaxWidth()
            .then(
                if (!node.includedInContext) Modifier.background(p.elevatedBg.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                else Modifier
            )
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            .padding(vertical = 6.dp)
    } else {
        val shape = RoundedCornerShape(11.dp)
        val bg = if (isAssistant) p.cardBg else p.elevatedBg.copy(alpha = 0.65f)
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(0.85.dp, p.border.copy(alpha = 0.8f), shape)
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
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
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if ((isAssistant && preferences.showModelName) || (!isAssistant && preferences.showUserName)) {
                    BasicText(
                        text = speakerTitle.uppercase(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = p.uiLabelStyle.copy(
                            color = if (isAssistant) p.accent else p.narrationInk,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp
                        )
                    )
                }

                if (isAssistant && preferences.showModelName && activeVar.modelUsed.isNotBlank()) {
                    val provSuffix = if (preferences.showProviderAfterModelName) " ($providerName)" else ""
                    BasicText(
                        text = "• ${activeVar.modelUsed}$provSuffix",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = p.uiLabelStyle.copy(fontSize = 11.sp)
                    )
                }

                if (!node.includedInContext) {
                    BasicText(
                        text = "[Muted]",
                        style = p.uiLabelStyle.copy(color = p.danger, fontWeight = FontWeight.SemiBold)
                    )
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
                        compact = true,
                        enabled = node.activeVariantIndex < node.variants.lastIndex,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { onSelectVariant(node.activeVariantIndex + 1) }
                    )
                }

                val hasThinking = preferences.showThinkingCards && activeVar.thinkingSummary.isNotBlank()
                val hasTools = preferences.showToolCards && activeVar.toolCalls.isNotEmpty()
                if (hasThinking || hasTools) {
                    val sec = if (activeVar.thinkingDurationMs > 0) " (${activeVar.thinkingDurationMs / 1000.0}s)" else ""
                    val toolBadge = if (hasTools) " • ${activeVar.toolCalls.size} tools" else ""
                    val chipText = if (isActivelyThinkingThisNode) {
                        "🧠 Thinking$sec…"
                    } else if (isReasoningExpanded) {
                        "▾ Thought$sec$toolBadge"
                    } else {
                        "▸ Thought$sec$toolBadge"
                    }
                    SepiaChip(
                        label = chipText,
                        selected = isReasoningExpanded || isActivelyThinkingThisNode,
                        onClick = { manualThinkingToggle = !isReasoningExpanded }
                    )
                }
            }
        }

        // Animated Collapsible Thinking (with FULL Markdown & LaTeX Rendering!) & Workspace Agent Tool Drawer
        val showDrawer = isReasoningExpanded &&
            ((preferences.showThinkingCards && activeVar.thinkingSummary.isNotBlank()) ||
                (preferences.showToolCards && activeVar.toolCalls.isNotEmpty()))

        AnimatedVisibility(
            visible = showDrawer,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(150)),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(120))
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(p.sheetBg.copy(alpha = 0.72f))
                    .border(0.85.dp, if (isActivelyThinkingThisNode) p.accent else p.border.copy(alpha = 0.75f), RoundedCornerShape(9.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (preferences.showThinkingCards && activeVar.thinkingSummary.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (isActivelyThinkingThisNode) {
                                KissStreamingPulseDot(color = p.accent)
                            }
                            BasicText(
                                text = if (isActivelyThinkingThisNode) "Reasoning in progress…" else "Reasoning Trace (${countWordsFast(activeVar.thinkingSummary)} words)",
                                style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                            )
                        }
                        SepiaButton(
                            text = "Collapse ▲",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { manualThinkingToggle = false }
                        )
                    }

                    // Render Reasoning with full Markdown (**bold**, *italic*, code blocks) & LaTeX ($...$, $$...$$)!
                    LiteraryProseRenderer(
                        text = activeVar.thinkingSummary,
                        compactReasoningMode = true,
                        enableMarkdown = preferences.renderReasoningWithMarkdown,
                        enableMath = preferences.mathFormulaRendering,
                        enableInlineDollarMath = preferences.inlineDollarMathRendering,
                        autoCollapseCodeBlocks = preferences.autoCollapseCodeBlocks
                    )

                    if (activeVar.thinkingSignature.isNotBlank()) {
                        BasicText(
                            text = "✓ Cryptographic Thought Signature Preserved (${activeVar.thinkingSignature.length} chars)",
                            style = p.uiLabelStyle.copy(fontSize = 10.sp, fontFamily = p.codeFontFamily)
                        )
                    }
                }

                if (preferences.showToolCards && activeVar.toolCalls.isNotEmpty()) {
                    BasicText(
                        text = "Workspace Agent Tool Executions (${activeVar.toolCalls.size})",
                        style = p.uiLabelStyle.copy(color = p.accentSecondary, fontWeight = FontWeight.SemiBold)
                    )
                    activeVar.toolCalls.forEach { tc ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(p.cardBg)
                                .border(0.8.dp, p.border.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        ) {
                            BasicText(
                                text = "⚙ ${tc.name}(${tc.argumentsJson})",
                                style = p.monoStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
                            )
                            if (preferences.showToolResultSummary && tc.resultText.isNotBlank()) {
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
                        text = "Save as Branch",
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
                val enableMd = if (isAssistant) preferences.renderAssistantMessagesWithMarkdown else preferences.renderUserMessagesWithMarkdown
                LiteraryProseRenderer(
                    text = activeVar.content.ifBlank { if (activeVar.isStreaming) "…" else "" },
                    enableMarkdown = enableMd,
                    enableMath = preferences.mathFormulaRendering,
                    enableInlineDollarMath = preferences.inlineDollarMathRendering,
                    autoCollapseCodeBlocks = preferences.autoCollapseCodeBlocks
                )
            }
        }

        // Inline Generated Images & Videos
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
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(p.cardBg)
                                    .border(0.85.dp, p.border, RoundedCornerShape(10.dp))
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

                        MediaKind.VIDEO -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(p.cardBg)
                                    .border(0.85.dp, p.accentSecondary, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                BasicText(
                                    text = "🎬 Generated Video (${media.mimeType}${if (media.aspectRatio.isNotBlank()) " • ${media.aspectRatio}" else ""})",
                                    style = p.uiHeaderStyle.copy(fontSize = 14.5.sp, color = p.accent)
                                )
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
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 1-Tap Copy (Clean Text) & Copy MD (Raw Markdown)
                    SepiaButton(
                        text = if (copiedBadge == "txt") "✓ Copied Text" else "📋 Copy",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            clipboard.setText(AnnotatedString(stripMarkdownToPlainText(activeVar.content)))
                            copiedBadge = "txt"
                        }
                    )
                    SepiaButton(
                        text = if (copiedBadge == "md") "✓ Copied MD" else "📋 MD",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            clipboard.setText(AnnotatedString(activeVar.content))
                            copiedBadge = "md"
                        }
                    )
                    if (isAssistant) {
                        SepiaButton(
                            text = "↻ Redo",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onRegenerate
                        )
                        SepiaButton(
                            text = "→ Continue",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onContinue
                        )
                    }
                    SepiaButton(
                        text = "✎ Edit",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            editBuffer = activeVar.content
                            isEditing = !isEditing
                        }
                    )
                    SepiaButton(
                        text = if (showMoreActions) "▾ Less" else "⋯ More",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { showMoreActions = !showMoreActions }
                    )

                    if (showMoreActions) {
                        SepiaButton(
                            text = "⑂ Fork",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onForkHere
                        )
                        SepiaButton(
                            text = if (node.includedInContext) "Mute" else "Unmute",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onToggleContextMute
                        )
                        if (activeVar.rawRequestPreview.isNotBlank()) {
                            SepiaButton(
                                text = if (showRawWireDrawer) "Hide JSON" else "Wire JSON",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = { showRawWireDrawer = !showRawWireDrawer }
                            )
                        }
                        SepiaButton(
                            text = "🗑 Delete",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onDeleteNode
                        )
                    }
                }

                // Telemetry Readout on its own full-width line underneath
                val m = activeVar.tokenMetrics
                if (preferences.showTokenAndContextStats && (m.outputTokens > 0 || m.inputTokens > 0)) {
                    val costStr = if (m.estimatedCostUsd > 0.0) " • ${formatUsdCost(m.estimatedCostUsd)}" else ""
                    val detailStr = if (showMoreActions || preferences.showTokensForEntireTurn) {
                        val cacheStr = if (m.cachedInputTokens > 0) " (Cache ${formatCompactTokens(m.cachedInputTokens)})" else ""
                        val thinkStr = if (m.reasoningTokens > 0) " • Think ${formatCompactTokens(m.reasoningTokens)}" else ""
                        "TTFT ${m.ttftMs}ms • ${m.tokensPerSec} tok/s • In ${formatCompactTokens(m.inputTokens)}$cacheStr$thinkStr • Out ${formatCompactTokens(m.outputTokens)} • Total ${formatCompactTokens(m.totalTokens)} tok$costStr"
                    } else {
                        "${m.tokensPerSec} tok/s • ${formatCompactTokens(m.totalTokens)} tok$costStr"
                    }
                    BasicText(
                        text = detailStr,
                        style = p.uiLabelStyle.copy(fontSize = 10.sp, fontFamily = p.codeFontFamily)
                    )
                }
            }
        }

        AnimatedVisibility(visible = showRawWireDrawer && activeVar.rawRequestPreview.isNotBlank()) {
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.sheetBg)
                    .border(0.85.dp, p.border, RoundedCornerShape(8.dp))
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
// CLEAN STATE LOADER — ZERO HARDCODED ASSISTANTS, ZERO MODEL WIPING, ZERO PROFILE RESURRECTION!
// ============================================================================================
private val HARDCODED_EXAMPLE_ASSISTANT_IDS = setOf(
    "asst-general",
    "asst-test",
    "asst-prompt-writer"
)

private fun loadOrCreateDefaultAppState(): AppStateSnapshot {
    val raw = PlatformBridge.loadAppStateJson()
    if (!raw.isNullOrBlank()) {
        runCatching {
            val loaded = compactStateJson.decodeFromString<AppStateSnapshot>(raw)
            if (loaded.profiles.isNotEmpty() && loaded.sessions.isNotEmpty()) {
                val cleanedAssistants = loaded.assistants.filterNot { it.id in HARDCODED_EXAMPLE_ASSISTANT_IDS }
                val cleanedActiveAsstId = if (loaded.activeAssistantId in HARDCODED_EXAMPLE_ASSISTANT_IDS) {
                    cleanedAssistants.firstOrNull()?.id.orEmpty()
                } else loaded.activeAssistantId
                // Reset any stale isStreaming flags left over if the OS killed the process mid-stream
                val recoveredSessions = loaded.sessions.map { sess ->
                    sess.copy(
                        assistantId = if (sess.assistantId in HARDCODED_EXAMPLE_ASSISTANT_IDS) "" else sess.assistantId,
                        messages = sess.messages.map { node ->
                            node.copy(
                                variants = node.variants.map { v ->
                                    if (v.isStreaming) v.copy(isStreaming = false) else v
                                }
                            )
                        }
                    )
                }
                return loaded.copy(
                    assistants = cleanedAssistants,
                    activeAssistantId = cleanedActiveAsstId,
                    sessions = recoveredSessions
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
        name = "Google Gemini",
        protocol = ProviderProtocol.GEMINI_INTERACTIONS,
        useStatefulChaining = true,
        useBackgroundLongDecode = true
    )
    val openAiProfile = ProviderProfile(
        id = "prof-openai-responses",
        name = "OpenAI",
        protocol = ProviderProtocol.OPENAI_RESPONSES,
        useStatefulChaining = true
    )
    val anthropicProfile = ProviderProfile(
        id = "prof-anthropic-messages",
        name = "Anthropic",
        protocol = ProviderProtocol.ANTHROPIC_MESSAGES
    )
    val byteDanceProfile = ProviderProfile(
        id = "prof-bytedance-ark",
        name = "ByteDance Ark",
        protocol = ProviderProtocol.BYTEDANCE_ARK,
        enableImageGeneration = true
    )

    val initialSession = ChatSession(
        id = "sess-initial",
        title = "New Chat",
        createdAt = now,
        updatedAt = now,
        profileId = geminiProfile.id,
        assistantId = ""
    )

    return AppStateSnapshot(
        profiles = listOf(geminiProfile, openAiProfile, anthropicProfile, byteDanceProfile),
        activeProfileId = geminiProfile.id,
        assistants = emptyList(),
        activeAssistantId = "",
        sessions = listOf(initialSession),
        activeSessionId = initialSession.id,
        theme = ThemeConfig()
    )
}
