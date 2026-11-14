package io.parchment.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import io.parchment.ai.network.cancelBackgroundInteraction
import io.parchment.ai.network.stripInlineErrorNotices
import io.parchment.ai.platform.MAX_INLINE_ATTACHMENT_BYTES
import io.parchment.ai.platform.MAX_INLINE_TEXT_ATTACHMENT_BYTES
import io.parchment.ai.strings.Strings
import io.parchment.ai.platform.inlineAttachmentText
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.AgentPermissionOverrides
import io.parchment.ai.model.withLegacyWorkspaceChangeFrom
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.OtaReleaseInfo
import io.parchment.ai.network.OtaUpdateEngine
import io.parchment.ai.network.OtaUpdateState
import io.parchment.ai.network.StreamUpdate
import io.parchment.ai.network.updateBlockedReason
import io.parchment.ai.model.ApiUsageRecord
import io.parchment.ai.platform.PickedBinaryFile
import io.parchment.ai.platform.PlatformRuntime
import io.parchment.ai.platform.PlatformStorage
import io.parchment.ai.platform.PlatformVoice
import io.parchment.ai.platform.normalizePickedBinaryFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.ExperimentalEncodingApi
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CoroutineScope

/**
 * The app's state and actions. ParchmentApp keeps the layout and the effects; each frame it copies in the values it
 * derives (the active chat, stream info, drafts kept per chat), which the actions read as they did when they were
 * local functions of the composable.
 */
@OptIn(ExperimentalEncodingApi::class)
internal class ParchmentAppController(
    val processState: ParchmentProcessState,
    val uiScope: CoroutineScope
) {
    lateinit var showComposerToolsDrawerState: MutableState<Boolean>
    var showComposerToolsDrawer: Boolean
        get() = showComposerToolsDrawerState.value
        set(value) { showComposerToolsDrawerState.value = value }
    lateinit var activeProfile: ProviderProfile
    lateinit var activeSession: ChatSession
    lateinit var activeAssistant: AssistantAgent
    var currentTabStream: SessionStreamRuntime? = null
    var isStreaming: Boolean = false
    var isCurrentlyThinkingPhase: Boolean = false
    var streamingTargetNodeId: String = ""
    var streamingStatus: String = ""
    var streamingInteractionId: String = ""
    lateinit var queuedPrompts: List<QueuedPromptItem>
    lateinit var openTabIds: List<String>
    lateinit var userPromptInputState: MutableState<String>
    var userPromptInput: String
        get() = userPromptInputState.value
        set(value) { userPromptInputState.value = value }
    lateinit var assistantPrefillInputState: MutableState<String>
    var assistantPrefillInput: String
        get() = assistantPrefillInputState.value
        set(value) { assistantPrefillInputState.value = value }
    lateinit var pendingAttachments: List<PromptAttachment>
    var currentEffectiveModel: String = ""
    lateinit var generationHost: GenerationHost
    lateinit var summaryHost: StorySummaryHost

    var appState by processState.snapshot

    var needsBackgroundChatHydration by processState.needsBackgroundHydration

    var isSettingsOpen by mutableStateOf(false)

    var activeSettingsCategory by mutableStateOf(SettingsCategory.PREFERENCES)

    var startMobileSettingsAtRoot by mutableStateOf(true)

    var showSidebar by mutableStateOf(!PlatformRuntime.isAndroid && appState.preferences.sidebarOpen)

    var sidebarTab by mutableStateOf(SidebarTab.TOPICS)

    var pendingPinJump by mutableStateOf<PinnedReply?>(null)

    var showSessionInspector by mutableStateOf(false)

    var showRightFilesSidebar by mutableStateOf(false)

    var showQuickModelPopover by mutableStateOf(false)

    var showMobileTabSwitcher by mutableStateOf(false)

    var bannerNotification by processState.notice

    // Chats just deleted, kept until the notice offering Undo goes away.
    var pendingUndo by mutableStateOf<DeletedChats?>(null)

    var showCommandPalette by mutableStateOf(false)

    // OTA Update state
    var otaState by mutableStateOf<OtaUpdateState>(OtaUpdateState.Idle)

    var dismissOtaBanner by mutableStateOf(false)

    // Stream and queue state per chat, so several tabs can run at once.
    var activeStreamsBySession by processState.runs.streams

    var queuedPromptsBySession by processState.queuedPrompts

    var showPrefillBox by mutableStateOf(false)

    var isGoalModeEnabled by mutableStateOf(false)

    var isLiveVoiceMode by mutableStateOf(false)

    var isVoiceListening by mutableStateOf(false)

    var pendingAttachmentsBySession by processState.pendingAttachments

    var pendingDeleteConfirmation by mutableStateOf<DeleteConfirmRequest?>(null)

    var pendingRegenerateConfirm by mutableStateOf<RegenerateConfirmRequest?>(null)

    var activeViewerItem by mutableStateOf<ActiveMediaViewerItem?>(null)

    var isDraggingFilesOverWindow by mutableStateOf(false)

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
                    PlatformStorage.openOrPlayMedia(att.localFilePath)
                } else {
                    bannerNotification = Strings.filePathIsUnavailableFor(att.fileName)
                }
            }
            isRasterImage -> {
                activeViewerItem = ActiveMediaViewerItem.ImageViewer(
                    title = att.fileName.ifBlank { Strings.imageAttachment },
                    mimeType = att.mimeType.ifBlank { "image/png" },
                    localFilePath = att.localFilePath
                )
            }
            isSvg || att.textContent.isNotBlank() || mime.startsWith("text/") || mime == "application/json" -> {
                val content = att.textContent.ifBlank {
                    if (PlatformStorage.localFileSizeBytes(att.localFilePath) > MAX_TEXT_PREVIEW_BYTES) {
                        bannerNotification = Strings.isTooLargeToPreviewHere(att.fileName)
                        return
                    }
                    PlatformStorage.readBinaryFileBytes(att.localFilePath)?.let {
                        runCatching { it.decodeToString() }.getOrNull()
                    }.orEmpty()
                }
                activeViewerItem = ActiveMediaViewerItem.TextOrCodeViewer(
                    title = att.fileName.ifBlank { Strings.textAttachment },
                    mimeType = if (isSvg) "image/svg+xml" else att.mimeType.ifBlank { "text/plain" },
                    textContent = content,
                    localFilePath = att.localFilePath
                )
            }
            att.localFilePath.isNotBlank() -> {
                PlatformStorage.openOrPlayMedia(att.localFilePath)
            }
        }
    }

    fun attachPickedFiles(files: List<PickedBinaryFile>) {
        val (oversized, accepted) = files.partition { it.oversized }
        if (oversized.isNotEmpty()) {
            val limitMb = MAX_INLINE_ATTACHMENT_BYTES / (1024L * 1024L)
            bannerNotification = Strings.tooLargeToAttachLimitMb(limitMb, oversized.joinToString { it.fileName })
        }
        if (accepted.isEmpty()) return
        val newAtts = accepted.mapIndexed { idx, rawPicked ->
            val now = PlatformRuntime.currentTimeMillis() + idx
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
                    val savedPath = PlatformStorage.saveGeneratedMediaFile("att-$now-${picked.fileName}", picked.bytes, sessionId = activeSession.id)
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
                    val savedPath = PlatformStorage.saveGeneratedMediaFile("att-$now-${picked.fileName}", picked.bytes, sessionId = activeSession.id)
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
                    val savedPath = PlatformStorage.saveGeneratedMediaFile("att-$now-${picked.fileName}", picked.bytes, sessionId = activeSession.id)
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
                    val savedPath = PlatformStorage.saveGeneratedMediaFile("att-$now-${picked.fileName}", picked.bytes, sessionId = activeSession.id)
                    if (inlineText != null) {
                        PromptAttachment(
                            id = "att-$now",
                            fileName = picked.fileName,
                            mimeType = "text/plain",
                            textContent = inlineText,
                            localFilePath = savedPath
                        )
                    } else {
                        val fileKind = if (picked.bytes.size > MAX_INLINE_TEXT_ATTACHMENT_BYTES) Strings.largeFile else Strings.binaryFile
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
            bannerNotification = Strings.unableToLoadChatHistory + error.message.orEmpty()
            return null
        }
        if (complete.isCompactStub) {
            bannerNotification = Strings.chatHistoryIsUnavailableRestoreIts
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
                val mod = transform(complete).withLegacyWorkspaceChangeFrom(complete).copy(updatedAt = PlatformRuntime.currentTimeMillis())
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
                val mod = if (touch) changed.copy(updatedAt = PlatformRuntime.currentTimeMillis()) else changed
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
                    otaState = OtaUpdateState.Error(err.message ?: Strings.failedToCheckGithubReleases)
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
        PlatformRuntime.flushPendingStateNow()
        appProcessScope.launch {
            OtaUpdateEngine.downloadAndInstall(release, ::currentUpdateBlock).collect { st ->
                otaState = st
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
        val runId = kotlinx.coroutines.currentCoroutineContext()[AgentToolRunContext]?.broker?.runId ?: PlatformRuntime.createRunId()
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
                if (forceRegenerate) bannerNotification = Strings.chatHistoryIsUnavailableForTitle
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
                        bannerNotification = Strings.titleGenerationFailed(err.message)
                    }
                }
            )
        }
    }

    /**
     * Core Generation Dispatcher with:
     * - Concurrent Per-Session Multi-Tasking (multiple tabs run simultaneously in background)
     * - Reveals text once per display frame (`withFrameNanos`)
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
            bannerNotification = Strings.theRequestedTurnIsNoLonger
            return
        }
        val profile = appState.profiles.find { it.id == requestedSession.profileId } ?: activeProfile
        val model = requestedSession.modelOverride.ifBlank { profile.defaultModel }
        val identity = AgentRunIdentity(PlatformRuntime.createRunId(), sessionId, targetAssistantNodeId, targetVariantId,
            profile.id, model, if (profile.protocol == ProviderProtocol.CUSTOM) profile.customDialect
            else io.parchment.ai.model.mapProtocolToDialect(profile.protocol.name))
        val persistedIds = requestedSession.messages.map { it.id }.toSet()
        processState.runs.prepare(identity, SessionStreamRuntime(sessionId,
            targetNodeId = targetAssistantNodeId, statusText = Strings.preparingGeneration, providerContext = RunProviderContext(profile)), appProcessScope) { previous ->
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
        val now = PlatformRuntime.currentTimeMillis()
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

        // A chat title the user set by hand is never replaced by the first message.
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

    fun continueAssistantNodeAt(nodeIndex: Int) {
        val complete = completeSessionForAction(activeSession) ?: return
        val targetNode = complete.messages.getOrNull(nodeIndex) ?: return
        if (targetNode.role != MessageRole.ASSISTANT) return
        val historyBefore = complete.messages.subList(0, nodeIndex)
        val existingText = AiProviderEngine.stripInlineErrorNotices(targetNode.activeVariant.content)
        // Clear the inline error at once so the resumed reply starts clean.
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
            val noticeId = "sys-${PlatformRuntime.currentTimeMillis()}"
            updateActiveSession { sess ->
                sess.copy(
                    messages = sess.messages + MessageNode(
                        id = noticeId,
                        role = MessageRole.ASSISTANT,
                        variants = listOf(
                            MessageVariant(
                                id = "$noticeId-v",
                                content = Strings.chooseAnApiFormatInProvider,
                                modelUsed = "system",
                                createdAt = PlatformRuntime.currentTimeMillis()
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

        // `/continue` extends the last assistant reply.
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
            bannerNotification = Strings.clearedMessagesIn(activeSession.title)
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
                bannerNotification = Strings.rewoundLast(plural(dropCount, "message"))
            }
            return
        }

        // `/btw <question>` asks a side question; once answered it is left out of later context.
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
            val now = PlatformRuntime.currentTimeMillis()
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
     * appends the user's new follow-up prompt, and starts generating from the new prompt.
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
            id = "q-${PlatformRuntime.currentTimeMillis()}",
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
        val now = PlatformRuntime.currentTimeMillis()
        val draftProfile = (appState.profiles.find { it.id == complete.profileId } ?: activeProfile).copy(
            enableWebSearch = false, enableUrlContext = false, enableCodeExecution = false,
            enableImageGeneration = false, enableVideoGeneration = false, useBackgroundLongDecode = false)
        val draftRunId = PlatformRuntime.createRunId()
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
                            bannerNotification = Strings.draftError(update.message)
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
            SessionStreamRuntime(sessionId = targetSessId, statusText = Strings.draftingSTurn(activeSession.userName), job = impJob,
                providerContext = RunProviderContext(draftProfile))
        }
    }

    /**
     * Regenerates from either kind of turn:
     * 1. A user turn: deletes the turns below it, adds a new assistant turn and streams a new reply.
     * 2. An assistant turn: follows `deleteMessagesBelowWhenRegenerating` (drop later turns) and
     *    `deletePreviousMessageWhenRegenerating` (replace only the active version, keeping the others).
     */
    fun executeRegenerateFromNode(nodeIndex: Int, sessionId: String = activeSession.id) {
        val curSnap = appState
        val sess = curSnap.sessions.find { it.id == sessionId }?.let(::completeSessionForAction) ?: return
        val targetNode = sess.messages.getOrNull(nodeIndex) ?: return

        // Cancel any active stream on this session before mutating the message list
        activeStreamsBySession[sess.id]?.job?.cancel()
        updateSessionStreamState(sess.id) { null }
        val now = PlatformRuntime.currentTimeMillis()
        val curProf = curSnap.profiles.find { it.id == sess.profileId } ?: curSnap.profiles.first()
        val modelUsed = sess.modelOverride.ifBlank { curProf.defaultModel }

        if (targetNode.role == MessageRole.USER) {
            // Regenerating from a user message always deletes the messages below it.
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
                // Replace only the active version in place; the other versions stay.
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
            bannerNotification = Strings.selectAModelFirstToTranslate
            return
        }
        bannerNotification = Strings.translatingTurnTo(nodeIndex + 1, targetLang, transModel)
        appProcessScope.launch {
            val prompt = "Translate the following passage accurately and naturally into $targetLang. Preserve all Markdown formatting, code blocks, and tone. Return ONLY the translated text without introductory commentary:\n\n$sourceText"
            generateTrackedUtility(sess.id, "translation", transProf, transModel, prompt).fold(
                onSuccess = { translated ->
                    if (translated.isNotBlank()) {
                        val now = PlatformRuntime.currentTimeMillis()
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
                        bannerNotification = Strings.translatedTurnToAddedAsSwipe(nodeIndex + 1, targetLang)
                    }
                },
                onFailure = { err ->
                    bannerNotification = Strings.translationFailed(err.message)
                }
            )
        }
    }

    fun triggerVoiceInput() {
        if (isVoiceListening) {
            PlatformVoice.stopVoiceInput()
            isVoiceListening = false
            return
        }
        PlatformVoice.stopSpeakingAloud()
        isVoiceListening = true
        bannerNotification = Strings.listeningSpeakNow
        PlatformVoice.startVoiceInput(
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
        // Reuse an open, untouched "New Chat" tab for this assistant instead of adding another blank one.
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
        emptyDropSet.forEach { PlatformStorage.deleteChatSessionFile(it) }
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

    /**
     * Keeps what is needed to bring chats back before they are deleted, so the notice can offer Undo instead of
     * a confirm dialog. Chats in memory are saved as they are; stored ones are read from disk first.
     */
    fun rememberForUndo(removed: List<ChatSession>, message: String) {
        pendingUndo = DeletedChats(
            chats = removed.map { s ->
                s to (if (s.isCompactStub) PlatformStorage.loadChatSessionJsonById(s.id) else compactStateJson.encodeToString(ChatSession.serializer(), s))
            },
            activeSessionId = appState.activeSessionId,
            openTabIds = openTabIds
        )
        bannerNotification = message
    }

    fun undoDelete() {
        val undo = pendingUndo ?: return
        pendingUndo = null
        undo.chats.forEach { (session, json) -> if (json != null) PlatformStorage.saveChatSessionJson(session.id, json) }
        val restoredIds = undo.chats.map { it.first.id }.toSet()
        val sessions = undo.chats.map { it.first } + appState.sessions.filterNot { it.id in restoredIds }
        val existing = sessions.map { it.id }.toSet()
        persist(
            appState.copy(
                sessions = sessions,
                activeSessionId = undo.activeSessionId.takeIf { it in existing } ?: appState.activeSessionId,
                openTabSessionIds = undo.openTabIds.filter { it in existing }.ifEmpty { appState.openTabSessionIds }
            ),
            immediateFlush = true
        )
        bannerNotification = if (undo.chats.size == 1) "Restored the chat." else "Restored ${undo.chats.size} chats."
    }

    fun executeDeleteSingleSession(targetId: String) {
        appState.sessions.find { it.id == targetId }?.let { rememberForUndo(listOf(it), "Deleted \"${it.title}\".") }
        activeStreamsBySession[targetId]?.job?.cancel()
        updateSessionStreamState(targetId) { null }
        queuedPromptsBySession = queuedPromptsBySession - targetId
        pendingAttachmentsBySession = pendingAttachmentsBySession - targetId
        PlatformStorage.deleteChatSessionFile(targetId)
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
        rememberForUndo(removedSessions, Strings.deletedFor(plural(removedSessions.size, "chat"), activeAssistant.name))
        removedIds.forEach { id ->
            activeStreamsBySession[id]?.job?.cancel()
            PlatformStorage.deleteChatSessionFile(id)
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
    }
}
