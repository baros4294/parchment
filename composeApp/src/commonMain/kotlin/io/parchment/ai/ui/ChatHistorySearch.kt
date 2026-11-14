package io.parchment.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.parchment.ai.model.ChatSession
import io.parchment.ai.platform.PlatformStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.coroutineContext

internal fun chatMessagesContain(session: ChatSession, query: String): Boolean =
    session.messages.any { it.activeVariant.content.contains(query, ignoreCase = true) }

/** The text check skips decoding the many stored chats that cannot match. */
internal fun storedChatJsonContains(rawJson: String, chatId: String, query: String): Boolean {
    val encoded = compactStateJson.encodeToString(String.serializer(), query).removeSurrounding("\"")
    if (!rawJson.contains(encoded, ignoreCase = true)) return false
    val decoded = runCatching { compactStateJson.decodeFromString(ChatSession.serializer(), rawJson) }.getOrNull() ?: return false
    return decoded.id == chatId && chatMessagesContain(decoded, query)
}

private fun storedChatContains(chatId: String, query: String): Boolean {
    appDiskWriter.pendingFullSession(chatId)?.let { return chatMessagesContain(it, query) }
    val raw = runCatching { PlatformStorage.loadChatSessionJsonById(chatId) }.getOrNull() ?: return false
    return storedChatJsonContains(raw, chatId, query)
}

internal data class HistorySearch(val matchingIds: Set<String> = emptySet(), val inProgress: Boolean = false)

/** Searches chats that are not loaded in memory, adding matches as they are found. */
@Composable
internal fun rememberHistorySearch(storedChatIds: List<String>, rawQuery: String): HistorySearch {
    val query = rawQuery.trim()
    var state by remember { mutableStateOf(HistorySearch()) }
    LaunchedEffect(storedChatIds, query) {
        state = HistorySearch(inProgress = query.isNotEmpty() && storedChatIds.isNotEmpty())
        if (!state.inProgress) return@LaunchedEffect
        delay(250) // Typing replaces this effect, so only a settled query reads the disk.
        withContext(Dispatchers.Default) {
            for (id in storedChatIds) {
                coroutineContext.ensureActive()
                if (storedChatContains(id, query)) state = state.copy(matchingIds = state.matchingIds + id)
            }
        }
        state = state.copy(inProgress = false)
    }
    return state
}
