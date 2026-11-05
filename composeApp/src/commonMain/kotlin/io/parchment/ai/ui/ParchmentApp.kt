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
import io.parchment.ai.model.WebSourceCitation
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
import io.parchment.ai.protocol.O200kTokenizer
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

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun ParchmentApp(
    desktopWindowHeader: (@Composable () -> Unit)? = null
) {
    val uiScope = rememberCoroutineScope()

    var appState by remember { mutableStateOf(loadOrCreateDefaultAppState()) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var activeSettingsCategory by remember { mutableStateOf(SettingsCategory.PREFERENCES) }
    var startMobileSettingsAtRoot by remember { mutableStateOf(true) }
    var showSidebar by remember { mutableStateOf(!PlatformBridge.isAndroid) }
    var sidebarTab by remember { mutableStateOf(SidebarTab.TOPICS) }
    var showSessionInspector by remember { mutableStateOf(false) }
    var showRightFilesSidebar by remember { mutableStateOf(false) }
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

    // Per-session composer inputs & staged attachments (synced per session so switching tabs never leaks or loses files!)
    var userPromptInput by remember(activeSession.id) { mutableStateOf(activeSession.draftInput) }
    var assistantPrefillInput by remember(activeSession.id) { mutableStateOf(activeSession.draftPrefill) }
    var showPrefillBox by remember { mutableStateOf(false) }
    var isGoalModeEnabled by remember { mutableStateOf(false) }
    var isLiveVoiceMode by remember { mutableStateOf(false) }
    var isVoiceListening by remember { mutableStateOf(false) }
    var pendingAttachmentsBySession by remember { mutableStateOf<Map<String, List<PromptAttachment>>>(emptyMap()) }
    val pendingAttachments = pendingAttachmentsBySession[activeSession.id].orEmpty()
    var pendingDeleteConfirmation by remember { mutableStateOf<DeleteConfirmRequest?>(null) }
    var pendingRegenerateConfirm by remember { mutableStateOf<RegenerateConfirmRequest?>(null) }
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
        val targetSessId = activeSession.id
        val existingList = pendingAttachmentsBySession[targetSessId].orEmpty()
        pendingAttachmentsBySession = pendingAttachmentsBySession + (targetSessId to (existingList + newAtts))
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
        val currentSnap = appState
        val targetId = currentSnap.sessions.find { it.id == currentSnap.activeSessionId }?.id
            ?: currentSnap.sessions.firstOrNull()?.id ?: return
        val newSessions = currentSnap.sessions.map {
            if (it.id == targetId) transform(it).copy(updatedAt = PlatformBridge.currentTimeMillis()) else it
        }
        persist(currentSnap.copy(sessions = newSessions), immediateFlush = immediate)
    }

    fun updateSessionById(sessionId: String, immediate: Boolean = false, transform: (ChatSession) -> ChatSession) {
        val currentSnap = appState
        val newSessions = currentSnap.sessions.map {
            if (it.id == sessionId) transform(it).copy(updatedAt = PlatformBridge.currentTimeMillis()) else it
        }
        persist(currentSnap.copy(sessions = newSessions), immediateFlush = immediate)
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
        val currentSnap = appState
        val targetProfId = currentSnap.profiles.find { it.id == currentSnap.activeProfileId }?.id
            ?: currentSnap.profiles.firstOrNull()?.id ?: return
        val newProfiles = currentSnap.profiles.map {
            if (it.id == targetProfId) transform(it) else it
        }
        persist(currentSnap.copy(profiles = newProfiles), immediateFlush = true)
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
    val currentEffectiveModel = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }.trim()
    LaunchedEffect(activeProfile.id, currentEffectiveModel) {
        if (currentEffectiveModel.isNotEmpty()) {
            val savedCustom = activeProfile.customModelSpecs[currentEffectiveModel]
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
                ModelsDevClient.fetchModelSpec(currentEffectiveModel).onSuccess { spec ->
                    updateProfileById(activeProfile.id) {
                        it.copy(
                            contextWindowTokens = spec.contextWindowTokens,
                            inputPricePerM = spec.inputPricePerM,
                            outputPricePerM = spec.outputPricePerM,
                            cacheReadPricePerM = spec.cacheReadPricePerM,
                            cacheWritePricePerM = spec.cacheWritePricePerM,
                            reasoningPricePerM = spec.reasoningPricePerM,
                            customModelSpecs = it.customModelSpecs + (currentEffectiveModel to spec)
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

            AiProviderEngine.generateUtilityText(resolvedProf, resolvedModel, prompt).fold(
                onSuccess = { genTitle ->
                    val cleaned = genTitle.lines().firstOrNull()?.trim()?.removeSurrounding("\"")?.take(60).orEmpty()
                    if (cleaned.isNotBlank()) {
                        updateSessionById(sessionId, immediate = true) { s ->
                            if (!forceRegenerate && s.isTitleManuallySet) s else s.copy(title = cleaned)
                        }
                    }
                },
                onFailure = { err ->
                    if (forceRegenerate) {
                        bannerNotification = "Title generation failed: ${err.message}"
                    }
                }
            )
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
        val initialTrailingThinking = existingTargetVariant?.trailingThinking.orEmpty()
        val initialSignature = existingTargetVariant?.thinkingSignature.orEmpty()
        val initialTools = existingTargetVariant?.toolCalls.orEmpty()
        val initialMedia = existingTargetVariant?.mediaItems.orEmpty()
        val initialWebSources = existingTargetVariant?.webSources.orEmpty()
        val initialDurationMs = existingTargetVariant?.thinkingDurationMs ?: 0L

        val newJob = appProcessScope.launch {
            val networkContentTarget = StringBuilder(cleanContinuePrefix)
            val networkThinkingTarget = StringBuilder(initialThinking)
            val currentStepThinkingTarget = StringBuilder(initialTrailingThinking)
            val signatureSb = StringBuilder(initialSignature)
            var displayedContentLen = cleanContinuePrefix.length
            var displayedThinkingLen = initialThinking.length
            var displayedStepThinkingLen = initialTrailingThinking.length
            val accumulatedTools = initialTools.toMutableList()
            var latestGoalTracker: GoalTrackerState? = existingTargetVariant?.goalTracker
            val accumulatedMedia = initialMedia.toMutableList()
            var accumulatedWebSources = initialWebSources
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
                val curStepThink = if (accumulatedTools.isEmpty()) "" else if (isFinal) currentStepThinkingTarget.toString() else currentStepThinkingTarget.substring(0, displayedStepThinkingLen.coerceAtMost(currentStepThinkingTarget.length))
                val curSig = signatureSb.toString()
                val curTools = accumulatedTools.toList()
                val curGoal = latestGoalTracker
                val curMedia = accumulatedMedia.toList()
                val curWebSources = accumulatedWebSources
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
                                                trailingThinking = curStepThink,
                                                thinkingSignature = curSig,
                                                thinkingDurationMs = thinkingDurationMs,
                                                toolCalls = curTools,
                                                goalTracker = curGoal ?: v.goalTracker,
                                                mediaItems = curMedia,
                                                webSources = curWebSources,
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
                    val frameScale = (dtMs / 8.0f).coerceIn(0.4f, 3.5f)
                    var changed = false
                    val targetThinkLen = networkThinkingTarget.length
                    if (displayedThinkingLen < targetThinkLen) {
                        val diff = targetThinkLen - displayedThinkingLen
                        val step = maxOf(1, ((maxOf(2, diff / 4)) * frameScale).toInt())
                        displayedThinkingLen = (displayedThinkingLen + step).coerceAtMost(targetThinkLen)
                        changed = true
                    }
                    val targetStepThinkLen = currentStepThinkingTarget.length
                    if (displayedStepThinkingLen < targetStepThinkLen) {
                        val diff = targetStepThinkLen - displayedStepThinkingLen
                        val step = maxOf(1, ((maxOf(2, diff / 4)) * frameScale).toInt())
                        displayedStepThinkingLen = (displayedStepThinkingLen + step).coerceAtMost(targetStepThinkLen)
                        changed = true
                    } else if (displayedStepThinkingLen > targetStepThinkLen) {
                        displayedStepThinkingLen = targetStepThinkLen
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
                        val visibleStepThink = if (accumulatedTools.isEmpty()) "" else currentStepThinkingTarget.substring(0, displayedStepThinkingLen.coerceAtMost(currentStepThinkingTarget.length))
                        val curSig = signatureSb.toString()
                        patchVariantMemory { v ->
                            v.copy(
                                content = visibleCont,
                                thinkingSummary = visibleThink,
                                trailingThinking = visibleStepThink,
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
                var finalSessionForCall = mergedSessionForApi
                var finalHistoryForCall = effectiveHistory

                // Layer 1: Proactive Pre-Send Auto-Compaction when active context reaches >= 85% of contextWindowTokens
                val effectiveCtxLimit = (effectiveProfileForApi.customModelSpecs[modelName]?.contextWindowTokens
                    ?: effectiveProfileForApi.contextWindowTokens)
                val activeUnmutedIndices = finalHistoryForCall.mapIndexedNotNull { idx, n -> if (n.includedInContext) idx else null }
                val lastAssistantMetrics = activeUnmutedIndices
                    .map { finalHistoryForCall[it] }
                    .lastOrNull { it.role == MessageRole.ASSISTANT }
                    ?.activeVariant?.tokenMetrics
                val approxTotalChars = finalSessionForCall.systemPrompt.length +
                    finalSessionForCall.storySummary.length +
                    finalSessionForCall.authorsNote.length +
                    (finalSessionForCall.activeCard?.buildCompiledInstructions(finalSessionForCall.userName)?.length ?: 0) +
                    finalSessionForCall.fragments.filter { it.mode != FragmentActivationMode.MUTED }.sumOf { it.content.length } +
                    activeUnmutedIndices.sumOf { finalHistoryForCall[it].activeVariant.content.length }
                val estimatedContextTokens = maxOf(
                    approxTotalChars / 4,
                    (lastAssistantMetrics?.inputTokens ?: 0) + (lastAssistantMetrics?.outputTokens ?: 0)
                )
                if (appState.preferences.autoCompactWhenNearContextLimit &&
                    effectiveCtxLimit > 0 &&
                    activeUnmutedIndices.size > 6 &&
                    estimatedContextTokens >= (effectiveCtxLimit * 85) / 100
                ) {
                    val usagePct = ((estimatedContextTokens.toLong() * 100L) / effectiveCtxLimit.toLong()).coerceAtMost(999L)
                    updateSessionStreamState(sessionId) { cur ->
                        cur?.copy(statusText = "Context at $usagePct% — auto-compacting older turns into continuity memory…")
                    }
                    val indicesToMute = activeUnmutedIndices.dropLast(6).toSet()
                    val turnsToSummarize = indicesToMute.map { finalHistoryForCall[it] }
                    val dm = appState.defaultModels
                    val sumProf = if (!dm.summaryFollowGlobal && dm.summaryProfileId.isNotBlank()) {
                        appState.profiles.find { it.id == dm.summaryProfileId } ?: effectiveProfileForApi
                    } else effectiveProfileForApi
                    val sumModel = if (!dm.summaryFollowGlobal && dm.summaryModelId.isNotBlank()) {
                        dm.summaryModelId
                    } else modelName
                    val transcriptSnippet = buildString {
                        if (finalSessionForCall.storySummary.isNotBlank()) {
                            appendLine("Existing Continuity Summary:")
                            appendLine(finalSessionForCall.storySummary.trim())
                            appendLine()
                        }
                        appendLine("Older Conversation Turns to Fold into Continuity Summary:")
                        for (turn in turnsToSummarize) {
                            val roleLabel = if (turn.role == MessageRole.ASSISTANT) "Assistant" else "User"
                            val txt = AiProviderEngine.stripInlineErrorNotices(turn.activeVariant.content).trim().take(800)
                            if (txt.isNotEmpty()) appendLine("$roleLabel: $txt")
                        }
                    }
                    val sumPrompt = "Update the concise bulleted continuity summary with all key events, facts, decisions, and character/project states from the turns below:\n\n$transcriptSnippet"
                    val generatedSummary = withTimeoutOrNull(25_000L) {
                        AiProviderEngine.generateUtilityText(sumProf, sumModel, sumPrompt).getOrNull()
                    }?.takeIf { it.isNotBlank() } ?: buildString {
                        if (finalSessionForCall.storySummary.isNotBlank()) {
                            appendLine(finalSessionForCall.storySummary.trim())
                        }
                        for (turn in turnsToSummarize.takeLast(12)) {
                            val roleLabel = if (turn.role == MessageRole.ASSISTANT) "AI" else "User"
                            val txt = AiProviderEngine.stripInlineErrorNotices(turn.activeVariant.content)
                                .replace(Regex("\\s+"), " ").trim().take(200)
                            if (txt.isNotEmpty()) appendLine("• $roleLabel: $txt")
                        }
                    }.trim()

                    val mutedIdSet = indicesToMute.map { finalHistoryForCall[it].id }.toSet()
                    finalSessionForCall = finalSessionForCall.copy(storySummary = generatedSummary)
                    finalHistoryForCall = finalHistoryForCall.map { n ->
                        if (n.id in mutedIdSet) n.copy(includedInContext = false) else n
                    }
                    updateSessionById(sessionId, immediate = true) { sess ->
                        sess.copy(
                            storySummary = generatedSummary,
                            messages = sess.messages.map { n ->
                                if (n.id in mutedIdSet) n.copy(includedInContext = false) else n
                            }
                        )
                    }
                }

                AiProviderEngine.streamGeneration(
                    profile = effectiveProfileForApi,
                    session = finalSessionForCall,
                    historyNodes = finalHistoryForCall,
                    continueFromAssistantPrefix = cleanContinuePrefix,
                    initialThinking = initialThinking,
                    initialSignature = initialSignature,
                    initialToolCalls = initialTools,
                    initialMediaItems = initialMedia,
                    initialWebSources = initialWebSources
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
                            currentStepThinkingTarget.append(update.delta)
                            signatureSb.append(update.signatureDelta)
                            val thinkWords = countWordsFast(networkThinkingTarget)
                            updateSessionStreamState(sessionId) { cur ->
                                cur?.copy(
                                    isThinkingPhase = true,
                                    statusText = "Reasoning ($modelName • $thinkWords words • ${formatDurationFromMs(thinkingDurationMs)})…"
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
                            val stepThinkFallback = currentStepThinkingTarget.toString().trim()
                            currentStepThinkingTarget.clear()
                            displayedStepThinkingLen = 0
                            val enrichedTool = if (update.toolCall.precedingThinking.isBlank() && stepThinkFallback.isNotEmpty()) {
                                update.toolCall.copy(precedingThinking = stepThinkFallback)
                            } else {
                                update.toolCall
                            }
                            accumulatedTools.add(enrichedTool)
                            val toolsSnap = accumulatedTools.toList()
                            patchVariantMemory { v ->
                                v.copy(
                                    toolCalls = toolsSnap,
                                    trailingThinking = ""
                                )
                            }
                            updateSessionStreamState(sessionId) { cur ->
                                cur?.copy(statusText = "Executed tool: ${enrichedTool.name}")
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
                        is StreamUpdate.WebSourcesUpdated -> {
                            accumulatedWebSources = update.sources
                            patchVariantMemory { v -> v.copy(webSources = update.sources) }
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
                                    trailingThinking = update.trailingThinking,
                                    thinkingSignature = update.thinkingSignature,
                                    thinkingDurationMs = thinkingDurationMs,
                                    toolCalls = update.toolCalls,
                                    goalTracker = update.goalTracker ?: latestGoalTracker,
                                    mediaItems = update.mediaItems,
                                    webSources = update.webSources.ifEmpty { accumulatedWebSources },
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
                                    trailingThinking = if (accumulatedTools.isNotEmpty()) currentStepThinkingTarget.toString().trim() else "",
                                    thinkingSignature = signatureSb.toString(),
                                    thinkingDurationMs = thinkingDurationMs,
                                    toolCalls = accumulatedTools.toList(),
                                    goalTracker = latestGoalTracker ?: v.goalTracker,
                                    mediaItems = accumulatedMedia.toList(),
                                    webSources = accumulatedWebSources,
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
                        trailingThinking = if (accumulatedTools.isNotEmpty()) currentStepThinkingTarget.toString().trim() else "",
                        thinkingSignature = signatureSb.toString(),
                        thinkingDurationMs = thinkingDurationMs,
                        toolCalls = accumulatedTools.toList(),
                        goalTracker = latestGoalTracker ?: v.goalTracker,
                        mediaItems = accumulatedMedia.toList(),
                        webSources = accumulatedWebSources,
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

    fun clearCurrentChatMessages(targetSessionId: String = activeSession.id) {
        val curStream = activeStreamsBySession[targetSessionId]
        curStream?.job?.cancel()
        if (curStream != null && curStream.interactionId.isNotBlank() && activeProfile.useBackgroundLongDecode) {
            appProcessScope.launch {
                AiProviderEngine.cancelBackgroundInteraction(activeProfile, curStream.interactionId)
            }
        }
        updateSessionStreamState(targetSessionId) { null }
        queuedPromptsBySession = queuedPromptsBySession - targetSessionId
        pendingAttachmentsBySession = pendingAttachmentsBySession - targetSessionId
        updateSessionById(targetSessionId, immediate = true) { it.copy(messages = emptyList()) }
    }

    fun continueAssistantNodeAt(nodeIndex: Int) {
        val targetNode = activeSession.messages.getOrNull(nodeIndex) ?: return
        if (targetNode.role != MessageRole.ASSISTANT) return
        val historyBefore = activeSession.messages.subList(0, nodeIndex)
        val existingText = AiProviderEngine.stripInlineErrorNotices(targetNode.activeVariant.content)
        // Immediately strip any inline error banner in UI state so the user sees clean resumption at 0ms!
        updateActiveSession(immediate = true) { sess ->
            sess.copy(
                messages = sess.messages.mapIndexed { idx, n ->
                    if (idx != nodeIndex) n
                    else n.copy(
                        variants = n.variants.mapIndexed { vIdx, v ->
                            if (vIdx == n.activeVariantIndex) v.copy(content = existingText, isStreaming = true) else v
                        }
                    )
                }
            )
        }
        triggerGeneration(
            sessionId = activeSession.id,
            historyForCall = historyBefore,
            targetAssistantNodeId = targetNode.id,
            targetVariantId = targetNode.activeVariant.id,
            continuePrefix = existingText
        )
    }

    fun sendUserTurn() {
        val rawInput = userPromptInput.trim()
        val prefill = assistantPrefillInput.trim()
        val sessId = activeSession.id
        var currentAtts = pendingAttachmentsBySession[sessId].orEmpty()
        if (rawInput.isEmpty() && prefill.isEmpty() && currentAtts.isEmpty()) return

        // Handle `/compact` slash command (Summarizes into Memory + Mutes older turns, keeping last 4 active)
        if (rawInput.equals("/compact", ignoreCase = true) && currentAtts.isEmpty()) {
            userPromptInput = ""
            updateActiveSession { it.copy(draftInput = "") }
            triggerAutoSummarizeStory(autoMuteOlderTurns = true)
            return
        }

        // Handle `/summarize` slash command (Updates Rolling Story / Continuity Summary without muting turns)
        if (rawInput.equals("/summarize", ignoreCase = true) && currentAtts.isEmpty()) {
            userPromptInput = ""
            updateActiveSession { it.copy(draftInput = "") }
            triggerAutoSummarizeStory(autoMuteOlderTurns = false)
            return
        }

        // Handle `/continue` slash command (Continues the last Assistant turn seamlessly)
        if (rawInput.equals("/continue", ignoreCase = true) && currentAtts.isEmpty()) {
            val lastAsstIdx = activeSession.messages.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (lastAsstIdx >= 0) {
                userPromptInput = ""
                updateActiveSession { it.copy(draftInput = "") }
                continueAssistantNodeAt(lastAsstIdx)
                return
            }
        }

        // Handle `/clear` slash command (Clears all messages in current chat)
        if (rawInput.equals("/clear", ignoreCase = true) && currentAtts.isEmpty()) {
            userPromptInput = ""
            updateActiveSession { it.copy(draftInput = "") }
            clearCurrentChatMessages(sessId)
            bannerNotification = "Cleared messages in '${activeSession.title}'."
            return
        }

        // Handle `/rewind` slash command (Removes the last turn pair in-place)
        if (rawInput.equals("/rewind", ignoreCase = true) && currentAtts.isEmpty()) {
            userPromptInput = ""
            if (activeSession.messages.isNotEmpty()) {
                activeStreamsBySession[sessId]?.job?.cancel()
                updateSessionStreamState(sessId) { null }
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
        pendingAttachmentsBySession = pendingAttachmentsBySession - sessId

        dispatchPromptItem(
            sessionId = sessId,
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
        val sessId = activeSession.id
        val atts = pendingAttachmentsBySession[sessId].orEmpty()
        if (direction.isEmpty() && prefill.isEmpty() && atts.isEmpty()) return
        userPromptInput = ""
        assistantPrefillInput = ""
        pendingAttachmentsBySession = pendingAttachmentsBySession - sessId
        dispatchPromptItem(
            sessionId = sessId,
            text = direction,
            prefill = prefill,
            attachments = atts,
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
        val atts = pendingAttachmentsBySession[targetSessId].orEmpty()
        if (steerText.isEmpty() && prefill.isEmpty() && atts.isEmpty()) return

        userPromptInput = ""
        assistantPrefillInput = ""
        pendingAttachmentsBySession = pendingAttachmentsBySession - targetSessId
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
        val atts = pendingAttachmentsBySession[targetSessId].orEmpty()
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
        pendingAttachmentsBySession = pendingAttachmentsBySession - targetSessId
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

    /**
     * Unified Regenerate Dispatcher supporting BOTH:
     * 1. User turns (`targetNode.role == MessageRole.USER`): deletes all Assistant / subsequent turns below
     *    the User message, appends a fresh Assistant turn right below it, and streams a new response.
     * 2. Assistant turns (`targetNode.role == MessageRole.ASSISTANT`): honors `deleteMessagesBelowWhenRegenerating`
     *    (truncating subsequent turns) and `deletePreviousMessageWhenRegenerating` (replacing ONLY the active
     *    swipe variant in-place instead of wiping out other swipe versions).
     */
    fun executeRegenerateFromNode(nodeIndex: Int, sessionId: String = activeSession.id) {
        val curSnap = appState
        val sess = curSnap.sessions.find { it.id == sessionId }
            ?: curSnap.sessions.find { it.id == curSnap.activeSessionId }
            ?: curSnap.sessions.firstOrNull() ?: return
        val targetNode = sess.messages.getOrNull(nodeIndex) ?: return

        // Cancel any active stream on this session before mutating the message list
        activeStreamsBySession[sess.id]?.job?.cancel()
        updateSessionStreamState(sess.id) { null }
        val now = PlatformBridge.currentTimeMillis()
        val curProf = curSnap.profiles.find { it.id == sess.profileId } ?: curSnap.profiles.first()
        val modelUsed = sess.modelOverride.ifBlank { curProf.defaultModel }

        if (targetNode.role == MessageRole.USER) {
            // Regenerating under a User message ALWAYS deletes all Assistant/subsequent messages below it
            val historyUpToUser = sess.messages.subList(0, nodeIndex + 1)
            val newAsstNodeId = "node-$now-regen-a"
            val newVarId = "var-$now-regen-a"
            val newAsstNode = MessageNode(
                id = newAsstNodeId,
                role = MessageRole.ASSISTANT,
                variants = listOf(
                    MessageVariant(
                        id = newVarId,
                        content = "",
                        modelUsed = modelUsed,
                        providerProtocol = curProf.protocol,
                        isStreaming = true,
                        createdAt = now
                    )
                )
            )
            val updatedMessages = historyUpToUser + newAsstNode
            updateSessionById(sess.id, immediate = true) { it.copy(messages = updatedMessages) }
            triggerGeneration(
                sessionId = sess.id,
                historyForCall = historyUpToUser,
                targetAssistantNodeId = newAsstNodeId,
                targetVariantId = newVarId
            )
        } else {
            val historyBefore = sess.messages.subList(0, nodeIndex)
            val newVarId = "var-$now-regen"
            val newVar = MessageVariant(
                id = newVarId,
                content = "",
                modelUsed = modelUsed,
                providerProtocol = curProf.protocol,
                isStreaming = true,
                createdAt = now
            )
            val prefs = curSnap.preferences
            val activeVarIdx = targetNode.activeVariantIndex.coerceIn(0, (targetNode.variants.size - 1).coerceAtLeast(0))
            val newVariants = if (prefs.deletePreviousMessageWhenRegenerating && targetNode.variants.isNotEmpty()) {
                // Replace ONLY the currently active swipe variant in-place, preserving all other swipe versions!
                targetNode.variants.mapIndexed { vIdx, v -> if (vIdx == activeVarIdx) newVar else v }
            } else {
                targetNode.variants + newVar
            }
            val newIdx = if (prefs.deletePreviousMessageWhenRegenerating && targetNode.variants.isNotEmpty()) {
                activeVarIdx
            } else {
                newVariants.lastIndex
            }
            val baseList = if (prefs.deleteMessagesBelowWhenRegenerating) {
                sess.messages.subList(0, nodeIndex + 1)
            } else {
                sess.messages
            }
            updateSessionById(sess.id, immediate = true) { s ->
                val sliced = if (prefs.deleteMessagesBelowWhenRegenerating) {
                    s.messages.take(nodeIndex + 1)
                } else {
                    s.messages
                }
                s.copy(
                    messages = sliced.mapIndexed { idx, n ->
                        if (idx == nodeIndex) n.copy(variants = newVariants, activeVariantIndex = newIdx) else n
                    }
                )
            }
            triggerGeneration(
                sessionId = sess.id,
                historyForCall = historyBefore,
                targetAssistantNodeId = targetNode.id,
                targetVariantId = newVarId
            )
        }
    }

    /**
     * Translates a turn into `defaultModels.translationTargetLanguage` using the configured Translation Model
     * and adds the translated passage as a new swipeable version branch on that turn.
     */
    fun triggerTranslateNode(nodeIndex: Int) {
        val curSnap = appState
        val sess = curSnap.sessions.find { it.id == curSnap.activeSessionId }
            ?: curSnap.sessions.firstOrNull() ?: return
        val targetNode = sess.messages.getOrNull(nodeIndex) ?: return
        val sourceText = AiProviderEngine.stripInlineErrorNotices(targetNode.activeVariant.content).trim()
        if (sourceText.isEmpty()) return

        val dm = curSnap.defaultModels
        val targetLang = dm.translationTargetLanguage.ifBlank { "English" }.trim()
        val transProf = if (!dm.translationFollowGlobal && dm.translationProfileId.isNotBlank()) {
            curSnap.profiles.find { it.id == dm.translationProfileId } ?: activeProfile
        } else {
            curSnap.profiles.find { it.id == sess.profileId } ?: activeProfile
        }
        val transModel = if (!dm.translationFollowGlobal && dm.translationModelId.isNotBlank()) {
            dm.translationModelId
        } else {
            sess.modelOverride.ifBlank { transProf.defaultModel }.ifBlank { dm.globalModelId }
        }
        if (transModel.isBlank()) {
            bannerNotification = "Select a model first to translate messages."
            return
        }
        bannerNotification = "Translating turn #${nodeIndex + 1} to $targetLang ($transModel)…"
        appProcessScope.launch {
            val prompt = "Translate the following passage accurately and naturally into $targetLang. Preserve all Markdown formatting, code blocks, and tone. Return ONLY the translated text without introductory commentary:\n\n$sourceText"
            AiProviderEngine.generateUtilityText(transProf, transModel, prompt).fold(
                onSuccess = { translated ->
                    if (translated.isNotBlank()) {
                        val now = PlatformBridge.currentTimeMillis()
                        updateSessionById(sess.id, immediate = true) { s ->
                            s.copy(
                                messages = s.messages.mapIndexed { idx, n ->
                                    if (idx != nodeIndex) n
                                    else {
                                        val newVar = n.activeVariant.copy(
                                            id = "var-trans-$now",
                                            content = translated,
                                            modelUsed = "${n.activeVariant.modelUsed.ifBlank { transModel }} ($targetLang)",
                                            providerInteractionId = "",
                                            createdAt = now
                                        )
                                        val updatedVars = n.variants + newVar
                                        n.copy(variants = updatedVars, activeVariantIndex = updatedVars.lastIndex)
                                    }
                                }
                            )
                        }
                        bannerNotification = "Translated turn #${nodeIndex + 1} to $targetLang (added as swipe version)."
                    }
                },
                onFailure = { err ->
                    bannerNotification = "Translation failed: ${err.message}"
                }
            )
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
        // If an untouched empty 'New Chat' tab for this exact assistant is already open, switch to it instead of piling up duplicate blank tabs!
        val existingEmptyOpenTab = appState.sessions.find { s ->
            s.id in openTabIds &&
                s.assistantId == assistant.id &&
                s.messages.isEmpty() &&
                s.title == "New Chat" &&
                s.draftInput.isBlank()
        }
        if (existingEmptyOpenTab != null && existingEmptyOpenTab.id != activeSession.id) {
            persist(
                appState.copy(
                    activeAssistantId = assistant.id,
                    activeSessionId = existingEmptyOpenTab.id
                ),
                immediateFlush = true
            )
            isSettingsOpen = false
            showMobileTabSwitcher = false
            return
        }

        val newSess = buildFreshSessionForAssistant(
            appState = appState,
            activeProfile = activeProfile,
            assistant = assistant,
            userName = activeSession.userName
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

    fun closeEmptyNewChatTabs() {
        val emptyTabIds = openTabIds.filter { tabId ->
            val s = appState.sessions.find { it.id == tabId }
            s != null && s.messages.isEmpty() && s.title == "New Chat" && s.draftInput.isBlank() && !activeStreamsBySession.containsKey(tabId)
        }
        if (emptyTabIds.size <= 1 && openTabIds.size == emptyTabIds.size) return
        val tabsToKeep = openTabIds.filterNot { it in emptyTabIds }.ifEmpty { listOf(emptyTabIds.first()) }
        val keptSet = tabsToKeep.toSet()
        val emptyDropSet = emptyTabIds.filterNot { it in keptSet }.toSet()
        val nextSessions = appState.sessions.filterNot { it.id in emptyDropSet }
        val nextActiveId = if (activeSession.id in tabsToKeep) activeSession.id else tabsToKeep.first()
        val nextSess = nextSessions.find { it.id == nextActiveId }
        persist(
            appState.copy(
                sessions = nextSessions,
                activeSessionId = nextActiveId,
                activeAssistantId = nextSess?.assistantId?.ifBlank { appState.activeAssistantId } ?: appState.activeAssistantId,
                openTabSessionIds = tabsToKeep
            ),
            immediateFlush = true
        )
    }

    LaunchedEffect(Unit) {
        if (appState.preferences.newChatOnLaunch && activeSession.messages.isNotEmpty()) {
            createNewTopicForAssistant(activeAssistant, openInNewTab = true)
        }
    }

    fun executeDeleteSingleSession(targetId: String) {
        activeStreamsBySession[targetId]?.job?.cancel()
        updateSessionStreamState(targetId) { null }
        queuedPromptsBySession = queuedPromptsBySession - targetId
        pendingAttachmentsBySession = pendingAttachmentsBySession - targetId
        val remaining = appState.sessions.filterNot { it.id == targetId }
        val remainingTabs = openTabIds.filterNot { it == targetId }
        val remainingForAssistant = remaining.filter {
            it.assistantId == activeAssistant.id || appState.assistants.none { a -> a.id == it.assistantId }
        }
        val shouldCreateFresh = remainingForAssistant.isEmpty() ||
            (appState.preferences.newChatAfterDeletingTopic && appState.activeSessionId == targetId && remainingTabs.isEmpty())
        if (!shouldCreateFresh && remainingForAssistant.isNotEmpty()) {
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
            val fresh = buildFreshSessionForAssistant(
                appState = appState,
                activeProfile = activeProfile,
                assistant = activeAssistant,
                userName = activeSession.userName
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
        val removedSessions = appState.sessions.filter {
            it.assistantId == activeAssistant.id || appState.assistants.none { a -> a.id == it.assistantId }
        }
        val removedIds = removedSessions.map { it.id }.toSet()
        removedIds.forEach { id ->
            activeStreamsBySession[id]?.job?.cancel()
        }
        activeStreamsBySession = activeStreamsBySession - removedIds
        queuedPromptsBySession = queuedPromptsBySession - removedIds
        pendingAttachmentsBySession = pendingAttachmentsBySession - removedIds

        val otherSessions = appState.sessions.filterNot { it.id in removedIds }
        val otherIds = otherSessions.map { it.id }.toSet()
        val fresh = buildFreshSessionForAssistant(
            appState = appState,
            activeProfile = activeProfile,
            assistant = activeAssistant,
            userName = activeSession.userName
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
                    if (desktopWindowHeader != null) {
                        desktopWindowHeader()
                        SepiaDivider()
                    }

                    // 1. Top Editorial Header Bar (Hidden whenever Settings is open on both Desktop & Mobile so there is ONLY ONE `← Chat` button!)
                    if (!isSettingsOpen) {
                        val bgStreamingCount = activeStreamsBySession.keys.count { it != activeSession.id }
                        val chatVaultCount = remember(activeSession.id, activeSession.messages, pendingAttachments) {
                            val (aiItems, uploads, sources) = extractSessionVaultItems(activeSession, pendingAttachments)
                            aiItems.size + uploads.size + sources.size
                        }
                        TopEditorialBar(
                            appState = appState,
                            activeSession = activeSession,
                            activeAssistant = activeAssistant,
                            activeProfile = activeProfile,
                            isCompactScreen = isCompactScreen,
                            showSidebar = showSidebar,
                            showSessionInspector = showSessionInspector,
                            showRightFilesSidebar = showRightFilesSidebar,
                            chatArtifactsCount = chatVaultCount,
                            showQuickModelPopover = showQuickModelPopover,
                            openTabCount = openTabIds.size,
                            backgroundStreamingCount = bgStreamingCount,
                            onOpenMobileTabSwitcher = { showMobileTabSwitcher = true },
                            onNewChatTab = { createNewTopicForAssistant(activeAssistant, openInNewTab = true) },
                            onToggleSidebar = { showSidebar = !showSidebar },
                            onToggleSessionInspector = { showSessionInspector = !showSessionInspector },
                            onToggleRightFilesSidebar = { showRightFilesSidebar = !showRightFilesSidebar },
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
                                if (cat != null) {
                                    activeSettingsCategory = cat
                                    startMobileSettingsAtRoot = false
                                } else {
                                    startMobileSettingsAtRoot = true
                                }
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
                                onNewTab = { createNewTopicForAssistant(activeAssistant, openInNewTab = true) },
                                onOpenTabOverview = { showMobileTabSwitcher = true }
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

                    LaunchedEffect(bannerNotification, isVoiceListening) {
                        if (bannerNotification.isNotBlank() && !isVoiceListening) {
                            val snap = bannerNotification
                            delay(5000L)
                            if (bannerNotification == snap && !isVoiceListening) {
                                bannerNotification = ""
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = bannerNotification.isNotBlank(),
                        enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(140)),
                        exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(140))
                    ) {
                        Column {
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
                                    text = "×",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { bannerNotification = "" }
                                )
                            }
                            SepiaDivider()
                        }
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
                                    val openTabForAsst = assistantSessions.firstOrNull { it.id in openTabIds }
                                    val nextSess = openTabForAsst ?: assistantSessions.maxByOrNull { it.updatedAt } ?: assistantSessions.first()
                                    val updatedTabs = if (nextSess.id in openTabIds) openTabIds else (openTabIds + nextSess.id)
                                    persist(
                                        appState.copy(
                                            activeAssistantId = asst.id,
                                            activeSessionId = nextSess.id,
                                            openTabSessionIds = updatedTabs
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
                                startMobileSettingsAtRoot = false
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
                                startMobileSettingsAtRoot = false
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
                                startMobileSettingsAtRoot = true
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
                                    startAtMobileRoot = startMobileSettingsAtRoot,
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
                                            bannerNotification = "Deleted provider profile."
                                        } else {
                                            bannerNotification = "At least one provider profile is required."
                                        }
                                    },
                                    onUpdateAssistants = { updatedList, activeId ->
                                        val safeList = updatedList.ifEmpty {
                                            listOf(AssistantAgent(id = "asst-default", name = "Default Assistant", iconEmoji = "💬"))
                                        }
                                        val safeActive = safeList.find { it.id == activeId }?.id ?: safeList.first().id
                                        persist(appState.copy(assistants = safeList, activeAssistantId = safeActive), immediateFlush = true)
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
                                        activeStreamsBySession.values.forEach { it.job?.cancel() }
                                        activeStreamsBySession = emptyMap()
                                        queuedPromptsBySession = emptyMap()
                                        pendingAttachmentsBySession = emptyMap()
                                        persist(sanitizeLoadedSnapshot(restored), immediateFlush = true)
                                        bannerNotification = "Restored backup."
                                    },
                                    onWipeAllData = {
                                        activeStreamsBySession.values.forEach { it.job?.cancel() }
                                        activeStreamsBySession = emptyMap()
                                        queuedPromptsBySession = emptyMap()
                                        pendingAttachmentsBySession = emptyMap()
                                        PlatformBridge.stopSpeakingAloud()
                                        PlatformBridge.stopVoiceInput()
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
                                    onAttachWorkspaceFile = { relPath ->
                                        val wsRoot = activeSession.workspacePath.ifBlank { activeAssistant.workspacePath }
                                        if (wsRoot.isNotBlank()) {
                                            PlatformBridge.workspaceRead(wsRoot, relPath, 1, 1500).onSuccess { rawRead ->
                                                val cleanLines = rawRead.lines().drop(1).joinToString("\n") {
                                                    it.replaceFirst(Regex("^\\d+:\\s?"), "")
                                                }
                                                val curList = pendingAttachmentsBySession[activeSession.id].orEmpty()
                                                if (curList.none { it.fileName == relPath }) {
                                                    val att = PromptAttachment(
                                                        id = "att-ws-${PlatformBridge.currentTimeMillis()}",
                                                        fileName = relPath,
                                                        mimeType = "text/plain",
                                                        textContent = cleanLines
                                                    )
                                                    pendingAttachmentsBySession = pendingAttachmentsBySession +
                                                        (activeSession.id to (curList + att))
                                                }
                                            }
                                        }
                                    },
                                    onReattachExistingAttachment = { existingAtt ->
                                        val curList = pendingAttachmentsBySession[activeSession.id].orEmpty()
                                        if (curList.none { it.fileName == existingAtt.fileName }) {
                                            pendingAttachmentsBySession = pendingAttachmentsBySession +
                                                (activeSession.id to (curList + existingAtt.copy(id = "att-re-${PlatformBridge.currentTimeMillis()}")))
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
                                            clearCurrentChatMessages(activeSession.id)
                                        }
                                    },
                                    onRemoveAttachment = { attId ->
                                        val curAtts = pendingAttachmentsBySession[activeSession.id].orEmpty()
                                        pendingAttachmentsBySession = pendingAttachmentsBySession +
                                            (activeSession.id to curAtts.filterNot { it.id == attId })
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
                                                    val v = n.activeVariant
                                                    val roleHeader = if (n.role == MessageRole.ASSISTANT) {
                                                        activeSession.activeCard?.name?.ifBlank { v.modelUsed.ifBlank { activeAssistant.name } } ?: activeAssistant.name
                                                    } else {
                                                        activeSession.userName.ifBlank { "User" }
                                                    }
                                                    val muteTag = if (!n.includedInContext) " *(Muted)*" else ""
                                                    appendLine("### $roleHeader$muteTag")
                                                    if (v.thinkingSummary.isNotBlank()) {
                                                        appendLine("<details><summary>Reasoning</summary>")
                                                        appendLine()
                                                        appendLine(v.thinkingSummary)
                                                        appendLine("</details>")
                                                        appendLine()
                                                    }
                                                    if (v.toolCalls.isNotEmpty()) {
                                                        appendLine("<details><summary>Tools Executed (${v.toolCalls.size})</summary>")
                                                        appendLine()
                                                        for (tc in v.toolCalls) {
                                                            appendLine("- **`${tc.name}`**: `${tc.argumentsJson}`")
                                                            if (tc.resultText.isNotBlank()) {
                                                                appendLine("  ```")
                                                                appendLine("  ${tc.resultText.take(1200)}")
                                                                appendLine("  ```")
                                                            }
                                                        }
                                                        appendLine("</details>")
                                                        appendLine()
                                                    }
                                                    if (v.content.isNotBlank()) {
                                                        appendLine(v.content)
                                                        appendLine()
                                                    }
                                                    if (v.attachments.isNotEmpty()) {
                                                        appendLine("*Attachments: ${v.attachments.joinToString { it.fileName }}*")
                                                        appendLine()
                                                    }
                                                    if (v.mediaItems.isNotEmpty()) {
                                                        appendLine("*Generated Media: ${v.mediaItems.joinToString { it.localFilePath.ifBlank { it.remoteUrl } }}*")
                                                        appendLine()
                                                    }
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
                                        startMobileSettingsAtRoot = false
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
                                        val sessId = activeSession.id
                                        activeStreamsBySession[sessId]?.job?.cancel()
                                        updateSessionStreamState(sessId) { null }
                                        val total = activeSession.messages.size
                                        if (total > 0) {
                                            if (nodeIndex < total - 1) {
                                                val keepCount = (nodeIndex + 1).coerceIn(1, total)
                                                val dropped = total - keepCount
                                                updateActiveSession(immediate = true) { sess ->
                                                    sess.copy(messages = sess.messages.take(keepCount))
                                                }
                                                bannerNotification = "Rewound chat to turn #${nodeIndex + 1} (removed $dropped later turn(s))."
                                            } else {
                                                // Clicked `↺ Rewind` on the final message: rewind that turn (or turn pair) so it's never a no-op!
                                                val dropCount = if (total >= 2 && activeSession.messages.last().role == MessageRole.ASSISTANT) 2 else 1
                                                updateActiveSession(immediate = true) { sess ->
                                                    sess.copy(messages = sess.messages.dropLast(dropCount))
                                                }
                                                bannerNotification = "Rewound last $dropCount turn(s)."
                                            }
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
                                        if (appState.preferences.confirmBeforeRegenerating) {
                                            pendingRegenerateConfirm = RegenerateConfirmRequest(activeSession.id, nodeIndex)
                                        } else {
                                            executeRegenerateFromNode(nodeIndex, activeSession.id)
                                        }
                                    },
                                    onTranslateNode = { nodeIndex ->
                                        triggerTranslateNode(nodeIndex)
                                    },
                                    onContinueAssistantNode = ::continueAssistantNodeAt
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = !isCompactScreen && showRightFilesSidebar && !isSettingsOpen,
                            enter = expandHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(150)),
                            exit = shrinkHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(120))
                        ) {
                            Row(modifier = Modifier.fillMaxHeight()) {
                                Box(
                                    modifier = Modifier
                                        .width(0.85.dp)
                                        .fillMaxHeight()
                                        .background(p.border.copy(alpha = 0.7f))
                                )
                                ParchmentRightFilesSidebar(
                                    activeSession = activeSession,
                                    pendingAttachments = pendingAttachments,
                                    onInspectAttachment = ::openAttachmentInViewer,
                                    onInspectGeneratedImage = { media ->
                                        activeViewerItem = ActiveMediaViewerItem.ImageViewer(
                                            title = media.revisedPrompt.take(48).ifBlank { "Generated Image" },
                                            mimeType = media.mimeType.ifBlank { "image/png" },
                                            localFilePath = media.localFilePath,
                                            remoteUrl = media.remoteUrl
                                        )
                                    },
                                    onReattachAttachment = { existingAtt ->
                                        val curList = pendingAttachmentsBySession[activeSession.id].orEmpty()
                                        if (curList.none { it.fileName == existingAtt.fileName }) {
                                            pendingAttachmentsBySession = pendingAttachmentsBySession +
                                                (activeSession.id to (curList + existingAtt.copy(id = "att-re-${PlatformBridge.currentTimeMillis()}")))
                                            bannerNotification = "Re-attached '${existingAtt.fileName}' to prompt."
                                        }
                                    },
                                    onClose = { showRightFilesSidebar = false },
                                    modifier = Modifier.width(300.dp).fillMaxHeight()
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

                            // Mobile 86%-Width Right Slide-Over Files & Sources Drawer
                            Row(modifier = Modifier.fillMaxSize()) {
                                AnimatedVisibility(
                                    visible = showRightFilesSidebar && !isSettingsOpen,
                                    enter = fadeIn(tween(150)),
                                    exit = fadeOut(tween(130))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.34f))
                                            .clickable { showRightFilesSidebar = false }
                                    )
                                }
                            }

                            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.End) {
                                AnimatedVisibility(
                                    visible = showRightFilesSidebar && !isSettingsOpen,
                                    enter = expandHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(140)),
                                    exit = shrinkHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(120))
                                ) {
                                    Row(modifier = Modifier.fillMaxHeight()) {
                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .fillMaxHeight()
                                                .background(p.border)
                                        )
                                        ParchmentRightFilesSidebar(
                                            activeSession = activeSession,
                                            pendingAttachments = pendingAttachments,
                                            onInspectAttachment = ::openAttachmentInViewer,
                                            onInspectGeneratedImage = { media ->
                                                activeViewerItem = ActiveMediaViewerItem.ImageViewer(
                                                    title = media.revisedPrompt.take(48).ifBlank { "Generated Image" },
                                                    mimeType = media.mimeType.ifBlank { "image/png" },
                                                    localFilePath = media.localFilePath,
                                                    remoteUrl = media.remoteUrl
                                                )
                                            },
                                            onReattachAttachment = { existingAtt ->
                                                val curList = pendingAttachmentsBySession[activeSession.id].orEmpty()
                                                if (curList.none { it.fileName == existingAtt.fileName }) {
                                                    pendingAttachmentsBySession = pendingAttachmentsBySession +
                                                        (activeSession.id to (curList + existingAtt.copy(id = "att-re-${PlatformBridge.currentTimeMillis()}")))
                                                    bannerNotification = "Re-attached '${existingAtt.fileName}' to prompt."
                                                }
                                            },
                                            onClose = { showRightFilesSidebar = false },
                                            modifier = Modifier
                                                .fillMaxWidth(0.86f)
                                                .fillMaxHeight()
                                                .clickable(enabled = false) {}
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
                                                        clearCurrentChatMessages(activeSession.id)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        // Regenerate Confirmation Modal Dialog (when confirmBeforeRegenerating is enabled in Settings -> Preferences)
                        val regenReq = pendingRegenerateConfirm
                        if (regenReq != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.42f))
                                    .clickable { pendingRegenerateConfirm = null },
                                contentAlignment = Alignment.Center
                            ) {
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
                                        text = "Regenerate Reply?",
                                        style = p.uiHeaderStyle.copy(fontSize = 17.sp)
                                    )
                                    BasicText(
                                        text = if (appState.preferences.deletePreviousMessageWhenRegenerating) {
                                            "This will replace the current reply in-place."
                                        } else {
                                            "This will generate a new version branch for this turn."
                                        },
                                        style = p.uiBodyStyle.copy(color = p.narrationInk)
                                    )
                                    SepiaToggleRow(
                                        title = "Ask for confirmation before regenerating",
                                        checked = appState.preferences.confirmBeforeRegenerating,
                                        onCheckedChange = { checked ->
                                            persist(
                                                appState.copy(preferences = appState.preferences.copy(confirmBeforeRegenerating = checked)),
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
                                            onClick = { pendingRegenerateConfirm = null }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        SepiaButton(
                                            text = "↻ Regenerate",
                                            compact = true,
                                            style = SepiaButtonStyle.PRIMARY,
                                            onClick = {
                                                pendingRegenerateConfirm = null
                                                executeRegenerateFromNode(regenReq.nodeIndex, regenReq.sessionId)
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

                        // Full Multi-Column Tab Overview Grid (Unlocked on BOTH Mobile & Desktop!)
                        if (showMobileTabSwitcher) {
                            MobileTabSwitcherModal(
                                appState = appState,
                                openTabIds = openTabIds,
                                activeSessionId = activeSession.id,
                                activeStreamsBySession = activeStreamsBySession,
                                isCompactScreen = isCompactScreen,
                                onSelectTab = ::switchToTab,
                                onCloseTab = ::closeChatTab,
                                onCloseEmptyTabs = ::closeEmptyNewChatTabs,
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
    onAttachWorkspaceFile: (String) -> Unit = {},
    onReattachExistingAttachment: (PromptAttachment) -> Unit = {},
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
    onTranslateNode: (Int) -> Unit = {},
    onContinueAssistantNode: (Int) -> Unit
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showMinimapTreeModal by remember { mutableStateOf(false) }
    var showContextBreakdownPopover by remember { mutableStateOf(false) }

    // Smart Bottom-Edge Auto-Follow with Instant User-Scroll Disengage & 5-Second Cooldown:
    // 1. Scrolls to the bottom spacer (`totalItemsCount - 1`) instead of `messages.size`, so long AI messages
    //    pin their BOTTOM edge to the bottom of the viewport instead of snapping to the TOP of the message!
    // 2. Immediately pauses auto-scroll for 5 seconds (and disengages while scrolled up) the moment the user
    //    scrolls manually with the mouse wheel or touch drag to read thoughts or earlier paragraphs.
    var userScrollPausedUntilMs by remember(activeSession.id) { mutableStateOf(0L) }
    var userDisengagedAutoFollow by remember(activeSession.id) { mutableStateOf(false) }

    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            // Re-pin auto-follow when a brand-new generation starts if the user was near the bottom
            if (!listState.canScrollForward) {
                userDisengagedAutoFollow = false
            }
        } else {
            userDisengagedAutoFollow = false
        }
    }

    LaunchedEffect(listState.isScrollInProgress, listState.canScrollForward) {
        if (listState.isScrollInProgress && listState.canScrollForward) {
            userDisengagedAutoFollow = true
            userScrollPausedUntilMs = PlatformBridge.currentTimeMillis() + 5000L
        } else if (!listState.isScrollInProgress && !listState.canScrollForward) {
            val now = PlatformBridge.currentTimeMillis()
            val remainingWait = userScrollPausedUntilMs - now
            if (remainingWait > 0L) {
                delay(remainingWait)
            }
            if (!listState.canScrollForward && !listState.isScrollInProgress) {
                userDisengagedAutoFollow = false
            }
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
            now >= userScrollPausedUntilMs
        ) {
            val totalItems = listState.layoutInfo.totalItemsCount
            val bottomSpacerIdx = (totalItems - 1).coerceAtLeast(activeSession.messages.size)
            listState.scrollToItem(bottomSpacerIdx)
        }
    }

    val activeContextNodes = remember(activeSession.id, activeSession.messages.size, isStreaming) {
        activeSession.messages.filter { it.includedInContext }
    }
    val sessionTotalInputTok = remember(activeSession.id, activeSession.messages.size, isStreaming) {
        activeSession.messages.sumOf { it.activeVariant.tokenMetrics.inputTokens }
    }
    val sessionTotalReasoningTok = remember(activeSession.id, activeSession.messages.size, isStreaming) {
        activeSession.messages.sumOf { it.activeVariant.tokenMetrics.reasoningTokens }
    }
    val sessionTotalOutputTok = remember(activeSession.id, activeSession.messages.size, isStreaming) {
        activeSession.messages.sumOf { it.activeVariant.tokenMetrics.outputTokens }
    }
    val sessionTotalTokens = sessionTotalInputTok + sessionTotalReasoningTok + sessionTotalOutputTok
    val sessionTotalCostUsd = remember(activeSession.id, activeSession.messages.size, lastMsgContentLen, isStreaming, activeProfile) {
        activeSession.messages.sumOf { node ->
            val v = node.activeVariant
            if (node.role == MessageRole.USER) {
                val uTok = O200kTokenizer.countMessageVariantTokens(MessageRole.USER, v)
                (uTok / 1_000_000.0) * activeProfile.inputPricePerM
            } else {
                val m = v.tokenMetrics
                if (m.estimatedCostUsd > 0.0) m.estimatedCostUsd else activeProfile.calculateTurnCostUsd(m)
            }
        }
    }
    val contextBreakdown = remember(
        activeSession,
        activeAssistant,
        activeProfile,
        activeContextNodes,
        userPromptInput,
        assistantPrefillInput,
        pendingAttachments,
        isGoalModeEnabled,
        lastMsgContentLen
    ) {
        O200kTokenizer.computeContextWindowBreakdown(
            session = activeSession,
            assistant = activeAssistant,
            profile = activeProfile,
            draftPrompt = userPromptInput,
            draftPrefill = assistantPrefillInput,
            pendingAttachments = pendingAttachments,
            goalModeActive = isGoalModeEnabled
        )
    }
    val liveContextTokens = contextBreakdown.totalUsedTokens

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
            activeSession.messages.isNotEmpty() && listState.canScrollForward
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
                    .pointerInput(activeSession.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.type == PointerEventType.Scroll) {
                                    val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    if (dy < 0f) {
                                        // User scrolled UP with mouse wheel / trackpad — immediately pause auto-follow for 5s!
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
                    }
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
                        assistantIconEmoji = activeAssistant.iconEmoji,
                        providerName = activeProfile.name,
                        inputPricePerM = activeProfile.inputPricePerM,
                        outputPricePerM = activeProfile.outputPricePerM,
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
                            userDisengagedAutoFollow = false
                            userScrollPausedUntilMs = 0L
                            scope.launch {
                                val totalItems = listState.layoutInfo.totalItemsCount
                                val bottomSpacerIdx = (totalItems - 1).coerceAtLeast(activeSession.messages.size)
                                listState.scrollToItem(bottomSpacerIdx)
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
                                userDisengagedAutoFollow = false
                                userScrollPausedUntilMs = 0L
                                scope.launch {
                                    val totalItems = listState.layoutInfo.totalItemsCount
                                    val bottomSpacerIdx = (totalItems - 1).coerceAtLeast(activeSession.messages.size)
                                    listState.scrollToItem(bottomSpacerIdx)
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
                                label = if (showPrefillBox) "▾ Prefill Reply: ON" else "▸ Prefill Reply…",
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
                // Vertical Slash-Command Autocomplete Palette when typing `/` in the Composer!
                // Automatically dismisses as soon as a space is entered (e.g. `/goal ` or `/btw `) so it never blocks typing!
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
                            .border(0.65.dp, p.border.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
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
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(
                                        when {
                                            isExact || isPressed -> p.accent.copy(alpha = 0.14f)
                                            isHovered -> p.accent.copy(alpha = 0.08f)
                                            else -> p.cardBg.copy(alpha = 0.72f)
                                        }
                                    )
                                    .border(
                                        width = 0.6.dp,
                                        color = if (isExact || isHovered) p.accent.copy(alpha = 0.55f) else Color.Transparent,
                                        shape = RoundedCornerShape(7.dp)
                                    )
                                    .kissClickable(
                                        pressScale = 0.99f,
                                        hoverScale = 1.004f,
                                        interactionSource = rowInteraction,
                                        onClick = {
                                            if (spec.takesArgument) {
                                                onUserPromptChange("${spec.command} ")
                                            } else {
                                                // 1-tap instant execution for no-argument slash commands!
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
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(p.elevatedBg.copy(alpha = 0.7f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    BasicText(
                                        text = if (spec.takesArgument) "⇥ arg" else "↵ Run",
                                        style = p.monoStyle.copy(
                                            fontSize = 10.sp,
                                            color = p.narrationInk,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Vertical `@` File, Attachment & Lore Mention Autocomplete Palette!
                val activeAtQuery: String? = remember(userPromptInput) {
                    val lastAt = userPromptInput.lastIndexOf('@')
                    if (lastAt == -1) null
                    else if (lastAt > 0 && !userPromptInput[lastAt - 1].isWhitespace()) null
                    else {
                        val afterAt = userPromptInput.substring(lastAt + 1)
                        if (afterAt.contains(' ') || afterAt.contains('\n')) null else afterAt
                    }
                }
                val effectiveWorkspacePath = activeSession.workspacePath.ifBlank { activeAssistant.workspacePath }
                val workspaceFiles = remember(effectiveWorkspacePath, activeAtQuery != null) {
                    if (activeAtQuery == null || effectiveWorkspacePath.isBlank()) emptyList()
                    else {
                        PlatformBridge.workspaceGlob(effectiveWorkspacePath, "*", maxResults = 160)
                            .getOrDefault("")
                            .lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("No files") && !it.endsWith("/") }
                            .map { it.substringBefore(" (").trim() }
                    }
                }
                val historyAttachments = remember(activeSession.messages) {
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
                            .border(0.65.dp, p.border.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
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
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(
                                        when {
                                            isPressed -> p.accent.copy(alpha = 0.14f)
                                            isHovered -> p.accent.copy(alpha = 0.08f)
                                            else -> p.cardBg.copy(alpha = 0.72f)
                                        }
                                    )
                                    .border(
                                        width = 0.6.dp,
                                        color = if (isHovered) p.accent.copy(alpha = 0.55f) else Color.Transparent,
                                        shape = RoundedCornerShape(7.dp)
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
                                            fontSize = 11.5.sp
                                        )
                                    )
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

                // Interactive Context Window Breakdown Popover (Opened by clicking the Circular Context Ring)
                AnimatedVisibility(visible = showContextBreakdownPopover) {
                    ContextWindowBreakdownPopover(
                        breakdown = contextBreakdown,
                        sessionTotalCostUsd = sessionTotalCostUsd,
                        isStreaming = isStreaming,
                        canCompact = activeSession.messages.size > 4,
                        canSummarize = activeSession.messages.isNotEmpty(),
                        onCompactContext = onCompactContext,
                        onSummarizeStory = onAutoSummarizeStory,
                        onOpenPricingSettings = onOpenPricingSettings,
                        onClose = { showContextBreakdownPopover = false }
                    )
                }
                SepiaDivider()

                // 5. Zen Composer Footer: Dynamically scaled for both Mobile & Desktop (zero right-side whitespace!)
                val hasActiveTools = activeProfile.enableWebSearch ||
                    activeProfile.enableCodeExecution ||
                    activeProfile.enableUrlContext ||
                    activeSession.workspaceMode != WorkspaceMode.OFF ||
                    isGoalModeEnabled ||
                    isLiveVoiceMode ||
                    showPrefillBox

                val rawEffort = activeProfile.thinkingEffort.ifBlank { "default" }.lowercase()
                val thinkingLabel = if (preferences.showReasoningLevelOnButton) {
                    "🧠 ${rawEffort.uppercase()}"
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
                            if (preferences.showTokenAndContextStats) {
                                Spacer(Modifier.width(6.dp))
                                SepiaContextGaugeRing(
                                    usedTokens = liveContextTokens,
                                    contextLimitTokens = contextBreakdown.contextLimitTokens,
                                    sessionCostUsd = sessionTotalCostUsd,
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
                            if (preferences.showTokenAndContextStats) {
                                SepiaContextGaugeRing(
                                    usedTokens = liveContextTokens,
                                    contextLimitTokens = contextBreakdown.contextLimitTokens,
                                    sessionCostUsd = sessionTotalCostUsd,
                                    onClick = { showContextBreakdownPopover = !showContextBreakdownPopover }
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
    //   - Default (`manualThinkingToggle == null` or `true`): Full Reasoning & Tool Timeline open.
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

    val speakerTitle = if (isAssistant) {
        characterName.ifBlank { activeVar.modelUsed.ifBlank { "Assistant" } }
    } else {
        userName.ifBlank { "User" }
    }

    var variantSwipeDelta by remember(node.id, node.activeVariantIndex) { mutableStateOf(0f) }

    // Disable outer `.animateContentSize` while `activeVar.isStreaming` is true so 60/120fps VSync character
    // streaming never fights a lagging spring height animation on every wrapped line!
    val baseContainerModifier = if (viewMode == ViewMode.MANUSCRIPT) {
        Modifier
            .fillMaxWidth()
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
        val shape = RoundedCornerShape(12.dp)
        val bg = if (isAssistant) p.cardBg else p.elevatedBg.copy(alpha = 0.56f)
        val borderCol = if (isAssistant) p.border.copy(alpha = 0.45f) else p.border.copy(alpha = 0.38f)
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(0.65.dp, borderCol, shape)
            .then(
                if (!activeVar.isStreaming) Modifier.animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                else Modifier
            )
            .padding(horizontal = 15.dp, vertical = 14.dp)
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
                modifier = Modifier.weight(1f, fill = false).padding(top = 2.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isAssistant && preferences.showModelIcon) {
                    if (characterName.isNotBlank() && assistantIconEmoji != "💬") {
                        SepiaAvatarCircle(
                            symbol = assistantIconEmoji,
                            selected = true,
                            sizeDp = 22.dp
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(p.accent.copy(alpha = 0.14f))
                                .border(0.8.dp, p.accent.copy(alpha = 0.55f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            ModelLabSvgIcon(
                                modelOrProvider = activeVar.modelUsed.ifBlank { providerName },
                                tint = p.accent,
                                size = 13.dp,
                                fallbackProtocolName = providerName
                            )
                        }
                    }
                } else if (!isAssistant && preferences.showUserAvatar) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(p.elevatedBg)
                            .border(0.8.dp, p.border, CircleShape),
                            contentAlignment = Alignment.Center
                    ) {
                        KissVectorIcon(icon = KissIcon.USER, tint = p.narrationInk, size = 12.5.dp)
                    }
                }

                val showTurnTimestamp = (isAssistant && preferences.showModelTimestamp) || (!isAssistant && preferences.showUserTimestamp)
                val timeSuffix = if (showTurnTimestamp && activeVar.createdAt > 0L) {
                    " • ${formatCompactTimestamp(activeVar.createdAt)}"
                } else ""

                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (isAssistant && preferences.showModelName) {
                            val provSuffix = if (preferences.showProviderAfterModelName && characterName.isBlank()) " ($providerName)" else ""
                            val primaryHeader = if (characterName.isNotBlank()) {
                                characterName.uppercase()
                            } else {
                                "${activeVar.modelUsed.ifBlank { "Assistant" }}$provSuffix"
                            }
                            BasicText(
                                text = primaryHeader,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = p.uiLabelStyle.copy(
                                    color = p.accent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp,
                                    letterSpacing = if (characterName.isNotBlank()) 0.6.sp else 0.sp
                                )
                            )
                        } else if (!isAssistant && preferences.showUserName) {
                            BasicText(
                                text = speakerTitle.uppercase(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = p.uiLabelStyle.copy(
                                    color = p.narrationInk,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.6.sp
                                )
                            )
                        }
                        if (timeSuffix.isNotEmpty()) {
                            BasicText(
                                text = timeSuffix.trimStart(),
                                maxLines = 1,
                                style = p.uiLabelStyle.copy(fontSize = 10.5.sp, color = p.mutedInk)
                            )
                        }
                        if (!node.includedInContext) {
                            BasicText(
                                text = "[Muted]",
                                style = p.uiLabelStyle.copy(color = p.danger, fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }

                    if (isAssistant && preferences.showModelName && characterName.isNotBlank() && activeVar.modelUsed.isNotBlank()) {
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
                            style = p.monoStyle.copy(fontSize = 10.sp, color = p.mutedInk)
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
                    val sec = if (activeVar.thinkingDurationMs > 0) " (${formatDurationFromMs(activeVar.thinkingDurationMs)})" else ""
                    val toolBadge = if (hasTools) " • ${activeVar.toolCalls.size} tools" else ""
                    val chipText = when {
                        isLivePeekOnly -> "🧠 Peek$sec…"
                        isActivelyThinkingThisNode -> "🧠 Thinking$sec…"
                        isReasoningExpanded -> "▾ Thought$sec$toolBadge"
                        else -> "▸ Thought$sec$toolBadge"
                    }
                    SepiaChip(
                        label = chipText,
                        selected = isReasoningExpanded || isLivePeekOnly || isActivelyThinkingThisNode,
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

        // Animated Collapsible Thinking (Full Timeline OR Compact Single-Thought Peek while Reasoning)
        val showDrawer = (isReasoningExpanded || isLivePeekOnly) &&
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
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.sheetBg.copy(alpha = 0.58f))
                    .border(0.65.dp, if (isActivelyThinkingThisNode) p.accent.copy(alpha = 0.8f) else p.border.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header Row for Reasoning & Tool Timeline Drawer
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
                                append("Latest Thought")
                            } else if (isActivelyThinkingThisNode) {
                                append("Thinking")
                            } else if (win.isCompact) {
                                append("Reasoning")
                            } else {
                                append("Reasoning & Tool Timeline")
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
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            manualThinkingToggle = if (isLivePeekOnly) true else false
                        }
                    )
                }

                if (isLivePeekOnly) {
                    // Compact Single-Thought Peek Window (shows ONLY the current/latest thought block while reasoning)
                    val (peekTitle, peekBody) = remember(activeVar.thinkingSummary, activeVar.trailingThinking) {
                        extractLatestThoughtPeek(activeVar)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (peekTitle.isNotEmpty()) {
                            BasicText(
                                text = peekTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = p.uiBodyStyle.copy(
                                    color = p.primaryInk,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            )
                        }
                        if (peekBody.isNotEmpty()) {
                            BasicText(
                                text = peekBody,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = p.uiBodyStyle.copy(
                                    color = p.narrationInk,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp
                                )
                            )
                        }
                    }
                } else {
                val hasInterleavedSteps = activeVar.toolCalls.isNotEmpty() &&
                    (activeVar.toolCalls.any { it.precedingThinking.isNotBlank() } || activeVar.trailingThinking.isNotBlank())

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
                    val toolIcon = remember(tc.name) { resolveToolKissIcon(tc.name) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(p.cardBg)
                            .border(
                                0.85.dp,
                                if (tc.isError) p.danger.copy(alpha = 0.7f) else p.border.copy(alpha = 0.75f),
                                RoundedCornerShape(7.dp)
                            )
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                KissVectorIcon(
                                    icon = toolIcon,
                                    tint = if (tc.isError) p.danger else p.accentSecondary,
                                    size = 13.dp
                                )
                                BasicText(
                                    text = "${tc.name}(${tc.argumentsJson})",
                                    maxLines = if (isToolResultExpanded) 6 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.monoStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
                                )
                            }
                            if (tc.resultText.length > 260) {
                                SepiaButton(
                                    text = if (isToolResultExpanded) "▴" else "▾",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { isToolResultExpanded = !isToolResultExpanded }
                                )
                            }
                        }
                        if (preferences.showToolResultSummary && tc.resultText.isNotBlank()) {
                            val shownResult = if (isToolResultExpanded) tc.resultText.take(4000) else tc.resultText.take(360)
                            BasicText(
                                text = if (!isToolResultExpanded && tc.resultText.length > 360) "$shownResult…" else shownResult,
                                style = p.monoStyle.copy(
                                    color = if (tc.isError) p.danger else p.narrationInk,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }

                if (hasInterleavedSteps) {
                    // Chronological Interleaved Timeline: think1 -> tool1 -> think2 -> tool2 -> think3 -> tool3
                    var thoughtStepCounter = 0
                    activeVar.toolCalls.forEach { tc ->
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
                                        style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp)
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
                        if (preferences.showToolCards) {
                            RenderToolExecutionCard(tc)
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
                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp)
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
                            text = "Thought Signature Verified (${activeVar.thinkingSignature.length} chars)",
                            style = p.uiLabelStyle.copy(fontSize = 10.sp, fontFamily = p.codeFontFamily)
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
                    if (!isAssistant) {
                        SepiaButton(
                            text = "↻ Save & Regenerate",
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
                        text = "Save as New Version",
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
            val hasNonEmptyProseOrIdle = activeVar.content.isNotBlank() ||
                (!isActivelyThinkingThisNode && activeVar.mediaItems.isEmpty() && activeVar.attachments.isEmpty())
            if (hasNonEmptyProseOrIdle) {
                val isLongCollapsible = preferences.collapseLongMessages && !activeVar.isStreaming && activeVar.content.length > 1800
                var isLongExpanded by remember(node.id, node.activeVariantIndex) { mutableStateOf(false) }
                val displayedProse = if (isLongCollapsible && !isLongExpanded) {
                    activeVar.content.take(1400) + "…"
                } else {
                    activeVar.content.ifBlank { if (activeVar.isStreaming) "…" else "" }
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

                    // Show, Don't Tell: Icon-only primary turn actions (Copy, Speak/Stop, Redo, More)
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
                        compact = true,
                        enabled = !activeVar.isStreaming,
                        style = SepiaButtonStyle.GHOST,
                        onClick = onRegenerate
                    )
                    SepiaButton(
                        text = if (showMoreActions) "▴" else "⋯",
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
                            text = if (copiedBadge == "md") "✓ MD" else "📋 MD",
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
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onToggleContextMute
                        )
                        if (activeVar.rawRequestPreview.isNotBlank()) {
                            SepiaButton(
                                text = "‹/›",
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
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = onDeleteNode
                            )
                        } else {
                            SepiaButton(
                                text = "🗑",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = onDeleteNode
                            )
                        }
                    }
                }

                // Clean, Scannable Pill Telemetry for Assistant Messages (Unified with `o200k_base` Context Ring!)
                val m = activeVar.tokenMetrics
                if (isAssistant && preferences.showTokenAndContextStats &&
                    (activeVar.content.isNotBlank() || activeVar.toolCalls.isNotEmpty() || m.outputTokens > 0)
                ) {
                    val turnContextTok = remember(
                        activeVar.content,
                        activeVar.thinkingSummary,
                        activeVar.toolCalls,
                        activeVar.attachments
                    ) {
                        O200kTokenizer.countMessageVariantTokens(
                            role = MessageRole.ASSISTANT,
                            variant = activeVar,
                            includeReasoningAndTools = false
                        ) + activeVar.toolCalls.sumOf { tc ->
                            6 + O200kTokenizer.countTokens(tc.name) +
                                O200kTokenizer.countTokens(tc.argumentsJson) +
                                O200kTokenizer.countTokens(tc.resultText)
                        }
                    }
                    val thinkTok = remember(activeVar.thinkingSummary, m.reasoningTokens) {
                        if (activeVar.thinkingSummary.isNotBlank()) {
                            O200kTokenizer.countTokens(activeVar.thinkingSummary)
                        } else {
                            m.reasoningTokens
                        }
                    }
                    val costVal = if (m.estimatedCostUsd > 0.0) {
                        m.estimatedCostUsd
                    } else if (outputPricePerM > 0.0) {
                        ((turnContextTok + thinkTok) / 1_000_000.0) * outputPricePerM
                    } else 0.0

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Pill 1: Turn Context Tokens (+ Reasoning Tokens if present)
                        val tokPillText = buildString {
                            append("${formatCompactTokens(turnContextTok)} tok")
                            if (thinkTok > 0) {
                                append(" • 🧠 ${formatCompactTokens(thinkTok)}")
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(p.elevatedBg.copy(alpha = 0.72f))
                                .border(0.65.dp, p.border.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 7.dp, vertical = 2.5.dp)
                        ) {
                            BasicText(
                                text = tokPillText,
                                style = p.monoStyle.copy(fontSize = 10.sp, color = p.narrationInk, fontWeight = FontWeight.Medium)
                            )
                        }

                        // Pill 2: Speed & Duration
                        if (m.tokensPerSec > 0.0 || m.durationMs > 0L) {
                            val speedText = buildString {
                                if (m.tokensPerSec > 0.0) append("⚡ ${m.tokensPerSec} tok/s")
                                if (m.durationMs > 0L) {
                                    if (isNotEmpty()) append(" • ")
                                    append(formatDurationFromMs(m.durationMs))
                                }
                                if ((showMoreActions || preferences.showTokensForEntireTurn) && m.ttftMs > 0L) {
                                    append(" (TTFT ${formatDurationFromMs(m.ttftMs)})")
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(p.elevatedBg.copy(alpha = 0.72f))
                                    .border(0.65.dp, p.border.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
                            ) {
                                BasicText(
                                    text = speedText,
                                    style = p.monoStyle.copy(fontSize = 10.sp, color = p.mutedInk)
                                )
                            }
                        }

                        // Pill 3: Cost Badge
                        if (costVal > 0.0) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(p.accent.copy(alpha = 0.12f))
                                    .border(0.65.dp, p.accent.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
                            ) {
                                BasicText(
                                    text = formatUsdCost(costVal),
                                    style = p.monoStyle.copy(fontSize = 10.sp, color = p.accent, fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }

                        // Optional Expanded Wire Input/Cache Pill when `⋯` More is open
                        if ((showMoreActions || preferences.showTokensForEntireTurn) && m.inputTokens > 0) {
                            val wireText = buildString {
                                append("Wire ↑ ${formatCompactTokens(m.inputTokens)}")
                                if (m.cachedInputTokens > 0) {
                                    append(" (⚡${formatCompactTokens(m.cachedInputTokens)} cached)")
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(p.elevatedBg.copy(alpha = 0.55f))
                                    .border(0.65.dp, p.border.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
                            ) {
                                BasicText(
                                    text = wireText,
                                    style = p.monoStyle.copy(fontSize = 10.sp, color = p.mutedInk)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Clean User Message Token & Price Pill Row (Shown below every User message when token stats are enabled)
        if (!isAssistant && preferences.showTokenAndContextStats &&
            (activeVar.content.isNotBlank() || activeVar.attachments.isNotEmpty())
        ) {
            val userTok = remember(activeVar.content, activeVar.attachments) {
                O200kTokenizer.countMessageVariantTokens(MessageRole.USER, activeVar)
            }
            val userCost = (userTok / 1_000_000.0) * inputPricePerM
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val attSuffix = if (activeVar.attachments.isNotEmpty()) {
                    " • ${activeVar.attachments.size} file${if (activeVar.attachments.size > 1) "s" else ""}"
                } else ""
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(p.elevatedBg.copy(alpha = 0.72f))
                        .border(0.65.dp, p.border.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                ) {
                    BasicText(
                        text = "${formatCompactTokens(userTok)} tok$attSuffix",
                        style = p.monoStyle.copy(fontSize = 10.sp, color = p.mutedInk, fontWeight = FontWeight.Medium)
                    )
                }
                if (userCost > 0.0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(p.accent.copy(alpha = 0.12f))
                            .border(0.65.dp, p.accent.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 7.dp, vertical = 2.5.dp)
                    ) {
                        BasicText(
                            text = formatUsdCost(userCost),
                            style = p.monoStyle.copy(fontSize = 10.sp, color = p.accent, fontWeight = FontWeight.SemiBold)
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
                    .border(0.9.dp, p.accent.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        )
                    }
                    SepiaButton(
                        text = "×",
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
                                "↳ Line ${src.groundedLineNumbers.first()}"
                            } else {
                                "↳ Lines ${src.groundedLineNumbers.joinToString(", ")}"
                            }
                        } else {
                            "↳ Grounded"
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelectedLine) p.accent.copy(alpha = 0.12f) else p.cardBg)
                                .border(
                                    width = if (isSelectedLine) 1.15.dp else 0.85.dp,
                                    color = if (isSelectedLine) p.accent else p.border,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    if (firstGroundedLine.isNotBlank()) {
                                        highlightedSourceLineText = if (isSelectedLine) "" else firstGroundedLine
                                    } else {
                                        PlatformBridge.openOrPlayMedia(src.url)
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
                                            style = p.monoStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                                        )
                                    }
                                    BasicText(
                                        text = src.title.ifBlank { src.domain.ifBlank { src.url } },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp),
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (src.domain.isNotBlank()) {
                                        BasicText(
                                            text = "• ${src.domain}",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiLabelStyle.copy(color = p.mutedInk, fontSize = 10.5.sp)
                                        )
                                    }
                                }
                                SepiaButton(
                                    text = "↗",
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { PlatformBridge.openOrPlayMedia(src.url) }
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
                                        BasicText(
                                            text = lineBadgeText,
                                            style = p.uiLabelStyle.copy(
                                                color = p.accent,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        )
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
