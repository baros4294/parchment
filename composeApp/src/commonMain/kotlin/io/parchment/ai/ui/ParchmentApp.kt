package io.parchment.ai.ui

import androidx.compose.ui.draw.alpha
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.QueuedPromptItem
import io.parchment.ai.harness.AgentToolRunContext
import io.parchment.ai.network.MAX_INLINE_ATTACHMENT_BYTES
import io.parchment.ai.network.cancelBackgroundInteraction
import io.parchment.ai.network.fetchAvailableModels
import io.parchment.ai.network.stripInlineErrorNotices
import io.parchment.ai.platform.MAX_INLINE_TEXT_ATTACHMENT_BYTES
import io.parchment.ai.platform.inlineAttachmentText
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.AgentPermissionOverrides
import io.parchment.ai.model.withImportedPermissions
import io.parchment.ai.model.withLegacyWorkspaceChangeFrom
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.ModelsDevClient
import io.parchment.ai.network.OtaReleaseInfo
import io.parchment.ai.ParchmentBuildConfig
import io.parchment.ai.network.OtaUpdateEngine
import io.parchment.ai.network.OtaUpdateState
import io.parchment.ai.network.StreamUpdate
import io.parchment.ai.network.updateBlockedReason
import io.parchment.ai.model.ApiUsageRecord
import io.parchment.ai.platform.PickedBinaryFile
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.platform.normalizePickedBinaryFile
import io.parchment.ai.protocol.O200kTokenizer
import io.parchment.ai.protocol.CardAndPromptCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.ExperimentalEncodingApi


private const val STARTUP_UPDATE_CHECK_DELAY_MS = 8_000L

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun ParchmentApp(
    /** Desktop window hook; it receives what the window caption should name: the open chat or Settings. */
    desktopWindowHeader: (@Composable (captionTitle: String) -> Unit)? = null
) {
    val uiScope = rememberCoroutineScope()

    val processState = parchmentProcessState
    var appState by processState.snapshot
    var needsBackgroundChatHydration by processState.needsBackgroundHydration
    var isSettingsOpen by remember { mutableStateOf(false) }
    var activeSettingsCategory by remember { mutableStateOf(SettingsCategory.PREFERENCES) }
    var startMobileSettingsAtRoot by remember { mutableStateOf(true) }
    var showSidebar by remember { mutableStateOf(!PlatformBridge.isAndroid && appState.preferences.sidebarOpen) }
    var sidebarTab by remember { mutableStateOf(SidebarTab.TOPICS) }
    var pendingPinJump by remember { mutableStateOf<PinnedReply?>(null) }
    var showSessionInspector by remember { mutableStateOf(false) }
    var showRightFilesSidebar by remember { mutableStateOf(false) }
    var showQuickModelPopover by remember { mutableStateOf(false) }
    var showComposerToolsDrawer by remember(appState.activeSessionId) { mutableStateOf(false) }
    var showMobileTabSwitcher by remember { mutableStateOf(false) }
    var bannerNotification by processState.notice

    // OTA Update state
    var otaState by remember { mutableStateOf<OtaUpdateState>(OtaUpdateState.Idle) }
    var dismissOtaBanner by remember { mutableStateOf(false) }

    // Concurrent Per-Session Multi-Tasking Stream & Queue State (allows multiple tabs to run simultaneously!)
    var activeStreamsBySession by processState.runs.streams
    var queuedPromptsBySession by processState.queuedPrompts
    DisposableEffect(uiScope) {
        val clock = uiScope.coroutineContext[MonotonicFrameClock]
        processState.attachClock(clock)
        onDispose { processState.detachClock(clock) }
    }

    val activeProfile = remember(appState.profiles, appState.activeProfileId) {
        appState.profiles.find { it.id == appState.activeProfileId }
            ?: appState.profiles.first()
    }
    val activeSession = remember(appState.sessions, appState.activeSessionId) {
        appState.sessions.find { it.id == appState.activeSessionId }
            ?: appState.sessions.first()
    }
    val activeAssistant = remember(appState.assistants, activeSession.assistantId, appState.activeAssistantId) {
        appState.assistants.find { it.id == activeSession.assistantId && it.id.isNotBlank() }
            ?: appState.assistants.find { it.id == appState.activeAssistantId && it.id.isNotBlank() }
            ?: appState.assistants.firstOrNull()
            ?: AssistantAgent(id = "asst-default", name = "Default Assistant", iconEmoji = "💬", systemPrompt = "")
    }

    McpServerConnections(appState.preferences.mcpServers)
    // Background startup hydration: after Frame 1 renders, pre-warm O200kTokenizer and load inactive chat stubs on Dispatchers.Default
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withContext(Dispatchers.Default) {
            O200kTokenizer.warmUp()
        }
        if (needsBackgroundChatHydration) {
            val beforeLoad = appState
            val keepHydrated = activeStreamsBySession.keys
            val hydrated = withContext(Dispatchers.Default) {
                loadRemainingHistoricalSessionsDehydrated(beforeLoad, keepHydrated)
            }
            appState = mergeLoadedSessions(appState, beforeLoad, hydrated)
            needsBackgroundChatHydration = false
        }
    }

    // On-demand lazy hydration when switching active chat + automatic dehydration of inactive chats to keep RAM ultra-low
    LaunchedEffect(appState.activeSessionId) {
        val current = appState
        val target = current.sessions.find { it.id == current.activeSessionId }
        if (target != null && target.isCompactStub) {
            val keepHydrated = activeStreamsBySession.keys
            val rebalanced = withContext(Dispatchers.Default) {
                ensureActiveSessionHydratedAndDehydrateOthers(current, keepHydrated)
            }
            appState = mergeLoadedSessions(appState, current, rebalanced)
            if (appState.sessions.find { it.id == appState.activeSessionId }?.isCompactStub == true) {
                bannerNotification = "Chat history is unavailable. Restore its saved data before editing or sending."
            }
        } else {
            val rebalanced = ensureActiveSessionHydratedAndDehydrateOthers(current, activeStreamsBySession.keys)
            if (rebalanced !== current) {
                appState = rebalanced
            }
        }
    }

    // Active tab's stream state
    val currentTabStream = activeStreamsBySession[activeSession.id]
    val isStreaming = currentTabStream != null
    val isCurrentlyThinkingPhase = currentTabStream?.isThinkingPhase == true
    val streamingTargetNodeId = currentTabStream?.targetNodeId.orEmpty()
    val streamingStatus = currentTabStream?.statusText.orEmpty()
    val streamingInteractionId = currentTabStream?.interactionId.orEmpty()
    val queuedPrompts = queuedPromptsBySession[activeSession.id].orEmpty()

    // Open Browser Tabs list (always includes activeSession.id)
    val openTabIds = remember(appState.openTabSessionIds, appState.sessions, activeSession.id) {
        val validSaved = appState.openTabSessionIds.filter { id -> appState.sessions.any { it.id == id } }
        normalizeOpenTabs(validSaved, activeSession.id)
    }

    // Per-session composer inputs & staged attachments (synced per session so switching tabs never leaks or loses files!)
    var userPromptInput by remember(activeSession.id) { mutableStateOf(activeSession.draftInput) }
    var assistantPrefillInput by remember(activeSession.id) { mutableStateOf(activeSession.draftPrefill) }
    var showPrefillBox by remember { mutableStateOf(false) }
    var isGoalModeEnabled by remember { mutableStateOf(false) }
    var isLiveVoiceMode by remember { mutableStateOf(false) }
    var isVoiceListening by remember { mutableStateOf(false) }
    var pendingAttachmentsBySession by processState.pendingAttachments
    val pendingAttachments = pendingAttachmentsBySession[activeSession.id].orEmpty()
    var pendingDeleteConfirmation by remember { mutableStateOf<DeleteConfirmRequest?>(null) }
    var pendingRegenerateConfirm by remember { mutableStateOf<RegenerateConfirmRequest?>(null) }
    var activeViewerItem by remember { mutableStateOf<ActiveMediaViewerItem?>(null) }
    var isDraggingFilesOverWindow by remember { mutableStateOf(false) }
    fun openAttachmentInViewer(att: PromptAttachment) {
        val mime = att.mimeType.lowercase().trim()
        val lower = att.fileName.lowercase().trim()
        val isSvg = mime == "image/svg+xml" || lower.endsWith(".svg") ||
            (att.textContent.contains("<svg", ignoreCase = true) && att.textContent.contains("</svg>", ignoreCase = true))
        val isPdf = mime == "application/pdf" || lower.endsWith(".pdf")
        val isVideoOrAudio = mime.startsWith("video/") || mime.startsWith("audio/") ||
            lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") ||
            lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".mp3") ||
            lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".flac")
        val isRasterImage = !isSvg && (mime.startsWith("image/") || lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp"))

        when {
            isPdf || isVideoOrAudio -> {
                if (att.localFilePath.isNotBlank()) {
                    PlatformBridge.openOrPlayMedia(att.localFilePath)
                } else {
                    bannerNotification = "File path is unavailable for ${att.fileName}."
                }
            }
            isRasterImage -> {
                activeViewerItem = ActiveMediaViewerItem.ImageViewer(
                    title = att.fileName.ifBlank { "Image Attachment" },
                    mimeType = att.mimeType.ifBlank { "image/png" },
                    localFilePath = att.localFilePath
                )
            }
            isSvg || att.textContent.isNotBlank() || mime.startsWith("text/") || mime == "application/json" -> {
                val content = att.textContent.ifBlank {
                    if (PlatformBridge.localFileSizeBytes(att.localFilePath) > MAX_TEXT_PREVIEW_BYTES) {
                        bannerNotification = "${att.fileName} is too large to preview here."
                        return
                    }
                    PlatformBridge.readBinaryFileBytes(att.localFilePath)?.let {
                        runCatching { it.decodeToString() }.getOrNull()
                    }.orEmpty()
                }
                activeViewerItem = ActiveMediaViewerItem.TextOrCodeViewer(
                    title = att.fileName.ifBlank { "Text Attachment" },
                    mimeType = if (isSvg) "image/svg+xml" else att.mimeType.ifBlank { "text/plain" },
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
        val (oversized, accepted) = files.partition { it.oversized }
        if (oversized.isNotEmpty()) {
            val limitMb = MAX_INLINE_ATTACHMENT_BYTES / (1024L * 1024L)
            bannerNotification = "Too large to attach (limit $limitMb MB): ${oversized.joinToString { it.fileName }}"
        }
        if (accepted.isEmpty()) return
        val newAtts = accepted.mapIndexed { idx, rawPicked ->
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
                    val savedPath = PlatformBridge.saveGeneratedMediaFile("att-$now-${picked.fileName}", picked.bytes, sessionId = activeSession.id)
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
                    val savedPath = PlatformBridge.saveGeneratedMediaFile("att-$now-${picked.fileName}", picked.bytes, sessionId = activeSession.id)
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
                    val savedPath = PlatformBridge.saveGeneratedMediaFile("att-$now-${picked.fileName}", picked.bytes, sessionId = activeSession.id)
                    PromptAttachment(
                        id = "att-$now",
                        fileName = picked.fileName,
                        mimeType = mime,
                        base64Data = "",
                        localFilePath = savedPath
                    )
                }
                else -> {
                    val inlineText = inlineAttachmentText(picked.bytes)
                    val savedPath = PlatformBridge.saveGeneratedMediaFile("att-$now-${picked.fileName}", picked.bytes, sessionId = activeSession.id)
                    if (inlineText != null) {
                        PromptAttachment(
                            id = "att-$now",
                            fileName = picked.fileName,
                            mimeType = "text/plain",
                            textContent = inlineText,
                            localFilePath = savedPath
                        )
                    } else {
                        val fileKind = if (picked.bytes.size > MAX_INLINE_TEXT_ATTACHMENT_BYTES) "Large file" else "Binary file"
                        PromptAttachment(
                            id = "att-$now",
                            fileName = picked.fileName,
                            mimeType = "application/octet-stream",
                            localFilePath = savedPath,
                            textContent = "[$fileKind attached: ${picked.fileName} (${picked.bytes.size / 1024} KB)]"
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
                    if (dropped.none { it.oversized }) {
                        bannerNotification = "Attached ${plural(dropped.size, "item")}: ${dropped.joinToString { it.fileName }}"
                    }
                }
            }
        )
    }

    // One process-wide writer retains all dirty sessions across debounce and UI lifecycle changes.
    LaunchedEffect(Unit) {
        PlatformBridge.registerStateFlushCallback { appDiskWriter.flushBlocking() }
    }
    LaunchedEffect(Unit) {
        var previousSaveNotice = ""
        appDiskWriter.lastError.collect { message ->
            val notice = message?.takeIf { it.isNotBlank() }?.let { "Unable to save changes: $it" }.orEmpty()
            if (notice.isNotEmpty()) bannerNotification = notice
            else if (bannerNotification == previousSaveNotice) bannerNotification = ""
            previousSaveNotice = notice
        }
    }

    fun persist(updated: AppStateSnapshot, immediateFlush: Boolean = false) {
        val previous = appState
        activeStreamsBySession.values.forEach { runtime ->
            val old = previous.sessions.find { it.id == runtime.sessionId }
            val next = updated.sessions.find { it.id == runtime.sessionId }
            fun policy(snapshot: AppStateSnapshot, session: ChatSession?) = session?.let {
                AgentPermissionResolver.resolve(it, snapshot.assistants.find { a -> a.id == it.assistantId }, snapshot.profiles.find { p -> p.id == it.profileId })
            }
            if (policy(previous, old) != policy(updated, next)) runtime.toolApprovalBroker?.invalidate()
        }
        val previousSessionsById = previous.sessions.associateBy { it.id }
        val changedSessions = updated.sessions.filter { previousSessionsById[it.id] != it }
        val removedIds = previousSessionsById.keys - updated.sessions.map { it.id }.toSet()
        appState = updated
        activeStreamsBySession.values.forEach { runtime ->
            runtime.toolApprovalBroker?.let { broker -> appProcessScope.launch { broker.refreshPolicy() } }
        }
        appDiskWriter.enqueue(
            settings = updated,
            sessions = changedSessions,
            deletedIds = removedIds,
            urgent = immediateFlush
        )
    }

    fun persistSingleSession(updatedSession: ChatSession, immediateFlush: Boolean = false) {
        appDiskWriter.enqueue(sessions = listOf(updatedSession), urgent = immediateFlush)
    }

    fun completeSessionForAction(session: ChatSession): ChatSession? {
        val complete = runCatching { hydrateSessionFromDiskIfNeeded(session) }.getOrElse { error ->
            bannerNotification = "Unable to load chat history: " + error.message.orEmpty()
            return null
        }
        if (complete.isCompactStub) {
            bannerNotification = "Chat history is unavailable. Restore its saved data before editing or sending."
            return null
        }
        return complete
    }

    fun updateActiveSession(immediate: Boolean = false, transform: (ChatSession) -> ChatSession) {
        val currentSnap = appState
        val targetId = currentSnap.sessions.find { it.id == currentSnap.activeSessionId }?.id
            ?: currentSnap.sessions.firstOrNull()?.id ?: return
        val complete = completeSessionForAction(currentSnap.sessions.first { it.id == targetId }) ?: return
        var modifiedSession: ChatSession? = null
        val newSessions = currentSnap.sessions.map {
            if (it.id == targetId) {
                val mod = transform(complete).withLegacyWorkspaceChangeFrom(complete).copy(updatedAt = PlatformBridge.currentTimeMillis())
                if (mod.permissionOverrides != complete.permissionOverrides) activeStreamsBySession[targetId]?.toolApprovalBroker?.invalidate()
                modifiedSession = mod
                mod
            } else it
        }
        appState = currentSnap.copy(sessions = newSessions)
        if (modifiedSession?.permissionOverrides != complete.permissionOverrides) {
            activeStreamsBySession[targetId]?.toolApprovalBroker?.let { broker -> appProcessScope.launch { broker.refreshPolicy() } }
        }
        modifiedSession?.let { persistSingleSession(it, immediateFlush = immediate) }
    }

    /** [touch] = false keeps the chat's place in the list: for bookkeeping such as pins, not new content. */
    fun updateSession(sessionId: String, immediate: Boolean, touch: Boolean, transform: (ChatSession) -> ChatSession) {
        val currentSnap = appState
        val target = currentSnap.sessions.find { it.id == sessionId } ?: return
        val complete = completeSessionForAction(target) ?: return
        var modifiedSession: ChatSession? = null
        val newSessions = currentSnap.sessions.map {
            if (it.id == sessionId) {
                val changed = transform(complete).withLegacyWorkspaceChangeFrom(complete)
                val mod = if (touch) changed.copy(updatedAt = PlatformBridge.currentTimeMillis()) else changed
                if (mod.permissionOverrides != complete.permissionOverrides) activeStreamsBySession[sessionId]?.toolApprovalBroker?.invalidate()
                modifiedSession = mod
                if (target.isCompactStub && sessionId != currentSnap.activeSessionId && sessionId !in activeStreamsBySession &&
                    mod.messages.none { node -> node.activeVariant.isStreaming }) {
                    dehydrateChatSessionToCompactStub(mod)
                } else mod
            } else it
        }
        appState = currentSnap.copy(sessions = newSessions)
        if (modifiedSession?.permissionOverrides != complete.permissionOverrides) {
            activeStreamsBySession[sessionId]?.toolApprovalBroker?.let { broker -> appProcessScope.launch { broker.refreshPolicy() } }
        }
        modifiedSession?.let { persistSingleSession(it, immediateFlush = immediate) }
    }

    fun updateSessionById(sessionId: String, immediate: Boolean = false, transform: (ChatSession) -> ChatSession) =
        updateSession(sessionId, immediate, touch = true, transform = transform)

    // Pinning is bookkeeping, not new content, so the chat keeps its place in the list.
    fun togglePinnedReply(sessionId: String, nodeId: String) =
        updateSession(sessionId, immediate = true, touch = false) { it.togglePinned(nodeId) }

    fun updateActiveSessionInMemoryOnly(sessionId: String, transform: (ChatSession) -> ChatSession) {
        val currentState = appState
        val target = currentState.sessions.find { it.id == sessionId } ?: return
        val complete = completeSessionForAction(target) ?: return
        var modifiedSession: ChatSession? = null
        val newSessions = currentState.sessions.map {
            if (it.id == sessionId) transform(complete).also { updated -> modifiedSession = updated } else it
        }
        appState = currentState.copy(sessions = newSessions)
        modifiedSession?.let { appDiskWriter.enqueue(sessions = listOf(it), notify = false) }
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

    // Automatically check for OTA updates on startup if enabled; development builds never offer one.
    // The wait keeps the network request and its parsing out of the first seconds of use.
    LaunchedEffect(appState.autoCheckOtaOnStartup, appState.otaGithubRepo) {
        if (appState.autoCheckOtaOnStartup && ParchmentBuildConfig.OFFICIAL_RELEASE) {
            delay(STARTUP_UPDATE_CHECK_DELAY_MS)
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

    fun currentUpdateBlock(): String? = updateBlockedReason(
        runningChats = activeStreamsBySession.size,
        queuedPrompts = queuedPromptsBySession.values.sumOf { it.size }
    )

    fun triggerOtaDownloadAndInstall(release: OtaReleaseInfo) {
        dismissOtaBanner = false
        currentUpdateBlock()?.let { reason ->
            bannerNotification = reason
            return
        }
        isSettingsOpen = false
        PlatformBridge.flushPendingStateNow()
        appProcessScope.launch {
            OtaUpdateEngine.downloadAndInstall(release, ::currentUpdateBlock).collect { st ->
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
            if (savedCustom != null && savedCustom.outputModalities.isNotEmpty()) {
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
                }
                ModelsDevClient.fetchModelSpec(currentEffectiveModel).onSuccess { spec ->
                    updateProfileById(activeProfile.id) {
                        val mergedSpec = if (savedCustom != null) {
                            ModelsDevClient.mergeModelSpecs(savedCustom, spec)
                        } else {
                            spec
                        }
                        it.copy(
                            contextWindowTokens = mergedSpec.contextWindowTokens,
                            inputPricePerM = mergedSpec.inputPricePerM,
                            outputPricePerM = mergedSpec.outputPricePerM,
                            cacheReadPricePerM = mergedSpec.cacheReadPricePerM,
                            cacheWritePricePerM = mergedSpec.cacheWritePricePerM,
                            reasoningPricePerM = mergedSpec.reasoningPricePerM,
                            customModelSpecs = it.customModelSpecs + (currentEffectiveModel to mergedSpec)
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
    fun recordUtilityUsage(sessionId: String, record: ApiUsageRecord) {
        updateSessionById(sessionId) { current ->
            current.withUtilityUsage(record)
        }
    }

    suspend fun generateTrackedUtility(sessionId: String, purpose: String, profile: ProviderProfile,
        model: String, prompt: String, maxOutputTokens: Int? = null): Result<String> {
        val runId = kotlinx.coroutines.currentCoroutineContext()[AgentToolRunContext]?.broker?.runId ?: PlatformBridge.createRunId()
        return AiProviderEngine.generateUtilityText(profile, model, prompt, maxOutputTokens,
            ownerSessionId = sessionId, ownerRunId = runId, purpose = purpose,
            onUsage = { record, _ -> withContext(Dispatchers.Main.immediate) { recordUtilityUsage(sessionId, record) } })
    }

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

            val hydratedTarget = withContext(Dispatchers.Default) {
                hydrateSessionFromDiskIfNeeded(targetSess)
            }
            if (hydratedTarget.isCompactStub) {
                if (forceRegenerate) bannerNotification = "Chat history is unavailable for title generation."
                return@launch
            }
            val transcriptSnippet = hydratedTarget.messages.take(4).joinToString("\n\n") { n ->
                val who = if (n.role == MessageRole.ASSISTANT) "Assistant" else "User"
                "$who: ${n.activeVariant.content.take(450)}"
            }
            val prompt = "${dm.titlePromptTemplate}\n\nConversation:\n$transcriptSnippet"

            generateTrackedUtility(sessionId, "title", resolvedProf, resolvedModel, prompt, maxOutputTokens = 256).fold(
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
        if (next != null) processState.runs.track(next)
    }

    val generationHost = GenerationHost(
        processState, activeAssistant, activeProfile, { isGoalModeEnabled }, { isLiveVoiceMode }, { dispatchNextQueuedItem },
        ::updateSessionById, ::updateActiveSessionInMemoryOnly, ::generateTopicTitle
    ) { id, purpose, profile, model, prompt, limit -> generateTrackedUtility(id, purpose, profile, model, prompt, limit) }

    fun beginGeneration(
        sessionId: String,
        historyForCall: List<MessageNode>,
        targetAssistantNodeId: String,
        targetVariantId: String,
        continuePrefix: String = "",
        autoMuteNodeIdsOnFinish: Set<String> = emptySet()
    ) = generationHost.beginGeneration(sessionId, historyForCall, targetAssistantNodeId, targetVariantId, continuePrefix, autoMuteNodeIdsOnFinish)

    fun triggerGeneration(
        sessionId: String,
        historyForCall: List<MessageNode>,
        targetAssistantNodeId: String,
        targetVariantId: String,
        continuePrefix: String = "",
        autoMuteNodeIdsOnFinish: Set<String> = emptySet()
    ) {
        val requestedSession = appState.sessions.find { it.id == sessionId }?.let(::completeSessionForAction) ?: return
        if (requestedSession.messages.none { it.id == targetAssistantNodeId && it.variants.any { v -> v.id == targetVariantId } }) {
            bannerNotification = "The requested turn is no longer available."
            return
        }
        val profile = appState.profiles.find { it.id == requestedSession.profileId } ?: activeProfile
        val model = requestedSession.modelOverride.ifBlank { profile.defaultModel }
        val identity = AgentRunIdentity(PlatformBridge.createRunId(), sessionId, targetAssistantNodeId, targetVariantId,
            profile.id, model, if (profile.protocol == ProviderProtocol.CUSTOM) profile.customDialect
            else io.parchment.ai.model.mapProtocolToDialect(profile.protocol.name))
        val persistedIds = requestedSession.messages.map { it.id }.toSet()
        processState.runs.prepare(identity, SessionStreamRuntime(sessionId,
            targetNodeId = targetAssistantNodeId, statusText = "Preparing generation…", providerContext = RunProviderContext(profile)), appProcessScope) { previous ->
            previous.forEach { old ->
                val producer = old.providerContext?.profile ?: profile
                if (old.interactionId.isNotBlank() && producer.useBackgroundLongDecode) {
                    runCatching { AiProviderEngine.cancelBackgroundInteraction(producer, old.interactionId) }
                }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val current = appState.sessions.find { it.id == sessionId }?.let(::completeSessionForAction) ?: return@prepare
            val target = current.messages.find { it.id == targetAssistantNodeId }?.variants?.find { it.id == targetVariantId }
                ?: return@prepare
            val continuingSameVariant = previous.any { it.identity?.let { old -> old.nodeId == targetAssistantNodeId && old.variantId == targetVariantId } == true }
            beginGeneration(sessionId, refreshGenerationHistory(historyForCall, persistedIds, current), targetAssistantNodeId,
                targetVariantId, if (continuingSameVariant) AiProviderEngine.stripInlineErrorNotices(target.content) else continuePrefix,
                autoMuteNodeIdsOnFinish)
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
        val targetSess = appState.sessions.find { it.id == sessionId }?.let(::completeSessionForAction) ?: return
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
            providerProtocol = AiProviderEngine.resolveWireProtocol(activeProfile),
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

    fun continueAssistantNodeAt(nodeIndex: Int) {
        val complete = completeSessionForAction(activeSession) ?: return
        val targetNode = complete.messages.getOrNull(nodeIndex) ?: return
        if (targetNode.role != MessageRole.ASSISTANT) return
        val historyBefore = complete.messages.subList(0, nodeIndex)
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

    val summaryHost = StorySummaryHost(
        processState, activeProfile, activeSession, ::completeSessionForAction, ::continueAssistantNodeAt, ::updateSessionStreamState, ::updateSessionById
    ) { id, purpose, profile, model, prompt, limit -> generateTrackedUtility(id, purpose, profile, model, prompt, limit) }

    fun triggerAutoSummarizeStory(autoMuteOlderTurns: Boolean = false) = summaryHost.triggerAutoSummarizeStory(autoMuteOlderTurns)

    fun clearCurrentChatMessages(targetSessionId: String = activeSession.id) {
        val curStream = activeStreamsBySession[targetSessionId]
        curStream?.identity?.let { processState.runs.requestStop(it) }
        curStream?.job?.cancel()
        if (curStream != null && curStream.interactionId.isNotBlank() && (curStream.providerContext?.profile ?: activeProfile).useBackgroundLongDecode) {
            appProcessScope.launch {
                AiProviderEngine.cancelBackgroundInteraction(curStream.providerContext?.profile ?: activeProfile, curStream.interactionId)
            }
        }
        updateSessionStreamState(targetSessionId) { null }
        queuedPromptsBySession = queuedPromptsBySession - targetSessionId
        pendingAttachmentsBySession = pendingAttachmentsBySession - targetSessionId
        updateSessionById(targetSessionId, immediate = true) { it.copy(messages = emptyList()) }
    }

    fun sendUserTurn() {
        if (completeSessionForAction(activeSession) == null) return
        val rawInput = userPromptInput.trim()
        val prefill = assistantPrefillInput.trim()
        val sessId = activeSession.id
        var currentAtts = pendingAttachmentsBySession[sessId].orEmpty()
        if (rawInput.isEmpty() && prefill.isEmpty() && currentAtts.isEmpty()) return

        // Custom Protocol with no wire dialect chosen must never guess a payload shape.
        if (AiProviderEngine.isCustomDialectUnresolved(activeProfile)) {
            val noticeId = "sys-${PlatformBridge.currentTimeMillis()}"
            updateActiveSession { sess ->
                sess.copy(
                    messages = sess.messages + MessageNode(
                        id = noticeId,
                        role = MessageRole.ASSISTANT,
                        variants = listOf(
                            MessageVariant(
                                id = "$noticeId-v",
                                content = "Choose an API format in Provider settings before sending.",
                                modelUsed = "system",
                                createdAt = PlatformBridge.currentTimeMillis()
                            )
                        ),
                        activeVariantIndex = 0
                    )
                )
            }
            return
        }

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
                bannerNotification = "Rewound last ${plural(dropCount, "message")}."
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
        if (completeSessionForAction(activeSession) == null) return
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

        if (steerText.equals("/compact", ignoreCase = true) && atts.isEmpty()) {
            userPromptInput = ""
            updateActiveSession { it.copy(draftInput = "") }
            triggerAutoSummarizeStory(autoMuteOlderTurns = true)
            return
        }
        if (steerText.equals("/summarize", ignoreCase = true) && atts.isEmpty()) {
            userPromptInput = ""
            updateActiveSession { it.copy(draftInput = "") }
            triggerAutoSummarizeStory(autoMuteOlderTurns = false)
            return
        }

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
        val complete = completeSessionForAction(activeSession) ?: return
        val targetSessId = activeSession.id
        if (activeStreamsBySession.containsKey(targetSessId)) return
        val now = PlatformBridge.currentTimeMillis()
        val draftProfile = (appState.profiles.find { it.id == complete.profileId } ?: activeProfile).copy(
            enableWebSearch = false, enableUrlContext = false, enableCodeExecution = false,
            enableImageGeneration = false, enableVideoGeneration = false, useBackgroundLongDecode = false)
        val draftRunId = PlatformBridge.createRunId()
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
        val historyForCall = complete.messages + syntheticNode
        userPromptInput = ""

        val impJob = appProcessScope.launch {
            val buf = StringBuilder()
            try {
                AiProviderEngine.streamGeneration(
                    profile = draftProfile,
                    session = complete.withProfileName(appState.preferences).copy(permissionOverrides = AgentPermissionOverrides(toolsEnabled = false)),
                    historyNodes = historyForCall,
                    usageOwnerSessionId = targetSessId,
                    usageOwnerRunId = draftRunId,
                    usagePurpose = "draft"
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
                        is StreamUpdate.UsageUpdated -> recordUtilityUsage(targetSessId, update.record)
                        else -> {}
                    }
                }
            } finally {
                if (activeStreamsBySession[targetSessId]?.job === coroutineContext[kotlinx.coroutines.Job]) updateSessionStreamState(targetSessId) { null }
            }
        }
        updateSessionStreamState(targetSessId) {
            SessionStreamRuntime(sessionId = targetSessId, statusText = "Drafting ${activeSession.userName}'s turn…", job = impJob,
                providerContext = RunProviderContext(draftProfile))
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
        val sess = curSnap.sessions.find { it.id == sessionId }?.let(::completeSessionForAction) ?: return
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
                        providerProtocol = AiProviderEngine.resolveWireProtocol(curProf),
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
                providerProtocol = AiProviderEngine.resolveWireProtocol(curProf),
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
        val sess = curSnap.sessions.find { it.id == curSnap.activeSessionId }?.let(::completeSessionForAction) ?: return
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
            generateTrackedUtility(sess.id, "translation", transProf, transModel, prompt).fold(
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
                                            tokenMetrics = io.parchment.ai.model.TokenMetrics(),
                                            apiUsageRecords = emptyList(),
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
        // Derive from persisted state (not the remembered snapshot) so rapid tab clicks never
        // operate on a stale list, and cap the strip with MRU eviction.
        val persistedTabs = appState.openTabSessionIds.filter { id -> appState.sessions.any { it.id == id } }
        val updatedTabs = normalizeOpenTabs(persistedTabs, sessionId)
        persist(
            appState.copy(
                activeSessionId = sessionId,
                activeAssistantId = target.assistantId.ifBlank { appState.activeAssistantId },
                openTabSessionIds = updatedTabs
            ),
            immediateFlush = true
        )
        // Switching tabs must not silently discard the user's settings context.
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
        emptyDropSet.forEach { PlatformBridge.deleteChatSessionFile(it) }
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
        PlatformBridge.deleteChatSessionFile(targetId)
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
            PlatformBridge.deleteChatSessionFile(id)
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

            // Register Android System Back Gesture / Back Button Navigation Stack
            LaunchedEffect(
                activeViewerItem,
                pendingDeleteConfirmation,
                pendingRegenerateConfirm,
                showMobileTabSwitcher,
                showQuickModelPopover,
                showRightFilesSidebar,
                showSessionInspector,
                showComposerToolsDrawer,
                showSidebar,
                isSettingsOpen,
                isCompactScreen
            ) {
                PlatformBridge.registerBackHandler {
                    when {
                        activeViewerItem != null -> {
                            activeViewerItem = null
                            true
                        }
                        pendingDeleteConfirmation != null -> {
                            pendingDeleteConfirmation = null
                            true
                        }
                        pendingRegenerateConfirm != null -> {
                            pendingRegenerateConfirm = null
                            true
                        }
                        showMobileTabSwitcher -> {
                            showMobileTabSwitcher = false
                            true
                        }
                        showQuickModelPopover -> {
                            showQuickModelPopover = false
                            true
                        }
                        showRightFilesSidebar -> {
                            showRightFilesSidebar = false
                            true
                        }
                        showSessionInspector -> {
                            showSessionInspector = false
                            true
                        }
                        !isSettingsOpen && showComposerToolsDrawer -> {
                            showComposerToolsDrawer = false
                            true
                        }
                        isCompactScreen && showSidebar && !isSettingsOpen -> {
                            showSidebar = false
                            true
                        }
                        isSettingsOpen -> {
                            isSettingsOpen = false
                            true
                        }
                        else -> false
                    }
                }
            }

            CompositionLocalProvider(
                LocalParchmentWindowSize provides windowSize,
                LocalReduceMotion provides appState.preferences.reduceMotion
            ) {
                var horizontalSwipeDelta by remember { mutableStateOf(0f) }
                var startedNearLeftEdge by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                        .onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && event.key == Key.Escape && PlatformBridge.handleSystemBackPressed()
                        }
                        .background(p.canvasBg)
                        .then(
                            if (isCompactScreen && !isSettingsOpen) {
                                Modifier.pointerInput(showSidebar, isSettingsOpen) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { startOffset ->
                                            horizontalSwipeDelta = 0f
                                            startedNearLeftEdge = startOffset.x <= 36.dp.toPx()
                                        },
                                        onHorizontalDrag = { _, dragAmount ->
                                            horizontalSwipeDelta += dragAmount
                                        },
                                        onDragEnd = {
                                            if (!showSidebar && startedNearLeftEdge && horizontalSwipeDelta > 52.dp.toPx()) {
                                                showSidebar = true
                                            } else if (showSidebar && horizontalSwipeDelta < -48.dp.toPx()) {
                                                showSidebar = false
                                            }
                                            horizontalSwipeDelta = 0f
                                            startedNearLeftEdge = false
                                        }
                                    )
                                }
                            } else Modifier
                        )
                ) {
                    if (desktopWindowHeader != null) {
                        desktopWindowHeader(if (isSettingsOpen) "Settings" else activeSession.title)
                    }

                    // 1. Top Editorial Header Bar (Hidden whenever Settings is open on both Desktop & Mobile so there is ONLY ONE `← Chat` button!)
                    if (!isSettingsOpen) {
                        val bgStreamingCount = activeStreamsBySession.keys.count { it != activeSession.id }
                        val vaultCountKey = remember(
                            activeSession.id,
                            activeSession.messages.size,
                            pendingAttachments.size,
                            activeSession.messages.lastOrNull()?.activeVariant?.let { v ->
                                "${v.toolCalls.size}:${v.mediaItems.size}:${v.webSources.size}:${v.isStreaming}:${v.content.length / 256}"
                            }
                        ) {
                            "${activeSession.id}:${activeSession.messages.size}:${pendingAttachments.size}:" +
                                activeSession.messages.sumOf {
                                    val v = it.activeVariant
                                    v.toolCalls.size * 17 + v.mediaItems.size * 31 + v.webSources.size * 13 + v.attachments.size * 7 + (v.content.length / 256)
                                }
                        }
                        val chatVaultCount = remember(vaultCountKey, pendingAttachments) {
                            countSessionVaultItemsFast(activeSession, pendingAttachments)
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
                            onToggleSidebar = {
                                showSidebar = !showSidebar
                                if (!isCompactScreen) persist(appState.copy(preferences = appState.preferences.copy(sidebarOpen = showSidebar)))
                            },
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
                        if (!isCompactScreen && openTabIds.size > 1) {
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
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(top = 4.dp, end = 8.dp)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.SPARKLES, tint = p.accent, size = 14.dp)
                                        BasicText(
                                            text = "Version ${currentOta.release.tagName.removePrefix("v")} is ready ($sizeMb MB)",
                                            style = p.uiBodyStyle.copy(fontWeight = FontWeight.Medium)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SepiaButton(
                                            text = "Update",
                                            compact = true,
                                            style = SepiaButtonStyle.PRIMARY,
                                            onClick = { triggerOtaDownloadAndInstall(currentOta.release) }
                                        )
                                        SepiaButton(
                                            text = "×",
                                            contentDescription = "Dismiss update notice",
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
                                        text = "Update failed: ${currentOta.message}",
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
                                            contentDescription = "Dismiss update notice",
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
                            onOpenPinnedReply = { pin ->
                                pendingPinJump = pin
                                switchToTab(pin.sessionId)
                                isSettingsOpen = false
                                if (isCompactScreen) showSidebar = false
                            },
                            onUnpinReply = { pin -> togglePinnedReply(pin.sessionId, pin.nodeId) },
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
                            Column(Modifier.fillMaxSize()) {
                                ToolApprovalPanel(currentTabStream?.toolApprovalBroker, activeSession.title)
                                UserQuestionPanel(currentTabStream?.toolApprovalBroker, activeSession.title)
                                Box(Modifier.weight(1f).fillMaxWidth()) {
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
                                    onRestoreFullBackup = { importedSnapshot ->
                                        val restored = importedSnapshot.withImportedPermissions(appState.otaGithubRepo)
                                        activeStreamsBySession.values.forEach { it.job?.cancel() }
                                        activeStreamsBySession = emptyMap()
                                        queuedPromptsBySession = emptyMap()
                                        pendingAttachmentsBySession = emptyMap()
                                        // Preserve existing chats on disk when importing a settings-only JSON file!
                                        val mergedSessions = if (restored.sessions.isEmpty()) {
                                            appState.sessions
                                        } else {
                                            val byId = LinkedHashMap<String, ChatSession>()
                                            for (s in appState.sessions) byId[s.id] = s
                                            for (s in restored.sessions) byId[s.id] = s
                                            byId.values.toList()
                                        }
                                        val finalSnap = sanitizeLoadedSnapshot(restored.copy(sessions = mergedSessions))
                                        val removedIds = appState.sessions.map { it.id }.toSet() - finalSnap.sessions.map { it.id }.toSet()
                                        appDiskWriter.replaceState(finalSnap, removedIds)
                                        appDiskWriter.flushBlocking()
                                        appState = finalSnap
                                        bannerNotification = "Imported settings & assistants (preserved ${plural(finalSnap.sessions.size, "chat")})."
                                    },
                                    onImportChatSessions = { importedList ->
                                        val byId = LinkedHashMap<String, ChatSession>()
                                        for (existing in appState.sessions) {
                                            byId[existing.id] = existing
                                        }
                                        val defaultProfId = appState.activeProfileId.ifBlank { appState.profiles.first().id }
                                        val defaultAsstId = appState.activeAssistantId.ifBlank { appState.assistants.first().id }
                                        var addedOrUpdated = 0
                                        var firstImportedId = ""
                                        for (incomingChat in importedList) {
                                            val rawChat = incomingChat.withImportedPermissions()
                                            val safeId = rawChat.id.ifBlank { "sess-imp-${PlatformBridge.currentTimeMillis()}-$addedOrUpdated" }
                                            val validProfId = appState.profiles.find { it.id == rawChat.profileId }?.id ?: defaultProfId
                                            val validAsstId = appState.assistants.find { it.id == rawChat.assistantId }?.id ?: defaultAsstId
                                            val normalized = rawChat.copy(
                                                id = safeId,
                                                profileId = validProfId,
                                                assistantId = validAsstId,
                                                messages = rawChat.messages.map { n ->
                                                    n.copy(variants = n.variants.map { v -> if (v.isStreaming) v.copy(isStreaming = false) else v })
                                                }
                                            )
                                            byId[safeId] = normalized
                                            saveSingleChatSessionToDisk(normalized)
                                            if (firstImportedId.isEmpty()) firstImportedId = safeId
                                            addedOrUpdated++
                                        }
                                        val sorted = byId.values.sortedWith(
                                            compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt }
                                        )
                                        val nextActive = if (firstImportedId.isNotEmpty()) firstImportedId else appState.activeSessionId
                                        val nextTabs = (openTabIds + firstImportedId).filter { it.isNotBlank() }.distinct()
                                        persist(
                                            appState.copy(
                                                sessions = sorted,
                                                activeSessionId = nextActive,
                                                openTabSessionIds = nextTabs
                                            ),
                                            immediateFlush = true
                                        )
                                        bannerNotification = "Imported ${plural(addedOrUpdated, "chat")} into ${PlatformBridge.getChatsStorageDirectoryPath()}."
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
                                    onDeleteChatSessionById = ::executeDeleteSingleSession,
                                    onInspectAttachment = ::openAttachmentInViewer,
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
                                    showComposerToolsDrawer = showComposerToolsDrawer,
                                    onComposerToolsDrawerChange = { showComposerToolsDrawer = it },
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
                                        val wsRoot = AgentPermissionResolver.resolve(activeSession, activeAssistant, activeProfile).workspacePath
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
                                            if (pasted.none { it.oversized }) {
                                                bannerNotification = "Attached ${plural(pasted.size, "clipboard item")}: ${pasted.joinToString { it.fileName }}"
                                            }
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
                                    onExportChatJson = {
                                        uiScope.launch {
                                            val hasLocalMedia = activeSession.messages.any { n ->
                                                n.variants.any { v ->
                                                    v.mediaItems.any { it.localFilePath.isNotBlank() } ||
                                                        v.attachments.any { it.localFilePath.isNotBlank() }
                                                }
                                            }
                                            if (hasLocalMedia) {
                                                val zipName = buildExportZipFileNameForChat(activeSession)
                                                val saved = runCatching { exportChatSessionsBundle(zipName, listOf(activeSession)) }.getOrElse { error ->
                                                    bannerNotification = "Chat export failed: ${error.message}"
                                                    return@launch
                                                }
                                                if (saved != null) bannerNotification = "Exported the chat with its media to $saved"
                                            } else {
                                                val fileName = buildExportFileNameForChat(activeSession)
                                                val bytes = CardAndPromptCodec.prettyJson.encodeToString(ChatSession.serializer(), activeSession).encodeToByteArray()
                                                val saved = PlatformBridge.saveFileForExport(fileName, bytes)
                                                if (saved != null) bannerNotification = "Exported the chat to $saved"
                                            }
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
                                                bannerNotification = "Rewound chat to turn #${nodeIndex + 1} (removed ${plural(dropped, "later turn")})."
                                            } else {
                                                // Clicked `↺ Rewind` on the final message: rewind that turn (or turn pair) so it's never a no-op!
                                                val dropCount = if (total >= 2 && activeSession.messages.last().role == MessageRole.ASSISTANT) 2 else 1
                                                updateActiveSession(immediate = true) { sess ->
                                                    sess.copy(messages = sess.messages.dropLast(dropCount))
                                                }
                                                bannerNotification = "Rewound last ${plural(dropCount, "turn")}."
                                            }
                                        }
                                    },
                                    onForkSessionFromNode = { nodeIndex ->
                                        val now = PlatformBridge.currentTimeMillis()
                                        val keepVersions = appState.preferences.keepMessageVersionsWhenForking
                                        val sourceSession = activeSession.withSpendProvenance()
                                        val slicedMessages = sourceSession.messages
                                            .subList(0, (nodeIndex + 1).coerceAtMost(activeSession.messages.size))
                                            .map { n ->
                                                if (keepVersions) n else n.copy(variants = listOf(n.activeVariant), activeVariantIndex = 0)
                                            }
                                        val forkedSession = sourceSession.copy(
                                            id = "sess-fork-$now",
                                            title = "${activeSession.title} (Branch #${nodeIndex + 1})",
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
                                        bannerNotification = "Started a new branch at message #${nodeIndex + 1}."
                                    },
                                    onStopStream = {
                                        val targetSessId = activeSession.id
                                        val curStream = activeStreamsBySession[targetSessId]
                                        curStream?.identity?.let { processState.runs.requestStop(it) }
                                        curStream?.job?.cancel()
                                        if (curStream != null && curStream.interactionId.isNotBlank() && (curStream.providerContext?.profile ?: activeProfile).useBackgroundLongDecode) {
                                            appProcessScope.launch {
                                                AiProviderEngine.cancelBackgroundInteraction(curStream.providerContext?.profile ?: activeProfile, curStream.interactionId)
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
                                    onContinueAssistantNode = ::continueAssistantNodeAt,
                                    jumpToNodeId = pendingPinJump?.takeIf { it.sessionId == activeSession.id }?.nodeId.orEmpty(),
                                    onJumpHandled = { pendingPinJump = null },
                                    onTogglePinnedReply = { nodeId -> togglePinnedReply(activeSession.id, nodeId) }
                                )
                                }
                            }
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
                                            .dismissOnOutsideTap { showSidebar = false }
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
                                                .blockTapThrough()
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
                                            .dismissOnOutsideTap { showRightFilesSidebar = false }
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
                                                .blockTapThrough()
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
                                    .border(2.dp, p.accent, RoundedCornerShape(12.dp))
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    KissVectorIcon(icon = KissIcon.UPLOAD, tint = p.accent, size = 34.dp)
                                    BasicText(
                                        text = "Drop images, videos, or files to attach",
                                        style = p.uiHeaderStyle.copy(fontSize = 20.sp, color = p.accent)
                                    )
                                    BasicText(
                                        text = "Supports PNG, JPG, WEBP, GIF, MP4, WEBM, MOV, PDF, audio, and Code/Text files",
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
                                    .dismissOnOutsideTap { pendingDeleteConfirmation = null },
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
                                        "Are you sure you want to delete all ${plural(confirmReq.count, "chat")} for '${confirmReq.assistantName}'? Your Assistant and System Prompt will be kept."
                                    is DeleteConfirmRequest.ClearCurrentMessages ->
                                        "Are you sure you want to clear all messages in '${confirmReq.title}'?"
                                }
                                val confirmBtnText = when (confirmReq) {
                                    is DeleteConfirmRequest.SingleSession -> "🗑 Delete"
                                    is DeleteConfirmRequest.ClearAllSessions -> "🗑 Clear All"
                                    is DeleteConfirmRequest.ClearCurrentMessages -> "🗑 Clear"
                                }

                                SepiaConfirmationCard(
                                    title = dialogTitle,
                                    body = dialogBody,
                                    options = {
                                        SepiaToggleRow(
                                            title = "Ask for confirmation before deleting",
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
                                    }
                                ) {
                                    SepiaButton(
                                        text = "Cancel",
                                        compact = true,
                                        style = SepiaButtonStyle.OUTLINE,
                                        onClick = { pendingDeleteConfirmation = null }
                                    )
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
                        // Regenerate Confirmation Modal Dialog (when confirmBeforeRegenerating is enabled in Settings -> Preferences)
                        val regenReq = pendingRegenerateConfirm
                        if (regenReq != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.42f))
                                    .dismissOnOutsideTap { pendingRegenerateConfirm = null },
                                contentAlignment = Alignment.Center
                            ) {
                                SepiaConfirmationCard(
                                    title = "Regenerate Reply?",
                                    body = if (appState.preferences.deletePreviousMessageWhenRegenerating) {
                                        "This will replace the current reply in-place."
                                    } else {
                                        "This will generate a new version branch for this turn."
                                    },
                                    options = {
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
                                    }
                                ) {
                                    SepiaButton(
                                        text = "Cancel",
                                        compact = true,
                                        style = SepiaButtonStyle.OUTLINE,
                                        onClick = { pendingRegenerateConfirm = null }
                                    )
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
                        FloatingNotification(
                            message = bannerNotification,
                            onDismiss = { bannerNotification = "" },
                            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
