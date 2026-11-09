package io.parchment.ai.ui

import androidx.compose.runtime.MonotonicFrameClock
import io.parchment.ai.harness.ToolAccessScope
import io.parchment.ai.harness.ToolApprovalBroker
import io.parchment.ai.harness.ToolApprovalDecision
import io.parchment.ai.harness.ToolAuthorization
import io.parchment.ai.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class AgentRunCoordinatorTest {
    private fun identity(run: String, session: String = "chat") = AgentRunIdentity(run, session, "node", "variant", "profile", "model", "openai_responses")
    private fun runtime(id: AgentRunIdentity, job: Job? = null) = SessionStreamRuntime(id.sessionId, job = job,
        identity = id, phase = AgentRunPhase.RUNNING)

    @Test fun oldEventsAndCompletionCannotEraseTheNewOwnerOrAnotherChat() = runBlocking {
        val coordinator = AgentRunCoordinator(this)
        val old = identity("old")
        val next = identity("next")
        val other = identity("other", "other-chat")
        val oldJob = Job()
        val nextJob = Job()
        val otherJob = Job()
        try {
            coordinator.attach(old, runtime(old, oldJob))
            coordinator.attach(other, runtime(other, otherJob))
            coordinator.attach(next, runtime(next, nextJob))
            assertTrue(oldJob.isCancelled)
            assertFalse(otherJob.isCancelled)
            assertFalse(coordinator.update(old) { it.copy(statusText = "stale") })
            assertFalse(coordinator.finish(old, AgentRunPhase.COMPLETED))
            assertEquals(next, coordinator.streams.value["chat"]?.identity)
            assertTrue(coordinator.requestStop(next))
            assertTrue(nextJob.isCancelled)
            assertFalse(coordinator.update(next) { it.copy(phase = AgentRunPhase.RUNNING) })
            assertFalse(otherJob.isCancelled)
            coordinator.finish(next, AgentRunPhase.COMPLETED)
            assertEquals(AgentRunPhase.STOPPED, coordinator.lastPhases.value["chat"])
        } finally { oldJob.cancel(); nextJob.cancel(); otherJob.cancel() }
    }

    @Test fun repeatedBindingPreservesTheProducerAndDoesNotCancelItsWorker() = runBlocking {
        val coordinator = AgentRunCoordinator(this)
        val id = identity("run")
        val job = Job()
        val producer = RunProviderContext(ProviderProfile("profile", "Original", ProviderProtocol.OPENAI_RESPONSES,
            apiKey = "original-secret", baseUrl = "https://original.example"))
        val changed = RunProviderContext(producer.profile.copy(apiKey = "new-secret", baseUrl = "https://new.example"))
        try {
            coordinator.attach(id, runtime(id, job).copy(providerContext = producer))
            coordinator.attach(id, runtime(id, job).copy(providerContext = changed))
            assertFalse(job.isCancelled)
            assertSame(producer, coordinator.streams.value["chat"]?.providerContext)
            coordinator.update(id) { it.copy(providerContext = changed) }
            assertSame(producer, coordinator.streams.value["chat"]?.providerContext)
            assertFalse(producer.toString().contains("secret"))
            val replacement = Job()
            try { assertFails { coordinator.attach(id, runtime(id, replacement)) } } finally { replacement.cancel() }
            assertFalse(job.isCancelled)
        } finally { job.cancel() }
    }

    @Test fun rapidReplacementWaitsForTheInvisibleWorkersFinalCheckpoint() = runBlocking {
        val coordinator = AgentRunCoordinator(this)
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var saved = "partial"
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleanupStarted.complete(Unit); releaseCleanup.await(); saved = "final checkpoint" }
            }
        }
        val real = Job()
        try {
            coordinator.attach(identity("old"), runtime(identity("old"), old))
            // Explicit Stop/UI removal does not mean the old worker has finished writing.
            coordinator.streams.value = emptyMap()
            val starts = mutableListOf<String>()
            val firstId = identity("preparing-first")
            val first = coordinator.prepare(firstId, runtime(firstId), this) { starts.add("first") }
            withTimeout(2000) { cleanupStarted.await() }
            val secondId = identity("preparing-second")
            val second = coordinator.prepare(secondId, runtime(secondId), this) { prior ->
                assertEquals("final checkpoint", saved)
                assertEquals("old", prior.single().identity?.runId)
                starts.add("second")
                coordinator.attach(identity("real"), runtime(identity("real"), real), cancelPrevious = false)
            }
            yield()
            assertTrue(starts.isEmpty())
            releaseCleanup.complete(Unit)
            withTimeout(2000) { second.join(); first.join(); old.join() }
            assertEquals(listOf("second"), starts)
            assertEquals("real", coordinator.streams.value["chat"]?.identity?.runId)
            assertFalse(real.isCancelled)
        } finally { releaseCleanup.complete(Unit); old.cancelAndJoin(); real.cancel() }
    }

    @Test fun stoppingPreparationCannotLaunchThePendingRequest() = runBlocking {
        val coordinator = AgentRunCoordinator(this)
        val cleanupStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanupStarted.complete(Unit); release.await() } }
        }
        var started = false
        try {
            coordinator.attach(identity("old"), runtime(identity("old"), old))
            val pending = identity("pending")
            val preparing = coordinator.prepare(pending, runtime(pending), this) { started = true }
            withTimeout(2000) { cleanupStarted.await() }
            assertTrue(coordinator.requestStop(pending))
            release.complete(Unit)
            withTimeout(2000) { preparing.join(); old.join() }
            assertFalse(started)
            assertEquals(AgentRunPhase.STOPPED, coordinator.lastPhases.value["chat"])
        } finally { release.complete(Unit); old.cancelAndJoin() }
    }

    @Test fun aDetachedUiConsumerLeavesTheRunAndApprovalAccessible() = runBlocking {
        val state = ParchmentProcessState(AppStateSnapshot(), needsHydration = true, completionScope = this)
        val consumer = CoroutineScope(Job() + Dispatchers.Unconfined)
        val access = ToolAccessScope(AgentPermissionPolicy(approvalMode = ToolApprovalMode.ASK_EVERY_TOOL), goalModeActive = true)
        val broker = ToolApprovalBroker("run", "chat", { access })
        val worker = async { broker.authorize("call", "calc_eval", """{"expression":"2+2"}""") }
        try {
            val id = identity("run")
            state.runs.attach(id, runtime(id, worker).copy(toolApprovalBroker = broker))
            state.queuedPrompts.value = mapOf("chat" to listOf(QueuedPromptItem("queued", "next instruction")))
            val clock = object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(1L)
            }
            state.attachClock(clock)
            val request = withTimeout(2000) { broker.requests.first { it.isNotEmpty() }.single() }
            consumer.cancel()
            state.detachClock(clock)
            assertFalse(worker.isCompleted)
            assertTrue(state.needsBackgroundHydration.value)
            assertEquals("next instruction", state.queuedPrompts.value["chat"]?.single()?.text)
            assertSame(broker, state.runs.streams.value["chat"]?.toolApprovalBroker)
            broker.respond(request.requestId, ToolApprovalDecision.ALLOW_ONCE)
            assertTrue(worker.await() is ToolAuthorization.Allowed)
        } finally { consumer.cancel(); worker.cancelAndJoin(); broker.close() }
    }

    @Test fun missingOrDisposedFrameClocksDoNotCancelTheOwningWork() = runBlocking {
        val state = ParchmentProcessState(AppStateSnapshot(), completionScope = this)
        assertNull(state.nextFrameNanos())
        val oldClock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = throw CancellationException("UI removed")
        }
        state.attachClock(oldClock)
        assertNull(state.nextFrameNanos())
        assertTrue(currentCoroutineContext().isActive)
        val newClock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(42L)
        }
        state.attachClock(newClock)
        state.detachClock(oldClock)
        assertEquals(42L, state.nextFrameNanos())
    }

    @Test fun ownerCancellationIsPropagatedWhileWaitingForAFrame() = runBlocking {
        val state = ParchmentProcessState(AppStateSnapshot(), completionScope = this)
        val entered = CompletableDeferred<Unit>()
        state.attachClock(object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R { entered.complete(Unit); awaitCancellation() }
        })
        var resumed = false
        val worker = launch { state.nextFrameNanos(); resumed = true }
        withTimeout(2000) { entered.await() }
        worker.cancelAndJoin()
        assertTrue(worker.isCancelled)
        assertFalse(resumed)
    }

    @Test fun historyRefreshPreservesSelectionAndSyntheticInstructionsWithoutRestoringDeletions() {
        val old = MessageNode("old", MessageRole.ASSISTANT,
            variants = listOf(MessageVariant("selected", "partial"), MessageVariant("other", "other branch")))
        val deleted = MessageNode("deleted", MessageRole.USER, variants = listOf(MessageVariant("deleted-v", "deleted instruction")))
        val direction = MessageNode("synthetic", MessageRole.USER, variants = listOf(MessageVariant("direction", "uncapped scene direction")))
        val latest = old.copy(activeVariantIndex = 1,
            variants = listOf(MessageVariant("selected", "final saved answer"), MessageVariant("other", "other branch")))
        val current = ChatSession("chat", "Chat", 1L, 1L, "profile", messages = listOf(latest))
        val refreshed = refreshGenerationHistory(listOf(old, deleted, direction), setOf("old", "deleted"), current)
        assertEquals(listOf("old", "synthetic"), refreshed.map { it.id })
        assertEquals("final saved answer", refreshed.first().activeVariant.content)
        assertEquals("uncapped scene direction", refreshed.last().activeVariant.content)
    }

    @Test fun terminalPhaseRetentionIsBounded() = runBlocking {
        val coordinator = AgentRunCoordinator(this)
        repeat(24) { index ->
            val id = identity("run-$index", "chat-$index")
            coordinator.attach(id, runtime(id))
            coordinator.finish(id, AgentRunPhase.COMPLETED)
        }
        assertEquals(12, coordinator.lastPhases.value.size)
        assertTrue(coordinator.streams.value.isEmpty())
        assertEquals(AgentRunPhase.COMPLETED, coordinator.lastPhases.value["chat-23"])
    }
}
