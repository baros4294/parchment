package io.parchment.ai.harness

import io.parchment.ai.runtime.RunActivityContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class ToolApprovalRequest(
    val requestId: String,
    val runId: String,
    val chatId: String,
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val scope: ToolAccessScope,
    val policyRevision: Long
)

enum class ToolApprovalDecision { ALLOW_ONCE, ALLOW_EXACT_ACTION_FOR_RUN, DENY, STOP }

/** This capability is deliberately not serializable. */
class ToolExecutionPermit internal constructor(
    internal val broker: ToolApprovalBroker,
    internal val scope: ToolAccessScope,
    internal val toolName: String,
    internal val argumentsJson: String,
    internal val policyRevision: Long
) {
    private val consumed = MutableStateFlow(false)
    fun isRevoked(): Boolean = broker.isPermitRevoked(this)
    internal fun claim(scope: ToolAccessScope, name: String, arguments: String): String? {
        if (scope != this.scope || name != toolName || arguments != argumentsJson) return "Approval does not match this exact action."
        broker.permitDenial(this)?.let { return it }
        if (!consumed.compareAndSet(false, true)) return "This one-use execution permit was already consumed."
        return null
    }
}

sealed interface ToolAuthorization {
    data class Allowed(val permit: ToolExecutionPermit) : ToolAuthorization
    data class Denied(val reason: String) : ToolAuthorization
}

/** One broker per logical run, shared by parallel calls and continuation/compaction rounds. */
class ToolApprovalBroker(
    val runId: String,
    val chatId: String,
    private val policySource: () -> ToolAccessScope?,
    private val onStop: () -> Unit = {}
) {
    private data class Action(val name: String, val arguments: String)
    private data class Pending(val request: ToolApprovalRequest, val answer: CompletableDeferred<ToolApprovalDecision>)
    private val mutex = Mutex()
    private val pending = mutableMapOf<String, Pending>()
    private val grants = mutableSetOf<Action>()
    private val denials = mutableSetOf<Action>()
    private val requestsState = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())
    val requests: StateFlow<List<ToolApprovalRequest>> = requestsState.asStateFlow()
    private val closed = MutableStateFlow(false)
    private val revision = MutableStateFlow(0L)
    private var observedScope: ToolAccessScope? = policySource()
    private var observedRevision = 0L
    private var sequence = 0L

    fun currentScope(): ToolAccessScope? = if (closed.value) null else policySource()
    /** Call synchronously from policy/path edits so even an Off→On change invalidates old grants. */
    fun invalidate() { revision.update { it + 1L } }

    private fun reconcileLocked(): ToolAccessScope? {
        val current = currentScope()
        if (current != observedScope || observedRevision != revision.value) {
            if (current != observedScope) invalidate()
            grants.clear()
            denials.clear()
            pending.values.forEach { it.answer.complete(ToolApprovalDecision.DENY) }
            pending.clear()
            requestsState.value = emptyList()
            observedScope = current
            observedRevision = revision.value
        }
        return current
    }

    suspend fun refreshPolicy() { mutex.withLock { reconcileLocked() } }

    suspend fun authorize(callId: String, name: String, argumentsJson: String): ToolAuthorization {
        currentCoroutineContext().ensureActive()
        val action = Action(name, argumentsJson)
        var approval: Pending? = null
        val immediate = mutex.withLock {
            val scope = reconcileLocked() ?: return@withLock ToolAuthorization.Denied("This run is no longer active.")
            AgentPermissionGate.denial(scope, name, argumentsJson)?.let { return@withLock ToolAuthorization.Denied(it) }
            if (action in denials) return@withLock ToolAuthorization.Denied("This action was denied for this run. Change its permissions to reconsider it.")
            if (!AgentPermissionGate.effect(scope, name, argumentsJson).requiresApproval || action in grants) {
                return@withLock ToolAuthorization.Allowed(ToolExecutionPermit(this, scope, name, argumentsJson, revision.value))
            }
            val request = ToolApprovalRequest("$runId:${++sequence}", runId, chatId, callId, name, argumentsJson, scope, revision.value)
            val waiting = Pending(request, CompletableDeferred())
            approval = waiting
            pending[request.requestId] = waiting
            requestsState.value = pending.values.map { it.request }
            null
        }
        if (immediate != null) return immediate
        val waiting = requireNotNull(approval)
        val activity = currentCoroutineContext()[RunActivityContext]
        activity?.activity?.setWaiting(activity.branch, true)
        try {
            val decision = waiting.answer.await()
            currentCoroutineContext().ensureActive()
            if (decision == ToolApprovalDecision.STOP) throw CancellationException("Stopped while awaiting tool approval.")
            return mutex.withLock {
                val scope = reconcileLocked()
                if (scope == null || scope != waiting.request.scope || revision.value != waiting.request.policyRevision) return@withLock ToolAuthorization.Denied("Permissions changed while approval was pending.")
                AgentPermissionGate.denial(scope, name, argumentsJson)?.let { return@withLock ToolAuthorization.Denied(it) }
                when (decision) {
                    ToolApprovalDecision.ALLOW_ONCE, ToolApprovalDecision.ALLOW_EXACT_ACTION_FOR_RUN -> {
                        if (decision == ToolApprovalDecision.ALLOW_EXACT_ACTION_FOR_RUN) grants.add(action)
                        ToolAuthorization.Allowed(ToolExecutionPermit(this, scope, name, argumentsJson, revision.value))
                    }
                    else -> {
                        denials.add(action)
                        ToolAuthorization.Denied("Action denied by the user.")
                    }
                }
            }
        } finally {
            activity?.activity?.setWaiting(activity.branch, false)
            withContext(NonCancellable) {
                mutex.withLock {
                    pending.remove(waiting.request.requestId)
                    requestsState.value = pending.values.map { it.request }
                }
            }
        }
    }

    suspend fun respond(requestId: String, decision: ToolApprovalDecision): Boolean {
        val accepted = mutex.withLock {
            reconcileLocked()
            val waiting = pending[requestId] ?: return@withLock false
            if (decision == ToolApprovalDecision.STOP) {
                closed.value = true
                grants.clear()
                denials.clear()
                pending.values.forEach { it.answer.complete(ToolApprovalDecision.STOP) }
                pending.clear()
                requestsState.value = emptyList()
                true
            } else waiting.answer.complete(decision)
        }
        if (accepted && decision == ToolApprovalDecision.STOP) onStop()
        return accepted
    }

    suspend fun close() {
        mutex.withLock {
            closed.value = true
            reconcileLocked()
        }
    }

    internal fun permitDenial(permit: ToolExecutionPermit): String? {
        val scope = currentScope()
        if (scope == null || scope != permit.scope || revision.value != permit.policyRevision) return "Tool permission was revoked before execution."
        return AgentPermissionGate.denial(scope, permit.toolName, permit.argumentsJson)
    }

    internal fun isPermitRevoked(permit: ToolExecutionPermit): Boolean =
        closed.value || revision.value != permit.policyRevision || currentScope() != permit.scope
}

class AgentToolRunContext(val broker: ToolApprovalBroker?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentToolRunContext>
}
