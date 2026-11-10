package io.parchment.ai.network

import io.ktor.client.request.get
import io.parchment.ai.harness.AgentPermissionGate
import io.parchment.ai.harness.AgentToolRunContext
import io.parchment.ai.harness.ToolAccessScope
import io.parchment.ai.harness.ToolApprovalBroker
import io.parchment.ai.harness.ToolAuthorization
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.GoalTrackerState
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.network.AiProviderEngine.ToolStepBatch
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.runtime.RunActivityContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// Workspace tool authorization and execution for the agent loops.

private suspend fun AiProviderEngine.authorizeWorkspaceTool(
    scope: ToolAccessScope, callId: String, name: String, arguments: String,
    activityContext: RunActivityContext? = null
): ToolAuthorization {
    currentCoroutineContext().ensureActive()
    AgentPermissionGate.denial(scope, name, arguments)?.let { return ToolAuthorization.Denied(it) }
    val broker = currentCoroutineContext()[AgentToolRunContext]?.broker
    if (broker == null && AgentPermissionGate.effect(scope, name, arguments).requiresApproval) {
        return ToolAuthorization.Denied("Tool approval is required, but this run has no approval interface.")
    }
    val activeScope = broker?.currentScope()
    if (broker != null && (activeScope == null || activeScope.policy.workspacePath != scope.policy.workspacePath)) {
        return ToolAuthorization.Denied("The workspace changed. Resume with the current workspace before using tools.")
    }
    val activity = activityContext ?: currentCoroutineContext()[RunActivityContext]
    return withContext(activity ?: EmptyCoroutineContext) {
        (broker ?: ToolApprovalBroker("headless", "", { scope })).authorize(callId, name, arguments)
    }
}

internal suspend fun AiProviderEngine.ensureAgentPolicyCurrent(session: ChatSession, profile: ProviderProfile) {
    currentCoroutineContext().ensureActive()
    val broker = currentCoroutineContext()[AgentToolRunContext]?.broker ?: return
    val expected = AgentPermissionResolver.resolve(session, profile = profile)
    if (broker.currentScope()?.policy != expected) {
        throw kotlinx.coroutines.CancellationException("Agent permissions changed. Resume this turn with the current permissions.")
    }
}

private suspend fun AiProviderEngine.executeWorkspaceToolWithLiveProgress(
    workspaceRoot: String,
    mode: io.parchment.ai.model.WorkspaceMode,
    allowShell: Boolean,
    accessScope: ToolAccessScope,
    callId: String,
    toolName: String,
    argumentsJson: String,
    thoughtSignature: String = "",
    precedingThinking: String = "",
    onStatus: suspend (String) -> Unit,
    onToolCall: suspend (ToolCallRecord) -> Unit,
    activityContext: RunActivityContext?
): ToolCallRecord {
    val intent = WorkspaceHarness.describeToolCallIntent(toolName, argumentsJson)
    if (AgentPermissionGate.effect(accessScope, toolName, argumentsJson).requiresApproval) onStatus("Awaiting approval: $intent")
    val authorization = authorizeWorkspaceTool(accessScope, callId, toolName, argumentsJson, activityContext)
    if (authorization is ToolAuthorization.Denied) return ToolCallRecord(
        id = callId, name = toolName, argumentsJson = argumentsJson,
        resultText = "Error: ${authorization.reason}", isError = true,
        thoughtSignature = thoughtSignature, precedingThinking = precedingThinking
    )
    val permit = (authorization as ToolAuthorization.Allowed).permit
    onStatus("$intent…")
    val initialRunningRecord = ToolCallRecord(
        id = callId,
        name = toolName,
        argumentsJson = argumentsJson,
        resultText = if (toolName.equals("shell_exec", ignoreCase = true)) "Running shell command…" else "Running $toolName…",
        isRunning = true,
        thoughtSignature = thoughtSignature,
        precedingThinking = precedingThinking
    )
    onToolCall(initialRunningRecord)

    val liveChannel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val clock = activityContext?.activity?.let { activity -> { activity.elapsedMs } } ?: PlatformBridge::monotonicTimeMillis
    val startMs = clock()
    // IMPORTANT: Run the blocking tool execution in `async(Dispatchers.IO)` (which NEVER calls `flow.emit()`)
    // and consume `liveChannel` directly on the caller's coroutine so Kotlin's `Flow` context invariant is NEVER violated!
    return kotlinx.coroutines.coroutineScope {
        val workerDeferred = async(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive()
                val workerJob = currentCoroutineContext()[kotlinx.coroutines.Job]!!
                WorkspaceHarness.executeToolCall(
                    workspaceRoot = workspaceRoot,
                    mode = mode,
                    allowShell = allowShell,
                    callId = callId,
                    toolName = toolName,
                    argumentsJson = argumentsJson,
                    accessScope = permit.scope,
                    permit = permit,
                    isCancelled = { !workerJob.isActive || permit.isRevoked() },
                    onOutputDelta = { partial ->
                        liveChannel.trySend(partial)
                    }
                )
            } finally {
                liveChannel.close()
            }
        }
        try {
            for (partialOut in liveChannel) {
                val elapsedSec = ((clock() - startMs) / 100L) / 10.0
                val lastNonEmptyLine = partialOut.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
                val statusHeader = intent.lineSequence().firstOrNull().orEmpty()
                val liveStatus = if (lastNonEmptyLine.isNotEmpty()) {
                    "$statusHeader (${elapsedSec}s)\n$lastNonEmptyLine"
                } else {
                    "$intent (${elapsedSec}s)…"
                }
                onStatus(liveStatus)
                onToolCall(
                    initialRunningRecord.copy(
                        resultText = "[Running ${elapsedSec}s]\n$partialOut",
                        isRunning = true
                    )
                )
            }
            val rawRecord = workerDeferred.await()
            rawRecord.copy(
                isRunning = false,
                durationMs = (clock() - startMs).coerceAtLeast(1L),
                thoughtSignature = thoughtSignature.ifBlank { rawRecord.thoughtSignature },
                precedingThinking = precedingThinking.ifBlank { rawRecord.precedingThinking }
            )
        } finally {
            liveChannel.close()
            workerDeferred.cancel()
        }
    }
}

internal suspend fun AiProviderEngine.executeWorkspaceToolBatchWithLiveProgress(
    workspaceRoot: String,
    mode: io.parchment.ai.model.WorkspaceMode,
    allowShell: Boolean,
    accessScope: ToolAccessScope,
    calls: List<Triple<String, String, String>>, // (callId, toolName, argumentsJson)
    firstThoughtSignature: String = "",
    firstPrecedingThinking: String = "",
    circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
    onStatus: suspend (String) -> Unit,
    onToolCall: suspend (ToolCallRecord) -> Unit,
    onGoalProgress: suspend (GoalTrackerState) -> Unit
): List<ToolCallRecord> {
    if (calls.isEmpty()) return emptyList()
    val firstCallId = calls.first().first
    val onStepToolCall: suspend (ToolCallRecord) -> Unit = { rec ->
        onToolCall(if (rec.id == firstCallId) rec.copy(startsToolStep = true) else rec)
    }
    val canRunBatchInParallel = calls.size > 1 && calls.all { (_, fnName, _) ->
        WorkspaceHarness.isParallelSafeReadOnlyTool(fnName)
    }
    val activity = currentCoroutineContext()[RunActivityContext]?.activity
    val batch = activity?.beginTools(if (canRunBatchInParallel) calls.size else 1)
    try {
        if (!canRunBatchInParallel) {
            val results = mutableListOf<ToolCallRecord>()
            for ((idx, triple) in calls.withIndex()) {
                val (callId, fnName, argsJson) = triple
                val signedRec = executeWorkspaceToolWithLiveProgress(
                    workspaceRoot = workspaceRoot,
                    mode = mode,
                    allowShell = allowShell,
                    accessScope = accessScope,
                    callId = callId,
                    toolName = fnName,
                    argumentsJson = argsJson,
                    thoughtSignature = if (idx == 0) firstThoughtSignature else "",
                    precedingThinking = if (idx == 0) firstPrecedingThinking else "",
                    onStatus = onStatus,
                    onToolCall = onStepToolCall,
                    activityContext = activity?.let { RunActivityContext(it, requireNotNull(batch).branches.single()) }
                )
                val rec = circuitBreaker.onToolExecuted(signedRec)
                circuitBreaker.goalState?.let { onGoalProgress(it) }
                onStepToolCall(rec)
                results.add(rec)
            }
            return results
        }

        // Parallel fast path: emit RUNNING cards for all read-only tools immediately,
        // execute them concurrently on Dispatchers.IO, and emit completed records in deterministic order!
        val summaryNames = calls.map { it.second }.distinct().joinToString(", ")
        onStatus("Executing ${calls.size} read/search tools in parallel ($summaryNames)…")
        for ((idx, triple) in calls.withIndex()) {
            val (callId, fnName, argsJson) = triple
            onStepToolCall(
                ToolCallRecord(
                    id = callId,
                    name = fnName,
                    argumentsJson = argsJson,
                    resultText = "Running $fnName in parallel…",
                    isRunning = true,
                    thoughtSignature = if (idx == 0) firstThoughtSignature else "",
                    precedingThinking = if (idx == 0) firstPrecedingThinking else ""
                )
            )
        }

        val rawCompleted = kotlinx.coroutines.coroutineScope {
            calls.mapIndexed { idx, (callId, fnName, argsJson) ->
                val work = activity?.let { RunActivityContext(it, requireNotNull(batch).branches[idx]) }
                async(Dispatchers.IO + (work ?: EmptyCoroutineContext)) {
                    try {
                        val authorization = authorizeWorkspaceTool(accessScope, callId, fnName, argsJson)
                        currentCoroutineContext().ensureActive()
                        val workerJob = currentCoroutineContext()[kotlinx.coroutines.Job]!!
                        val startedAt = activity?.elapsedMs ?: 0L
                        val raw = if (authorization is ToolAuthorization.Denied) ToolCallRecord(
                            id = callId, name = fnName, argumentsJson = argsJson,
                            resultText = "Error: ${authorization.reason}", isError = true
                        ) else WorkspaceHarness.executeToolCall(
                            workspaceRoot = workspaceRoot,
                            mode = mode,
                            allowShell = allowShell,
                            callId = callId,
                            toolName = fnName,
                            argumentsJson = argsJson,
                            accessScope = (authorization as ToolAuthorization.Allowed).permit.scope,
                            permit = authorization.permit,
                            isCancelled = { !workerJob.isActive || authorization.permit.isRevoked() }
                        )
                        raw.copy(
                            isRunning = false,
                            durationMs = if (authorization is ToolAuthorization.Allowed && activity != null)
                                (activity.elapsedMs - startedAt).coerceAtLeast(1L) else raw.durationMs,
                            thoughtSignature = if (idx == 0) firstThoughtSignature.ifBlank { raw.thoughtSignature } else raw.thoughtSignature,
                            precedingThinking = if (idx == 0) firstPrecedingThinking.ifBlank { raw.precedingThinking } else raw.precedingThinking
                        )
                    } finally { work?.let { it.activity.completeBranch(it.branch) } }
                }
            }.map { it.await() }
        }

        val finalResults = mutableListOf<ToolCallRecord>()
        for (rawRec in rawCompleted) {
            val rec = circuitBreaker.onToolExecuted(rawRec)
            circuitBreaker.goalState?.let { onGoalProgress(it) }
            onStepToolCall(rec)
            finalResults.add(rec)
        }
        return finalResults
    } finally { batch?.close() }
}

internal fun AiProviderEngine.groupToolCallsIntoStepBatches(
    toolCalls: List<ToolCallRecord>,
    fallbackTurnSignature: String,
    fallbackTurnThinking: String
): List<ToolStepBatch> {
    if (toolCalls.isEmpty()) return emptyList()
    val batches = mutableListOf<ToolStepBatch>()
    val hasAnyPrecedingThinking = toolCalls.any { it.precedingThinking.isNotBlank() }
    var currentCalls = mutableListOf<ToolCallRecord>()
    var currentSig = ""
    var currentThink = ""

    for ((idx, tc) in toolCalls.withIndex()) {
        val tcSig = tc.thoughtSignature.ifBlank { if (idx == 0) fallbackTurnSignature else "" }
        val tcThink = tc.precedingThinking.ifBlank { if (idx == 0 && !hasAnyPrecedingThinking) fallbackTurnThinking else "" }
        val startsNewBatch = idx == 0 || tc.startsToolStep || tc.thoughtSignature.isNotBlank() || tc.precedingThinking.isNotBlank()
        if (startsNewBatch) {
            if (currentCalls.isNotEmpty()) {
                batches.add(ToolStepBatch(currentSig, currentThink, currentCalls.toList()))
            }
            currentCalls = mutableListOf(tc)
            currentSig = tcSig
            currentThink = tcThink
        } else {
            currentCalls.add(tc)
        }
    }
    if (currentCalls.isNotEmpty()) {
        batches.add(ToolStepBatch(currentSig, currentThink, currentCalls.toList()))
    }
    return batches
}

/**
 * Tool-call IDs that any wire accepts, stable across requests and paired between call and result.
 * Other providers' IDs can break a format's character or length rules, and history may repeat or lack IDs.
 */
internal class ReplayToolCallIds(private val maxLength: Int = 64) {
    private val used = HashSet<String>()

    private fun isPortable(id: String) = id.length <= maxLength &&
        id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

    fun assign(calls: List<ToolCallRecord>): List<String> = calls.map { tc ->
        val raw = tc.id.trim().ifEmpty { "call_${tc.name}" }
        val base = if (isPortable(raw)) raw else "call_${raw.hashCode().toUInt().toString(16)}"
        var candidate = base
        var suffix = 1
        while (!used.add(candidate)) {
            suffix++
            candidate = "${base.take(maxLength - 6)}_$suffix"
        }
        candidate
    }
}
