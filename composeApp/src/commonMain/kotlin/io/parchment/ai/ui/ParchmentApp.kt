package io.parchment.ai.ui

import io.parchment.ai.harness.wasCutOff
import io.parchment.ai.harness.withInterruptedOutcome
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.hoverable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
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
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.ChatSession
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
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.harness.AgentToolRunContext
import io.parchment.ai.network.MAX_INLINE_ATTACHMENT_BYTES
import io.parchment.ai.network.appendSignatureChunkSafe
import io.parchment.ai.network.cancelBackgroundInteraction
import io.parchment.ai.network.fetchAvailableModels
import io.parchment.ai.network.resolveModelModalityProfile
import io.parchment.ai.network.stripInlineErrorNotices
import io.parchment.ai.platform.MAX_INLINE_TEXT_ATTACHMENT_BYTES
import io.parchment.ai.platform.inlineAttachmentText
import io.parchment.ai.runtime.appRunLeases
import io.parchment.ai.harness.ToolApprovalBroker
import io.parchment.ai.harness.ToolAccessScope
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.AgentPermissionOverrides
import io.parchment.ai.model.asOverrides
import io.parchment.ai.model.withImportedPermissions
import io.parchment.ai.model.withLegacyWorkspaceChangeFrom
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.model.ViewMode
import io.parchment.ai.model.WorkspaceMode
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.ModelsDevClient
import io.parchment.ai.network.OtaReleaseInfo
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun ParchmentApp(
    desktopWindowHeader: (@Composable () -> Unit)? = null
) {
    val uiScope = rememberCoroutineScope()

    val processState = parchmentProcessState
    var appState by processState.snapshot
    var needsBackgroundChatHydration by processState.needsBackgroundHydration
    var isSettingsOpen by remember { mutableStateOf(false) }
    var activeSettingsCategory by remember { mutableStateOf(SettingsCategory.PREFERENCES) }
    var startMobileSettingsAtRoot by remember { mutableStateOf(true) }
    var showSidebar by remember { mutableStateOf(!PlatformBridge.isAndroid) }
    var sidebarTab by remember { mutableStateOf(SidebarTab.TOPICS) }
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

    fun updateSessionById(sessionId: String, immediate: Boolean = false, transform: (ChatSession) -> ChatSession) {
        val currentSnap = appState
        val target = currentSnap.sessions.find { it.id == sessionId } ?: return
        val complete = completeSessionForAction(target) ?: return
        var modifiedSession: ChatSession? = null
        val newSessions = currentSnap.sessions.map {
            if (it.id == sessionId) {
                val mod = transform(complete).withLegacyWorkspaceChangeFrom(complete).copy(updatedAt = PlatformBridge.currentTimeMillis())
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

    fun beginGeneration(
        sessionId: String,
        historyForCall: List<MessageNode>,
        targetAssistantNodeId: String,
        targetVariantId: String,
        continuePrefix: String = "",
        autoMuteNodeIdsOnFinish: Set<String> = emptySet()
    ) {
        val sessionSnap = appState.sessions.find { it.id == sessionId } ?: return
        if (sessionSnap.isCompactStub) {
            bannerNotification = "Chat history is still loading. Try again when it is ready."
            return
        }
        if (sessionSnap.messages.none { node -> node.id == targetAssistantNodeId && node.variants.any { it.id == targetVariantId } }) {
            bannerNotification = "The requested turn is no longer available."
            return
        }
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

        val toolPolicy = AgentPermissionResolver.resolve(sessionSnap, assistantSnap, profileSnap)

        val mergedSessionForApi = sessionSnap.copy(
            systemPrompt = mergedSystemPrompt,
            authorsNote = sessionSnap.authorsNote.ifBlank { assistantSnap.authorsNote },
            activeCard = sessionSnap.activeCard ?: assistantSnap.activeCard,
            fragments = (appState.globalWorldBook + assistantSnap.fragments + sessionSnap.fragments).distinctBy { it.id },
            workspacePath = toolPolicy.workspacePath,
            workspaceMode = toolPolicy.workspaceMode,
            permissionOverrides = toolPolicy.asOverrides()
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
            enableWebSearch = toolPolicy.toolsEnabled && toolPolicy.webAccess && toolPolicy.allowsTool("web_search"),
            enableUrlContext = toolPolicy.toolsEnabled && toolPolicy.webAccess && toolPolicy.allowsTool("web_fetch") && profileSnap.enableUrlContext,
            allowShellExecution = false
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
        val identity = AgentRunIdentity(
            PlatformBridge.createRunId(), sessionId, targetAssistantNodeId, targetVariantId,
            effectiveProfileForApi.id, modelName,
            if (effectiveProfileForApi.protocol == ProviderProtocol.CUSTOM) effectiveProfileForApi.customDialect
            else io.parchment.ai.model.mapProtocolToDialect(effectiveProfileForApi.protocol.name)
        )

        var owningJob: kotlinx.coroutines.Job? = null
        val runStartedWithGoal = existingTargetVariant?.goalTracker != null || historyForCall.any { it.activeVariant.content.startsWith("/goal", true) }
        val approvalBroker = ToolApprovalBroker(
            runId = identity.runId,
            chatId = sessionId,
            policySource = {
                val current = appState.sessions.find { it.id == sessionId }
                if (current == null || owningJob?.isActive == false) null else ToolAccessScope(
                    AgentPermissionResolver.resolve(current, appState.assistants.find { it.id == current.assistantId }, appState.profiles.find { it.id == current.profileId }),
                    goalModeActive = isGoalModeEnabled || runStartedWithGoal
                )
            },
            onStop = { processState.runs.requestStop(identity); owningJob?.cancel() }
        )
        fun updateOwnedStream(updater: (SessionStreamRuntime?) -> SessionStreamRuntime?) {
            processState.runs.update(identity) { current -> updater(current) ?: current }
        }
        val newJob = appRunLeases.launch(appProcessScope, modelName, AgentToolRunContext(approvalBroker),
            start = kotlinx.coroutines.CoroutineStart.LAZY) { activity ->
            var terminalPhase = AgentRunPhase.INTERRUPTED
            updateOwnedStream { it?.copy(phase = AgentRunPhase.RUNNING) }
            val approvalObserver = launch {
                activity.waitingForApproval.collect { waiting ->
                    updateOwnedStream { current -> current?.copy(phase = when {
                        waiting -> AgentRunPhase.WAITING_APPROVAL
                        current.phase == AgentRunPhase.WAITING_APPROVAL -> AgentRunPhase.RUNNING
                        else -> current.phase
                    }) }
                }
            }
            val networkContentTarget = StringBuilder(cleanContinuePrefix)
            val networkThinkingTarget = StringBuilder(initialThinking)
            val currentStepThinkingTarget = StringBuilder(initialTrailingThinking)
            val signatureSb = StringBuilder(initialSignature)
            var displayedContentLen = cleanContinuePrefix.length
            var displayedThinkingLen = initialThinking.length
            var displayedStepThinkingLen = initialTrailingThinking.length
            val accumulatedTools = initialTools.toMutableList()
            var latestGoalTracker: GoalTrackerState? = existingTargetVariant?.goalTracker
            var accumulatedUsageRecords = existingTargetVariant?.apiUsageRecords.orEmpty()
            var latestTokenMetrics = existingTargetVariant?.tokenMetrics ?: io.parchment.ai.model.TokenMetrics()
            val accumulatedMedia = initialMedia.toMutableList()
            var accumulatedWebSources = initialWebSources
            var localInteractionId = existingTargetVariant?.providerInteractionId.orEmpty()
            var rawReq = ""
            var rawResp = ""
            var networkDone = false
            val turnRequestStartMs = activity.elapsedMs
            var activeThinkingPhaseStartMs = -1L
            var accumulatedThinkingMs = initialDurationMs
            var thinkingDurationMs = initialDurationMs
            var firstLiveTokenMs = -1L
            var liveTokensPerSec = 0.0
            var lastStopwatchTickMs = 0L
            var lastDiskCheckpointMs = turnRequestStartMs
            fun pauseActiveThinkingStopwatch() {
                if (activeThinkingPhaseStartMs >= 0L) {
                    val now = activity.elapsedMs
                    accumulatedThinkingMs += (now - activeThinkingPhaseStartMs).coerceAtLeast(0L)
                    thinkingDurationMs = accumulatedThinkingMs.coerceAtLeast(100L)
                    activeThinkingPhaseStartMs = -1L
                }
            }
            fun patchVariantMemory(updater: (MessageVariant) -> MessageVariant) {
                updateActiveSessionInMemoryOnly(sessionId) { sess ->
                    val msgs = sess.messages
                    val lastIdx = msgs.lastIndex
                    if (lastIdx >= 0 && msgs[lastIdx].id == targetAssistantNodeId) {
                        // Fast O(1) path for the active streaming turn at the bottom of the chat
                        val lastNode = msgs[lastIdx]
                        val updatedLast = lastNode.copy(
                            variants = lastNode.variants.map { v ->
                                if (v.id == targetVariantId) updater(v) else v
                            }
                        )
                        val nextList = ArrayList<MessageNode>(msgs.size)
                        for (i in 0 until lastIdx) nextList.add(msgs[i])
                        nextList.add(updatedLast)
                        sess.copy(messages = nextList)
                    } else {
                        sess.copy(
                            messages = msgs.map { node ->
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
            }

            fun flushCheckpointToDisk(isFinal: Boolean, finalUpdater: ((MessageVariant) -> MessageVariant)? = null) {
                if (isFinal) pauseActiveThinkingStopwatch()
                val curContent = if (isFinal) networkContentTarget.toString() else networkContentTarget.substring(0, displayedContentLen.coerceAtMost(networkContentTarget.length))
                val curThink = if (isFinal) networkThinkingTarget.toString() else networkThinkingTarget.substring(0, displayedThinkingLen.coerceAtMost(networkThinkingTarget.length))
                val curStepThink = if (accumulatedTools.isEmpty()) "" else if (isFinal) currentStepThinkingTarget.toString() else currentStepThinkingTarget.substring(0, displayedStepThinkingLen.coerceAtMost(currentStepThinkingTarget.length))
                val curSig = signatureSb.toString()
                val curTools = if (isFinal) {
                    accumulatedTools.map { tc -> if (tc.wasCutOff()) tc.withInterruptedOutcome() else tc }
                } else {
                    accumulatedTools.toList()
                }
                val curGoal = latestGoalTracker
                val curMedia = accumulatedMedia.toList()
                val curWebSources = accumulatedWebSources
                updateSessionById(sessionId, immediate = true) { sess ->
                    sess.copy(
                        messages = sess.messages.map { node ->
                            val shouldAutoMute = isFinal && terminalPhase == AgentRunPhase.COMPLETED && node.id in autoMuteNodeIdsOnFinish
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

            // Hardware VSync Letter-by-Letter Character Pacer (locks to 60Hz / 120Hz / 144Hz / 240Hz display clock via uiScope's MonotonicFrameClock,
            // using sub-pixel fractional accumulators so prose & thoughts glide onto the page letter-by-letter instead of jumping in word chunks!)
            val pacerJob = launch {
                var lastFrameNanos = 0L
                var thinkCarry = 0f
                var stepThinkCarry = 0f
                var contCarry = 0f

                fun computeLetterStep(diff: Int, dtMs: Long, carry: Float, done: Boolean): Pair<Int, Float> {
                    if (diff <= 0) return 0 to 0f
                    if (processState.frameClock.value == null) return diff to 0f
                    val dtSec = (dtMs.coerceIn(2L, 32L)) / 1000f
                    // Base letter-by-letter speed (~92 chars/sec -> ~0.76 chars per 120Hz frame, ~1.4 chars per 60Hz frame)
                    // plus a gentle elastic catch-up only when buffer backlog grows beyond 28 chars
                    val backlog = (diff - 28).coerceAtLeast(0)
                    val catchUpPerSec = if (done) {
                        maxOf(220f, diff * 10f)
                    } else {
                        backlog * 4.2f
                    }
                    val exactChars = carry + (92f + catchUpPerSec) * dtSec
                    val rawStep = exactChars.toInt()
                    return if (rawStep <= 0) {
                        0 to exactChars
                    } else {
                        val maxPerFrame = when {
                            done -> maxOf(4, diff / 4)
                            diff <= 28 -> 1
                            diff <= 72 -> 2
                            diff <= 160 -> 4
                            else -> maxOf(6, diff / 14)
                        }
                        val clamped = rawStep.coerceAtMost(maxPerFrame).coerceAtMost(diff)
                        clamped to (exactChars - clamped).coerceIn(0f, 0.99f)
                    }
                }

                var cachedVisibleCont = networkContentTarget.substring(0, displayedContentLen.coerceAtMost(networkContentTarget.length))
                var cachedVisibleContLen = displayedContentLen
                var cachedVisibleThink = networkThinkingTarget.substring(0, displayedThinkingLen.coerceAtMost(networkThinkingTarget.length))
                var cachedVisibleThinkLen = displayedThinkingLen
                var cachedVisibleStepThink = ""
                var cachedVisibleStepThinkLen = -1
                var cachedSig = signatureSb.toString()
                var cachedSigLen = signatureSb.length
                var cachedThinkWords = 0
                var lastThinkWordScanIdx = 0
                var lastThinkCharWasSpace = true

                while (!networkDone || displayedContentLen < networkContentTarget.length || displayedThinkingLen < networkThinkingTarget.length) {
                    val frameNanos = processState.nextFrameNanos()

                    val dtMs = if (frameNanos != null) {
                        val delta = if (lastFrameNanos > 0L) ((frameNanos - lastFrameNanos) / 1_000_000L).coerceIn(2L, 32L) else 8L
                        lastFrameNanos = frameNanos
                        delta
                    } else {
                        delay(if (processState.frameClock.value == null && !networkDone) 100L else 8L)
                        lastFrameNanos = 0L
                        8L
                    }
                    var changed = false
                    val now = activity.elapsedMs
                    if (firstLiveTokenMs >= 0L && now - lastStopwatchTickMs >= 100L) {
                        val netGenerationMs = ((now - turnRequestStartMs) - activity.toolMs).coerceAtLeast(500L)
                        val approxToolChars = accumulatedTools.sumOf { it.name.length + it.argumentsJson.length }
                        val approxGeneratedTok = ((networkContentTarget.length + networkThinkingTarget.length + approxToolChars) / 4).coerceAtLeast(1)
                        val rawLiveRate = (approxGeneratedTok * 1000.0) / netGenerationMs.toDouble()
                        val newLiveRate = ((rawLiveRate * 10.0).toInt()) / 10.0
                        if (newLiveRate != liveTokensPerSec) {
                            liveTokensPerSec = newLiveRate
                            changed = true
                        }
                    }
                    if (activeThinkingPhaseStartMs >= 0L && now - lastStopwatchTickMs >= 100L) {
                        lastStopwatchTickMs = now
                        thinkingDurationMs = (accumulatedThinkingMs + (now - activeThinkingPhaseStartMs)).coerceAtLeast(100L)
                        changed = true
                        val hasRunningToolNow = accumulatedTools.any { it.isRunning }
                        if (!hasRunningToolNow) {
                            val curThinkTargetLen = networkThinkingTarget.length
                            if (curThinkTargetLen < lastThinkWordScanIdx) {
                                cachedThinkWords = 0
                                lastThinkWordScanIdx = 0
                                lastThinkCharWasSpace = true
                            }
                            while (lastThinkWordScanIdx < curThinkTargetLen) {
                                val c = networkThinkingTarget[lastThinkWordScanIdx]
                                val isSp = c == ' ' || c == '\n' || c == '\r' || c == '\t'
                                if (!isSp && lastThinkCharWasSpace) cachedThinkWords++
                                lastThinkCharWasSpace = isSp
                                lastThinkWordScanIdx++
                            }
                            val thinkWords = cachedThinkWords
                            val toolSuffix = if (accumulatedTools.isNotEmpty()) " • ${accumulatedTools.size} tools" else ""
                            updateOwnedStream { cur ->
                                if (cur != null && cur.isThinkingPhase && !cur.statusText.startsWith("Auto-retrying") && !cur.statusText.startsWith("Context ")) {
                                    cur.copy(
                                        statusText = "Reasoning ($modelName • $thinkWords words$toolSuffix • ${formatDurationFromMs(thinkingDurationMs)})…"
                                    )
                                } else cur
                            }
                        }
                    } else if (now - lastStopwatchTickMs >= 100L) {
                        lastStopwatchTickMs = now
                    }
                    val targetThinkLen = networkThinkingTarget.length
                    if (displayedThinkingLen < targetThinkLen) {
                        val (step, nextCarry) = computeLetterStep(targetThinkLen - displayedThinkingLen, dtMs, thinkCarry, networkDone)
                        thinkCarry = nextCarry
                        if (step > 0) {
                            displayedThinkingLen = (displayedThinkingLen + step).coerceAtMost(targetThinkLen)
                            changed = true
                        }
                    } else if (displayedThinkingLen > targetThinkLen) {
                        displayedThinkingLen = targetThinkLen
                        thinkCarry = 0f
                        changed = true
                    } else {
                        thinkCarry = 0f
                    }
                    val targetStepThinkLen = currentStepThinkingTarget.length
                    if (displayedStepThinkingLen < targetStepThinkLen) {
                        val (step, nextCarry) = computeLetterStep(targetStepThinkLen - displayedStepThinkingLen, dtMs, stepThinkCarry, networkDone)
                        stepThinkCarry = nextCarry
                        if (step > 0) {
                            displayedStepThinkingLen = (displayedStepThinkingLen + step).coerceAtMost(targetStepThinkLen)
                            changed = true
                        }
                    } else if (displayedStepThinkingLen > targetStepThinkLen) {
                        displayedStepThinkingLen = targetStepThinkLen
                        stepThinkCarry = 0f
                        changed = true
                    } else {
                        stepThinkCarry = 0f
                    }
                    val targetContLen = networkContentTarget.length
                    if (displayedContentLen < targetContLen) {
                        val (step, nextCarry) = computeLetterStep(targetContLen - displayedContentLen, dtMs, contCarry, networkDone)
                        contCarry = nextCarry
                        if (step > 0) {
                            displayedContentLen = (displayedContentLen + step).coerceAtMost(targetContLen)
                            changed = true
                        }
                    } else if (displayedContentLen > targetContLen) {
                        displayedContentLen = targetContLen
                        contCarry = 0f
                        changed = true
                    } else {
                        contCarry = 0f
                    }
                    if (changed) {
                        if (displayedContentLen != cachedVisibleContLen) {
                            cachedVisibleCont = networkContentTarget.substring(0, displayedContentLen.coerceAtMost(networkContentTarget.length))
                            cachedVisibleContLen = displayedContentLen
                        }
                        if (displayedThinkingLen != cachedVisibleThinkLen) {
                            cachedVisibleThink = networkThinkingTarget.substring(0, displayedThinkingLen.coerceAtMost(networkThinkingTarget.length))
                            cachedVisibleThinkLen = displayedThinkingLen
                        }
                        val effStepLen = if (accumulatedTools.isEmpty()) 0 else displayedStepThinkingLen.coerceAtMost(currentStepThinkingTarget.length)
                        if (effStepLen != cachedVisibleStepThinkLen) {
                            cachedVisibleStepThink = if (effStepLen == 0) "" else currentStepThinkingTarget.substring(0, effStepLen)
                            cachedVisibleStepThinkLen = effStepLen
                        }
                        if (signatureSb.length != cachedSigLen) {
                            cachedSig = signatureSb.toString()
                            cachedSigLen = signatureSb.length
                        }
                        patchVariantMemory { v ->
                            v.copy(
                                content = cachedVisibleCont,
                                thinkingSummary = cachedVisibleThink,
                                trailingThinking = cachedVisibleStepThink,
                                thinkingSignature = cachedSig,
                                thinkingDurationMs = thinkingDurationMs,
                                tokenMetrics = if (liveTokensPerSec > 0.0) {
                                    v.tokenMetrics.copy(tokensPerSec = liveTokensPerSec)
                                } else v.tokenMetrics,
                                isStreaming = true
                            )
                        }
                    }
                    // Atomic background disk checkpoint every 3.5 seconds so switching apps NEVER loses progress without disk/JSON thrashing
                    if (now - lastDiskCheckpointMs >= 3500L) {
                        lastDiskCheckpointMs = now
                        flushCheckpointToDisk(isFinal = false)
                    }
                }
            }

            try {
                var finalSessionForCall = mergedSessionForApi
                var finalHistoryForCall = effectiveHistory
                val effectiveCtxLimit = (effectiveProfileForApi.customModelSpecs[modelName]?.contextWindowTokens
                    ?: effectiveProfileForApi.contextWindowTokens)
                val autoCompactThresholdTokens = (effectiveCtxLimit * 85) / 100
                var minTokensForNextAutoCompact = autoCompactThresholdTokens
                var lastMidStreamCompactCheckMs = activity.elapsedMs

                class MidStreamAutoCompactSignal(val usagePct: Long) : Exception("Auto-compact triggered at $usagePct%")

                fun computeBaseContextTokens(): Int {
                    return O200kTokenizer.computeContextWindowBreakdown(
                        session = finalSessionForCall.copy(messages = finalHistoryForCall),
                        assistant = assistantSnap,
                        profile = effectiveProfileForApi,
                        draftPrompt = "",
                        draftPrefill = "",
                        pendingAttachments = emptyList(),
                        goalModeActive = latestGoalTracker != null,
                        globalWorldBook = appState.globalWorldBook
                    ).totalUsedTokens
                }

                var cachedBaseTokens = computeBaseContextTokens()

                fun computeLiveTurnAndContextTokens(forceExact: Boolean = false): Int {
                    if (!forceExact) {
                        // Fast O(1) upper-bound check (~3 chars/token) so 1.5s mid-stream checks skip regex tokenization when well below threshold
                        val approxToolsChars = accumulatedTools.sumOf { 24 + it.name.length + it.argumentsJson.length + it.resultText.length }
                        val approxUpperBound = cachedBaseTokens + ((networkContentTarget.length + networkThinkingTarget.length + approxToolsChars) / 3)
                        if (approxUpperBound < (minTokensForNextAutoCompact * 85) / 100) {
                            return approxUpperBound
                        }
                    }
                    val liveAssistantTokens = O200kTokenizer.countTokens(networkContentTarget.toString()) +
                        O200kTokenizer.countTokens(networkThinkingTarget.toString()) +
                        accumulatedTools.sumOf { tc ->
                            6 + O200kTokenizer.countTokens(tc.name) +
                                O200kTokenizer.countTokens(tc.argumentsJson) +
                                O200kTokenizer.countTokens(tc.resultText)
                        }
                    return cachedBaseTokens + liveAssistantTokens
                }

                suspend fun executeAutoCompactionInPlace(usagePct: Long, isMidStream: Boolean): Boolean {
                    val plan = O200kTokenizer.prepareCompactionPlan(
                        existingStorySummary = finalSessionForCall.storySummary,
                        historyNodes = finalHistoryForCall,
                        keepRecentTurnsCount = 2,
                        liveTargetTools = accumulatedTools.toList(),
                        liveTargetThinking = networkThinkingTarget.toString()
                    )
                    if (!plan.didCompactAnything) return false

                    val statusPrefix = if (isMidStream) {
                        "Context reached $usagePct% (≥85%) — paused agent & auto-compacting into Continuity Memory…"
                    } else {
                        "Context at $usagePct% (≥85%) — auto-compacting older turns & tool outputs into Continuity Memory…"
                    }
                    updateOwnedStream { cur ->
                        cur?.copy(isThinkingPhase = false, statusText = statusPrefix, phase = AgentRunPhase.COMPACTING)
                    }

                    val dm = appState.defaultModels
                    val sumProf = if (!dm.summaryFollowGlobal && dm.summaryProfileId.isNotBlank()) {
                        appState.profiles.find { it.id == dm.summaryProfileId } ?: effectiveProfileForApi
                    } else effectiveProfileForApi
                    val sumModel = if (!dm.summaryFollowGlobal && dm.summaryModelId.isNotBlank()) {
                        dm.summaryModelId
                    } else modelName

                    val sumPrompt = "Update the comprehensive bulleted continuity summary with all key events, facts, files/tools inspected, decisions, and character/project states from the context below. Do not truncate or omit important details:\n\n${plan.transcriptToSummarize}"
                    val generatedSummary = withTimeoutOrNull(180_000L) {
                        generateTrackedUtility(sessionId, "compaction", sumProf, sumModel, sumPrompt, maxOutputTokens = null).getOrNull()
                    }?.takeIf { it.isNotBlank() } ?: plan.fallbackSummary.ifBlank { finalSessionForCall.storySummary }

                    finalSessionForCall = finalSessionForCall.copy(storySummary = generatedSummary)
                    finalHistoryForCall = plan.compactedHistoryNodes
                    accumulatedTools.clear()
                    accumulatedTools.addAll(plan.compactedLiveTools)
                    if (plan.compactedLiveThinking != networkThinkingTarget.toString()) {
                        networkThinkingTarget.clear()
                        networkThinkingTarget.append(plan.compactedLiveThinking)
                        displayedThinkingLen = displayedThinkingLen.coerceAtMost(networkThinkingTarget.length)
                    }
                    currentStepThinkingTarget.clear()
                    displayedStepThinkingLen = 0
                    localInteractionId = ""

                    val compactedHistoryById = plan.compactedHistoryNodes.associateBy { it.id }
                    val toolsSnap = accumulatedTools.toList()
                    val thinkSnap = networkThinkingTarget.toString()
                    updateSessionById(sessionId, immediate = true) { sess ->
                        sess.copy(
                            storySummary = generatedSummary,
                            messages = sess.messages.map { n ->
                                when {
                                    n.id == targetAssistantNodeId -> n.copy(
                                        variants = n.variants.map { v ->
                                            if (v.id == targetVariantId) {
                                                v.copy(
                                                    toolCalls = toolsSnap,
                                                    thinkingSummary = thinkSnap,
                                                    trailingThinking = "",
                                                    providerInteractionId = "",
                                                    pendingBackgroundInteractionId = ""
                                                )
                                            } else v
                                        }
                                    )
                                    compactedHistoryById.containsKey(n.id) -> compactedHistoryById[n.id]!!
                                    n.id in plan.mutedNodeIds -> n.copy(includedInContext = false)
                                    else -> n
                                }
                            }
                        )
                    }
                    cachedBaseTokens = computeBaseContextTokens()
                    val newTokens = computeLiveTurnAndContextTokens()
                    val newPct = if (effectiveCtxLimit > 0) ((newTokens.toLong() * 100L) / effectiveCtxLimit.toLong()) else 0L
                    minTokensForNextAutoCompact = maxOf(
                        autoCompactThresholdTokens,
                        newTokens + maxOf(1000, effectiveCtxLimit / 15)
                    )
                    updateOwnedStream { cur ->
                        cur?.copy(
                            statusText = "Context compacted ($usagePct% → $newPct%) — auto-resuming agent ($modelName)…",
                            interactionId = "",
                            phase = AgentRunPhase.RUNNING
                        )
                    }
                    return true
                }

                fun checkMidStreamAutoCompactAndThrowIfNeeded() {
                    if (!appState.preferences.autoCompactWhenNearContextLimit || effectiveCtxLimit <= 0) return
                    if (accumulatedTools.any { it.isRunning }) return
                    val liveTokens = computeLiveTurnAndContextTokens()
                    if (liveTokens >= minTokensForNextAutoCompact &&
                        O200kTokenizer.hasCompactableContext(
                            historyNodes = finalHistoryForCall,
                            keepRecentTurnsCount = 2,
                            liveTargetTools = accumulatedTools,
                            liveTargetThinking = networkThinkingTarget.toString()
                        )
                    ) {
                        val usagePct = ((liveTokens.toLong() * 100L) / effectiveCtxLimit.toLong()).coerceAtMost(999L)
                        throw MidStreamAutoCompactSignal(usagePct)
                    }
                }

                // Layer 1: Proactive Pre-Send Auto-Compaction when active context is already >= 85%
                val initialTokens = computeLiveTurnAndContextTokens()
                if (appState.preferences.autoCompactWhenNearContextLimit &&
                    effectiveCtxLimit > 0 &&
                    initialTokens >= minTokensForNextAutoCompact &&
                    O200kTokenizer.hasCompactableContext(
                        historyNodes = finalHistoryForCall,
                        keepRecentTurnsCount = 2,
                        liveTargetTools = accumulatedTools,
                        liveTargetThinking = networkThinkingTarget.toString()
                    )
                ) {
                    val usagePct = ((initialTokens.toLong() * 100L) / effectiveCtxLimit.toLong()).coerceAtMost(999L)
                    executeAutoCompactionInPlace(usagePct = usagePct, isMidStream = false)
                }

                // Layer 2: Real-Time Stream Loop with Mid-Stream >=85% Auto-Compaction & Instant Auto-Resume
                while (true) {
                    try {
                        AiProviderEngine.streamGeneration(
                            profile = effectiveProfileForApi,
                            session = finalSessionForCall.withProfileName(appState.preferences),
                            historyNodes = finalHistoryForCall,
                            continueFromAssistantPrefix = AiProviderEngine.stripInlineErrorNotices(networkContentTarget.toString()),
                            initialThinking = networkThinkingTarget.toString(),
                            initialSignature = signatureSb.toString(),
                            resumeMediaTask = existingTargetVariant?.pendingBackgroundInteractionId.orEmpty(),
                            spendLimitUsd = appState.preferences.chatSpendLimitUsd,
                            spendBeforeRunUsd = if (appState.preferences.chatSpendLimitUsd > 0.0) sessionSpendSummary(finalSessionForCall, effectiveProfileForApi).towardLimitUsd else 0.0,
                            initialToolCalls = accumulatedTools.toList(),
                            initialMediaItems = accumulatedMedia.toList(),
                            initialWebSources = accumulatedWebSources,
                            initialGoalTracker = latestGoalTracker,
                            initialElapsedMs = (activity.elapsedMs - turnRequestStartMs).coerceAtLeast(0L),
                            initialApiUsageRecords = accumulatedUsageRecords,
                            initialTokenMetrics = latestTokenMetrics
                        ).collect { update ->
                            when (update) {
                                is StreamUpdate.Status -> {
                                    if (update.interactionId.isNotBlank()) {
                                        localInteractionId = update.interactionId
                                        flushCheckpointToDisk(isFinal = false)
                                    }
                                    updateOwnedStream { cur ->
                                        cur?.copy(statusText = update.message, interactionId = localInteractionId)
                                    }
                                }
                                is StreamUpdate.ThinkingDelta -> {
                                    val now = activity.elapsedMs
                                    if (firstLiveTokenMs < 0L) firstLiveTokenMs = now
                                    if (activeThinkingPhaseStartMs < 0L) activeThinkingPhaseStartMs = now
                                    thinkingDurationMs = (accumulatedThinkingMs + (now - activeThinkingPhaseStartMs)).coerceAtLeast(100L)
                                    networkThinkingTarget.append(update.delta)
                                    currentStepThinkingTarget.append(update.delta)
                                    AiProviderEngine.appendSignatureChunkSafe(signatureSb, update.signatureDelta)
                                    val thinkWords = countWordsFast(networkThinkingTarget)
                                    val toolSuffix = if (accumulatedTools.isNotEmpty()) " • ${accumulatedTools.size} tools" else ""
                                    updateOwnedStream { cur ->
                                        cur?.copy(
                                            isThinkingPhase = true,
                                            statusText = "Reasoning ($modelName • $thinkWords words$toolSuffix • ${formatDurationFromMs(thinkingDurationMs)})…"
                                        )
                                    }
                                    if (now - lastMidStreamCompactCheckMs >= 1500L) {
                                        lastMidStreamCompactCheckMs = now
                                        checkMidStreamAutoCompactAndThrowIfNeeded()
                                    }
                                }
                                is StreamUpdate.ContentDelta -> {
                                    val now = activity.elapsedMs
                                    if (firstLiveTokenMs < 0L) firstLiveTokenMs = now
                                    pauseActiveThinkingStopwatch()
                                    networkContentTarget.append(update.delta)
                                    val wordCount = countWordsFast(networkContentTarget)
                                    updateOwnedStream { cur ->
                                        cur?.copy(
                                            isThinkingPhase = false,
                                            statusText = "Writing ($modelName • +$wordCount words)…"
                                        )
                                    }
                                    if (now - lastMidStreamCompactCheckMs >= 1500L) {
                                        lastMidStreamCompactCheckMs = now
                                        checkMidStreamAutoCompactAndThrowIfNeeded()
                                    }
                                }
                                is StreamUpdate.ToolCallEvent -> {
                                    val now = activity.elapsedMs
                                    // Keep the Reasoning + Tool stopwatch ticking continuously across tool calls!
                                    if (activeThinkingPhaseStartMs < 0L) activeThinkingPhaseStartMs = now
                                    thinkingDurationMs = (accumulatedThinkingMs + (now - activeThinkingPhaseStartMs)).coerceAtLeast(100L)
                                    val stepThinkFallback = currentStepThinkingTarget.toString().trim()
                                    val stepSigFallback = signatureSb.toString().trim()
                                    if (stepThinkFallback.isNotEmpty()) {
                                        currentStepThinkingTarget.clear()
                                        displayedStepThinkingLen = 0
                                    }
                                    if (stepSigFallback.isNotEmpty()) {
                                        signatureSb.clear()
                                    }
                                    val existingIdx = accumulatedTools.indexOfFirst {
                                        it.id == update.toolCall.id && it.id.isNotBlank()
                                    }
                                    val enrichedTool = if (existingIdx >= 0) {
                                        val prev = accumulatedTools[existingIdx]
                                        update.toolCall.copy(
                                            precedingThinking = update.toolCall.precedingThinking.ifBlank { prev.precedingThinking },
                                            thoughtSignature = update.toolCall.thoughtSignature.ifBlank { prev.thoughtSignature }
                                        )
                                    } else {
                                        update.toolCall.copy(
                                            precedingThinking = update.toolCall.precedingThinking.ifBlank { stepThinkFallback },
                                            thoughtSignature = update.toolCall.thoughtSignature.ifBlank { stepSigFallback }
                                        )
                                    }
                                    if (existingIdx >= 0) {
                                        accumulatedTools[existingIdx] = enrichedTool
                                    } else {
                                        accumulatedTools.add(enrichedTool)
                                    }
                                    val toolsSnap = accumulatedTools.toList()
                                    patchVariantMemory { v ->
                                        v.copy(
                                            toolCalls = toolsSnap,
                                            trailingThinking = "",
                                            thinkingDurationMs = thinkingDurationMs
                                        )
                                    }
                                    if (!enrichedTool.isRunning) {
                                        updateOwnedStream { cur ->
                                            cur?.copy(
                                                isThinkingPhase = true,
                                                statusText = "Executed tool: ${enrichedTool.name} (${formatDurationFromMs(thinkingDurationMs)})…"
                                            )
                                        }
                                        flushCheckpointToDisk(isFinal = false)
                                        checkMidStreamAutoCompactAndThrowIfNeeded()
                                    } else {
                                        updateOwnedStream { cur ->
                                            cur?.copy(isThinkingPhase = true)
                                        }
                                        // Saved before it runs, so a crash mid-change leaves a record instead of a blank.
                                        if (!WorkspaceHarness.isParallelSafeReadOnlyTool(enrichedTool.name)) flushCheckpointToDisk(isFinal = false)
                                    }
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
                                    updateOwnedStream { cur -> cur?.copy(statusText = goalMsg) }
                                    patchVariantMemory { v -> v.copy(goalTracker = update.goalTracker) }
                                    flushCheckpointToDisk(isFinal = false)
                                }
                                is StreamUpdate.MediaGenerated -> {
                                    val relocatedItem = if (update.item.localFilePath.isNotBlank()) {
                                        update.item.copy(localFilePath = PlatformBridge.relocateLegacyMediaToChatFolder(update.item.localFilePath, sessionId))
                                    } else update.item
                                    accumulatedMedia.add(relocatedItem)
                                    val mediaSnap = accumulatedMedia.toList()
                                    patchVariantMemory { v -> v.copy(mediaItems = mediaSnap) }
                                    updateOwnedStream { cur ->
                                        cur?.copy(statusText = "Generated ${relocatedItem.kind.name.lowercase()} (${accumulatedMedia.size})")
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
                                is StreamUpdate.UsageUpdated -> {
                                    updateOwnedStream { it?.copy(actualWireDialect = update.record.wireDialect) }
                                    val index = accumulatedUsageRecords.indexOfFirst { it.requestId == update.record.requestId }
                                    accumulatedUsageRecords = if (index < 0) accumulatedUsageRecords + update.record else {
                                        accumulatedUsageRecords.toMutableList().also { it[index] = update.record }
                                    }
                                    latestTokenMetrics = update.metrics.copy(durationMs = latestTokenMetrics.durationMs,
                                        ttftMs = latestTokenMetrics.ttftMs, tokensPerSec = latestTokenMetrics.tokensPerSec)
                                    patchVariantMemory { v -> v.copy(apiUsageRecords = accumulatedUsageRecords, tokenMetrics = latestTokenMetrics) }
                                }
                                is StreamUpdate.Completed -> {
                                    terminalPhase = if ((update.goalTracker ?: latestGoalTracker)?.let {
                                        it.goalObjective.isNotBlank() && !it.isCompleted
                                    } == true) AgentRunPhase.INTERRUPTED else AgentRunPhase.COMPLETED
                                    accumulatedUsageRecords = update.apiUsageRecords
                                    latestTokenMetrics = update.metrics
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
                                            providerProtocol = AiProviderEngine.resolveWireProtocol(effectiveProfileForApi),
                                            modelUsed = modelName,
                                            tokenMetrics = update.metrics,
                                            apiUsageRecords = update.apiUsageRecords,
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
                        break
                    } catch (compactSignal: MidStreamAutoCompactSignal) {
                        pauseActiveThinkingStopwatch()
                        flushCheckpointToDisk(isFinal = false)
                        val compacted = executeAutoCompactionInPlace(
                            usagePct = compactSignal.usagePct,
                            isMidStream = true
                        )
                        if (!compacted) {
                            minTokensForNextAutoCompact = Int.MAX_VALUE
                        }
                        continue
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                terminalPhase = if (activeStreamsBySession[sessionId]?.phase == AgentRunPhase.STOPPED) AgentRunPhase.STOPPED else AgentRunPhase.INTERRUPTED
                networkDone = true
                pacerJob.cancel()
                flushCheckpointToDisk(isFinal = true)
                throw c
            } catch (t: Throwable) {
                terminalPhase = AgentRunPhase.INTERRUPTED
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
                approvalObserver.cancel()
                networkDone = true
                if (activeStreamsBySession[sessionId]?.phase == AgentRunPhase.STOPPED) terminalPhase = AgentRunPhase.STOPPED
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { approvalBroker.close() }
                val finishedOwnRun = processState.runs.finish(identity, terminalPhase)

                // Auto-generate Topic Title after the first assistant turn completes with non-empty content
                val updatedSess = appState.sessions.find { it.id == sessionId }
                if (finishedOwnRun && terminalPhase == AgentRunPhase.COMPLETED && networkContentTarget.isNotBlank() && updatedSess != null && !updatedSess.isTitleManuallySet && updatedSess.title == "New Chat" && updatedSess.messages.size in 2..3) {
                    generateTopicTitle(sessionId, forceRegenerate = false)
                }

                // If there are queued prompts for this session (`⏳ Queue` mode), automatically dispatch the next one!
                val sessQueue = queuedPromptsBySession[sessionId].orEmpty()
                if (finishedOwnRun && terminalPhase == AgentRunPhase.COMPLETED && sessQueue.isNotEmpty()) {
                    val nextItem = sessQueue.first()
                    queuedPromptsBySession = queuedPromptsBySession + (sessionId to sessQueue.drop(1))
                    dispatchNextQueuedItem?.invoke(sessionId, nextItem)
                }
            }
        }

        processState.runs.attach(identity,
            SessionStreamRuntime(
                sessionId = sessionId,
                targetNodeId = targetAssistantNodeId,
                isThinkingPhase = false,
                statusText = "Connecting to ${effectiveProfileForApi.name} ($modelName)…",
                interactionId = "",
                job = newJob,
                toolApprovalBroker = approvalBroker,
                phase = AgentRunPhase.PREPARING,
                providerContext = RunProviderContext(effectiveProfileForApi),
                actualWireDialect = identity.declaredWireDialect
            ),
            cancelPrevious = false
        )
        owningJob = newJob
        newJob.start()
    }

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

    fun triggerAutoSummarizeStory(autoMuteOlderTurns: Boolean = false) {
        val complete = completeSessionForAction(activeSession) ?: return
        val targetSessId = complete.id
        if (complete.messages.isEmpty()) return
        val prevStream = activeStreamsBySession[targetSessId]
        val wasStreaming = prevStream != null
        val dm = appState.defaultModels
        val summaryProf = if (!dm.summaryFollowGlobal && dm.summaryProfileId.isNotBlank()) {
            appState.profiles.find { it.id == dm.summaryProfileId } ?: activeProfile
        } else activeProfile
        val summaryModel = if (!dm.summaryFollowGlobal && dm.summaryModelId.isNotBlank()) {
            dm.summaryModelId
        } else complete.modelOverride.ifBlank { summaryProf.defaultModel }

        val sumStatus = when {
            wasStreaming && autoMuteOlderTurns ->
                "Stopping agent — compacting context into Continuity Memory & auto-resuming ($summaryModel)…"
            wasStreaming ->
                "Stopping agent — summarizing continuity & auto-resuming ($summaryModel)…"
            autoMuteOlderTurns ->
                "Compacting context & summarizing continuity ($summaryModel)…"
            else ->
                "Summarizing story continuity into memory ($summaryModel)…"
        }

        val sumJob = appRunLeases.launch(appProcessScope, summaryModel) {
            var shouldResumeTurnIndex = -1
            try {
                if (prevStream != null) {
                    prevStream.job?.cancel()
                    if (prevStream.interactionId.isNotBlank() && (prevStream.providerContext?.profile ?: activeProfile).useBackgroundLongDecode) {
                        runCatching {
                            AiProviderEngine.cancelBackgroundInteraction(prevStream.providerContext?.profile ?: activeProfile, prevStream.interactionId)
                        }
                    }
                    prevStream.job?.join()
                }
                updateSessionStreamState(targetSessId) {
                    SessionStreamRuntime(sessionId = targetSessId, statusText = sumStatus, job = coroutineContext[kotlinx.coroutines.Job]!!)
                }

                val sessSnap = appState.sessions.find { it.id == targetSessId }?.let(::completeSessionForAction) ?: return@launch
                val keepTurnsCount = if (wasStreaming) 2 else 4
                val plan = O200kTokenizer.prepareCompactionPlan(
                    existingStorySummary = sessSnap.storySummary,
                    historyNodes = sessSnap.messages,
                    keepRecentTurnsCount = keepTurnsCount
                )
                val transcriptForPrompt = if (plan.transcriptToSummarize.isNotBlank()) {
                    plan.transcriptToSummarize
                } else {
                    buildString {
                        if (sessSnap.storySummary.isNotBlank()) {
                            appendLine("Existing Continuity Summary:")
                            appendLine(sessSnap.storySummary.trim())
                            appendLine()
                        }
                        appendLine("Conversation Turns:")
                        for (turn in sessSnap.messages.filter { it.includedInContext }.takeLast(24)) {
                            val roleLabel = if (turn.role == MessageRole.ASSISTANT) "Assistant" else "User"
                            val txt = AiProviderEngine.stripInlineErrorNotices(turn.activeVariant.content).trim().take(1600)
                            if (txt.isNotEmpty()) appendLine("$roleLabel: $txt")
                        }
                    }.trim()
                }
                val sumPrompt = "Update the comprehensive bulleted continuity summary with all key events, facts, files/tools inspected, decisions, and character/project states from the context below. Do not truncate or omit important details:\n\n$transcriptForPrompt"
                val generatedSummary = withTimeoutOrNull(180_000L) {
                    generateTrackedUtility(targetSessId, if (autoMuteOlderTurns) "compaction" else "summary", summaryProf, summaryModel, sumPrompt, maxOutputTokens = null).getOrNull()
                }?.takeIf { it.isNotBlank() } ?: plan.fallbackSummary.ifBlank { sessSnap.storySummary }

                updateSessionById(targetSessId, immediate = true) { sess ->
                    val updatedMsgs = if (autoMuteOlderTurns) {
                        val compactedById = plan.compactedHistoryNodes.associateBy { it.id }
                        sess.messages.map { n ->
                            compactedById[n.id] ?: if (n.id in plan.mutedNodeIds) n.copy(includedInContext = false) else n
                        }
                    } else {
                        sess.messages
                    }
                    sess.copy(
                        storySummary = generatedSummary,
                        messages = updatedMsgs
                    )
                }

                if (wasStreaming) {
                    val latestSess = appState.sessions.find { it.id == targetSessId }
                    shouldResumeTurnIndex = latestSess?.messages?.indexOfLast { it.role == MessageRole.ASSISTANT } ?: -1
                    bannerNotification = if (autoMuteOlderTurns) {
                        "Context Compacted into Memory — auto-resuming agent."
                    } else {
                        "Updated Continuity Memory — auto-resuming agent."
                    }
                } else {
                    bannerNotification = if (autoMuteOlderTurns) {
                        "Context Compacted: summarized into Memory & muted older turns/tool outputs."
                    } else {
                        "Updated Rolling Continuity Memory."
                    }
                }
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    bannerNotification = "Compaction error: ${e.message}"
                }
            } finally {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && shouldResumeTurnIndex >= 0) {
                    appProcessScope.launch {
                        continueAssistantNodeAt(shouldResumeTurnIndex)
                    }
                } else if (activeStreamsBySession[targetSessId]?.job === coroutineContext[kotlinx.coroutines.Job]) {
                    updateSessionStreamState(targetSessId) { null }
                }
            }
        }
        if (!wasStreaming) {
            updateSessionStreamState(targetSessId) {
                SessionStreamRuntime(sessionId = targetSessId, statusText = sumStatus, job = sumJob)
            }
        }
    }

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
                                content = "Custom Protocol has no wire dialect selected. Open Provider settings → Wire Dialect and choose one before sending.",
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
                        desktopWindowHeader()
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
                                                if (saved != null) bannerNotification = "Exported Chat Bundle (chat.json + media/) to $saved"
                                            } else {
                                                val fileName = buildExportFileNameForChat(activeSession)
                                                val bytes = CardAndPromptCodec.prettyJson.encodeToString(ChatSession.serializer(), activeSession).encodeToByteArray()
                                                val saved = PlatformBridge.saveFileForExport(fileName, bytes)
                                                if (saved != null) bannerNotification = "Exported Chat JSON to $saved"
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
                                    onContinueAssistantNode = ::continueAssistantNodeAt
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

// ============================================================================================
// MAIN MANUSCRIPT / CHAT VIEWPORT WITH FLOATING MINIMAP (`⤒`, `↑`, `🗺 Tree/Swipes`, `↓`, `⤓`)
// AND STEER / QUEUE COMMAND DECK COMPOSER
// ============================================================================================
/**
 * Advances to the next value in a preset list, wrapping at the end.
 * Falls back to [fallback] when [current] is not one of the presets, so a free-form value
 * typed in the Tools drawer still cycles predictably instead of sticking.
 */
internal fun <T> nextPreset(
    presets: List<T>,
    current: T,
    fallback: T = presets.first(),
    matches: (T, T) -> Boolean = { a, b -> a == b }
): T {
    val idx = presets.indexOfFirst { matches(it, current) }
    return if (idx >= 0) presets[(idx + 1) % presets.size] else fallback
}

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
    onContinueAssistantNode: (Int) -> Unit
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showMinimapTreeModal by remember { mutableStateOf(false) }
    var showContextBreakdownPopover by remember { mutableStateOf(false) }

    // Smart Bottom-Edge Auto-Follow with Sticky Manual Scroll-Up Disengage & Auto-Scroll on Chat Open:
    // 1. Scrolls to the bottom spacer (`totalItemsCount - 1`) with a large positive offset so long AI messages
    //    pin their BOTTOM edge to the bottom of the viewport instead of snapping to the TOP of the message!
    // 2. Immediately disengages auto-scroll when the user scrolls up manually and stays disengaged until the user
    //    scrolls back to the bottom or taps `↓ Latest` / `⤓` (never yanking the viewport away after 5s while reading!).
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
        if (activeSession.messages.isNotEmpty()) {
            if (preferences.autoScrollToBottomOnOpen) {
                jumpToBottomEdge()
                kotlinx.coroutines.delay(16L)
                jumpToBottomEdge()
            } else {
                listState.scrollToItem(0)
            }
        }
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

    // Bucket streaming content length into 128-char steps while streaming so 120fps VSync frames
    // never recompute full-history O200kTokenizer breakdowns on every single character!
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
        // Collapsible Chat Notes & Memory Drawer (Toggled via `✎ Notes` in the Top Bar — zero duplicate sub-header ribbon!)
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
                                text = if (isStreaming) "⚡ Compact & Resume" else "⚡ Compact Context",
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

        // Main Chat Area + Floating Message Navigation Minimap (`⤒`, `↑`, `🗺 Tree/Swipes`, `↓`, `⤓`)
        val win = LocalParchmentWindowSize.current
        val userPicture = remember(preferences.profilePicturePath) {
            preferences.profilePicturePath.takeIf { it.isNotBlank() }?.let(PlatformBridge::loadImageBitmapFromFile)
        }
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
            // Stacked vertically with `spacedBy(8.dp)` so the Floating Live Audio Pill NEVER overlaps the `↓ Latest` Pill!
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

                // High-Contrast Frosted-Glass Opaque `↓ Latest` Pill when scrolled up (zero background text bleed-through!)
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
        // ZEN-BY-DEFAULT FLOATING COMPOSER WITH DESKTOP ENTER-TO-SEND & STEER / QUEUE
        // ========================================================================================
        var customOverrideTarget by remember { mutableStateOf<String?>(null) }
        var customOverrideDraft by remember { mutableStateOf("") }
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
                // 1. Multi-Line Expandable Live Streaming Status Strip (Splits headline & detail onto clean newlines!)
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
                                        text = "⚡ Steer now",
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
                            BasicText("Manual override", style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold))
                            val drawerModelId = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }
                            val drawerModalityProfile = remember(
                                drawerModelId,
                                activeProfile.endpointPathOverride,
                                activeProfile.customModelSpecs
                            ) {
                                AiProviderEngine.resolveModelModalityProfile(activeProfile, drawerModelId)
                            }
                            val overrideTargets = buildList {
                                if (!drawerModalityProfile.isDedicatedImage &&
                                    !drawerModalityProfile.isDedicatedVideo &&
                                    !drawerModalityProfile.isDedicatedAudioTts
                                ) {
                                    add("reasoning" to "🧠 Reasoning")
                                }
                                if (drawerModalityProfile.isAnyImageModel || drawerModalityProfile.isAnyVideoModel) {
                                    add("aspect" to "🖼 Aspect Ratio")
                                    add("resolution" to "🖼 Resolution")
                                }
                                if (drawerModalityProfile.isAnyVideoModel) {
                                    add("duration" to "🎬 Duration")
                                }
                                if (drawerModalityProfile.isAnyAudioModel) {
                                    add("voice" to "🎵 Voice")
                                    if (drawerModalityProfile.isDedicatedAudioTts) {
                                        add("speed" to "🎵 Speed")
                                    }
                                }
                            }
                            val overrideTarget = customOverrideTarget
                                ?.takeIf { key -> overrideTargets.any { it.first == key } }
                                ?: overrideTargets.firstOrNull()?.first
                            if (overrideTarget != null) {
                                val overrideCurrentValue = when (overrideTarget) {
                                    "aspect" -> activeProfile.mediaAspectRatio
                                    "resolution" -> activeProfile.mediaResolution
                                    "duration" -> activeProfile.videoDurationSeconds.toString()
                                    "voice" -> activeProfile.audioVoice
                                    "speed" -> activeProfile.audioSpeed.toString()
                                    else -> activeProfile.thinkingEffort
                                }
                                LaunchedEffect(overrideTarget) {
                                    customOverrideDraft = overrideCurrentValue
                                }
                                val overridePlaceholder = when (overrideTarget) {
                                    "aspect" -> "Aspect ratio (e.g. 21:9, 4:5)"
                                    "duration" -> "Duration in seconds (4–15)"
                                    "voice" -> "Voice id (e.g. alloy, nova, Puck)"
                                    "speed" -> "Speed multiplier (0.25–4.0)"
                                    "resolution" -> "Resolution (512, 1K, 2K, 4K)"
                                    else -> "Reasoning effort (e.g. high, xhigh, adaptive)"
                                }
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    overrideTargets.forEach { (key, label) ->
                                        SepiaChip(
                                            label = label,
                                            selected = overrideTarget == key,
                                            onClick = { customOverrideTarget = key }
                                        )
                                    }
                                }
                                SepiaTextField(
                                    value = customOverrideDraft,
                                    onValueChange = { raw ->
                                        customOverrideDraft = raw
                                        val typed = raw.trim()
                                        when (overrideTarget) {
                                            "aspect" -> onUpdateProfile { it.copy(mediaAspectRatio = typed) }
                                            "resolution" -> onUpdateProfile { it.copy(mediaResolution = typed) }
                                            "duration" -> typed.toIntOrNull()?.let { secs ->
                                                onUpdateProfile { it.copy(videoDurationSeconds = secs.coerceIn(4, 15)) }
                                            }
                                            "voice" -> onUpdateProfile { it.copy(audioVoice = typed) }
                                            "speed" -> typed.toDoubleOrNull()?.let { rate ->
                                                onUpdateProfile { it.copy(audioSpeed = rate.coerceIn(0.25, 4.0)) }
                                            }
                                            else -> onUpdateProfile { it.copy(thinkingEffort = typed) }
                                        }
                                    },
                                    placeholder = overridePlaceholder,
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                SepiaDivider()
                            }
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
                                    label = if (showPrefillBox) "▾ Prefill Reply: ON" else "▸ Prefill Reply…",
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
                            val thumbBmp = remember(att.localFilePath, isImg) {
                                if (isImg && att.localFilePath.isNotBlank()) {
                                    PlatformBridge.loadImageBitmapFromFile(att.localFilePath)
                                } else null
                            }

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
                                    "Mount a local workspace folder for Agent Harness tools…"
                                } else {
                                    "Change mounted workspace folder ($effectiveWorkspacePath)…"
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
                val imageAspectPresets = listOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "21:9")
                val videoAspectPresets = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")
                val imageResolutionPresets = listOf("1K", "2K", "4K")
                val videoResolutionPresets = listOf("720p", "1080p", "4K")
                val videoDurationPresets = listOf(5, 8, 10, 15)
                val audioVoicePresets = listOf("alloy", "nova", "echo", "onyx", "shimmer", "coral", "sage", "Puck", "Kore", "Fenrir", "Aoede")
                val audioSpeedPresets = listOf(1.0, 1.25, 1.5, 0.85)

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
                            val currentRatio = activeProfile.mediaAspectRatio.ifBlank { "1:1" }
                            val currentRes = activeProfile.mediaResolution.ifBlank { "1K" }
                            SepiaChip(
                                label = "🖼 $currentRatio",
                                selected = true,
                                onClick = {
                                    val next = nextPreset(imageAspectPresets, currentRatio)
                                    onUpdateProfile { it.copy(mediaAspectRatio = next) }
                                }
                            )
                            SepiaChip(
                                label = currentRes,
                                selected = currentRes in listOf("2K", "4K"),
                                onClick = {
                                    val next = nextPreset(imageResolutionPresets, currentRes)
                                    onUpdateProfile { it.copy(mediaResolution = next) }
                                }
                            )
                        }
                        if (activeModalityProfile.isAnyVideoModel) {
                            val currentRatio = activeProfile.mediaAspectRatio.ifBlank { "16:9" }
                            val currentRes = when (activeProfile.mediaResolution) {
                                "720p", "1080p", "4K" -> activeProfile.mediaResolution
                                "2K" -> "1080p"
                                else -> "720p"
                            }
                            val currentDur = activeProfile.videoDurationSeconds.coerceIn(4, 15)
                            SepiaChip(
                                label = "🎬 $currentRatio",
                                selected = true,
                                onClick = {
                                    val next = nextPreset(videoAspectPresets, currentRatio)
                                    onUpdateProfile { it.copy(mediaAspectRatio = next) }
                                }
                            )
                            SepiaChip(
                                label = currentRes,
                                selected = currentRes in listOf("1080p", "4K"),
                                onClick = {
                                    val next = nextPreset(videoResolutionPresets, currentRes)
                                    onUpdateProfile { it.copy(mediaResolution = next) }
                                }
                            )
                            SepiaChip(
                                label = "${currentDur}s",
                                selected = currentDur > 5,
                                onClick = {
                                    val next = nextPreset(videoDurationPresets, currentDur)
                                    onUpdateProfile { it.copy(videoDurationSeconds = next) }
                                }
                            )
                            SepiaChip(
                                label = if (activeProfile.videoGenerateAudio) "🔊 Audio" else "🔇 Mute",
                                selected = activeProfile.videoGenerateAudio,
                                onClick = {
                                    onUpdateProfile { it.copy(videoGenerateAudio = !it.videoGenerateAudio) }
                                }
                            )
                        }
                        if (activeModalityProfile.isAnyAudioModel) {
                            val currentVoice = activeProfile.audioVoice.ifBlank { "alloy" }
                            SepiaChip(
                                label = "🗣 $currentVoice",
                                selected = true,
                                onClick = {
                                    val next = nextPreset(
                                        audioVoicePresets,
                                        currentVoice,
                                        matches = { a, b -> a.equals(b, ignoreCase = true) }
                                    )
                                    onUpdateProfile { it.copy(audioVoice = next) }
                                }
                            )
                            if (activeModalityProfile.isDedicatedAudioTts) {
                                val currentSpeed = activeProfile.audioSpeed
                                SepiaChip(
                                    label = "${currentSpeed}x",
                                    selected = currentSpeed != 1.0,
                                    onClick = {
                                        val next = nextPreset(audioSpeedPresets, currentSpeed)
                                        onUpdateProfile { it.copy(audioSpeed = next) }
                                    }
                                )
                            }
                        }
                        if (!activeModalityProfile.isDedicatedImage &&
                            !activeModalityProfile.isDedicatedVideo &&
                            !activeModalityProfile.isDedicatedAudioTts
                        ) {
                            SepiaChip(
                                label = thinkingLabel,
                                hint = "How much the model reasons before it answers. Click to change.",
                                selected = activeProfile.thinkingEffort.isNotBlank() && !activeProfile.thinkingEffort.equals("none", ignoreCase = true),
                                onClick = {
                                    val next = nextPreset(THINKING_EFFORT_PRESETS, activeProfile.thinkingEffort.lowercase(), "medium")
                                    onUpdateProfile { it.copy(thinkingEffort = next) }
                                }
                            )
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
                                    text = "⚡ Steer ↵",
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
                    // Single full-width SpaceBetween row on both Mobile (idle) and Desktop!
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
                                    text = "⚡ Steer ↵",
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

// ============================================================================================
// MESSAGE TURN ITEM WITH:
// - Auto-Opening Live Markdown/LaTeX Reasoning Box while Thinking -> Auto-Collapse when Done
// - 1-Tap `📋 Copy` (Plain Text) & `📋 MD` (Markdown) Buttons
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
private fun MessageTurnItem(
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
                        hint = "Show or hide the reasoning and tool steps",
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
                                append(if (livePeekInfo.isToolPeek) "Latest Tool" else "Latest Thought")
                            } else if (isActivelyThinkingThisNode) {
                                append(if (livePeekInfo.isToolPeek) "Running Tools & Reasoning" else "Thinking")
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
                        contentDescription = if (isLivePeekOnly) "Expand reasoning" else "Collapse reasoning",
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
                    visibleTools.forEach { tc ->
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
                            text = "Reasoning signature saved",
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
                            val bmp = remember(media.localFilePath) {
                                PlatformBridge.loadImageBitmapFromFile(media.localFilePath)
                            }
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
                                            text = "${formatCompactTokens(m.cachedInputTokens)} cache read • ${formatCompactTokens(m.cacheCreationInputTokens)} cache write",
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
                                        text = "Reasoning included in output", style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", color = p.mutedInk)
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
                        BasicText(text = "Outgoing request payload:", style = p.uiLabelStyle.copy(fontWeight = FontWeight.Bold))
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
                            BasicText(text = "Latest wire event / response:", style = p.uiLabelStyle.copy(fontWeight = FontWeight.Bold))
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
