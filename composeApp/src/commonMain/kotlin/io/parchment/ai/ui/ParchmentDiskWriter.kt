package io.parchment.ai.ui

import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.ChatSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Retains the newest dirty value for every file until its write succeeds.
 * The conflated channel carries wakeups, never snapshots that can discard another chat.
 */
internal class ParchmentDiskWriter(
    scope: CoroutineScope,
    private val saveSettings: (AppStateSnapshot) -> Unit,
    private val saveSession: (ChatSession) -> Unit,
    private val deleteSession: (String) -> Unit,
    private val debounceMs: Long = 250L
) {
    private data class SettingsWrite(val revision: Long, val snapshot: AppStateSnapshot)
    private data class ChatWrite(val revision: Long, val session: ChatSession?)
    private data class Pending(
        val revision: Long = 0L,
        val settings: SettingsWrite? = null,
        val chats: Map<String, ChatWrite> = emptyMap(),
        val urgent: Boolean = false
    )

    private val pending = MutableStateFlow(Pending())
    private val writeMutex = Mutex()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val errorState = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = errorState.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            for (ignored in wakeups) {
                if (!pending.value.urgent && debounceMs > 0L) delay(debounceMs)
                try {
                    flush()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // flush recorded the failure and retained the uncommitted entries.
                }
            }
        }
    }

    fun enqueue(
        settings: AppStateSnapshot? = null,
        sessions: List<ChatSession> = emptyList(),
        deletedIds: Set<String> = emptySet(),
        urgent: Boolean = false,
        notify: Boolean = true
    ) {
        pending.update { old ->
            val revision = old.revision + 1L
            val chats = old.chats.toMutableMap()
            deletedIds.forEach { chats[it] = ChatWrite(revision, null) }
            sessions.forEach { chats[it.id] = ChatWrite(revision, it) }
            Pending(
                revision = revision,
                settings = settings?.let { SettingsWrite(revision, it.copy(sessions = emptyList())) } ?: old.settings,
                chats = chats,
                urgent = old.urgent || urgent
            )
        }
        if (notify) wakeups.trySend(Unit)
    }

    fun replaceState(snapshot: AppStateSnapshot, deletedIds: Set<String>) {
        pending.update { old ->
            val revision = old.revision + 1L
            val chats = deletedIds.associateWith { ChatWrite(revision, null) }.toMutableMap()
            snapshot.sessions.forEach { chats[it.id] = ChatWrite(revision, it) }
            Pending(revision, SettingsWrite(revision, snapshot.copy(sessions = emptyList())), chats, urgent = true)
        }
        wakeups.trySend(Unit)
    }

    suspend fun flush() = writeMutex.withLock {
        val batch = pending.value
        try {
            batch.settings?.let { entry ->
                saveSettings(entry.snapshot)
                pending.update { latest ->
                    if (latest.settings?.revision == entry.revision) latest.copy(settings = null) else latest
                }
            }
            for ((id, entry) in batch.chats) {
                val session = entry.session
                if (session == null) deleteSession(id) else saveSession(session)
                pending.update { latest ->
                    if (latest.chats[id]?.revision == entry.revision) {
                        latest.copy(chats = latest.chats - id)
                    } else latest
                }
            }
            pending.update { latest ->
                if (latest.revision == batch.revision) latest.copy(urgent = false) else latest
            }
            errorState.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            errorState.value = error.message?.take(1000) ?: "Unable to persist local changes."
            throw error
        }
    }

    fun flushBlocking() {
        runBlocking(Dispatchers.IO) { flush() }
    }
}
