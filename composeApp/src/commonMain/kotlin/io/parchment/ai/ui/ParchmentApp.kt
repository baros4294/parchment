package io.parchment.ai.ui

import androidx.compose.ui.draw.alpha
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
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
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.network.cancelBackgroundInteraction
import io.parchment.ai.network.fetchAvailableModels
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.withImportedPermissions
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.ModelsDevClient
import io.parchment.ai.ParchmentBuildConfig
import io.parchment.ai.network.OtaUpdateEngine
import io.parchment.ai.network.OtaUpdateState
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.protocol.O200kTokenizer
import io.parchment.ai.protocol.CardAndPromptCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.ExperimentalEncodingApi


private const val STARTUP_UPDATE_CHECK_DELAY_MS = 8_000L

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun ParchmentApp(
    /** Desktop window hook; it receives what the window caption should name: the open chat or Settings. */
    desktopWindowHeader: (@Composable (captionTitle: String) -> Unit)? = null,
    /** Desktop window hook; the window sends it key events that nothing focused consumed, so shortcuts always work. */
    shortcutDispatcher: AppShortcutDispatcher? = null
) {
    val uiScope = rememberCoroutineScope()
    val processState = parchmentProcessState
    val app = remember { ParchmentAppController(processState, uiScope) }
    with(app) {
        showComposerToolsDrawerState = remember(appState.activeSessionId) { mutableStateOf(false) }
        DisposableEffect(uiScope) {
            val clock = uiScope.coroutineContext[MonotonicFrameClock]
            processState.attachClock(clock)
            onDispose { processState.detachClock(clock) }
        }

        activeProfile = remember(appState.profiles, appState.activeProfileId) {
            appState.profiles.find { it.id == appState.activeProfileId }
                ?: appState.profiles.first()
        }
        activeSession = remember(appState.sessions, appState.activeSessionId) {
            appState.sessions.find { it.id == appState.activeSessionId }
                ?: appState.sessions.first()
        }
        activeAssistant = remember(appState.assistants, activeSession.assistantId, appState.activeAssistantId) {
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

        // Load the chat being switched to in full and shrink inactive ones back to stubs, to keep memory low.
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
                    bannerNotification = Strings.chatHistoryIsUnavailableRestoreIts
                }
            } else {
                val rebalanced = ensureActiveSessionHydratedAndDehydrateOthers(current, activeStreamsBySession.keys)
                if (rebalanced !== current) {
                    appState = rebalanced
                }
            }
        }

        // Active tab's stream state
        currentTabStream = activeStreamsBySession[activeSession.id]
        isStreaming = currentTabStream != null
        isCurrentlyThinkingPhase = currentTabStream?.isThinkingPhase == true
        streamingTargetNodeId = currentTabStream?.targetNodeId.orEmpty()
        streamingStatus = currentTabStream?.statusText.orEmpty()
        streamingInteractionId = currentTabStream?.interactionId.orEmpty()
        queuedPrompts = queuedPromptsBySession[activeSession.id].orEmpty()

        // Open Browser Tabs list (always includes activeSession.id)
        openTabIds = remember(appState.openTabSessionIds, appState.sessions, activeSession.id) {
            val validSaved = appState.openTabSessionIds.filter { id -> appState.sessions.any { it.id == id } }
            normalizeOpenTabs(validSaved, activeSession.id)
        }

        // Composer text and staged attachments per chat, so switching tabs never moves or loses files.
        userPromptInputState = remember(activeSession.id) { mutableStateOf(activeSession.draftInput) }
        assistantPrefillInputState = remember(activeSession.id) { mutableStateOf(activeSession.draftPrefill) }
        pendingAttachments = pendingAttachmentsBySession[activeSession.id].orEmpty()
        LaunchedEffect(Unit) {
            PlatformBridge.registerFileDropCallbacks(
                onDragHover = { hovering -> isDraggingFilesOverWindow = hovering },
                onFilesDropped = { dropped ->
                    uiScope.launch {
                        attachPickedFiles(dropped)
                        if (dropped.none { it.oversized }) {
                            bannerNotification = Strings.attached(plural(dropped.size, "item"), dropped.joinToString { it.fileName })
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
                val notice = message?.takeIf { it.isNotBlank() }?.let { Strings.unableToSaveChanges(it) }.orEmpty()
                if (notice.isNotEmpty()) bannerNotification = notice
                else if (bannerNotification == previousSaveNotice) bannerNotification = ""
                previousSaveNotice = notice
            }
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

        // Fetch models automatically only when none are saved, and never replace the user's chosen default model.
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
        currentEffectiveModel = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }.trim()
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

        generationHost = GenerationHost(
            processState, activeAssistant, activeProfile, { isGoalModeEnabled }, { isLiveVoiceMode }, { dispatchNextQueuedItem },
            ::updateSessionById, ::updateActiveSessionInMemoryOnly, ::generateTopicTitle
        ) { id, purpose, profile, model, prompt, limit -> generateTrackedUtility(id, purpose, profile, model, prompt, limit) }

        dispatchNextQueuedItem = { sessId, nextItem ->
            dispatchPromptItem(
                sessionId = sessId,
                text = nextItem.text,
                prefill = nextItem.prefill,
                attachments = nextItem.attachments,
                isGuidedDirection = nextItem.isGuidedDirection
            )
        }

        summaryHost = StorySummaryHost(
            processState, activeProfile, activeSession, ::completeSessionForAction, ::continueAssistantNodeAt, ::updateSessionStreamState, ::updateSessionById
        ) { id, purpose, profile, model, prompt, limit -> generateTrackedUtility(id, purpose, profile, model, prompt, limit) }

        LaunchedEffect(Unit) {
            if (appState.preferences.newChatOnLaunch && activeSession.messages.isNotEmpty()) {
                createNewTopicForAssistant(activeAssistant, openInNewTab = true)
            }
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

                fun openNewChat() {
                    createNewTopicForAssistant(activeAssistant)
                    isSettingsOpen = false
                    if (isCompactScreen) showSidebar = false
                }

                /** Opens Settings at [category], or at its first page; without a category it toggles Settings. */
                fun openSettings(category: SettingsCategory?) {
                    if (category == null && isSettingsOpen) {
                        isSettingsOpen = false
                        return
                    }
                    if (category != null) activeSettingsCategory = category
                    startMobileSettingsAtRoot = category == null
                    isSettingsOpen = true
                    if (isCompactScreen) showSidebar = false
                }

                /** Everything Ctrl+K can do: main actions, every settings page, and each chat by name. */
                fun paletteCommands(): List<PaletteCommand> = buildList {
                    add(PaletteCommand(Strings.newChat2, Strings.ctrlN, ::openNewChat))
                    add(PaletteCommand(if (isSettingsOpen) Strings.closeSettings else Strings.openSettings, Strings.ctrl) { openSettings(null) })
                    if (!isCompactScreen) add(PaletteCommand(Strings.showOrHideTheSidebar, Strings.ctrlB) { showSidebar = !showSidebar })
                    SettingsCategory.entries.forEach { category ->
                        add(PaletteCommand(Strings.settings4(category.label)) { openSettings(category) })
                    }
                    appState.sessions.sortedByDescending { it.updatedAt }.forEach { chat ->
                        add(PaletteCommand(Strings.chat2(chat.title)) {
                            switchToTab(chat.id)
                            isSettingsOpen = false
                            if (isCompactScreen) showSidebar = false
                        })
                    }
                }

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

                    // The one shortcut list: the root runs it first while something is focused, and the desktop
                    // window runs it for keys nothing focused consumed (at launch or after a click on empty space).
                    val handleAppShortcut: (KeyEvent) -> Boolean = handle@{ event ->
                        if (event.type != KeyEventType.KeyDown) return@handle false
                        val ctrl = event.isCtrlPressed || event.isMetaPressed
                        when {
                            event.key == Key.Escape && showCommandPalette -> { showCommandPalette = false; true }
                            event.key == Key.Escape -> PlatformBridge.handleSystemBackPressed()
                            ctrl && event.key == Key.K -> { showCommandPalette = !showCommandPalette; true }
                            ctrl && event.key == Key.N -> { openNewChat(); true }
                            ctrl && event.key == Key.Comma -> { openSettings(null); true }
                            ctrl && event.key == Key.B && !isCompactScreen -> { showSidebar = !showSidebar; true }
                            else -> false
                        }
                    }
                    if (shortcutDispatcher != null) {
                        SideEffect { shortcutDispatcher.handler = handleAppShortcut }
                        DisposableEffect(shortcutDispatcher) { onDispose { shortcutDispatcher.handler = { false } } }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .imePadding()
                            .onPreviewKeyEvent(handleAppShortcut)
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
                            desktopWindowHeader(if (isSettingsOpen) Strings.settings else activeSession.title)
                        }

                        // 1. Top bar, hidden while Settings is open so there is only one way back to the chat.
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
                                        it.copy(title = newTitle.ifBlank { Strings.untitled }, isTitleManuallySet = true)
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
                                            .padding(horizontal = Space.s16, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalArrangement = Arrangement.spacedBy(Space.s6)
                                    ) {
                                        val sizeMb = (currentOta.release.assetSizeBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Space.s6),
                                            modifier = Modifier.padding(top = Space.s4, end = Space.s8)
                                        ) {
                                            KissVectorIcon(icon = KissIcon.SPARKLES, tint = p.accent, size = 14.dp)
                                            BasicText(
                                                text = Strings.versionIsReadyMb(currentOta.release.tagName.removePrefix("v"), sizeMb),
                                                style = p.uiBodyStyle.copy(fontWeight = FontWeight.Medium)
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
                                            SepiaButton(
                                                text = Strings.update,
                                                compact = true,
                                                style = SepiaButtonStyle.PRIMARY,
                                                onClick = { triggerOtaDownloadAndInstall(currentOta.release) }
                                            )
                                            SepiaButton(
                                                text = "×",
                                                contentDescription = Strings.dismissUpdateNotice,
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
                                        .padding(horizontal = Space.s16, vertical = Space.s8),
                                    verticalArrangement = Arrangement.spacedBy(Space.s6)
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
                                            horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                        ) {
                                            KissVectorIcon(icon = KissIcon.DOWNLOAD, tint = p.accent, size = 14.dp)
                                            BasicText(
                                                text = Strings.downloadingMb(currentOta.release.tagName, dlMb, totMb),
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
                                            .clip(RoundedCornerShape(Radius.pill))
                                            .background(p.border.copy(alpha = 0.45f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth((currentOta.progressPercent / 100f).coerceIn(0.02f, 1f))
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(Radius.pill))
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
                                        .padding(horizontal = Space.s16, vertical = Space.s8),
                                    verticalArrangement = Arrangement.spacedBy(Space.s6)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.s8)
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
                                            .clip(RoundedCornerShape(Radius.pill))
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
                                            .padding(horizontal = Space.s16, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalArrangement = Arrangement.spacedBy(Space.s6)
                                    ) {
                                        BasicText(
                                            text = Strings.updateFailed(currentOta.message),
                                            style = p.uiBodyStyle.copy(color = p.danger, fontWeight = FontWeight.Medium),
                                            modifier = Modifier.weight(1f, fill = false).padding(top = Space.s4, end = Space.s8)
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
                                            SepiaButton(
                                                text = Strings.retry,
                                                compact = true,
                                                style = SepiaButtonStyle.OUTLINE,
                                                onClick = ::triggerOtaCheckManual
                                            )
                                            SepiaButton(
                                                text = "×",
                                                contentDescription = Strings.dismissUpdateNotice,
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
                                // A notice with Undo stays longer, so there is time to reach the button.
                                delay(if (pendingUndo != null) UNDO_NOTICE_MS else NOTICE_MS)
                                if (bannerNotification == snap && !isVoiceListening) {
                                    bannerNotification = ""
                                }
                            }
                            // Once the notice has gone (or shows something else), the deleted chats can't come back.
                            if (bannerNotification.isBlank()) pendingUndo = null
                        }

                        // 2. Main Content Area:
                        // - On Desktop: Side-by-side Sidebar + Chat/Settings
                        // - On phones: the chat or Settings underneath, with the sidebar sliding over 86% of the width.
                        // A streaming reply replaces appState every frame. The sidebar gets the copy it last drew from until
                        // something it shows changes, so its lists and pinned replies aren't rebuilt per frame.
                        val sidebarKey = sidebarFingerprint(appState)
                        val sidebarAppState = remember(sidebarKey) { appState }
                        val sidebarActiveSession = remember(sidebarKey) { activeSession }
                        val sidebarComposable: @Composable (Modifier) -> Unit = { mod ->
                            ParchmentSidebar(
                                appState = sidebarAppState,
                                activeAssistant = activeAssistant,
                                activeSession = sidebarActiveSession,
                                selectedTab = sidebarTab,
                                isCompactScreen = isCompactScreen,
                                onSelectTab = stableCallback { sidebarTab = it },
                                modifier = mod,
                                onSelectAssistant = stableCallback { asst ->
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
                                onEditAssistantInSettings = stableCallback { asstId ->
                                    persist(appState.copy(activeAssistantId = asstId), immediateFlush = true)
                                    activeSettingsCategory = SettingsCategory.ASSISTANTS
                                    startMobileSettingsAtRoot = false
                                    isSettingsOpen = true
                                    if (isCompactScreen) showSidebar = false
                                },
                                onCreateNewAssistant = stableAction {
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
                                onSelectSession = stableCallback { sessId ->
                                    switchToTab(sessId)
                                    if (isCompactScreen) showSidebar = false
                                },
                                onOpenPinnedReply = stableCallback { pin ->
                                    pendingPinJump = pin
                                    switchToTab(pin.sessionId)
                                    isSettingsOpen = false
                                    if (isCompactScreen) showSidebar = false
                                },
                                onUnpinReply = stableCallback { pin -> togglePinnedReply(pin.sessionId, pin.nodeId) },
                                onNewTopic = stableAction(::openNewChat),
                                onTogglePinSession = stableCallback { sessId ->
                                    updateSessionById(sessId, immediate = true) { it.copy(isPinned = !it.isPinned) }
                                },
                                onRegenerateSessionTitle = stableCallback { sessId ->
                                    generateTopicTitle(sessId, forceRegenerate = true)
                                },
                                onDeleteSession = stableCallback { targetId ->
                                    if (appState.preferences.confirmBeforeDeletingChat) {
                                        val title = appState.sessions.find { it.id == targetId }?.title ?: "Chat"
                                        pendingDeleteConfirmation = DeleteConfirmRequest.SingleSession(targetId, title)
                                    } else {
                                        executeDeleteSingleSession(targetId)
                                    }
                                },
                                onDeleteAllTopicsForAssistant = stableAction {
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
                                onOpenSettings = stableAction {
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
                                    enter = expandHorizontally(motionSpring()) + fadeIn(motionTween(Motion.STANDARD)),
                                    exit = shrinkHorizontally(motionSpring()) + fadeOut(motionTween(Motion.QUICK))
                                ) {
                                    Row(modifier = Modifier.fillMaxHeight()) {
                                        sidebarComposable(Modifier.textScaledWidth(285.dp).fillMaxHeight())
                                        Box(
                                            modifier = Modifier
                                                .width(LineWidth.hairline)
                                                .fillMaxHeight()
                                                .background(p.border.copy(alpha = 0.7f))
                                        )
                                    }
                                }

                                Crossfade(
                                    targetState = isSettingsOpen,
                                    animationSpec = motionTween(Motion.MEDIUM),
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
                                                name = Strings.provider(protocol.displayName.substringBefore(" ")),
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
                                                bannerNotification = Strings.deletedProviderProfile
                                            } else {
                                                bannerNotification = Strings.atLeastOneProviderProfileIs
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
                                            // Importing a settings-only file keeps the chats already on disk.
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
                                            bannerNotification = Strings.importedSettingsAssistantsPreserved(plural(finalSnap.sessions.size, "chat"))
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
                                            bannerNotification = Strings.importedInto(plural(addedOrUpdated, "chat"), PlatformBridge.getChatsStorageDirectoryPath())
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
                                            bannerNotification = Strings.allLocalDataWipedCleanly
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
                                                    bannerNotification = Strings.attached(plural(pasted.size, "clipboard item"), pasted.joinToString { it.fileName })
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
                                                            activeSession.userName.ifBlank { Strings.user }
                                                        }
                                                        val muteTag = if (!n.includedInContext) Strings.muted else ""
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
                                                            appendLine(Strings.attachments(v.attachments.joinToString { it.fileName }))
                                                            appendLine()
                                                        }
                                                        if (v.mediaItems.isNotEmpty()) {
                                                            appendLine(Strings.generatedMedia(v.mediaItems.joinToString { it.localFilePath.ifBlank { it.remoteUrl } }))
                                                            appendLine()
                                                        }
                                                        appendLine("---")
                                                        appendLine()
                                                    }
                                                }
                                                val safeTitle = activeSession.title.replace(Regex("[^a-zA-Z0-9-_]"), "_").ifBlank { "chat" }
                                                val saved = PlatformBridge.saveFileForExport("$safeTitle.md", md.encodeToByteArray())
                                                if (saved != null) bannerNotification = Strings.exportedMarkdownTo(saved)
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
                                                        bannerNotification = Strings.chatExportFailed(error.message)
                                                        return@launch
                                                    }
                                                    if (saved != null) bannerNotification = Strings.exportedTheChatWithItsMedia(saved)
                                                } else {
                                                    val fileName = buildExportFileNameForChat(activeSession)
                                                    val bytes = CardAndPromptCodec.prettyJson.encodeToString(ChatSession.serializer(), activeSession).encodeToByteArray()
                                                    val saved = PlatformBridge.saveFileForExport(fileName, bytes)
                                                    if (saved != null) bannerNotification = Strings.exportedTheChatTo(saved)
                                                }
                                            }
                                        },
                                        onOpenProviderSettings = {
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
                                                    bannerNotification = Strings.rewoundChatToTurnRemoved(nodeIndex + 1, plural(dropped, "later turn"))
                                                } else {
                                                    // Rewind on the last message removes that turn (or turn pair), so it always does something.
                                                    val dropCount = if (total >= 2 && activeSession.messages.last().role == MessageRole.ASSISTANT) 2 else 1
                                                    updateActiveSession(immediate = true) { sess ->
                                                        sess.copy(messages = sess.messages.dropLast(dropCount))
                                                    }
                                                    bannerNotification = Strings.rewoundLast(plural(dropCount, "turn"))
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
                                                title = Strings.branch2(activeSession.title, nodeIndex + 1),
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
                                            bannerNotification = Strings.startedANewBranchAtMessage(nodeIndex + 1)
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
                                enter = expandHorizontally(motionSpring()) + fadeIn(motionTween(Motion.STANDARD)),
                                exit = shrinkHorizontally(motionSpring()) + fadeOut(motionTween(Motion.QUICK))
                            ) {
                                Row(modifier = Modifier.fillMaxHeight()) {
                                    Box(
                                        modifier = Modifier
                                            .width(LineWidth.hairline)
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
                                                bannerNotification = Strings.reAttachedToPrompt(existingAtt.fileName)
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
                                        enter = fadeIn(motionTween(Motion.STANDARD)),
                                        exit = fadeOut(motionTween(Motion.STANDARD))
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
                                        enter = expandHorizontally(motionSpring()) + fadeIn(motionTween(Motion.STANDARD)),
                                        exit = shrinkHorizontally(motionSpring()) + fadeOut(motionTween(Motion.QUICK))
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
                                        enter = fadeIn(motionTween(Motion.STANDARD)),
                                        exit = fadeOut(motionTween(Motion.STANDARD))
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
                                        enter = expandHorizontally(motionSpring()) + fadeIn(motionTween(Motion.STANDARD)),
                                        exit = shrinkHorizontally(motionSpring()) + fadeOut(motionTween(Motion.QUICK))
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
                                                        bannerNotification = Strings.reAttachedToPrompt(existingAtt.fileName)
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

                            if (isDraggingFilesOverWindow) FileDropOverlay()

                            val confirmReq = pendingDeleteConfirmation
                            if (confirmReq != null) {
                                DeleteConfirmDialog(
                                    request = confirmReq,
                                    askBeforeDeleting = appState.preferences.confirmBeforeDeletingChat,
                                    onAskBeforeDeletingChange = { checked ->
                                        persist(appState.copy(preferences = appState.preferences.copy(confirmBeforeDeletingChat = checked)), immediateFlush = true)
                                    },
                                    onDismiss = { pendingDeleteConfirmation = null },
                                    onConfirm = {
                                        pendingDeleteConfirmation = null
                                        when (confirmReq) {
                                            is DeleteConfirmRequest.SingleSession -> executeDeleteSingleSession(confirmReq.sessionId)
                                            is DeleteConfirmRequest.ClearAllSessions -> executeClearAllTopicsForAssistant()
                                            is DeleteConfirmRequest.ClearCurrentMessages -> clearCurrentChatMessages(activeSession.id)
                                        }
                                    }
                                )
                            }
                            val regenReq = pendingRegenerateConfirm
                            if (regenReq != null) {
                                RegenerateConfirmDialog(
                                    replacesReply = appState.preferences.deletePreviousMessageWhenRegenerating,
                                    askBeforeRegenerating = appState.preferences.confirmBeforeRegenerating,
                                    onAskBeforeRegeneratingChange = { checked ->
                                        persist(appState.copy(preferences = appState.preferences.copy(confirmBeforeRegenerating = checked)), immediateFlush = true)
                                    },
                                    onDismiss = { pendingRegenerateConfirm = null },
                                    onConfirm = {
                                        pendingRegenerateConfirm = null
                                        executeRegenerateFromNode(regenReq.nodeIndex, regenReq.sessionId)
                                    }
                                )
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

                            // Grid of all open tabs, on phones and desktop.
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
                            if (showCommandPalette) {
                                CommandPalette(paletteCommands(), onDismiss = { showCommandPalette = false })
                            }
                            FloatingNotification(
                                message = bannerNotification,
                                onDismiss = { bannerNotification = "" },
                                actionLabel = if (pendingUndo != null) "Undo" else null,
                                onAction = ::undoDelete,
                                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = Space.s12, vertical = Space.s12)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Changes only when something the sidebar displays changes, so a streaming reply doesn't redraw it. Per chat:
 * what the sidebar shows, leaving out reply text on purpose (it changes every frame).
 */
private fun sidebarFingerprint(state: AppStateSnapshot): List<Any> = listOf(
    state.sessions.map { s ->
        listOf(
            s.id, s.title, s.isPinned, s.updatedAt, s.assistantId, s.messages.size, s.isCompactStub,
            s.messages.mapNotNull { node -> node.id.takeIf { node.pinned } }
        )
    },
    state.assistants,
    state.preferences,
    state.activeSessionId
)

/** Chats removed by the last delete, with their saved data, so Undo can put them back as they were. */
internal class DeletedChats(
    val chats: List<Pair<ChatSession, String?>>,
    val activeSessionId: String,
    val openTabIds: List<String>
)

private const val NOTICE_MS = 5_000L
private const val UNDO_NOTICE_MS = 8_000L
