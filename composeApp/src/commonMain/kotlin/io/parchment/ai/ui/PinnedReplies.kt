package io.parchment.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.ChatSession

/** Long enough for two lines in the sidebar; chat stubs keep this much of each pinned reply. */
internal const val PIN_PREVIEW_CHARS = 280

private val WHITESPACE = Regex("\\s+")

internal data class PinnedReply(
    val sessionId: String,
    val sessionTitle: String,
    val nodeId: String,
    val preview: String
)

/** Pins in message order within each chat, newest chat first. Stubs list their pins without being loaded. */
internal fun pinnedReplies(sessions: List<ChatSession>, onlySessionId: String? = null): List<PinnedReply> =
    sessions
        .asSequence()
        .filter { onlySessionId == null || it.id == onlySessionId }
        .sortedByDescending { it.updatedAt }
        .flatMap { session ->
            session.messages.asSequence().filter { it.pinned }.map { node ->
                val preview = stripMarkdownToPlainText(node.activeVariant.content.take(PIN_PREVIEW_CHARS))
                    .replace(WHITESPACE, " ")
                    .trim()
                PinnedReply(session.id, session.title, node.id, preview.ifBlank { Strings.replyWithoutText })
            }
        }
        .toList()

internal fun ChatSession.togglePinned(nodeId: String): ChatSession =
    copy(messages = messages.map { if (it.id == nodeId) it.copy(pinned = !it.pinned) else it })

/** The sidebar's Pinned tab: this chat's pins or every chat's, filtered by the sidebar search. */
@Composable
internal fun ColumnScope.PinnedRepliesPanel(
    sessions: List<ChatSession>,
    activeSessionId: String,
    searchQuery: String,
    onOpen: (PinnedReply) -> Unit,
    onUnpin: (PinnedReply) -> Unit
) {
    val p = LocalParchmentPalette.current
    var allChats by remember { mutableStateOf(false) }
    val query = searchQuery.trim()
    val pins = remember(sessions, activeSessionId, allChats, query) {
        pinnedReplies(sessions, onlySessionId = if (allChats) null else activeSessionId).filter {
            query.isEmpty() || it.preview.contains(query, ignoreCase = true) || it.sessionTitle.contains(query, ignoreCase = true)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
        SepiaChip(Strings.thisChat2, selected = !allChats, onClick = { allChats = false })
        SepiaChip(Strings.allChats, selected = allChats, onClick = { allChats = true })
    }
    if (pins.isEmpty()) {
        BasicText(
            text = when {
                query.isNotEmpty() -> Strings.noPinnedRepliesMatchYourSearch
                allChats -> Strings.noPinnedRepliesYetUseThe
                else -> Strings.noPinnedRepliesInThisChat
            },
            style = p.uiLabelStyle,
            modifier = Modifier.padding(horizontal = Space.s4)
        )
        return
    }
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        items(pins, key = { "${it.sessionId}/${it.nodeId}" }) { pin ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.r8))
                    .background(if (hovered) p.accent.copy(alpha = 0.06f) else Color.Transparent),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .kissClickable(pressScale = 0.98f, interactionSource = interaction, onClick = { onOpen(pin) })
                        .padding(start = Space.s10, top = 7.dp, bottom = 7.dp, end = Space.s4),
                    verticalArrangement = Arrangement.spacedBy(Space.s2)
                ) {
                    if (allChats) {
                        BasicText(pin.sessionTitle, style = p.uiLabelStyle.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BasicText(pin.preview, style = p.uiBodyStyle.copy(fontSize = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                SepiaButton(
                    text = "📌",
                    contentDescription = Strings.unpinReply,
                    hint = Strings.removeFromPinned,
                    compact = true,
                    style = SepiaButtonStyle.GHOST,
                    onClick = { onUnpin(pin) }
                )
            }
        }
    }
}
