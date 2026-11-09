package io.parchment.ai.ui

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.QueuedPromptItem
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import io.parchment.ai.runtime.RunLeaseRegistry
import io.parchment.ai.runtime.appRunLeases

data class AgentRunIdentity(
    val runId: String,
    val sessionId: String,
    val nodeId: String,
    val variantId: String,
    val profileId: String,
    val modelId: String,
    val declaredWireDialect: String
)

enum class AgentRunPhase { PREPARING, RUNNING, WAITING_APPROVAL, COMPACTING, INTERRUPTED, STOPPED, COMPLETED }

/** Credentials stay available to the owning run without appearing in runtime diagnostics. */
class RunProviderContext(internal val profile: ProviderProfile) {
    override fun toString(): String = "RunProviderContext(profileId=${profile.id})"
}

/** UI consumers share these owners; disposing a screen cannot dispose its jobs or approval broker. */
internal class AgentRunCoordinator(
    private val completionScope: CoroutineScope = appProcessScope,
    private val leases: RunLeaseRegistry = RunLeaseRegistry()
) {
    val streams = mutableStateOf<Map<String, SessionStreamRuntime>>(emptyMap())
    val lastPhases = mutableStateOf<Map<String, AgentRunPhase>>(emptyMap())
    private val workers = MutableStateFlow<Map<Job, SessionStreamRuntime>>(emptyMap())

    /** Keep draining workers after their tab/status has disappeared; a new run waits for them. */
    fun track(runtime: SessionStreamRuntime) {
        val job = runtime.job ?: return
        if (runtime.isPreparingHandoff) return
        var firstRegistration: Boolean
        while (true) {
            val before = workers.value
            if (workers.compareAndSet(before, before + (job to runtime))) {
                firstRegistration = job !in before
                break
            }
        }
        if (firstRegistration) job.invokeOnCompletion { cause ->
            while (true) {
                val before = workers.value
                if (workers.compareAndSet(before, before - job)) break
            }
            completionScope.launch {
                val current = streams.value[runtime.sessionId]
                if (current?.job === job) {
                    val phase = if (current.phase == AgentRunPhase.STOPPED) AgentRunPhase.STOPPED
                        else if (cause == null) AgentRunPhase.COMPLETED else AgentRunPhase.INTERRUPTED
                    current.identity?.let { finish(it, phase) } ?: run { streams.value = streams.value - runtime.sessionId }
                }
            }
        }
    }

    fun attach(identity: AgentRunIdentity, runtime: SessionStreamRuntime, cancelPrevious: Boolean = true) {
        require(identity.sessionId == runtime.sessionId)
        val previous = streams.value[identity.sessionId]
        if (previous?.identity == identity) require(previous.job === runtime.job) { "A run identity cannot be reused for another worker." }
        if (cancelPrevious && previous?.job !== runtime.job) previous?.job?.cancel()
        val bound = runtime.copy(identity = identity,
            providerContext = previous?.takeIf { it.identity == identity }?.providerContext ?: runtime.providerContext)
        streams.value = streams.value + (identity.sessionId to bound)
        track(bound)
    }

    fun update(identity: AgentRunIdentity, transform: (SessionStreamRuntime) -> SessionStreamRuntime): Boolean {
        val current = streams.value[identity.sessionId] ?: return false
        if (current.identity != identity || current.phase == AgentRunPhase.STOPPED) return false
        val next = transform(current).copy(identity = identity, providerContext = current.providerContext)
        streams.value = streams.value + (identity.sessionId to next)
        track(next)
        return true
    }

    fun finish(identity: AgentRunIdentity, phase: AgentRunPhase): Boolean {
        val current = streams.value[identity.sessionId] ?: return false
        if (current.identity != identity) return false
        val finalPhase = if (current.phase == AgentRunPhase.STOPPED) AgentRunPhase.STOPPED else phase
        streams.value = streams.value - identity.sessionId
        val updated = lastPhases.value.filterKeys { it != identity.sessionId } + (identity.sessionId to finalPhase)
        lastPhases.value = updated.entries.toList().takeLast(12).associate { it.key to it.value }
        return true
    }

    fun requestStop(identity: AgentRunIdentity): Boolean {
        val current = streams.value[identity.sessionId] ?: return false
        if (current.identity != identity) return false
        streams.value = streams.value + (identity.sessionId to current.copy(phase = AgentRunPhase.STOPPED))
        lastPhases.value = (lastPhases.value.filterKeys { it != identity.sessionId } +
            (identity.sessionId to AgentRunPhase.STOPPED)).entries.toList().takeLast(12).associate { it.key to it.value }
        current.job?.cancel()
        return true
    }

    fun owns(identity: AgentRunIdentity): Boolean = streams.value[identity.sessionId]?.identity == identity

    fun prepare(
        identity: AgentRunIdentity,
        runtime: SessionStreamRuntime,
        scope: CoroutineScope,
        start: suspend (List<SessionStreamRuntime>) -> Unit
    ): Job {
        val previousWorkers = workers.value.values.filter { it.sessionId == identity.sessionId && it.job?.isCompleted == false }
        val preparing = leases.launch(scope, identity.modelId, start = CoroutineStart.LAZY) {
            try {
                previousWorkers.forEach { it.job?.cancel() }
                previousWorkers.forEach { it.job?.join() }
                currentCoroutineContext().ensureActive()
                if (owns(identity)) start(previousWorkers)
            } finally {
                if (owns(identity)) finish(identity, AgentRunPhase.INTERRUPTED)
            }
        }
        attach(identity, runtime.copy(job = preparing, phase = AgentRunPhase.PREPARING, isPreparingHandoff = true))
        preparing.start()
        return preparing
    }
}

internal fun refreshGenerationHistory(requested: List<MessageNode>, persistedIds: Set<String>, current: ChatSession): List<MessageNode> {
    val currentById = current.messages.associateBy { it.id }
    return requested.mapNotNull { node ->
        val latest = currentById[node.id]
        if (latest == null) node.takeIf { it.id !in persistedIds }
        else {
            val selectedId = node.activeVariant.id
            val selectedIndex = latest.variants.indexOfFirst { it.id == selectedId }
            if (selectedIndex < 0) latest else latest.copy(activeVariantIndex = selectedIndex)
        }
    }
}

internal class ParchmentProcessState(initial: AppStateSnapshot, needsHydration: Boolean = false,
    completionScope: CoroutineScope = appProcessScope, leases: RunLeaseRegistry = RunLeaseRegistry()) {
    val snapshot = mutableStateOf(initial)
    val needsBackgroundHydration = mutableStateOf(needsHydration)
    val runs = AgentRunCoordinator(completionScope, leases)
    val queuedPrompts = mutableStateOf<Map<String, List<QueuedPromptItem>>>(emptyMap())
    val pendingAttachments = mutableStateOf<Map<String, List<PromptAttachment>>>(emptyMap())
    val notice = mutableStateOf("")
    val frameClock = mutableStateOf<MonotonicFrameClock?>(null)

    fun attachClock(clock: MonotonicFrameClock?) { frameClock.value = clock }
    fun detachClock(clock: MonotonicFrameClock?) {
        if (frameClock.value === clock) frameClock.value = null
    }

    suspend fun nextFrameNanos(): Long? {
        val clock = frameClock.value ?: return null
        return try {
            withTimeoutOrNull(28L) { clock.withFrameNanos { it } }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            detachClock(clock)
            null
        } catch (_: IllegalStateException) {
            detachClock(clock)
            null
        }
    }
}

internal val parchmentProcessState by lazy {
    val (snapshot, needsHydration) = loadFastInitialAppState()
    ParchmentProcessState(snapshot, needsHydration, leases = appRunLeases)
}
