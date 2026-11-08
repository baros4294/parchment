package io.parchment.ai.ui

import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.ChatSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParchmentDiskWriterTest {
    private fun chat(id: String, title: String = id) =
        ChatSession(id = id, title = title, createdAt = 1L, updatedAt = 1L, profileId = "profile")

    @Test
    fun debounceRetainsBothChats() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val saved = mutableListOf<String>()
        val complete = CompletableDeferred<Unit>()
        try {
            val writer = ParchmentDiskWriter(scope, {}, { session ->
                saved.add(session.id)
                if (saved.size == 2) complete.complete(Unit)
            }, {}, debounceMs = 50)
            writer.enqueue(sessions = listOf(chat("a")))
            delay(10)
            writer.enqueue(sessions = listOf(chat("b")))
            withTimeout(3_000) { complete.await() }
            assertEquals(setOf("a", "b"), saved.toSet())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun latestUpdateSurvivesAnInFlightWrite() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        try {
            val writer = ParchmentDiskWriter(scope, {}, { session ->
                saved.add(session.title)
                if (session.title == "old") {
                    entered.complete(Unit)
                    runBlocking { resume.await() }
                }
            }, {}, debounceMs = 60_000)
            writer.enqueue(sessions = listOf(chat("a", "old")), notify = false)
            val inFlight = launch(Dispatchers.IO) { writer.flush() }
            withTimeout(3_000) { entered.await() }
            writer.enqueue(sessions = listOf(chat("a", "new")), notify = false)
            resume.complete(Unit)
            inFlight.join()
            writer.flush()
            assertEquals(listOf("old", "new"), saved)
        } finally {
            resume.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun failedWriteIsRetainedWithoutRepeatingCommittedChat() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        var fail = true
        val saved = mutableListOf<String>()
        try {
            val writer = ParchmentDiskWriter(scope, {}, { session ->
                if (session.id == "b" && fail) throw IllegalStateException("disk failure")
                saved.add(session.id)
            }, {}, debounceMs = 60_000)
            writer.enqueue(sessions = listOf(chat("a"), chat("b")), notify = false)
            assertFailsWith<IllegalStateException> { writer.flush() }
            assertEquals("disk failure", writer.lastError.value)
            fail = false
            writer.flush()
            assertEquals(listOf("a", "b"), saved)
            assertEquals(null, writer.lastError.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun deletionAfterInFlightSaveDoesNotResurrectChat() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        try {
            val writer = ParchmentDiskWriter(scope, {}, { session ->
                operations.add("save:" + session.id)
                entered.complete(Unit)
                runBlocking { resume.await() }
            }, { id -> operations.add("delete:" + id) }, debounceMs = 60_000)
            writer.enqueue(sessions = listOf(chat("a")), notify = false)
            val inFlight = launch(Dispatchers.IO) { writer.flush() }
            withTimeout(3_000) { entered.await() }
            writer.enqueue(deletedIds = setOf("a"), notify = false)
            resume.complete(Unit)
            inFlight.join()
            writer.flush()
            assertEquals(listOf("save:a", "delete:a"), operations)
        } finally {
            resume.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun settingsAndMultipleChatsShareOneFlush() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        var savedSettings: AppStateSnapshot? = null
        val savedChats = mutableListOf<String>()
        try {
            val writer = ParchmentDiskWriter(scope, { savedSettings = it }, {
                savedChats.add(it.id)
            }, {}, debounceMs = 60_000)
            writer.enqueue(settings = AppStateSnapshot(activeSessionId = "a", sessions = listOf(chat("a"))), notify = false)
            writer.enqueue(sessions = listOf(chat("a"), chat("b")), notify = false)
            writer.flush()
            assertEquals("a", savedSettings?.activeSessionId)
            assertTrue(savedSettings?.sessions?.isEmpty() == true)
            assertEquals(listOf("a", "b"), savedChats)
            writer.flush()
            assertEquals(2, savedChats.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fullRestoreReplacesQueuedStateAndDeletesRemovedChats() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val operations = mutableListOf<String>()
        try {
            val writer = ParchmentDiskWriter(scope, { operations.add("settings:" + it.activeSessionId) }, {
                operations.add("save:" + it.id)
            }, { operations.add("delete:" + it) }, debounceMs = 60_000)
            writer.enqueue(sessions = listOf(chat("old")), notify = false)
            writer.replaceState(AppStateSnapshot(activeSessionId = "new", sessions = listOf(chat("new"))), setOf("old"))
            writer.flush()
            assertEquals(listOf("settings:new", "delete:old", "save:new"), operations)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun lifecycleFlushIncludesUnsignalledStreamingCheckpoint() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val saved = mutableListOf<String>()
        try {
            val writer = ParchmentDiskWriter(scope, {}, { saved.add(it.title) }, {}, debounceMs = 60_000)
            writer.enqueue(sessions = listOf(chat("a", "stream checkpoint")), notify = false)
            writer.flushBlocking()
            assertEquals(listOf("stream checkpoint"), saved)
        } finally {
            scope.cancel()
        }
    }
}
