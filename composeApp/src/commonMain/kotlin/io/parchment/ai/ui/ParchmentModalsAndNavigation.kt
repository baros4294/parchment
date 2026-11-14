package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import io.parchment.ai.strings.Strings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.protocol.ContextWindowBreakdown
import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.launch

// Chat tabs: a tab strip on desktop and a tab grid on phones.
@Composable
internal fun DesktopBrowserTabBar(
    appState: AppStateSnapshot,
    openTabIds: List<String>,
    activeSessionId: String,
    activeStreamsBySession: Map<String, SessionStreamRuntime>,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onOpenTabOverview: () -> Unit
) {
    val p = LocalParchmentPalette.current
    val sessionsById = remember(appState.sessions.size, activeSessionId, openTabIds) {
        appState.sessions.associateBy { it.id }
    }
    val assistantsById = remember(appState.assistants) {
        appState.assistants.associateBy { it.id }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.sheetBg.copy(alpha = 0.88f))
            .padding(horizontal = Space.s10, vertical = Space.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.s6),
            verticalAlignment = Alignment.CenterVertically
        ) {
            openTabIds.forEach { tabId ->
                val sess = sessionsById[tabId] ?: appState.sessions.find { it.id == tabId } ?: return@forEach
                val isSelected = tabId == activeSessionId
                val isTabStreaming = activeStreamsBySession.containsKey(tabId)
                val tabAsst = assistantsById[sess.assistantId]
                    ?: appState.assistants.firstOrNull()
                val cardName = (sess.activeCard ?: tabAsst?.activeCard)?.name?.takeIf { it.isNotBlank() }
                val rawTitle = sess.title.ifBlank { "New Chat" }
                val primaryTitle = if (rawTitle == "New Chat" && cardName != null) {
                    Strings.newChat3(cardName)
                } else {
                    rawTitle
                }
                val tabShape = RoundedCornerShape(Radius.r8)

                Row(
                    modifier = Modifier
                        .clip(tabShape)
                        .background(if (isSelected) p.cardBg else Color.Transparent)
                        .then(if (isSelected) Modifier.rubricMark(p.accent, verticalInset = 7.dp) else Modifier)
                        .kissClickable(pressScale = 0.98f, role = Role.Tab, selected = isSelected) { onSelectTab(tabId) }
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (isTabStreaming) {
                        KissStreamingPulseDot(color = p.accent)
                    }
                    Column(
                        modifier = Modifier.widthIn(max = 185.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        BasicText(
                            text = primaryTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiLabelStyle.copy(
                                color = if (isSelected) p.primaryInk else p.narrationInk,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        )
                    }
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

            SepiaButton(
                text = "+",
                contentDescription = Strings.newTab,
                compact = true,
                style = SepiaButtonStyle.GHOST,
                onClick = onNewTab
            )
        }

        Spacer(Modifier.width(Space.s8))

        SepiaButton(
            text = Strings.tabs(openTabIds.size),
            compact = true,
            style = SepiaButtonStyle.OUTLINE,
            onClick = onOpenTabOverview
        )
    }
}

@Composable
internal fun MobileTabSwitcherModal(
    appState: AppStateSnapshot,
    openTabIds: List<String>,
    activeSessionId: String,
    activeStreamsBySession: Map<String, SessionStreamRuntime>,
    isCompactScreen: Boolean = true,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseEmptyTabs: () -> Unit = {},
    onNewTab: () -> Unit,
    onCloseModal: () -> Unit
) {
    val p = LocalParchmentPalette.current
    val runningTotal = activeStreamsBySession.size
    val win = LocalParchmentWindowSize.current
    val columns = if (isCompactScreen && win.isVeryNarrow) 1 else 2
    var tabFilterQuery by remember { mutableStateOf("") }
    val emptyTabsCount = remember(openTabIds, appState.sessions, activeStreamsBySession) {
        openTabIds.count { tabId ->
            val s = appState.sessions.find { it.id == tabId }
            s != null && s.messages.isEmpty() && s.title == "New Chat" && s.draftInput.isBlank() && !activeStreamsBySession.containsKey(tabId)
        }
    }
    val filteredTabIds = remember(openTabIds, appState.sessions, appState.assistants, tabFilterQuery) {
        val q = tabFilterQuery.trim()
        if (q.isEmpty()) openTabIds
        else openTabIds.filter { tabId ->
            val s = appState.sessions.find { it.id == tabId } ?: return@filter false
            val asst = appState.assistants.find { it.id == s.assistantId }
            s.title.contains(q, ignoreCase = true) ||
                (asst?.name?.contains(q, ignoreCase = true) == true) ||
                s.modelOverride.contains(q, ignoreCase = true) ||
                s.messages.any { it.activeVariant.content.contains(q, ignoreCase = true) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dismissOnOutsideTap(onCloseModal),
        contentAlignment = if (isCompactScreen) Alignment.BottomCenter else Alignment.Center
    ) {
        val sheetShape = if (isCompactScreen) {
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
        } else {
            RoundedCornerShape(Radius.r12)
        }
        Column(
            modifier = Modifier
                .then(if (isCompactScreen) Modifier.fillMaxWidth() else Modifier.widthIn(max = 860.dp).fillMaxWidth(0.92f))
                .heightIn(max = win.heightDp * if (isCompactScreen) 0.84f else 0.80f)
                .clip(sheetShape)
                .background(p.canvasBg)
                .border(1.dp, p.border, sheetShape)
                .blockTapThrough()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s10)
        ) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Space.s8)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s8),
                    modifier = Modifier.padding(top = Space.s4)
                ) {
                    KissVectorIcon(icon = KissIcon.TABS, tint = p.accent, size = IconSize.lg)
                    BasicText(
                        text = Strings.openTabs(openTabIds.size) + if (runningTotal > 0) " • $runningTotal active" else "",
                        style = p.uiHeaderStyle.copy(fontSize = 16.sp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalAlignment = Alignment.CenterVertically) {
                    if (emptyTabsCount >= 2) {
                        SepiaButton(
                            text = Strings.empty(emptyTabsCount),
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = onCloseEmptyTabs
                        )
                    }
                    SepiaButton(
                        text = Strings.newTab2,
                        compact = true,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = onNewTab
                    )
                    SepiaButton(
                        text = "×",
                        contentDescription = Strings.closeTabOverview,
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = onCloseModal
                    )
                }
            }

            if (openTabIds.size > 3) {
                SepiaTextField(
                    value = tabFilterQuery,
                    onValueChange = { tabFilterQuery = it },
                    placeholder = Strings.filterOpenTabsByTitle,
                    singleLine = true,
                    minHeight = 34.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SepiaDivider()

            val rows = remember(filteredTabIds, columns) { filteredTabIds.chunked(columns) }
            LazyColumn(
                modifier = Modifier.heightIn(max = win.heightDp * 0.68f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Space.s10)
            ) {
                itemsIndexed(rows) { _, rowPair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.s10)
                    ) {
                        rowPair.forEach { tabId ->
                            val sess = appState.sessions.find { it.id == tabId }
                            if (sess != null) {
                                val isSel = tabId == activeSessionId
                                val streamInfo = activeStreamsBySession[tabId]
                                val tabAsst = appState.assistants.find { it.id == sess.assistantId && it.id.isNotBlank() }
                                    ?: appState.assistants.firstOrNull()
                                val tabProf = appState.profiles.find { it.id == sess.profileId }
                                    ?: appState.profiles.firstOrNull()
                                val asstName = tabAsst?.name?.takeIf { it.isNotBlank() } ?: "Default Assistant"
                                val modelLabel = sess.modelOverride.ifBlank { tabProf?.defaultModel.orEmpty() }
                                    .substringAfterLast('/')
                                    .ifBlank { tabProf?.name.orEmpty() }
                                val lastMsgPreview = sess.messages.lastOrNull()?.activeVariant?.content
                                    ?.take(110)?.replace('\n', ' ').orEmpty()
                                    .ifBlank { Strings.emptyChatTapToStart }

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 122.dp)
                                        .clip(RoundedCornerShape(Radius.r12))
                                        .background(p.cardBg)
                                        .border(
                                            width = if (isSel) LineWidth.selected else LineWidth.hairline,
                                            color = if (isSel) p.accent else p.border,
                                            shape = RoundedCornerShape(Radius.r12)
                                        )
                                        .kissClickable(pressScale = 0.98f, role = Role.Tab, selected = isSel) { onSelectTab(tabId) }
                                        .padding(Space.s10),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                SepiaAvatarCircle(
                                                    symbol = tabAsst?.iconEmoji ?: "💬",
                                                    selected = isSel,
                                                    sizeDp = 24.dp
                                                )
                                                BasicText(
                                                    text = sess.title.ifBlank { "New Chat" },
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = p.uiBodyStyle.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = if (isSel) p.accent else p.primaryInk
                                                    )
                                                )
                                            }
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
                                        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                                            BasicText(
                                                text = asstName,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.accent, fontWeight = FontWeight.SemiBold)
                                            )
                                            if (modelLabel.isNotBlank()) {
                                                BasicText(
                                                    text = modelLabel,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = p.monoStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                                )
                                            }
                                        }
                                        BasicText(
                                            text = lastMsgPreview,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.narrationInk)
                                        )
                                    }

                                    Spacer(Modifier.height(Space.s6))

                                    if (streamInfo != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            KissStreamingPulseDot(color = p.accent)
                                            BasicText(
                                                text = streamInfo.statusText.ifBlank { Strings.runningInBackground },
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                            )
                                        }
                                    } else {
                                        val dateSuffix = if (appState.preferences.showChatListDates && sess.updatedAt > 0L) {
                                            " • ${formatCompactTimestamp(sess.updatedAt)}"
                                        } else ""
                                        BasicText(
                                            text = Strings.messages(sess.messages.size, dateSuffix),
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp)
                                        )
                                    }
                                }
                            }
                        }
                        if (columns == 2 && rowPair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// Top bar: rename the chat, write a new title, and pick the model.
@Composable
internal fun TopEditorialBar(
    appState: AppStateSnapshot,
    activeSession: ChatSession,
    activeAssistant: AssistantAgent,
    activeProfile: ProviderProfile,
    isCompactScreen: Boolean,
    showSidebar: Boolean,
    showSessionInspector: Boolean,
    showRightFilesSidebar: Boolean = false,
    chatArtifactsCount: Int = 0,
    showQuickModelPopover: Boolean,
    openTabCount: Int,
    backgroundStreamingCount: Int,
    onOpenMobileTabSwitcher: () -> Unit,
    onNewChatTab: () -> Unit,
    onToggleSidebar: () -> Unit,
    onToggleSessionInspector: () -> Unit,
    onToggleRightFilesSidebar: () -> Unit = {},
    onToggleQuickModelPopover: () -> Unit,
    onSelectModelQuick: (String, String) -> Unit,
    onRenameTopicInline: (String) -> Unit,
    onRegenerateTitleAi: () -> Unit,
    onOpenSettings: (SettingsCategory?) -> Unit
) {
    val p = LocalParchmentPalette.current
    val activeModel = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }
    val modelLabel = activeModel.ifBlank { Strings.selectModel }
    var isEditingTitle by remember(activeSession.id) { mutableStateOf(false) }
    var titleBuffer by remember(activeSession.id, activeSession.title) { mutableStateOf(activeSession.title) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.canvasBg)
            .animateContentSize(animationSpec = motionSpring())
            .padding(horizontal = Space.s12, vertical = Space.s8),
        verticalArrangement = Arrangement.spacedBy(Space.s6)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Space.s8),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SepiaButton(
                    text = if (showSidebar) "◂" else "☰",
                    contentDescription = if (showSidebar) Strings.closeSidebar else Strings.openSidebar,
                    compact = true,
                    style = SepiaButtonStyle.GHOST,
                    onClick = onToggleSidebar
                )

                if (appState.preferences.showAssistantAvatarInTitleBar) {
                    SepiaAvatarCircle(
                        symbol = activeAssistant.iconEmoji,
                        selected = true,
                        sizeDp = 28.dp,
                        modifier = Modifier.kissClickable { onOpenSettings(SettingsCategory.ASSISTANTS) }.accessibilityLabel(Strings.assistantSettings)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    if (isEditingTitle) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Space.s6),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SepiaTextField(
                                value = titleBuffer,
                                onValueChange = { titleBuffer = it },
                                minHeight = 30.dp,
                                modifier = Modifier.weight(1f)
                            )
                            SepiaButton(
                                text = Strings.save,
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
                                style = p.uiHeaderStyle.copy(fontSize = 15.sp),
                                modifier = Modifier.weight(1f, fill = false).kissClickable(hoverScale = 1.01f) { isEditingTitle = true }.accessibilityLabel(Strings.editTitle(activeSession.title))
                            )
                            if (!isCompactScreen && activeSession.messages.isNotEmpty()) {
                                SepiaButton(
                                    text = "✦",
                                    contentDescription = Strings.regenerateTitle,
                                    hint = Strings.writeANewTitleFrom,
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = onRegenerateTitleAi
                                )
                            }
                        }
                    }

                    val shortModelLabel = if (isCompactScreen && modelLabel.contains('/')) {
                        modelLabel.substringAfterLast('/')
                    } else {
                        modelLabel
                    }
                    val capsuleText = if (appState.preferences.showProviderInModelCapsule && !isCompactScreen) {
                        "$shortModelLabel on ${activeProfile.name}"
                    } else {
                        shortModelLabel
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s4),
                        modifier = Modifier.kissClickable(hoverScale = 1.01f) { onToggleQuickModelPopover() }
                    ) {
                        BasicText(
                            text = capsuleText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiLabelStyle.copy(fontSize = 12.sp),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        KissVectorIcon(
                            icon = if (showQuickModelPopover) KissIcon.CHEVRON_UP else KissIcon.CHEVRON_DOWN,
                            tint = p.mutedInk,
                            size = 11.dp
                        )
                    }
                }
            }

            Spacer(Modifier.width(Space.s6))

            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.s6),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val win = LocalParchmentWindowSize.current
                val hasChatNotes = activeSession.systemPrompt.isNotBlank() ||
                    activeSession.authorsNote.isNotBlank() ||
                    activeSession.storySummary.isNotBlank()
                if (win.isCompact && (openTabCount > 1 || backgroundStreamingCount > 0)) {
                    val tabBadgeShape = RoundedCornerShape(Radius.r6)
                    Box(
                        modifier = Modifier
                            .heightIn(min = 27.dp)
                            .widthIn(min = 27.dp)
                            .clip(tabBadgeShape)
                            .background(if (backgroundStreamingCount > 0) p.accent.copy(alpha = 0.16f) else p.cardBg)
                            .border(
                                width = if (backgroundStreamingCount > 0) 1.dp else 1.dp,
                                color = if (backgroundStreamingCount > 0) p.accent else p.border,
                                shape = tabBadgeShape
                            )
                            .kissClickable { onOpenMobileTabSwitcher() }
                            .accessibilityLabel(Strings.openTabs2(openTabCount))
                            .padding(horizontal = Space.s6),
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
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (backgroundStreamingCount > 0) p.accent else p.primaryInk
                                )
                            )
                        }
                    }
                }
                if (chatArtifactsCount > 0 || showRightFilesSidebar) {
                    SepiaButton(
                        text = "📁 $chatArtifactsCount",
                        contentDescription = Strings.filesInThisChat(chatArtifactsCount),
                        compact = true,
                        style = if (showRightFilesSidebar) SepiaButtonStyle.OUTLINE else SepiaButtonStyle.GHOST,
                        onClick = onToggleRightFilesSidebar
                    )
                }
                SepiaButton(
                    text = "✎",
                    contentDescription = Strings.chatNotes,
                    compact = true,
                    style = if (showSessionInspector || hasChatNotes) SepiaButtonStyle.OUTLINE else SepiaButtonStyle.GHOST,
                    onClick = onToggleSessionInspector
                )
                if (!win.isCompact) {
                    SepiaButton(
                        text = "⚙",
                        contentDescription = Strings.settings,
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { onOpenSettings(null) }
                    )
                }
            }
        }

        // Quick Inline Searchable Vertical Model Switcher Popover
        AnimatedVisibility(visible = showQuickModelPopover) {
            val totalModelsCount = remember(appState.profiles) {
                appState.profiles.sumOf { prof ->
                    if (prof.savedModels.isNotEmpty()) prof.savedModels.size
                    else if (prof.defaultModel.isNotBlank()) 1
                    else 0
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.r10))
                    .background(p.cardBg)
                    .border(LineWidth.hairline, p.border, RoundedCornerShape(Radius.r10))
                    .padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s8)
            ) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(Space.s6)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s6),
                        modifier = Modifier.padding(top = Space.s4)
                    ) {
                        KissVectorIcon(icon = KissIcon.CPU, tint = p.accent, size = 14.dp)
                        BasicText(
                            text = Strings.chooseAModel,
                            style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        )
                        BasicText(
                            text = "($totalModelsCount)",
                            style = p.monoStyle.copy(color = p.mutedInk, fontSize = 11.sp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s4)
                    ) {
                        SepiaButton(
                            text = if (isCompactScreen) Strings.providers else Strings.manageProviders,
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                onToggleQuickModelPopover()
                                onOpenSettings(SettingsCategory.PROVIDERS)
                            }
                        )
                        SepiaButton(
                            text = "×",
                            contentDescription = Strings.closeModelPicker,
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onToggleQuickModelPopover
                        )
                    }
                }
                SepiaSearchableModelPicker(
                    profiles = appState.profiles,
                    selectedProfileId = activeProfile.id,
                    selectedModelId = activeModel,
                    onSelectModel = { profId, modelId ->
                        onSelectModelQuick(profId, modelId)
                    },
                    maxListHeight = 280.dp
                )
            }
        }
    }
}

// Attachment viewer: zoomable images, and text or code files.
@Composable
internal fun ParchmentAttachmentViewerModal(
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
            .dismissOnOutsideTap(onClose),
        contentAlignment = Alignment.Center
    ) {
        when (item) {
            is ActiveMediaViewerItem.ImageViewer -> {
                val fileImage = rememberFileImage(item.localFilePath)
                val bmp = fileImage.bitmap
                var scale by remember(item) { mutableStateOf(1f) }
                var offsetX by remember(item) { mutableStateOf(0f) }
                var offsetY by remember(item) { mutableStateOf(0f) }

                Column(
                    modifier = Modifier
                        .widthIn(max = 1040.dp)
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(0.88f)
                        .clip(RoundedCornerShape(Radius.r12))
                        .background(p.cardBg)
                        .border(1.dp, p.accent, RoundedCornerShape(Radius.r12))
                        .blockTapThrough()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.r10))
                            .background(p.sheetBg.copy(alpha = 0.85f))
                            .border(LineWidth.hairline, p.border, RoundedCornerShape(Radius.r10))
                            .padding(horizontal = Space.s12, vertical = Space.s8),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(Space.s6)
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.s6)
                            ) {
                                KissVectorIcon(icon = KissIcon.IMAGE, tint = p.accent, size = 15.dp)
                                BasicText(
                                    text = item.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiHeaderStyle.copy(fontSize = 15.sp)
                                )
                            }
                            val dimInfo = if (bmp != null) "${bmp.width}×${bmp.height}px • " else ""
                            BasicText(
                                text = Strings.scrollPinchOrDoubleTapTo(dimInfo, item.mimeType),
                                style = p.uiLabelStyle
                            )
                        }

                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.s6),
                            verticalArrangement = Arrangement.spacedBy(Space.s6)
                        ) {
                            SepiaButton(
                                text = "🔍−",
                                contentDescription = Strings.zoomOut,
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
                                text = "🔍+",
                                contentDescription = Strings.zoomIn,
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    scale = (scale * 1.25f).coerceIn(0.5f, 8f)
                                }
                            )
                            val openTarget = item.localFilePath.ifBlank { item.remoteUrl }
                            if (openTarget.isNotBlank()) {
                                SepiaButton(
                                    text = "📂",
                                    contentDescription = Strings.openFile,
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { PlatformBridge.openOrPlayMedia(openTarget) }
                                )
                            }
                            if (item.localFilePath.isNotBlank()) {
                                SepiaButton(
                                    text = "📥",
                                    contentDescription = Strings.saveFile,
                                    compact = true,
                                    style = SepiaButtonStyle.SECONDARY,
                                    onClick = {
                                        scope.launch {
                                            val saved = runCatching { PlatformBridge.exportLocalFile(item.title, item.localFilePath) }.getOrElse { error ->
                                                onNotify(Strings.saveFailed(error.message))
                                                return@launch
                                            }
                                            if (saved != null) onNotify(Strings.savedImageTo(saved))
                                        }
                                    }
                                )
                            }
                            SepiaButton(
                                text = "✕",
                                contentDescription = Strings.close,
                                compact = true,
                                style = SepiaButtonStyle.PRIMARY,
                                onClick = onClose
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = Space.s8)
                            .clip(RoundedCornerShape(Radius.r10))
                            .background(p.sheetBg.copy(alpha = 0.55f))
                            .border(LineWidth.hairline, p.border.copy(alpha = 0.7f), RoundedCornerShape(Radius.r10))
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
                        } else if (!fileImage.isLoading) {
                            BasicText(
                                text = Strings.unableToDecodeImagePreview(item.localFilePath.ifBlank { item.remoteUrl }),
                                style = p.uiHeaderStyle.copy(color = p.narrationInk)
                            )
                        }
                    }
                }
            }

            is ActiveMediaViewerItem.TextOrCodeViewer -> {
                var copied by remember(item) { mutableStateOf(false) }
                val linesCount = remember(item.textContent) { countLinesFast(item.textContent) }
                val lowerTitle = remember(item.title) { item.title.lowercase() }
                val isSvgContent = remember(item.title, item.mimeType, item.textContent) {
                    lowerTitle.endsWith(".svg") || item.mimeType.equals("image/svg+xml", ignoreCase = true) ||
                        (item.textContent.contains("<svg", ignoreCase = true) && item.textContent.contains("</svg>", ignoreCase = true))
                }
                val isMarkdownContent = remember(item.title, item.mimeType) {
                    lowerTitle.endsWith(".md") || lowerTitle.endsWith(".markdown") || item.mimeType.equals("text/markdown", ignoreCase = true)
                }
                var showRenderedPreview by remember(item, isSvgContent, isMarkdownContent) {
                    mutableStateOf(isSvgContent || isMarkdownContent)
                }

                Column(
                    modifier = Modifier
                        .widthIn(max = 940.dp)
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(0.88f)
                        .clip(RoundedCornerShape(Radius.r12))
                        .background(p.cardBg)
                        .border(1.dp, p.accent, RoundedCornerShape(Radius.r12))
                        .blockTapThrough()
                        .padding(Space.s16),
                    verticalArrangement = Arrangement.spacedBy(Space.s10)
                ) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(Space.s6)
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.s6)
                            ) {
                                KissVectorIcon(icon = KissIcon.FILE_TEXT, tint = p.accent, size = 15.dp)
                                BasicText(
                                    text = item.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiHeaderStyle.copy(fontSize = 16.sp)
                                )
                            }
                            BasicText(
                                text = Strings.linesChars(linesCount, item.textContent.length, item.mimeType),
                                style = p.uiLabelStyle
                            )
                        }

                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.s6),
                            verticalArrangement = Arrangement.spacedBy(Space.s6)
                        ) {
                            if (isSvgContent || isMarkdownContent) {
                                SepiaButton(
                                    text = if (showRenderedPreview) "‹/›" else "👁",
                                    contentDescription = if (showRenderedPreview) Strings.showSource else Strings.showPreview,
                                    compact = true,
                                    style = if (showRenderedPreview) SepiaButtonStyle.SECONDARY else SepiaButtonStyle.OUTLINE,
                                    onClick = { showRenderedPreview = !showRenderedPreview }
                                )
                            }
                            SepiaButton(
                                text = if (copied) "✓" else "📋",
                                contentDescription = if (copied) Strings.copied else Strings.copy,
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    clipboard.setText(AnnotatedString(item.textContent))
                                    copied = true
                                }
                            )
                            SepiaButton(
                                text = "📥",
                                contentDescription = Strings.saveFile,
                                compact = true,
                                style = SepiaButtonStyle.SECONDARY,
                                onClick = {
                                    scope.launch {
                                        val fileName = item.title.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file.txt" }
                                        val saved = PlatformBridge.saveFileForExport(fileName, item.textContent.encodeToByteArray())
                                        if (saved != null) onNotify(Strings.savedFileTo(saved))
                                    }
                                }
                            )
                            if (item.localFilePath.isNotBlank()) {
                                SepiaButton(
                                    text = "📂",
                                    contentDescription = Strings.openFile,
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { PlatformBridge.openOrPlayMedia(item.localFilePath) }
                                )
                            }
                            SepiaButton(
                                text = "✕",
                                contentDescription = Strings.close,
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
                            .clip(RoundedCornerShape(Radius.r8))
                            .background(p.sheetBg.copy(alpha = 0.65f))
                            .border(LineWidth.hairline, p.border.copy(alpha = 0.7f), RoundedCornerShape(Radius.r8))
                            .padding(Space.s12)
                            .verticalScroll(rememberScrollState())
                    ) {
                        when {
                            isSvgContent && showRenderedPreview -> {
                                InlineSvgArtifactPreview(rawSvg = item.textContent)
                            }
                            isMarkdownContent && showRenderedPreview -> {
                                LiteraryProseRenderer(
                                    text = item.textContent,
                                    enableMarkdown = true,
                                    enableMath = true
                                )
                            }
                            else -> {
                                BasicText(
                                    text = item.textContent.ifEmpty { Strings.emptyFile },
                                    style = p.monoStyle.copy(fontSize = 13.sp, lineHeight = 18.sp)
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
// Context window breakdown (`ContextWindowBreakdownPopover`).
// Opened by clicking `SepiaContextGaugeRing` in the Composer footer.
// Displays unified `o200k_base` token counts across 7 context categories + Free Space + 1-tap actions.
// ============================================================================================
@Composable
internal fun ContextWindowBreakdownPopover(
    breakdown: ContextWindowBreakdown,
    sessionTotalCostUsd: Double,
    isStreaming: Boolean,
    canCompact: Boolean,
    canSummarize: Boolean,
    onCompactContext: () -> Unit,
    onSummarizeStory: () -> Unit,
    onOpenPricingSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    utilityUsageRecords: List<io.parchment.ai.model.ApiUsageRecord> = emptyList(),
    sessionCostLabel: String = ""
) {
    val p = LocalParchmentPalette.current
    val isUnlimited = breakdown.contextLimitTokens <= 0
    val safeLimit = if (isUnlimited) maxOf(breakdown.totalUsedTokens, 128_000) else breakdown.contextLimitTokens
    val pctInt = ((breakdown.totalUsedTokens.toDouble() / safeLimit.toDouble()) * 1000.0).toInt() / 10.0

    val colSystem = p.accent
    val colLorebook = p.accentSecondary
    val colWorkspace = Color(0xFF4A7C59)
    val colFiles = Color(0xFF3D6B8E)
    val colHistory = Color(0xFF9B5B34)
    val colDraft = Color(0xFFB5892E)
    val colFree = p.border.copy(alpha = 0.55f)

    data class CategorySlice(
        val label: String,
        val tokens: Int,
        val color: Color,
        val isFreeSpace: Boolean = false
    )

    val historyLabel = buildString {
        append(Strings.chatHistoryActive(breakdown.activeTurnsCount))
        if (breakdown.mutedTurnsCount > 0) append(", ${breakdown.mutedTurnsCount} left out")
        append(")")
    }

    val slices = listOf(
        CategorySlice("System & Persona Prompt", breakdown.systemAndPersonaTokens, colSystem),
        CategorySlice(Strings.lorebookStoryMemory, breakdown.lorebookAndMemoryTokens, colLorebook),
        CategorySlice(Strings.workspaceToolSchemas, breakdown.workspaceAndToolsTokens, colWorkspace),
        CategorySlice(Strings.uploadedFiles(breakdown.uploadedFilesCount), breakdown.uploadedFilesTokens, colFiles),
        CategorySlice(historyLabel, breakdown.chatHistoryTokens, colHistory),
        CategorySlice(Strings.currentComposerDraft, breakdown.composerDraftTokens, colDraft),
        CategorySlice(
            label = Strings.freeSpaceRemaining,
            tokens = breakdown.freeSpaceTokens,
            color = colFree,
            isFreeSpace = true
        )
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Space.s8)
            .clip(RoundedCornerShape(Radius.r12))
            .background(p.cardBg)
            .border(1.dp, p.accent.copy(alpha = 0.75f), RoundedCornerShape(Radius.r12))
            .padding(Space.s12),
        verticalArrangement = Arrangement.spacedBy(Space.s8)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                BasicText(
                    text = Strings.whatFillsThisConversation,
                    style = p.uiHeaderStyle.copy(fontSize = 15.sp)
                )
                val limitText = if (isUnlimited) "∞" else formatCompactTokens(breakdown.contextLimitTokens)
                val costSuffix = sessionCostLabel.takeIf { it.isNotBlank() }?.let { Strings.session(it) }
                    ?: if (sessionTotalCostUsd > 0.0) Strings.session2(formatUsdCost(sessionTotalCostUsd)) else ""
                BasicText(
                    text = Strings.o200kBaseTokUsed(breakdown.totalUsedTokens, limitText, pctInt, costSuffix),
                    style = p.monoStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                )
            }
            SepiaButton(
                text = "×",
                contentDescription = Strings.close,
                compact = true,
                style = SepiaButtonStyle.GHOST,
                onClick = onClose
            )
        }

        // Multi-Segment Proportional Stacked Bar
        val barDenom = maxOf(safeLimit, breakdown.totalUsedTokens, 1).toFloat()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(p.elevatedBg)
                .border(LineWidth.hairline, p.border.copy(alpha = 0.7f), RoundedCornerShape(Radius.pill))
        ) {
            for (slice in slices) {
                if (slice.tokens > 0) {
                    val weightVal = (slice.tokens.toFloat() / barDenom).coerceAtLeast(if (slice.isFreeSpace) 0.01f else 0.015f)
                    Box(
                        modifier = Modifier
                            .weight(weightVal)
                            .fillMaxHeight()
                            .background(slice.color)
                    )
                }
            }
        }

        SepiaDivider()

        // 7 Itemized Category Rows
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (slice in slices) {
                val slicePct = ((slice.tokens.toDouble() / safeLimit.toDouble()) * 1000.0).toInt() / 10.0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.weight(1f).padding(end = Space.s8)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (slice.isFreeSpace) p.accentSecondary else slice.color)
                        )
                        BasicText(
                            text = slice.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiBodyStyle.copy(
                                fontSize = 12.sp,
                                fontWeight = if (slice.isFreeSpace || slice.tokens > 0) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (slice.isFreeSpace) p.accent else if (slice.tokens > 0) p.primaryInk else p.mutedInk
                            )
                        )
                    }
                    BasicText(
                        text = Strings.tokens2(formatCompactTokens(slice.tokens), slicePct),
                        style = p.monoStyle.copy(
                            fontSize = 11.sp,
                            fontWeight = if (slice.isFreeSpace || slice.tokens > 0) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (slice.isFreeSpace) p.accent else if (slice.tokens > 0) p.narrationInk else p.mutedInk
                        )
                    )
                }
            }
        }

        SepiaDivider()

        UtilityUsageSummary(utilityUsageRecords)

        // Quick Context Management Actions
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s6),
            verticalArrangement = Arrangement.spacedBy(Space.s6)
        ) {
            if (canCompact) {
                SepiaButton(
                    text = if (isStreaming) Strings.shortenAndContinue else Strings.shortenConversation,
                    compact = true,
                    enabled = true,
                    style = SepiaButtonStyle.SECONDARY,
                    onClick = {
                        onCompactContext()
                        onClose()
                    }
                )
            }
            if (canSummarize) {
                SepiaButton(
                    text = if (isStreaming) Strings.summarizeResume else Strings.summarize,
                    compact = true,
                    enabled = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = {
                        onSummarizeStory()
                        onClose()
                    }
                )
            }
            SepiaButton(
                text = Strings.limitPricing,
                compact = true,
                style = SepiaButtonStyle.GHOST,
                onClick = {
                    onClose()
                    onOpenPricingSettings()
                }
            )
        }
    }
}
