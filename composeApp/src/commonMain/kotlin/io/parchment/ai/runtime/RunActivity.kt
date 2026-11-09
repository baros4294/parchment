package io.parchment.ai.runtime

import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Process-local durations only; wall-clock timestamps remain a separate persistence contract. */
internal class RunActivity(
    private val nowMs: () -> Long = PlatformBridge::monotonicTimeMillis,
    private val onRunnable: (Boolean) -> Unit = {},
    private val maxUnobservedGapMs: Long = 5_000L
) {
    private enum class Work { RUNNING, WAITING, COMPLETE }
    private val lock = Any()
    private var sampledAt = nowMs()
    private var elapsed = 0L
    private var tools = 0L
    private var sequence = 0L
    private var rootWaiting = false
    private var branches = emptyMap<Long, Work>()
    private val waitingState = MutableStateFlow(false)
    val waitingForApproval: StateFlow<Boolean> = waitingState.asStateFlow()

    private fun runnable(): Boolean = if (branches.isEmpty()) !rootWaiting
        else branches.values.any { it == Work.RUNNING } || branches.values.all { it == Work.COMPLETE }

    private fun sample() {
        val now = nowMs()
        val delta = (now - sampledAt).coerceAtLeast(0L)
        sampledAt = maxOf(sampledAt, now)
        // A one-second heartbeat observes ordinary silent network/tool waits. An unobserved
        // scheduling gap over five seconds is excluded (suspend or a stalled process).
        if (delta <= maxUnobservedGapMs && runnable()) {
            elapsed += delta
            if (branches.isNotEmpty()) tools += delta
        }
    }

    private fun publish() {
        val waiting = !runnable()
        if (waitingState.value != waiting) {
            waitingState.value = waiting
            onRunnable(!waiting)
        }
    }

    val elapsedMs: Long get() = synchronized(lock) { sample(); elapsed }
    val toolMs: Long get() = synchronized(lock) { sample(); tools }
    fun pulse() = synchronized(lock) { sample() }

    fun setWaiting(branch: Long, waiting: Boolean) = synchronized(lock) {
        sample()
        if (branch == 0L) rootWaiting = waiting
        else if (branches[branch] != null && branches[branch] != Work.COMPLETE) {
            branches = branches + (branch to if (waiting) Work.WAITING else Work.RUNNING)
        }
        publish()
    }

    fun completeBranch(branch: Long) = synchronized(lock) {
        sample()
        if (branch in branches) branches = branches + (branch to Work.COMPLETE)
        publish()
    }

    fun beginTools(parallelism: Int): ToolBatch = synchronized(lock) {
        require(parallelism > 0 && branches.isEmpty())
        sample()
        val ids = List(parallelism) { ++sequence }
        branches = ids.associateWith { Work.RUNNING }
        publish()
        ToolBatch(ids)
    }

    inner class ToolBatch internal constructor(val branches: List<Long>) {
        private var closed = false
        fun close() = synchronized(lock) {
            if (!closed) {
                sample()
                closed = true
                this@RunActivity.branches = emptyMap()
                publish()
            }
        }
    }
}

internal class RunActivityContext(val activity: RunActivity, val branch: Long = 0L) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunActivityContext>
}

/** Absolute snapshots reconcile platform holds; closing an old lease cannot remove a new owner. */
internal class RunLeaseRegistry(
    private val synchronize: (owners: Int, runnable: Int, status: String) -> Unit = { _, _, _ -> },
    private val clock: () -> Long = PlatformBridge::monotonicTimeMillis
) {
    private data class Owner(val model: String, val runnable: Boolean = true)
    private val lock = Any()
    private var sequence = 0L
    private val owners = mutableMapOf<Long, Owner>()

    private fun publish() {
        val runnable = owners.values.count { it.runnable }
        val status = when {
            owners.isEmpty() -> "Parchment — Literary & Bleeding-Edge AI Studio"
            runnable == 0 -> "Awaiting approval (${owners.size} runs)"
            owners.size == 1 -> "Working (${owners.values.first().model})…"
            else -> "$runnable working / ${owners.size} runs"
        }
        synchronize(owners.size, runnable, status)
    }

    fun open(model: String): Lease = synchronized(lock) {
        val id = ++sequence
        owners[id] = Owner(model)
        val activity = RunActivity(clock, { runnable ->
            synchronized(lock) {
                owners[id]?.let { owners[id] = it.copy(runnable = runnable); publish() }
            }
        })
        publish()
        Lease(id, activity)
    }

    inner class Lease internal constructor(private val id: Long, val activity: RunActivity) {
        fun close() = synchronized(lock) { if (owners.remove(id) != null) publish() }
        suspend fun heartbeat() {
            var ticks = 0
            while (currentCoroutineContext().isActive) {
                delay(1_000L)
                activity.pulse()
                if (++ticks % 15 == 0) synchronized(lock) { if (id in owners) publish() }
            }
        }
    }

    fun launch(
        scope: CoroutineScope,
        model: String,
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.(RunActivity) -> Unit
    ): Job {
        val lease = open(model)
        val job = scope.launch(context + RunActivityContext(lease.activity), start) {
            val heartbeat = launch { lease.heartbeat() }
            try { block(lease.activity) } finally { heartbeat.cancel() }
        }
        job.invokeOnCompletion { lease.close() }
        return job
    }
}

internal val appRunLeases = RunLeaseRegistry(PlatformBridge::synchronizeBackgroundExecution)

/** Streams called inside a run inherit its owner; standalone/utility streams get an owner too. */
internal fun <T> runActivityFlow(model: String, block: suspend FlowCollector<T>.(RunActivity) -> Unit): Flow<T> = flow {
    val inherited = currentCoroutineContext()[RunActivityContext]
    val lease = if (inherited == null) appRunLeases.open(model) else null
    val activity = inherited?.activity ?: requireNotNull(lease).activity
    val heartbeat = lease?.let { CoroutineScope(currentCoroutineContext()).launch { it.heartbeat() } }
    try {
        emitAll(flow { block(activity) }.flowOn(inherited ?: RunActivityContext(activity)))
    } finally {
        heartbeat?.cancel()
        lease?.close()
    }
}
