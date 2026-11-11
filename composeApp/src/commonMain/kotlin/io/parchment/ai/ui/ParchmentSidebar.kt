package io.parchment.ai.ui

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.ChatSession

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
                        ),
                        // At large text sizes a long label shortens with an ellipsis instead of breaking mid-word.
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Space.s4)
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
