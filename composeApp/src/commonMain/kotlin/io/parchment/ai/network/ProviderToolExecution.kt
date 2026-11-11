package io.parchment.ai.network

import io.parchment.ai.ui.Strings

import io.ktor.client.request.get
import io.parchment.ai.harness.AgentPermissionGate
import io.parchment.ai.harness.AgentToolRunContext
import io.parchment.ai.harness.ComputerUse
import io.parchment.ai.harness.McpRegistry
import io.parchment.ai.harness.ToolAccessScope
import io.parchment.ai.harness.ToolApprovalBroker
import io.parchment.ai.harness.ToolAuthorization
import io.parchment.ai.harness.ToolExecutionPermit
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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

/** The permit is claimed here because this call does not go through the workspace executor. */
private suspend fun runMcpTool(permit: ToolExecutionPermit, callId: String, name: String, argumentsJson: String): ToolCallRecord {
    permit.claim(permit.scope, name, argumentsJson)?.let {
        return ToolCallRecord(id = callId, name = name, argumentsJson = argumentsJson, resultText = "Error: $it", isError = true)
    }
    val result = McpRegistry.call(name, argumentsJson)
    currentCoroutineContext().ensureActive()
    return ToolCallRecord(id = callId, name = name, argumentsJson = argumentsJson,
        resultText = if (result.isError) "Error: ${result.text}" else result.text, isError = result.isError)
}

private suspend fun runComputerTool(permit: ToolExecutionPermit, callId: String, name: String, argumentsJson: String): ToolCallRecord {
    fun record(text: String, isError: Boolean, image: String = "") = ToolCallRecord(id = callId, name = name, argumentsJson = argumentsJson, resultText = text, isError = isError, screenshotJpegBase64 = image)
    permit.claim(permit.scope, name, argumentsJson)?.let { return record("Error: $it", true) }
    val job = currentCoroutineContext()[kotlinx.coroutines.Job]
    val outcome = withContext(Dispatchers.IO) { ComputerUse.perform(argumentsJson) { job?.isActive == false || permit.isRevoked() } }
    currentCoroutineContext().ensureActive()
    return record(outcome.text, outcome.isError, outcome.screenshot?.jpegBase64.orEmpty())
}

private suspend fun runHelper(callId: String, argumentsJson: String, intent: String, onStatus: suspend (String) -> Unit): ToolCallRecord {
    fun record(text: String, isError: Boolean) = ToolCallRecord(id = callId, name = "agent_delegate", argumentsJson = argumentsJson, resultText = text, isError = isError)
    val task = (runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()?.get("task") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (task.isEmpty()) return record("Error: A task is required.", true)
    val runner = currentCoroutineContext()[AgentToolRunContext]?.broker?.subagentRunner
        ?: return record("Error: Helpers are not available in this run.", true)
    val result = runner(task) { progress -> onStatus("$intent\n$progress") }
    currentCoroutineContext().ensureActive()
    return result.fold({ record(it, false) }, { record("Error: ${it.message ?: "The helper failed."}", true) })
}

private const val MAX_QUESTION_CHARS = 2_000
private const val MAX_QUESTION_OPTIONS = 8
private const val MAX_OPTION_CHARS = 200

/** Waits for the person running this chat; the answer goes back to the model as the tool result. */
private suspend fun answerFromUser(callId: String, argumentsJson: String): ToolCallRecord {
    fun failed(reason: String) = ToolCallRecord(id = callId, name = "ask_user", argumentsJson = argumentsJson, resultText = "Error: $reason", isError = true)
    val arguments = runCatching { Json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull()
        ?: return failed("Tool arguments must be a valid JSON object.")
    val question = (arguments["question"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(MAX_QUESTION_CHARS).orEmpty()
    if (question.isEmpty()) return failed("A question is required.")
    val options = (arguments["options"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.take(MAX_OPTION_CHARS) }
        .filter { it.isNotEmpty() }.distinct().take(MAX_QUESTION_OPTIONS)
    val broker = currentCoroutineContext()[AgentToolRunContext]?.broker
        ?: return failed("No one is available to answer in this run. Decide yourself and state your assumption.")
    val answer = broker.askUser(callId, question, options)
    currentCoroutineContext().ensureActive()
    return if (answer == null) failed("The question was withdrawn because this run's permissions changed.")
    else ToolCallRecord(id = callId, name = "ask_user", argumentsJson = argumentsJson, resultText = "The user answered: $answer")
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
    if (AgentPermissionGate.effect(accessScope, toolName, argumentsJson).requiresApproval) onStatus(Strings.awaitingApproval(intent))
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
    if (toolName == "ask_user" || toolName == "agent_delegate" || toolName == ComputerUse.TOOL_NAME || McpRegistry.isMcpTool(toolName)) {
        val record = when {
            toolName == ComputerUse.TOOL_NAME -> runComputerTool(permit, callId, toolName, argumentsJson)
            toolName == "ask_user" -> answerFromUser(callId, argumentsJson)
            toolName == "agent_delegate" -> runHelper(callId, argumentsJson, intent, onStatus)
            else -> runMcpTool(permit, callId, toolName, argumentsJson)
        }
        return record.copy(isRunning = false, durationMs = (clock() - startMs).coerceAtLeast(1L),
            thoughtSignature = thoughtSignature, precedingThinking = precedingThinking)
    }
    // The blocking tool runs in `async(Dispatchers.IO)`, which never calls `emit()`; `liveChannel` is read on the
    // caller's coroutine. Emitting from another context would break Flow's context rule and throw.
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

        // Read-only tools run in parallel: show a running card for each at once, run them on Dispatchers.IO,
        // and report the results in a fixed order.
        val summaryNames = calls.map { it.second }.distinct().joinToString(", ")
        onStatus(Strings.executingReadSearchToolsInParallel(calls.size, summaryNames))
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
    fallbackTurnThinking: String,
    beforeTool: List<String> = emptyList()
): List<ToolStepBatch> {
    if (toolCalls.isEmpty()) return emptyList()
    val batches = mutableListOf<ToolStepBatch>()
    val hasAnyPrecedingThinking = toolCalls.any { it.precedingThinking.isNotBlank() }
    var currentCalls = mutableListOf<ToolCallRecord>()
    var currentSig = ""
    var currentThink = ""
    var currentText = ""

    for ((idx, tc) in toolCalls.withIndex()) {
        val tcText = beforeTool.getOrElse(idx) { "" }
        val tcSig = tc.thoughtSignature.ifBlank { if (idx == 0) fallbackTurnSignature else "" }
        val tcThink = tc.precedingThinking.ifBlank { if (idx == 0 && !hasAnyPrecedingThinking) fallbackTurnThinking else "" }
        val startsNewBatch = idx == 0 || tc.startsToolStep || tc.thoughtSignature.isNotBlank() || tc.precedingThinking.isNotBlank() || tcText.isNotEmpty()
        if (startsNewBatch) {
            if (currentCalls.isNotEmpty()) {
                batches.add(ToolStepBatch(currentSig, currentThink, currentCalls.toList(), currentText))
            }
            currentCalls = mutableListOf(tc)
            currentSig = tcSig
            currentThink = tcThink
            currentText = tcText
        } else {
            currentCalls.add(tc)
        }
    }
    if (currentCalls.isNotEmpty()) {
        batches.add(ToolStepBatch(currentSig, currentThink, currentCalls.toList(), currentText))
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
