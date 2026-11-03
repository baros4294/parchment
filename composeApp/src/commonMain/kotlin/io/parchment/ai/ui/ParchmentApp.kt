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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
import io.parchment.ai.model.ContextFragment
import io.parchment.ai.model.DefaultModelConfig
import io.parchment.ai.model.DesktopWindowConfig
import io.parchment.ai.model.FragmentActivationMode
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.GoalTrackerState
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
import io.parchment.ai.model.SendShortcutMode
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
import io.parchment.ai.platform.PickedBinaryFile
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.platform.normalizePickedBinaryFile
import io.parchment.ai.protocol.CardAndPromptCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Process-wide supervisor scope so switching apps on Android or recomposing UI
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

sealed interface DeleteConfirmRequest {
    data class SingleSession(val sessionId: String, val title: String) : DeleteConfirmRequest
    data class ClearAllSessions(val assistantName: String, val count: Int) : DeleteConfirmRequest
    data class ClearCurrentMessages(val title: String) : DeleteConfirmRequest
}

sealed interface ActiveMediaViewerItem {
    data class ImageViewer(
        val title: String,
        val mimeType: String,
        val localFilePath: String,
        val remoteUrl: String = ""
    ) : ActiveMediaViewerItem

    data class TextOrCodeViewer(
        val title: String,
        val mimeType: String,
        val textContent: String,
        val localFilePath: String = ""
    ) : ActiveMediaViewerItem
}

data class SessionStreamRuntime(
    val sessionId: String,
    val targetNodeId: String = "",
    val isThinkingPhase: Boolean = false,
    val statusText: String = "",
    val interactionId: String = "",
    val job: Job? = null
)

private fun formatSecondsFromMs(ms: Long): String {
    val sec = (ms / 10L).toDouble() / 100.0
    return "${sec}s"
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
    var showMobileTabSwitcher by remember { mutableStateOf(false) }
    var bannerNotification by remember { mutableStateOf("") }

    // OTA Update state
    var otaState by remember { mutableStateOf<OtaUpdateState>(OtaUpdateState.Idle) }
    var dismissOtaBanner by remember { mutableStateOf(false) }

    // Concurrent Per-Session Multi-Tasking Stream & Queue State (allows multiple tabs to run simultaneously!)
    var activeStreamsBySession by remember { mutableStateOf<Map<String, SessionStreamRuntime>>(emptyMap()) }
    var queuedPromptsBySession by remember { mutableStateOf<Map<String, List<QueuedPromptItem>>>(emptyMap()) }

    val activeProfile = appState.profiles.find { it.id == appState.activeProfileId }
        ?: appState.profiles.first()
    val activeSession = appState.sessions.find { it.id == appState.activeSessionId }
        ?: appState.sessions.first()
    val activeAssistant = appState.assistants.find { it.id == activeSession.assistantId && it.id.isNotBlank() }
        ?: appState.assistants.find { it.id == appState.activeAssistantId && it.id.isNotBlank() }
        ?: appState.assistants.firstOrNull()
        ?: AssistantAgent(id = "asst-default", name = "Default Assistant", iconEmoji = "💬", systemPrompt = "")

    // Active tab's stream state
    val currentTabStream = activeStreamsBySession[activeSession.id]
    val isStreaming = currentTabStream != null
    val isCurrentlyThinkingPhase = currentTabStream?.isThinkingPhase == true
    val streamingTargetNodeId = currentTabStream?.targetNodeId.orEmpty()
    val streamingStatus = currentTabStream?.statusText.orEmpty()
    val streamingInteractionId = currentTabStream?.interactionId.orEmpty()
    val activeStreamJob = currentTabStream?.job
    val queuedPrompts = queuedPromptsBySession[activeSession.id].orEmpty()

    // Open Browser Tabs list (always includes activeSession.id)
    val openTabIds = remember(appState.openTabSessionIds, appState.sessions, activeSession.id) {
        val validSaved = appState.openTabSessionIds.filter { id -> appState.sessions.any { it.id == id } }
        if (activeSession.id in validSaved) validSaved else (validSaved + activeSession.id)
    }

    // Per-session composer inputs (synced with activeSession.draftInput so switching chats preserves drafts!)
    var userPromptInput by remember(activeSession.id) { mutableStateOf(activeSession.draftInput) }
    var assistantPrefillInput by remember(activeSession.id) { mutableStateOf(activeSession.draftPrefill) }
    var showPrefillBox by remember { mutableStateOf(false) }
    var isGoalModeEnabled by remember { mutableStateOf(false) }
    var isLiveVoiceMode by remember { mutableStateOf(false) }
    var isVoiceListening by remember { mutableStateOf(false) }
    var pendingAttachments by remember { mutableStateOf<List<PromptAttachment>>(emptyList()) }
    var pendingDeleteConfirmation by remember { mutableStateOf<DeleteConfirmRequest?>(null) }
    var activeViewerItem by remember { mutableStateOf<ActiveMediaViewerItem?>(null) }
    var isDraggingFilesOverWindow by remember { mutableStateOf(false) }
    var diskPersistJob by remember { mutableStateOf<Job?>(null) }

    fun openAttachmentInViewer(att: PromptAttachment) {
        val mime = att.mimeType.lowercase().trim()
        val lower = att.fileName.lowercase().trim()
        val isPdf = mime == "application/pdf" || lower.endsWith(".pdf")
        val isVideoOrAudio = mime.startsWith("video/") || mime.startsWith("audio/") ||
            lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") ||
            lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".mp3") ||
            lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".flac")
        val isImage = mime.startsWith("image/") || lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")

        when {
            isPdf || isVideoOrAudio -> {
                if (att.localFilePath.isNotBlank()) {
                    PlatformBridge.openOrPlayMedia(att.localFilePath)
                } else {
                    bannerNotification = "File path is unavailable for ${att.fileName}."
                }
            }
            isImage -> {
                activeViewerItem = ActiveMediaViewerItem.ImageViewer(
                    title = att.fileName.ifBlank { "Image Attachment" },
                    mimeType = att.mimeType.ifBlank { "image/png" },
                    localFilePath = att.localFilePath
                )
            }
            att.textContent.isNotBlank() || mime.startsWith("text/") || mime == "application/json" -> {
                val content = att.textContent.ifBlank {
                    PlatformBridge.readBinaryFileBytes(att.localFilePath)?.let {
                        runCatching { it.decodeToString() }.getOrNull()
                    }.orEmpty()
                }
                activeViewerItem = ActiveMediaViewerItem.TextOrCodeViewer(
                    title = att.fileName.ifBlank { "Text Attachment" },
                    mimeType = att.mimeType.ifBlank { "text/plain" },
                    textContent = content,
                    localFilePath = att.localFilePath
                )
            }
            att.localFilePath.isNotBlank() -> {
                PlatformBridge.openOrPlayMedia(att.localFilePath)
            }
        }
    }

    fun attachPickedFiles(files: List<PickedBinaryFile>) {
        if (files.isEmpty()) return
        val newAtts = files.mapIndexed { idx, rawPicked ->
            val now = PlatformBridge.currentTimeMillis() + idx
            val picked = normalizePickedBinaryFile(
                rawFileName = rawPicked.fileName,
                bytes = rawPicked.bytes,
                explicitMimeType = rawPicked.mimeType,
                fallbackTimestamp = now
            )
            val lower = picked.fileName.lowercase()
            val resolvedMime = picked.mimeType.lowercase()
            val isImg = resolvedMime.startsWith("image/") ||
                lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
            val isVid = resolvedMime.startsWith("video/") ||
                lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") ||
                lower.endsWith(".mkv") || lower.endsWith(".avi")
            val isAudio = resolvedMime.startsWith("audio/") ||
                lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") ||
                lower.endsWith(".flac") || lower.endsWith(".m4a")
            val isPdf = resolvedMime == "application/pdf" || lower.endsWith(".pdf")

            when {
                isImg -> {
                    val mime = when {
                        resolvedMime.startsWith("image/") -> resolvedMime
                        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                        lower.endsWith(".webp") -> "image/webp"
                        lower.endsWith(".gif") -> "image/gif"
                        lower.endsWith(".bmp") -> "image/bmp"
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
                }
                isVid -> {
                    val mime = when {
                        resolvedMime.startsWith("video/") -> resolvedMime
                        lower.endsWith(".webm") -> "video/webm"
                        lower.endsWith(".mov") -> "video/quicktime"
                        lower.endsWith(".mkv") -> "video/x-matroska"
                        else -> "video/mp4"
                    }
                    val savedPath = PlatformBridge.saveGeneratedMediaFile("att-$now-${picked.fileName}", picked.bytes)
                    PromptAttachment(
                        id = "att-$now",
                        fileName = picked.fileName,
                        mimeType = mime,
                        base64Data = "",
                        localFilePath = savedPath
                    )
                }
                isAudio || isPdf -> {
                    val mime = when {
                        isPdf -> "application/pdf"
                        resolvedMime.startsWith("audio/") -> resolvedMime
                        lower.endsWith(".wav") -> "audio/wav"
                        lower.endsWith(".ogg") -> "audio/ogg"
                        lower.endsWith(".flac") -> "audio/flac"
                        else -> "audio/mp3"
                    }
                    val savedPath = PlatformBridge.saveGeneratedMediaFile("att-$now-${picked.fileName}", picked.bytes)
                    PromptAttachment(
                        id = "att-$now",
                        fileName = picked.fileName,
                        mimeType = mime,
                        base64Data = "",
                        localFilePath = savedPath
                    )
                }
                else -> {
                    val decoded = runCatching { picked.bytes.decodeToString() }.getOrNull()
                    val savedPath = PlatformBridge.saveGeneratedMediaFile("att-$now-${picked.fileName}", picked.bytes)
                    if (decoded != null && !decoded.contains('\u0000')) {
                        PromptAttachment(
                            id = "att-$now",
                            fileName = picked.fileName,
                            mimeType = "text/plain",
                            textContent = decoded,
                            localFilePath = savedPath
                        )
                    } else {
                        PromptAttachment(
                            id = "att-$now",
                            fileName = picked.fileName,
                            mimeType = "application/octet-stream",
                            localFilePath = savedPath,
                            textContent = "[Binary file attached: ${picked.fileName} (${picked.bytes.size / 1024} KB)]"
                        )
                    }
                }
            }
        }
        pendingAttachments = pendingAttachments + newAtts
    }

    LaunchedEffect(Unit) {
        PlatformBridge.registerFileDropCallbacks(
            onDragHover = { hovering -> isDraggingFilesOverWindow = hovering },
            onFilesDropped = { dropped ->
                uiScope.launch {
                    attachPickedFiles(dropped)
                    bannerNotification = "Attached ${dropped.size} item(s): ${dropped.joinToString { it.fileName }}"
                }
            }
        )
    }

    // Track dirty user state so ShutdownHook / onPause ONLY writes when the user actually changed state!
    var hasDirtyUnflushedChanges by remember { mutableStateOf(false) }

    // Register synchronous flush callback for Android MainActivity.onPause/onStop, OTA updates, & Desktop Shutdown Hook
    LaunchedEffect(Unit) {
        PlatformBridge.registerStateFlushCallback {
            runCatching {
                val encoded = compactStateJson.encodeToString(AppStateSnapshot.serializer(), appState)
                PlatformBridge.saveAppStateJson(encoded)
                hasDirtyUnflushedChanges = false
            }
        }
    }

    fun persist(updated: AppStateSnapshot, immediateFlush: Boolean = false) {
        appState = updated
        hasDirtyUnflushedChanges = true
        diskPersistJob?.cancel()
        if (immediateFlush) {
            appProcessScope.launch(Dispatchers.IO) {
                runCatching {
                    val encoded = compactStateJson.encodeToString(AppStateSnapshot.serializer(), updated)
                    PlatformBridge.saveAppStateJson(encoded)
                    hasDirtyUnflushedChanges = false
                }
            }
        } else {
            diskPersistJob = appProcessScope.launch(Dispatchers.Default) {
                delay(250L)
                runCatching {
                    val encoded = compactStateJson.encodeToString(AppStateSnapshot.serializer(), appState)
                    PlatformBridge.saveAppStateJson(encoded)
                    hasDirtyUnflushedChanges = false
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
        hasDirtyUnflushedChanges = true
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
        isSettingsOpen = false
        PlatformBridge.flushPendingStateNow()
        appProcessScope.launch {
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
     * Parchment Automatic & Manual AI Title Generation.
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
     * - Concurrent Per-Session Multi-Tasking (multiple tabs run simultaneously in background)
     * - 60/120fps VSYNC Character Pacer (`withFrameNanos`)
     * - Auto-Open / Auto-Collapse Reasoning Phase tracking
     * - 1.5s Atomic Disk Checkpoints + Cancellation/Close Preservation
     * - Automatic Queue Drain (`⏳ Queue`), Live Voice TTS Readout, and First-Turn Auto-Title Generation
     */
    var dispatchNextQueuedItem: ((String, QueuedPromptItem) -> Unit)? = null

    fun updateSessionStreamState(sessionId: String, updater: (SessionStreamRuntime?) -> SessionStreamRuntime?) {
        val current = activeStreamsBySession[sessionId]
        val next = updater(current)
        activeStreamsBySession = if (next == null) {
            activeStreamsBySession - sessionId
        } else {
            activeStreamsBySession + (sessionId to next)
        }
    }

    fun triggerGeneration(
        sessionId: String,
        historyForCall: List<MessageNode>,
        targetAssistantNodeId: String,
        targetVariantId: String,
        continuePrefix: String = "",
        autoMuteNodeIdsOnFinish: Set<String> = emptySet()
    ) {
        // Cancel only any prior job on THIS sessionId — never interrupt other running tabs!
        activeStreamsBySession[sessionId]?.job?.cancel()
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

        val resolvedWorkspacePath = sessionSnap.workspacePath.ifBlank { assistantSnap.workspacePath }
        val resolvedWorkspaceMode = if (sessionSnap.workspaceMode != WorkspaceMode.OFF) {
            sessionSnap.workspaceMode
        } else if (sessionSnap.workspacePath.isBlank()) {
            assistantSnap.workspaceMode
        } else {
            WorkspaceMode.OFF
        }

        val mergedSessionForApi = sessionSnap.copy(
            systemPrompt = mergedSystemPrompt,
            authorsNote = sessionSnap.authorsNote.ifBlank { assistantSnap.authorsNote },
            activeCard = sessionSnap.activeCard ?: assistantSnap.activeCard,
            fragments = (appState.globalWorldBook + assistantSnap.fragments + sessionSnap.fragments).distinctBy { it.id },
            workspacePath = resolvedWorkspacePath,
            workspaceMode = resolvedWorkspaceMode
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
            thinkingEffort = assistantSnap.thinkingEffort.ifBlank { profileSnap.thinkingEffort },
            enableWebSearch = profileSnap.enableWebSearch || assistantSnap.enableWebSearch
        )

        val modelName = mergedSessionForApi.modelOverride.ifBlank {
            assistantSnap.defaultModelId.ifBlank {
                effectiveProfileForApi.defaultModel.ifBlank { appState.defaultModels.globalModelId }
            }
        }

        val existingTargetVariant = sessionSnap.messages
            .find { it.id == targetAssistantNodeId }
            ?.variants
            ?.find { it.id == targetVariantId }
        val cleanContinuePrefix = AiProviderEngine.stripInlineErrorNotices(continuePrefix)
        val initialThinking = existingTargetVariant?.thinkingSummary.orEmpty()
        val initialSignature = existingTargetVariant?.thinkingSignature.orEmpty()
        val initialTools = existingTargetVariant?.toolCalls.orEmpty()
        val initialMedia = existingTargetVariant?.mediaItems.orEmpty()
        val initialDurationMs = existingTargetVariant?.thinkingDurationMs ?: 0L

        val newJob = appProcessScope.launch {
            val networkContentTarget = StringBuilder(cleanContinuePrefix)
            val networkThinkingTarget = StringBuilder(initialThinking)
            val signatureSb = StringBuilder(initialSignature)
            var displayedContentLen = cleanContinuePrefix.length
            var displayedThinkingLen = initialThinking.length
            val accumulatedTools = initialTools.toMutableList()
            var latestGoalTracker: GoalTrackerState? = existingTargetVariant?.goalTracker
            val accumulatedMedia = initialMedia.toMutableList()
            var localInteractionId = existingTargetVariant?.providerInteractionId.orEmpty()
            var rawReq = ""
            var rawResp = ""
            var networkDone = false
            var thinkingStartMs = 0L
            var thinkingDurationMs = initialDurationMs
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
                val curGoal = latestGoalTracker
                val curMedia = accumulatedMedia.toList()
                updateSessionById(sessionId, immediate = true) { sess ->
                    sess.copy(
                        messages = sess.messages.map { node ->
                            val shouldAutoMute = isFinal && node.id in autoMuteNodeIdsOnFinish
                            if (node.id != targetAssistantNodeId) {
                                if (shouldAutoMute) node.copy(includedInContext = false) else node
                            } else {
                                node.copy(
                                    includedInContext = if (shouldAutoMute) false else node.includedInContext,
                                    variants = node.variants.map { v ->
                                        if (v.id != targetVariantId) v
                                        else {
                                            val base = v.copy(
                                                content = curContent,
                                                thinkingSummary = curThink,
                                                thinkingSignature = curSig,
                                                thinkingDurationMs = thinkingDurationMs,
                                                toolCalls = curTools,
                                                goalTracker = curGoal ?: v.goalTracker,
                                                mediaItems = curMedia,
                                                pendingBackgroundInteractionId = if (isFinal) "" else localInteractionId,
                                                isStreaming = !isFinal
                                            )
                                            if (finalUpdater != null) finalUpdater(base) else base
                                        }
                                    }
                                )
                            }
                        }
                    )
                }
            }

            // Hardware VSync Character Pacer (locks to 60Hz / 120Hz / 144Hz / 240Hz display clock via uiScope's MonotonicFrameClock,
            // with automatic 8ms fallback when backgrounded on Android so background streams never pause or crash!)
            val uiFrameClock = uiScope.coroutineContext[MonotonicFrameClock]
            val pacerJob = launch {
                var lastFrameNanos = 0L
                while (!networkDone || displayedContentLen < networkContentTarget.length || displayedThinkingLen < networkThinkingTarget.length) {
                    val frameNanos = if (uiFrameClock != null) {
                        runCatching {
                            withTimeoutOrNull(28L) {
                                uiFrameClock.withFrameNanos { it }
                            }
                        }.getOrNull()
                    } else null

                    val dtMs = if (frameNanos != null) {
                        val delta = if (lastFrameNanos > 0L) ((frameNanos - lastFrameNanos) / 1_000_000L).coerceIn(2L, 32L) else 8L
                        lastFrameNanos = frameNanos
                        delta
                    } else {
                        delay(8L)
                        lastFrameNanos = 0L
                        8L
                    }

                    val frameScale = (dtMs / 8f).coerceIn(0.4f, 3.0f)
                    var changed = false
                    val targetThinkLen = networkThinkingTarget.length
                    if (displayedThinkingLen < targetThinkLen) {
                        val diff = targetThinkLen - displayedThinkingLen
                        val step = maxOf(1, ((maxOf(2, diff / 4)) * frameScale).toInt())
                        displayedThinkingLen = (displayedThinkingLen + step).coerceAtMost(targetThinkLen)
                        changed = true
                    }
                    val targetContLen = networkContentTarget.length
                    if (displayedContentLen < targetContLen) {
                        val diff = targetContLen - displayedContentLen
                        val step = maxOf(1, ((maxOf(2, diff / 4)) * frameScale).toInt())
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
                    // Atomic background disk checkpoint every 1.5 seconds so switching apps NEVER loses progress
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
                    continueFromAssistantPrefix = cleanContinuePrefix,
                    initialThinking = initialThinking,
                    initialSignature = initialSignature,
                    initialToolCalls = initialTools,
                    initialMediaItems = initialMedia
                ).collect { update ->
                    when (update) {
                        is StreamUpdate.Status -> {
                            if (update.interactionId.isNotBlank()) {
                                localInteractionId = update.interactionId
                                flushCheckpointToDisk(isFinal = false)
                            }
                            updateSessionStreamState(sessionId) { cur ->
                                cur?.copy(statusText = update.message, interactionId = localInteractionId)
                            }
                        }
                        is StreamUpdate.ThinkingDelta -> {
                            if (thinkingStartMs == 0L) thinkingStartMs = PlatformBridge.currentTimeMillis()
                            thinkingDurationMs = (PlatformBridge.currentTimeMillis() - thinkingStartMs).coerceAtLeast(100L)
                            networkThinkingTarget.append(update.delta)
                            signatureSb.append(update.signatureDelta)
                            val thinkWords = countWordsFast(networkThinkingTarget)
                            updateSessionStreamState(sessionId) { cur ->
                                cur?.copy(
                                    isThinkingPhase = true,
                                    statusText = "Reasoning ($modelName • $thinkWords words • ${thinkingDurationMs / 1000.0}s)…"
                                )
                            }
                        }
                        is StreamUpdate.ContentDelta -> {
                            if (thinkingStartMs > 0L) {
                                thinkingDurationMs = (PlatformBridge.currentTimeMillis() - thinkingStartMs).coerceAtLeast(thinkingDurationMs)
                            }
                            networkContentTarget.append(update.delta)
                            val wordCount = countWordsFast(networkContentTarget)
                            updateSessionStreamState(sessionId) { cur ->
                                cur?.copy(
                                    isThinkingPhase = false,
                                    statusText = "Writing ($modelName • +$wordCount words)…"
                                )
                            }
                        }
                        is StreamUpdate.ToolCallEvent -> {
                            accumulatedTools.add(update.toolCall)
                            val toolsSnap = accumulatedTools.toList()
                            patchVariantMemory { v -> v.copy(toolCalls = toolsSnap) }
                            updateSessionStreamState(sessionId) { cur ->
                                cur?.copy(statusText = "Executed tool: ${update.toolCall.name}")
                            }
                            flushCheckpointToDisk(isFinal = false)
                        }
                        is StreamUpdate.GoalProgress -> {
                            latestGoalTracker = update.goalTracker
                            val done = update.goalTracker.completedCount
                            val total = update.goalTracker.todos.size
                            val goalMsg = if (total > 0) {
                                "Goal Progress ($done/$total steps done)…"
                            } else {
                                "Planning Goal: ${update.goalTracker.goalObjective.take(48)}…"
                            }
                            updateSessionStreamState(sessionId) { cur -> cur?.copy(statusText = goalMsg) }
                            patchVariantMemory { v -> v.copy(goalTracker = update.goalTracker) }
                            flushCheckpointToDisk(isFinal = false)
                        }
                        is StreamUpdate.MediaGenerated -> {
                            accumulatedMedia.add(update.item)
                            val mediaSnap = accumulatedMedia.toList()
                            patchVariantMemory { v -> v.copy(mediaItems = mediaSnap) }
                            updateSessionStreamState(sessionId) { cur ->
                                cur?.copy(statusText = "Generated ${update.item.kind.name.lowercase()} (${accumulatedMedia.size})")
                            }
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
                            if (update.goalTracker != null) {
                                latestGoalTracker = update.goalTracker
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
                                    goalTracker = update.goalTracker ?: latestGoalTracker,
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
                            if (isLiveVoiceMode && sessionId == appState.activeSessionId && update.fullContent.isNotBlank()) {
                                PlatformBridge.speakTextAloud(stripMarkdownToPlainText(update.fullContent))
                            }
                        }
                        is StreamUpdate.Error -> {
                            networkDone = true
                            pacerJob.cancel()
                            val partialProse = networkContentTarget.toString()
                            val hasPartialReasoningOrTools = networkThinkingTarget.isNotEmpty() || accumulatedTools.isNotEmpty()
                            flushCheckpointToDisk(isFinal = true) { v ->
                                val errorNotice = when {
                                    partialProse.isNotBlank() -> {
                                        "$partialProse\n\n*[Stream paused/interrupted: ${update.message} — tap '→ Continue' to resume seamlessly]*"
                                    }
                                    hasPartialReasoningOrTools -> {
                                        "*[Stream paused/interrupted: ${update.message} — tap '→ Continue' or reply 'continue' to resume seamlessly]*"
                                    }
                                    else -> {
                                        "**[Provider Error: ${update.message}]**\n\nCheck your API Key, Base URL (`${effectiveProfileForApi.baseUrl}`), or Model ID (`$modelName`) in **⚙ Settings → Providers**."
                                    }
                                }
                                v.copy(
                                    content = errorNotice,
                                    thinkingSummary = networkThinkingTarget.toString(),
                                    thinkingSignature = signatureSb.toString(),
                                    thinkingDurationMs = thinkingDurationMs,
                                    toolCalls = accumulatedTools.toList(),
                                    goalTracker = latestGoalTracker ?: v.goalTracker,
                                    mediaItems = accumulatedMedia.toList(),
                                    isStreaming = false,
                                    rawRequestPreview = rawReq,
                                    rawResponsePreview = update.rawDetails.ifBlank { rawResp }.take(4096)
                                )
                            }
                        }
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                networkDone = true
                pacerJob.cancel()
                flushCheckpointToDisk(isFinal = true)
                throw c
            } catch (t: Throwable) {
                networkDone = true
                pacerJob.cancel()
                val partialProse = networkContentTarget.toString()
                val hasPartialReasoningOrTools = networkThinkingTarget.isNotEmpty() || accumulatedTools.isNotEmpty()
                flushCheckpointToDisk(isFinal = true) { v ->
                    val errLabel = t.message ?: t::class.simpleName ?: "Unknown error"
                    val notice = when {
                        partialProse.isNotBlank() -> "$partialProse\n\n*[Interrupted: $errLabel]*"
                        hasPartialReasoningOrTools -> "*[Interrupted: $errLabel — tap '→ Continue' or reply 'continue' to resume]*"
                        else -> "**[Stream Error: $errLabel]**"
                    }
                    v.copy(
                        content = notice,
                        thinkingSummary = networkThinkingTarget.toString(),
                        thinkingSignature = signatureSb.toString(),
                        thinkingDurationMs = thinkingDurationMs,
                        toolCalls = accumulatedTools.toList(),
                        goalTracker = latestGoalTracker ?: v.goalTracker,
                        mediaItems = accumulatedMedia.toList(),
                        isStreaming = false
                    )
                }
            } finally {
                networkDone = true
                updateSessionStreamState(sessionId) { null }

                // Auto-generate Topic Title after the first assistant turn completes with non-empty content
                val updatedSess = appState.sessions.find { it.id == sessionId }
                if (networkContentTarget.isNotBlank() && updatedSess != null && !updatedSess.isTitleManuallySet && updatedSess.title == "New Chat" && updatedSess.messages.size in 2..3) {
                    generateTopicTitle(sessionId, forceRegenerate = false)
                }

                // If there are queued prompts for this session (`⏳ Queue` mode), automatically dispatch the next one!
                val sessQueue = queuedPromptsBySession[sessionId].orEmpty()
                if (sessQueue.isNotEmpty()) {
                    val nextItem = sessQueue.first()
                    queuedPromptsBySession = queuedPromptsBySession + (sessionId to sessQueue.drop(1))
                    dispatchNextQueuedItem?.invoke(sessionId, nextItem)
                }
            }
        }

        updateSessionStreamState(sessionId) {
            SessionStreamRuntime(
                sessionId = sessionId,
                targetNodeId = targetAssistantNodeId,
                isThinkingPhase = false,
                statusText = "Connecting to ${effectiveProfileForApi.name} ($modelName)…",
                interactionId = "",
                job = newJob
            )
        }
    }

    fun dispatchPromptItem(
        sessionId: String,
        text: String,
        prefill: String,
        attachments: List<PromptAttachment>,
        isGuidedDirection: Boolean,
        isBtwSideQuestion: Boolean = false
    ) {
        val targetSess = appState.sessions.find { it.id == sessionId } ?: activeSession
        val now = PlatformBridge.currentTimeMillis()
        val updatedMessages = targetSess.messages.toMutableList()
        val userNodeId = "node-$now-u"

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
                        id = userNodeId,
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

        val autoMuteSet = if (isBtwSideQuestion) setOf(userNodeId, assistantNodeId) else emptySet()

        triggerGeneration(
            sessionId = sessionId,
            historyForCall = historyForCall,
            targetAssistantNodeId = assistantNodeId,
            targetVariantId = assistantVarId,
            continuePrefix = prefill,
            autoMuteNodeIdsOnFinish = autoMuteSet
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

    fun triggerAutoSummarizeStory(autoMuteOlderTurns: Boolean = false) {
        val targetSessId = activeSession.id
        if (activeStreamsBySession.containsKey(targetSessId) || activeSession.messages.isEmpty()) return
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

        val sumStatus = if (autoMuteOlderTurns) {
            "Compacting context & summarizing continuity ($summaryModel)…"
        } else {
            "Summarizing story continuity into memory ($summaryModel)…"
        }

        val sumJob = appProcessScope.launch {
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
                            updateSessionById(targetSessId) { it.copy(storySummary = buf.toString()) }
                        }
                        is StreamUpdate.Completed -> {
                            val summaryText = update.fullContent.trim()
                            updateSessionById(targetSessId, immediate = true) { sess ->
                                val updatedMsgs = if (autoMuteOlderTurns && sess.messages.size > 4) {
                                    val keepStartIdx = sess.messages.size - 4
                                    sess.messages.mapIndexed { idx, node ->
                                        if (idx < keepStartIdx) node.copy(includedInContext = false) else node
                                    }
                                } else {
                                    sess.messages
                                }
                                sess.copy(
                                    storySummary = summaryText,
                                    messages = updatedMsgs
                                )
                            }
                            bannerNotification = if (autoMuteOlderTurns) {
                                "Context Compacted: summarized into Memory & muted older turns (last 4 turns kept active)."
                            } else {
                                "Updated Rolling Continuity Memory."
                            }
                        }
                        is StreamUpdate.Error -> {
                            bannerNotification = "Summarize error: ${update.message}"
                        }
                        else -> {}
                    }
                }
            } finally {
                updateSessionStreamState(targetSessId) { null }
            }
        }
        updateSessionStreamState(targetSessId) {
            SessionStreamRuntime(sessionId = targetSessId, statusText = sumStatus, job = sumJob)
        }
    }

    fun sendUserTurn() {
        val rawInput = userPromptInput.trim()
        val prefill = assistantPrefillInput.trim()
        var currentAtts = pendingAttachments
        if (rawInput.isEmpty() && prefill.isEmpty() && currentAtts.isEmpty()) return

        // Handle `/compact` slash command (Summarizes into Memory + Mutes older turns, keeping last 4 active)
        if (rawInput.equals("/compact", ignoreCase = true) && currentAtts.isEmpty()) {
            userPromptInput = ""
            updateActiveSession { it.copy(draftInput = "") }
            triggerAutoSummarizeStory(autoMuteOlderTurns = true)
            return
        }

        // Handle `/rewind` slash command (Removes the last turn pair in-place)
        if (rawInput.equals("/rewind", ignoreCase = true) && currentAtts.isEmpty()) {
            userPromptInput = ""
            if (activeSession.messages.isNotEmpty()) {
                val dropCount = if (activeSession.messages.size >= 2 && activeSession.messages.last().role == MessageRole.ASSISTANT) 2 else 1
                updateActiveSession(immediate = true) {
                    it.copy(
                        messages = it.messages.dropLast(dropCount),
                        draftInput = ""
                    )
                }
                bannerNotification = "Rewound last $dropCount message(s)."
            }
            return
        }

        // Handle `/btw <question>` ephemeral side-question (auto-muted from context once answered!)
        val isBtw = rawInput.startsWith("/btw ", ignoreCase = true)
        val cleanedInput = if (isBtw) {
            "[Side Question (/btw): ${rawInput.substringAfter(" ").trim()}]"
        } else if (isGoalModeEnabled && !rawInput.startsWith("/goal", ignoreCase = true)) {
            "/goal $rawInput"
        } else {
            rawInput
        }

        // Support "Paste long text as file" threshold
        val prefs = appState.preferences
        val finalText = if (prefs.pasteLongTextAsFile && cleanedInput.length >= prefs.pasteConversionThresholdChars) {
            val now = PlatformBridge.currentTimeMillis()
            currentAtts = currentAtts + PromptAttachment(
                id = "att-paste-$now",
                fileName = "pasted-text-${cleanedInput.length}chars.txt",
                mimeType = "text/plain",
                textContent = cleanedInput
            )
            "[Attached pasted text (${cleanedInput.length} characters)]"
        } else {
            cleanedInput
        }

        userPromptInput = ""
        assistantPrefillInput = ""
        pendingAttachments = emptyList()

        dispatchPromptItem(
            sessionId = activeSession.id,
            text = finalText,
            prefill = prefill,
            attachments = currentAtts,
            isGuidedDirection = false,
            isBtwSideQuestion = isBtw
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
     * `⚡ Steer` Mode: Immediately stops the in-flight stream on the active tab, keeps partial AI output,
     * appends the user's new follow-up prompt, and starts generating from the new prompt!
     */
    fun triggerSteerNow() {
        val targetSessId = activeSession.id
        val steerText = userPromptInput.trim()
        val prefill = assistantPrefillInput.trim()
        val atts = pendingAttachments
        if (steerText.isEmpty() && prefill.isEmpty() && atts.isEmpty()) return

        userPromptInput = ""
        assistantPrefillInput = ""
        pendingAttachments = emptyList()

        uiScope.launch {
            val runningJob = activeStreamsBySession[targetSessId]?.job
            runningJob?.cancel()
            runningJob?.join()
            dispatchPromptItem(
                sessionId = targetSessId,
                text = steerText,
                prefill = prefill,
                attachments = atts,
                isGuidedDirection = false
            )
        }
    }

    /**
     * `⏳ Queue` Mode: Adds the current prompt to the active tab's queue while the current reply finishes streaming.
     */
    fun enqueueFollowUpPrompt() {
        val targetSessId = activeSession.id
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
        val curList = queuedPromptsBySession[targetSessId].orEmpty()
        queuedPromptsBySession = queuedPromptsBySession + (targetSessId to (curList + item))
        userPromptInput = ""
        assistantPrefillInput = ""
        pendingAttachments = emptyList()
        updateActiveSession { it.copy(draftInput = "", draftPrefill = "") }
    }

    fun triggerImpersonateDraft() {
        val targetSessId = activeSession.id
        if (activeStreamsBySession.containsKey(targetSessId)) return
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
        userPromptInput = ""

        val impJob = appProcessScope.launch {
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
                            if (appState.activeSessionId == targetSessId) {
                                userPromptInput = buf.toString()
                            }
                        }
                        is StreamUpdate.Completed -> {
                            val drafted = update.fullContent.trim()
                            if (appState.activeSessionId == targetSessId) {
                                userPromptInput = drafted
                            }
                            updateSessionById(targetSessId) { it.copy(draftInput = drafted) }
                        }
                        is StreamUpdate.Error -> {
                            bannerNotification = "Draft error: ${update.message}"
                        }
                        else -> {}
                    }
                }
            } finally {
                updateSessionStreamState(targetSessId) { null }
            }
        }
        updateSessionStreamState(targetSessId) {
            SessionStreamRuntime(sessionId = targetSessId, statusText = "Drafting ${activeSession.userName}'s turn…", job = impJob)
        }
    }

    fun triggerVoiceInput() {
        if (isVoiceListening) {
            PlatformBridge.stopVoiceInput()
            isVoiceListening = false
            return
        }
        PlatformBridge.stopSpeakingAloud()
        isVoiceListening = true
        bannerNotification = "Listening… Speak now."
        PlatformBridge.startVoiceInput(
            onRecognizedText = { recognized ->
                uiScope.launch {
                    isVoiceListening = false
                    bannerNotification = ""
                    val combined = if (userPromptInput.isBlank()) recognized else "${userPromptInput.trimEnd()} $recognized"
                    userPromptInput = combined
                    updateActiveSession { it.copy(draftInput = combined) }
                    if (isLiveVoiceMode && !isStreaming) {
                        sendUserTurn()
                    }
                }
            },
            onError = { errMsg ->
                uiScope.launch {
                    isVoiceListening = false
                    bannerNotification = errMsg
                }
            }
        )
    }

    fun createNewTopicForAssistant(assistant: AssistantAgent, openInNewTab: Boolean = true) {
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
            viewMode = appState.preferences.defaultViewMode,
            workspacePath = assistant.workspacePath,
            workspaceMode = assistant.workspaceMode,
            messages = initialMessages
        )
        val updatedTabs = if (openInNewTab) {
            (openTabIds + newSess.id).distinct()
        } else {
            openTabIds.map { if (it == activeSession.id) newSess.id else it }.ifEmpty { listOf(newSess.id) }
        }
        persist(
            appState.copy(
                activeAssistantId = assistant.id,
                sessions = listOf(newSess) + appState.sessions,
                activeSessionId = newSess.id,
                openTabSessionIds = updatedTabs
            ),
            immediateFlush = true
        )
    }

    fun switchToTab(sessionId: String) {
        val target = appState.sessions.find { it.id == sessionId } ?: return
        val updatedTabs = if (sessionId in openTabIds) openTabIds else (openTabIds + sessionId)
        persist(
            appState.copy(
                activeSessionId = sessionId,
                activeAssistantId = target.assistantId.ifBlank { appState.activeAssistantId },
                openTabSessionIds = updatedTabs
            ),
            immediateFlush = true
        )
        isSettingsOpen = false
        showMobileTabSwitcher = false
    }

    fun closeChatTab(sessionId: String) {
        val remainingTabs = openTabIds.filterNot { it == sessionId }
        if (remainingTabs.isEmpty()) {
            createNewTopicForAssistant(activeAssistant, openInNewTab = false)
            showMobileTabSwitcher = false
        } else {
            val nextActiveId = if (appState.activeSessionId == sessionId) {
                val closedIdx = openTabIds.indexOf(sessionId).coerceAtLeast(0)
                remainingTabs.getOrElse((closedIdx - 1).coerceAtLeast(0)) { remainingTabs.first() }
            } else {
                appState.activeSessionId
            }
            val nextSess = appState.sessions.find { it.id == nextActiveId }
            persist(
                appState.copy(
                    activeSessionId = nextActiveId,
                    activeAssistantId = nextSess?.assistantId?.ifBlank { appState.activeAssistantId } ?: appState.activeAssistantId,
                    openTabSessionIds = remainingTabs
                ),
                immediateFlush = true
            )
        }
    }

    fun executeDeleteSingleSession(targetId: String) {
        activeStreamsBySession[targetId]?.job?.cancel()
        updateSessionStreamState(targetId) { null }
        val remaining = appState.sessions.filterNot { it.id == targetId }
        val remainingTabs = openTabIds.filterNot { it == targetId }
        val remainingForAssistant = remaining.filter {
            it.assistantId == activeAssistant.id || appState.assistants.none { a -> a.id == it.assistantId }
        }
        if (remainingForAssistant.isNotEmpty()) {
            val nextActive = if (appState.activeSessionId == targetId) {
                remainingTabs.firstOrNull() ?: remainingForAssistant.first().id
            } else appState.activeSessionId
            persist(
                appState.copy(
                    sessions = remaining,
                    activeSessionId = nextActive,
                    openTabSessionIds = if (remainingTabs.isNotEmpty()) remainingTabs else listOf(nextActive)
                ),
                immediateFlush = true
            )
        } else {
            val now = PlatformBridge.currentTimeMillis()
            val fresh = ChatSession(
                id = "sess-$now",
                title = "New Chat",
                createdAt = now,
                updatedAt = now,
                profileId = activeProfile.id,
                modelOverride = activeProfile.defaultModel,
                assistantId = activeAssistant.id,
                viewMode = appState.preferences.defaultViewMode
            )
            persist(
                appState.copy(
                    sessions = listOf(fresh) + remaining,
                    activeSessionId = fresh.id,
                    openTabSessionIds = remainingTabs + fresh.id
                ),
                immediateFlush = true
            )
        }
    }

    fun executeClearAllTopicsForAssistant() {
        val otherSessions = appState.sessions.filterNot {
            it.assistantId == activeAssistant.id || appState.assistants.none { a -> a.id == it.assistantId }
        }
        val otherIds = otherSessions.map { it.id }.toSet()
        val now = PlatformBridge.currentTimeMillis()
        val fresh = ChatSession(
            id = "sess-$now",
            title = "New Chat",
            createdAt = now,
            updatedAt = now,
            profileId = activeProfile.id,
            modelOverride = activeProfile.defaultModel,
            assistantId = activeAssistant.id,
            viewMode = appState.preferences.defaultViewMode
        )
        val keptTabs = openTabIds.filter { it in otherIds } + fresh.id
        persist(
            appState.copy(
                sessions = listOf(fresh) + otherSessions,
                activeSessionId = fresh.id,
                openTabSessionIds = keptTabs
            ),
            immediateFlush = true
        )
        bannerNotification = "Cleared all chats for '${activeAssistant.name}' (Assistant & System Prompt preserved)."
    }

    ProvideParchmentTheme(config = appState.theme) {
        val p = LocalParchmentPalette.current

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(p.sheetBg)
        ) {
            val windowSize = ParchmentWindowSize(widthDp = maxWidth, heightDp = maxHeight)
            val isCompactScreen = maxWidth < 760.dp

            CompositionLocalProvider(LocalParchmentWindowSize provides windowSize) {
                var horizontalSwipeDelta by remember { mutableStateOf(0f) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                        .background(p.canvasBg)
                        .then(
                            if (isCompactScreen && !isSettingsOpen) {
                                Modifier.pointerInput(showSidebar, isSettingsOpen) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { horizontalSwipeDelta = 0f },
                                        onHorizontalDrag = { _, dragAmount ->
                                            horizontalSwipeDelta += dragAmount
                                        },
                                        onDragEnd = {
                                            if (!showSidebar && horizontalSwipeDelta > 56.dp.toPx()) {
                                                showSidebar = true
                                            } else if (showSidebar && horizontalSwipeDelta < -48.dp.toPx()) {
                                                showSidebar = false
                                            }
                                            horizontalSwipeDelta = 0f
                                        }
                                    )
                                }
                            } else Modifier
                        )
                ) {
                    // 1. Top Editorial Header Bar (Hidden whenever Settings is open on both Desktop & Mobile so there is ONLY ONE `← Chat` button!)
                    if (!isSettingsOpen) {
                        val bgStreamingCount = activeStreamsBySession.keys.count { it != activeSession.id }
                        TopEditorialBar(
                            appState = appState,
                            activeSession = activeSession,
                            activeAssistant = activeAssistant,
                            activeProfile = activeProfile,
                            isCompactScreen = isCompactScreen,
                            showSidebar = showSidebar,
                            showSessionInspector = showSessionInspector,
                            showQuickModelPopover = showQuickModelPopover,
                            openTabCount = openTabIds.size,
                            backgroundStreamingCount = bgStreamingCount,
                            onOpenMobileTabSwitcher = { showMobileTabSwitcher = true },
                            onNewChatTab = { createNewTopicForAssistant(activeAssistant, openInNewTab = true) },
                            onToggleSidebar = { showSidebar = !showSidebar },
                            onToggleSessionInspector = { showSessionInspector = !showSessionInspector },
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
                            onOpenSettings = { cat ->
                                activeSettingsCategory = cat
                                isSettingsOpen = true
                                if (isCompactScreen) showSidebar = false
                            }
                        )

                        SepiaDivider()

                        // Desktop Browser-Style Multi-Tasking Tab Bar
                        if (!isCompactScreen) {
                            DesktopBrowserTabBar(
                                appState = appState,
                                openTabIds = openTabIds,
                                activeSessionId = activeSession.id,
                                activeStreamsBySession = activeStreamsBySession,
                                onSelectTab = ::switchToTab,
                                onCloseTab = ::closeChatTab,
                                onNewTab = { createNewTopicForAssistant(activeAssistant, openInNewTab = true) }
                            )
                            SepiaDivider()
                        }
                    }

                    // Top In-App OTA Banner (Available, Downloading Progress Bar, Installing, & Error)
                    when (val currentOta = otaState) {
                        is OtaUpdateState.Available -> {
                            if (!dismissOtaBanner) {
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(p.cardBg)
                                        .padding(horizontal = 16.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val sizeMb = (currentOta.release.assetSizeBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                                    BasicText(
                                        text = "✦ OTA Update Available: ${currentOta.release.tagName} ($sizeMb MB)",
                                        style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold),
                                        modifier = Modifier.padding(top = 4.dp, end = 8.dp)
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
                        is OtaUpdateState.Downloading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(p.cardBg)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val dlMb = (currentOta.downloadedBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                                val totMb = (currentOta.totalBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.DOWNLOAD, tint = p.accent, size = 14.dp)
                                        BasicText(
                                            text = "Downloading ${currentOta.release.tagName} ($dlMb / $totMb MB)…",
                                            style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                                        )
                                    }
                                    BasicText(
                                        text = "${currentOta.progressPercent}%",
                                        style = p.monoStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(p.border.copy(alpha = 0.45f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth((currentOta.progressPercent / 100f).coerceIn(0.02f, 1f))
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(p.accent)
                                    )
                                }
                            }
                            SepiaDivider()
                        }
                        is OtaUpdateState.Installing -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(p.cardBg)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    KissStreamingPulseDot(color = p.accent)
                                    BasicText(
                                        text = currentOta.message,
                                        style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(p.accent)
                                )
                            }
                            SepiaDivider()
                        }
                        is OtaUpdateState.Error -> {
                            if (!dismissOtaBanner) {
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(p.cardBg)
                                        .padding(horizontal = 16.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    BasicText(
                                        text = "OTA Update Error: ${currentOta.message}",
                                        style = p.uiBodyStyle.copy(color = p.danger, fontWeight = FontWeight.Medium),
                                        modifier = Modifier.weight(1f, fill = false).padding(top = 4.dp, end = 8.dp)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SepiaButton(
                                            text = "↻ Retry",
                                            compact = true,
                                            style = SepiaButtonStyle.OUTLINE,
                                            onClick = ::triggerOtaCheckManual
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
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(p.elevatedBg)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BasicText(
                                text = bannerNotification,
                                style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.Medium),
                                modifier = Modifier.padding(top = 4.dp, end = 8.dp)
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

                    // 2. Main Content Area:
                    // - On Desktop: Side-by-side Sidebar + Chat/Settings
                    // - On Mobile: Chat/Settings underneath + 86%-width Slide-Over Drawer with Scrim!
                    val sidebarComposable: @Composable (Modifier) -> Unit = { mod ->
                        ParchmentSidebar(
                            appState = appState,
                            activeAssistant = activeAssistant,
                            activeSession = activeSession,
                            selectedTab = sidebarTab,
                            isCompactScreen = isCompactScreen,
                            onSelectTab = { sidebarTab = it },
                            modifier = mod,
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
                                switchToTab(sessId)
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
                                if (appState.preferences.confirmBeforeDeletingChat) {
                                    val title = appState.sessions.find { it.id == targetId }?.title ?: "Chat"
                                    pendingDeleteConfirmation = DeleteConfirmRequest.SingleSession(targetId, title)
                                } else {
                                    executeDeleteSingleSession(targetId)
                                }
                            },
                            onDeleteAllTopicsForAssistant = {
                                val matchingCount = appState.sessions.count {
                                    it.assistantId == activeAssistant.id || appState.assistants.none { a -> a.id == it.assistantId }
                                }
                                if (appState.preferences.confirmBeforeDeletingChat) {
                                    pendingDeleteConfirmation = DeleteConfirmRequest.ClearAllSessions(
                                        assistantName = activeAssistant.name,
                                        count = matchingCount
                                    )
                                } else {
                                    executeClearAllTopicsForAssistant()
                                }
                            },
                            onOpenSettings = {
                                isSettingsOpen = true
                                if (isCompactScreen) showSidebar = false
                            }
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            AnimatedVisibility(
                                visible = !isCompactScreen && showSidebar && !isSettingsOpen,
                                enter = expandHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(150)),
                                exit = shrinkHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(120))
                            ) {
                                Row(modifier = Modifier.fillMaxHeight()) {
                                    sidebarComposable(Modifier.width(285.dp).fillMaxHeight())
                                    Box(
                                        modifier = Modifier
                                            .width(0.85.dp)
                                            .fillMaxHeight()
                                            .background(p.border.copy(alpha = 0.7f))
                                    )
                                }
                            }

                            Crossfade(
                                targetState = isSettingsOpen,
                                animationSpec = tween(180),
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            ) { settingsMode ->
                            if (settingsMode) {
                                ParchmentSettingsScreen(
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
                                    onRemoveQueuedPrompt = { qId ->
                                        val curList = queuedPromptsBySession[activeSession.id].orEmpty()
                                        queuedPromptsBySession = queuedPromptsBySession + (activeSession.id to curList.filterNot { it.id == qId })
                                    },
                                    onSteerQueuedPromptNow = { item ->
                                        val targetSessId = activeSession.id
                                        val curList = queuedPromptsBySession[targetSessId].orEmpty()
                                        queuedPromptsBySession = queuedPromptsBySession + (targetSessId to curList.filterNot { it.id == item.id })
                                        uiScope.launch {
                                            val runningJob = activeStreamsBySession[targetSessId]?.job
                                            runningJob?.cancel()
                                            runningJob?.join()
                                            dispatchPromptItem(
                                                sessionId = targetSessId,
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
                                    isGoalModeEnabled = isGoalModeEnabled,
                                    onToggleGoalMode = { isGoalModeEnabled = !isGoalModeEnabled },
                                    isLiveVoiceMode = isLiveVoiceMode,
                                    isVoiceListening = isVoiceListening,
                                    onToggleLiveVoiceMode = {
                                        isLiveVoiceMode = !isLiveVoiceMode
                                        if (!isLiveVoiceMode) {
                                            PlatformBridge.stopSpeakingAloud()
                                        }
                                    },
                                    onTriggerVoiceInput = ::triggerVoiceInput,
                                    pendingAttachments = pendingAttachments,
                                    onAddAttachment = {
                                        uiScope.launch {
                                            val picked = PlatformBridge.pickFileForImport(emptyList()) ?: return@launch
                                            attachPickedFiles(listOf(picked))
                                        }
                                    },
                                    onPasteClipboardMedia = {
                                        val pasted = PlatformBridge.readClipboardFiles()
                                        if (pasted.isNotEmpty()) {
                                            attachPickedFiles(pasted)
                                            bannerNotification = "Attached ${pasted.size} clipboard item(s): ${pasted.joinToString { it.fileName }}"
                                            true
                                        } else {
                                            false
                                        }
                                    },
                                    onRequestClearCurrentChat = {
                                        if (appState.preferences.confirmBeforeDeletingChat) {
                                            pendingDeleteConfirmation = DeleteConfirmRequest.ClearCurrentMessages(activeSession.title)
                                        } else {
                                            updateActiveSession(immediate = true) { it.copy(messages = emptyList()) }
                                        }
                                    },
                                    onRemoveAttachment = { attId ->
                                        pendingAttachments = pendingAttachments.filterNot { it.id == attId }
                                    },
                                    onInspectAttachment = ::openAttachmentInViewer,
                                    onInspectGeneratedImage = { media ->
                                        activeViewerItem = ActiveMediaViewerItem.ImageViewer(
                                            title = media.revisedPrompt.take(48).ifBlank { "Generated Image" },
                                            mimeType = media.mimeType.ifBlank { "image/png" },
                                            localFilePath = media.localFilePath,
                                            remoteUrl = media.remoteUrl
                                        )
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
                                    onAutoSummarizeStory = { triggerAutoSummarizeStory(autoMuteOlderTurns = false) },
                                    onCompactContext = { triggerAutoSummarizeStory(autoMuteOlderTurns = true) },
                                    onRewindToNode = { nodeIndex ->
                                        val keepCount = (nodeIndex + 1).coerceIn(1, activeSession.messages.size)
                                        val dropped = activeSession.messages.size - keepCount
                                        if (dropped > 0) {
                                            updateActiveSession(immediate = true) { sess ->
                                                sess.copy(messages = sess.messages.take(keepCount))
                                            }
                                            bannerNotification = "Rewound chat to turn #${nodeIndex + 1} (removed $dropped later turn(s))."
                                        }
                                    },
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
                                                activeSessionId = forkedSession.id,
                                                openTabSessionIds = (openTabIds + forkedSession.id).distinct()
                                            ),
                                            immediateFlush = true
                                        )
                                        bannerNotification = "Forked topic at turn #${nodeIndex + 1}."
                                    },
                                    onStopStream = {
                                        val targetSessId = activeSession.id
                                        val curStream = activeStreamsBySession[targetSessId]
                                        curStream?.job?.cancel()
                                        if (curStream != null && curStream.interactionId.isNotBlank() && activeProfile.useBackgroundLongDecode) {
                                            appProcessScope.launch {
                                                AiProviderEngine.cancelBackgroundInteraction(activeProfile, curStream.interactionId)
                                            }
                                        }
                                        updateSessionStreamState(targetSessId) { null }
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
                                        val existingText = AiProviderEngine.stripInlineErrorNotices(targetNode.activeVariant.content)
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
                        } // Closes Row(modifier = Modifier.fillMaxSize()) so Crossfade(Modifier.weight(1f)) gets 100% width!

                        // Mobile 86%-Width Slide-Over Sidebar Drawer + Dimmed Tap-to-Dismiss Backdrop Scrim
                        if (isCompactScreen) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                AnimatedVisibility(
                                    visible = showSidebar && !isSettingsOpen,
                                    enter = fadeIn(tween(150)),
                                    exit = fadeOut(tween(130))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.34f))
                                            .clickable { showSidebar = false }
                                    )
                                }
                            }

                            Row(modifier = Modifier.fillMaxHeight()) {
                                AnimatedVisibility(
                                    visible = showSidebar && !isSettingsOpen,
                                    enter = expandHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(140)),
                                    exit = shrinkHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(120))
                                ) {
                                    Row(modifier = Modifier.fillMaxHeight()) {
                                        sidebarComposable(
                                            Modifier
                                                .fillMaxWidth(0.86f)
                                                .fillMaxHeight()
                                                .clickable(enabled = false) {}
                                        )
                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .fillMaxHeight()
                                                .background(p.border)
                                        )
                                    }
                                }
                            }
                        }

                        // Drag-and-Drop Full-Window Visual Overlay
                        if (isDraggingFilesOverWindow) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(p.canvasBg.copy(alpha = 0.88f))
                                    .border(2.dp, p.accent, RoundedCornerShape(14.dp))
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    KissVectorIcon(icon = KissIcon.UPLOAD, tint = p.accent, size = 34.dp)
                                    BasicText(
                                        text = "Drop Images, Videos, or Files to Attach",
                                        style = p.uiHeaderStyle.copy(fontSize = 20.sp, color = p.accent)
                                    )
                                    BasicText(
                                        text = "Supports PNG, JPG, WEBP, GIF, MP4, WEBM, MOV, PDF, Audio, and Code/Text files",
                                        style = p.uiLabelStyle.copy(color = p.narrationInk)
                                    )
                                }
                            }
                        }

                        // Delete / Clear Confirmation Modal Dialog (Active by default, toggleable in Settings -> Preferences)
                        val confirmReq = pendingDeleteConfirmation
                        if (confirmReq != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.42f))
                                    .clickable { pendingDeleteConfirmation = null },
                                contentAlignment = Alignment.Center
                            ) {
                                val dialogTitle = when (confirmReq) {
                                    is DeleteConfirmRequest.SingleSession -> "Delete Chat?"
                                    is DeleteConfirmRequest.ClearAllSessions -> "Clear All Chats?"
                                    is DeleteConfirmRequest.ClearCurrentMessages -> "Clear Messages?"
                                }
                                val dialogBody = when (confirmReq) {
                                    is DeleteConfirmRequest.SingleSession ->
                                        "Are you sure you want to delete '${confirmReq.title}'? This cannot be undone."
                                    is DeleteConfirmRequest.ClearAllSessions ->
                                        "Are you sure you want to delete all ${confirmReq.count} chat(s) for '${confirmReq.assistantName}'? Your Assistant and System Prompt will be kept."
                                    is DeleteConfirmRequest.ClearCurrentMessages ->
                                        "Are you sure you want to clear all messages in '${confirmReq.title}'?"
                                }
                                val confirmBtnText = when (confirmReq) {
                                    is DeleteConfirmRequest.SingleSession -> "🗑 Delete"
                                    is DeleteConfirmRequest.ClearAllSessions -> "🗑 Clear All"
                                    is DeleteConfirmRequest.ClearCurrentMessages -> "🗑 Clear"
                                }

                                Column(
                                    modifier = Modifier
                                        .widthIn(max = 420.dp)
                                        .fillMaxWidth(0.9f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(p.cardBg)
                                        .border(1.dp, p.border, RoundedCornerShape(14.dp))
                                        .clickable(enabled = false) {}
                                        .padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    BasicText(
                                        text = dialogTitle,
                                        style = p.uiHeaderStyle.copy(fontSize = 17.sp)
                                    )
                                    BasicText(
                                        text = dialogBody,
                                        style = p.uiBodyStyle.copy(color = p.narrationInk)
                                    )
                                    SepiaToggleRow(
                                        title = "Ask for confirmation before deleting",
                                        subtitle = "You can also change this anytime in Settings → Preferences",
                                        checked = appState.preferences.confirmBeforeDeletingChat,
                                        onCheckedChange = { checked ->
                                            persist(
                                                appState.copy(
                                                    preferences = appState.preferences.copy(confirmBeforeDeletingChat = checked)
                                                ),
                                                immediateFlush = true
                                            )
                                        }
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        SepiaButton(
                                            text = "Cancel",
                                            compact = true,
                                            style = SepiaButtonStyle.OUTLINE,
                                            onClick = { pendingDeleteConfirmation = null }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        SepiaButton(
                                            text = confirmBtnText,
                                            compact = true,
                                            style = SepiaButtonStyle.DANGER,
                                            onClick = {
                                                pendingDeleteConfirmation = null
                                                when (confirmReq) {
                                                    is DeleteConfirmRequest.SingleSession ->
                                                        executeDeleteSingleSession(confirmReq.sessionId)
                                                    is DeleteConfirmRequest.ClearAllSessions ->
                                                        executeClearAllTopicsForAssistant()
                                                    is DeleteConfirmRequest.ClearCurrentMessages ->
                                                        updateActiveSession(immediate = true) { it.copy(messages = emptyList()) }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Built-In Zoomable Image & Text/Code Attachment Viewer Modal
                        val viewerItem = activeViewerItem
                        if (viewerItem != null) {
                            ParchmentAttachmentViewerModal(
                                item = viewerItem,
                                onNotify = { bannerNotification = it },
                                onClose = { activeViewerItem = null }
                            )
                        }

                        // Mobile Chromium / Firefox-Style Tab Switcher Grid Sheet
                        if (showMobileTabSwitcher && isCompactScreen) {
                            MobileTabSwitcherModal(
                                appState = appState,
                                openTabIds = openTabIds,
                                activeSessionId = activeSession.id,
                                activeStreamsBySession = activeStreamsBySession,
                                onSelectTab = ::switchToTab,
                                onCloseTab = ::closeChatTab,
                                onNewTab = {
                                    createNewTopicForAssistant(activeAssistant, openInNewTab = true)
                                    showMobileTabSwitcher = false
                                },
                                onCloseModal = { showMobileTabSwitcher = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================================
// DESKTOP BROWSER-STYLE MULTI-TASKING TAB BAR & MOBILE CHROMIUM/FIREFOX TAB SWITCHER GRID
// ============================================================================================
@Composable
private fun DesktopBrowserTabBar(
    appState: AppStateSnapshot,
    openTabIds: List<String>,
    activeSessionId: String,
    activeStreamsBySession: Map<String, SessionStreamRuntime>,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit
) {
    val p = LocalParchmentPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.sheetBg.copy(alpha = 0.88f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            openTabIds.forEach { tabId ->
                val sess = appState.sessions.find { it.id == tabId } ?: return@forEach
                val isSelected = tabId == activeSessionId
                val isTabStreaming = activeStreamsBySession.containsKey(tabId)
                val tabShape = RoundedCornerShape(7.dp)

                Row(
                    modifier = Modifier
                        .clip(tabShape)
                        .background(if (isSelected) p.cardBg else p.elevatedBg.copy(alpha = 0.55f))
                        .border(
                            width = if (isSelected) 1.dp else 0.75.dp,
                            color = if (isSelected) p.accent else p.border.copy(alpha = 0.65f),
                            shape = tabShape
                        )
                        .kissClickable(pressScale = 0.98f) { onSelectTab(tabId) }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isTabStreaming) {
                        KissStreamingPulseDot(color = p.accent)
                    } else {
                        KissVectorIcon(
                            icon = KissIcon.MESSAGE_CIRCLE,
                            tint = if (isSelected) p.accent else p.mutedInk,
                            size = 12.dp
                        )
                    }
                    BasicText(
                        text = sess.title.ifBlank { "New Chat" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = p.uiLabelStyle.copy(
                            color = if (isSelected) p.primaryInk else p.narrationInk,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.widthIn(max = 165.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .kissClickable { onCloseTab(tabId) },
                        contentAlignment = Alignment.Center
                    ) {
                        KissVectorIcon(
                            icon = KissIcon.CLOSE,
                            tint = p.mutedInk,
                            size = 10.dp
                        )
                    }
                }
            }
        }

        SepiaButton(
            text = "+",
            compact = true,
            style = SepiaButtonStyle.GHOST,
            onClick = onNewTab
        )
    }
}

@Composable
private fun MobileTabSwitcherModal(
    appState: AppStateSnapshot,
    openTabIds: List<String>,
    activeSessionId: String,
    activeStreamsBySession: Map<String, SessionStreamRuntime>,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseModal: () -> Unit
) {
    val p = LocalParchmentPalette.current
    val runningTotal = activeStreamsBySession.size

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable { onCloseModal() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(p.sheetBg)
                .border(1.dp, p.border, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .clickable(enabled = false) {}
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KissVectorIcon(icon = KissIcon.TABS, tint = p.accent, size = 16.dp)
                    BasicText(
                        text = "Open Tabs (${openTabIds.size})" + if (runningTotal > 0) " • $runningTotal active" else "",
                        style = p.uiHeaderStyle.copy(fontSize = 16.sp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaButton(
                        text = "+ New Tab",
                        compact = true,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = onNewTab
                    )
                    SepiaButton(
                        text = "×",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = onCloseModal
                    )
                }
            }

            SepiaDivider()

            val rows = remember(openTabIds) { openTabIds.chunked(2) }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(rows) { _, rowPair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowPair.forEach { tabId ->
                            val sess = appState.sessions.find { it.id == tabId }
                            if (sess != null) {
                                val isSel = tabId == activeSessionId
                                val streamInfo = activeStreamsBySession[tabId]
                                val asstName = appState.assistants.find { it.id == sess.assistantId }?.name
                                    ?.takeIf { it.isNotBlank() } ?: "Default Assistant"
                                val lastMsgPreview = sess.messages.lastOrNull()?.activeVariant?.content
                                    ?.take(90)?.replace('\n', ' ').orEmpty()
                                    .ifBlank { "Empty chat — tap to start" }

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 118.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(p.cardBg)
                                        .border(
                                            width = if (isSel) 1.5.dp else 0.85.dp,
                                            color = if (isSel) p.accent else p.border,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .kissClickable(pressScale = 0.98f) { onSelectTab(tabId) }
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            BasicText(
                                                text = sess.title.ifBlank { "New Chat" },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = p.uiBodyStyle.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = if (isSel) p.accent else p.primaryInk
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clip(CircleShape)
                                                    .kissClickable { onCloseTab(tabId) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                KissVectorIcon(icon = KissIcon.CLOSE, tint = p.mutedInk, size = 11.dp)
                                            }
                                        }
                                        BasicText(
                                            text = asstName,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiLabelStyle.copy(fontSize = 10.5.sp, color = p.accentSecondary)
                                        )
                                        BasicText(
                                            text = lastMsgPreview,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.narrationInk)
                                        )
                                    }

                                    Spacer(Modifier.height(6.dp))

                                    if (streamInfo != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            KissStreamingPulseDot(color = p.accent)
                                            BasicText(
                                                text = streamInfo.statusText.ifBlank { "Running in background…" },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                                            )
                                        }
                                    } else {
                                        BasicText(
                                            text = "${sess.messages.size} messages",
                                            style = p.uiLabelStyle.copy(fontSize = 10.sp)
                                        )
                                    }
                                }
                            }
                        }
                        if (rowPair.size == 1) {
                            Spacer(Modifier.weight(1f))
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
    showSessionInspector: Boolean,
    showQuickModelPopover: Boolean,
    openTabCount: Int,
    backgroundStreamingCount: Int,
    onOpenMobileTabSwitcher: () -> Unit,
    onNewChatTab: () -> Unit,
    onToggleSidebar: () -> Unit,
    onToggleSessionInspector: () -> Unit,
    onToggleQuickModelPopover: () -> Unit,
    onSelectModelQuick: (String, String) -> Unit,
    onRenameTopicInline: (String) -> Unit,
    onRegenerateTitleAi: () -> Unit,
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
                        modifier = Modifier.kissClickable { onOpenSettings(SettingsCategory.ASSISTANTS) }
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
                                modifier = Modifier.weight(1f, fill = false).kissClickable(hoverScale = 1.01f) { isEditingTitle = true }
                            )
                            KissVectorIcon(
                                icon = KissIcon.EDIT,
                                tint = p.mutedInk,
                                size = 12.dp,
                                modifier = Modifier.kissClickable { isEditingTitle = true }
                            )
                            if (activeSession.messages.isNotEmpty()) {
                                KissVectorIcon(
                                    icon = KissIcon.SPARKLES,
                                    tint = p.accent,
                                    size = 13.dp,
                                    modifier = Modifier.kissClickable { onRegenerateTitleAi() }
                                )
                            }
                        }
                    }

                    val asstPrefix = if (activeAssistant.id.isNotBlank()) "${activeAssistant.name} • " else ""
                    val capsuleText = if (appState.preferences.showProviderInModelCapsule) {
                        "$asstPrefix${activeProfile.name} • $modelLabel ▾"
                    } else {
                        "$asstPrefix$modelLabel ▾"
                    }
                    BasicText(
                        text = capsuleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = p.uiLabelStyle.copy(color = p.accent, fontSize = 11.sp),
                        modifier = Modifier.kissClickable(hoverScale = 1.01f) { onToggleQuickModelPopover() }
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val win = LocalParchmentWindowSize.current
                val hasChatNotes = activeSession.systemPrompt.isNotBlank() ||
                    activeSession.authorsNote.isNotBlank() ||
                    activeSession.storySummary.isNotBlank()
                if (win.isCompact) {
                    // Chromium / Firefox Mobile-Style Rounded Square Tab Counter Button
                    val tabBadgeShape = RoundedCornerShape(6.dp)
                    Box(
                        modifier = Modifier
                            .height(27.dp)
                            .widthIn(min = 27.dp)
                            .clip(tabBadgeShape)
                            .background(if (backgroundStreamingCount > 0) p.accent.copy(alpha = 0.16f) else p.cardBg)
                            .border(
                                width = if (backgroundStreamingCount > 0) 1.2.dp else 1.dp,
                                color = if (backgroundStreamingCount > 0) p.accent else p.narrationInk.copy(alpha = 0.8f),
                                shape = tabBadgeShape
                            )
                            .kissClickable { onOpenMobileTabSwitcher() }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            if (backgroundStreamingCount > 0) {
                                KissStreamingPulseDot(color = p.accent)
                            }
                            BasicText(
                                text = openTabCount.toString(),
                                style = p.monoStyle.copy(
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (backgroundStreamingCount > 0) p.accent else p.primaryInk
                                )
                            )
                        }
                    }
                } else {
                    SepiaButton(
                        text = "+ New Tab",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = onNewChatTab
                    )
                }
                SepiaChip(
                    label = if (win.isCompact) "✎" else "✎ Notes",
                    selected = showSessionInspector || hasChatNotes,
                    onClick = onToggleSessionInspector
                )
                SepiaButton(
                    text = if (win.isCompact) "⚙" else "⚙ Settings",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
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
// PARCHMENT `[ Assistants | Chats ]` LEFT SIDEBAR
// ============================================================================================
@Composable
private fun ParchmentSidebar(
    appState: AppStateSnapshot,
    activeAssistant: AssistantAgent,
    activeSession: ChatSession,
    selectedTab: SidebarTab,
    isCompactScreen: Boolean,
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
            .background(p.sheetBg.copy(alpha = if (isCompactScreen) 0.98f else 0.65f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Top Search Pill Bar (`🔍 Search assistants` / `🔍 Search topics`) + Single `+` Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SepiaTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = if (selectedTab == SidebarTab.ASSISTANTS) "🔍 Search assistants…" else "🔍 Search chats…",
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

        // 2. Parchment 2-Tab Pill Switcher (`[ Assistants | Chats ]`)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .background(p.cardBg.copy(alpha = 0.7f))
                .border(0.8.dp, p.border.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(SidebarTab.ASSISTANTS to "Assistants", SidebarTab.TOPICS to "Chats").forEach { (tab, label) ->
                val isSel = selectedTab == tab
                val tabInteraction = remember { MutableInteractionSource() }
                val tabHovered by tabInteraction.collectIsHoveredAsState()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .kissClickable(
                            pressScale = 0.96f,
                            hoverScale = 1.01f,
                            interactionSource = tabInteraction,
                            onClick = { onSelectTab(tab) }
                        )
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            when {
                                isSel -> p.sheetBg
                                tabHovered -> p.accent.copy(alpha = 0.08f)
                                else -> Color.Transparent
                            }
                        )
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = label,
                        style = p.uiBodyStyle.copy(
                            fontSize = 13.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSel || tabHovered) p.accent else p.narrationInk
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
                            text = "No matching assistants found. Tap '+' above to create a new assistant.",
                            style = p.uiLabelStyle,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                itemsIndexed(filteredAssistants, key = { _, a -> a.id }) { _, asst ->
                    val isSel = asst.id == activeAssistant.id
                    val shape = RoundedCornerShape(999.dp)
                    val rowInteraction = remember { MutableInteractionSource() }
                    val rowHovered by rowInteraction.collectIsHoveredAsState()
                    val rowPressed by rowInteraction.collectIsPressedAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .kissClickable(
                                pressScale = 0.98f,
                                hoverScale = 1.01f,
                                interactionSource = rowInteraction,
                                onClick = { onSelectAssistant(asst) }
                            )
                            .clip(shape)
                            .background(
                                when {
                                    isSel -> p.sheetBg
                                    rowPressed -> p.accent.copy(alpha = 0.14f)
                                    rowHovered -> p.accent.copy(alpha = 0.08f)
                                    else -> Color.Transparent
                                }
                            )
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
                                    color = if (isSel || rowHovered) p.accent else p.primaryInk
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
            // CHATS / TOPICS TAB (Filtered by active Assistant, Pinned first, sorted by updatedAt)
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val hdrIcon = remember(activeAssistant.iconEmoji) {
                        parseKissLabelIcons(activeAssistant.iconEmoji).leadingIcon ?: KissIcon.SPARKLES
                    }
                    KissVectorIcon(icon = hdrIcon, tint = p.accent, size = 13.dp)
                    BasicText(
                        text = "${activeAssistant.name} (${assistantTopics.size})",
                        style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                if (assistantTopics.size > 1 || assistantTopics.any { it.messages.isNotEmpty() || it.title != "New Chat" }) {
                    SepiaButton(
                        text = "Clear All",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = onDeleteAllTopicsForAssistant
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(assistantTopics, key = { _, s -> s.id }) { _, sess ->
                    val selected = sess.id == activeSession.id
                    val shape = RoundedCornerShape(10.dp)
                    val rowInteraction = remember { MutableInteractionSource() }
                    val rowHovered by rowInteraction.collectIsHoveredAsState()
                    val rowPressed by rowInteraction.collectIsPressedAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .kissClickable(
                                pressScale = 0.98f,
                                hoverScale = 1.008f,
                                interactionSource = rowInteraction,
                                onClick = { onSelectSession(sess.id) }
                            )
                            .clip(shape)
                            .background(
                                when {
                                    selected -> p.cardBg
                                    rowPressed -> p.accent.copy(alpha = 0.14f)
                                    rowHovered -> p.accent.copy(alpha = 0.08f)
                                    else -> p.elevatedBg.copy(alpha = 0.35f)
                                }
                            )
                            .border(
                                0.85.dp,
                                if (selected || rowHovered) p.accent.copy(alpha = if (selected) 1f else 0.55f) else Color.Transparent,
                                shape
                            )
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
                                    KissVectorIcon(icon = KissIcon.PIN, tint = p.accent, size = 11.dp)
                                }
                                BasicText(
                                    text = sess.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiBodyStyle.copy(
                                        fontSize = 13.5.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected || rowHovered) p.accent else p.primaryInk
                                    )
                                )
                            }
                            if (sess.messages.isNotEmpty()) {
                                BasicText(
                                    text = "${sess.messages.size} messages",
                                    style = p.uiLabelStyle.copy(fontSize = 10.5.sp)
                                )
                            }
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

        if (isCompactScreen) {
            SepiaDivider()

            // Mobile Drawer Footer: Quick Settings Button
            SepiaButton(
                text = "⚙ Settings & Providers",
                style = SepiaButtonStyle.OUTLINE,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenSettings
            )
        }
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
    isGoalModeEnabled: Boolean,
    onToggleGoalMode: () -> Unit,
    isLiveVoiceMode: Boolean,
    isVoiceListening: Boolean,
    onToggleLiveVoiceMode: () -> Unit,
    onTriggerVoiceInput: () -> Unit,
    pendingAttachments: List<PromptAttachment>,
    onAddAttachment: () -> Unit,
    onPasteClipboardMedia: () -> Boolean,
    onRequestClearCurrentChat: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onInspectAttachment: (PromptAttachment) -> Unit,
    onInspectGeneratedImage: (GeneratedMediaItem) -> Unit,
    onExportChatMarkdown: () -> Unit,
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

    // 5-Second Minimap Linger Timer (`5000ms` after scrolling stops or after any minimap button tap!)
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

    val isScrolledUpFromBottom by remember {
        derivedStateOf {
            activeSession.messages.size > 2 && listState.canScrollForward
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Collapsible Chat Notes & Memory Drawer (Toggled via `✎ Notes` in the Top Bar — zero duplicate sub-header ribbon!)
        AnimatedVisibility(visible = showSessionInspector) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
                        .border(0.85.dp, p.border, RoundedCornerShape(10.dp))
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
                            text = "Chat Notes & Memory",
                            style = p.uiHeaderStyle.copy(fontSize = 14.5.sp),
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
                                    text = "Clear Chat",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = onRequestClearCurrentChat
                                )
                            }
                            SepiaButton(
                                text = "×",
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
                        label = "Extra Instructions for This Chat",
                        placeholder = "Optional instructions added specifically to this conversation…",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SepiaTextField(
                        value = activeSession.authorsNote,
                        onValueChange = { note -> onUpdateSession { it.copy(authorsNote = note) } },
                        label = "Style & Tone Reminder",
                        placeholder = "Optional reminder kept near the end of the conversation…",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BasicText(
                            text = "Chat Summary & Memory",
                            style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(top = 4.dp, end = 8.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SepiaButton(
                                text = if (isStreaming) "Working…" else "✦ Summarize",
                                compact = true,
                                enabled = !isStreaming && activeSession.messages.isNotEmpty(),
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = onAutoSummarizeStory
                            )
                            SepiaButton(
                                text = "⚡ Compact Context",
                                compact = true,
                                enabled = !isStreaming && activeSession.messages.size > 4,
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
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Main Chat Area + Floating Message Navigation Minimap (`⤒`, `↑`, `🗺 Tree/Swipes`, `↓`, `⤓`)
        val win = LocalParchmentWindowSize.current
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
                    .padding(horizontal = if (win.isCompact) 12.dp else 18.dp),
                verticalArrangement = Arrangement.spacedBy(
                    if (preferences.defaultViewMode == ViewMode.MANUSCRIPT) 16.dp else 18.dp
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
                                .padding(vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SepiaAvatarCircle(
                                symbol = activeAssistant.iconEmoji,
                                selected = true,
                                sizeDp = 46.dp
                            )
                            BasicText(
                                text = displayName,
                                style = p.uiHeaderStyle.copy(fontSize = 19.sp)
                            )
                            BasicText(
                                text = "${activeProfile.name} • ${activeModel.ifBlank { "Select a model in Settings" }}",
                                style = p.uiLabelStyle.copy(color = p.mutedInk)
                            )
                            if (card != null && card.firstMessage.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                SepiaButton(
                                    text = "Start Opening Scene",
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
                        viewMode = preferences.defaultViewMode,
                        preferences = preferences,
                        userName = activeSession.userName,
                        characterName = activeSession.activeCard?.name?.takeIf { it.isNotBlank() }
                            ?: activeAssistant.name.takeIf { activeAssistant.id.isNotBlank() }.orEmpty(),
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
                        onRewindHere = { onRewindToNode(index) },
                        onRegenerate = { onRegenerateNode(index) },
                        onContinue = { onContinueAssistantNode(index) },
                        onInspectAttachment = onInspectAttachment,
                        onInspectGeneratedImage = onInspectGeneratedImage
                    )
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            // Floating `↓ Latest` Pill when scrolled up
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
            ) {
                AnimatedVisibility(
                    visible = isScrolledUpFromBottom,
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(150))
                ) {
                    SepiaChip(
                        label = "↓ Latest",
                        selected = true,
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(activeSession.messages.size.coerceAtLeast(0))
                            }
                        }
                    )
                }
            }

            // Floating Message Navigation Minimap (`⤒`, `↑`, `🗺 Tree/Swipes`, `↓`, `⤓`) with 5-Second Linger!
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
                                            .clip(RoundedCornerShape(7.dp))
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
                            .background(p.cardBg.copy(alpha = 0.94f))
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
                            onClick = {
                                minimapInteractionTick++
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                        )
                        // Jump Previous Message
                        SepiaButton(
                            text = "↑",
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
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                minimapInteractionTick++
                                scope.launch {
                                    listState.animateScrollToItem(activeSession.messages.size.coerceAtLeast(0))
                                }
                            }
                        )
                    }
                }
                }
            }
        }

        // ========================================================================================
        // ZEN-BY-DEFAULT FLOATING COMPOSER WITH DESKTOP ENTER-TO-SEND & STEER / QUEUE
        // ========================================================================================
        var showComposerToolsDrawer by remember { mutableStateOf(false) }
        val hasTypedInput = userPromptInput.isNotBlank() || assistantPrefillInput.isNotBlank() || pendingAttachments.isNotEmpty()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.canvasBg)
                .padding(horizontal = if (win.isCompact) 10.dp else 14.dp, vertical = 7.dp),
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
                // 1. Subtle Live Streaming Status Strip (No duplicate Stop button here — Stop is in the action bar below!)
                AnimatedVisibility(visible = isStreaming) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.sheetBg.copy(alpha = 0.75f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        KissStreamingPulseDot(color = p.accent)
                        BasicText(
                            text = streamingStatus.ifBlank { "Generating reply…" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiBodyStyle.copy(color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                            text = "Queued Follow-Up (${queuedPrompts.size} — auto-sends when current reply finishes):",
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
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiBodyStyle.copy(fontSize = 12.sp),
                                    modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp)
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

                // 3. Collapsible Capabilities & Co-Writing Tray (`⚙ Tools`)
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
                        SepiaDivider()
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                            if (activeProfile.protocol == ProviderProtocol.BYTEDANCE_ARK ||
                                activeProfile.protocol == ProviderProtocol.OPENAI_RESPONSES
                            ) {
                                SepiaChip(
                                    label = "🎬 Video Gen",
                                    selected = activeProfile.enableVideoGeneration,
                                    onClick = { onUpdateProfile { it.copy(enableVideoGeneration = !it.enableVideoGeneration) } }
                                )
                            }
                            SepiaChip(
                                label = if (isGoalModeEnabled) "🎯 Goal Mode: ON" else "🎯 Goal Mode (/goal)",
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
                                    label = "💻 Code Exec",
                                    selected = activeProfile.enableCodeExecution,
                                    onClick = { onUpdateProfile { it.copy(enableCodeExecution = !it.enableCodeExecution) } }
                                )
                                SepiaChip(
                                    label = "🔗 URL Context",
                                    selected = activeProfile.enableUrlContext,
                                    onClick = { onUpdateProfile { it.copy(enableUrlContext = !it.enableUrlContext) } }
                                )
                            }
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
                                label = if (showPrefillBox) "▾ Start AI Reply With: ON" else "▸ Start AI Reply With…",
                                selected = showPrefillBox || assistantPrefillInput.isNotBlank(),
                                onClick = onTogglePrefillBox
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
                                text = "Co-Writing Helpers:",
                                style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(top = 5.dp, end = 4.dp)
                            )
                            SepiaButton(
                                text = "✍ Draft My Reply",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    showComposerToolsDrawer = false
                                    onImpersonateDraft()
                                }
                            )
                            SepiaButton(
                                text = "🧭 Guide Next Reply",
                                compact = true,
                                enabled = hasTypedInput,
                                style = SepiaButtonStyle.SECONDARY,
                                onClick = {
                                    showComposerToolsDrawer = false
                                    onSendGuidedDirection()
                                }
                            )
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
                            val thumbBmp = remember(att.localFilePath, isImg) {
                                if (isImg && att.localFilePath.isNotBlank()) {
                                    PlatformBridge.loadImageBitmapFromFile(att.localFilePath)
                                } else null
                            }

                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(p.cardBg)
                                    .border(0.85.dp, p.accent.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                    .clickable { onInspectAttachment(att) }
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
                                            .clip(RoundedCornerShape(5.dp))
                                            .border(0.6.dp, p.border, RoundedCornerShape(5.dp))
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

                // 4. Clean Borderless Prompt Input Area with Desktop Enter-to-Send & Shift+Enter Newline!
                val targetName = activeSession.activeCard?.name?.takeIf { it.isNotBlank() }
                    ?: activeAssistant.name.takeIf { it.isNotBlank() }
                    ?: activeSession.modelOverride.ifBlank { activeProfile.defaultModel }.ifBlank { "AI" }
                SepiaTextField(
                    value = userPromptInput,
                    onValueChange = onUserPromptChange,
                    placeholder = if (isStreaming) {
                        "Type a follow-up to Steer immediately or Queue next…"
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

                SepiaDivider()

                // 5. Zen Composer Footer: Dynamically scaled for both Mobile & Desktop (zero right-side whitespace!)
                val hasActiveTools = activeProfile.enableWebSearch ||
                    activeProfile.enableImageGeneration ||
                    activeProfile.enableVideoGeneration ||
                    activeProfile.enableCodeExecution ||
                    activeProfile.enableUrlContext ||
                    activeSession.workspaceMode != WorkspaceMode.OFF ||
                    isGoalModeEnabled ||
                    isLiveVoiceMode ||
                    showPrefillBox

                val rawEffort = activeProfile.thinkingEffort.ifBlank { "default" }.lowercase()
                val compactEffortCode = when (rawEffort) {
                    "minimal" -> "MIN"
                    "none" -> "NONE"
                    "low" -> "LOW"
                    "medium" -> "MED"
                    "high" -> "HI"
                    "xhigh" -> "XHI"
                    "max" -> "MAX"
                    else -> "DEF"
                }
                val thinkingLabel = if (preferences.showReasoningLevelOnButton) {
                    if (win.isCompact) "🧠 $compactEffortCode" else "🧠 ${rawEffort.uppercase()}"
                } else {
                    "🧠"
                }

                val toolsChipLabel = if (win.isCompact) {
                    if (hasActiveTools) "⚙ •" else "⚙"
                } else {
                    if (hasActiveTools) "⚙ Tools •" else "⚙ Tools"
                }

                val leftChipsComposable: @Composable (Modifier) -> Unit = { rowMod ->
                    Row(
                        modifier = rowMod,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SepiaChip(
                            label = "📎",
                            selected = pendingAttachments.isNotEmpty(),
                            onClick = onAddAttachment
                        )
                        SepiaChip(
                            label = "📋",
                            selected = false,
                            onClick = { onPasteClipboardMedia() }
                        )
                        SepiaChip(
                            label = "🎙",
                            selected = isVoiceListening || isLiveVoiceMode,
                            onClick = onTriggerVoiceInput
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
                        SepiaChip(
                            label = toolsChipLabel,
                            selected = showComposerToolsDrawer || hasActiveTools,
                            onClick = { showComposerToolsDrawer = !showComposerToolsDrawer }
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
                            leftChipsComposable(Modifier.weight(1f).horizontalScroll(rememberScrollState()))
                            if (preferences.showTokenAndContextStats && (activeSession.messages.isNotEmpty() || hasTypedInput)) {
                                Spacer(Modifier.width(6.dp))
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
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SepiaButton(
                                text = "■ Stop",
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
                            }
                        }
                    }
                } else {
                    // Single full-width SpaceBetween row on both Mobile (idle) and Desktop!
                    // Passing Modifier.weight(1f) to the left row forces Compose to measure the unweighted right row (Context Ring + Send) FIRST,
                    // guaranteeing the Send button can NEVER overflow or clip off the right edge!
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        leftChipsComposable(Modifier.weight(1f).horizontalScroll(rememberScrollState()))

                        Spacer(Modifier.width(6.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (preferences.showTokenAndContextStats && (activeSession.messages.isNotEmpty() || hasTypedInput)) {
                                SepiaContextGaugeRing(
                                    usedTokens = liveContextTokens,
                                    contextLimitTokens = activeProfile.contextWindowTokens,
                                    sessionCostUsd = sessionTotalCostUsd,
                                    onClick = onOpenPricingSettings
                                )
                            }

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
    onRewindHere: () -> Unit = {},
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onInspectAttachment: (PromptAttachment) -> Unit = {},
    onInspectGeneratedImage: (GeneratedMediaItem) -> Unit = {}
) {
    val p = LocalParchmentPalette.current
    @Suppress("DEPRECATION")
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

    var variantSwipeDelta by remember(node.id, node.activeVariantIndex) { mutableStateOf(0f) }

    val baseContainerModifier = if (viewMode == ViewMode.MANUSCRIPT) {
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

    val containerModifier = if (node.variants.size > 1) {
        baseContainerModifier.pointerInput(node.id, node.activeVariantIndex, node.variants.size) {
            detectHorizontalDragGestures(
                onDragStart = { variantSwipeDelta = 0f },
                onHorizontalDrag = { _, dragAmount -> variantSwipeDelta += dragAmount },
                onDragEnd = {
                    if (variantSwipeDelta < -52.dp.toPx() && node.activeVariantIndex < node.variants.lastIndex) {
                        onSelectVariant(node.activeVariantIndex + 1)
                    } else if (variantSwipeDelta > 52.dp.toPx() && node.activeVariantIndex > 0) {
                        onSelectVariant(node.activeVariantIndex - 1)
                    }
                    variantSwipeDelta = 0f
                }
            )
        }
    } else {
        baseContainerModifier
    }

    Column(modifier = containerModifier) {
        // Turn Header & Branch/Swipe Bar (FlowRow so Speaker/Model and Swipe/Thought pills never overlap on mobile!)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isAssistant && preferences.showModelName) {
                    val provSuffix = if (preferences.showProviderAfterModelName) " ($providerName)" else ""
                    if (characterName.isNotBlank()) {
                        BasicText(
                            text = characterName.uppercase(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiLabelStyle.copy(
                                color = p.accent,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.7.sp
                            )
                        )
                        if (activeVar.modelUsed.isNotBlank()) {
                            BasicText(
                                text = "• ${activeVar.modelUsed}$provSuffix",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = p.uiLabelStyle.copy(fontSize = 11.sp)
                            )
                        }
                    } else {
                        BasicText(
                            text = "${activeVar.modelUsed.ifBlank { "Assistant" }}$provSuffix",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiLabelStyle.copy(
                                color = p.accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp
                            )
                        )
                    }
                } else if (!isAssistant && preferences.showUserName) {
                    BasicText(
                        text = speakerTitle.uppercase(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = p.uiLabelStyle.copy(
                            color = p.narrationInk,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp
                        )
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
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp, end = 6.dp)
                        ) {
                            if (isActivelyThinkingThisNode) {
                                KissStreamingPulseDot(color = p.accent)
                            }
                            BasicText(
                                text = if (isActivelyThinkingThisNode) "Thinking…" else "Thought (${countWordsFast(activeVar.thinkingSummary)} words)",
                                style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                            )
                        }
                        SepiaButton(
                            text = "Hide ▲",
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
                goalTracker.isCompleted -> "COMPLETED ($doneCount/$totalCount)"
                totalCount > 0 -> "IN PROGRESS ($doneCount/$totalCount)"
                else -> "PLANNING GOAL"
            }
            Column(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(p.sheetBg.copy(alpha = 0.78f))
                    .border(
                        width = 0.9.dp,
                        color = if (goalTracker.isCompleted) p.accentSecondary else p.accent,
                        shape = RoundedCornerShape(9.dp)
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
                                        fontSize = 12.5.sp,
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
                    SepiaButton(
                        text = "Save",
                        compact = true,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = {
                            onSaveEditedText(editBuffer, false)
                            isEditing = false
                        }
                    )
                    SepiaButton(
                        text = "Save as New Version",
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
            if (activeVar.content.isNotBlank() || (activeVar.mediaItems.isEmpty() && activeVar.attachments.isEmpty())) {
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
                            val bmp = remember(att.localFilePath) {
                                PlatformBridge.loadImageBitmapFromFile(att.localFilePath)
                            }
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 360.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(p.cardBg)
                                    .border(0.85.dp, p.border, RoundedCornerShape(10.dp))
                                    .clickable { onInspectAttachment(att) }
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
                                    .border(0.85.dp, p.border, RoundedCornerShape(8.dp))
                                    .clickable { onInspectAttachment(att) }
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
                                                text = "🔍 View & Zoom",
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    KissVectorIcon(icon = KissIcon.VIDEO, tint = p.accent, size = 15.dp)
                                    BasicText(
                                        text = "Generated Video (${media.mimeType}${if (media.aspectRatio.isNotBlank()) " • ${media.aspectRatio}" else ""})",
                                        style = p.uiHeaderStyle.copy(fontSize = 14.5.sp, color = p.accent)
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
                    var isSpeakingThisTurn by remember(node.id, node.activeVariantIndex) { mutableStateOf(false) }

                    // Zen-by-Default Primary Actions: Copy, Speak (TTS), Redo (for AI), & More (⋯)
                    SepiaButton(
                        text = if (copiedBadge == "txt") "✓ Copied" else "📋 Copy",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            clipboard.setText(AnnotatedString(stripMarkdownToPlainText(activeVar.content)))
                            copiedBadge = "txt"
                        }
                    )
                    if (activeVar.content.isNotBlank()) {
                        SepiaButton(
                            text = if (isSpeakingThisTurn) "🔇 Stop" else "🔊 Speak",
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
                    if (isAssistant) {
                        SepiaButton(
                            text = "↻ Redo",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onRegenerate
                        )
                    }
                    SepiaButton(
                        text = if (showMoreActions) "▾ Less" else "⋯",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { showMoreActions = !showMoreActions }
                    )

                    if (showMoreActions) {
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
                        "first token ${formatSecondsFromMs(m.ttftMs)} • ${m.tokensPerSec} tok/s • In ${formatCompactTokens(m.inputTokens)}$cacheStr$thinkStr • Out ${formatCompactTokens(m.outputTokens)} • Total ${formatCompactTokens(m.totalTokens)} tok$costStr"
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
// BUILT-IN ZOOMABLE IMAGE LIGHTBOX & TEXT/CODE ATTACHMENT VIEWER MODAL (KISS)
// ============================================================================================
@Composable
private fun ParchmentAttachmentViewerModal(
    item: ActiveMediaViewerItem,
    onNotify: (String) -> Unit,
    onClose: () -> Unit
) {
    val p = LocalParchmentPalette.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .clickable { onClose() },
        contentAlignment = Alignment.Center
    ) {
        when (item) {
            is ActiveMediaViewerItem.ImageViewer -> {
                val bmp = remember(item.localFilePath) {
                    PlatformBridge.loadImageBitmapFromFile(item.localFilePath)
                }
                var scale by remember(item) { mutableStateOf(1f) }
                var offsetX by remember(item) { mutableStateOf(0f) }
                var offsetY by remember(item) { mutableStateOf(0f) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                        .clickable(enabled = false) {},
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top Control Bar
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.cardBg.copy(alpha = 0.96f))
                            .border(0.85.dp, p.border, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                KissVectorIcon(icon = KissIcon.IMAGE, tint = p.accent, size = 15.dp)
                                BasicText(
                                    text = item.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiHeaderStyle.copy(fontSize = 14.5.sp)
                                )
                            }
                            val dimInfo = if (bmp != null) "${bmp.width}×${bmp.height}px • " else ""
                            BasicText(
                                text = "$dimInfo${item.mimeType} • Pinch, Scroll Wheel, or Double-Tap to Zoom",
                                style = p.uiLabelStyle
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SepiaButton(
                                text = "−",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    scale = (scale / 1.25f).coerceIn(0.5f, 8f)
                                    if (scale <= 1.02f) {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            )
                            SepiaButton(
                                text = "${(scale * 100).toInt()}%",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            )
                            SepiaButton(
                                text = "+",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    scale = (scale * 1.25f).coerceIn(0.5f, 8f)
                                }
                            )
                            val openTarget = item.localFilePath.ifBlank { item.remoteUrl }
                            if (openTarget.isNotBlank()) {
                                SepiaButton(
                                    text = "Open in OS",
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { PlatformBridge.openOrPlayMedia(openTarget) }
                                )
                            }
                            if (item.localFilePath.isNotBlank()) {
                                SepiaButton(
                                    text = "Save As",
                                    compact = true,
                                    style = SepiaButtonStyle.SECONDARY,
                                    onClick = {
                                        scope.launch {
                                            val bytes = PlatformBridge.readBinaryFileBytes(item.localFilePath) ?: return@launch
                                            val saved = PlatformBridge.saveFileForExport(item.title, bytes)
                                            if (saved != null) onNotify("Saved image to $saved")
                                        }
                                    }
                                )
                            }
                            SepiaButton(
                                text = "✕ Close",
                                compact = true,
                                style = SepiaButtonStyle.PRIMARY,
                                onClick = onClose
                            )
                        }
                    }

                    // Interactive Zoomable & Pannable Image Viewport
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .pointerInput(item) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (scale > 1.05f) {
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                        } else {
                                            scale = 2.5f
                                        }
                                    }
                                )
                            }
                            .pointerInput(item) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(0.5f, 8f)
                                    scale = newScale
                                    if (newScale > 1.0f) {
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            }
                            .pointerInput(item) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Scroll) {
                                            val deltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                            if (deltaY != 0f) {
                                                val factor = if (deltaY < 0f) 1.15f else (1f / 1.15f)
                                                scale = (scale * factor).coerceIn(0.5f, 8f)
                                                if (scale <= 1.02f) {
                                                    offsetX = 0f
                                                    offsetY = 0f
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = item.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offsetX,
                                        translationY = offsetY
                                    )
                            )
                        } else {
                            BasicText(
                                text = "Unable to decode image preview (${item.localFilePath.ifBlank { item.remoteUrl }})",
                                style = p.uiHeaderStyle.copy(color = Color.White)
                            )
                        }
                    }
                }
            }

            is ActiveMediaViewerItem.TextOrCodeViewer -> {
                var copied by remember(item) { mutableStateOf(false) }
                val linesCount = remember(item.textContent) { item.textContent.lines().size }

                Column(
                    modifier = Modifier
                        .widthIn(max = 940.dp)
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(0.88f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.cardBg)
                        .border(1.dp, p.border, RoundedCornerShape(12.dp))
                        .clickable(enabled = false) {}
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                KissVectorIcon(icon = KissIcon.FILE_TEXT, tint = p.accent, size = 15.dp)
                                BasicText(
                                    text = item.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiHeaderStyle.copy(fontSize = 15.5.sp)
                                )
                            }
                            BasicText(
                                text = "$linesCount lines • ${item.textContent.length} chars • ${item.mimeType}",
                                style = p.uiLabelStyle
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SepiaButton(
                                text = if (copied) "✓ Copied" else "📋 Copy",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    clipboard.setText(AnnotatedString(item.textContent))
                                    copied = true
                                }
                            )
                            if (item.localFilePath.isNotBlank()) {
                                SepiaButton(
                                    text = "Open in OS",
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { PlatformBridge.openOrPlayMedia(item.localFilePath) }
                                )
                            }
                            SepiaButton(
                                text = "✕ Close",
                                compact = true,
                                style = SepiaButtonStyle.PRIMARY,
                                onClick = onClose
                            )
                        }
                    }

                    SepiaDivider()

                    SelectionContainer(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.sheetBg.copy(alpha = 0.65f))
                            .border(0.75.dp, p.border.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        BasicText(
                            text = item.textContent.ifEmpty { "(Empty file)" },
                            style = p.monoStyle.copy(fontSize = 12.5.sp, lineHeight = 18.sp)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================================
// CLEAN & RESILIENT STATE LOADER — LOADS SAVED STATE 1:1 AND RECOVERS FIELD-BY-FIELD IF NEEDED
// ============================================================================================
private fun loadOrCreateDefaultAppState(): AppStateSnapshot {
    val raw = PlatformBridge.loadAppStateJson()
    if (!raw.isNullOrBlank()) {
        val directResult = runCatching {
            val loaded = compactStateJson.decodeFromString<AppStateSnapshot>(raw)
            sanitizeLoadedSnapshot(loaded)
        }.getOrNull()

        if (directResult != null) {
            return directResult
        }

        // Field-by-field fallback recovery: if a schema field ever changed across updates,
        // recover every valid profile, API key, assistant, session, and preference individually!
        val recovered = runCatching {
            recoverAppStateFromPartialJson(raw)
        }.getOrNull()

        if (recovered != null) {
            return sanitizeLoadedSnapshot(recovered)
        }
    }
    return createDefaultAppState()
}

private fun sanitizeLoadedSnapshot(loaded: AppStateSnapshot): AppStateSnapshot {
    val defaultFallback = createDefaultAppState()
    val safeProfiles = if (loaded.profiles.isNotEmpty()) loaded.profiles else defaultFallback.profiles
    val safeActiveProfileId = safeProfiles.find { it.id == loaded.activeProfileId }?.id ?: safeProfiles.first().id
    val safeActiveProfile = safeProfiles.first { it.id == safeActiveProfileId }

    val safeAssistants = if (loaded.assistants.isNotEmpty()) {
        loaded.assistants
    } else {
        defaultFallback.assistants
    }
    val safeActiveAssistantId = safeAssistants.find { it.id == loaded.activeAssistantId }?.id
        ?: safeAssistants.first().id

    val recoveredSessions = if (loaded.sessions.isNotEmpty()) {
        loaded.sessions.map { sess ->
            sess.copy(
                assistantId = sess.assistantId.ifBlank { safeActiveAssistantId },
                messages = sess.messages.map { node ->
                    node.copy(
                        variants = node.variants.map { v ->
                            if (v.isStreaming) v.copy(isStreaming = false) else v
                        }
                    )
                }
            )
        }
    } else {
        val now = PlatformBridge.currentTimeMillis()
        listOf(
            ChatSession(
                id = "sess-$now",
                title = "New Chat",
                createdAt = now,
                updatedAt = now,
                profileId = safeActiveProfile.id,
                modelOverride = safeActiveProfile.defaultModel,
                assistantId = safeActiveAssistantId
            )
        )
    }

    val safeActiveSessionId = recoveredSessions.find { it.id == loaded.activeSessionId }?.id ?: recoveredSessions.first().id
    val validSessionIdSet = recoveredSessions.map { it.id }.toSet()
    val safeOpenTabs = (loaded.openTabSessionIds.filter { it in validSessionIdSet } + safeActiveSessionId).distinct()

    val migratedTheme = if (loaded.theme.name.endsWith("Warm Ivory (Light)") && !loaded.theme.name.startsWith("Parchment ")) {
        loaded.theme.copy(name = "Parchment Warm Ivory (Light)")
    } else {
        loaded.theme
    }

    return loaded.copy(
        profiles = safeProfiles,
        activeProfileId = safeActiveProfileId,
        assistants = safeAssistants,
        activeAssistantId = safeActiveAssistantId,
        sessions = recoveredSessions,
        activeSessionId = safeActiveSessionId,
        openTabSessionIds = safeOpenTabs,
        theme = migratedTheme
    )
}

private fun recoverAppStateFromPartialJson(raw: String): AppStateSnapshot {
    val fallback = createDefaultAppState()
    val root = compactStateJson.parseToJsonElement(raw) as? JsonObject ?: return fallback

    fun <T> decodeListItems(key: String, itemDeserializer: kotlinx.serialization.DeserializationStrategy<T>): List<T> {
        val arr = root[key] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { elem ->
            runCatching { compactStateJson.decodeFromJsonElement(itemDeserializer, elem) }.getOrNull()
        }
    }

    val profiles = decodeListItems("profiles", ProviderProfile.serializer()).ifEmpty { fallback.profiles }
    val activeProfileId = root["activeProfileId"]?.jsonPrimitive?.contentOrNull ?: profiles.first().id
    val assistants = decodeListItems("assistants", AssistantAgent.serializer()).ifEmpty { fallback.assistants }
    val activeAssistantId = root["activeAssistantId"]?.jsonPrimitive?.contentOrNull ?: assistants.firstOrNull()?.id.orEmpty()
    val defaultModels = root["defaultModels"]?.let {
        runCatching { compactStateJson.decodeFromJsonElement(DefaultModelConfig.serializer(), it) }.getOrNull()
    } ?: fallback.defaultModels
    val preferences = root["preferences"]?.let {
        runCatching { compactStateJson.decodeFromJsonElement(PreferencesConfig.serializer(), it) }.getOrNull()
    } ?: fallback.preferences
    val desktopWindow = root["desktopWindow"]?.let {
        runCatching { compactStateJson.decodeFromJsonElement(DesktopWindowConfig.serializer(), it) }.getOrNull()
    } ?: fallback.desktopWindow
    val sessions = decodeListItems("sessions", ChatSession.serializer())
    val activeSessionId = root["activeSessionId"]?.jsonPrimitive?.contentOrNull ?: sessions.firstOrNull()?.id.orEmpty()
    val openTabs = (root["openTabSessionIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    val presets = decodeListItems("systemPromptPresets", SystemPromptPreset.serializer())
    val savedCards = decodeListItems("savedCards", CharacterOrScenarioCard.serializer())
    val globalWorldBook = decodeListItems("globalWorldBook", ContextFragment.serializer())
    val theme = root["theme"]?.let {
        runCatching { compactStateJson.decodeFromJsonElement(ThemeConfig.serializer(), it) }.getOrNull()
    } ?: fallback.theme
    val otaRepo = root["otaGithubRepo"]?.jsonPrimitive?.contentOrNull ?: fallback.otaGithubRepo
    val autoOta = root["autoCheckOtaOnStartup"]?.jsonPrimitive?.booleanOrNull ?: fallback.autoCheckOtaOnStartup

    return AppStateSnapshot(
        profiles = profiles,
        activeProfileId = activeProfileId,
        assistants = assistants,
        activeAssistantId = activeAssistantId,
        defaultModels = defaultModels,
        preferences = preferences,
        desktopWindow = desktopWindow,
        sessions = sessions,
        activeSessionId = activeSessionId,
        openTabSessionIds = openTabs,
        systemPromptPresets = presets,
        savedCards = savedCards,
        globalWorldBook = globalWorldBook,
        theme = theme,
        otaGithubRepo = otaRepo,
        autoCheckOtaOnStartup = autoOta
    )
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

    val defaultAssistant = AssistantAgent(
        id = "asst-default",
        name = "Default Assistant",
        iconEmoji = "💬",
        systemPrompt = ""
    )

    val initialSession = ChatSession(
        id = "sess-initial",
        title = "New Chat",
        createdAt = now,
        updatedAt = now,
        profileId = geminiProfile.id,
        assistantId = defaultAssistant.id
    )

    return AppStateSnapshot(
        profiles = listOf(geminiProfile, openAiProfile, anthropicProfile, byteDanceProfile),
        activeProfileId = geminiProfile.id,
        assistants = listOf(defaultAssistant),
        activeAssistantId = defaultAssistant.id,
        sessions = listOf(initialSession),
        activeSessionId = initialSession.id,
        openTabSessionIds = listOf(initialSession.id),
        theme = ThemeConfig()
    )
}
