package io.parchment.ai.ui

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.MediaKind
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.WebSourceCitation
import io.parchment.ai.protocol.ContextWindowBreakdown
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                    "$cardName • New Chat"
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
                contentDescription = "New tab",
                compact = true,
                style = SepiaButtonStyle.GHOST,
                onClick = onNewTab
            )
        }

        Spacer(Modifier.width(Space.s8))

        SepiaButton(
            text = "⊞ ${openTabIds.size} Tabs",
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
                        text = "Open Tabs (${openTabIds.size})" + if (runningTotal > 0) " • $runningTotal active" else "",
                        style = p.uiHeaderStyle.copy(fontSize = 16.sp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalAlignment = Alignment.CenterVertically) {
                    if (emptyTabsCount >= 2) {
                        SepiaButton(
                            text = "🗑 Empty ($emptyTabsCount)",
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = onCloseEmptyTabs
                        )
                    }
                    SepiaButton(
                        text = "+ New tab",
                        compact = true,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = onNewTab
                    )
                    SepiaButton(
                        text = "×",
                        contentDescription = "Close tab overview",
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
                    placeholder = "🔍 Filter open tabs by title, assistant, model, or message…",
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
                                    .ifBlank { "Empty chat — tap to start" }

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
                                                text = streamInfo.statusText.ifBlank { "Running in background…" },
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
                                            text = "${sess.messages.size} messages$dateSuffix",
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
    val modelLabel = activeModel.ifBlank { "Select model…" }
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
                    contentDescription = if (showSidebar) "Close sidebar" else "Open sidebar",
                    compact = true,
                    style = SepiaButtonStyle.GHOST,
                    onClick = onToggleSidebar
                )

                if (appState.preferences.showAssistantAvatarInTitleBar) {
                    SepiaAvatarCircle(
                        symbol = activeAssistant.iconEmoji,
                        selected = true,
                        sizeDp = 28.dp,
                        modifier = Modifier.kissClickable { onOpenSettings(SettingsCategory.ASSISTANTS) }.accessibilityLabel("Assistant settings")
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
                                style = p.uiHeaderStyle.copy(fontSize = 15.sp),
                                modifier = Modifier.weight(1f, fill = false).kissClickable(hoverScale = 1.01f) { isEditingTitle = true }.accessibilityLabel("Edit title: ${activeSession.title}")
                            )
                            if (!isCompactScreen && activeSession.messages.isNotEmpty()) {
                                SepiaButton(
                                    text = "✦",
                                    contentDescription = "Regenerate title",
                                    hint = "Write a new title from this chat",
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
                            .accessibilityLabel("Open tabs: $openTabCount")
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
                        contentDescription = "Files in this chat: $chatArtifactsCount",
                        compact = true,
                        style = if (showRightFilesSidebar) SepiaButtonStyle.OUTLINE else SepiaButtonStyle.GHOST,
                        onClick = onToggleRightFilesSidebar
                    )
                }
                SepiaButton(
                    text = "✎",
                    contentDescription = "Chat notes",
                    compact = true,
                    style = if (showSessionInspector || hasChatNotes) SepiaButtonStyle.OUTLINE else SepiaButtonStyle.GHOST,
                    onClick = onToggleSessionInspector
                )
                if (!win.isCompact) {
                    SepiaButton(
                        text = "⚙",
                        contentDescription = "Settings",
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
                            text = "Choose a model",
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
                            text = if (isCompactScreen) "⚙ Providers" else "⚙ Manage providers",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                onToggleQuickModelPopover()
                                onOpenSettings(SettingsCategory.PROVIDERS)
                            }
                        )
                        SepiaButton(
                            text = "×",
                            contentDescription = "Close model picker",
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

// Left sidebar: Assistants, Chats and Pinned.
@Composable
internal fun ParchmentSidebar(
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
    onOpenPinnedReply: (PinnedReply) -> Unit,
    onUnpinReply: (PinnedReply) -> Unit,
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
            .background(p.sheetBg)
            .padding(Space.s12),
        verticalArrangement = Arrangement.spacedBy(Space.s10)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s6),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SepiaTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = when (selectedTab) {
                    SidebarTab.ASSISTANTS -> "🔍 Search assistants…"
                    SidebarTab.PINNED -> "🔍 Search pinned…"
                    SidebarTab.TOPICS -> "🔍 Search chats…"
                },
                minHeight = 36.dp,
                modifier = Modifier.weight(1f)
            )
            SepiaButton(
                text = "+",
                contentDescription = if (selectedTab == SidebarTab.ASSISTANTS) "New assistant" else "New chat",
                compact = true,
                style = SepiaButtonStyle.PRIMARY,
                onClick = {
                    if (selectedTab == SidebarTab.ASSISTANTS) onCreateNewAssistant() else onNewTopic()
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.pill))
                .background(p.cardBg.copy(alpha = 0.7f))
                .border(LineWidth.hairline, p.border.copy(alpha = 0.65f), RoundedCornerShape(Radius.pill))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.s4)
        ) {
            listOf(SidebarTab.ASSISTANTS to "Assistants", SidebarTab.TOPICS to "Chats", SidebarTab.PINNED to "Pinned").forEach { (tab, label) ->
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
                            role = Role.Tab,
                            selected = isSel,
                            onClick = { onSelectTab(tab) }
                        )
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(
                            when {
                                isSel -> p.sheetBg
                                tabHovered -> p.accent.copy(alpha = 0.08f)
                                else -> Color.Transparent
                            }
                        )
                        .padding(vertical = Space.s6),
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

        if (selectedTab == SidebarTab.PINNED) {
            PinnedRepliesPanel(appState.sessions, activeSession.id, searchQuery, onOpenPinnedReply, onUnpinReply)
        } else if (selectedTab == SidebarTab.ASSISTANTS) {
            val filteredAssistants = remember(appState.assistants, searchQuery) {
                val q = searchQuery.trim()
                if (q.isEmpty()) appState.assistants
                else appState.assistants.filter {
                    it.name.contains(q, ignoreCase = true) || it.systemPrompt.contains(q, ignoreCase = true)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Space.s6)
            ) {
                if (filteredAssistants.isEmpty()) {
                    item {
                        BasicText(
                            text = "No matching assistants found. Tap '+' above to create a new assistant.",
                            style = p.uiLabelStyle,
                            modifier = Modifier.padding(Space.s8)
                        )
                    }
                }
                itemsIndexed(filteredAssistants, key = { _, a -> a.id }) { _, asst ->
                    val isSel = asst.id == activeAssistant.id
                    val shape = RoundedCornerShape(Radius.pill)
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
                                selected = isSel,
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
                            .padding(horizontal = Space.s10, vertical = Space.s8),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(Space.s10),
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
                            contentDescription = "Edit assistant",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onEditAssistantInSettings(asst.id) }
                        )
                    }
                }
            }
        } else {
            val storedChatIds = remember(appState.sessions.size, activeSession.id, activeAssistant.id, searchQuery.isBlank()) {
                if (searchQuery.isBlank()) emptyList() else appState.sessions
                    .filter { it.isCompactStub && (it.assistantId == activeAssistant.id || appState.assistants.none { a -> a.id == it.assistantId }) }
                    .map { it.id }
            }
            val historySearch = rememberHistorySearch(storedChatIds, searchQuery)
            val topicsStructuralKey = remember(
                appState.sessions.size,
                activeSession.id,
                activeSession.title,
                activeSession.isPinned,
                activeSession.messages.size,
                activeAssistant.id,
                searchQuery,
                historySearch.matchingIds
            ) {
                "${appState.sessions.size}:${activeSession.id}:${activeSession.title}:${activeSession.isPinned}:${activeSession.messages.size}:${activeAssistant.id}:$searchQuery:${historySearch.matchingIds.size}"
            }
            val assistantTopics = remember(topicsStructuralKey) {
                val q = searchQuery.trim()
                val forAsst = appState.sessions.filter { it.assistantId == activeAssistant.id || appState.assistants.none { a -> a.id == it.assistantId } }
                val searched = if (q.isEmpty()) forAsst else forAsst.filter { s ->
                    s.title.contains(q, ignoreCase = true) || chatMessagesContain(s, q) || s.id in historySearch.matchingIds
                }
                searched.sortedWith(compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = Space.s6),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s6)
                ) {
                    val hdrIcon = remember(activeAssistant.iconEmoji) {
                        parseKissLabelIcons(activeAssistant.iconEmoji).leadingIcon ?: KissIcon.SPARKLES
                    }
                    KissVectorIcon(icon = hdrIcon, tint = p.accent, size = 13.dp)
                    BasicText(
                        text = if (historySearch.inProgress) "Searching full history…" else "${activeAssistant.name} (${assistantTopics.size})",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                if (assistantTopics.size > 1 || assistantTopics.any { it.messages.isNotEmpty() || it.title != "New Chat" }) {
                    SepiaButton(
                        text = "Clear all",
                        hint = "Delete every chat for this assistant",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = onDeleteAllTopicsForAssistant
                    )
                }
            }

            var touchActionsChatId by remember { mutableStateOf<String?>(null) }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Space.s2)
            ) {
                itemsIndexed(assistantTopics, key = { _, s -> s.id }) { _, sess ->
                    val selected = sess.id == activeSession.id
                    val shape = RoundedCornerShape(Radius.r8)
                    val rowInteraction = remember { MutableInteractionSource() }
                    var rowHasFocus by remember { mutableStateOf(false) }
                    val rowHovered by rowInteraction.collectIsHoveredAsState()
                    val rowPressed by rowInteraction.collectIsPressedAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { rowHasFocus = it.hasFocus }
                            .kissClickable(
                                pressScale = 0.98f,
                                interactionSource = rowInteraction,
                                selected = selected,
                                onClick = { onSelectSession(sess.id) }
                            )
                            .clip(shape)
                            .background(
                                when {
                                    selected -> p.cardBg
                                    rowPressed -> p.accent.copy(alpha = 0.14f)
                                    rowHovered -> p.accent.copy(alpha = 0.06f)
                                    else -> Color.Transparent
                                }
                            )
                            .then(if (selected) Modifier.rubricMark(p.accent, verticalInset = 9.dp) else Modifier)
                            .padding(horizontal = Space.s10, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.s4)
                            ) {
                                if (sess.isPinned) {
                                    KissVectorIcon(icon = KissIcon.PIN, tint = p.accent, size = 11.dp)
                                }
                                BasicText(
                                    text = sess.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiBodyStyle.copy(
                                        fontSize = 14.sp,
                                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                                    )
                                )
                            }
                            val dateLabel = if (appState.preferences.showChatListDates && sess.updatedAt > 0L) {
                                formatCompactTimestamp(sess.updatedAt)
                            } else ""
                            if (dateLabel.isNotEmpty()) {
                                BasicText(text = dateLabel, style = p.uiLabelStyle.copy(fontSize = 11.sp))
                            }
                        }
                        // Row actions appear on hover or focus; touch opens them with one explicit button.
                        val showRowActions = if (isCompactScreen) touchActionsChatId == sess.id else rowHovered || rowHasFocus
                        if (isCompactScreen) {
                            SepiaButton(
                                text = if (showRowActions) "▴" else "⋯",
                                contentDescription = if (showRowActions) "Hide chat actions" else "Chat actions",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = { touchActionsChatId = if (showRowActions) null else sess.id }
                            )
                        }
                        if (showRowActions) Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                            SepiaButton(
                                text = "📌",
                                contentDescription = if (sess.isPinned) "Unpin chat" else "Pin chat",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = { onTogglePinSession(sess.id) }
                            )
                            if (sess.messages.isNotEmpty()) {
                                SepiaButton(
                                    text = "✦",
                                    contentDescription = "Regenerate title",
                                    hint = "Write a new title from this chat",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { onRegenerateSessionTitle(sess.id) }
                                )
                            }
                            SepiaButton(
                                text = "🗑",
                                contentDescription = "Delete chat",
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

            SepiaButton(
                text = "⚙ Settings",
                style = SepiaButtonStyle.GHOST,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenSettings
            )
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
                                text = "$dimInfo${item.mimeType} • Scroll, pinch, or double-tap to zoom",
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
                                contentDescription = "Zoom out",
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
                                contentDescription = "Zoom in",
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
                                    contentDescription = "Open file",
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { PlatformBridge.openOrPlayMedia(openTarget) }
                                )
                            }
                            if (item.localFilePath.isNotBlank()) {
                                SepiaButton(
                                    text = "📥",
                                    contentDescription = "Save file",
                                    compact = true,
                                    style = SepiaButtonStyle.SECONDARY,
                                    onClick = {
                                        scope.launch {
                                            val saved = runCatching { PlatformBridge.exportLocalFile(item.title, item.localFilePath) }.getOrElse { error ->
                                                onNotify("Save failed: ${error.message}")
                                                return@launch
                                            }
                                            if (saved != null) onNotify("Saved image to $saved")
                                        }
                                    }
                                )
                            }
                            SepiaButton(
                                text = "✕",
                                contentDescription = "Close",
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
                                text = "Unable to decode image preview (${item.localFilePath.ifBlank { item.remoteUrl }})",
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
                                text = "$linesCount lines • ${item.textContent.length} chars • ${item.mimeType}",
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
                                    contentDescription = if (showRenderedPreview) "Show source" else "Show preview",
                                    compact = true,
                                    style = if (showRenderedPreview) SepiaButtonStyle.SECONDARY else SepiaButtonStyle.OUTLINE,
                                    onClick = { showRenderedPreview = !showRenderedPreview }
                                )
                            }
                            SepiaButton(
                                text = if (copied) "✓" else "📋",
                                contentDescription = if (copied) "Copied" else "Copy",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    clipboard.setText(AnnotatedString(item.textContent))
                                    copied = true
                                }
                            )
                            SepiaButton(
                                text = "📥",
                                contentDescription = "Save file",
                                compact = true,
                                style = SepiaButtonStyle.SECONDARY,
                                onClick = {
                                    scope.launch {
                                        val fileName = item.title.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file.txt" }
                                        val saved = PlatformBridge.saveFileForExport(fileName, item.textContent.encodeToByteArray())
                                        if (saved != null) onNotify("Saved file to $saved")
                                    }
                                }
                            )
                            if (item.localFilePath.isNotBlank()) {
                                SepiaButton(
                                    text = "📂",
                                    contentDescription = "Open file",
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { PlatformBridge.openOrPlayMedia(item.localFilePath) }
                                )
                            }
                            SepiaButton(
                                text = "✕",
                                contentDescription = "Close",
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
                                    text = item.textContent.ifEmpty { "(Empty file)" },
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
// Right sidebar: the chat's files, generated media and sources (`ParchmentRightFilesSidebar`).
// Categorizes every item in the active chat into:
// 1. `✦ AI-Generated Files & Media` (Workspace writes/edits, generated images/videos, SVG/code artifacts)
// 2. `↑ User-Uploaded Documents & Files` (Attached PDFs, images, code/text files, video/audio)
// 3. `🌐 Grounded Web Sources` (Internet search citations with explicit line-number & quote pointers)
// ============================================================================================
internal enum class RightVaultFilter(val label: String) {
    ALL("All"),
    AI_GENERATED("✦ AI Gen"),
    USER_UPLOADED("↑ Uploaded"),
    WEB_SOURCES("🌐 Sources")
}

internal sealed class AiGeneratedChatArtifact {
    abstract val turnNumber: Int

    data class WorkspaceFileArtifact(
        override val turnNumber: Int,
        val filePath: String,
        val actionBadge: String,
        val content: String,
        val lineCount: Int
    ) : AiGeneratedChatArtifact()

    data class MediaArtifact(
        override val turnNumber: Int,
        val media: GeneratedMediaItem
    ) : AiGeneratedChatArtifact()

    data class CodeSnippetArtifact(
        override val turnNumber: Int,
        val fileName: String,
        val language: String,
        val code: String,
        val lineCount: Int
    ) : AiGeneratedChatArtifact()
}

internal data class UserUploadedChatArtifact(
    val turnNumber: Int, // 0 = currently staged in composer
    val attachment: PromptAttachment
)

internal data class SessionGroundedWebSource(
    val turnNumber: Int,
    val source: WebSourceCitation
)

private val rightVaultJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val VAULT_CODE_FENCE_REGEX = Regex("```([a-zA-Z0-9_+-]*)\\n([\\s\\S]*?)```")
private val VAULT_LINE_NUMBER_STRIP_REGEX = Regex("^\\d+:\\s?")

/**
 * Counts the chat's files for the top bar badge without parsing or building lists; the full
 * `extractSessionVaultItems` runs only when the files sidebar is opened.
 */
internal fun countSessionVaultItemsFast(
    session: ChatSession,
    pendingAttachments: List<PromptAttachment> = emptyList()
): Int {
    var total = pendingAttachments.size
    for (node in session.messages) {
        val v = node.activeVariant
        total += v.attachments.size
        if (node.role == MessageRole.ASSISTANT) {
            total += v.mediaItems.size
            total += v.webSources.size
            var wroteWorkspaceFileInTurn = false
            for (tc in v.toolCalls) {
                if (!tc.isError) {
                    val n = tc.name
                    if (n.equals("fs_write", ignoreCase = true) ||
                        n.equals("fs_edit", ignoreCase = true) ||
                        n.equals("fs_multi_edit", ignoreCase = true)
                    ) {
                        wroteWorkspaceFileInTurn = true
                        total++
                    }
                }
            }
            if (!wroteWorkspaceFileInTurn && v.content.length >= 32 && v.content.contains("```")) {
                for (match in VAULT_CODE_FENCE_REGEX.findAll(v.content)) {
                    val lang = match.groupValues.getOrNull(1)?.trim().orEmpty()
                    val code = match.groupValues.getOrNull(2)?.trim().orEmpty()
                    if (code.isNotEmpty() && (countLinesFast(code) >= 6 || lang.equals("svg", ignoreCase = true))) {
                        total++
                    }
                }
            }
        }
    }
    return total
}

internal fun extractSessionVaultItems(
    session: ChatSession,
    pendingAttachments: List<PromptAttachment> = emptyList()
): Triple<List<AiGeneratedChatArtifact>, List<UserUploadedChatArtifact>, List<SessionGroundedWebSource>> {
    val aiArtifacts = mutableListOf<AiGeneratedChatArtifact>()
    val userUploads = mutableListOf<UserUploadedChatArtifact>()
    val webSources = mutableListOf<SessionGroundedWebSource>()

    for (att in pendingAttachments) {
        userUploads.add(UserUploadedChatArtifact(turnNumber = 0, attachment = att))
    }

    val codeFenceRegex = VAULT_CODE_FENCE_REGEX

    session.messages.forEachIndexed { idx, node ->
        val turnNum = idx + 1
        val v = node.activeVariant
        if (node.role == MessageRole.USER) {
            for (att in v.attachments) {
                userUploads.add(UserUploadedChatArtifact(turnNumber = turnNum, attachment = att))
            }
        } else if (node.role == MessageRole.ASSISTANT) {
            for (att in v.attachments) {
                userUploads.add(UserUploadedChatArtifact(turnNumber = turnNum, attachment = att))
            }
            var wroteWorkspaceFileInTurn = false
            for (tc in v.toolCalls) {
                val low = tc.name.lowercase()
                if ((low == "fs_write" || low == "fs_edit" || low == "fs_multi_edit") && !tc.isError) {
                    val parsed = runCatching { rightVaultJson.parseToJsonElement(tc.argumentsJson).jsonObject }.getOrNull()
                    val path = parsed?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (path.isNotBlank()) {
                        wroteWorkspaceFileInTurn = true
                        val content = when (low) {
                            "fs_write" -> parsed?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                            "fs_edit" -> parsed?.get("new_text")?.jsonPrimitive?.contentOrNull.orEmpty()
                            else -> {
                                val edits = parsed?.get("edits") as? JsonArray
                                edits?.mapNotNull { (it as? JsonObject)?.get("new_text")?.jsonPrimitive?.contentOrNull }
                                    ?.joinToString("\n\n").orEmpty()
                            }
                        }
                        val badge = if (low == "fs_write") "✦ AI wrote" else "✦ AI edited"
                        aiArtifacts.add(
                            AiGeneratedChatArtifact.WorkspaceFileArtifact(
                                turnNumber = turnNum,
                                filePath = path,
                                actionBadge = badge,
                                content = content.ifBlank { tc.resultText },
                                lineCount = countLinesFast(content).coerceAtLeast(1)
                            )
                        )
                    }
                }
            }
            for (media in v.mediaItems) {
                aiArtifacts.add(AiGeneratedChatArtifact.MediaArtifact(turnNumber = turnNum, media = media))
            }
            if (!wroteWorkspaceFileInTurn && v.content.contains("```")) {
                var blockCounter = 0
                for (match in codeFenceRegex.findAll(v.content)) {
                    val lang = match.groupValues.getOrNull(1)?.trim().orEmpty().ifBlank { "txt" }
                    val code = match.groupValues.getOrNull(2)?.trim().orEmpty()
                    val lines = countLinesFast(code)
                    if (code.isNotEmpty() && (lines >= 6 || lang.equals("svg", ignoreCase = true))) {
                        blockCounter++
                        aiArtifacts.add(
                            AiGeneratedChatArtifact.CodeSnippetArtifact(
                                turnNumber = turnNum,
                                fileName = "turn-$turnNum-artifact-$blockCounter.${lang.lowercase()}",
                                language = lang,
                                code = code,
                                lineCount = lines
                            )
                        )
                    }
                }
            }
            for (src in v.webSources) {
                webSources.add(SessionGroundedWebSource(turnNumber = turnNum, source = src))
            }
        }
    }

    return Triple(aiArtifacts, userUploads, webSources)
}

@Composable
internal fun ParchmentRightFilesSidebar(
    activeSession: ChatSession,
    pendingAttachments: List<PromptAttachment>,
    onInspectAttachment: (PromptAttachment) -> Unit,
    onInspectGeneratedImage: (GeneratedMediaItem) -> Unit,
    onReattachAttachment: (PromptAttachment) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalParchmentPalette.current
    var selectedFilter by remember(activeSession.id) { mutableStateOf(RightVaultFilter.ALL) }
    val vaultStructuralKey = remember(
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
    val (aiArtifacts, userUploads, webSources) = remember(vaultStructuralKey, pendingAttachments) {
        extractSessionVaultItems(activeSession, pendingAttachments)
    }
    val totalCount = aiArtifacts.size + userUploads.size + webSources.size

    Column(
        modifier = modifier
            .background(p.sheetBg)
            .padding(horizontal = Space.s12, vertical = Space.s10),
        verticalArrangement = Arrangement.spacedBy(Space.s8)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.weight(1f)
            ) {
                KissVectorIcon(icon = KissIcon.FOLDER, tint = p.accent, size = 15.dp)
                BasicText(
                    text = "Chat Files & Sources ($totalCount)",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = p.uiHeaderStyle.copy(fontSize = 15.sp)
                )
            }
            SepiaButton(
                text = "×",
                contentDescription = "Close",
                compact = true,
                style = SepiaButtonStyle.GHOST,
                onClick = onClose
            )
        }

        // Category Filter Pills
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            SepiaChip(
                label = "All $totalCount",
                selected = selectedFilter == RightVaultFilter.ALL,
                onClick = { selectedFilter = RightVaultFilter.ALL }
            )
            SepiaChip(
                label = "✦ AI Gen ${aiArtifacts.size}",
                selected = selectedFilter == RightVaultFilter.AI_GENERATED,
                onClick = { selectedFilter = RightVaultFilter.AI_GENERATED }
            )
            SepiaChip(
                label = "↑ Uploaded ${userUploads.size}",
                selected = selectedFilter == RightVaultFilter.USER_UPLOADED,
                onClick = { selectedFilter = RightVaultFilter.USER_UPLOADED }
            )
            SepiaChip(
                label = "🌐 Sources ${webSources.size}",
                selected = selectedFilter == RightVaultFilter.WEB_SOURCES,
                onClick = { selectedFilter = RightVaultFilter.WEB_SOURCES }
            )
        }

        SepiaDivider()

        if (totalCount == 0) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(Space.s16),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.s8)
                ) {
                    KissVectorIcon(icon = KissIcon.FOLDER, tint = p.mutedInk, size = 26.dp)
                    BasicText(
                        text = "No files or sources yet",
                        style = p.uiHeaderStyle.copy(fontSize = 14.sp, color = p.narrationInk)
                    )
                    BasicText(
                        text = "Files uploaded by you, files or media generated by the AI, and grounded web search sources in this chat will appear here.",
                        style = p.uiLabelStyle.copy(color = p.mutedInk)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.s10)
            ) {
                val showAiSection = (selectedFilter == RightVaultFilter.ALL || selectedFilter == RightVaultFilter.AI_GENERATED) &&
                    aiArtifacts.isNotEmpty()
                val showUploadedSection = (selectedFilter == RightVaultFilter.ALL || selectedFilter == RightVaultFilter.USER_UPLOADED) &&
                    userUploads.isNotEmpty()
                val showSourcesSection = (selectedFilter == RightVaultFilter.ALL || selectedFilter == RightVaultFilter.WEB_SOURCES) &&
                    webSources.isNotEmpty()

                // 1. AI-Generated Files & Media
                if (showAiSection) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        KissVectorIcon(icon = KissIcon.SPARKLES, tint = p.accent, size = 12.dp)
                        BasicText(
                            text = "Files and media from AI (${aiArtifacts.size})",
                            style = p.uiLabelStyle.copy(
                                color = p.accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }

                    aiArtifacts.forEach { item ->
                        when (item) {
                            is AiGeneratedChatArtifact.WorkspaceFileArtifact -> {
                                val shortName = item.filePath.substringAfterLast('/').substringAfterLast('\\')
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radius.r8))
                                        .background(p.cardBg)
                                        .border(LineWidth.hairline, p.accent.copy(alpha = 0.65f), RoundedCornerShape(Radius.r8))
                                        .clickable(role = Role.Button) {
                                            val wsRoot = activeSession.workspacePath
                                            val liveText = if (wsRoot.isNotBlank()) {
                                                PlatformBridge.workspaceRead(wsRoot, item.filePath, 1, 2000).getOrNull()?.let { raw ->
                                                    raw.lines().drop(1).joinToString("\n") { it.replaceFirst(VAULT_LINE_NUMBER_STRIP_REGEX, "") }
                                                }
                                            } else null
                                            val fullLocalPath = if (wsRoot.isNotBlank()) {
                                                "${wsRoot.trimEnd('/', '\\')}/${item.filePath.trimStart('/', '\\')}"
                                            } else ""
                                            onInspectAttachment(
                                                PromptAttachment(
                                                    id = "ai-ws-${item.turnNumber}-${item.filePath}",
                                                    fileName = item.filePath,
                                                    mimeType = "text/plain",
                                                    textContent = liveText?.takeIf { it.isNotBlank() } ?: item.content,
                                                    localFilePath = fullLocalPath
                                                )
                                            )
                                        }
                                        .padding(Space.s10),
                                    verticalArrangement = Arrangement.spacedBy(Space.s4)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(Radius.r4))
                                                .background(p.accent.copy(alpha = 0.15f))
                                                .padding(horizontal = Space.s6, vertical = Space.s2)
                                        ) {
                                            val parsedBadge = remember(item.actionBadge) { parseKissLabelIcons(item.actionBadge) }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                            ) {
                                                KissVectorIcon(
                                                    icon = parsedBadge.leadingIcon ?: KissIcon.SPARKLES,
                                                    tint = p.accent,
                                                    size = IconSize.xs
                                                )
                                                BasicText(
                                                    text = parsedBadge.cleanText.ifEmpty { item.actionBadge },
                                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                )
                                            }
                                        }
                                        BasicText(
                                            text = "Turn #${item.turnNumber} • ${item.lineCount} lines",
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.DIFF, tint = p.accent, size = IconSize.md)
                                        BasicText(
                                            text = shortName,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        )
                                    }
                                    BasicText(
                                        text = "${item.filePath} • Click to inspect",
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                    )
                                }
                            }

                            is AiGeneratedChatArtifact.MediaArtifact -> {
                                val media = item.media
                                val isVideo = media.kind == MediaKind.VIDEO
                                val fileLabel = media.localFilePath.substringAfterLast('/').substringAfterLast('\\')
                                    .ifBlank { if (isVideo) "Generated Video (.MP4)" else "Generated Image" }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radius.r8))
                                        .background(p.cardBg)
                                        .border(LineWidth.hairline, p.accent.copy(alpha = 0.65f), RoundedCornerShape(Radius.r8))
                                        .clickable(role = Role.Button) {
                                            if (isVideo) {
                                                val target = media.localFilePath.ifBlank { media.remoteUrl }
                                                if (target.isNotBlank()) PlatformBridge.openOrPlayMedia(target)
                                            } else {
                                                onInspectGeneratedImage(media)
                                            }
                                        }
                                        .padding(Space.s10),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(Radius.r4))
                                                .background(p.accent.copy(alpha = 0.15f))
                                                .padding(horizontal = Space.s6, vertical = Space.s2)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                            ) {
                                                KissVectorIcon(
                                                    icon = KissIcon.SPARKLES,
                                                    tint = p.accent,
                                                    size = IconSize.xs
                                                )
                                                BasicText(
                                                    text = if (isVideo) "AI video" else "AI image",
                                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                )
                                            }
                                        }
                                        BasicText(
                                            text = "Turn #${item.turnNumber}${if (media.aspectRatio.isNotBlank()) " • ${media.aspectRatio}" else ""}",
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                    ) {
                                        KissVectorIcon(
                                            icon = if (isVideo) KissIcon.VIDEO else KissIcon.IMAGE,
                                            tint = p.accent,
                                            size = IconSize.md
                                        )
                                        BasicText(
                                            text = fileLabel,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        )
                                    }
                                    BasicText(
                                        text = if (isVideo) "Click to play MP4 video" else "Click to zoom image",
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.accent)
                                    )
                                }
                            }

                            is AiGeneratedChatArtifact.CodeSnippetArtifact -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radius.r8))
                                        .background(p.cardBg)
                                        .border(LineWidth.hairline, p.accent.copy(alpha = 0.65f), RoundedCornerShape(Radius.r8))
                                        .clickable(role = Role.Button) {
                                            onInspectAttachment(
                                                PromptAttachment(
                                                    id = "ai-code-${item.turnNumber}-${item.fileName}",
                                                    fileName = item.fileName,
                                                    mimeType = "text/plain",
                                                    textContent = item.code
                                                )
                                            )
                                        }
                                        .padding(Space.s10),
                                    verticalArrangement = Arrangement.spacedBy(Space.s4)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(Radius.r4))
                                                .background(p.accent.copy(alpha = 0.15f))
                                                .padding(horizontal = Space.s6, vertical = Space.s2)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                            ) {
                                                KissVectorIcon(
                                                    icon = KissIcon.SPARKLES,
                                                    tint = p.accent,
                                                    size = IconSize.xs
                                                )
                                                BasicText(
                                                    text = "AI ${item.language}",
                                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                )
                                            }
                                        }
                                        BasicText(
                                            text = "Turn #${item.turnNumber} • ${item.lineCount} lines",
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.CODE, tint = p.accent, size = IconSize.md)
                                        BasicText(
                                            text = item.fileName,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        )
                                    }
                                    BasicText(
                                        text = "Click to view full code",
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. User-Uploaded Documents & Files
                if (showUploadedSection) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        KissVectorIcon(icon = KissIcon.UPLOAD, tint = p.narrationInk, size = 12.dp)
                        BasicText(
                            text = "Your uploads (${userUploads.size})",
                            style = p.uiLabelStyle.copy(
                                color = p.narrationInk,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }

                    userUploads.forEach { entry ->
                        val att = entry.attachment
                        val mime = att.mimeType.lowercase()
                        val lowerName = att.fileName.lowercase()
                        val isImg = mime.startsWith("image/") || lowerName.endsWith(".png") ||
                            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp")
                        val isPdf = mime == "application/pdf" || lowerName.endsWith(".pdf")
                        val isVid = mime.startsWith("video/") || lowerName.endsWith(".mp4")
                        val isAud = mime.startsWith("audio/") || lowerName.endsWith(".mp3") || lowerName.endsWith(".wav")
                        val fileIcon = when {
                            isImg -> KissIcon.IMAGE
                            isVid -> KissIcon.VIDEO
                            isAud -> KissIcon.AUDIO
                            else -> KissIcon.FILE_TEXT
                        }
                        val kindLabel = when {
                            isPdf -> "PDF Document"
                            isImg -> "Image"
                            isVid -> "Video"
                            isAud -> "Audio"
                            att.textContent.isNotBlank() -> "${att.textContent.lines().size} lines"
                            else -> "File"
                        }
                        val turnLabel = if (entry.turnNumber == 0) "Staged" else "Turn #${entry.turnNumber}"

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.r8))
                                .background(p.cardBg)
                                .border(LineWidth.hairline, p.border, RoundedCornerShape(Radius.r8))
                                .clickable(role = Role.Button, onClickLabel = "Open attachment") { onInspectAttachment(att) }
                                .padding(Space.s10),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.r4))
                                        .background(p.elevatedBg)
                                        .padding(horizontal = Space.s6, vertical = Space.s2)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                    ) {
                                        KissVectorIcon(
                                            icon = KissIcon.UPLOAD,
                                            tint = p.primaryInk,
                                            size = IconSize.xs
                                        )
                                        BasicText(
                                            text = "Your upload",
                                            style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        )
                                    }
                                }
                                BasicText(
                                    text = "$turnLabel • $kindLabel",
                                    style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).padding(end = Space.s6),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                ) {
                                    KissVectorIcon(icon = fileIcon, tint = p.accent, size = IconSize.md)
                                    BasicText(
                                        text = att.fileName,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    )
                                }
                                if (entry.turnNumber > 0) {
                                    SepiaButton(
                                        text = "@",
                                        contentDescription = "Reattach to composer",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = { onReattachAttachment(att) }
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Grounded Web Sources
                if (showSourcesSection) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        KissVectorIcon(icon = KissIcon.GLOBE, tint = p.accentSecondary, size = 12.dp)
                        BasicText(
                            text = "Web sources (${webSources.size})",
                            style = p.uiLabelStyle.copy(
                                color = p.accentSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }

                    webSources.forEach { item ->
                        val src = item.source
                        val lineLabel = if (src.groundedLineNumbers.isNotEmpty()) {
                            "Line ${src.groundedLineNumbers.joinToString(", ")}"
                        } else "Grounded"
                        val quoteExcerpt = src.groundedLines.firstOrNull().orEmpty()

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.r8))
                                .background(p.cardBg)
                                .border(LineWidth.hairline, p.border, RoundedCornerShape(Radius.r8))
                                .clickable(role = Role.Button, onClickLabel = "Open source") { openWebLink(src.url) }
                                .padding(Space.s10),
                            verticalArrangement = Arrangement.spacedBy(Space.s4)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(Radius.r4))
                                            .background(p.accent.copy(alpha = 0.16f))
                                            .padding(horizontal = 5.dp, vertical = Space.s2)
                                    ) {
                                        BasicText(
                                            text = "[${src.index}]",
                                            style = p.monoStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        )
                                    }
                                    BasicText(
                                        text = src.domain.ifBlank { src.url },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                ) {
                                    BasicText(
                                        text = "Turn #${item.turnNumber} • $lineLabel",
                                        style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                    )
                                    KissVectorIcon(
                                        icon = KissIcon.EXTERNAL_LINK,
                                        tint = p.accent,
                                        size = IconSize.xs
                                    )
                                }
                            }
                            BasicText(
                                text = src.title.ifBlank { src.url },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            )
                            if (quoteExcerpt.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                ) {
                                    KissVectorIcon(
                                        icon = KissIcon.CORNER_DOWN_RIGHT,
                                        tint = p.narrationInk,
                                        size = IconSize.xs
                                    )
                                    BasicText(
                                        text = "$lineLabel: \"$quoteExcerpt\"",
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiLabelStyle.copy(color = p.narrationInk, fontSize = 11.sp)
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
        append("Chat History (${breakdown.activeTurnsCount} active")
        if (breakdown.mutedTurnsCount > 0) append(", ${breakdown.mutedTurnsCount} left out")
        append(")")
    }

    val slices = listOf(
        CategorySlice("System & Persona Prompt", breakdown.systemAndPersonaTokens, colSystem),
        CategorySlice("Lorebook & Story Memory", breakdown.lorebookAndMemoryTokens, colLorebook),
        CategorySlice("Workspace & Tool Schemas", breakdown.workspaceAndToolsTokens, colWorkspace),
        CategorySlice("Uploaded Files (${breakdown.uploadedFilesCount})", breakdown.uploadedFilesTokens, colFiles),
        CategorySlice(historyLabel, breakdown.chatHistoryTokens, colHistory),
        CategorySlice("Current Composer Draft", breakdown.composerDraftTokens, colDraft),
        CategorySlice(
            label = "Free space remaining",
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
                    text = "What fills this conversation",
                    style = p.uiHeaderStyle.copy(fontSize = 15.sp)
                )
                val limitText = if (isUnlimited) "∞" else formatCompactTokens(breakdown.contextLimitTokens)
                val costSuffix = sessionCostLabel.takeIf { it.isNotBlank() }?.let { " • Session $it" }
                    ?: if (sessionTotalCostUsd > 0.0) " • Session ~${formatUsdCost(sessionTotalCostUsd)}" else ""
                BasicText(
                    text = "o200k_base • ${breakdown.totalUsedTokens} / $limitText tok ($pctInt% used)$costSuffix",
                    style = p.monoStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                )
            }
            SepiaButton(
                text = "×",
                contentDescription = "Close",
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
                        text = "${formatCompactTokens(slice.tokens)} tokens ($slicePct%)",
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
                    text = if (isStreaming) "⚡ Shorten and continue" else "⚡ Shorten conversation",
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
                    text = if (isStreaming) "✦ Summarize & Resume" else "✦ Summarize",
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
                text = "⚙ Limit & pricing",
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
