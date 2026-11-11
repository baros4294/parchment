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
    private val debounceMs: Long = 250L,
    private val retryInitialMs: Long = 250L,
    private val retryMaxMs: Long = 30_000L
) {
    private data class SettingsWrite(
        val revision: Long,
        val snapshot: AppStateSnapshot,
        val requiredChats: Map<String, Long> = emptyMap()
    )
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
        require(debounceMs >= 0L && retryInitialMs > 0L && retryMaxMs >= retryInitialMs)
        scope.launch(Dispatchers.IO) {
            for (ignored in wakeups) {
                if (!pending.value.urgent && debounceMs > 0L) delay(debounceMs)
                var retryDelay = retryInitialMs
                while (true) {
                    try {
                        flush()
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Retain failures and retry without waiting for another user edit.
                        delay(retryDelay)
                        retryDelay += minOf(retryDelay, retryMaxMs - retryDelay)
                    }
                }
            }
        }
    }

    fun enqueue(
        settings: AppStateSnapshot? = null,
        sessions: List<ChatSession> = emptyList(),
        deletedIds: Set<String> = emptySet(),
        urgent: Boolean = false,
        notify: Boolean = true,
        settingsRequiresSessions: Boolean = false
    ) {
        pending.update { old ->
            val revision = old.revision + 1L
            val chats = old.chats.toMutableMap()
            deletedIds.forEach { chats[it] = ChatWrite(revision, null) }
            sessions.forEach { session ->
                val fullPending = chats[session.id]?.session?.takeUnless { it.isCompactStub }
                val value = if (session.isCompactStub && fullPending != null) mergeSessionMetadata(fullPending, session) else session
                chats[session.id] = ChatWrite(revision, value)
            }
            Pending(
                revision = revision,
                settings = settings?.let {
                    val required = old.settings?.requiredChats.orEmpty().filterKeys { id -> id !in deletedIds } +
                        if (settingsRequiresSessions) sessions.associate { session -> session.id to revision } else emptyMap()
                    SettingsWrite(revision, it.copy(sessions = emptyList()), required)
                } ?: old.settings?.let { it.copy(requiredChats = it.requiredChats.filterKeys { id -> id !in deletedIds }) },
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
            snapshot.sessions.forEach { session ->
                val fullPending = old.chats[session.id]?.session?.takeUnless { it.isCompactStub }
                val value = if (session.isCompactStub && fullPending != null) mergeSessionMetadata(fullPending, session) else session
                chats[session.id] = ChatWrite(revision, value)
            }
            val required = snapshot.sessions.associate { it.id to revision }
            Pending(revision, SettingsWrite(revision, snapshot.copy(sessions = emptyList()), required), chats, urgent = true)
        }
        wakeups.trySend(Unit)
    }

    fun pendingFullSession(sessionId: String): ChatSession? = pending.value.chats[sessionId]?.session?.takeUnless { it.isCompactStub }

    suspend fun flush() = writeMutex.withLock {
        val batch = pending.value
        var failure: Exception? = null
        fun attempt(write: () -> Unit): Boolean = try {
            write()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (failure == null) failure = error
            false
        }
        for ((id, entry) in batch.chats) {
            if (attempt {
                    val session = entry.session
                    if (session == null) deleteSession(id) else saveSession(session)
                }) {
                pending.update { latest ->
                    latest.copy(
                        chats = if (latest.chats[id]?.revision == entry.revision) latest.chats - id else latest.chats,
                        settings = latest.settings?.let { settings ->
                            if ((settings.requiredChats[id] ?: Long.MAX_VALUE) <= entry.revision) {
                                settings.copy(requiredChats = settings.requiredChats - id)
                            } else settings
                        }
                    )
                }
            }
        }
        // A settings migration may strip embedded history; commit it only after its chat files succeed.
        pending.value.settings?.takeIf { it.revision == batch.settings?.revision && it.requiredChats.isEmpty() }?.let { entry ->
                if (attempt { saveSettings(entry.snapshot) }) {
                    pending.update { latest ->
                        if (latest.settings?.revision == entry.revision) latest.copy(settings = null) else latest
                    }
                }
        }
        val error = failure
        if (error != null) {
            errorState.value = error.message?.take(1000) ?: Strings.yourChangesCouldNotBeSaved
            throw error
        } else {
            pending.update { latest ->
                if (latest.revision == batch.revision) latest.copy(urgent = false) else latest
            }
            errorState.value = null
        }
    }

    fun flushBlocking() {
        runBlocking(Dispatchers.IO) { flush() }
    }
}
