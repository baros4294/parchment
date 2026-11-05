package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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

// ============================================================================================
// DESKTOP BROWSER-STYLE MULTI-TASKING TAB BAR & MOBILE CHROMIUM/FIREFOX TAB SWITCHER GRID
// ============================================================================================
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.sheetBg.copy(alpha = 0.88f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            openTabIds.forEach { tabId ->
                val sess = appState.sessions.find { it.id == tabId } ?: return@forEach
                val isSelected = tabId == activeSessionId
                val isTabStreaming = activeStreamsBySession.containsKey(tabId)
                val tabAsst = appState.assistants.find { it.id == sess.assistantId && it.id.isNotBlank() }
                    ?: appState.assistants.firstOrNull()
                val tabProf = appState.profiles.find { it.id == sess.profileId }
                    ?: appState.profiles.firstOrNull()
                val tabModelShort = sess.modelOverride.ifBlank { tabProf?.defaultModel.orEmpty() }
                    .substringAfterLast('/')
                    .take(22)
                val asstName = tabAsst?.name?.takeIf { it.isNotBlank() } ?: "Default Assistant"
                val cardName = (sess.activeCard ?: tabAsst?.activeCard)?.name?.takeIf { it.isNotBlank() }
                val rawTitle = sess.title.ifBlank { "New Chat" }
                val primaryTitle = if (rawTitle == "New Chat" && cardName != null) {
                    "$cardName • New Chat"
                } else {
                    rawTitle
                }
                val contextDetail = when {
                    isTabStreaming -> "Streaming…"
                    sess.messages.isNotEmpty() -> "${sess.messages.size} msgs"
                    tabModelShort.isNotBlank() -> tabModelShort
                    else -> "Empty"
                }
                val subtitleText = "$asstName • $contextDetail"
                val tabShape = RoundedCornerShape(8.dp)

                Row(
                    modifier = Modifier
                        .clip(tabShape)
                        .background(if (isSelected) p.cardBg else p.elevatedBg.copy(alpha = 0.55f))
                        .border(
                            width = if (isSelected) 1.2.dp else 0.75.dp,
                            color = if (isSelected) p.accent else p.border.copy(alpha = 0.65f),
                            shape = tabShape
                        )
                        .kissClickable(pressScale = 0.98f) { onSelectTab(tabId) }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (isTabStreaming) {
                        KissStreamingPulseDot(color = p.accent)
                    } else {
                        SepiaAvatarCircle(
                            symbol = tabAsst?.iconEmoji ?: "💬",
                            selected = isSelected,
                            sizeDp = 22.dp
                        )
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
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 11.5.sp
                            )
                        )
                        BasicText(
                            text = subtitleText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiLabelStyle.copy(
                                color = if (isSelected) p.accent else p.mutedInk,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 9.5.sp
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
                compact = true,
                style = SepiaButtonStyle.GHOST,
                onClick = onNewTab
            )
        }

        Spacer(Modifier.width(8.dp))

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
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable { onCloseModal() },
        contentAlignment = if (isCompactScreen) Alignment.BottomCenter else Alignment.Center
    ) {
        val sheetShape = if (isCompactScreen) {
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
        } else {
            RoundedCornerShape(14.dp)
        }
        Column(
            modifier = Modifier
                .then(if (isCompactScreen) Modifier.fillMaxWidth() else Modifier.widthIn(max = 860.dp).fillMaxWidth(0.92f))
                .fillMaxHeight(if (isCompactScreen) 0.84f else 0.80f)
                .clip(sheetShape)
                .background(p.sheetBg)
                .border(1.dp, p.border, sheetShape)
                .clickable(enabled = false) {}
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    KissVectorIcon(icon = KissIcon.TABS, tint = p.accent, size = 16.dp)
                    BasicText(
                        text = "Open Tabs (${openTabIds.size})" + if (runningTotal > 0) " • $runningTotal active" else "",
                        style = p.uiHeaderStyle.copy(fontSize = 16.sp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (emptyTabsCount >= 2) {
                        SepiaButton(
                            text = "🗑 Empty ($emptyTabsCount)",
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = onCloseEmptyTabs
                        )
                    }
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

            val rows = remember(filteredTabIds) { filteredTabIds.chunked(2) }
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
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            BasicText(
                                                text = asstName,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = p.uiLabelStyle.copy(fontSize = 10.5.sp, color = p.accent, fontWeight = FontWeight.SemiBold)
                                            )
                                            if (modelLabel.isNotBlank()) {
                                                BasicText(
                                                    text = modelLabel,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = p.monoStyle.copy(fontSize = 10.sp, color = p.mutedInk)
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

                                    Spacer(Modifier.height(6.dp))

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
                                                style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                                            )
                                        }
                                    } else {
                                        val dateSuffix = if (appState.preferences.showChatListDates && sess.updatedAt > 0L) {
                                            " • ${formatCompactTimestamp(sess.updatedAt)}"
                                        } else ""
                                        BasicText(
                                            text = "${sess.messages.size} messages$dateSuffix",
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
                                maxLines = if (isCompactScreen) 2 else 1,
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

                    val shortModelLabel = if (isCompactScreen && modelLabel.contains('/')) {
                        modelLabel.substringAfterLast('/')
                    } else {
                        modelLabel
                    }
                    val asstSuffix = if (activeAssistant.id.isNotBlank() && !isCompactScreen) " • ${activeAssistant.name}" else ""
                    val capsuleText = if (appState.preferences.showProviderInModelCapsule) {
                        "$shortModelLabel • ${activeProfile.name}$asstSuffix"
                    } else {
                        "$shortModelLabel$asstSuffix"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.kissClickable(hoverScale = 1.01f) { onToggleQuickModelPopover() }
                    ) {
                        BasicText(
                            text = capsuleText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiLabelStyle.copy(color = p.accent, fontSize = 11.sp),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        KissVectorIcon(
                            icon = if (showQuickModelPopover) KissIcon.CHEVRON_UP else KissIcon.CHEVRON_DOWN,
                            tint = p.accent,
                            size = 11.dp
                        )
                    }
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
                    label = if (chatArtifactsCount > 0) "📁 $chatArtifactsCount" else "📁",
                    selected = showRightFilesSidebar,
                    onClick = onToggleRightFilesSidebar
                )
                SepiaChip(
                    label = "✎",
                    selected = showSessionInspector || hasChatNotes,
                    onClick = onToggleSessionInspector
                )
                SepiaButton(
                    text = "⚙",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = { onOpenSettings(null) }
                )
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
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.cardBg)
                    .border(0.95.dp, p.accent.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                    .padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        KissVectorIcon(icon = KissIcon.CPU, tint = p.accent, size = 14.dp)
                        BasicText(
                            text = "Quick Switch Model",
                            style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        )
                        BasicText(
                            text = "($totalModelsCount)",
                            style = p.monoStyle.copy(color = p.mutedInk, fontSize = 11.sp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SepiaButton(
                            text = if (isCompactScreen) "⚙ Providers" else "⚙ Manage Providers",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = {
                                onToggleQuickModelPopover()
                                onOpenSettings(SettingsCategory.PROVIDERS)
                            }
                        )
                        SepiaButton(
                            text = "×",
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

// ============================================================================================
// PARCHMENT `[ Assistants | Chats ]` LEFT SIDEBAR
// ============================================================================================
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
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val hdrIcon = remember(activeAssistant.iconEmoji) {
                        parseKissLabelIcons(activeAssistant.iconEmoji).leadingIcon ?: KissIcon.SPARKLES
                    }
                    KissVectorIcon(icon = hdrIcon, tint = p.accent, size = 13.dp)
                    BasicText(
                        text = "${activeAssistant.name} (${assistantTopics.size})",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiBodyStyle.copy(
                                        fontSize = 13.5.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected || rowHovered) p.accent else p.primaryInk
                                    )
                                )
                            }
                            val msgCountLabel = if (sess.messages.isNotEmpty()) "${sess.messages.size} messages" else "Empty chat"
                            val dateLabel = if (appState.preferences.showChatListDates && sess.updatedAt > 0L) {
                                formatCompactTimestamp(sess.updatedAt)
                            } else ""
                            val combinedSub = if (dateLabel.isNotEmpty()) "$msgCountLabel • $dateLabel" else if (sess.messages.isNotEmpty()) msgCountLabel else ""
                            if (combinedSub.isNotEmpty()) {
                                BasicText(
                                    text = combinedSub,
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
// BUILT-IN ZOOMABLE IMAGE LIGHTBOX & TEXT/CODE ATTACHMENT VIEWER MODAL (ZERO-DIM STUDIO CARD)
// ============================================================================================
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            ),
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
                        .widthIn(max = 1040.dp)
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(0.88f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.cardBg)
                        .border(1.3.dp, p.accent, RoundedCornerShape(14.dp))
                        .clickable(enabled = false) {}
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.sheetBg.copy(alpha = 0.85f))
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
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiHeaderStyle.copy(fontSize = 14.5.sp)
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
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SepiaButton(
                                text = "🔍−",
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
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { PlatformBridge.openOrPlayMedia(openTarget) }
                                )
                            }
                            if (item.localFilePath.isNotBlank()) {
                                SepiaButton(
                                    text = "📥",
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
                                text = "✕",
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
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.sheetBg.copy(alpha = 0.55f))
                            .border(0.75.dp, p.border.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
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
                                style = p.uiHeaderStyle.copy(color = p.narrationInk)
                            )
                        }
                    }
                }
            }

            is ActiveMediaViewerItem.TextOrCodeViewer -> {
                var copied by remember(item) { mutableStateOf(false) }
                val linesCount = remember(item.textContent) { item.textContent.lines().size }
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
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.cardBg)
                        .border(1.3.dp, p.accent, RoundedCornerShape(14.dp))
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
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiHeaderStyle.copy(fontSize = 15.5.sp)
                                )
                            }
                            BasicText(
                                text = "$linesCount lines • ${item.textContent.length} chars • ${item.mimeType}",
                                style = p.uiLabelStyle
                            )
                        }

                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isSvgContent || isMarkdownContent) {
                                SepiaButton(
                                    text = if (showRenderedPreview) "‹/›" else "👁",
                                    compact = true,
                                    style = if (showRenderedPreview) SepiaButtonStyle.SECONDARY else SepiaButtonStyle.OUTLINE,
                                    onClick = { showRenderedPreview = !showRenderedPreview }
                                )
                            }
                            SepiaButton(
                                text = if (copied) "✓" else "📋",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    clipboard.setText(AnnotatedString(item.textContent))
                                    copied = true
                                }
                            )
                            SepiaButton(
                                text = "📥",
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
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { PlatformBridge.openOrPlayMedia(item.localFilePath) }
                                )
                            }
                            SepiaButton(
                                text = "✕",
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
                                    style = p.monoStyle.copy(fontSize = 12.5.sp, lineHeight = 18.sp)
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
// RIGHT CHAT FILES, ARTIFACTS & SOURCES SIDEBAR (`ParchmentRightFilesSidebar`)
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

    val codeFenceRegex = Regex("```([a-zA-Z0-9_+-]*)\\n([\\s\\S]*?)```")

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
                        val badge = if (low == "fs_write") "✦ AI WROTE" else "✦ AI EDITED"
                        aiArtifacts.add(
                            AiGeneratedChatArtifact.WorkspaceFileArtifact(
                                turnNumber = turnNum,
                                filePath = path,
                                actionBadge = badge,
                                content = content.ifBlank { tc.resultText },
                                lineCount = content.lines().size.coerceAtLeast(1)
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
                    val lines = code.lines().size
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
    val (aiArtifacts, userUploads, webSources) = remember(
        activeSession.id,
        activeSession.messages,
        pendingAttachments
    ) {
        extractSessionVaultItems(activeSession, pendingAttachments)
    }
    val totalCount = aiArtifacts.size + userUploads.size + webSources.size

    Column(
        modifier = modifier
            .background(p.sheetBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    style = p.uiHeaderStyle.copy(fontSize = 14.5.sp)
                )
            }
            SepiaButton(
                text = "×",
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
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KissVectorIcon(icon = KissIcon.FOLDER, tint = p.mutedInk, size = 26.dp)
                    BasicText(
                        text = "No Files or Sources Yet",
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            text = "AI-GENERATED FILES & MEDIA (${aiArtifacts.size})",
                            style = p.uiLabelStyle.copy(
                                color = p.accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
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
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(p.cardBg)
                                        .border(0.9.dp, p.accent.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                                        .clickable {
                                            val wsRoot = activeSession.workspacePath
                                            val liveText = if (wsRoot.isNotBlank()) {
                                                PlatformBridge.workspaceRead(wsRoot, item.filePath, 1, 2000).getOrNull()?.let { raw ->
                                                    raw.lines().drop(1).joinToString("\n") { it.replaceFirst(Regex("^\\d+:\\s?"), "") }
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
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(p.accent.copy(alpha = 0.15f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            val parsedBadge = remember(item.actionBadge) { parseKissLabelIcons(item.actionBadge) }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                                            ) {
                                                KissVectorIcon(
                                                    icon = parsedBadge.leadingIcon ?: KissIcon.SPARKLES,
                                                    tint = p.accent,
                                                    size = 9.5.dp
                                                )
                                                BasicText(
                                                    text = parsedBadge.cleanText.ifEmpty { item.actionBadge },
                                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 9.5.sp)
                                                )
                                            }
                                        }
                                        BasicText(
                                            text = "Turn #${item.turnNumber} • ${item.lineCount} lines",
                                            style = p.uiLabelStyle.copy(fontSize = 10.sp, color = p.mutedInk)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.DIFF, tint = p.accent, size = 13.5.dp)
                                        BasicText(
                                            text = shortName,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                        )
                                    }
                                    BasicText(
                                        text = "${item.filePath} • Click to inspect",
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiLabelStyle.copy(fontSize = 10.5.sp, color = p.mutedInk)
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
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(p.cardBg)
                                        .border(0.9.dp, p.accent.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                                        .clickable {
                                            if (isVideo) {
                                                val target = media.localFilePath.ifBlank { media.remoteUrl }
                                                if (target.isNotBlank()) PlatformBridge.openOrPlayMedia(target)
                                            } else {
                                                onInspectGeneratedImage(media)
                                            }
                                        }
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(p.accent.copy(alpha = 0.15f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                                            ) {
                                                KissVectorIcon(
                                                    icon = KissIcon.SPARKLES,
                                                    tint = p.accent,
                                                    size = 9.5.dp
                                                )
                                                BasicText(
                                                    text = if (isVideo) "AI VIDEO" else "AI IMAGE",
                                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 9.5.sp)
                                                )
                                            }
                                        }
                                        BasicText(
                                            text = "Turn #${item.turnNumber}${if (media.aspectRatio.isNotBlank()) " • ${media.aspectRatio}" else ""}",
                                            style = p.uiLabelStyle.copy(fontSize = 10.sp, color = p.mutedInk)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        KissVectorIcon(
                                            icon = if (isVideo) KissIcon.VIDEO else KissIcon.IMAGE,
                                            tint = p.accent,
                                            size = 13.5.dp
                                        )
                                        BasicText(
                                            text = fileLabel,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                        )
                                    }
                                    BasicText(
                                        text = if (isVideo) "Click to play MP4 video" else "Click to zoom image",
                                        style = p.uiLabelStyle.copy(fontSize = 10.5.sp, color = p.accent)
                                    )
                                }
                            }

                            is AiGeneratedChatArtifact.CodeSnippetArtifact -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(p.cardBg)
                                        .border(0.9.dp, p.accent.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                                        .clickable {
                                            onInspectAttachment(
                                                PromptAttachment(
                                                    id = "ai-code-${item.turnNumber}-${item.fileName}",
                                                    fileName = item.fileName,
                                                    mimeType = "text/plain",
                                                    textContent = item.code
                                                )
                                            )
                                        }
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(p.accent.copy(alpha = 0.15f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                                            ) {
                                                KissVectorIcon(
                                                    icon = KissIcon.SPARKLES,
                                                    tint = p.accent,
                                                    size = 9.5.dp
                                                )
                                                BasicText(
                                                    text = "AI ${item.language.uppercase()}",
                                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 9.5.sp)
                                                )
                                            }
                                        }
                                        BasicText(
                                            text = "Turn #${item.turnNumber} • ${item.lineCount} lines",
                                            style = p.uiLabelStyle.copy(fontSize = 10.sp, color = p.mutedInk)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.CODE, tint = p.accent, size = 13.5.dp)
                                        BasicText(
                                            text = item.fileName,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                        )
                                    }
                                    BasicText(
                                        text = "Click to view full code",
                                        style = p.uiLabelStyle.copy(fontSize = 10.5.sp, color = p.mutedInk)
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
                            text = "USER-UPLOADED DOCUMENTS (${userUploads.size})",
                            style = p.uiLabelStyle.copy(
                                color = p.narrationInk,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
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
                                .clip(RoundedCornerShape(8.dp))
                                .background(p.cardBg)
                                .border(0.85.dp, p.border, RoundedCornerShape(8.dp))
                                .clickable { onInspectAttachment(att) }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(p.elevatedBg)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                                    ) {
                                        KissVectorIcon(
                                            icon = KissIcon.UPLOAD,
                                            tint = p.primaryInk,
                                            size = 9.5.dp
                                        )
                                        BasicText(
                                            text = "USER UPLOAD",
                                            style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.Bold, fontSize = 9.5.sp)
                                        )
                                    }
                                }
                                BasicText(
                                    text = "$turnLabel • $kindLabel",
                                    style = p.uiLabelStyle.copy(fontSize = 10.sp, color = p.mutedInk)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    KissVectorIcon(icon = fileIcon, tint = p.accent, size = 13.5.dp)
                                    BasicText(
                                        text = att.fileName,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                    )
                                }
                                if (entry.turnNumber > 0) {
                                    SepiaButton(
                                        text = "@",
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
                            text = "GROUNDED WEB SOURCES (${webSources.size})",
                            style = p.uiLabelStyle.copy(
                                color = p.accentSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
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
                                .clip(RoundedCornerShape(8.dp))
                                .background(p.cardBg)
                                .border(0.85.dp, p.border, RoundedCornerShape(8.dp))
                                .clickable { PlatformBridge.openOrPlayMedia(src.url) }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(p.accent.copy(alpha = 0.16f))
                                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                                    ) {
                                        BasicText(
                                            text = "[${src.index}]",
                                            style = p.monoStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                        )
                                    }
                                    BasicText(
                                        text = src.domain.ifBlank { src.url },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiLabelStyle.copy(fontSize = 10.5.sp, color = p.mutedInk)
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    BasicText(
                                        text = "Turn #${item.turnNumber} • $lineLabel",
                                        style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                                    )
                                    KissVectorIcon(
                                        icon = KissIcon.EXTERNAL_LINK,
                                        tint = p.accent,
                                        size = 9.5.dp
                                    )
                                }
                            }
                            BasicText(
                                text = src.title.ifBlank { src.url },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                            )
                            if (quoteExcerpt.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    KissVectorIcon(
                                        icon = KissIcon.CORNER_DOWN_RIGHT,
                                        tint = p.narrationInk,
                                        size = 9.5.dp
                                    )
                                    BasicText(
                                        text = "$lineLabel: \"$quoteExcerpt\"",
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiLabelStyle.copy(color = p.narrationInk, fontSize = 10.5.sp)
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
// INTERACTIVE CONTEXT WINDOW BREAKDOWN POPOVER (`ContextWindowBreakdownPopover`)
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
    modifier: Modifier = Modifier
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
        if (breakdown.mutedTurnsCount > 0) append(", ${breakdown.mutedTurnsCount} muted")
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
            label = "Free Space Remaining",
            tokens = breakdown.freeSpaceTokens,
            color = colFree,
            isFreeSpace = true
        )
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(p.cardBg)
            .border(1.dp, p.accent.copy(alpha = 0.75f), RoundedCornerShape(11.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(
                    text = "Context Window Breakdown",
                    style = p.uiHeaderStyle.copy(fontSize = 14.5.sp)
                )
                val limitText = if (isUnlimited) "∞" else formatCompactTokens(breakdown.contextLimitTokens)
                val costSuffix = if (sessionTotalCostUsd > 0.0) " • Session ${formatUsdCost(sessionTotalCostUsd)}" else ""
                BasicText(
                    text = "o200k_base • ${breakdown.totalUsedTokens} / $limitText tok ($pctInt% used)$costSuffix",
                    style = p.monoStyle.copy(fontSize = 10.5.sp, color = p.mutedInk)
                )
            }
            SepiaButton(
                text = "×",
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
                .clip(RoundedCornerShape(999.dp))
                .background(p.elevatedBg)
                .border(0.65.dp, p.border.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
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
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
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
                        text = "${formatCompactTokens(slice.tokens)} tok ($slicePct%)",
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

        // Quick Context Management Actions
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (canCompact) {
                SepiaButton(
                    text = if (isStreaming) "⚡ Compact & Resume" else "⚡ Compact Context",
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
                text = "⚙ Limit & Pricing",
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
