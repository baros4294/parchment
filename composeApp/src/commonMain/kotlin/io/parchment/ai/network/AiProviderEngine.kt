package io.parchment.ai.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.AuthHeaderMode
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.GoalTrackerState
import io.parchment.ai.model.MediaKind
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.TokenMetrics
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.model.WebSourceCitation
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.protocol.O200kTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import androidx.compose.runtime.Immutable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Immutable
data class ModelModalityProfile(
    val outputModalities: List<String>,
    val inputModalities: List<String> = listOf("text"),
    val isDedicatedImage: Boolean = false,
    val isInterleavedImage: Boolean = false,
    val isDedicatedVideo: Boolean = false,
    val isDedicatedAudioTts: Boolean = false,
    val isLiveRealtimeAudio: Boolean = false
) {
    val isAnyImageModel: Boolean get() = isDedicatedImage || isInterleavedImage
    val isAnyVideoModel: Boolean get() = isDedicatedVideo
    val isAnyAudioModel: Boolean get() = isDedicatedAudioTts || isLiveRealtimeAudio
}

sealed class StreamUpdate {
    data class Status(val message: String, val interactionId: String = "") : StreamUpdate()
    data class ThinkingDelta(val delta: String, val signatureDelta: String = "") : StreamUpdate()
    data class ContentDelta(val delta: String) : StreamUpdate()
    data class ToolCallEvent(val toolCall: ToolCallRecord) : StreamUpdate()
    data class GoalProgress(val goalTracker: GoalTrackerState) : StreamUpdate()
    data class MediaGenerated(val item: GeneratedMediaItem) : StreamUpdate()
    data class WebSourcesUpdated(val sources: List<WebSourceCitation>) : StreamUpdate()
    data class RawWireSnapshot(val requestJson: String, val latestResponseChunk: String) : StreamUpdate()
    data class Completed(
        val fullContent: String,
        val fullThinking: String,
        val trailingThinking: String = "",
        val thinkingSignature: String,
        val toolCalls: List<ToolCallRecord>,
        val goalTracker: GoalTrackerState?,
        val mediaItems: List<GeneratedMediaItem>,
        val webSources: List<WebSourceCitation> = emptyList(),
        val providerInteractionId: String,
        val metrics: TokenMetrics,
        val rawRequest: String,
        val rawResponse: String
    ) : StreamUpdate()
    data class Error(val message: String, val rawDetails: String = "") : StreamUpdate()
}

@OptIn(ExperimentalEncodingApi::class)
object AiProviderEngine {

    /**
     * Long-horizon autonomous execution engine.
     * Uses a Repeated-Failure Circuit Breaker + Stop-Hook Work-Check Gate instead of a brittle 64-step limit,
     * allowing tasks to run productively for hours, days, or weeks while halting repeated identical errors.
     */
    private const val MAX_SAFETY_HORIZON_STEPS = 10_000
    internal const val MAX_PRE_TOKEN_RETRIES = 10
    internal const val RETRY_WAIT_SECONDS = 60
    internal const val LEGACY_FALLBACK_PRIMARY_MAX_RETRIES = 2
    internal const val LEGACY_FALLBACK_PRIMARY_WAIT_SECONDS = 3
    private const val MAX_GOAL_NETWORK_RETRIES = 120
    private const val MAX_MID_STREAM_CONTINUATION_PASSES = 64
    private val discoveredModelModalities = mutableMapOf<String, List<String>>()
    private val discoveredModelVendors = mutableMapOf<String, String>()

    internal class StepRetriesExhaustedException(message: String, cause: Throwable) : RuntimeException(message, cause)

    /**
     * Unwraps raw JSON API error payloads (such as `{"error":{"code":"invalid_request","message":"..."}}`)
     * into clean, human-readable summaries without raw JSON braces or mid-word truncation.
     */
    internal fun extractCleanApiErrorSummary(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val braceIdx = trimmed.indexOf('{')
        val bracketIdx = trimmed.indexOf('[')
        val jsonStart = when {
            braceIdx >= 0 && bracketIdx >= 0 -> minOf(braceIdx, bracketIdx)
            braceIdx >= 0 -> braceIdx
            bracketIdx >= 0 -> bracketIdx
            else -> -1
        }
        if (jsonStart >= 0) {
            val prefix = trimmed.substring(0, jsonStart).trim().removeSuffix(":").trim()
            val jsonCandidate = trimmed.substring(jsonStart).trim()
            val parsedSummary = runCatching {
                val rootEl = json.parseToJsonElement(jsonCandidate)
                val rootObj = when (rootEl) {
                    is JsonObject -> rootEl
                    is JsonArray -> rootEl.firstOrNull() as? JsonObject
                    else -> null
                }
                if (rootObj != null) {
                    val errObj = (rootObj["error"] as? JsonObject) ?: rootObj
                    val code = errObj["code"]?.jsonPrimitive?.contentOrNull
                        ?: errObj["status"]?.jsonPrimitive?.contentOrNull
                        ?: errObj["type"]?.jsonPrimitive?.contentOrNull
                    val msg = errObj["message"]?.jsonPrimitive?.contentOrNull
                        ?: errObj["detail"]?.jsonPrimitive?.contentOrNull
                        ?: (rootObj["error"] as? JsonPrimitive)?.contentOrNull
                    if (!msg.isNullOrBlank()) {
                        val cleanMsg = msg.replace(Regex("\\s+"), " ").trim()
                        if (prefix.isNotEmpty()) {
                            val codeTag = if (!code.isNullOrBlank() && !prefix.contains(code, ignoreCase = true)) {
                                " ($code)"
                            } else ""
                            "$prefix$codeTag: $cleanMsg"
                        } else {
                            val codeTag = if (!code.isNullOrBlank()) "[$code] " else ""
                            "$codeTag$cleanMsg"
                        }
                    } else null
                } else null
            }.getOrNull()
            if (!parsedSummary.isNullOrBlank()) {
                return parsedSummary
            }
        }
        return trimmed.replace(Regex("\\s+"), " ").trim()
    }

    private suspend fun waitWithRetryCountdown(
        attempt: Int,
        maxAttempts: Int,
        waitSeconds: Int = RETRY_WAIT_SECONDS,
        reasonSummary: String,
        onStatus: suspend (String) -> Unit
    ) {
        val cleanReason = extractCleanApiErrorSummary(reasonSummary)
        for (remaining in waitSeconds downTo 1) {
            val statusMsg = if (cleanReason.isNotBlank()) {
                "Auto-retrying ($attempt/$maxAttempts in ${remaining}s)\n$cleanReason"
            } else {
                "Auto-retrying ($attempt/$maxAttempts in ${remaining}s)…"
            }
            onStatus(statusMsg)
            delay(1000L)
        }
        onStatus("Retrying now (attempt $attempt/$maxAttempts)…")
    }

    private suspend fun executeWorkspaceToolWithLiveProgress(
        workspaceRoot: String,
        mode: io.parchment.ai.model.WorkspaceMode,
        callId: String,
        toolName: String,
        argumentsJson: String,
        thoughtSignature: String = "",
        precedingThinking: String = "",
        onStatus: suspend (String) -> Unit,
        onToolCall: suspend (ToolCallRecord) -> Unit
    ): ToolCallRecord {
        val intent = WorkspaceHarness.describeToolCallIntent(toolName, argumentsJson)
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
        val startMs = PlatformBridge.currentTimeMillis()
        // IMPORTANT: Run the blocking tool execution in `async(Dispatchers.IO)` (which NEVER calls `flow.emit()`)
        // and consume `liveChannel` directly on the caller's coroutine so Kotlin's `Flow` context invariant is NEVER violated!
        return kotlinx.coroutines.coroutineScope {
            val workerDeferred = async(Dispatchers.IO) {
                try {
                    WorkspaceHarness.executeToolCall(
                        workspaceRoot = workspaceRoot,
                        mode = mode,
                        callId = callId,
                        toolName = toolName,
                        argumentsJson = argumentsJson,
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
                    val elapsedSec = ((PlatformBridge.currentTimeMillis() - startMs) / 100L) / 10.0
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
                    thoughtSignature = thoughtSignature.ifBlank { rawRecord.thoughtSignature },
                    precedingThinking = precedingThinking.ifBlank { rawRecord.precedingThinking }
                )
            } finally {
                liveChannel.close()
                workerDeferred.cancel()
            }
        }
    }

    private suspend fun executeWorkspaceToolBatchWithLiveProgress(
        workspaceRoot: String,
        mode: io.parchment.ai.model.WorkspaceMode,
        calls: List<Triple<String, String, String>>, // (callId, toolName, argumentsJson)
        firstThoughtSignature: String = "",
        firstPrecedingThinking: String = "",
        circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
        onStatus: suspend (String) -> Unit,
        onToolCall: suspend (ToolCallRecord) -> Unit,
        onGoalProgress: suspend (GoalTrackerState) -> Unit
    ): List<ToolCallRecord> {
        if (calls.isEmpty()) return emptyList()
        val canRunBatchInParallel = calls.size > 1 && calls.all { (_, fnName, _) ->
            WorkspaceHarness.isParallelSafeReadOnlyTool(fnName)
        }
        if (!canRunBatchInParallel) {
            val results = mutableListOf<ToolCallRecord>()
            for ((idx, triple) in calls.withIndex()) {
                val (callId, fnName, argsJson) = triple
                val signedRec = executeWorkspaceToolWithLiveProgress(
                    workspaceRoot = workspaceRoot,
                    mode = mode,
                    callId = callId,
                    toolName = fnName,
                    argumentsJson = argsJson,
                    thoughtSignature = if (idx == 0) firstThoughtSignature else "",
                    precedingThinking = if (idx == 0) firstPrecedingThinking else "",
                    onStatus = onStatus,
                    onToolCall = onToolCall
                )
                val rec = circuitBreaker.onToolExecuted(signedRec)
                circuitBreaker.goalState?.let { onGoalProgress(it) }
                onToolCall(rec)
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
            onToolCall(
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
                async(Dispatchers.IO) {
                    val raw = WorkspaceHarness.executeToolCall(
                        workspaceRoot = workspaceRoot,
                        mode = mode,
                        callId = callId,
                        toolName = fnName,
                        argumentsJson = argsJson
                    )
                    raw.copy(
                        isRunning = false,
                        thoughtSignature = if (idx == 0) firstThoughtSignature.ifBlank { raw.thoughtSignature } else raw.thoughtSignature,
                        precedingThinking = if (idx == 0) firstPrecedingThinking.ifBlank { raw.precedingThinking } else raw.precedingThinking
                    )
                }
            }.map { it.await() }
        }

        val finalResults = mutableListOf<ToolCallRecord>()
        for (rawRec in rawCompleted) {
            val rec = circuitBreaker.onToolExecuted(rawRec)
            circuitBreaker.goalState?.let { onGoalProgress(it) }
            onToolCall(rec)
            finalResults.add(rec)
        }
        return finalResults
    }
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val prettyJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    internal val httpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(this@AiProviderEngine.json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 24 * 60 * 60 * 1000L // 24-hour per-request ceiling for ultra-long reasoning & decodes
                connectTimeoutMillis = 45_000L
                socketTimeoutMillis = 24 * 60 * 60 * 1000L
            }
        }
    }

    /**
     * Streams or polls a generation turn across Gemini Interactions API, OpenAI Responses API,
     * or Anthropic Messages API, including multi-step local workspace tool loops and
     * mid-sentence "Continue" / Assistant Prefill support.
     */
    fun streamGeneration(
        profile: ProviderProfile,
        session: ChatSession,
        historyNodes: List<MessageNode>,
        continueFromAssistantPrefix: String = "",
        initialThinking: String = "",
        initialSignature: String = "",
        initialToolCalls: List<ToolCallRecord> = emptyList(),
        initialMediaItems: List<GeneratedMediaItem> = emptyList(),
        initialWebSources: List<WebSourceCitation> = emptyList(),
        initialGoalTracker: GoalTrackerState? = null,
        initialElapsedMs: Long = 0L
    ): Flow<StreamUpdate> = flow {
        val model = session.modelOverride.ifBlank { profile.defaultModel }.trim()
        if (model.isBlank()) {
            emit(
                StreamUpdate.Error(
                    message = "No Model ID is selected. Fetch models automatically from the endpoint or enter a Model ID manually in Providers & Proxy."
                )
            )
            return@flow
        }
        val startTime = PlatformBridge.currentTimeMillis()
        var firstTokenTime = 0L

        val cleanInitialPrefix = stripInlineErrorNotices(continueFromAssistantPrefix)
        val contentBuilder = StringBuilder(cleanInitialPrefix)
        val thinkingBuilder = StringBuilder(initialThinking)
        val currentStepThinkingBuilder = StringBuilder()
        val signatureBuilder = StringBuilder(initialSignature)
        val executedTools = initialToolCalls.toMutableList()
        val generatedMedia = initialMediaItems.toMutableList()
        val rawWebCitations = mutableListOf<RawWebCitation>()
        for (existingSrc in initialWebSources) {
            rawWebCitations.add(
                RawWebCitation(
                    url = existingSrc.url,
                    title = existingSrc.title,
                    snippet = existingSrc.snippet,
                    groundedText = existingSrc.groundedLines.firstOrNull().orEmpty()
                )
            )
        }
        var lastInteractionId = ""
        var inputTokens = 0
        var cachedTokens = 0
        var stepReasoningTokens = 0
        var stepOutputTokens = 0
        var completedStepsReasoningTokens = 0
        var completedStepsOutputTokens = 0
        var activeDecodeAccumulatedMs = 0L
        var currentBurstStartMs = 0L
        var currentBurstLastMs = 0L
        var totalToolExecutionMs = 0L
        var toolBatchActiveStartMs = 0L
        var lastRawReq = ""
        var lastRawResp = ""
        fun recordTokenActivity() {
            val now = PlatformBridge.currentTimeMillis()
            if (firstTokenTime == 0L) firstTokenTime = now
            if (currentBurstStartMs == 0L) {
                currentBurstStartMs = now
                currentBurstLastMs = now
            } else {
                val gap = now - currentBurstLastMs
                if (gap > 2500L) {
                    // Server paused >2.5s (e.g. server-side tool / web search / multi-round TTFT); close prior burst
                    activeDecodeAccumulatedMs += (currentBurstLastMs - currentBurstStartMs).coerceAtLeast(40L)
                    currentBurstStartMs = now
                    currentBurstLastMs = now
                } else {
                    currentBurstLastMs = now
                }
            }
        }

        fun closeCurrentDecodeBurst() {
            if (currentBurstStartMs > 0L && currentBurstLastMs >= currentBurstStartMs) {
                activeDecodeAccumulatedMs += (currentBurstLastMs - currentBurstStartMs).coerceAtLeast(40L)
            }
            currentBurstStartMs = 0L
            currentBurstLastMs = 0L
        }

        fun commitCompletedStepUsage() {
            if (stepReasoningTokens > 0) {
                completedStepsReasoningTokens += stepReasoningTokens
                stepReasoningTokens = 0
            }
            if (stepOutputTokens > 0) {
                completedStepsOutputTokens += stepOutputTokens
                stepOutputTokens = 0
            }
        }

        fun recordRawWireBounded(req: String, resp: String) {
            if (req.isNotEmpty()) {
                lastRawReq = if (req.length > 4096) req.substring(0, 4096) else req
            }
            if (resp.isNotEmpty()) {
                lastRawResp = if (resp.length > 4096) resp.substring(0, 4096) else resp
            }
        }

        fun recordStepUsage(inTok: Int, cacheTok: Int, thinkTok: Int, outTok: Int) {
            if (inTok > 0) inputTokens = maxOf(inputTokens, inTok)
            if (cacheTok > 0) cachedTokens = maxOf(cachedTokens, cacheTok)
            if (thinkTok > 0) stepReasoningTokens = maxOf(stepReasoningTokens, thinkTok)
            if (outTok > 0) stepOutputTokens = maxOf(stepOutputTokens, outTok)
        }

        fun appendSignatureIfUnique(sig: String): String {
            return appendSignatureChunkSafe(signatureBuilder, sig)
        }

        fun attachStepThinkingToToolCall(tc: ToolCallRecord): ToolCallRecord {
            val stepThink = currentStepThinkingBuilder.toString().trim()
            val stepSig = signatureBuilder.toString().trim()
            currentStepThinkingBuilder.clear()
            signatureBuilder.clear()
            return tc.copy(
                precedingThinking = tc.precedingThinking.ifBlank { stepThink },
                thoughtSignature = tc.thoughtSignature.ifBlank { stepSig }
            )
        }
        suspend fun recordRawCitationAndEmit(citation: RawWebCitation) {
            if (citation.url.isBlank() || rawWebCitations.size >= 128) return
            val isDuplicate = rawWebCitations.any {
                it.url == citation.url && it.startIndex == citation.startIndex && it.groundedText == citation.groundedText
            }
            if (isDuplicate) return
            rawWebCitations.add(citation)
            val bound = bindSourcesToMessageLines(contentBuilder.toString(), rawWebCitations)
            if (bound.isNotEmpty()) {
                emit(StreamUpdate.WebSourcesUpdated(bound))
            }
        }
        suspend fun upsertToolCallAndEmit(tc: ToolCallRecord) {
            closeCurrentDecodeBurst()
            val now = PlatformBridge.currentTimeMillis()
            if (tc.isRunning && toolBatchActiveStartMs == 0L) {
                toolBatchActiveStartMs = now
                commitCompletedStepUsage()
            }
            val existingIdx = executedTools.indexOfFirst { it.id == tc.id && tc.id.isNotBlank() }
            val enriched = if (existingIdx >= 0) {
                val prev = executedTools[existingIdx]
                tc.copy(
                    precedingThinking = tc.precedingThinking.ifBlank { prev.precedingThinking },
                    thoughtSignature = tc.thoughtSignature.ifBlank { prev.thoughtSignature }
                )
            } else {
                attachStepThinkingToToolCall(tc)
            }
            if (existingIdx >= 0) {
                executedTools[existingIdx] = enriched
            } else {
                executedTools.add(enriched)
            }
            emit(StreamUpdate.ToolCallEvent(enriched))
            if (!enriched.isRunning) {
                if (toolBatchActiveStartMs > 0L && executedTools.none { it.isRunning }) {
                    totalToolExecutionMs += (PlatformBridge.currentTimeMillis() - toolBatchActiveStartMs).coerceAtLeast(0L)
                    toolBatchActiveStartMs = 0L
                } else if (toolBatchActiveStartMs == 0L && enriched.durationMs > 0L && existingIdx < 0) {
                    totalToolExecutionMs += enriched.durationMs
                }
                extractCitationsFromToolCall(enriched).forEach { recordRawCitationAndEmit(it) }
            }
        }
        val initialGoalObjective = extractGoalObjectiveFromHistory(historyNodes)
        val circuitBreaker = WorkspaceHarness.LoopCircuitBreaker(
            initialGoal = initialGoalTracker ?: if (initialGoalObjective.isNotBlank()) {
                GoalTrackerState(goalObjective = initialGoalObjective)
            } else null
        )
        circuitBreaker.goalState?.let { emit(StreamUpdate.GoalProgress(it)) }

        PlatformBridge.notifyLongDecodeStart(model)

        try {
            var activeBaseHistoryNodes = historyNodes
            var currentContinuePrefix = cleanInitialPrefix
            var retryAttempt = 0
            var continuationPass = 0
            var emptyAnswerNudgePass = 0
            var emptyStreamRetryPass = 0

            while (true) {
                val charsBeforePass = contentBuilder.length
                val thinkBeforePass = thinkingBuilder.length
                val sigBeforePass = signatureBuilder.length
                val toolsBeforePass = executedTools.size
                val mediaBeforePass = generatedMedia.size

                // On Nudge Pass >= 2 (if the model completed reasoning once and then spent Nudge Pass 1
                // thinking again without outputting any final prose), automatically relax thinkingEffort
                // and surface the completed thought summary so the model immediately writes the final answer!
                val effectiveProfileForPass = when {
                    emptyAnswerNudgePass == 2 -> profile.copy(thinkingEffort = "low")
                    emptyAnswerNudgePass >= 3 -> profile.copy(thinkingEffort = "minimal")
                    else -> profile
                }

                // If continuing a turn that already accumulated thoughts or tool calls (either before clicking
                // '→ Continue', during an automatic zero-answer nudge pass, or after a mid-stream network drop),
                // synthesize the partial assistant turn in history so no protocol loses prior thoughts/signatures
                // or re-executes completed tools.
                val (effectiveHistoryNodes, effectiveContinuePrefix) = buildEffectiveHistoryAndPrefix(
                    baseHistoryNodes = activeBaseHistoryNodes,
                    continuePrefix = currentContinuePrefix,
                    thinkingText = thinkingBuilder.toString(),
                    trailingThinkingText = currentStepThinkingBuilder.toString().trim(),
                    signatureText = signatureBuilder.toString(),
                    executedTools = executedTools.toList(),
                    generatedMedia = generatedMedia.toList(),
                    protocol = effectiveProfileForPass.protocol,
                    model = model,
                    passTag = "pass-$continuationPass-nudge-$emptyAnswerNudgePass",
                    forceVisibleThoughtSummary = emptyAnswerNudgePass >= 2
                )

                try {
                    when (effectiveProfileForPass.protocol) {
                        ProviderProtocol.GEMINI_INTERACTIONS -> {
                            suspend fun runLegacyGeminiGenerateContent(
                                targetHistoryNodes: List<MessageNode>,
                                targetContinuePrefix: String
                            ) {
                                executeGeminiGenerateContentLoop(
                                    profile = effectiveProfileForPass,
                                    model = model,
                                    session = session,
                                    historyNodes = targetHistoryNodes,
                                    continuePrefix = targetContinuePrefix,
                                    circuitBreaker = circuitBreaker,
                                    onStatus = { statusMsg ->
                                        emit(StreamUpdate.Status(statusMsg, lastInteractionId))
                                    },
                                    onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                    onFirstToken = { recordTokenActivity() },
                                    onThinking = { delta, sig ->
                                        if (delta.isNotEmpty()) recordTokenActivity()
                                        thinkingBuilder.append(delta)
                                        currentStepThinkingBuilder.append(delta)
                                        val uniqueSig = appendSignatureIfUnique(sig)
                                        if (delta.isNotEmpty() || uniqueSig.isNotEmpty()) {
                                            emit(StreamUpdate.ThinkingDelta(delta, uniqueSig))
                                        }
                                    },
                                    onContent = { delta ->
                                        if (delta.isNotEmpty()) recordTokenActivity()
                                        contentBuilder.append(delta)
                                        val approxOut = (contentBuilder.length / 4).coerceAtLeast(completedStepsOutputTokens + stepOutputTokens)
                                        val elapsedSec = ((PlatformBridge.currentTimeMillis() - startTime) / 1000L).toInt()
                                        PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onMedia = { item ->
                                        recordTokenActivity()
                                        generatedMedia.add(item)
                                        emit(StreamUpdate.MediaGenerated(item))
                                    },
                                    onToolCall = { tc ->
                                        upsertToolCallAndEmit(tc)
                                    },
                                    onRawWire = { req, resp ->
                                        recordRawWireBounded(req, resp)
                                    },
                                    onUsage = { inTok, cacheTok, thinkTok, outTok ->
                                        recordStepUsage(inTok, cacheTok, thinkTok, outTok)
                                    },
                                    onCitation = { cit -> recordRawCitationAndEmit(cit) }
                                )
                            }
                            // Always attempt the newer /v1beta/interactions protocol first; if it errors out
                            // (or exhausts fast probe retries when useLegacyFallbackEndpoint is ON), seamlessly
                            // hand off any accumulated thoughts/tools/prose to :streamGenerateContent!
                            if (emptyAnswerNudgePass >= 3 && effectiveProfileForPass.useLegacyFallbackEndpoint) {
                                runLegacyGeminiGenerateContent(effectiveHistoryNodes, effectiveContinuePrefix)
                            } else {
                            try {
                                executeGeminiInteractionsLoop(
                                    profile = effectiveProfileForPass,
                                    model = model,
                                    session = session,
                                    historyNodes = effectiveHistoryNodes,
                                    continuePrefix = effectiveContinuePrefix,
                                    circuitBreaker = circuitBreaker,
                                    onStatus = { statusMsg, id ->
                                        if (id.isNotBlank()) lastInteractionId = id
                                        emit(StreamUpdate.Status(statusMsg, lastInteractionId))
                                    },
                                    onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                    onFirstToken = { recordTokenActivity() },
                                    onThinking = { delta, sig ->
                                        if (delta.isNotEmpty()) recordTokenActivity()
                                        thinkingBuilder.append(delta)
                                        currentStepThinkingBuilder.append(delta)
                                        val uniqueSig = appendSignatureIfUnique(sig)
                                        if (delta.isNotEmpty() || uniqueSig.isNotEmpty()) {
                                            emit(StreamUpdate.ThinkingDelta(delta, uniqueSig))
                                        }
                                    },
                                    onContent = { delta ->
                                        if (delta.isNotEmpty()) recordTokenActivity()
                                        contentBuilder.append(delta)
                                        val approxOut = (contentBuilder.length / 4).coerceAtLeast(completedStepsOutputTokens + stepOutputTokens)
                                        val elapsedSec = ((PlatformBridge.currentTimeMillis() - startTime) / 1000L).toInt()
                                        PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onMedia = { item ->
                                        recordTokenActivity()
                                        generatedMedia.add(item)
                                        emit(StreamUpdate.MediaGenerated(item))
                                    },
                                    onToolCall = { tc ->
                                        upsertToolCallAndEmit(tc)
                                    },
                                    onRawWire = { req, resp ->
                                        recordRawWireBounded(req, resp)
                                    },
                                    onUsage = { id, inTok, cacheTok, thinkTok, outTok ->
                                        if (id.isNotBlank()) lastInteractionId = id
                                        recordStepUsage(inTok, cacheTok, thinkTok, outTok)
                                    },
                                    onCitation = { cit -> recordRawCitationAndEmit(cit) }
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                if (shouldFallbackToLegacyProtocol(e, profile.useLegacyFallbackEndpoint)) {
                                    val actualCause = if (e is StepRetriesExhaustedException && e.cause != null) e.cause!! else e
                                    val reasonClean = extractCleanApiErrorSummary(actualCause.message ?: "endpoint error")
                                    emit(
                                        StreamUpdate.Status(
                                            "Primary /v1beta/interactions failed — falling back to :streamGenerateContent…\n$reasonClean",
                                            lastInteractionId
                                        )
                                    )
                                    val (fbNodes, fbPrefix) = buildEffectiveHistoryAndPrefix(
                                        baseHistoryNodes = activeBaseHistoryNodes,
                                        continuePrefix = contentBuilder.toString(),
                                        thinkingText = thinkingBuilder.toString(),
                                        trailingThinkingText = currentStepThinkingBuilder.toString().trim(),
                                        signatureText = signatureBuilder.toString(),
                                        executedTools = executedTools.toList(),
                                        generatedMedia = generatedMedia.toList(),
                                        protocol = profile.protocol,
                                        model = model,
                                        passTag = "fallback-gemini-$continuationPass"
                                    )
                                    runLegacyGeminiGenerateContent(fbNodes, fbPrefix)
                                } else {
                                    throw e
                                }
                            }
                            }
                        }

                        ProviderProtocol.OPENAI_RESPONSES -> {
                            val modalityProfile = resolveModelModalityProfileAsync(effectiveProfileForPass, model)
                            val isExplicitVideoPath = profile.endpointPathOverride.contains("contents/generations/tasks", ignoreCase = true) ||
                                profile.endpointPathOverride.contains("videos/generations", ignoreCase = true) ||
                                profile.endpointPathOverride.contains("video/generations", ignoreCase = true)
                            val isDirectImagesEndpoint = profile.endpointPathOverride.contains("images/generations", ignoreCase = true) ||
                                profile.endpointPathOverride.contains("images/edits", ignoreCase = true) ||
                                modalityProfile.isDedicatedImage
                            val isDirectAudioEndpoint = profile.endpointPathOverride.contains("audio/speech", ignoreCase = true) ||
                                modalityProfile.isDedicatedAudioTts
                            suspend fun runLegacyOpenAiChatCompletions(
                                targetHistoryNodes: List<MessageNode>,
                                targetContinuePrefix: String
                            ) {
                                executeOpenAiChatCompletionsLoop(
                                    profile = effectiveProfileForPass,
                                    model = model,
                                    session = session,
                                    historyNodes = targetHistoryNodes,
                                    continuePrefix = targetContinuePrefix,
                                    circuitBreaker = circuitBreaker,
                                    onStatus = { st -> emit(StreamUpdate.Status(st)) },
                                    onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                    onFirstToken = { recordTokenActivity() },
                                    onThinking = { delta ->
                                        if (delta.isNotEmpty()) recordTokenActivity()
                                        thinkingBuilder.append(delta)
                                        currentStepThinkingBuilder.append(delta)
                                        emit(StreamUpdate.ThinkingDelta(delta))
                                    },
                                    onContent = { delta ->
                                        if (delta.isNotEmpty()) recordTokenActivity()
                                        contentBuilder.append(delta)
                                        val approxOut = (contentBuilder.length / 4).coerceAtLeast(completedStepsOutputTokens + stepOutputTokens)
                                        val elapsedSec = ((PlatformBridge.currentTimeMillis() - startTime) / 1000L).toInt()
                                        PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onMedia = { item ->
                                        recordTokenActivity()
                                        generatedMedia.add(item)
                                        emit(StreamUpdate.MediaGenerated(item))
                                    },
                                    onToolCall = { tc ->
                                        upsertToolCallAndEmit(tc)
                                    },
                                    onRawWire = { req, resp ->
                                        recordRawWireBounded(req, resp)
                                    },
                                    onUsage = { inTok, cacheTok, thinkTok, outTok ->
                                        recordStepUsage(inTok, cacheTok, thinkTok, outTok)
                                    },
                                    onCitation = { cit -> recordRawCitationAndEmit(cit) }
                                )
                            }

                            suspend fun runUniversalMediaGeneration(forceVideo: Boolean) {
                                executeByteDanceArkGeneration(
                                    profile = profile,
                                    model = model,
                                    session = session,
                                    historyNodes = historyNodes,
                                    circuitBreaker = circuitBreaker,
                                    forceVideoMode = forceVideo,
                                    onStatus = { st, id ->
                                        if (id.isNotBlank()) lastInteractionId = id
                                        emit(StreamUpdate.Status(st, lastInteractionId))
                                    },
                                    onFirstToken = { recordTokenActivity() },
                                    onThinking = { delta ->
                                        if (delta.isNotEmpty()) recordTokenActivity()
                                        thinkingBuilder.append(delta)
                                        emit(StreamUpdate.ThinkingDelta(delta))
                                    },
                                    onMedia = { item ->
                                        recordTokenActivity()
                                        generatedMedia.add(item)
                                        emit(StreamUpdate.MediaGenerated(item))
                                    },
                                    onContent = { delta ->
                                        if (delta.isNotEmpty()) recordTokenActivity()
                                        contentBuilder.append(delta)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onRawWire = { req, resp ->
                                        recordRawWireBounded(req, resp)
                                    }
                                )
                            }

                            suspend fun runUniversalAudioSpeechGeneration() {
                                executeOpenAiAudioSpeechEndpoint(
                                    profile = profile,
                                    model = model,
                                    session = session,
                                    historyNodes = historyNodes,
                                    circuitBreaker = circuitBreaker,
                                    onStatus = { st -> emit(StreamUpdate.Status(st, lastInteractionId)) },
                                    onFirstToken = { recordTokenActivity() },
                                    onMedia = { item ->
                                        recordTokenActivity()
                                        generatedMedia.add(item)
                                        emit(StreamUpdate.MediaGenerated(item))
                                    },
                                    onContent = { delta ->
                                        if (delta.isNotEmpty()) recordTokenActivity()
                                        contentBuilder.append(delta)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onRawWire = { req, resp ->
                                        recordRawWireBounded(req, resp)
                                    }
                                )
                            }

                            if (modalityProfile.isDedicatedVideo || isExplicitVideoPath) {
                                try {
                                    runUniversalMediaGeneration(forceVideo = true)
                                } catch (videoErr: Throwable) {
                                    if (videoErr is kotlinx.coroutines.CancellationException) throw videoErr
                                    if (generatedMedia.isEmpty() && contentBuilder.isEmpty() && profile.useLegacyFallbackEndpoint) {
                                        runLegacyOpenAiChatCompletions(effectiveHistoryNodes, effectiveContinuePrefix)
                                    } else {
                                        throw videoErr
                                    }
                                }
                            } else if (isDirectImagesEndpoint) {
                                try {
                                    runUniversalMediaGeneration(forceVideo = false)
                                } catch (imgErr: Throwable) {
                                    if (imgErr is kotlinx.coroutines.CancellationException) throw imgErr
                                    if (generatedMedia.isEmpty() && contentBuilder.isEmpty() && profile.useLegacyFallbackEndpoint) {
                                        runLegacyOpenAiChatCompletions(effectiveHistoryNodes, effectiveContinuePrefix)
                                    } else {
                                        throw imgErr
                                    }
                                }
                            } else if (isDirectAudioEndpoint) {
                                try {
                                    runUniversalAudioSpeechGeneration()
                                } catch (audErr: Throwable) {
                                    if (audErr is kotlinx.coroutines.CancellationException) throw audErr
                                    if (generatedMedia.isEmpty() && contentBuilder.isEmpty() && profile.useLegacyFallbackEndpoint) {
                                        runLegacyOpenAiChatCompletions(effectiveHistoryNodes, effectiveContinuePrefix)
                                    } else {
                                        throw audErr
                                    }
                                }
                            } else {
                                // Always attempt the newer /v1/responses protocol first; if it errors out
                                // (or exhausts fast probe retries when useLegacyFallbackEndpoint is ON), seamlessly
                                // hand off any accumulated thoughts/tools/prose to /v1/chat/completions!
                                if (emptyAnswerNudgePass >= 3 && effectiveProfileForPass.useLegacyFallbackEndpoint) {
                                    runLegacyOpenAiChatCompletions(effectiveHistoryNodes, effectiveContinuePrefix)
                                } else {
                                try {
                                    executeOpenAiResponsesLoop(
                                        profile = effectiveProfileForPass,
                                        model = model,
                                        session = session,
                                        historyNodes = effectiveHistoryNodes,
                                        continuePrefix = effectiveContinuePrefix,
                                        circuitBreaker = circuitBreaker,
                                        onStatus = { st -> emit(StreamUpdate.Status(st, lastInteractionId)) },
                                        onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                        onFirstToken = { recordTokenActivity() },
                                        onThinking = { delta ->
                                            if (delta.isNotEmpty()) recordTokenActivity()
                                            thinkingBuilder.append(delta)
                                            currentStepThinkingBuilder.append(delta)
                                            emit(StreamUpdate.ThinkingDelta(delta))
                                        },
                                        onContent = { delta ->
                                            if (delta.isNotEmpty()) recordTokenActivity()
                                            contentBuilder.append(delta)
                                            val approxOut = (contentBuilder.length / 4).coerceAtLeast(completedStepsOutputTokens + stepOutputTokens)
                                            val elapsedSec = ((PlatformBridge.currentTimeMillis() - startTime) / 1000L).toInt()
                                            PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                            emit(StreamUpdate.ContentDelta(delta))
                                        },
                                        onMedia = { item ->
                                            recordTokenActivity()
                                            generatedMedia.add(item)
                                            emit(StreamUpdate.MediaGenerated(item))
                                        },
                                        onToolCall = { tc ->
                                            upsertToolCallAndEmit(tc)
                                        },
                                        onRawWire = { req, resp ->
                                            recordRawWireBounded(req, resp)
                                        },
                                        onUsage = { id, inTok, cacheTok, thinkTok, outTok ->
                                            if (id.isNotBlank()) lastInteractionId = id
                                            recordStepUsage(inTok, cacheTok, thinkTok, outTok)
                                        },
                                        onCitation = { cit -> recordRawCitationAndEmit(cit) }
                                    )
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    val routeHint = detectMediaEndpointRedirectFromError(e)
                                    if (routeHint == "image") {
                                        recordDiscoveredModality(model, listOf("image"))
                                        emit(StreamUpdate.Status("Model requires Image Generation endpoint — routing automatically…", lastInteractionId))
                                        runUniversalMediaGeneration(forceVideo = false)
                                    } else if (routeHint == "video") {
                                        recordDiscoveredModality(model, listOf("video"))
                                        emit(StreamUpdate.Status("Model requires Video Generation endpoint — routing automatically…", lastInteractionId))
                                        runUniversalMediaGeneration(forceVideo = true)
                                    } else if (routeHint == "audio") {
                                        recordDiscoveredModality(model, listOf("audio"))
                                        emit(StreamUpdate.Status("Model requires Audio Speech endpoint — routing automatically…", lastInteractionId))
                                        runUniversalAudioSpeechGeneration()
                                    } else if (shouldFallbackToLegacyProtocol(e, profile.useLegacyFallbackEndpoint)) {
                                        val actualCause = if (e is StepRetriesExhaustedException && e.cause != null) e.cause!! else e
                                        val reasonClean = extractCleanApiErrorSummary(actualCause.message ?: "endpoint error")
                                        emit(
                                            StreamUpdate.Status(
                                                "Primary /v1/responses failed — falling back to /v1/chat/completions…\n$reasonClean",
                                                lastInteractionId
                                            )
                                        )
                                        val (fbNodes, fbPrefix) = buildEffectiveHistoryAndPrefix(
                                            baseHistoryNodes = activeBaseHistoryNodes,
                                            continuePrefix = contentBuilder.toString(),
                                            thinkingText = thinkingBuilder.toString(),
                                            trailingThinkingText = currentStepThinkingBuilder.toString().trim(),
                                            signatureText = signatureBuilder.toString(),
                                            executedTools = executedTools.toList(),
                                            generatedMedia = generatedMedia.toList(),
                                            protocol = profile.protocol,
                                            model = model,
                                            passTag = "fallback-openai-$continuationPass"
                                        )
                                        try {
                                            runLegacyOpenAiChatCompletions(fbNodes, fbPrefix)
                                        } catch (chatErr: Throwable) {
                                            if (chatErr is kotlinx.coroutines.CancellationException) throw chatErr
                                            val chatHint = detectMediaEndpointRedirectFromError(chatErr)
                                            if (chatHint == "image" || modalityProfile.isInterleavedImage) {
                                                recordDiscoveredModality(model, listOf("image"))
                                                runUniversalMediaGeneration(forceVideo = false)
                                            } else if (chatHint == "video") {
                                                recordDiscoveredModality(model, listOf("video"))
                                                runUniversalMediaGeneration(forceVideo = true)
                                            } else if (chatHint == "audio") {
                                                recordDiscoveredModality(model, listOf("audio"))
                                                runUniversalAudioSpeechGeneration()
                                            } else {
                                                throw chatErr
                                            }
                                        }
                                    } else {
                                        throw e
                                    }
                                }
                                }
                            }
                        }

                        ProviderProtocol.ANTHROPIC_MESSAGES -> {
                            executeAnthropicMessagesLoop(
                                profile = effectiveProfileForPass,
                                model = model,
                                session = session,
                                historyNodes = effectiveHistoryNodes,
                                continuePrefix = effectiveContinuePrefix,
                                circuitBreaker = circuitBreaker,
                                onStatus = { st -> emit(StreamUpdate.Status(st, lastInteractionId)) },
                                onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                onFirstToken = { recordTokenActivity() },
                                onThinking = { delta, sig ->
                                    if (delta.isNotEmpty()) recordTokenActivity()
                                    thinkingBuilder.append(delta)
                                    currentStepThinkingBuilder.append(delta)
                                    val uniqueSig = appendSignatureIfUnique(sig)
                                    if (delta.isNotEmpty() || uniqueSig.isNotEmpty()) {
                                        emit(StreamUpdate.ThinkingDelta(delta, uniqueSig))
                                    }
                                },
                                onContent = { delta ->
                                    if (delta.isNotEmpty()) recordTokenActivity()
                                    contentBuilder.append(delta)
                                    val approxOut = (contentBuilder.length / 4).coerceAtLeast(completedStepsOutputTokens + stepOutputTokens)
                                    val elapsedSec = ((PlatformBridge.currentTimeMillis() - startTime) / 1000L).toInt()
                                    PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                    emit(StreamUpdate.ContentDelta(delta))
                                },
                                onToolCall = { tc ->
                                    upsertToolCallAndEmit(tc)
                                },
                                onRawWire = { req, resp ->
                                    recordRawWireBounded(req, resp)
                                },
                                onUsage = { id, inTok, cacheTok, outTok ->
                                    if (id.isNotBlank()) lastInteractionId = id
                                    recordStepUsage(inTok, cacheTok, 0, outTok)
                                },
                                onCitation = { cit -> recordRawCitationAndEmit(cit) }
                            )
                        }
                    }
                    // Foolproof 3-Stage Zero-Answer Auto-Continuation Engine:
                    // Sometimes an upstream model or proxy finishes its reasoning (`thinkingBuilder` / `signatureBuilder`)
                    // or finishes executing tool calls (`executedTools`), and then closes the SSE stream cleanly (`[DONE]`)
                    // with ZERO final answer prose (`contentBuilder.isBlank()`).
                    // Instead of stopping and forcing the user to manually click '→ Continue', automatically nudge the model!
                    val hasNoFinalAnswer = contentBuilder.toString().isBlank() && generatedMedia.isEmpty()
                    val hasThoughtsOrTools = thinkingBuilder.isNotBlank() || signatureBuilder.isNotBlank() || executedTools.isNotEmpty()
                    val isBreakerTripped = circuitBreaker.checkTripped() != null

                    if (hasNoFinalAnswer && hasThoughtsOrTools && !isBreakerTripped && emptyAnswerNudgePass < 3) {
                        emptyAnswerNudgePass++
                        currentContinuePrefix = ""
                        val nudgeStatus = when (emptyAnswerNudgePass) {
                            1 -> "Reasoning complete — auto-continuing to generate final answer (1/3)…"
                            2 -> "Auto-nudging final answer with reduced thinking overhead (2/3)…"
                            else -> "Auto-nudging final answer via direct completion pass (3/3)…"
                        }
                        emit(StreamUpdate.Status(nudgeStatus, lastInteractionId))
                        continue
                    } else if (hasNoFinalAnswer && !hasThoughtsOrTools && emptyStreamRetryPass < 2) {
                        // Proxy returned HTTP 200 OK followed by immediate EOF with 0 thoughts, 0 tools, and 0 text
                        emptyStreamRetryPass++
                        emit(StreamUpdate.Status("Endpoint returned empty stream — auto-retrying ($emptyStreamRetryPass/2)…", lastInteractionId))
                        delay(1200L)
                        continue
                    }

                    // Completed cleanly with final answer
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (err: Throwable) {
                    val alreadyExhaustedStepRetries = err is StepRetriesExhaustedException
                    val actualErr = if (err is StepRetriesExhaustedException && err.cause != null) err.cause!! else err
                    val producedNewContent = contentBuilder.length > charsBeforePass
                    val producedNewThoughts = thinkingBuilder.length > thinkBeforePass || signatureBuilder.length > sigBeforePass
                    val producedMediaOrTools = generatedMedia.size > mediaBeforePass || executedTools.size > toolsBeforePass

                    // Reactive Context-Overflow Self-Healing: if the endpoint reports that the prompt/context
                    // exceeded the model's maximum context window, condense the oldest 50% of active turns into
                    // a Continuity Digest and retry immediately!
                    val unmutedCount = activeBaseHistoryNodes.count { it.includedInContext }
                    if (isContextLengthExceededError(actualErr) && unmutedCount > 4) {
                        activeBaseHistoryNodes = compactHistoryNodesOnOverflow(activeBaseHistoryNodes)
                        emit(
                            StreamUpdate.Status(
                                "Context window limit reached on endpoint — auto-compacting older turns & retrying…",
                                lastInteractionId
                            )
                        )
                        continue
                    }

                    val isRetryable = isTransientOrProxyTimeoutError(actualErr)
                    val isLongGoalRun = circuitBreaker.goalState != null
                    val maxRetries = if (isLongGoalRun) MAX_GOAL_NETWORK_RETRIES else MAX_PRE_TOKEN_RETRIES

                    if (!producedNewContent && !producedNewThoughts && !producedMediaOrTools &&
                        !alreadyExhaustedStepRetries && isRetryable && retryAttempt < maxRetries
                    ) {
                        retryAttempt++
                        waitWithRetryCountdown(
                            attempt = retryAttempt,
                            maxAttempts = maxRetries,
                            waitSeconds = RETRY_WAIT_SECONDS,
                            reasonSummary = actualErr.message ?: "Transient network/proxy error",
                            onStatus = { msg -> emit(StreamUpdate.Status(msg, lastInteractionId)) }
                        )
                        continue
                    } else if ((producedNewContent || producedNewThoughts || producedMediaOrTools) &&
                        isRetryable && (isLongGoalRun || continuationPass < MAX_MID_STREAM_CONTINUATION_PASSES)
                    ) {
                        // Defeat proxy/Cloudflare stream cutoffs or overnight Wi-Fi drops by waiting 60s and seamlessly stitching a continuation pass
                        continuationPass++
                        retryAttempt = 0
                        currentContinuePrefix = contentBuilder.toString()
                        val cleanInterruptReason = extractCleanApiErrorSummary(actualErr.message ?: "connection reset")
                        waitWithRetryCountdown(
                            attempt = continuationPass,
                            maxAttempts = if (isLongGoalRun) MAX_GOAL_NETWORK_RETRIES else MAX_MID_STREAM_CONTINUATION_PASSES,
                            waitSeconds = RETRY_WAIT_SECONDS,
                            reasonSummary = "Stream interrupted — resuming seamlessly: $cleanInterruptReason",
                            onStatus = { msg -> emit(StreamUpdate.Status(msg, lastInteractionId)) }
                        )
                        continue
                    } else {
                        throw actualErr
                    }
                }
            }

            closeCurrentDecodeBurst()
            if (toolBatchActiveStartMs > 0L) {
                totalToolExecutionMs += (PlatformBridge.currentTimeMillis() - toolBatchActiveStartMs).coerceAtLeast(0L)
                toolBatchActiveStartMs = 0L
            }
            commitCompletedStepUsage()

            val endTime = PlatformBridge.currentTimeMillis()
            val totalDurationMs = (endTime - startTime + initialElapsedMs.coerceAtLeast(0L)).coerceAtLeast(1L)
            val ttftMs = if (firstTokenTime > startTime) (firstTokenTime - startTime) else totalDurationMs
            val fallbackInTokens = if (inputTokens > 0) {
                inputTokens
            } else {
                val sysTok = O200kTokenizer.countTokens(buildCompiledSystemPrompt(session, circuitBreaker.goalState))
                val histTok = historyNodes.filter { it.includedInContext }.sumOf {
                    O200kTokenizer.countMessageVariantTokens(it.role, it.activeVariant, includeReasoningAndTools = false)
                }
                (sysTok + histTok).coerceAtLeast(1)
            }
            val rawMetrics = computeTurnTokenMetrics(
                reportedInputTokens = inputTokens,
                reportedCachedTokens = cachedTokens,
                reportedReasoningTokens = completedStepsReasoningTokens,
                reportedOutputTokens = completedStepsOutputTokens,
                fallbackInputTokens = fallbackInTokens,
                contentText = contentBuilder.toString(),
                thinkingText = thinkingBuilder.toString(),
                executedTools = executedTools,
                totalDurationMs = totalDurationMs,
                ttftMs = ttftMs,
                activeDecodeDurationMs = activeDecodeAccumulatedMs,
                totalToolExecutionMs = totalToolExecutionMs
            )
            val costUsd = profile.calculateTurnCostUsd(rawMetrics)

            // If web search or URL tools were active and the model included inline markdown citations, bind them too
            if (profile.enableWebSearch || profile.enableUrlContext || executedTools.any {
                it.name.equals("web_fetch", ignoreCase = true) || it.name.equals("web_search", ignoreCase = true)
            }) {
                extractMarkdownLinksAsCitations(contentBuilder.toString()).forEach { mdCit ->
                    if (rawWebCitations.none { it.url.equals(mdCit.url, ignoreCase = true) }) {
                        rawWebCitations.add(mdCit)
                    }
                }
            }
            val finalWebSources = bindSourcesToMessageLines(contentBuilder.toString(), rawWebCitations)
            if (finalWebSources.isNotEmpty()) {
                emit(StreamUpdate.WebSourcesUpdated(finalWebSources))
            }

            emit(
                StreamUpdate.Completed(
                    fullContent = contentBuilder.toString(),
                    fullThinking = thinkingBuilder.toString(),
                    trailingThinking = if (executedTools.isNotEmpty()) currentStepThinkingBuilder.toString().trim() else "",
                    thinkingSignature = signatureBuilder.toString(),
                    toolCalls = executedTools,
                    goalTracker = circuitBreaker.goalState,
                    mediaItems = generatedMedia,
                    webSources = finalWebSources,
                    providerInteractionId = lastInteractionId,
                    metrics = rawMetrics.copy(estimatedCostUsd = costUsd),
                    rawRequest = lastRawReq,
                    rawResponse = lastRawResp
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            PlatformBridge.killActiveShellProcesses()
            throw e
        } catch (t: Throwable) {
            val cleanErr = extractCleanApiErrorSummary(t.message ?: t::class.simpleName ?: "Unknown network error")
            emit(StreamUpdate.Error(message = cleanErr, rawDetails = lastRawResp))
        } finally {
            PlatformBridge.notifyLongDecodeComplete()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Computes accurate turn-level `TokenMetrics` and `tokensPerSec`:
     * - Numerator includes both `finalOutTokens` (prose + tool call arguments across all rounds) and `finalThinkTokens`.
     * - Denominator uses `activeDecodeDurationMs` (active token streaming bursts excluding TTFT wait and local tool execution time).
     */
    internal fun computeTurnTokenMetrics(
        reportedInputTokens: Int,
        reportedCachedTokens: Int,
        reportedReasoningTokens: Int,
        reportedOutputTokens: Int,
        fallbackInputTokens: Int,
        contentText: String,
        thinkingText: String,
        executedTools: List<ToolCallRecord>,
        totalDurationMs: Long,
        ttftMs: Long,
        activeDecodeDurationMs: Long,
        totalToolExecutionMs: Long
    ): TokenMetrics {
        val finalInTokens = if (reportedInputTokens > 0) reportedInputTokens else fallbackInputTokens.coerceAtLeast(1)
        val bpeThinkTokens = if (thinkingText.isNotEmpty()) {
            O200kTokenizer.countTokens(thinkingText).coerceAtLeast(1)
        } else 0
        val bpeToolCallTokens = executedTools.sumOf { tc ->
            O200kTokenizer.countTokens(tc.name) + O200kTokenizer.countTokens(tc.argumentsJson)
        }
        val bpeContentTokens = if (contentText.isNotEmpty()) {
            O200kTokenizer.countTokens(contentText).coerceAtLeast(1)
        } else 0

        val adjustedReportedOut = if (reportedReasoningTokens == 0 && bpeThinkTokens > 0 && reportedOutputTokens > bpeThinkTokens) {
            (reportedOutputTokens - bpeThinkTokens).coerceAtLeast(1)
        } else {
            reportedOutputTokens
        }
        val finalThinkTokens = maxOf(reportedReasoningTokens, bpeThinkTokens)
        val finalOutTokens = maxOf(adjustedReportedOut, bpeContentTokens + bpeToolCallTokens).coerceAtLeast(1)
        val totalGeneratedTokens = (finalOutTokens + finalThinkTokens).coerceAtLeast(1)

        val netLlmGenerationMs = (totalDurationMs - totalToolExecutionMs.coerceAtLeast(0L)).coerceAtLeast(250L)
        val minRealisticBurstMs = maxOf(500L, totalGeneratedTokens.toLong() * 8L)
        val effectiveDecodeMs = if (activeDecodeDurationMs >= minRealisticBurstMs && activeDecodeDurationMs <= netLlmGenerationMs) {
            activeDecodeDurationMs
        } else {
            netLlmGenerationMs
        }
        val tokPerSec = (totalGeneratedTokens * 1000.0) / effectiveDecodeMs.toDouble()
        return TokenMetrics(
            inputTokens = finalInTokens,
            cachedInputTokens = reportedCachedTokens,
            reasoningTokens = finalThinkTokens,
            outputTokens = finalOutTokens,
            ttftMs = ttftMs,
            durationMs = totalDurationMs,
            tokensPerSec = ((tokPerSec * 10.0).toInt()) / 10.0
        )
    }

    /**
     * Lightweight one-shot utility text generation for Auto-Title Generation,
     * Title Regeneration (`✦`), Context Summarization, and Translation.
     * `maxOutputTokens` defaults to `null` (unlimited) so summaries, compactions, and translations
     * are never truncated mid-sentence.
     */
    suspend fun generateUtilityText(
        profile: ProviderProfile,
        model: String,
        prompt: String,
        maxOutputTokens: Int? = null
    ): Result<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching {
            val cleanModel = model.trim().ifBlank { profile.defaultModel.trim() }
            require(cleanModel.isNotBlank()) { "No model selected for utility generation." }
            val tempSession = ChatSession(
                id = "util-temp",
                title = "Utility",
                createdAt = 0L,
                updatedAt = 0L,
                profileId = profile.id,
                modelOverride = cleanModel
            )
            val userNode = MessageNode(
                id = "util-node",
                role = MessageRole.USER,
                variants = listOf(MessageVariant(id = "util-var", content = prompt))
            )
            val fastProfile = profile.copy(
                thinkingEffort = "low",
                maxOutputTokens = maxOutputTokens,
                enableWebSearch = false,
                enableCodeExecution = false,
                enableUrlContext = false,
                enableImageGeneration = false,
                enableVideoGeneration = false,
                useBackgroundLongDecode = false
            )
            val sb = StringBuilder()
            var streamErr: String? = null
            streamGeneration(
                profile = fastProfile,
                session = tempSession,
                historyNodes = listOf(userNode)
            ).collect { upd ->
                when (upd) {
                    is StreamUpdate.ContentDelta -> sb.append(upd.delta)
                    is StreamUpdate.Completed -> if (sb.isEmpty()) sb.append(upd.fullContent)
                    is StreamUpdate.Error -> streamErr = upd.message
                    else -> {}
                }
            }
            val finalStr = sb.toString().trim().removeSurrounding("\"").removeSurrounding("'").trim()
            if (finalStr.isEmpty() && !streamErr.isNullOrBlank()) {
                error(streamErr)
            }
            finalStr
        }
    }

    /**
     * Dynamically fetches the available model IDs from the active provider or custom proxy endpoint.
     * Supports Google `/v1beta/models`, OpenAI `/v1/models`, `/api/v3/models`, and Anthropic `/v1/models` formats.
     */
    suspend fun fetchAvailableModels(profile: ProviderProfile): Result<List<String>> = runCatching {
        val base = profile.baseUrl.trim().trimEnd('#').trimEnd('/')
        val hasVersionSuffix = Regex("/(?:api/)?(?:paas/)?v\\d+(?:beta\\d*|alpha\\d*)?$", RegexOption.IGNORE_CASE)
            .containsMatchIn(base)
        val candidateUrls = when {
            base.endsWith("/models", ignoreCase = true) -> listOf(base)
            hasVersionSuffix -> listOf(
                "$base/models",
                "${base.replace(Regex("/(?:api/)?(?:paas/)?v\\d+(?:beta\\d*|alpha\\d*)?$", RegexOption.IGNORE_CASE), "")}/v1/models"
            ).distinct()
            profile.protocol == ProviderProtocol.GEMINI_INTERACTIONS -> listOf(
                "$base/v1beta/models?pageSize=200",
                "$base/v1/models",
                "$base/models"
            )
            profile.protocol == ProviderProtocol.ANTHROPIC_MESSAGES -> listOf(
                "$base/v1/models?limit=100",
                "$base/v1/models",
                "$base/models"
            )
            else -> listOf(
                "$base/v1/models",
                "$base/api/v3/models",
                "$base/models"
            )
        }

        var lastError = ""
        for (rawUrl in candidateUrls) {
            val url = buildUrlWithAuthQuery(profile, rawUrl)
            val respResult = runCatching {
                httpClient.get(url) {
                    applyAuthAndCustomHeaders(profile)
                    if (profile.protocol == ProviderProtocol.ANTHROPIC_MESSAGES) {
                        header("anthropic-version", "2023-06-01")
                    }
                }
            }
            val resp = respResult.getOrNull()
            if (resp == null) {
                val err = respResult.exceptionOrNull()
                lastError = err?.message ?: err?.let { it::class.simpleName } ?: "Connection failed"
                continue
            }

            val bodyText = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                lastError = "HTTP ${resp.status.value}: ${bodyText.take(240)}"
                continue
            }

            val parsed = parseModelsListJson(bodyText)
            if (parsed.isNotEmpty()) {
                return@runCatching parsed
            }
        }

        if (lastError.isNotBlank()) {
            error(lastError)
        } else {
            error("Endpoint returned an empty model list.")
        }
    }

    internal fun recordDiscoveredModality(modelId: String, modalities: List<String>) {
        val clean = modelId.trim().removePrefix("models/").lowercase()
        if (clean.isEmpty() || modalities.isEmpty()) return
        val normalized = modalities.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (normalized.isNotEmpty()) {
            discoveredModelModalities[clean] = normalized
            val shortKey = clean.substringAfterLast("/")
            if (shortKey.isNotEmpty()) {
                discoveredModelModalities[shortKey] = normalized
            }
            val canonical = ModelsDevClient.normalizeCanonicalModelSlug(clean)
            if (canonical.isNotEmpty()) {
                discoveredModelModalities[canonical] = normalized
            }
        }
    }

    internal fun recordDiscoveredVendor(modelId: String, vendor: String) {
        val clean = modelId.trim().removePrefix("models/").lowercase()
        val cleanVendor = vendor.trim()
        if (clean.isEmpty() || cleanVendor.isEmpty()) return
        discoveredModelVendors[clean] = cleanVendor
        val shortKey = clean.substringAfterLast("/")
        if (shortKey.isNotEmpty()) {
            discoveredModelVendors[shortKey] = cleanVendor
        }
    }

    fun getDiscoveredVendorForModel(modelId: String): String? {
        val clean = modelId.trim().removePrefix("models/").lowercase()
        if (clean.isEmpty()) return null
        return discoveredModelVendors[clean]
            ?: discoveredModelVendors[clean.substringAfterLast("/")]
            ?: ModelsDevClient.getCachedVendorSlug(modelId)
    }

    internal fun inferStructuralModalitiesFromModelSlug(
        modelId: String,
        endpointOverride: String = ""
    ): List<String> {
        val s = modelId.trim().lowercase()
        val ov = endpointOverride.trim().lowercase()
        if (s.isEmpty() && ov.isEmpty()) return emptyList()

        return when {
            ov.contains("contents/generations/tasks") ||
                ov.contains("videos/generations") ||
                ov.contains("video/generations") ||
                s.contains("seedance") ||
                s.contains("veo-") || s.contains("/veo") || s.startsWith("veo") ||
                s.contains("sora") ||
                s.contains("kling") ||
                (s.contains("wan") && !s.contains("image")) && (s.contains("wan-") || s.contains("wan2") || s.contains("wan3") || s.contains("wanx")) ||
                s.contains("hailuo") ||
                s.contains("runway") || s.contains("gen-3") || s.contains("gen-4") || s.contains("aleph-") ||
                s.contains("luma") || s.contains("ray-2") || s.contains("ray2") ||
                s.contains("pixverse") || s.contains("vidu") || s.contains("pika-") ||
                s.contains("hunyuan-video") || s.contains("cogvideo") || s.contains("mochi-") ||
                s.contains("ltx-video") || s.contains("grok-imagine-video") ||
                (s.contains("flux") && s.contains("video")) || s.contains("happyhorse") -> listOf("video")

            (s.contains("gemini") && s.contains("image")) ||
                s.contains("nano-banana") ||
                (s.contains("gpt-5") && s.contains("image")) ||
                (s.contains("omni") && s.contains("image")) -> listOf("text", "image")

            ov.contains("images/generations") ||
                ov.contains("images/edits") ||
                s.contains("seedream") ||
                s.contains("dreamina") ||
                s.contains("imagen") ||
                s.contains("dall-e") ||
                s.contains("gpt-image") ||
                s.contains("chatgpt-image") ||
                (s.contains("flux") && !s.contains("video")) ||
                s.contains("recraft") ||
                s.contains("ideogram") ||
                s.contains("midjourney") ||
                s.contains("stable-diffusion") || s.contains("sdxl") || s.contains("sd3") ||
                s.contains("qwen-image") ||
                (s.contains("wan") && s.contains("image")) ||
                s.contains("grok-imagine-image") || s.contains("grok-2-image") || s.contains("aurora") ||
                s.contains("mai-image") || s.contains("muse-image") || s.contains("riverflow") ||
                s.contains("krea") || s.contains("kolors") || s.contains("playground-v") ||
                s.contains("hidream") -> listOf("image")

            ov.contains("audio/speech") ||
                s.contains("seed-audio") ||
                s.startsWith("tts-") || s.contains("/tts-") || s.endsWith("-tts") || s.contains("-tts-") ||
                s.contains("elevenlabs") || s.contains("cosyvoice") || s.contains("fish-speech") ||
                s.contains("kokoro") || s.contains("sonic-") -> listOf("audio")

            s.contains("-live") || s.contains("live-") ||
                s.contains("-realtime") || s.contains("realtime-") ||
                s.contains("native-audio") -> listOf("text", "audio")

            else -> emptyList()
        }
    }

    internal fun resolveKnownOutputModalities(profile: ProviderProfile?, modelId: String): List<String> {
        val clean = modelId.trim().removePrefix("models/").lowercase()
        if (clean.isEmpty()) return emptyList()
        val shortKey = clean.substringAfterLast("/")
        val canonical = ModelsDevClient.normalizeCanonicalModelSlug(clean)
        if (profile != null) {
            profile.customModelSpecs[modelId.trim()]?.outputModalities?.takeIf { it.isNotEmpty() }?.let { return it }
            profile.customModelSpecs[clean]?.outputModalities?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        discoveredModelModalities[clean]?.takeIf { it.isNotEmpty() }?.let { return it }
        discoveredModelModalities[shortKey]?.takeIf { it.isNotEmpty() }?.let { return it }
        discoveredModelModalities[canonical]?.takeIf { it.isNotEmpty() }?.let { return it }

        val registryMods = ModelsDevClient.getCachedOutputModalities(modelId)
        val structuralMods = inferStructuralModalitiesFromModelSlug(modelId, profile?.endpointPathOverride.orEmpty())
        return when {
            structuralMods.any { it != "text" } -> structuralMods
            registryMods.isNotEmpty() -> registryMods
            else -> structuralMods
        }
    }

    fun resolveModelModalityProfile(profile: ProviderProfile?, modelId: String): ModelModalityProfile {
        val outMods = resolveKnownOutputModalities(profile, modelId)
        val inMods = ModelsDevClient.getCachedInputModalities(modelId).ifEmpty { listOf("text") }
        val s = modelId.trim().lowercase()
        val isLive = s.contains("-live") || s.contains("live-") ||
            s.contains("-realtime") || s.contains("realtime-") ||
            s.contains("native-audio") ||
            (outMods.contains("audio") && outMods.contains("text"))
        val isDedicatedVid = outMods.contains("video") && !outMods.contains("text")
        val isDedicatedImg = outMods.contains("image") && !outMods.contains("text")
        val isInterleavedImg = outMods.contains("image") && outMods.contains("text")
        val isDedicatedAud = (outMods.contains("audio") || outMods.contains("speech")) && !outMods.contains("text")
        return ModelModalityProfile(
            outputModalities = outMods.ifEmpty { listOf("text") },
            inputModalities = inMods,
            isDedicatedImage = isDedicatedImg,
            isInterleavedImage = isInterleavedImg,
            isDedicatedVideo = isDedicatedVid,
            isDedicatedAudioTts = isDedicatedAud,
            isLiveRealtimeAudio = isLive
        )
    }

    internal suspend fun resolveModelModalityProfileAsync(
        profile: ProviderProfile?,
        modelId: String
    ): ModelModalityProfile {
        val fastProfile = resolveModelModalityProfile(profile, modelId)
        if (fastProfile.isDedicatedImage ||
            fastProfile.isDedicatedVideo ||
            fastProfile.isDedicatedAudioTts ||
            fastProfile.isInterleavedImage ||
            ModelsDevClient.getCachedOutputModalities(modelId).isNotEmpty()
        ) {
            return fastProfile
        }
        val networkMods = ModelsDevClient.resolveOutputModalities(modelId)
        if (networkMods.isNotEmpty()) {
            recordDiscoveredModality(modelId, networkMods)
        }
        return resolveModelModalityProfile(profile, modelId)
    }

    internal fun detectMediaEndpointRedirectFromError(err: Throwable): String? {
        val msg = (err.message ?: err.toString()).lowercase()
        if (msg.contains("contents/generations/tasks") ||
            msg.contains("videos/generations") ||
            msg.contains("video/generations") ||
            msg.contains("v1/videos") ||
            msg.contains("video generation") ||
            msg.contains("video model")
        ) {
            return "video"
        }
        if (msg.contains("images/generations") ||
            msg.contains("images/edits") ||
            msg.contains("v1/images") ||
            msg.contains("only supports image") ||
            msg.contains("image generation") ||
            (msg.contains("not a chat model") && msg.contains("image"))
        ) {
            return "image"
        }
        if (msg.contains("audio/speech") ||
            msg.contains("v1/audio") ||
            msg.contains("speech generation") ||
            msg.contains("tts model")
        ) {
            return "audio"
        }
        return null
    }

    internal fun parseModelsListJson(bodyText: String): List<String> {
        val rootEl = runCatching { json.parseToJsonElement(bodyText) }.getOrNull() ?: return emptyList()
        val results = LinkedHashSet<String>()

        fun extractModalitiesFromModelObject(item: JsonObject): List<String> {
            val outList = mutableListOf<String>()
            (item["output_modalities"] as? JsonArray)?.forEach {
                it.jsonPrimitive.contentOrNull?.trim()?.lowercase()?.takeIf { s -> s.isNotEmpty() }?.let(outList::add)
            }
            val modObj = item["modalities"] as? JsonObject
            (modObj?.get("output") as? JsonArray)?.forEach {
                it.jsonPrimitive.contentOrNull?.trim()?.lowercase()?.takeIf { s -> s.isNotEmpty() }?.let(outList::add)
            }
            val archObj = item["architecture"] as? JsonObject
            (archObj?.get("output_modalities") as? JsonArray)?.forEach {
                it.jsonPrimitive.contentOrNull?.trim()?.lowercase()?.takeIf { s -> s.isNotEmpty() }?.let(outList::add)
            }
            val archMod = archObj?.get("modality")?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            if (archMod.contains("->")) {
                val rhs = archMod.substringAfter("->")
                rhs.split("+", ",", "/").map { it.trim() }.filter { it.isNotEmpty() }.forEach(outList::add)
            }
            val modelType = (item["type"]?.jsonPrimitive?.contentOrNull
                ?: item["mode"]?.jsonPrimitive?.contentOrNull
                ?: item["task_type"]?.jsonPrimitive?.contentOrNull
                ?: item["task"]?.jsonPrimitive?.contentOrNull)?.trim()?.lowercase().orEmpty()
            val tags = (item["tags"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.lowercase() }.orEmpty()
            when {
                modelType.contains("video") || tags.any { it.contains("video") } -> outList.add("video")
                modelType.contains("image") || tags.any { it.contains("image") } -> outList.add("image")
                modelType.contains("audio_speech") || modelType == "tts" || modelType == "speech" ||
                    modelType == "audio" || tags.any { it.contains("audio") || it.contains("tts") } -> outList.add("audio")
                modelType == "chat" || modelType == "text" || modelType == "language" ||
                    modelType == "responses" || modelType == "completion" -> outList.add("text")
            }
            val genMethods = ((item["supportedGenerationMethods"] as? JsonArray)
                ?: (item["supported_generation_methods"] as? JsonArray))
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }.orEmpty()
            if (genMethods.any { it.contains("bidi") }) {
                outList.add("text")
                outList.add("audio")
            } else if (genMethods.any { it.contains("predict") || it.contains("generateimages") } &&
                genMethods.none { it.contains("generatecontent") }
            ) {
                outList.add("image")
            } else if (genMethods.any { it.contains("generatevideo") }) {
                outList.add("video")
            }
            return outList.distinct()
        }

        fun extractFromArray(arr: JsonArray) {
            for (item in arr) {
                when (item) {
                    is JsonPrimitive -> {
                        val s = item.contentOrNull?.trim().orEmpty()
                        if (s.isNotEmpty()) results.add(s.removePrefix("models/"))
                    }
                    is JsonObject -> {
                        val idCandidate = item["id"]?.jsonPrimitive?.contentOrNull
                            ?: item["name"]?.jsonPrimitive?.contentOrNull
                            ?: item["model"]?.jsonPrimitive?.contentOrNull
                        if (!idCandidate.isNullOrBlank()) {
                            val cleanId = idCandidate.trim().removePrefix("models/")
                            results.add(cleanId)
                            val discovered = extractModalitiesFromModelObject(item)
                            if (discovered.isNotEmpty()) {
                                recordDiscoveredModality(cleanId, discovered)
                            }
                            val infoObj = item["info"] as? JsonObject
                            val vendor = item["owned_by"]?.jsonPrimitive?.contentOrNull
                                ?: item["provider"]?.jsonPrimitive?.contentOrNull
                                ?: item["developer"]?.jsonPrimitive?.contentOrNull
                                ?: infoObj?.get("developer")?.jsonPrimitive?.contentOrNull
                            if (!vendor.isNullOrBlank()) {
                                recordDiscoveredVendor(cleanId, vendor)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        when (rootEl) {
            is JsonArray -> extractFromArray(rootEl)
            is JsonObject -> {
                (rootEl["models"] as? JsonArray)?.let { extractFromArray(it) }
                (rootEl["data"] as? JsonArray)?.let { extractFromArray(it) }
            }
            else -> {}
        }

        return results.toList()
    }

    /**
     * Cancels a server-side background interaction on Google Gemini Interactions API.
     */
    suspend fun cancelBackgroundInteraction(profile: ProviderProfile, interactionId: String): Result<String> = runCatching {
        if (interactionId.isBlank()) return@runCatching "No interaction ID to cancel."
        val base = profile.baseUrl.trimEnd('/')
        val path = profile.endpointPathOverride.ifBlank { "/v1beta/interactions" }.trimEnd('/')
        val url = buildUrlWithAuthQuery(profile, "$base$path/$interactionId/cancel")
        val response = httpClient.post(url) {
            applyAuthAndCustomHeaders(profile)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        response.bodyAsText()
    }

    // ============================================================================================
    // 1. GOOGLE GEMINI INTERACTIONS API DRIVER (/v1beta/interactions)
    //    Supports Gemini 4 Argon Long Decode, SSE Streaming, Background=true Polling,
    //    previous_interaction_id stateful chaining, Built-in Tools & Local Workspace Harness.
    // ============================================================================================
    private suspend fun executeGeminiInteractionsLoop(
        profile: ProviderProfile,
        model: String,
        session: ChatSession,
        historyNodes: List<MessageNode>,
        continuePrefix: String,
        circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
        onStatus: suspend (String, String) -> Unit,
        onGoalProgress: suspend (GoalTrackerState) -> Unit,
        onFirstToken: () -> Unit,
        onThinking: suspend (String, String) -> Unit,
        onContent: suspend (String) -> Unit,
        onMedia: suspend (GeneratedMediaItem) -> Unit,
        onToolCall: suspend (ToolCallRecord) -> Unit,
        onRawWire: suspend (String, String) -> Unit,
        onUsage: (String, Int, Int, Int, Int) -> Unit,
        onCitation: suspend (RawWebCitation) -> Unit = {}
    ) {
        val activeNodes = sanitizeContextNodes(historyNodes)
        val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)

        // Check if we can use stateful previous_interaction_id chaining
        val previousAssistantIdx = activeNodes.indexOfLast { it.role == MessageRole.ASSISTANT }
        val previousInteractionId = if (profile.useStatefulChaining && continuePrefix.isBlank() && previousAssistantIdx >= 0) {
            val prevVar = activeNodes[previousAssistantIdx].activeVariant
            if (prevVar.providerProtocol == ProviderProtocol.GEMINI_INTERACTIONS && prevVar.providerInteractionId.isNotBlank()) {
                prevVar.providerInteractionId
            } else ""
        } else ""
        var currentPrevInteractionId = previousInteractionId
        var currentContinuationToken = ""
        var stripSamplingOnRetry = false
        var upgradeMinimalToLowOnRetry = false
        var simplifyThinkingOnRetry = false
        var omitResponseFormatOnRetry = false
        var forceImageResponseFormatOnRetry = false
        var omitBuiltInToolsOnRetry = false
        var foldHistoricalToolsOnRetry = false
        val hasHistoricalToolsOrSigs = activeNodes.any {
            it.role == MessageRole.ASSISTANT && (it.activeVariant.toolCalls.isNotEmpty() || it.activeVariant.thinkingSignature.isNotBlank())
        }
        val extraLoopSteps = mutableListOf<JsonObject>()
        var pendingToolResults: List<ToolCallRecord> = emptyList()
        var pendingStopHookPrompt: String = ""
        var loopStep = 0

        val knownModalities = resolveKnownOutputModalities(profile, model)
        val modelOutputsImages = knownModalities.contains("image")
        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val turnToolCalls = mutableListOf<Pair<String, Pair<String, String>>>() // callId -> (name, argsJson)
            val roundThoughtSig = StringBuilder()
            val stepSigByIndex = mutableMapOf<Int, StringBuilder>()
            val roundThoughtText = StringBuilder()
            val roundModelText = StringBuilder()
            var roundStatus = ""
            var roundContinuationToken = ""
            var receivedAnyStreamContent = false
            var stepRetryAttempt = 0

            fun addUniqueToolCall(callId: String, fnName: String, argsStr: String) {
                if (fnName.isBlank()) return
                val cleanArgs = argsStr.ifBlank { "{}" }
                val existingIdx = turnToolCalls.indexOfFirst { it.first == callId && callId.isNotBlank() }
                if (existingIdx >= 0) {
                    val prev = turnToolCalls[existingIdx]
                    if ((prev.second.second == "{}" || prev.second.second.isBlank()) && cleanArgs != "{}") {
                        turnToolCalls[existingIdx] = callId to (fnName to cleanArgs)
                    }
                } else {
                    turnToolCalls.add(callId to (fnName to cleanArgs))
                }
            }

            fun emitUniqueRoundSig(sig: String, stepIdx: Int = -1): String {
                if (sig.isEmpty()) return ""
                if (stepIdx >= 0) {
                    val stepBuilder = stepSigByIndex.getOrPut(stepIdx) { StringBuilder() }
                    val emitted = appendSignatureChunkSafe(stepBuilder, sig)
                    val fullStepSig = stepBuilder.toString()
                    if (!isGeminiServerSideToolSignature(fullStepSig) || roundThoughtSig.isEmpty()) {
                        roundThoughtSig.setLength(0)
                        roundThoughtSig.append(fullStepSig)
                    }
                    return emitted
                }
                return appendSignatureChunkSafe(roundThoughtSig, sig)
            }

            while (true) {
                turnToolCalls.clear()
                roundThoughtSig.clear()
                stepSigByIndex.clear()
                roundThoughtText.clear()
                roundModelText.clear()
                roundStatus = ""
                roundContinuationToken = ""
                receivedAnyStreamContent = false

                val useChainedId = profile.useStatefulChaining && currentPrevInteractionId.isNotBlank()
                val isContinuationHop = useChainedId && currentContinuationToken.isNotBlank()
                val baseInputSteps = if (useChainedId && previousAssistantIdx >= 0 && previousAssistantIdx < activeNodes.size - 1) {
                    val trailingNodes = activeNodes.subList(previousAssistantIdx + 1, activeNodes.size)
                    buildGeminiInteractionsTurnsArray(trailingNodes, session, continuePrefix, foldHistoricalToolsOnRetry)
                } else {
                    buildGeminiInteractionsTurnsArray(activeNodes, session, continuePrefix, foldHistoricalToolsOnRetry)
                }

                // 3-Layer Rolling Context Compaction: prune verbose tool outputs older than 8 steps in-place in RAM
                val compactedExtraSteps = compactGeminiInteractionsExtraSteps(extraLoopSteps)
                if (compactedExtraSteps !== extraLoopSteps) {
                    extraLoopSteps.clear()
                    extraLoopSteps.addAll(compactedExtraSteps)
                }

                val payloadObj = buildJsonObject {
                    put("model", model.trim().removePrefix("models/"))
                    if (systemInstruction.isNotBlank()) {
                        put("system_instruction", systemInstruction)
                    }

                    if (!profile.useStatefulChaining) {
                        put("store", false)
                    }

                    // Always stream SSE in real time so tokens & reasoning flow smoothly at 60/120fps;
                    // if `useBackgroundLongDecode` is enabled and the stream drops, we seamlessly poll `/interactions/{id}`!
                    put("stream", true)

                    if (useChainedId) {
                        put("previous_interaction_id", currentPrevInteractionId)
                    }
                    if (isContinuationHop) {
                        put("continuation_token", currentContinuationToken)
                    }

                    // Build `input` using official Interactions API Steps schema (omitted when continuing a foreground hop via continuation_token)
                    if (!isContinuationHop) {
                        if (pendingToolResults.isNotEmpty() && useChainedId) {
                            putJsonArray("input") {
                                for (tr in pendingToolResults) {
                                    add(buildJsonObject {
                                        put("type", "function_result")
                                        put("name", tr.name)
                                        put("call_id", tr.id)
                                        put("is_error", tr.isError)
                                        putJsonObject("result") {
                                            put("result", tr.resultText)
                                        }
                                    })
                                }
                            }
                        } else if (pendingStopHookPrompt.isNotBlank() && useChainedId) {
                            putJsonArray("input") {
                                add(buildJsonObject {
                                    put("type", "user_input")
                                    putJsonArray("content") {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", pendingStopHookPrompt)
                                        })
                                    }
                                })
                            }
                        } else {
                            putJsonArray("input") {
                                baseInputSteps.forEach { add(it) }
                                compactedExtraSteps.forEach { add(it) }
                            }
                        }
                    }

                    // Official polymorphic `response_format` array when model outputs images
                    if ((modelOutputsImages || forceImageResponseFormatOnRetry) && !omitResponseFormatOnRetry) {
                        putJsonArray("response_format") {
                            add(buildJsonObject { put("type", "text") })
                            add(buildJsonObject {
                                put("type", "image")
                                if (profile.mediaAspectRatio.isNotBlank()) {
                                    put("aspect_ratio", profile.mediaAspectRatio)
                                }
                                if (profile.mediaResolution in listOf("512", "1K", "2K", "4K")) {
                                    put("image_size", profile.mediaResolution)
                                }
                            })
                        }
                    }

                    // Generation config (self-heals sampling & thinking_level dynamically on HTTP 400)
                    putJsonObject("generation_config") {
                        if (!stripSamplingOnRetry) {
                            profile.temperature?.let { put("temperature", it) }
                            profile.topP?.let { put("top_p", it) }
                        }
                        val maxOut = profile.maxOutputTokens
                        if (maxOut != null && maxOut > 0) {
                            put("max_output_tokens", maxOut)
                        }
                        val rawEffort = profile.thinkingEffort.trim()
                        val lowerEffort = rawEffort.lowercase()
                        if (lowerEffort != "none" && lowerEffort != "off") {
                            put("thinking_summaries", "auto")
                            if (!simplifyThinkingOnRetry && rawEffort.isNotEmpty() && lowerEffort != "default" && lowerEffort != "auto") {
                                val mappedLevel = when (lowerEffort) {
                                    "minimal" -> if (upgradeMinimalToLowOnRetry) "low" else "minimal"
                                    "low" -> "low"
                                    "medium" -> "medium"
                                    "high", "xhigh", "max" -> "high"
                                    else -> lowerEffort
                                }
                                put("thinking_level", mappedLevel)
                            }
                        }
                    }

                    // Built-in Tools + Local Workspace Harness Tools
                    val workspaceTools = WorkspaceHarness.buildGeminiInteractionsTools(
                        session.workspaceMode,
                        session.workspacePath,
                        profile.allowShellExecution,
                        goalModeActive = circuitBreaker.goalState != null,
                        enableWebTools = (profile.enableWebSearch && omitBuiltInToolsOnRetry) || profile.enableUrlContext,
                        includeLocalWebSearchTool = profile.enableWebSearch && omitBuiltInToolsOnRetry
                    )
                    val toolsArray = buildJsonArray {
                        if (!omitBuiltInToolsOnRetry || workspaceTools.isEmpty()) {
                            if (profile.enableWebSearch) {
                                add(buildJsonObject { put("type", "google_search") })
                            }
                            if (profile.enableCodeExecution) {
                                add(buildJsonObject { put("type", "code_execution") })
                            }
                            if (profile.enableUrlContext) {
                                add(buildJsonObject { put("type", "url_context") })
                            }
                        }
                        workspaceTools.forEach { add(it) }
                    }
                    if (toolsArray.isNotEmpty()) {
                        put("tools", toolsArray)
                    }
                }

                val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
                val requestJsonStr = json.encodeToString(JsonObject.serializer(), finalPayload)

                val base = profile.baseUrl.trim().trimEnd('#').trimEnd('/')
                val defaultPath = if (base.endsWith("/v1beta") || base.endsWith("/v1")) "/interactions" else "/v1beta/interactions"
                val path = profile.endpointPathOverride.ifBlank { defaultPath }
                val rawUrl = resolveSmartEndpointUrl(
                    profile = profile,
                    defaultPathWithV1 = "/v1beta/interactions",
                    defaultPathWithoutVersion = "/interactions"
                )
                val finalUrl = buildUrlWithAuthQuery(profile, rawUrl)

                onRawWire(requestJsonStr, "Dispatching to $finalUrl ...")

                suspend fun pollInteractionUntilComplete(interactionIdToPoll: String) {
                    var pollCount = 0
                    var status = "in_progress"
                    var interactionObj = JsonObject(emptyMap())
                    while ((status == "in_progress" || status == "queued" || status == "processing") && interactionIdToPoll.isNotBlank()) {
                        pollCount++
                        onStatus("Background Poll Recovery ($status • poll #$pollCount)…", interactionIdToPoll)
                        delay(2000L)
                        val pollUrl = buildUrlWithAuthQuery(profile, "$base${path.substringBefore("?")}/$interactionIdToPoll")
                        val pollResp = httpClient.get(pollUrl) {
                            applyAuthAndCustomHeaders(profile)
                        }
                        val pollText = pollResp.bodyAsText()
                        onRawWire(requestJsonStr, pollText)
                        if (!pollResp.status.isSuccess()) {
                            error("Gemini Interactions Poll HTTP ${pollResp.status.value}: $pollText")
                        }
                        interactionObj = json.parseToJsonElement(pollText).jsonObject
                        status = interactionObj["status"]?.jsonPrimitive?.contentOrNull ?: "completed"
                    }
                    if (!receivedAnyStreamContent && interactionObj.isNotEmpty()) {
                        roundStatus = status
                        roundContinuationToken = interactionObj["continuation_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        parseCompletedGeminiInteractionObject(
                            interactionObj = interactionObj,
                            onFirstToken = onFirstToken,
                            onThinking = { th, sig ->
                                if (th.isNotEmpty()) roundThoughtText.append(th)
                                val uniqueSig = emitUniqueRoundSig(sig)
                                if (th.isNotEmpty() || uniqueSig.isNotEmpty()) {
                                    onThinking(th, uniqueSig)
                                }
                            },
                            onContent = { txt ->
                                receivedAnyStreamContent = true
                                roundModelText.append(txt)
                                onContent(txt)
                            },
                            onMedia = onMedia,
                            onFunctionCall = { callId, fnName, argsStr ->
                                addUniqueToolCall(callId, fnName, argsStr)
                            },
                            onUsage = { id, inTok, cacheTok, thinkTok, outTok ->
                                if (profile.useStatefulChaining && id.isNotBlank()) currentPrevInteractionId = id
                                onUsage(id, inTok, cacheTok, thinkTok, outTok)
                            },
                            onCitation = onCitation
                        )
                    }
                }

                try {
                    // Live SSE Streaming Mode (`stream = true`) with automatic Background Poll Recovery
                    httpClient.preparePost(finalUrl) {
                        applyAuthAndCustomHeaders(profile)
                        header("Accept", "text/event-stream")
                        contentType(ContentType.Application.Json)
                        setBody(requestJsonStr)
                    }.execute { response ->
                        if (!response.status.isSuccess()) {
                            val errText = response.bodyAsText()
                            onRawWire(requestJsonStr, errText)
                            error("Gemini Interactions HTTP ${response.status.value}: $errText")
                        }

                        val channel = response.bodyAsChannel()
                        val pendingFnArgsByIndex = mutableMapOf<Int, Pair<String, Pair<String, StringBuilder>>>()
                        val stepTypeByIndex = mutableMapOf<Int, String>()
                        var fallbackStreamStepType = ""

                        readSseOrJsonFallback(
                            channel = channel,
                            onSseEvent = { eventType, dataLine ->
                                if (dataLine == "[DONE]") return@readSseOrJsonFallback
                                onRawWire(requestJsonStr, dataLine)
                                val evObj = runCatching { json.parseToJsonElement(dataLine).jsonObject }.getOrNull() ?: return@readSseOrJsonFallback
                                val type = evObj["event_type"]?.jsonPrimitive?.contentOrNull
                                    ?: evObj["type"]?.jsonPrimitive?.contentOrNull
                                    ?: eventType

                                // Extract interaction ID if present and stateful chaining is enabled
                                val interactionSub = evObj["interaction"] as? JsonObject
                                val idCandidate = interactionSub?.get("id")?.jsonPrimitive?.contentOrNull
                                    ?: evObj["interaction_id"]?.jsonPrimitive?.contentOrNull
                                    ?: evObj["id"]?.jsonPrimitive?.contentOrNull
                                if (profile.useStatefulChaining && !idCandidate.isNullOrBlank()) {
                                    currentPrevInteractionId = idCandidate
                                }

                                when (type) {
                                    "interaction.start", "interaction.created", "interaction.status_update" -> {
                                        val st = interactionSub?.get("status")?.jsonPrimitive?.contentOrNull
                                            ?: evObj["status"]?.jsonPrimitive?.contentOrNull ?: "streaming"
                                        onStatus("Interaction $st", currentPrevInteractionId)
                                    }
                                    "content.start", "step.start" -> {
                                        val idx = evObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                        val contentObj = (evObj["content"] as? JsonObject) ?: (evObj["step"] as? JsonObject)
                                        val cType = contentObj?.get("type")?.jsonPrimitive?.contentOrNull
                                            ?: evObj["type"]?.jsonPrimitive?.contentOrNull.takeIf { it != "content.start" && it != "step.start" }
                                            ?: ""
                                        if (cType.isNotBlank()) {
                                            stepTypeByIndex[idx] = cType
                                            fallbackStreamStepType = cType
                                        }
                                        if (cType == "function_call") {
                                            val callId = contentObj?.get("id")?.jsonPrimitive?.contentOrNull
                                                ?: contentObj?.get("call_id")?.jsonPrimitive?.contentOrNull
                                                ?: "call_$idx"
                                            val fnName = contentObj?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                                            val argEl = contentObj?.get("arguments") ?: contentObj?.get("arguments_delta")
                                            val initialArgs = when {
                                                argEl is JsonPrimitive -> argEl.content
                                                argEl is JsonObject && argEl.isNotEmpty() -> argEl.toString()
                                                else -> ""
                                            }
                                            pendingFnArgsByIndex[idx] = callId to (fnName to StringBuilder(initialArgs))
                                        } else if (cType == "thought") {
                                            val sig = contentObj?.get("signature")?.jsonPrimitive?.contentOrNull ?: ""
                                            val uniqueSig = emitUniqueRoundSig(sig, idx)
                                            if (uniqueSig.isNotEmpty()) {
                                                onThinking("", uniqueSig)
                                            }
                                            val summaryArr = (contentObj?.get("summary") as? JsonArray)
                                                ?: (contentObj?.get("content") as? JsonArray)
                                            summaryArr?.forEach { sEl ->
                                                val txt = (sEl as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                                                if (txt.isNotEmpty()) {
                                                    onFirstToken()
                                                    roundThoughtText.append(txt)
                                                    onThinking(txt, "")
                                                }
                                            }
                                        } else if (cType == "model_output" || cType == "text") {
                                            val directTxt = contentObj?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                                            if (directTxt.isNotEmpty()) {
                                                onFirstToken()
                                                receivedAnyStreamContent = true
                                                roundModelText.append(directTxt)
                                                onContent(directTxt)
                                            }
                                            val contentArr = contentObj?.get("content") as? JsonArray
                                            contentArr?.forEach { cEl ->
                                                val txt = (cEl as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                                                if (txt.isNotEmpty()) {
                                                    onFirstToken()
                                                    receivedAnyStreamContent = true
                                                    roundModelText.append(txt)
                                                    onContent(txt)
                                                }
                                            }
                                        }
                                        extractCitationsFromGeminiObject(contentObj, onCitation)
                                    }
                                    "content.delta", "step.delta" -> {
                                        val idx = evObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                        val stepTypeForIdx = stepTypeByIndex[idx] ?: fallbackStreamStepType
                                        val deltaObj = evObj["delta"] as? JsonObject ?: evObj
                                        val deltaType = deltaObj["type"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val sigDelta = deltaObj["signature"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val uniqueSig = emitUniqueRoundSig(sigDelta, idx)
                                        if (uniqueSig.isNotEmpty()) {
                                            onThinking("", uniqueSig)
                                        }
                                        val deltaContentArr = deltaObj["content"] as? JsonArray
                                        if (deltaContentArr != null) {
                                            deltaContentArr.forEach { partEl ->
                                                val partObj = partEl as? JsonObject ?: return@forEach
                                                val partType = partObj["type"]?.jsonPrimitive?.contentOrNull ?: "text"
                                                when (partType) {
                                                    "text" -> {
                                                        val txt = partObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                                        if (txt.isNotEmpty()) {
                                                            onFirstToken()
                                                            if (stepTypeForIdx == "thought" || deltaType == "thought_summary") {
                                                                roundThoughtText.append(txt)
                                                                onThinking(txt, "")
                                                            } else {
                                                                receivedAnyStreamContent = true
                                                                roundModelText.append(txt)
                                                                onContent(txt)
                                                            }
                                                        }
                                                    }
                                                    "image" -> {
                                                        val b64 = partObj["data"]?.jsonPrimitive?.contentOrNull ?: ""
                                                        val uri = partObj["uri"]?.jsonPrimitive?.contentOrNull
                                                            ?: partObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                                                        val mime = partObj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                                                        saveAndEmitImage(b64, uri, mime, profile.mediaAspectRatio, "", onMedia)
                                                    }
                                                }
                                            }
                                        } else {
                                            val hasArgsDelta = deltaObj.containsKey("arguments_delta") ||
                                                deltaObj.containsKey("arguments") ||
                                                deltaObj.containsKey("partial_json")
                                            when {
                                                deltaType == "text" -> {
                                                    val txt = deltaObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                                    if (txt.isNotEmpty()) {
                                                        onFirstToken()
                                                        if (stepTypeForIdx == "thought") {
                                                            roundThoughtText.append(txt)
                                                            onThinking(txt, "")
                                                        } else {
                                                            receivedAnyStreamContent = true
                                                            roundModelText.append(txt)
                                                            onContent(txt)
                                                        }
                                                    }
                                                }
                                                deltaType == "thought_summary" || deltaType == "thought" || deltaType == "thinking" -> {
                                                    val thoughtTxt = (deltaObj["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                                                        ?: deltaObj["text"]?.jsonPrimitive?.contentOrNull
                                                        ?: deltaObj["thought"]?.jsonPrimitive?.contentOrNull
                                                        ?: ""
                                                    if (thoughtTxt.isNotEmpty()) {
                                                        onFirstToken()
                                                        roundThoughtText.append(thoughtTxt)
                                                        onThinking(thoughtTxt, "")
                                                    }
                                                }
                                                deltaType == "thought_signature" -> {
                                                    // Already handled via uniqueSig above
                                                }
                                                deltaType == "image" -> {
                                                    val b64 = deltaObj["data"]?.jsonPrimitive?.contentOrNull ?: ""
                                                    val uri = deltaObj["uri"]?.jsonPrimitive?.contentOrNull
                                                        ?: deltaObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                                                    val mime = deltaObj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                                                    saveAndEmitImage(b64, uri, mime, profile.mediaAspectRatio, "", onMedia)
                                                }
                                                deltaType == "function_call" || deltaType == "arguments_delta" ||
                                                    (stepTypeForIdx == "function_call" && hasArgsDelta) -> {
                                                    val callId = deltaObj["id"]?.jsonPrimitive?.contentOrNull
                                                        ?: deltaObj["call_id"]?.jsonPrimitive?.contentOrNull
                                                        ?: pendingFnArgsByIndex[idx]?.first
                                                        ?: "call_$idx"
                                                    val fnName = deltaObj["name"]?.jsonPrimitive?.contentOrNull
                                                        ?: pendingFnArgsByIndex[idx]?.second?.first
                                                        ?: ""
                                                    val rawArgEl = deltaObj["arguments_delta"]
                                                        ?: deltaObj["arguments"]
                                                        ?: deltaObj["partial_json"]
                                                    val existing = pendingFnArgsByIndex[idx]
                                                    when (rawArgEl) {
                                                        is JsonPrimitive -> {
                                                            val argChunk = rawArgEl.content
                                                            if (existing == null) {
                                                                pendingFnArgsByIndex[idx] = callId to (fnName to StringBuilder(argChunk))
                                                            } else {
                                                                val updatedName = fnName.ifBlank { existing.second.first }
                                                                existing.second.second.append(argChunk)
                                                                pendingFnArgsByIndex[idx] = callId to (updatedName to existing.second.second)
                                                            }
                                                        }
                                                        is JsonObject -> {
                                                            val prevObj = existing?.second?.second?.toString()
                                                                ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                                                            val mergedObj = if (prevObj != null) deepMergeJsonObjects(prevObj, rawArgEl) else rawArgEl
                                                            val updatedName = fnName.ifBlank { existing?.second?.first.orEmpty() }
                                                            pendingFnArgsByIndex[idx] = callId to (updatedName to StringBuilder(mergedObj.toString()))
                                                        }
                                                        else -> {
                                                            if (existing == null && (callId.isNotBlank() || fnName.isNotBlank())) {
                                                                pendingFnArgsByIndex[idx] = callId to (fnName to StringBuilder())
                                                            } else if (existing != null && fnName.isNotBlank()) {
                                                                pendingFnArgsByIndex[idx] = callId to (fnName to existing.second.second)
                                                            }
                                                        }
                                                    }
                                                }
                                                else -> {
                                                    val fallbackTxt = deltaObj["text"]?.jsonPrimitive?.contentOrNull
                                                    if (!fallbackTxt.isNullOrEmpty()) {
                                                        onFirstToken()
                                                        if (stepTypeForIdx == "thought") {
                                                            roundThoughtText.append(fallbackTxt)
                                                            onThinking(fallbackTxt, "")
                                                        } else {
                                                            receivedAnyStreamContent = true
                                                            roundModelText.append(fallbackTxt)
                                                            onContent(fallbackTxt)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        extractCitationsFromGeminiObject(deltaObj, onCitation)
                                    }
                                    "content.stop", "step.stop", "content.complete", "step.complete" -> {
                                        val idx = evObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                        val contentObj = (evObj["content"] as? JsonObject) ?: (evObj["step"] as? JsonObject)
                                        if (contentObj?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                                            val callId = contentObj["id"]?.jsonPrimitive?.contentOrNull
                                                ?: contentObj["call_id"]?.jsonPrimitive?.contentOrNull
                                                ?: pendingFnArgsByIndex[idx]?.first
                                                ?: "call_$idx"
                                            val fnName = contentObj["name"]?.jsonPrimitive?.contentOrNull
                                                ?: pendingFnArgsByIndex[idx]?.second?.first
                                                ?: ""
                                            val argEl = contentObj["arguments"]
                                            val argsStr = when {
                                                argEl is JsonPrimitive -> argEl.content
                                                argEl is JsonObject && argEl.isNotEmpty() -> argEl.toString()
                                                else -> pendingFnArgsByIndex[idx]?.second?.second?.toString().orEmpty()
                                            }
                                            if (fnName.isNotBlank()) {
                                                pendingFnArgsByIndex[idx] = callId to (fnName to StringBuilder(argsStr))
                                            }
                                        }
                                        extractCitationsFromGeminiObject(contentObj, onCitation)
                                    }
                                    "interaction.complete", "interaction.completed", "interaction.requires_action" -> {
                                        val completedObj = interactionSub ?: evObj
                                        roundStatus = completedObj["status"]?.jsonPrimitive?.contentOrNull
                                            ?: evObj["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        roundContinuationToken = completedObj["continuation_token"]?.jsonPrimitive?.contentOrNull
                                            ?: evObj["continuation_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        for ((_, pair) in pendingFnArgsByIndex.entries.sortedBy { it.key }) {
                                            addUniqueToolCall(pair.first, pair.second.first, pair.second.second.toString())
                                        }
                                        val itemsArray = (completedObj["steps"] as? JsonArray)
                                            ?: (completedObj["outputs"] as? JsonArray)
                                        itemsArray?.forEach { itemEl ->
                                            val item = itemEl as? JsonObject ?: return@forEach
                                            val itemType = item["type"]?.jsonPrimitive?.contentOrNull
                                            if (itemType == "function_call") {
                                                val callId = item["id"]?.jsonPrimitive?.contentOrNull
                                                    ?: item["call_id"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val name = item["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val args = item["arguments"]?.let {
                                                    if (it is JsonPrimitive) it.content else it.toString()
                                                } ?: "{}"
                                                addUniqueToolCall(callId, name, args)
                                            } else if (itemType == "thought" || itemType == "thought_summary") {
                                                val sig = item["signature"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val uniqueSig = emitUniqueRoundSig(sig)
                                                if (uniqueSig.isNotEmpty()) {
                                                    onThinking("", uniqueSig)
                                                }
                                            }
                                            extractCitationsFromGeminiObject(item, onCitation)
                                        }
                                        extractCitationsFromGeminiObject(completedObj, onCitation)
                                        val usageObj = completedObj["usage"] as? JsonObject
                                        if (usageObj != null) {
                                            val inTok = usageObj["total_input_tokens"]?.jsonPrimitive?.intOrNull
                                                ?: usageObj["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                            val cacheTok = usageObj["total_cached_tokens"]?.jsonPrimitive?.intOrNull
                                                ?: usageObj["cached_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                            val thinkTok = usageObj["total_thought_tokens"]?.jsonPrimitive?.intOrNull
                                                ?: usageObj["total_reasoning_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                            val outTok = usageObj["total_output_tokens"]?.jsonPrimitive?.intOrNull
                                                ?: usageObj["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                            onUsage(currentPrevInteractionId, inTok, cacheTok, thinkTok, outTok)
                                        }
                                    }
                                    "error" -> {
                                        val errObj = evObj["error"] as? JsonObject
                                        val msg = errObj?.get("message")?.jsonPrimitive?.contentOrNull ?: dataLine
                                        error("Gemini Interactions Stream Error: $msg")
                                    }
                                    else -> {
                                        if (evObj.containsKey("outputs") || evObj.containsKey("steps")) {
                                            roundStatus = evObj["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                            roundContinuationToken = evObj["continuation_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                            parseCompletedGeminiInteractionObject(
                                                interactionObj = evObj,
                                                onFirstToken = onFirstToken,
                                                onThinking = { th, sig ->
                                                    if (th.isNotEmpty()) roundThoughtText.append(th)
                                                    val uniqueSig = emitUniqueRoundSig(sig)
                                                    if (th.isNotEmpty() || uniqueSig.isNotEmpty()) {
                                                        onThinking(th, uniqueSig)
                                                    }
                                                },
                                                onContent = { txt ->
                                                    receivedAnyStreamContent = true
                                                    roundModelText.append(txt)
                                                    onContent(txt)
                                                },
                                                onMedia = onMedia,
                                                onFunctionCall = { callId, fnName, argsStr ->
                                                    addUniqueToolCall(callId, fnName, argsStr)
                                                },
                                                onUsage = { id, inTok, cacheTok, thinkTok, outTok ->
                                                    if (profile.useStatefulChaining && id.isNotBlank()) currentPrevInteractionId = id
                                                    onUsage(id, inTok, cacheTok, thinkTok, outTok)
                                                },
                                                onCitation = onCitation
                                            )
                                        }
                                    }
                                }
                            },
                            onPlainJsonFallback = { fullBody ->
                                onRawWire(requestJsonStr, fullBody)
                                val rootObj = json.parseToJsonElement(fullBody).jsonObject
                                val id = rootObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                if (profile.useStatefulChaining && id.isNotBlank()) currentPrevInteractionId = id
                                val st = rootObj["status"]?.jsonPrimitive?.contentOrNull ?: "completed"
                                roundStatus = st
                                roundContinuationToken = rootObj["continuation_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                if ((st == "in_progress" || st == "queued" || st == "processing") && currentPrevInteractionId.isNotBlank()) {
                                    pollInteractionUntilComplete(currentPrevInteractionId)
                                } else {
                                    parseCompletedGeminiInteractionObject(
                                        interactionObj = rootObj,
                                        onFirstToken = onFirstToken,
                                        onThinking = { th, sig ->
                                            if (th.isNotEmpty()) roundThoughtText.append(th)
                                            val uniqueSig = emitUniqueRoundSig(sig)
                                            if (th.isNotEmpty() || uniqueSig.isNotEmpty()) {
                                                onThinking(th, uniqueSig)
                                            }
                                        },
                                        onContent = { txt ->
                                            receivedAnyStreamContent = true
                                            roundModelText.append(txt)
                                            onContent(txt)
                                        },
                                        onMedia = onMedia,
                                        onFunctionCall = { callId, fnName, argsStr ->
                                            addUniqueToolCall(callId, fnName, argsStr)
                                        },
                                        onUsage = { uId, inTok, cacheTok, thinkTok, outTok ->
                                            if (profile.useStatefulChaining && uId.isNotBlank()) currentPrevInteractionId = uId
                                            onUsage(uId, inTok, cacheTok, thinkTok, outTok)
                                        },
                                        onCitation = onCitation
                                    )
                                }
                            }
                        )

                        for ((_, pair) in pendingFnArgsByIndex.entries.sortedBy { it.key }) {
                            addUniqueToolCall(pair.first, pair.second.first, pair.second.second.toString())
                        }
                    }
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val errMsg = (e.message ?: e.toString()).lowercase()
                    if (!receivedAnyStreamContent && errMsg.contains("http 400")) {
                        if (useChainedId) {
                            currentPrevInteractionId = ""
                            currentContinuationToken = ""
                            onStatus("Stateful interaction chain rejected — retrying step with full conversation history…", "")
                            continue
                        }
                        if (!stripSamplingOnRetry && (errMsg.contains("temperature") || errMsg.contains("top_p") || errMsg.contains("sampling"))) {
                            stripSamplingOnRetry = true
                            onStatus("Endpoint rejected sampling parameters — retrying with model defaults…", currentPrevInteractionId)
                            continue
                        }
                        if (!upgradeMinimalToLowOnRetry && profile.thinkingEffort.equals("minimal", ignoreCase = true) &&
                            (errMsg.contains("minimal") || errMsg.contains("thinking_level") || errMsg.contains("thinking"))
                        ) {
                            upgradeMinimalToLowOnRetry = true
                            onStatus("Model does not support minimal thinking_level — stepping up to low…", currentPrevInteractionId)
                            continue
                        }
                        if (!simplifyThinkingOnRetry && (errMsg.contains("thinking") || errMsg.contains("thinking_level"))) {
                            simplifyThinkingOnRetry = true
                            onStatus("Endpoint rejected thinking_level — retrying with automatic thinking…", currentPrevInteractionId)
                            continue
                        }
                        if (!forceImageResponseFormatOnRetry && !omitResponseFormatOnRetry &&
                            (errMsg.contains("only supports image") || (errMsg.contains("response_format") && errMsg.contains("image")))
                        ) {
                            forceImageResponseFormatOnRetry = true
                            recordDiscoveredModality(model, listOf("text", "image"))
                            onStatus("Model outputs images — enabling image response_format…", currentPrevInteractionId)
                            continue
                        }
                        if (!omitResponseFormatOnRetry && (errMsg.contains("response_format") || errMsg.contains("image_size") || errMsg.contains("aspect_ratio"))) {
                            omitResponseFormatOnRetry = true
                            onStatus("Endpoint rejected response_format — retrying without response_format…", currentPrevInteractionId)
                            continue
                        }
                        if (!omitBuiltInToolsOnRetry && (errMsg.contains("google_search") || errMsg.contains("url_context") || errMsg.contains("built-in") || errMsg.contains("multiple tools"))) {
                            omitBuiltInToolsOnRetry = true
                            onStatus("Endpoint rejected mixed built-in + function tools — retrying with workspace tools…", currentPrevInteractionId)
                            continue
                        }
                        if (!foldHistoricalToolsOnRetry && hasHistoricalToolsOrSigs &&
                            (errMsg.contains("couldn't process") || errMsg.contains("invalid_request") || errMsg.contains("thought") ||
                                errMsg.contains("signature") || errMsg.contains("function_call") || errMsg.contains("tool type"))
                        ) {
                            foldHistoricalToolsOnRetry = true
                            onStatus("Endpoint rejected historical tool/signature context — folding prior tool history into compact text & retrying…", currentPrevInteractionId)
                            continue
                        }
                    }
                    if (useChainedId && !receivedAnyStreamContent &&
                        (errMsg.contains("http 404") || errMsg.contains("previous_interaction_id") || errMsg.contains("function response turn") || errMsg.contains("function_result"))
                    ) {
                        currentPrevInteractionId = ""
                        currentContinuationToken = ""
                        onStatus("Stateful interaction chain rejected — retrying step with full conversation history…", "")
                        continue
                    }
                    if (profile.useBackgroundLongDecode && currentPrevInteractionId.isNotBlank() && isTransientOrProxyTimeoutError(e)) {
                        pollInteractionUntilComplete(currentPrevInteractionId)
                        break
                    }
                    if (!receivedAnyStreamContent && turnToolCalls.isEmpty() && isTransientOrProxyTimeoutError(e)) {
                        val maxPrimaryRetries = if (profile.useLegacyFallbackEndpoint) {
                            LEGACY_FALLBACK_PRIMARY_MAX_RETRIES
                        } else {
                            MAX_PRE_TOKEN_RETRIES
                        }
                        val waitSec = if (profile.useLegacyFallbackEndpoint) {
                            LEGACY_FALLBACK_PRIMARY_WAIT_SECONDS
                        } else {
                            RETRY_WAIT_SECONDS
                        }
                        if (stepRetryAttempt < maxPrimaryRetries) {
                            stepRetryAttempt++
                            waitWithRetryCountdown(
                                attempt = stepRetryAttempt,
                                maxAttempts = maxPrimaryRetries,
                                waitSeconds = waitSec,
                                reasonSummary = e.message ?: "Transient API error in step $loopStep",
                                onStatus = { msg -> onStatus(msg, currentPrevInteractionId) }
                            )
                            continue
                        } else {
                            throw StepRetriesExhaustedException(e.message ?: "Retries exhausted", e)
                        }
                    }
                    throw e
                }
            }

            // Official Long-Decode Foreground Continuation Hop (`status == "requires_continuation"`)
            if (roundStatus == "requires_continuation" && roundContinuationToken.isNotBlank() && currentPrevInteractionId.isNotBlank()) {
                currentContinuationToken = roundContinuationToken
                pendingToolResults = emptyList()
                pendingStopHookPrompt = ""
                onStatus("Long Decode Hop completed — continuing via continuation_token…", currentPrevInteractionId)
                continue
            }
            currentContinuationToken = ""

            if (turnToolCalls.isEmpty()) {
                val stopHookPrompt = circuitBreaker.evaluateStopHookPrompt()
                if (stopHookPrompt != null) {
                    pendingToolResults = emptyList()
                    pendingStopHookPrompt = stopHookPrompt
                    val cleanStopSig = sanitizeGeminiThoughtSignature(roundThoughtSig.toString(), forFunctionCall = false)
                    if (cleanStopSig.isNotEmpty()) {
                        extraLoopSteps.add(buildJsonObject {
                            put("type", "thought")
                            put("signature", cleanStopSig)
                            if (roundThoughtText.isNotEmpty()) {
                                putJsonArray("summary") {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", roundThoughtText.toString())
                                    })
                                }
                            }
                        })
                    }
                    if (roundModelText.isNotEmpty()) {
                        extraLoopSteps.add(buildJsonObject {
                            put("type", "model_output")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", roundModelText.toString())
                                })
                            }
                        })
                    }
                    extraLoopSteps.add(buildJsonObject {
                        put("type", "user_input")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", stopHookPrompt)
                            })
                        }
                    })
                    onStatus("Goal Work-Check: continuing incomplete checklist steps…", currentPrevInteractionId)
                    continue
                }
                break
            }
            pendingStopHookPrompt = ""

            // Record thought + model_output + function_call + function_result steps for next round
            val stepSigString = sanitizeGeminiThoughtSignature(roundThoughtSig.toString(), forFunctionCall = true)
            if (stepSigString.isNotEmpty()) {
                extraLoopSteps.add(buildJsonObject {
                    put("type", "thought")
                    put("signature", stepSigString)
                    if (roundThoughtText.isNotEmpty()) {
                        putJsonArray("summary") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", roundThoughtText.toString())
                            })
                        }
                    }
                })
            }
            if (roundModelText.isNotEmpty()) {
                extraLoopSteps.add(buildJsonObject {
                    put("type", "model_output")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", roundModelText.toString())
                        })
                    }
                })
            }

            for ((callId, fnPair) in turnToolCalls) {
                val parsedArgs = runCatching { json.parseToJsonElement(fnPair.second).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
                extraLoopSteps.add(buildJsonObject {
                    put("id", callId)
                    put("type", "function_call")
                    put("name", fnPair.first)
                    put("arguments", parsedArgs)
                })
            }

            val stepThoughtTxt = roundThoughtText.toString().trim()
            val results = executeWorkspaceToolBatchWithLiveProgress(
                workspaceRoot = session.workspacePath,
                mode = session.workspaceMode,
                calls = turnToolCalls.map { (callId, fnPair) -> Triple(callId, fnPair.first, fnPair.second) },
                firstThoughtSignature = stepSigString,
                firstPrecedingThinking = stepThoughtTxt,
                circuitBreaker = circuitBreaker,
                onStatus = { st -> onStatus(st, currentPrevInteractionId) },
                onToolCall = onToolCall,
                onGoalProgress = onGoalProgress
            )
            for (record in results) {
                extraLoopSteps.add(buildJsonObject {
                    put("type", "function_result")
                    put("name", record.name)
                    put("call_id", record.id)
                    put("is_error", record.isError)
                    putJsonObject("result") {
                        put("result", record.resultText)
                    }
                })
            }
            pendingToolResults = results
            val trippedReason = circuitBreaker.checkTripped()
            if (trippedReason != null) {
                onStatus(trippedReason, currentPrevInteractionId)
                break
            }
        }
    }

    private suspend fun parseCompletedGeminiInteractionObject(
        interactionObj: JsonObject,
        onFirstToken: () -> Unit,
        onThinking: suspend (String, String) -> Unit,
        onContent: suspend (String) -> Unit,
        onMedia: suspend (GeneratedMediaItem) -> Unit,
        onFunctionCall: (String, String, String) -> Unit,
        onUsage: (String, Int, Int, Int, Int) -> Unit,
        onCitation: suspend (RawWebCitation) -> Unit = {},
        sessionId: String = ""
    ) {
        val id = interactionObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val itemsArray = (interactionObj["steps"] as? JsonArray)
            ?: (interactionObj["outputs"] as? JsonArray)
        itemsArray?.forEach { itemEl ->
            val item = itemEl as? JsonObject ?: return@forEach
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val txt = item["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (txt.isNotEmpty()) {
                        onFirstToken()
                        onContent(txt)
                    }
                }
                "model_output" -> {
                    val contentArr = item["content"] as? JsonArray
                    contentArr?.forEach { cEl ->
                        val cObj = cEl as? JsonObject ?: return@forEach
                        when (cObj["type"]?.jsonPrimitive?.contentOrNull) {
                            "text", null -> {
                                val txt = cObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                if (txt.isNotEmpty()) {
                                    onFirstToken()
                                    onContent(txt)
                                }
                            }
                            "image" -> {
                                val b64 = cObj["data"]?.jsonPrimitive?.contentOrNull ?: ""
                                val uri = cObj["uri"]?.jsonPrimitive?.contentOrNull
                                    ?: cObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                                val mime = cObj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                                saveAndEmitImage(b64, uri, mime, "", "", onMedia, sessionId = sessionId)
                            }
                        }
                    }
                }
                "image" -> {
                    val b64 = item["data"]?.jsonPrimitive?.contentOrNull ?: ""
                    val uri = item["uri"]?.jsonPrimitive?.contentOrNull
                        ?: item["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    val mime = item["mime_type"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                    saveAndEmitImage(b64, uri, mime, "", "", onMedia, sessionId = sessionId)
                }
                "thought", "thought_summary" -> {
                    val summary = item["summary"]?.let { s ->
                        when (s) {
                            is JsonPrimitive -> s.content
                            is JsonArray -> s.joinToString("\n") {
                                (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: it.toString()
                            }
                            else -> s.toString()
                        }
                    } ?: item["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    val sig = item["signature"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (summary.isNotEmpty() || sig.isNotEmpty()) {
                        onFirstToken()
                        onThinking(summary, sig)
                    }
                }
                "function_call" -> {
                    val callId = item["id"]?.jsonPrimitive?.contentOrNull
                        ?: item["call_id"]?.jsonPrimitive?.contentOrNull
                        ?: "call_${kotlin.random.Random.nextInt(1000, 9999)}"
                    val name = item["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val args = item["arguments"]?.let {
                        if (it is JsonPrimitive) it.content else it.toString()
                    } ?: "{}"
                    if (name.isNotBlank()) {
                        onFunctionCall(callId, name, args)
                    }
                }
            }
            extractCitationsFromGeminiObject(item, onCitation)
        }
        extractCitationsFromGeminiObject(interactionObj, onCitation)

        val usageObj = interactionObj["usage"] as? JsonObject
        val inTok = usageObj?.get("total_input_tokens")?.jsonPrimitive?.intOrNull
            ?: usageObj?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val cacheTok = usageObj?.get("total_cached_tokens")?.jsonPrimitive?.intOrNull
            ?: usageObj?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val thinkTok = usageObj?.get("total_thought_tokens")?.jsonPrimitive?.intOrNull
            ?: usageObj?.get("total_reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val outTok = usageObj?.get("total_output_tokens")?.jsonPrimitive?.intOrNull
            ?: usageObj?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
        onUsage(id, inTok, cacheTok, thinkTok, outTok)
    }

    /**
     * Builds the official Gemini Interactions API `input` Steps array:
     * - User turns -> `{"type": "user_input", "content": [{"type": "text"|"image"|"video"|"audio"|"document", ...}]}`
     *   (automatically separating consecutive `user_input` turns with `\n\n` so prompts never glue together)
     * - Assistant turns -> chronological `thought` (`signature` + `summary`) + `function_call` + `function_result` + `model_output` steps.
     */
    internal fun buildGeminiInteractionsTurnsArray(
        nodes: List<MessageNode>,
        session: ChatSession,
        continuePrefix: String,
        foldHistoricalToolsToText: Boolean = false
    ): JsonArray {
        val stepsList = mutableListOf<JsonObject>()
        val withAuthorsNote = injectAuthorsNoteIntoHistory(nodes, session)
        val lastAssistantIdx = nodes.indexOfLast { it.role == MessageRole.ASSISTANT }

        fun appendUserStep(text: String, attachments: List<ResolvedBinaryAttachment>) {
            val newContentItems = mutableListOf<JsonObject>()
            if (text.isNotBlank()) {
                newContentItems.add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            for (att in attachments) {
                val m = att.mimeType.lowercase()
                val cType = when {
                    m.startsWith("video/") -> "video"
                    m.startsWith("audio/") -> "audio"
                    m == "application/pdf" || m.contains("document") || m.contains("msword") || m.contains("officedocument") || m == "text/csv" -> "document"
                    else -> "image"
                }
                newContentItems.add(buildJsonObject {
                    put("type", cType)
                    put("mime_type", att.mimeType)
                    put("data", att.base64Data)
                })
            }
            if (newContentItems.isEmpty()) {
                newContentItems.add(buildJsonObject {
                    put("type", "text")
                    put("text", " ")
                })
            }

            val lastStep = stepsList.lastOrNull()
            if (lastStep != null && lastStep["type"]?.jsonPrimitive?.contentOrNull == "user_input") {
                val prevContent = (lastStep["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty().toMutableList()
                val existingTextIdx = prevContent.indexOfLast { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                val firstNewItem = newContentItems.firstOrNull()
                if (existingTextIdx >= 0 && firstNewItem != null &&
                    firstNewItem["type"]?.jsonPrimitive?.contentOrNull == "text"
                ) {
                    val prevTxt = prevContent[existingTextIdx]["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val newTxt = firstNewItem["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val joined = if (prevTxt.isBlank()) newTxt else if (newTxt.isBlank()) prevTxt else "$prevTxt\n\n$newTxt"
                    prevContent[existingTextIdx] = buildJsonObject {
                        put("type", "text")
                        put("text", joined)
                    }
                    prevContent.addAll(newContentItems.drop(1))
                } else {
                    prevContent.addAll(newContentItems)
                }
                stepsList[stepsList.lastIndex] = buildJsonObject {
                    put("type", "user_input")
                    put("content", JsonArray(prevContent))
                }
            } else {
                stepsList.add(buildJsonObject {
                    put("type", "user_input")
                    put("content", JsonArray(newContentItems))
                })
            }
        }

        for ((idx, pair) in withAuthorsNote.withIndex()) {
            val (role, text) = pair
            val nodeVar = nodes.getOrNull(idx)?.activeVariant
            val binaryAttachments = resolveBinaryAttachments(nodeVar)

            if (role == MessageRole.ASSISTANT) {
                val cleanText = stripInlineErrorNotices(text)
                val isForeignProtocol = nodeVar?.providerProtocol != null &&
                    nodeVar.providerProtocol != ProviderProtocol.GEMINI_INTERACTIONS
                val rawSig = if (isForeignProtocol) "" else nodeVar?.thinkingSignature.orEmpty()
                val sig = sanitizeGeminiThoughtSignature(rawSig, forFunctionCall = false)
                val thinkSummary = nodeVar?.thinkingSummary.orEmpty()
                val trailingThink = nodeVar?.trailingThinking.orEmpty()
                val toolCalls = nodeVar?.toolCalls.orEmpty()
                val isOlderAssistantTurn = idx < lastAssistantIdx
                var emittedThoughtForTurn = false
                var emittedAnyStepForTurn = false

                if (foldHistoricalToolsToText && toolCalls.isNotEmpty()) {
                    val foldedText = buildFoldedToolHistoryText(toolCalls, thinkSummary, cleanText)
                    stepsList.add(buildJsonObject {
                        put("type", "model_output")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", foldedText)
                            })
                        }
                    })
                } else {
                    if (toolCalls.isEmpty() && sig.isNotBlank()) {
                        stepsList.add(buildJsonObject {
                            put("type", "thought")
                            put("signature", sig)
                            if (thinkSummary.isNotBlank()) {
                                putJsonArray("summary") {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", thinkSummary)
                                    })
                                }
                            }
                        })
                        emittedThoughtForTurn = true
                        emittedAnyStepForTurn = true
                    }

                    if (toolCalls.isNotEmpty()) {
                        val batches = groupToolCallsIntoStepBatches(
                            toolCalls = toolCalls,
                            fallbackTurnSignature = if (isForeignProtocol) "" else rawSig,
                            fallbackTurnThinking = thinkSummary
                        )
                        val totalCalls = toolCalls.size
                        var globalCallIdx = 0
                        for (batch in batches) {
                            val stepSig = if (isForeignProtocol) "" else sanitizeGeminiThoughtSignature(batch.thoughtSignature, forFunctionCall = true)
                            val stepSummary = batch.precedingThinking
                            if (stepSig.isNotBlank()) {
                                stepsList.add(buildJsonObject {
                                    put("type", "thought")
                                    put("signature", stepSig)
                                    if (stepSummary.isNotBlank()) {
                                        putJsonArray("summary") {
                                            add(buildJsonObject {
                                                put("type", "text")
                                                put("text", stepSummary)
                                            })
                                        }
                                    }
                                })
                                emittedThoughtForTurn = true
                                emittedAnyStepForTurn = true
                            }
                            for (tc in batch.calls) {
                                val parsedArgs = runCatching { json.parseToJsonElement(tc.argumentsJson.ifBlank { "{}" }).jsonObject }
                                    .getOrElse { JsonObject(emptyMap()) }
                                val callId = tc.id.ifBlank { "call_${tc.name}" }
                                stepsList.add(buildJsonObject {
                                    put("type", "function_call")
                                    put("id", callId)
                                    put("name", tc.name)
                                    put("arguments", parsedArgs)
                                })
                            }
                            for (tc in batch.calls) {
                                val callId = tc.id.ifBlank { "call_${tc.name}" }
                                val wireResult = compactHistoricalToolResultForWire(
                                    rawResult = tc.resultText,
                                    toolName = tc.name,
                                    isOlderAssistantTurn = isOlderAssistantTurn,
                                    callIndexInTurn = globalCallIdx++,
                                    totalCallsInTurn = totalCalls
                                )
                                stepsList.add(buildJsonObject {
                                    put("type", "function_result")
                                    put("name", tc.name.ifBlank { "tool" })
                                    put("call_id", callId)
                                    put("is_error", tc.isError)
                                    putJsonObject("result") {
                                        put("result", wireResult)
                                    }
                                })
                            }
                            emittedAnyStepForTurn = true
                        }
                    }

                    if (toolCalls.isNotEmpty() && trailingThink.isNotBlank() && sig.isNotBlank() &&
                        toolCalls.none { sanitizeGeminiThoughtSignature(it.thoughtSignature, forFunctionCall = false) == sig }
                    ) {
                        stepsList.add(buildJsonObject {
                            put("type", "thought")
                            put("signature", sig)
                            putJsonArray("summary") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", trailingThink)
                                })
                            }
                        })
                        emittedThoughtForTurn = true
                        emittedAnyStepForTurn = true
                    }
                    val outputText = when {
                        cleanText.isNotBlank() -> cleanText
                        !emittedThoughtForTurn && thinkSummary.isNotBlank() && toolCalls.isEmpty() -> {
                            "[Interrupted during reasoning — partial thoughts:\n$thinkSummary]"
                        }
                        !emittedAnyStepForTurn -> "..."
                        else -> ""
                    }
                    if (outputText.isNotBlank()) {
                        stepsList.add(buildJsonObject {
                            put("type", "model_output")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", outputText)
                                })
                            }
                        })
                    }
                }
            } else {
                appendUserStep(text, binaryAttachments)
            }
        }
        val cleanContinue = stripInlineErrorNotices(continuePrefix)
        if (cleanContinue.isNotBlank()) {
            appendUserStep(
                text = "[System Note: Continue the narrative seamlessly from the exact end of this partial passage without repeating it:\n\"$cleanContinue\"]",
                attachments = emptyList()
            )
        }
        return JsonArray(stepsList)
    }

    // ============================================================================================
    // 1B. GOOGLE GEMINI STREAM GENERATE CONTENT DRIVER (/v1beta/models/{model}:streamGenerateContent)
    //     Implements the exact Gemini 4 Argon / Gemini 3+ / Gemini 2.5 dialect:
    //     - Gemini 4 Argon: `thinkingConfig: { includeThoughts: true, thinkingLevel: "LOW"|"MEDIUM"|"HIGH" }`,
    //       omits `temperature`/`topP` (SamplingPolicy.never), defaults to 65536 maxOutputTokens,
    //       and replays `thoughtSignature` on multi-turn history & tool calls.
    // ============================================================================================
    private suspend fun executeGeminiGenerateContentLoop(
        profile: ProviderProfile,
        model: String,
        session: ChatSession,
        historyNodes: List<MessageNode>,
        continuePrefix: String,
        circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
        onStatus: suspend (String) -> Unit,
        onGoalProgress: suspend (GoalTrackerState) -> Unit,
        onFirstToken: () -> Unit,
        onThinking: suspend (String, String) -> Unit,
        onContent: suspend (String) -> Unit,
        onMedia: suspend (GeneratedMediaItem) -> Unit,
        onToolCall: suspend (ToolCallRecord) -> Unit,
        onRawWire: suspend (String, String) -> Unit,
        onUsage: (Int, Int, Int, Int) -> Unit,
        onCitation: suspend (RawWebCitation) -> Unit = {}
    ) {
        val cleanModel = model.trim().removePrefix("models/")
        val knownModalities = resolveKnownOutputModalities(profile, cleanModel)
        val modelOutputsImages = knownModalities.contains("image")

        val activeNodes = sanitizeContextNodes(historyNodes)
        val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)
        val extraLoopContentTurns = mutableListOf<JsonObject>()
        val hasHistoricalToolsOrSigs = activeNodes.any {
            it.role == MessageRole.ASSISTANT && (it.activeVariant.toolCalls.isNotEmpty() || it.activeVariant.thinkingSignature.isNotBlank())
        }

        val hasBuiltInTools = profile.enableWebSearch || profile.enableCodeExecution || profile.enableUrlContext
        var stripSamplingOnRetry = false
        var upgradeMinimalToLowOnRetry = false
        var useBudgetDialectOnRetry = false
        var simplifyThinkingOnRetry = false
        var disableMixedToolsOnRetry = false
        var forceImageModalityOnRetry = false
        var omitImageModalityOnRetry = false
        var foldHistoricalToolsOnRetry = false
        var loopStep = 0
        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val activeWorkspaceTools = WorkspaceHarness.activeToolsForMode(
                session.workspaceMode,
                session.workspacePath,
                profile.allowShellExecution,
                goalModeActive = circuitBreaker.goalState != null,
                enableWebTools = (profile.enableWebSearch && disableMixedToolsOnRetry) || profile.enableUrlContext,
                includeLocalWebSearchTool = profile.enableWebSearch && disableMixedToolsOnRetry
            )
            var roundModelParts = mutableListOf<JsonObject>()
            val roundToolCalls = mutableListOf<Triple<String, String, String>>() // (callId, name, argsJson)
            val roundThoughtSig = StringBuilder()
            var stepEmittedOutput = false
            var stepRetryAttempt = 0

            while (true) {
                roundModelParts.clear()
                roundToolCalls.clear()
                roundThoughtSig.clear()
                stepEmittedOutput = false

                val payloadObj = buildJsonObject {
                    if (systemInstruction.isNotBlank()) {
                        putJsonObject("systemInstruction") {
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", systemInstruction) })
                            }
                        }
                    }

                    val baseContents = buildGeminiGenerateContentContents(activeNodes, session, continuePrefix, foldHistoricalToolsOnRetry)
                    put("contents", JsonArray(baseContents + extraLoopContentTurns))

                    // Tools & toolConfig (attempts mixed built-in + function tools with VALIDATED, self-healing to AUTO on HTTP 400)
                    val allowMixedTools = !disableMixedToolsOnRetry
                    val toolsArr = buildJsonArray {
                        if (allowMixedTools || activeWorkspaceTools.isEmpty()) {
                            if (profile.enableCodeExecution) add(buildJsonObject { putJsonObject("code_execution") {} })
                            if (profile.enableWebSearch) add(buildJsonObject { putJsonObject("google_search") {} })
                            if (profile.enableUrlContext) add(buildJsonObject { putJsonObject("url_context") {} })
                        }
                        if (activeWorkspaceTools.isNotEmpty() && (allowMixedTools || !hasBuiltInTools)) {
                            add(buildJsonObject {
                                putJsonArray("function_declarations") {
                                    for (t in activeWorkspaceTools) {
                                        add(buildJsonObject {
                                            put("name", t.name)
                                            put("description", t.description)
                                            put("parameters", t.parametersSchema)
                                        })
                                    }
                                }
                            })
                        }
                    }
                    if (toolsArr.isNotEmpty()) {
                        put("tools", toolsArr)
                        if (activeWorkspaceTools.isNotEmpty()) {
                            putJsonObject("toolConfig") {
                                if (allowMixedTools && hasBuiltInTools) {
                                    putJsonObject("function_calling_config") {
                                        put("mode", "VALIDATED")
                                    }
                                    put("includeServerSideToolInvocations", true)
                                } else {
                                    putJsonObject("function_calling_config") {
                                        put("mode", "AUTO")
                                    }
                                }
                            }
                        }
                    }

                    putJsonObject("generationConfig") {
                        if (!stripSamplingOnRetry) {
                            profile.temperature?.let { put("temperature", it) }
                            profile.topP?.let { put("topP", it) }
                        }
                        val maxOut = profile.maxOutputTokens
                        if (maxOut != null && maxOut > 0) {
                            put("maxOutputTokens", maxOut)
                        }
                        if ((modelOutputsImages || forceImageModalityOnRetry) && !omitImageModalityOnRetry) {
                            putJsonArray("responseModalities") {
                                add(JsonPrimitive("TEXT"))
                                add(JsonPrimitive("IMAGE"))
                            }
                        }

                        if (!simplifyThinkingOnRetry) {
                            val rawEffort = profile.thinkingEffort.trim()
                            val lowerEffort = rawEffort.lowercase()
                            val thinkBudget = profile.thinkingBudgetTokens
                            val hideThoughts = lowerEffort == "none" || lowerEffort == "off"
                            if (!useBudgetDialectOnRetry && (thinkBudget == null || thinkBudget <= 0)) {
                                val resolvedLevel = when (lowerEffort) {
                                    "none", "off", "minimal" -> if (upgradeMinimalToLowOnRetry) "LOW" else "MINIMAL"
                                    "low" -> "LOW"
                                    "medium", "default", "auto", "" -> "MEDIUM"
                                    "high", "xhigh", "max" -> "HIGH"
                                    else -> rawEffort.uppercase()
                                }
                                putJsonObject("thinkingConfig") {
                                    put("includeThoughts", !hideThoughts)
                                    put("thinkingLevel", resolvedLevel)
                                }
                            } else {
                                val resolvedBudget = when {
                                    thinkBudget != null && thinkBudget > 0 -> thinkBudget
                                    lowerEffort == "minimal" || lowerEffort == "low" -> 2048
                                    lowerEffort == "high" || lowerEffort == "xhigh" || lowerEffort == "max" -> 16384
                                    else -> 8192
                                }
                                putJsonObject("thinkingConfig") {
                                    put("includeThoughts", !hideThoughts)
                                    if (!hideThoughts) {
                                        put("thinkingBudget", resolvedBudget)
                                    }
                                }
                            }
                        }
                    }
                }

                val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
                val reqStr = json.encodeToString(JsonObject.serializer(), finalPayload)
                val rawUrl = if (profile.endpointPathOverride.contains("interactions", ignoreCase = true) && !profile.useExactEndpointUrl) {
                    val base = profile.baseUrl.trim().trimEnd('#').trimEnd('/')
                    val hasVer = Regex("/(?:api/)?(?:paas/)?v\\d+(?:beta\\d*|alpha\\d*)?$", RegexOption.IGNORE_CASE).containsMatchIn(base)
                    if (hasVer) "$base/models/$cleanModel:streamGenerateContent?alt=sse"
                    else "$base/v1beta/models/$cleanModel:streamGenerateContent?alt=sse"
                } else {
                    resolveSmartEndpointUrl(
                        profile = profile,
                        defaultPathWithV1 = "/v1beta/models/$cleanModel:streamGenerateContent?alt=sse",
                        defaultPathWithoutVersion = "/models/$cleanModel:streamGenerateContent?alt=sse"
                    )
                }
                val url = buildUrlWithAuthQuery(profile, rawUrl)

                onRawWire(reqStr, "Dispatching to $url ...")

                try {
                    httpClient.preparePost(url) {
                        applyAuthAndCustomHeaders(profile)
                        header("Accept", "text/event-stream")
                        contentType(ContentType.Application.Json)
                        setBody(reqStr)
                    }.execute { response ->
                        if (!response.status.isSuccess()) {
                            val err = response.bodyAsText()
                            onRawWire(reqStr, err)
                            error("Gemini streamGenerateContent HTTP ${response.status.value}: $err")
                        }

                        suspend fun processCandidateParts(parts: JsonArray) {
                            for (p in parts) {
                                val partObj = p as? JsonObject ?: continue
                                val isThought = partObj["thought"]?.jsonPrimitive?.contentOrNull == "true"
                                val isServerSideToolPart = partObj.containsKey("toolCall") ||
                                    partObj.containsKey("toolResponse") ||
                                    partObj.containsKey("executableCode") ||
                                    partObj.containsKey("codeExecutionResult")
                                val hasFunctionCall = partObj.containsKey("functionCall")
                                val rawSig = partObj["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                                    ?: partObj["thought_signature"]?.jsonPrimitive?.contentOrNull ?: ""
                                val sig = if (isServerSideToolPart || (!hasFunctionCall && isGeminiServerSideToolSignature(rawSig))) {
                                    ""
                                } else if (hasFunctionCall) {
                                    sanitizeGeminiThoughtSignature(rawSig, forFunctionCall = true)
                                } else {
                                    rawSig
                                }
                                val txt = partObj["text"]?.jsonPrimitive?.contentOrNull ?: ""

                                val uniqueSig = if (sig.isNotEmpty()) {
                                    appendSignatureChunkSafe(roundThoughtSig, sig)
                                } else ""

                                if (!isThought && !isServerSideToolPart) {
                                    val cleanPart = if (rawSig.isNotEmpty()) {
                                        val cleanPartSig = sanitizeGeminiThoughtSignature(rawSig, forFunctionCall = hasFunctionCall)
                                        val m = partObj.toMutableMap()
                                        m.remove("thought_signature")
                                        if (cleanPartSig.isNotEmpty()) {
                                            m["thoughtSignature"] = JsonPrimitive(cleanPartSig)
                                        } else {
                                            m.remove("thoughtSignature")
                                        }
                                        JsonObject(m)
                                    } else partObj
                                    roundModelParts.add(cleanPart)
                                }
                                if (txt.isNotEmpty() || uniqueSig.isNotEmpty()) {
                                    onFirstToken()
                                    if (isThought) {
                                        onThinking(txt, uniqueSig)
                                    } else {
                                        if (uniqueSig.isNotEmpty()) onThinking("", uniqueSig)
                                        if (txt.isNotEmpty()) {
                                            stepEmittedOutput = true
                                            onContent(txt)
                                        }
                                    }
                                }

                                val fcObj = partObj["functionCall"] as? JsonObject
                                if (fcObj != null) {
                                    val callId = fcObj["id"]?.jsonPrimitive?.contentOrNull
                                        ?: "synth_call_${roundToolCalls.size + 1}"
                                    val fnName = fcObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val argsStr = fcObj["args"]?.toString() ?: "{}"
                                    if (fnName.isNotBlank()) {
                                        roundToolCalls.add(Triple(callId, fnName, argsStr))
                                    }
                                }

                                val inlineData = (partObj["inlineData"] as? JsonObject)
                                    ?: (partObj["inline_data"] as? JsonObject)
                                if (inlineData != null) {
                                    val mime = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull
                                        ?: inlineData["mime_type"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                                    val b64 = inlineData["data"]?.jsonPrimitive?.contentOrNull ?: ""
                                    if (b64.isNotBlank()) {
                                        stepEmittedOutput = true
                                        saveAndEmitImage(b64, "", mime, profile.mediaAspectRatio, "", onMedia, sessionId = session.id)
                                    }
                                }
                            }
                        }

                        readSseOrJsonFallback(
                            channel = response.bodyAsChannel(),
                            onSseEvent = { _, dataLine ->
                                if (dataLine == "[DONE]") return@readSseOrJsonFallback
                                onRawWire(reqStr, dataLine)
                                val obj = runCatching { json.parseToJsonElement(dataLine).jsonObject }.getOrNull() ?: return@readSseOrJsonFallback

                                // Check for in-band error object
                                val errObj = obj["error"] as? JsonObject
                                if (errObj != null) {
                                    val msg = errObj["message"]?.jsonPrimitive?.contentOrNull ?: dataLine
                                    error("Gemini Stream Error: $msg")
                                }

                                val candidates = obj["candidates"] as? JsonArray
                                val firstCand = candidates?.firstOrNull() as? JsonObject
                                val parts = (firstCand?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                                if (parts != null) {
                                    processCandidateParts(parts)
                                }
                                extractCitationsFromGeminiCandidate(firstCand, onCitation)

                                val usage = obj["usageMetadata"] as? JsonObject
                                if (usage != null) {
                                    val inTok = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                                    val cacheTok = usage["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                                    val thinkTok = usage["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                                    val outTok = usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                                    onUsage(inTok, cacheTok, thinkTok, outTok)
                                }
                            },
                            onPlainJsonFallback = { body ->
                                onRawWire(reqStr, body)
                                val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@readSseOrJsonFallback
                                val candidates = obj["candidates"] as? JsonArray
                                val firstCand = candidates?.firstOrNull() as? JsonObject
                                val parts = (firstCand?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                                if (parts != null) {
                                    processCandidateParts(parts)
                                }
                                extractCitationsFromGeminiCandidate(firstCand, onCitation)
                            }
                        )
                    }
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val errMsg = (e.message ?: e.toString()).lowercase()
                    if (!stepEmittedOutput && roundToolCalls.isEmpty() && errMsg.contains("http 400")) {
                        if (!stripSamplingOnRetry && (errMsg.contains("temperature") || errMsg.contains("topp") || errMsg.contains("top_p") || errMsg.contains("sampling"))) {
                            stripSamplingOnRetry = true
                            onStatus("Endpoint rejected sampling parameters — retrying with model defaults…")
                            continue
                        }
                        if (!forceImageModalityOnRetry && !omitImageModalityOnRetry &&
                            (errMsg.contains("responsemodalities") || errMsg.contains("response_modalities") || errMsg.contains("image")) &&
                            (errMsg.contains("only supports") || errMsg.contains("must include") || errMsg.contains("modality"))
                        ) {
                            forceImageModalityOnRetry = true
                            recordDiscoveredModality(cleanModel, listOf("text", "image"))
                            onStatus("Model outputs images — enabling TEXT + IMAGE responseModalities…")
                            continue
                        }
                        if (!omitImageModalityOnRetry && (modelOutputsImages || forceImageModalityOnRetry) &&
                            (errMsg.contains("responsemodalities") || errMsg.contains("response_modalities"))
                        ) {
                            omitImageModalityOnRetry = true
                            onStatus("Endpoint rejected responseModalities — retrying with text modality…")
                            continue
                        }
                        if (!disableMixedToolsOnRetry && hasBuiltInTools && activeWorkspaceTools.isNotEmpty() &&
                            (errMsg.contains("validated") || errMsg.contains("includeserversidetoolinvocations") || errMsg.contains("tool") || errMsg.contains("function_declarations") || errMsg.contains("google_search"))
                        ) {
                            disableMixedToolsOnRetry = true
                            onStatus("Endpoint rejected mixed built-in + function tools — retrying with workspace tools…")
                            continue
                        }
                        if (!upgradeMinimalToLowOnRetry && !useBudgetDialectOnRetry &&
                            (profile.thinkingEffort.equals("minimal", ignoreCase = true) || profile.thinkingEffort.equals("none", ignoreCase = true) || profile.thinkingEffort.equals("off", ignoreCase = true)) &&
                            (errMsg.contains("minimal") || errMsg.contains("thinkinglevel") || errMsg.contains("thinking_level"))
                        ) {
                            upgradeMinimalToLowOnRetry = true
                            onStatus("Model rejected MINIMAL thinkingLevel — stepping up to LOW…")
                            continue
                        }
                        if (!useBudgetDialectOnRetry && (errMsg.contains("thinkinglevel") || errMsg.contains("thinking_level"))) {
                            useBudgetDialectOnRetry = true
                            onStatus("Model uses thinkingBudget dialect — switching from thinkingLevel to thinkingBudget…")
                            continue
                        }
                        if (!simplifyThinkingOnRetry && (errMsg.contains("thinking") || errMsg.contains("thinkinglevel") || errMsg.contains("thinkingbudget"))) {
                            simplifyThinkingOnRetry = true
                            onStatus("Endpoint rejected thinkingConfig — retrying with default thinking…")
                            continue
                        }
                        if (!foldHistoricalToolsOnRetry && hasHistoricalToolsOrSigs &&
                            (errMsg.contains("couldn't process") || errMsg.contains("invalid_request") || errMsg.contains("thought") ||
                                errMsg.contains("signature") || errMsg.contains("functioncall") || errMsg.contains("function_call") || errMsg.contains("tool type"))
                        ) {
                            foldHistoricalToolsOnRetry = true
                            onStatus("Endpoint rejected historical tool/signature context — folding prior tool history into compact text & retrying…")
                            continue
                        }
                    }
                    if (!stepEmittedOutput && roundToolCalls.isEmpty() && isTransientOrProxyTimeoutError(e)) {
                        if (stepRetryAttempt < MAX_PRE_TOKEN_RETRIES) {
                            stepRetryAttempt++
                            waitWithRetryCountdown(
                                attempt = stepRetryAttempt,
                                maxAttempts = MAX_PRE_TOKEN_RETRIES,
                                waitSeconds = RETRY_WAIT_SECONDS,
                                reasonSummary = e.message ?: "Transient API error in step $loopStep",
                                onStatus = onStatus
                            )
                            continue
                        } else {
                            throw StepRetriesExhaustedException(e.message ?: "Retries exhausted", e)
                        }
                    }
                    throw e
                }
            }

            if (roundToolCalls.isEmpty()) {
                val stopHookPrompt = circuitBreaker.evaluateStopHookPrompt()
                if (stopHookPrompt != null) {
                    if (roundModelParts.isNotEmpty()) {
                        extraLoopContentTurns.add(buildJsonObject {
                            put("role", "model")
                            put("parts", JsonArray(roundModelParts))
                        })
                    }
                    extraLoopContentTurns.add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", stopHookPrompt) })
                        }
                    })
                    onStatus("Goal Work-Check: continuing incomplete checklist steps…")
                    continue
                }
                break
            }

            // Ensure the first functionCall part has a thoughtSignature (required by Gemini 3+ / 4 even when preceded by text parts!)
            val firstFunctionCallIdx = roundModelParts.indexOfFirst { it.containsKey("functionCall") }
            val cleanRoundSig = sanitizeGeminiThoughtSignature(roundThoughtSig.toString(), forFunctionCall = true)
            val fallbackSig = cleanRoundSig.ifBlank { "skip_thought_signature_validator" }
            val signedModelParts = roundModelParts.mapIndexed { idx, part ->
                if (idx == firstFunctionCallIdx && !part.containsKey("thoughtSignature") && !part.containsKey("thought_signature")) {
                    val mutable = part.toMutableMap()
                    mutable["thoughtSignature"] = JsonPrimitive(fallbackSig)
                    JsonObject(mutable)
                } else part
            }
            extraLoopContentTurns.add(buildJsonObject {
                put("role", "model")
                put("parts", JsonArray(signedModelParts))
            })

            val batchRecords = executeWorkspaceToolBatchWithLiveProgress(
                workspaceRoot = session.workspacePath,
                mode = session.workspaceMode,
                calls = roundToolCalls,
                firstThoughtSignature = cleanRoundSig,
                circuitBreaker = circuitBreaker,
                onStatus = onStatus,
                onToolCall = onToolCall,
                onGoalProgress = onGoalProgress
            )
            val responseParts = batchRecords.map { rec ->
                buildJsonObject {
                    putJsonObject("functionResponse") {
                        put("name", rec.name)
                        if (!rec.id.startsWith("synth_call_")) {
                            put("id", rec.id)
                        }
                        putJsonObject("response") {
                            put("result", rec.resultText)
                        }
                    }
                }
            }
            extraLoopContentTurns.add(buildJsonObject {
                put("role", "user")
                put("parts", JsonArray(responseParts))
            })
            val trippedReason = circuitBreaker.checkTripped()
            if (trippedReason != null) {
                onStatus(trippedReason)
                break
            }
        }
    }

    /**
     * Builds the Google GenerateContent `contents` list (`role: "user"|"model"`, `parts: [...]`),
     * preserving multi-turn `thought` parts, `thoughtSignature`, `functionCall` + `functionResponse` pairs,
     * and separating adjacent same-role text parts with `\n\n` so consecutive prompts never concatenate.
     */
    internal fun buildGeminiGenerateContentContents(
        activeNodes: List<MessageNode>,
        session: ChatSession,
        continuePrefix: String,
        foldHistoricalToolsToText: Boolean = false
    ): MutableList<JsonObject> {
        val turns = injectAuthorsNoteIntoHistory(activeNodes, session)
        val rollingContents = mutableListOf<JsonObject>()
        val lastAssistantIdx = activeNodes.indexOfLast { it.role == MessageRole.ASSISTANT }

        fun appendOrMergeContentTurn(roleStr: String, newParts: List<JsonObject>, allowMergeWithPrevious: Boolean = true) {
            if (newParts.isEmpty()) return
            val last = rollingContents.lastOrNull()
            if (allowMergeWithPrevious && last != null && last["role"]?.jsonPrimitive?.contentOrNull == roleStr) {
                val existingParts = (last["parts"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty().toMutableList()
                val existingHasFunction = existingParts.any { it.containsKey("functionCall") || it.containsKey("functionResponse") }
                val newHasFunction = newParts.any { it.containsKey("functionCall") || it.containsKey("functionResponse") }
                if (existingHasFunction && newHasFunction) {
                    rollingContents.add(buildJsonObject {
                        put("role", roleStr)
                        put("parts", JsonArray(newParts))
                    })
                    return
                }
                val existingTextIdx = existingParts.indexOfLast {
                    it.containsKey("text") &&
                        it["thought"]?.jsonPrimitive?.contentOrNull != "true" &&
                        !it.containsKey("functionCall") &&
                        !it.containsKey("functionResponse") &&
                        !it.containsKey("thoughtSignature") &&
                        !it.containsKey("thought_signature")
                }
                val firstNew = newParts.firstOrNull()
                val firstIsPlainUnsignedText = firstNew != null &&
                    firstNew.containsKey("text") &&
                    firstNew["thought"]?.jsonPrimitive?.contentOrNull != "true" &&
                    !firstNew.containsKey("functionCall") &&
                    !firstNew.containsKey("functionResponse") &&
                    !firstNew.containsKey("thoughtSignature") &&
                    !firstNew.containsKey("thought_signature")

                if (existingTextIdx >= 0 && firstIsPlainUnsignedText) {
                    val targetExisting = existingParts[existingTextIdx]
                    val prevTxt = targetExisting["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val nextTxt = firstNew["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val joined = if (prevTxt.isBlank()) nextTxt else if (nextTxt.isBlank()) prevTxt else "$prevTxt\n\n$nextTxt"
                    val mergedMap = targetExisting.toMutableMap()
                    mergedMap["text"] = JsonPrimitive(joined)
                    existingParts[existingTextIdx] = JsonObject(mergedMap)
                    existingParts.addAll(newParts.drop(1))
                } else {
                    existingParts.addAll(newParts)
                }
                rollingContents[rollingContents.lastIndex] = buildJsonObject {
                    put("role", roleStr)
                    put("parts", JsonArray(existingParts))
                }
            } else {
                rollingContents.add(buildJsonObject {
                    put("role", roleStr)
                    put("parts", JsonArray(newParts))
                })
            }
        }

        for ((idx, pair) in turns.withIndex()) {
            val (role, text) = pair
            val nodeVar = activeNodes.getOrNull(idx)?.activeVariant
            val binaryAttachments = resolveBinaryAttachments(nodeVar)

            if (role == MessageRole.ASSISTANT) {
                val cleanText = stripInlineErrorNotices(text)
                val isForeignProtocol = nodeVar?.providerProtocol != null &&
                    nodeVar.providerProtocol != ProviderProtocol.GEMINI_INTERACTIONS
                val rawSavedSig = if (isForeignProtocol) "" else nodeVar?.thinkingSignature.orEmpty()
                val savedSig = sanitizeGeminiThoughtSignature(rawSavedSig, forFunctionCall = false)
                val thinkSummary = nodeVar?.thinkingSummary.orEmpty()
                val toolCalls = nodeVar?.toolCalls.orEmpty()
                val isOlderAssistantTurn = idx < lastAssistantIdx
                var emittedAny = false

                if (foldHistoricalToolsToText && toolCalls.isNotEmpty()) {
                    val foldedText = buildFoldedToolHistoryText(toolCalls, thinkSummary, cleanText)
                    appendOrMergeContentTurn(
                        "model",
                        listOf(buildJsonObject { put("text", foldedText) })
                    )
                } else {
                    if (toolCalls.isEmpty() && thinkSummary.isNotBlank()) {
                        val thoughtPart = if (savedSig.isNotBlank()) {
                            buildJsonObject {
                                put("thought", true)
                                put("text", thinkSummary)
                                if (cleanText.isBlank()) {
                                    put("thoughtSignature", savedSig)
                                }
                            }
                        } else {
                            buildJsonObject {
                                put("text", "[Completed internal reasoning:\n$thinkSummary]")
                            }
                        }
                        appendOrMergeContentTurn("model", listOf(thoughtPart))
                        emittedAny = true
                    }

                    if (toolCalls.isNotEmpty()) {
                        val batches = groupToolCallsIntoStepBatches(
                            toolCalls = toolCalls,
                            fallbackTurnSignature = rawSavedSig,
                            fallbackTurnThinking = thinkSummary
                        )
                        val totalCalls = toolCalls.size
                        var globalCallIdx = 0
                        for (batch in batches) {
                            val cleanBatchSig = if (isForeignProtocol) "" else sanitizeGeminiThoughtSignature(batch.thoughtSignature, forFunctionCall = true)
                            val effSig = cleanBatchSig.ifBlank { "skip_thought_signature_validator" }
                            val modelStepParts = mutableListOf<JsonObject>()
                            if (batch.precedingThinking.isNotBlank()) {
                                modelStepParts.add(buildJsonObject {
                                    put("thought", true)
                                    put("text", batch.precedingThinking)
                                })
                            }
                            batch.calls.forEachIndexed { tcIdx, tc ->
                                val parsedArgs = runCatching { json.parseToJsonElement(tc.argumentsJson.ifBlank { "{}" }).jsonObject }
                                    .getOrElse { JsonObject(emptyMap()) }
                                modelStepParts.add(buildJsonObject {
                                    putJsonObject("functionCall") {
                                        if (tc.id.isNotBlank() && !tc.id.startsWith("synth_call_")) {
                                            put("id", tc.id)
                                        }
                                        put("name", tc.name)
                                        put("args", parsedArgs)
                                    }
                                    if (tcIdx == 0) {
                                        put("thoughtSignature", effSig)
                                    }
                                })
                            }
                            appendOrMergeContentTurn("model", modelStepParts, allowMergeWithPrevious = modelStepParts.none { it.containsKey("functionCall") })

                            val frParts = batch.calls.map { tc ->
                                val wireResult = compactHistoricalToolResultForWire(
                                    rawResult = tc.resultText,
                                    toolName = tc.name,
                                    isOlderAssistantTurn = isOlderAssistantTurn,
                                    callIndexInTurn = globalCallIdx++,
                                    totalCallsInTurn = totalCalls
                                )
                                buildJsonObject {
                                    putJsonObject("functionResponse") {
                                        put("name", tc.name)
                                        if (tc.id.isNotBlank() && !tc.id.startsWith("synth_call_")) {
                                            put("id", tc.id)
                                        }
                                        putJsonObject("response") {
                                            put("result", wireResult)
                                        }
                                    }
                                }
                            }
                            appendOrMergeContentTurn("user", frParts, allowMergeWithPrevious = false)
                        }
                        emittedAny = true
                    }

                    if (cleanText.isNotBlank() || !emittedAny) {
                        val modelTextPart = buildJsonObject {
                            put("text", cleanText.ifBlank { "..." })
                            if (savedSig.isNotBlank() && toolCalls.isEmpty()) {
                                put("thoughtSignature", savedSig)
                            }
                        }
                        appendOrMergeContentTurn("model", listOf(modelTextPart))
                    }
                }
            } else {
                val turnParts = buildList {
                    if (text.isNotBlank() || binaryAttachments.isEmpty()) {
                        add(buildJsonObject {
                            put("text", text)
                        })
                    }
                    for (att in binaryAttachments) {
                        add(buildJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", att.mimeType)
                                put("data", att.base64Data)
                            }
                        })
                    }
                }
                appendOrMergeContentTurn("user", turnParts)
            }
        }
        val cleanContinue = stripInlineErrorNotices(continuePrefix)
        if (cleanContinue.isNotBlank()) {
            appendOrMergeContentTurn(
                "user",
                listOf(
                    buildJsonObject {
                        put("text", "Continue seamlessly from this exact text without repeating it:\n$cleanContinue")
                    }
                )
            )
        }
        return rollingContents
    }

    // ============================================================================================
    // 2. OPENAI RESPONSES API DRIVER (/v1/responses)
    //    Supports stateful previous_response_id chaining, reasoning effort & summary,
    //    web_search_preview, and Local Workspace Function Tools loop.
    // ============================================================================================
    private suspend fun executeOpenAiResponsesLoop(
        profile: ProviderProfile,
        model: String,
        session: ChatSession,
        historyNodes: List<MessageNode>,
        continuePrefix: String,
        circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
        onStatus: suspend (String) -> Unit,
        onGoalProgress: suspend (GoalTrackerState) -> Unit,
        onFirstToken: () -> Unit,
        onThinking: suspend (String) -> Unit,
        onContent: suspend (String) -> Unit,
        onMedia: suspend (GeneratedMediaItem) -> Unit,
        onToolCall: suspend (ToolCallRecord) -> Unit,
        onRawWire: suspend (String, String) -> Unit,
        onUsage: (String, Int, Int, Int, Int) -> Unit,
        onCitation: suspend (RawWebCitation) -> Unit = {}
    ) {
        val activeNodes = sanitizeContextNodes(historyNodes)
        val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)

        val previousAssistantIdx = activeNodes.indexOfLast { it.role == MessageRole.ASSISTANT }
        var currentPrevResponseId = if (profile.useStatefulChaining && continuePrefix.isBlank() && previousAssistantIdx >= 0) {
            val prevVar = activeNodes[previousAssistantIdx].activeVariant
            if (prevVar.providerProtocol == ProviderProtocol.OPENAI_RESPONSES && prevVar.providerInteractionId.isNotBlank()) {
                prevVar.providerInteractionId
            } else ""
        } else ""

        var pendingToolResults: List<ToolCallRecord> = emptyList()
        var pendingStopHookPrompt: String = ""
        val extraResponsesLoopItems = mutableListOf<JsonObject>()
        var stripSamplingOnRetry = false
        var omitReasoningOnRetry = false
        var useLegacyWebSearchPreviewOnRetry = false
        var omitOpenAiNativeWebSearchOnRetry = false
        var omitEncryptedContentIncludeOnRetry = false
        var omitImageGenToolOnRetry = false
        var foldHistoricalToolsOnRetry = false
        val hasHistoricalToolsOrSigs = activeNodes.any {
            it.role == MessageRole.ASSISTANT && (it.activeVariant.toolCalls.isNotEmpty() || it.activeVariant.thinkingSignature.isNotBlank())
        }
        val knownModalities = resolveKnownOutputModalities(profile, model)
        val modelOutputsImages = knownModalities.contains("image")
        var loopStep = 0
        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val turnToolCalls = mutableListOf<Pair<String, Pair<String, String>>>()
            val roundTextBuilder = StringBuilder()
            val roundReasoningBuilder = StringBuilder()
            var roundEncryptedReasoning = ""
            var stepEmittedOutput = false
            var stepRetryAttempt = 0

            fun addUniqueToolCall(callId: String, fnName: String, argsStr: String) {
                if (fnName.isBlank()) return
                val cleanArgs = argsStr.ifBlank { "{}" }
                val existingIdx = turnToolCalls.indexOfFirst { it.first == callId && callId.isNotBlank() }
                if (existingIdx >= 0) {
                    val prev = turnToolCalls[existingIdx]
                    if ((prev.second.second == "{}" || prev.second.second.isBlank()) && cleanArgs != "{}") {
                        turnToolCalls[existingIdx] = callId to (fnName to cleanArgs)
                    }
                } else {
                    turnToolCalls.add(callId to (fnName to cleanArgs))
                }
            }

            while (true) {
                turnToolCalls.clear()
                roundTextBuilder.clear()
                roundReasoningBuilder.clear()
                roundEncryptedReasoning = ""
                stepEmittedOutput = false
                val useChainedId = profile.useStatefulChaining && currentPrevResponseId.isNotBlank()
                val baseInputItems = if (useChainedId && previousAssistantIdx >= 0 && previousAssistantIdx < activeNodes.size - 1) {
                    val trailing = activeNodes.subList(previousAssistantIdx + 1, activeNodes.size)
                    buildOpenAiResponsesInputArray(trailing, session, continuePrefix, foldHistoricalToolsOnRetry)
                } else {
                    buildOpenAiResponsesInputArray(activeNodes, session, continuePrefix, foldHistoricalToolsOnRetry)
                }

                val compactedLoopItems = compactOpenAiResponsesExtraItems(extraResponsesLoopItems)
                if (compactedLoopItems !== extraResponsesLoopItems) {
                    extraResponsesLoopItems.clear()
                    extraResponsesLoopItems.addAll(compactedLoopItems)
                }

                val payloadObj = buildJsonObject {
                    put("model", model)
                    put("stream", true)
                    put("store", profile.useStatefulChaining)

                    if (systemInstruction.isNotBlank()) {
                        put("instructions", systemInstruction)
                    }

                    if (useChainedId) {
                        put("previous_response_id", currentPrevResponseId)
                    }

                    if (pendingToolResults.isNotEmpty() && useChainedId) {
                        putJsonArray("input") {
                            for (tr in pendingToolResults) {
                                add(buildJsonObject {
                                    put("type", "function_call_output")
                                    put("call_id", tr.id)
                                    put("output", tr.resultText)
                                })
                            }
                        }
                    } else if (pendingStopHookPrompt.isNotBlank() && useChainedId) {
                        putJsonArray("input") {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", pendingStopHookPrompt)
                            })
                        }
                    } else {
                        putJsonArray("input") {
                            baseInputItems.forEach { add(it) }
                            compactedLoopItems.forEach { add(it) }
                        }
                    }

                    val maxOut = profile.maxOutputTokens
                    if (maxOut != null && maxOut > 0) {
                        put("max_output_tokens", maxOut)
                    }

                    val effort = profile.thinkingEffort.trim()
                    val lowerEffort = effort.lowercase()
                    val isReasoningEnabled = !omitReasoningOnRetry && effort.isNotEmpty() &&
                        lowerEffort != "none" && lowerEffort != "off" && lowerEffort != "default"
                    if (isReasoningEnabled) {
                        val normalizedEffort = when (lowerEffort) {
                            "max" -> "high"
                            else -> lowerEffort
                        }
                        putJsonObject("reasoning") {
                            put("effort", normalizedEffort)
                            put("summary", "auto")
                        }
                        if (!useChainedId && !omitEncryptedContentIncludeOnRetry) {
                            putJsonArray("include") {
                                add(JsonPrimitive("reasoning.encrypted_content"))
                            }
                        }
                    } else if (!stripSamplingOnRetry) {
                        profile.temperature?.let { put("temperature", it) }
                        profile.topP?.let { put("top_p", it) }
                    }
                    val toolsArr = buildJsonArray {
                        if (profile.enableWebSearch && !omitOpenAiNativeWebSearchOnRetry) {
                            val wsType = if (useLegacyWebSearchPreviewOnRetry) "web_search_preview" else "web_search"
                            add(buildJsonObject { put("type", wsType) })
                        }
                        if (modelOutputsImages && !omitImageGenToolOnRetry) {
                            add(buildJsonObject {
                                put("type", "image_generation")
                                val size = resolveOpenAiImageSize(profile.mediaAspectRatio, profile.mediaResolution)
                                if (size != "auto") put("size", size)
                            })
                        }
                        val wsTools = WorkspaceHarness.buildOpenAiResponsesTools(
                            session.workspaceMode,
                            session.workspacePath,
                            profile.allowShellExecution,
                            goalModeActive = circuitBreaker.goalState != null,
                            enableWebTools = profile.enableWebSearch || profile.enableUrlContext,
                            includeLocalWebSearchTool = profile.enableWebSearch && omitOpenAiNativeWebSearchOnRetry
                        )
                        wsTools.forEach { add(it) }
                    }
                    if (toolsArr.isNotEmpty()) {
                        put("tools", toolsArr)
                        put("tool_choice", "auto")
                    }
                }

                val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
                val reqStr = json.encodeToString(JsonObject.serializer(), finalPayload)
                val rawUrl = resolveSmartEndpointUrl(
                    profile = profile,
                    defaultPathWithV1 = "/v1/responses",
                    defaultPathWithoutVersion = "/responses"
                )
                val url = buildUrlWithAuthQuery(profile, rawUrl)

                onRawWire(reqStr, "Dispatching to $url ...")

                try {
                    httpClient.preparePost(url) {
                        applyAuthAndCustomHeaders(profile)
                        header("Accept", "text/event-stream")
                        contentType(ContentType.Application.Json)
                        setBody(reqStr)
                    }.execute { response ->
                        if (!response.status.isSuccess()) {
                            val err = response.bodyAsText()
                            onRawWire(reqStr, err)
                            error("OpenAI Responses HTTP ${response.status.value}: $err")
                        }

                        val fnBuildersByItemId = mutableMapOf<String, Pair<String, Pair<String, StringBuilder>>>()

                        readSseOrJsonFallback(
                            channel = response.bodyAsChannel(),
                            onSseEvent = { eventType, dataLine ->
                                if (dataLine == "[DONE]") return@readSseOrJsonFallback
                                onRawWire(reqStr, dataLine)
                                val evObj = runCatching { json.parseToJsonElement(dataLine).jsonObject }.getOrNull() ?: return@readSseOrJsonFallback
                                val type = evObj["type"]?.jsonPrimitive?.contentOrNull ?: eventType

                                when (type) {
                                    "response.created", "response.in_progress" -> {
                                        val respSub = evObj["response"] as? JsonObject
                                        val id = respSub?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
                                        if (profile.useStatefulChaining && id.isNotBlank()) currentPrevResponseId = id
                                    }
                                    "response.output_text.delta" -> {
                                        val delta = evObj["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                                        if (delta.isNotEmpty()) {
                                            onFirstToken()
                                            stepEmittedOutput = true
                                            roundTextBuilder.append(delta)
                                            onContent(delta)
                                        }
                                    }
                                    "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                                        val delta = evObj["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                                        if (delta.isNotEmpty()) {
                                            onFirstToken()
                                            roundReasoningBuilder.append(delta)
                                            onThinking(delta)
                                        }
                                    }
                                    "response.output_text.annotation.added" -> {
                                        val ann = evObj["annotation"] as? JsonObject
                                        if (ann != null) {
                                            extractCitationFromOpenAiAnnotation(ann, onCitation)
                                        }
                                    }
                                    "response.output_item.added" -> {
                                        val item = evObj["item"] as? JsonObject
                                        if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                                            val itemId = item["id"]?.jsonPrimitive?.contentOrNull
                                                ?: item["call_id"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: itemId
                                            val name = item["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val initArgs = item["arguments"]?.jsonPrimitive?.contentOrNull ?: ""
                                            fnBuildersByItemId[itemId] = callId to (name to StringBuilder(initArgs))
                                        }
                                    }
                                    "response.function_call_arguments.delta" -> {
                                        val itemId = evObj["item_id"]?.jsonPrimitive?.contentOrNull
                                            ?: evObj["call_id"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val delta = evObj["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                                        fnBuildersByItemId[itemId]?.second?.second?.append(delta)
                                    }
                                    "response.function_call_arguments.done" -> {
                                        val itemId = evObj["item_id"]?.jsonPrimitive?.contentOrNull
                                            ?: evObj["call_id"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val fullArgs = evObj["arguments"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val existing = fnBuildersByItemId[itemId]
                                        if (existing != null && fullArgs.isNotBlank()) {
                                            val name = evObj["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { existing.second.first } ?: existing.second.first
                                            fnBuildersByItemId[itemId] = existing.first to (name to StringBuilder(fullArgs))
                                        }
                                    }
                                    "response.output_item.done" -> {
                                        val item = evObj["item"] as? JsonObject
                                        val itemType = item?.get("type")?.jsonPrimitive?.contentOrNull
                                        if (itemType == "function_call") {
                                            val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val existing = fnBuildersByItemId[itemId]
                                            val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                                ?: existing?.first
                                                ?: itemId
                                            val name = item["name"]?.jsonPrimitive?.contentOrNull
                                                ?.ifBlank { existing?.second?.first.orEmpty() }
                                                ?: existing?.second?.first.orEmpty()
                                            val args = item["arguments"]?.jsonPrimitive?.contentOrNull
                                                ?.takeIf { it.isNotBlank() }
                                                ?: existing?.second?.second?.toString().orEmpty().ifBlank { "{}" }
                                            addUniqueToolCall(callId, name, args)
                                        } else if (itemType == "image_generation_call") {
                                            val b64 = item["result"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val revised = item["revised_prompt"]?.jsonPrimitive?.contentOrNull ?: ""
                                            if (b64.isNotBlank()) {
                                                stepEmittedOutput = true
                                                saveAndEmitImage(b64, "", "image/png", profile.mediaAspectRatio, revised, onMedia, sessionId = session.id)
                                            }
                                        } else if (itemType == "reasoning") {
                                            val enc = item["encrypted_content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                            if (enc.isNotBlank()) roundEncryptedReasoning = enc
                                        }
                                        if (item != null) {
                                            extractCitationsFromOpenAiResponsesItem(item, onCitation)
                                        }
                                    }
                                    "response.completed" -> {
                                        val respSub = evObj["response"] as? JsonObject
                                        val id = respSub?.get("id")?.jsonPrimitive?.contentOrNull ?: currentPrevResponseId
                                        if (profile.useStatefulChaining && id.isNotBlank()) currentPrevResponseId = id
                                        val outputArr = respSub?.get("output") as? JsonArray
                                        outputArr?.forEach { itemEl ->
                                            val item = itemEl as? JsonObject ?: return@forEach
                                            if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                                                val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                                    ?: item["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val name = item["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val args = item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                                                addUniqueToolCall(callId, name, args)
                                            }
                                            if (item["type"]?.jsonPrimitive?.contentOrNull == "reasoning") {
                                                val enc = item["encrypted_content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                                if (enc.isNotBlank()) roundEncryptedReasoning = enc
                                            }
                                            extractCitationsFromOpenAiResponsesItem(item, onCitation)
                                        }
                                        val usage = respSub?.get("usage") as? JsonObject
                                        if (usage != null) {
                                            val inTok = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                            val cacheTok = (usage["input_tokens_details"] as? JsonObject)
                                                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                            val thinkTok = (usage["output_tokens_details"] as? JsonObject)
                                                ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                            val rawOutTok = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                            val outTok = if (thinkTok in 1..rawOutTok) (rawOutTok - thinkTok) else rawOutTok
                                            onUsage(currentPrevResponseId, inTok, cacheTok, thinkTok, outTok)
                                        }
                                    }
                                    "error" -> {
                                        val msg = (evObj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: dataLine
                                        error("OpenAI Responses Error: $msg")
                                    }
                                }
                            },
                            onPlainJsonFallback = { fullBody ->
                                onRawWire(reqStr, fullBody)
                                val root = json.parseToJsonElement(fullBody).jsonObject
                                val id = root["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                if (profile.useStatefulChaining && id.isNotBlank()) currentPrevResponseId = id
                                val outputArr = root["output"] as? JsonArray
                                outputArr?.forEach { itemEl ->
                                    val item = itemEl as? JsonObject ?: return@forEach
                                    when (item["type"]?.jsonPrimitive?.contentOrNull) {
                                        "message" -> {
                                            val contentArr = item["content"] as? JsonArray
                                            contentArr?.forEach { cEl ->
                                                val cObj = cEl as? JsonObject ?: return@forEach
                                                val txt = cObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                                if (txt.isNotEmpty()) {
                                                    onFirstToken()
                                                    stepEmittedOutput = true
                                                    roundTextBuilder.append(txt)
                                                    onContent(txt)
                                                }
                                            }
                                        }
                                        "function_call" -> {
                                            val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                                ?: item["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val name = item["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val args = item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                                            addUniqueToolCall(callId, name, args)
                                        }
                                        "image_generation_call" -> {
                                            val b64 = item["result"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val revised = item["revised_prompt"]?.jsonPrimitive?.contentOrNull ?: ""
                                            if (b64.isNotBlank()) {
                                                stepEmittedOutput = true
                                                saveAndEmitImage(b64, "", "image/png", profile.mediaAspectRatio, revised, onMedia, sessionId = session.id)
                                            }
                                        }
                                    }
                                    extractCitationsFromOpenAiResponsesItem(item, onCitation)
                                }
                            }
                        )

                        for ((_, pair) in fnBuildersByItemId) {
                            addUniqueToolCall(pair.first, pair.second.first, pair.second.second.toString())
                        }
                    }
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val errMsg = (e.message ?: e.toString()).lowercase()
                    if (!stepEmittedOutput && errMsg.contains("http 400")) {
                        if (useChainedId) {
                            currentPrevResponseId = ""
                            onStatus("Stateful response chain rejected — retrying step with full history…")
                            continue
                        }
                        if (!omitEncryptedContentIncludeOnRetry && (errMsg.contains("encrypted_content") || errMsg.contains("include"))) {
                            omitEncryptedContentIncludeOnRetry = true
                            onStatus("Endpoint rejected reasoning.encrypted_content include — retrying without include…")
                            continue
                        }
                        if (!useLegacyWebSearchPreviewOnRetry && profile.enableWebSearch &&
                            (errMsg.contains("web_search") && !errMsg.contains("web_search_preview"))
                        ) {
                            useLegacyWebSearchPreviewOnRetry = true
                            onStatus("Endpoint uses legacy web_search_preview tool — switching automatically…")
                            continue
                        }
                        if (useLegacyWebSearchPreviewOnRetry && !omitOpenAiNativeWebSearchOnRetry && profile.enableWebSearch &&
                            errMsg.contains("web_search")
                        ) {
                            omitOpenAiNativeWebSearchOnRetry = true
                            onStatus("Endpoint does not support native web search — switching to free local web_search cascade…")
                            continue
                        }
                        if (!omitImageGenToolOnRetry && modelOutputsImages && errMsg.contains("image_generation")) {
                            omitImageGenToolOnRetry = true
                            onStatus("Endpoint rejected image_generation tool — retrying without image_generation tool…")
                            continue
                        }
                        if (!omitReasoningOnRetry && (errMsg.contains("reasoning") || errMsg.contains("effort") || errMsg.contains("summary"))) {
                            omitReasoningOnRetry = true
                            onStatus("Endpoint rejected reasoning parameter — retrying without reasoning config…")
                            continue
                        }
                        if (!stripSamplingOnRetry && (errMsg.contains("temperature") || errMsg.contains("top_p") || errMsg.contains("sampling"))) {
                            stripSamplingOnRetry = true
                            onStatus("Endpoint rejected sampling parameters — retrying with model defaults…")
                            continue
                        }
                        if (!foldHistoricalToolsOnRetry && hasHistoricalToolsOrSigs &&
                            (errMsg.contains("invalid_request") || errMsg.contains("reasoning") || errMsg.contains("encrypted_content") ||
                                errMsg.contains("function_call") || errMsg.contains("call_id"))
                        ) {
                            foldHistoricalToolsOnRetry = true
                            onStatus("Endpoint rejected historical tool/reasoning items — folding prior tool history into compact text & retrying…")
                            continue
                        }
                    }
                    if (useChainedId && !stepEmittedOutput &&
                        (errMsg.contains("http 404") || errMsg.contains("previous_response_id") || errMsg.contains("no tool call found"))
                    ) {
                        currentPrevResponseId = ""
                        onStatus("Stateful response chain rejected — retrying step with full history…")
                        continue
                    }
                    if (!stepEmittedOutput && turnToolCalls.isEmpty() && isTransientOrProxyTimeoutError(e)) {
                        val maxPrimaryRetries = if (profile.useLegacyFallbackEndpoint) {
                            LEGACY_FALLBACK_PRIMARY_MAX_RETRIES
                        } else {
                            MAX_PRE_TOKEN_RETRIES
                        }
                        val waitSec = if (profile.useLegacyFallbackEndpoint) {
                            LEGACY_FALLBACK_PRIMARY_WAIT_SECONDS
                        } else {
                            RETRY_WAIT_SECONDS
                        }
                        if (stepRetryAttempt < maxPrimaryRetries) {
                            stepRetryAttempt++
                            waitWithRetryCountdown(
                                attempt = stepRetryAttempt,
                                maxAttempts = maxPrimaryRetries,
                                waitSeconds = waitSec,
                                reasonSummary = e.message ?: "Transient API error in step $loopStep",
                                onStatus = onStatus
                            )
                            continue
                        } else {
                            throw StepRetriesExhaustedException(e.message ?: "Retries exhausted", e)
                        }
                    }
                    throw e
                }
            }

            if (turnToolCalls.isEmpty()) {
                val stopHookPrompt = circuitBreaker.evaluateStopHookPrompt()
                if (stopHookPrompt != null) {
                    pendingToolResults = emptyList()
                    pendingStopHookPrompt = stopHookPrompt
                    if (roundTextBuilder.isNotEmpty()) {
                        extraResponsesLoopItems.add(buildJsonObject {
                            put("type", "message")
                            put("role", "assistant")
                            put("status", "completed")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "output_text")
                                    put("text", roundTextBuilder.toString())
                                })
                            }
                        })
                    }
                    extraResponsesLoopItems.add(buildJsonObject {
                        put("role", "user")
                        put("content", stopHookPrompt)
                    })
                    onStatus("Goal Work-Check: continuing incomplete checklist steps…")
                    continue
                }
                break
            }
            pendingStopHookPrompt = ""

            if (isValidOpenAiEncryptedReasoning(roundEncryptedReasoning)) {
                extraResponsesLoopItems.add(buildJsonObject {
                    put("type", "reasoning")
                    putJsonArray("summary") {
                        if (roundReasoningBuilder.isNotEmpty()) {
                            add(buildJsonObject {
                                put("type", "summary_text")
                                put("text", roundReasoningBuilder.toString())
                            })
                        }
                    }
                    put("encrypted_content", roundEncryptedReasoning)
                })
            }

            if (roundTextBuilder.isNotEmpty()) {
                extraResponsesLoopItems.add(buildJsonObject {
                    put("type", "message")
                    put("role", "assistant")
                    put("status", "completed")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "output_text")
                            put("text", roundTextBuilder.toString())
                        })
                    }
                })
            }

            for ((callId, fnPair) in turnToolCalls) {
                extraResponsesLoopItems.add(buildJsonObject {
                    put("type", "function_call")
                    put("call_id", callId)
                    put("name", fnPair.first)
                    put("arguments", fnPair.second)
                    put("status", "completed")
                })
            }
            val results = executeWorkspaceToolBatchWithLiveProgress(
                workspaceRoot = session.workspacePath,
                mode = session.workspaceMode,
                calls = turnToolCalls.map { (callId, fnPair) -> Triple(callId, fnPair.first, fnPair.second) },
                firstThoughtSignature = roundEncryptedReasoning,
                firstPrecedingThinking = roundReasoningBuilder.toString().trim(),
                circuitBreaker = circuitBreaker,
                onStatus = onStatus,
                onToolCall = onToolCall,
                onGoalProgress = onGoalProgress
            )
            for (rec in results) {
                extraResponsesLoopItems.add(buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", rec.id)
                    put("output", rec.resultText)
                })
            }
            pendingToolResults = results
            val trippedReason = circuitBreaker.checkTripped()
            if (trippedReason != null) {
                onStatus(trippedReason)
                break
            }
        }
    }

    /**
     * Builds the official OpenAI `/v1/responses` input array:
     * - Assistant turns reconstruct `function_call` + `function_call_output` items and `message` items
     *   (preserving interrupted reasoning summaries when `text` is blank).
     * - User turns use `{"role": "user", "content": ...}` with multimodal `input_text`, `input_image`, and `input_file` (PDF) parts,
     *   coalescing consecutive user turns with `\n\n` separation.
     */
    internal fun buildOpenAiResponsesInputArray(
        nodes: List<MessageNode>,
        session: ChatSession,
        continuePrefix: String,
        foldHistoricalToolsToText: Boolean = false
    ): JsonArray {
        val items = mutableListOf<JsonObject>()
        val withAuthorsNote = injectAuthorsNoteIntoHistory(nodes, session)
        val lastAssistantIdx = nodes.indexOfLast { it.role == MessageRole.ASSISTANT }
        fun appendUserItem(text: String, attachments: List<ResolvedBinaryAttachment>) {
            val last = items.lastOrNull()
            if (attachments.isEmpty() && last != null &&
                last["role"]?.jsonPrimitive?.contentOrNull == "user" &&
                last["content"] is JsonPrimitive
            ) {
                val prevTxt = last["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val joined = if (prevTxt.isBlank()) text else if (text.isBlank()) prevTxt else "$prevTxt\n\n$text"
                items[items.lastIndex] = buildJsonObject {
                    put("role", "user")
                    put("content", joined)
                }
                return
            }

            items.add(buildJsonObject {
                put("role", "user")
                if (attachments.isEmpty()) {
                    put("content", text)
                } else {
                    putJsonArray("content") {
                        if (text.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "input_text")
                                put("text", text)
                            })
                        }
                        for (att in attachments) {
                            val m = att.mimeType.lowercase()
                            if (m == "application/pdf") {
                                add(buildJsonObject {
                                    put("type", "input_file")
                                    put("filename", att.fileName.ifBlank { "document.pdf" })
                                    put("file_data", "data:${att.mimeType};base64,${att.base64Data}")
                                })
                            } else {
                                add(buildJsonObject {
                                    put("type", "input_image")
                                    put("image_url", "data:${att.mimeType};base64,${att.base64Data}")
                                })
                            }
                        }
                    }
                }
            })
        }

        for ((idx, pair) in withAuthorsNote.withIndex()) {
            val (role, text) = pair
            val nodeVar = nodes.getOrNull(idx)?.activeVariant
            val binaryAttachments = resolveBinaryAttachments(nodeVar)

            if (role == MessageRole.ASSISTANT) {
                val cleanText = stripInlineErrorNotices(text)
                val thinkSummary = nodeVar?.thinkingSummary.orEmpty()
                val thinkSig = nodeVar?.thinkingSignature.orEmpty()
                val toolCalls = nodeVar?.toolCalls.orEmpty()
                val isOlderAssistantTurn = idx < lastAssistantIdx
                var emittedAny = false

                if (foldHistoricalToolsToText && toolCalls.isNotEmpty()) {
                    val foldedText = buildFoldedToolHistoryText(toolCalls, thinkSummary, cleanText)
                    items.add(buildJsonObject {
                        put("type", "message")
                        put("role", "assistant")
                        put("status", "completed")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "output_text")
                                put("text", foldedText)
                            })
                        }
                    })
                } else {
                    if (toolCalls.isNotEmpty()) {
                        val batches = groupToolCallsIntoStepBatches(
                            toolCalls = toolCalls,
                            fallbackTurnSignature = thinkSig,
                            fallbackTurnThinking = thinkSummary
                        )
                        val totalCalls = toolCalls.size
                        var globalCallIdx = 0
                        for (batch in batches) {
                            val stepEnc = batch.thoughtSignature.trim()
                            val stepSummary = batch.precedingThinking
                            if (isValidOpenAiEncryptedReasoning(stepEnc, nodeVar?.providerProtocol)) {
                                items.add(buildJsonObject {
                                    put("type", "reasoning")
                                    putJsonArray("summary") {
                                        if (stepSummary.isNotBlank()) {
                                            add(buildJsonObject {
                                                put("type", "summary_text")
                                                put("text", stepSummary)
                                            })
                                        }
                                    }
                                    put("encrypted_content", stepEnc)
                                })
                                emittedAny = true
                            }
                            for (tc in batch.calls) {
                                val callId = tc.id.ifBlank { "call_${tc.name}" }
                                items.add(buildJsonObject {
                                    put("type", "function_call")
                                    put("call_id", callId)
                                    put("name", tc.name)
                                    put("arguments", tc.argumentsJson.ifBlank { "{}" })
                                    put("status", "completed")
                                })
                            }
                            for (tc in batch.calls) {
                                val callId = tc.id.ifBlank { "call_${tc.name}" }
                                val wireOut = compactHistoricalToolResultForWire(
                                    rawResult = tc.resultText,
                                    toolName = tc.name,
                                    isOlderAssistantTurn = isOlderAssistantTurn,
                                    callIndexInTurn = globalCallIdx++,
                                    totalCallsInTurn = totalCalls
                                )
                                items.add(buildJsonObject {
                                    put("type", "function_call_output")
                                    put("call_id", callId)
                                    put("output", wireOut)
                                })
                            }
                            emittedAny = true
                        }
                    }

                    val outputText = when {
                        cleanText.isNotBlank() -> cleanText
                        thinkSummary.isNotBlank() && toolCalls.isEmpty() -> "[Interrupted during reasoning — partial thoughts:\n$thinkSummary]"
                        !emittedAny -> "..."
                        else -> ""
                    }
                    if (outputText.isNotBlank()) {
                        items.add(buildJsonObject {
                            put("type", "message")
                            put("role", "assistant")
                            put("status", "completed")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "output_text")
                                    put("text", outputText)
                                })
                            }
                        })
                    }
                }
            } else {
                appendUserItem(text, binaryAttachments)
            }
        }
        val cleanContinue = stripInlineErrorNotices(continuePrefix)
        if (cleanContinue.isNotBlank()) {
            appendUserItem(
                text = "[Continue the narrative seamlessly from the exact end of this partial passage without repeating it:\n\"$cleanContinue\"]",
                attachments = emptyList()
            )
        }
        return JsonArray(items)
    }

    // ============================================================================================
    // 2B. OPENAI LEGACY FALLBACK (/v1/chat/completions)
    //     For OpenAI-compatible 3rd-party proxies (OpenRouter, DeepSeek, Doubao Ark, vLLM/llama.cpp).
    //     Supports full multi-step tool loops (up to 64 steps) and streaming usage telemetry.
    // ============================================================================================
    private suspend fun executeOpenAiChatCompletionsLoop(
        profile: ProviderProfile,
        model: String,
        session: ChatSession,
        historyNodes: List<MessageNode>,
        continuePrefix: String,
        circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
        onStatus: suspend (String) -> Unit,
        onGoalProgress: suspend (GoalTrackerState) -> Unit,
        onFirstToken: () -> Unit,
        onThinking: suspend (String) -> Unit,
        onContent: suspend (String) -> Unit,
        onMedia: suspend (GeneratedMediaItem) -> Unit,
        onToolCall: suspend (ToolCallRecord) -> Unit,
        onRawWire: suspend (String, String) -> Unit,
        onUsage: (Int, Int, Int, Int) -> Unit,
        onCitation: suspend (RawWebCitation) -> Unit = {}
    ) {
        val activeNodes = sanitizeContextNodes(historyNodes)
        val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)
        val extraToolMessages = mutableListOf<JsonObject>()
        val fullTextAccumulator = StringBuilder()
        var stripSamplingOnRetry = false
        var omitReasoningEffortOnRetry = false
        var maxTokensDialectOverride = "auto" // "auto" | "max_completion_tokens" | "max_tokens" | "omit"
        var omitStreamOptionsOnRetry = false
        var omitModalitiesOnRetry = false
        var foldHistoricalToolsOnRetry = false
        val hasHistoricalTools = activeNodes.any { it.role == MessageRole.ASSISTANT && it.activeVariant.toolCalls.isNotEmpty() }
        val knownModalities = resolveKnownOutputModalities(profile, model)
        val modelOutputsImages = knownModalities.contains("image")
        var loopStep = 0
        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val compactedToolMessages = compactChatCompletionsToolMessages(extraToolMessages)
            val roundContentBuilder = StringBuilder()
            val roundReasoningBuilder = StringBuilder()
            // index -> Triple(id, name, argsBuilder)
            val roundToolCalls = mutableMapOf<Int, Triple<String, String, StringBuilder>>()
            var stepEmittedOutput = false
            var stepRetryAttempt = 0

            while (true) {
                roundContentBuilder.clear()
                roundReasoningBuilder.clear()
                roundToolCalls.clear()
                stepEmittedOutput = false

                val payloadObj = buildJsonObject {
                    put("model", model)
                    put("stream", true)
                    if (!omitStreamOptionsOnRetry) {
                        putJsonObject("stream_options") {
                            put("include_usage", true)
                        }
                    }
                    if (!stripSamplingOnRetry) {
                        profile.temperature?.let { put("temperature", it) }
                        profile.topP?.let { put("top_p", it) }
                    }
                    val effort = profile.thinkingEffort.trim()
                    val lowerEffort = effort.lowercase()
                    val isReasoningRequested = !omitReasoningEffortOnRetry &&
                        effort.isNotEmpty() &&
                        lowerEffort != "none" &&
                        lowerEffort != "off" &&
                        lowerEffort != "default"
                    val maxOut = profile.maxOutputTokens
                    if (maxOut != null && maxOut > 0 && maxTokensDialectOverride != "omit") {
                        val useCompletionTokensKey = when (maxTokensDialectOverride) {
                            "max_completion_tokens" -> true
                            "max_tokens" -> false
                            else -> isReasoningRequested
                        }
                        if (useCompletionTokensKey) {
                            put("max_completion_tokens", maxOut)
                        } else {
                            put("max_tokens", maxOut)
                        }
                    }
                    if (isReasoningRequested) {
                        val normalizedEffort = when (lowerEffort) {
                            "max" -> "high"
                            else -> lowerEffort
                        }
                        put("reasoning_effort", normalizedEffort)
                    }
                    if (modelOutputsImages && !omitModalitiesOnRetry) {
                        putJsonArray("modalities") {
                            add(JsonPrimitive("text"))
                            add(JsonPrimitive("image"))
                        }
                    }
                    put(
                        "messages",
                        buildOpenAiChatCompletionsMessages(
                            activeNodes = activeNodes,
                            session = session,
                            systemInstruction = systemInstruction,
                            continuePrefix = continuePrefix,
                            extraToolMessages = compactedToolMessages,
                            foldHistoricalToolsToText = foldHistoricalToolsOnRetry
                        )
                    )
                    val wsTools = WorkspaceHarness.buildOpenAiChatCompletionsTools(
                        session.workspaceMode,
                        session.workspacePath,
                        profile.allowShellExecution,
                        goalModeActive = circuitBreaker.goalState != null,
                        enableWebTools = profile.enableWebSearch || profile.enableUrlContext
                    )
                    if (wsTools.isNotEmpty()) {
                        put("tools", wsTools)
                        put("tool_choice", "auto")
                    }
                }

                val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
                val reqStr = json.encodeToString(JsonObject.serializer(), finalPayload)
                val rawUrl = resolveSmartEndpointUrl(
                    profile = profile,
                    defaultPathWithV1 = "/v1/chat/completions",
                    defaultPathWithoutVersion = "/chat/completions"
                )
                val url = buildUrlWithAuthQuery(profile, rawUrl)

                onRawWire(reqStr, "Dispatching to $url ...")

                try {
                    httpClient.preparePost(url) {
                        applyAuthAndCustomHeaders(profile)
                        header("Accept", "text/event-stream")
                        contentType(ContentType.Application.Json)
                        setBody(reqStr)
                    }.execute { response ->
                        if (!response.status.isSuccess()) {
                            val err = response.bodyAsText()
                            onRawWire(reqStr, err)
                            error("Chat Completions HTTP ${response.status.value}: $err")
                        }

                        readSseOrJsonFallback(
                            channel = response.bodyAsChannel(),
                            onSseEvent = { _, dataLine ->
                                if (dataLine == "[DONE]") return@readSseOrJsonFallback
                                onRawWire(reqStr, dataLine)
                                val obj = runCatching { json.parseToJsonElement(dataLine).jsonObject }.getOrNull() ?: return@readSseOrJsonFallback
                                val errObj = obj["error"] as? JsonObject
                                if (errObj != null && errObj.isNotEmpty()) {
                                    val msg = errObj["message"]?.jsonPrimitive?.contentOrNull ?: dataLine
                                    error("Chat Completions Stream Error: $msg")
                                }
                                val choices = obj["choices"] as? JsonArray
                                val firstChoice = choices?.firstOrNull() as? JsonObject
                                val delta = (firstChoice?.get("delta") as? JsonObject)
                                    ?: (firstChoice?.get("message") as? JsonObject)
                                if (delta != null) {
                                    val reasoning = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                                        ?: delta["reasoning"]?.jsonPrimitive?.contentOrNull ?: ""
                                    if (reasoning.isNotEmpty()) {
                                        onFirstToken()
                                        roundReasoningBuilder.append(reasoning)
                                        onThinking(reasoning)
                                    }
                                    val content = delta["content"]?.jsonPrimitive?.contentOrNull ?: ""
                                    if (content.isNotEmpty()) {
                                        onFirstToken()
                                        stepEmittedOutput = true
                                        roundContentBuilder.append(content)
                                        fullTextAccumulator.append(content)
                                        onContent(content)
                                    }
                                    val tcArray = delta["tool_calls"] as? JsonArray
                                    tcArray?.forEachIndexed { fallbackIdx, tcEl ->
                                        val tcObj = tcEl as? JsonObject ?: return@forEachIndexed
                                        val idx = tcObj["index"]?.jsonPrimitive?.intOrNull ?: fallbackIdx
                                        val idPart = tcObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val fnObj = tcObj["function"] as? JsonObject
                                        val namePart = fnObj?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                                        val argsPart = fnObj?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""
                                        val existing = roundToolCalls[idx]
                                        val mergedId = if (idPart.isNotBlank()) idPart else (existing?.first ?: "call_$idx")
                                        val mergedName = if (namePart.isNotBlank()) namePart else (existing?.second ?: "")
                                        val sb = existing?.third ?: StringBuilder()
                                        sb.append(argsPart)
                                        roundToolCalls[idx] = Triple(mergedId, mergedName, sb)
                                    }
                                    val imagesArr = delta["images"] as? JsonArray
                                    if (imagesArr != null) {
                                        for (imgEl in imagesArr) {
                                            val imgObj = imgEl as? JsonObject ?: continue
                                            val urlVal = (imgObj["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                                                ?: imgObj["url"]?.jsonPrimitive?.contentOrNull
                                                ?: imgObj["b64_json"]?.jsonPrimitive?.contentOrNull ?: ""
                                            if (urlVal.startsWith("data:image/")) {
                                                val mime = urlVal.substringAfter("data:").substringBefore(";").ifBlank { "image/png" }
                                                val b64 = urlVal.substringAfter("base64,")
                                                stepEmittedOutput = true
                                                saveAndEmitImage(b64, "", mime, profile.mediaAspectRatio, "", onMedia, sessionId = session.id)
                                            } else if (urlVal.startsWith("http")) {
                                                stepEmittedOutput = true
                                                saveAndEmitImage("", urlVal, "image/png", profile.mediaAspectRatio, "", onMedia, sessionId = session.id)
                                            }
                                        }
                                    }
                                    extractCitationsFromChatCompletionsObject(delta, onCitation)
                                }
                                extractCitationsFromChatCompletionsObject(obj, onCitation)
                                val usage = obj["usage"] as? JsonObject
                                if (usage != null) {
                                    val inTok = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                    val cacheTok = (usage["prompt_tokens_details"] as? JsonObject)
                                        ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                    val thinkTok = (usage["completion_tokens_details"] as? JsonObject)
                                        ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                    val rawOutTok = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                    val outTok = if (thinkTok in 1..rawOutTok) (rawOutTok - thinkTok) else rawOutTok
                                    onUsage(inTok, cacheTok, thinkTok, outTok)
                                }
                            },
                            onPlainJsonFallback = { body ->
                                onRawWire(reqStr, body)
                                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@readSseOrJsonFallback
                                val firstMsg = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                                val reasoning = firstMsg?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                                    ?: firstMsg?.get("reasoning")?.jsonPrimitive?.contentOrNull ?: ""
                                if (reasoning.isNotEmpty()) {
                                    onFirstToken()
                                    roundReasoningBuilder.append(reasoning)
                                    onThinking(reasoning)
                                }
                                val txt = firstMsg?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
                                if (txt.isNotEmpty()) {
                                    onFirstToken()
                                    stepEmittedOutput = true
                                    roundContentBuilder.append(txt)
                                    fullTextAccumulator.append(txt)
                                    onContent(txt)
                                }
                                val tcArray = firstMsg?.get("tool_calls") as? JsonArray
                                tcArray?.forEachIndexed { fallbackIdx, tcEl ->
                                    val tcObj = tcEl as? JsonObject ?: return@forEachIndexed
                                    val idx = tcObj["index"]?.jsonPrimitive?.intOrNull ?: fallbackIdx
                                    val idPart = tcObj["id"]?.jsonPrimitive?.contentOrNull ?: "call_$idx"
                                    val fnObj = tcObj["function"] as? JsonObject
                                    val namePart = fnObj?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                                    val argsPart = fnObj?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}"
                                    if (namePart.isNotBlank()) {
                                        roundToolCalls[idx] = Triple(idPart, namePart, StringBuilder(argsPart))
                                    }
                                }
                                if (firstMsg != null) extractCitationsFromChatCompletionsObject(firstMsg, onCitation)
                                extractCitationsFromChatCompletionsObject(root, onCitation)
                            }
                        )
                    }
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val errMsg = (e.message ?: e.toString()).lowercase()
                    if (!stepEmittedOutput && roundToolCalls.isEmpty() && errMsg.contains("http 400")) {
                        if (maxTokensDialectOverride != "omit" && (errMsg.contains("max_tokens") || errMsg.contains("max_completion_tokens"))) {
                            val wasUsingCompletionKey = when (maxTokensDialectOverride) {
                                "max_completion_tokens" -> true
                                "max_tokens" -> false
                                else -> !omitReasoningEffortOnRetry &&
                                    profile.thinkingEffort.trim().lowercase() !in setOf("", "none", "off", "default")
                            }
                            if (maxTokensDialectOverride == "auto") {
                                maxTokensDialectOverride = if (wasUsingCompletionKey) "max_tokens" else "max_completion_tokens"
                                onStatus("Switching token limit parameter to $maxTokensDialectOverride…")
                            } else {
                                maxTokensDialectOverride = "omit"
                                onStatus("Endpoint rejected token cap parameter — retrying with model default limit…")
                            }
                            continue
                        }
                        if (!omitReasoningEffortOnRetry && (errMsg.contains("reasoning_effort") || errMsg.contains("reasoning"))) {
                            omitReasoningEffortOnRetry = true
                            onStatus("Endpoint rejected reasoning_effort — retrying without reasoning_effort…")
                            continue
                        }
                        if (!stripSamplingOnRetry && (errMsg.contains("temperature") || errMsg.contains("top_p") || errMsg.contains("sampling"))) {
                            stripSamplingOnRetry = true
                            onStatus("Endpoint rejected sampling parameters — retrying with model defaults…")
                            continue
                        }
                        if (!omitStreamOptionsOnRetry && errMsg.contains("stream_options")) {
                            omitStreamOptionsOnRetry = true
                            onStatus("Endpoint rejected stream_options — retrying without stream_options…")
                            continue
                        }
                        if (!omitModalitiesOnRetry && modelOutputsImages && errMsg.contains("modalities")) {
                            omitModalitiesOnRetry = true
                            onStatus("Endpoint rejected modalities array — retrying without modalities…")
                            continue
                        }
                        if (!foldHistoricalToolsOnRetry && hasHistoricalTools &&
                            (errMsg.contains("tool_calls") || errMsg.contains("tool_call_id") || errMsg.contains("role 'tool'") || errMsg.contains("invalid_request"))
                        ) {
                            foldHistoricalToolsOnRetry = true
                            onStatus("Endpoint rejected historical tool_calls — folding prior tool history into compact text & retrying…")
                            continue
                        }
                    }
                    if (!stepEmittedOutput && roundToolCalls.isEmpty() && isTransientOrProxyTimeoutError(e)) {
                        if (stepRetryAttempt < MAX_PRE_TOKEN_RETRIES) {
                            stepRetryAttempt++
                            waitWithRetryCountdown(
                                attempt = stepRetryAttempt,
                                maxAttempts = MAX_PRE_TOKEN_RETRIES,
                                waitSeconds = RETRY_WAIT_SECONDS,
                                reasonSummary = e.message ?: "Transient API error in step $loopStep",
                                onStatus = onStatus
                            )
                            continue
                        } else {
                            throw StepRetriesExhaustedException(e.message ?: "Retries exhausted", e)
                        }
                    }
                    throw e
                }
            }
            val validCalls = roundToolCalls.values.filter { it.second.isNotBlank() }
            if (validCalls.isEmpty()) {
                val stopHookPrompt = circuitBreaker.evaluateStopHookPrompt()
                if (stopHookPrompt != null) {
                    if (roundContentBuilder.isNotEmpty()) {
                        extraToolMessages.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", roundContentBuilder.toString())
                        })
                    }
                    extraToolMessages.add(buildJsonObject {
                        put("role", "user")
                        put("content", stopHookPrompt)
                    })
                    onStatus("Goal Work-Check: continuing incomplete checklist steps…")
                    continue
                }
                break
            }

            extraToolMessages.add(buildJsonObject {
                put("role", "assistant")
                if (roundContentBuilder.isNotEmpty()) {
                    put("content", roundContentBuilder.toString())
                }
                if (roundReasoningBuilder.isNotEmpty()) {
                    put("reasoning_content", roundReasoningBuilder.toString())
                }
                putJsonArray("tool_calls") {
                    for ((callId, fnName, argsSb) in validCalls) {
                        add(buildJsonObject {
                            put("id", callId)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", fnName)
                                put("arguments", argsSb.toString().ifBlank { "{}" })
                            }
                        })
                    }
                }
            })

            val batchRecords = executeWorkspaceToolBatchWithLiveProgress(
                workspaceRoot = session.workspacePath,
                mode = session.workspaceMode,
                calls = validCalls.map { (callId, fnName, argsSb) -> Triple(callId, fnName, argsSb.toString().ifBlank { "{}" }) },
                circuitBreaker = circuitBreaker,
                onStatus = onStatus,
                onToolCall = onToolCall,
                onGoalProgress = onGoalProgress
            )
            for (rec in batchRecords) {
                extraToolMessages.add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", rec.id)
                    put("name", rec.name)
                    put("content", rec.resultText)
                })
            }
            val trippedReason = circuitBreaker.checkTripped()
            if (trippedReason != null) {
                onStatus(trippedReason)
                break
            }
        }
        extractMarkdownImagesFromText(fullTextAccumulator.toString(), profile.mediaAspectRatio, onMedia, sessionId = session.id)
    }

    internal fun buildOpenAiChatCompletionsMessages(
        activeNodes: List<MessageNode>,
        session: ChatSession,
        systemInstruction: String,
        continuePrefix: String,
        extraToolMessages: List<JsonObject> = emptyList(),
        foldHistoricalToolsToText: Boolean = false
    ): JsonArray {
        val turns = injectAuthorsNoteIntoHistory(activeNodes, session)
        val messages = mutableListOf<JsonObject>()
        val lastAssistantIdx = activeNodes.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (systemInstruction.isNotBlank()) {
            messages.add(buildJsonObject {
                put("role", "system")
                put("content", systemInstruction)
            })
        }

        fun appendUserMessage(text: String, attachments: List<ResolvedBinaryAttachment>) {
            val last = messages.lastOrNull()
            if (attachments.isEmpty() && last != null &&
                last["role"]?.jsonPrimitive?.contentOrNull == "user" &&
                last["content"] is JsonPrimitive
            ) {
                val prevTxt = last["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val joined = if (prevTxt.isBlank()) text else if (text.isBlank()) prevTxt else "$prevTxt\n\n$text"
                messages[messages.lastIndex] = buildJsonObject {
                    put("role", "user")
                    put("content", joined)
                }
                return
            }
            messages.add(buildJsonObject {
                put("role", "user")
                if (attachments.isEmpty()) {
                    put("content", text)
                } else {
                    putJsonArray("content") {
                        if (text.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            })
                        }
                        for (att in attachments) {
                            add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:${att.mimeType};base64,${att.base64Data}")
                                }
                            })
                        }
                    }
                }
            })
        }

        for ((idx, pair) in turns.withIndex()) {
            val (role, text) = pair
            val nodeVar = activeNodes.getOrNull(idx)?.activeVariant
            val binaryAttachments = resolveBinaryAttachments(nodeVar)

            if (role == MessageRole.ASSISTANT) {
                val cleanText = stripInlineErrorNotices(text)
                val thinkSummary = nodeVar?.thinkingSummary.orEmpty()
                val toolCalls = nodeVar?.toolCalls.orEmpty()
                val isOlderAssistantTurn = idx < lastAssistantIdx
                if (foldHistoricalToolsToText && toolCalls.isNotEmpty()) {
                    val foldedText = buildFoldedToolHistoryText(toolCalls, thinkSummary, cleanText)
                    messages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", foldedText)
                    })
                } else if (toolCalls.isNotEmpty()) {
                    val batches = groupToolCallsIntoStepBatches(
                        toolCalls = toolCalls,
                        fallbackTurnSignature = "",
                        fallbackTurnThinking = thinkSummary
                    )
                    val totalCalls = toolCalls.size
                    var globalCallIdx = 0
                    for (batch in batches) {
                        messages.add(buildJsonObject {
                            put("role", "assistant")
                            if (batch.precedingThinking.isNotBlank()) {
                                put("reasoning_content", batch.precedingThinking)
                            }
                            putJsonArray("tool_calls") {
                                for (tc in batch.calls) {
                                    add(buildJsonObject {
                                        put("id", tc.id.ifBlank { "call_${tc.name}" })
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", tc.name)
                                            put("arguments", tc.argumentsJson.ifBlank { "{}" })
                                        }
                                    })
                                }
                            }
                        })
                        for (tc in batch.calls) {
                            val wireOut = compactHistoricalToolResultForWire(
                                rawResult = tc.resultText,
                                toolName = tc.name,
                                isOlderAssistantTurn = isOlderAssistantTurn,
                                callIndexInTurn = globalCallIdx++,
                                totalCallsInTurn = totalCalls
                            )
                            messages.add(buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", tc.id.ifBlank { "call_${tc.name}" })
                                put("name", tc.name)
                                put("content", wireOut)
                            })
                        }
                    }
                    if (cleanText.isNotBlank()) {
                        messages.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", cleanText)
                        })
                    }
                } else {
                    val resolvedText = when {
                        cleanText.isNotBlank() -> cleanText
                        thinkSummary.isNotBlank() -> "[Interrupted during reasoning — partial thoughts:\n$thinkSummary]"
                        else -> "..."
                    }
                    messages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", resolvedText)
                        if (thinkSummary.isNotBlank() && cleanText.isNotBlank()) {
                            put("reasoning_content", thinkSummary)
                        }
                    })
                }
            } else {
                appendUserMessage(text, binaryAttachments)
            }
        }
        extraToolMessages.forEach { messages.add(it) }
        val cleanContinue = stripInlineErrorNotices(continuePrefix)
        if (cleanContinue.isNotBlank() && extraToolMessages.isEmpty()) {
            appendUserMessage(
                text = "[Continue the narrative seamlessly from the exact end of this partial passage without repeating it:\n\"$cleanContinue\"]",
                attachments = emptyList()
            )
        }
        return JsonArray(messages)
    }
    // ============================================================================================
    // 3. ANTHROPIC MESSAGES API DRIVER (/v1/messages)
    //    Supports Extended Thinking + cryptographic signature preservation,
    //    Automatic Prompt Caching (`cache_control: {"type": "ephemeral"}`),
    //    Native Assistant Prefill (`continuePrefix`), and Local Workspace Function Tools loop.
    // ============================================================================================
    private sealed class AnthropicStreamBlock {
        data class Thinking(
            val thinking: StringBuilder = StringBuilder(),
            val signature: StringBuilder = StringBuilder()
        ) : AnthropicStreamBlock()

        data class RedactedThinking(val data: String) : AnthropicStreamBlock()

        data class Text(val text: StringBuilder = StringBuilder()) : AnthropicStreamBlock()

        data class ToolUse(
            val id: String,
            val name: String,
            val initialInputJson: String = "",
            val streamedInputJson: StringBuilder = StringBuilder()
        ) : AnthropicStreamBlock() {
            fun resolvedArgsJson(): String {
                val streamed = streamedInputJson.toString().trim()
                if (streamed.isNotEmpty()) return streamed
                val init = initialInputJson.trim()
                return if (init.isNotEmpty()) init else "{}"
            }
        }
    }

    private suspend fun executeAnthropicMessagesLoop(
        profile: ProviderProfile,
        model: String,
        session: ChatSession,
        historyNodes: List<MessageNode>,
        continuePrefix: String,
        circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
        onStatus: suspend (String) -> Unit,
        onGoalProgress: suspend (GoalTrackerState) -> Unit,
        onFirstToken: () -> Unit,
        onThinking: suspend (String, String) -> Unit,
        onContent: suspend (String) -> Unit,
        onToolCall: suspend (ToolCallRecord) -> Unit,
        onRawWire: suspend (String, String) -> Unit,
        onUsage: (String, Int, Int, Int) -> Unit,
        onCitation: suspend (RawWebCitation) -> Unit = {}
    ) {
        val activeNodes = sanitizeContextNodes(historyNodes)
        val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)
        var foldHistoricalToolsOnRetry = false
        val hasHistoricalToolsOrSigs = activeNodes.any {
            it.role == MessageRole.ASSISTANT && (it.activeVariant.toolCalls.isNotEmpty() || it.activeVariant.thinkingSignature.isNotBlank())
        }
        // Mutable conversation list for multi-step tool loop
        val extraLoopMessages = mutableListOf<JsonObject>()
        var stripSamplingOnRetry = false
        // "auto" -> adaptive (or budget if thinkingBudgetTokens >= 1024); "adaptive" -> force adaptive; "budget" -> force enabled+budget; "omit" -> omit thinking object
        var thinkingModeOverride = "auto"
        var omitNativeWebSearchOnRetry = false
        var loopStep = 0

        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val streamBlocksByIndex = mutableMapOf<Int, AnthropicStreamBlock>()
            var stepEmittedOutput = false
            var stepRetryAttempt = 0

            while (true) {
                streamBlocksByIndex.clear()
                stepEmittedOutput = false

                val effort = profile.thinkingEffort.trim()
                val lowerEffort = effort.lowercase()
                val thinkBudget = profile.thinkingBudgetTokens ?: 0
                val isThinkingRequested = lowerEffort != "none" && lowerEffort != "off" && thinkingModeOverride != "omit"
                val useBudgetMode = isThinkingRequested && (
                    thinkingModeOverride == "budget" ||
                        (thinkingModeOverride == "auto" && thinkBudget >= 1024)
                    )
                val useAdaptiveMode = isThinkingRequested && !useBudgetMode
                val payloadObj = buildJsonObject {
                    put("model", model)
                    put("stream", true)
                    val userMaxOut = profile.maxOutputTokens
                    val resolvedMaxOut = if (userMaxOut != null && userMaxOut > 0) userMaxOut else 64000
                    val effectiveBudget = if (thinkBudget >= 1024) thinkBudget else 8192
                    val maxTok = if (useBudgetMode) {
                        maxOf(resolvedMaxOut, effectiveBudget + 4096)
                    } else {
                        resolvedMaxOut
                    }
                    put("max_tokens", maxTok)

                    if (useAdaptiveMode) {
                        putJsonObject("thinking") {
                            put("type", "adaptive")
                            put("display", "summarized")
                        }
                        if (effort.isNotEmpty() && lowerEffort != "default" && lowerEffort != "auto") {
                            val normalizedAnthropicEffort = when (lowerEffort) {
                                "minimal" -> "low"
                                "xhigh" -> "max"
                                else -> lowerEffort
                            }
                            putJsonObject("output_config") {
                                put("effort", normalizedAnthropicEffort)
                            }
                        }
                    } else if (useBudgetMode) {
                        putJsonObject("thinking") {
                            put("type", "enabled")
                            put("budget_tokens", effectiveBudget)
                        }
                    } else {
                        if (thinkingModeOverride != "omit" && (lowerEffort == "none" || lowerEffort == "off")) {
                            putJsonObject("thinking") {
                                put("type", "disabled")
                            }
                        }
                        if (!stripSamplingOnRetry) {
                            // Anthropic rejects requests that specify both temperature and top_p; prefer temperature when set
                            if (profile.temperature != null) {
                                put("temperature", profile.temperature)
                            } else if (profile.topP != null) {
                                put("top_p", profile.topP)
                            }
                        }
                    }

                    // System prompt with automatic ephemeral prompt caching breakpoint
                    if (systemInstruction.isNotBlank()) {
                        putJsonArray("system") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", systemInstruction)
                                putJsonObject("cache_control") {
                                    put("type", "ephemeral")
                                }
                            })
                        }
                    }

                    // Messages array with strict user-first / alternating role validation & cache_control breakpoint
                    val coalescedBaseMessages = buildAnthropicBaseMessages(activeNodes, session, continuePrefix, foldHistoricalToolsOnRetry)
                    putJsonArray("messages") {
                        val lastMsgIdx = coalescedBaseMessages.size - 1
                        for ((msgIdx, msgPair) in coalescedBaseMessages.withIndex()) {
                            val (roleStr, blocks) = msgPair
                            add(buildJsonObject {
                                put("role", roleStr)
                                putJsonArray("content") {
                                    val lastBlockIdx = blocks.size - 1
                                    for ((bIdx, bObj) in blocks.withIndex()) {
                                        val blockType = bObj["type"]?.jsonPrimitive?.contentOrNull
                                        val canAttachCache = blockType != "thinking" && blockType != "redacted_thinking"
                                        if (msgIdx == lastMsgIdx && bIdx == lastBlockIdx && extraLoopMessages.isEmpty() && canAttachCache) {
                                            val withCache = bObj.toMutableMap()
                                            withCache["cache_control"] = buildJsonObject { put("type", "ephemeral") }
                                            add(JsonObject(withCache))
                                        } else {
                                            add(bObj)
                                        }
                                    }
                                }
                            })
                        }
                        val compactedAnthropicExtras = compactAnthropicExtraMessages(extraLoopMessages)
                        if (compactedAnthropicExtras !== extraLoopMessages) {
                            extraLoopMessages.clear()
                            extraLoopMessages.addAll(compactedAnthropicExtras)
                        }
                        for (extraMsg in compactedAnthropicExtras) {
                            add(extraMsg)
                        }
                    }

                    val wsTools = WorkspaceHarness.buildAnthropicTools(
                        session.workspaceMode,
                        session.workspacePath,
                        profile.allowShellExecution,
                        goalModeActive = circuitBreaker.goalState != null,
                        enableWebTools = profile.enableWebSearch || profile.enableUrlContext,
                        includeLocalWebSearchTool = profile.enableWebSearch && omitNativeWebSearchOnRetry
                    )
                    val allAnthropicTools = buildJsonArray {
                        if (profile.enableWebSearch && !omitNativeWebSearchOnRetry) {
                            add(buildJsonObject {
                                put("type", "web_search_20250305")
                                put("name", "web_search")
                                put("max_uses", 5)
                            })
                        }
                        wsTools.forEach { add(it) }
                    }
                    if (allAnthropicTools.isNotEmpty()) {
                        put("tools", allAnthropicTools)
                    }
                }

                val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
                val reqStr = json.encodeToString(JsonObject.serializer(), finalPayload)
                val rawUrl = resolveSmartEndpointUrl(
                    profile = profile,
                    defaultPathWithV1 = "/v1/messages",
                    defaultPathWithoutVersion = "/messages"
                )
                val url = buildUrlWithAuthQuery(profile, rawUrl)

                onRawWire(reqStr, "Dispatching to $url ...")

                try {
                    httpClient.preparePost(url) {
                        applyAuthAndCustomHeaders(profile)
                        header("anthropic-version", "2023-06-01")
                        if (profile.anthropicBetaHeaders.isNotBlank()) {
                            header("anthropic-beta", profile.anthropicBetaHeaders.trim())
                        }
                        header("Accept", "text/event-stream")
                        contentType(ContentType.Application.Json)
                        setBody(reqStr)
                    }.execute { response ->
                        if (!response.status.isSuccess()) {
                            val err = response.bodyAsText()
                            onRawWire(reqStr, err)
                            error("Anthropic Messages HTTP ${response.status.value}: $err")
                        }

                        var msgId = ""
                        var inTok = 0
                        var cacheReadTok = 0
                        var outTok = 0

                        readSseOrJsonFallback(
                            channel = response.bodyAsChannel(),
                            onSseEvent = { eventType, dataLine ->
                                onRawWire(reqStr, dataLine)
                                val evObj = runCatching { json.parseToJsonElement(dataLine).jsonObject }.getOrNull() ?: return@readSseOrJsonFallback
                                val type = evObj["type"]?.jsonPrimitive?.contentOrNull ?: eventType

                                when (type) {
                                    "message_start" -> {
                                        val msgObj = evObj["message"] as? JsonObject
                                        msgId = msgObj?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
                                        val usage = msgObj?.get("usage") as? JsonObject
                                        if (usage != null) {
                                            inTok = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                            cacheReadTok = usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                            outTok = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                            onUsage(msgId, inTok, cacheReadTok, outTok)
                                        }
                                    }
                                    "content_block_start" -> {
                                        val idx = evObj["index"]?.jsonPrimitive?.intOrNull ?: streamBlocksByIndex.size
                                        val cb = evObj["content_block"] as? JsonObject
                                        when (cb?.get("type")?.jsonPrimitive?.contentOrNull) {
                                            "thinking" -> {
                                                val initThink = cb["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val initSig = cb["signature"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val block = AnthropicStreamBlock.Thinking()
                                                if (initThink.isNotEmpty()) {
                                                    onFirstToken()
                                                    block.thinking.append(initThink)
                                                    onThinking(initThink, "")
                                                }
                                                if (initSig.isNotEmpty()) {
                                                    block.signature.append(initSig)
                                                    onThinking("", initSig)
                                                }
                                                streamBlocksByIndex[idx] = block
                                            }
                                            "redacted_thinking" -> {
                                                val data = cb["data"]?.jsonPrimitive?.contentOrNull ?: ""
                                                streamBlocksByIndex[idx] = AnthropicStreamBlock.RedactedThinking(data)
                                            }
                                            "text" -> {
                                                val initTxt = cb["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val block = AnthropicStreamBlock.Text()
                                                if (initTxt.isNotEmpty()) {
                                                    onFirstToken()
                                                    stepEmittedOutput = true
                                                    block.text.append(initTxt)
                                                    onContent(initTxt)
                                                }
                                                streamBlocksByIndex[idx] = block
                                            }
                                            "tool_use" -> {
                                                val id = cb["id"]?.jsonPrimitive?.contentOrNull ?: "toolu_$idx"
                                                val name = cb["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val inputObj = cb["input"] as? JsonObject
                                                val initInput = if (inputObj != null && inputObj.isNotEmpty()) inputObj.toString() else ""
                                                streamBlocksByIndex[idx] = AnthropicStreamBlock.ToolUse(
                                                    id = id,
                                                    name = name,
                                                    initialInputJson = initInput
                                                )
                                            }
                                            "web_search_tool_result" -> {
                                                extractCitationsFromAnthropicBlock(cb, onCitation)
                                            }
                                        }
                                        if (cb != null) {
                                            extractCitationsFromAnthropicBlock(cb, onCitation)
                                        }
                                    }
                                    "content_block_delta" -> {
                                        val idx = evObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                        val deltaObj = evObj["delta"] as? JsonObject ?: return@readSseOrJsonFallback
                                        when (deltaObj["type"]?.jsonPrimitive?.contentOrNull) {
                                            "text_delta" -> {
                                                val txt = deltaObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                                if (txt.isNotEmpty()) {
                                                    onFirstToken()
                                                    stepEmittedOutput = true
                                                    val textBlock = (streamBlocksByIndex[idx] as? AnthropicStreamBlock.Text)
                                                        ?: AnthropicStreamBlock.Text().also { streamBlocksByIndex[idx] = it }
                                                    textBlock.text.append(txt)
                                                    onContent(txt)
                                                }
                                            }
                                            "thinking_delta" -> {
                                                val th = deltaObj["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                                                if (th.isNotEmpty()) {
                                                    onFirstToken()
                                                    val thinkBlock = (streamBlocksByIndex[idx] as? AnthropicStreamBlock.Thinking)
                                                        ?: AnthropicStreamBlock.Thinking().also { streamBlocksByIndex[idx] = it }
                                                    thinkBlock.thinking.append(th)
                                                    onThinking(th, "")
                                                }
                                            }
                                            "signature_delta" -> {
                                                val sig = deltaObj["signature"]?.jsonPrimitive?.contentOrNull ?: ""
                                                if (sig.isNotEmpty()) {
                                                    val thinkBlock = (streamBlocksByIndex[idx] as? AnthropicStreamBlock.Thinking)
                                                        ?: AnthropicStreamBlock.Thinking().also { streamBlocksByIndex[idx] = it }
                                                    thinkBlock.signature.append(sig)
                                                    onThinking("", sig)
                                                }
                                            }
                                            "input_json_delta" -> {
                                                val partial = deltaObj["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val toolBlock = streamBlocksByIndex[idx] as? AnthropicStreamBlock.ToolUse
                                                toolBlock?.streamedInputJson?.append(partial)
                                            }
                                            "citations_delta" -> {
                                                val citObj = deltaObj["citation"] as? JsonObject ?: deltaObj
                                                val currentBlockTxt = (streamBlocksByIndex[idx] as? AnthropicStreamBlock.Text)?.text?.toString().orEmpty()
                                                extractCitationFromAnthropicCitationObj(citObj, currentBlockTxt, onCitation)
                                            }
                                        }
                                    }
                                    "message_delta" -> {
                                        val usage = evObj["usage"] as? JsonObject
                                        if (usage != null) {
                                            val dIn = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                            val dCache = usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                            if (dIn > 0) inTok = dIn
                                            if (dCache > 0) cacheReadTok = dCache
                                            outTok = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: outTok
                                            onUsage(msgId, inTok, cacheReadTok, outTok)
                                        }
                                    }
                                    "error" -> {
                                        val errMsg = (evObj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: dataLine
                                        error("Anthropic Stream Error: $errMsg")
                                    }
                                }
                            },
                            onPlainJsonFallback = { body ->
                                onRawWire(reqStr, body)
                                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@readSseOrJsonFallback
                                val id = root["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                val usage = root["usage"] as? JsonObject
                                if (usage != null) {
                                    val uIn = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                    val uCache = usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                    val uOut = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                    onUsage(id, uIn, uCache, uOut)
                                }
                                val contentArr = root["content"] as? JsonArray
                                contentArr?.forEachIndexed { idx, el ->
                                    val cb = el as? JsonObject ?: return@forEachIndexed
                                    when (cb["type"]?.jsonPrimitive?.contentOrNull) {
                                        "thinking" -> {
                                            val th = cb["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val sig = cb["signature"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val block = AnthropicStreamBlock.Thinking()
                                            block.thinking.append(th)
                                            block.signature.append(sig)
                                            streamBlocksByIndex[idx] = block
                                            if (th.isNotEmpty() || sig.isNotEmpty()) {
                                                onFirstToken()
                                                onThinking(th, sig)
                                            }
                                        }
                                        "redacted_thinking" -> {
                                            val data = cb["data"]?.jsonPrimitive?.contentOrNull ?: ""
                                            streamBlocksByIndex[idx] = AnthropicStreamBlock.RedactedThinking(data)
                                        }
                                        "text" -> {
                                            val txt = cb["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val block = AnthropicStreamBlock.Text()
                                            block.text.append(txt)
                                            streamBlocksByIndex[idx] = block
                                            if (txt.isNotEmpty()) {
                                                onFirstToken()
                                                stepEmittedOutput = true
                                                onContent(txt)
                                            }
                                        }
                                        "tool_use" -> {
                                            val callId = cb["id"]?.jsonPrimitive?.contentOrNull ?: "toolu_$idx"
                                            val name = cb["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val inputStr = (cb["input"] as? JsonObject)?.toString() ?: "{}"
                                            streamBlocksByIndex[idx] = AnthropicStreamBlock.ToolUse(
                                                id = callId,
                                                name = name,
                                                initialInputJson = inputStr
                                            )
                                        }
                                    }
                                    extractCitationsFromAnthropicBlock(cb, onCitation)
                                }
                            }
                        )
                    }
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val hasAnyToolUse = streamBlocksByIndex.values.any { it is AnthropicStreamBlock.ToolUse }
                    val errMsg = (e.message ?: e.toString()).lowercase()
                    if (!stepEmittedOutput && !hasAnyToolUse && errMsg.contains("http 400")) {
                        if (!stripSamplingOnRetry && (errMsg.contains("temperature") || errMsg.contains("top_p") || errMsg.contains("top_k") || errMsg.contains("sampling"))) {
                            stripSamplingOnRetry = true
                            onStatus("Endpoint rejected sampling parameters — retrying with model defaults…")
                            continue
                        }
                        if (!omitNativeWebSearchOnRetry && profile.enableWebSearch &&
                            (errMsg.contains("web_search_20250305") || errMsg.contains("web_search"))
                        ) {
                            omitNativeWebSearchOnRetry = true
                            onStatus("Endpoint does not support native web_search_20250305 — falling back to local web_fetch tool…")
                            continue
                        }
                        if (useAdaptiveMode && thinkingModeOverride != "budget" &&
                            (errMsg.contains("adaptive") || errMsg.contains("output_config") || errMsg.contains("effort") || errMsg.contains("display"))
                        ) {
                            thinkingModeOverride = "budget"
                            onStatus("Endpoint does not support adaptive thinking — retrying with budget_tokens thinking…")
                            continue
                        }
                        if (useBudgetMode && thinkingModeOverride != "adaptive" &&
                            (errMsg.contains("budget_tokens") || errMsg.contains("enabled") || errMsg.contains("thinking"))
                        ) {
                            thinkingModeOverride = "adaptive"
                            onStatus("Model uses adaptive thinking — switching from budget_tokens to adaptive thinking…")
                            continue
                        }
                        if (thinkingModeOverride != "omit" && (errMsg.contains("thinking") || errMsg.contains("budget_tokens") || errMsg.contains("output_config") || errMsg.contains("effort"))) {
                            thinkingModeOverride = "omit"
                            onStatus("Endpoint rejected thinking configuration — retrying with default model configuration…")
                            continue
                        }
                        if (!foldHistoricalToolsOnRetry && hasHistoricalToolsOrSigs &&
                            (errMsg.contains("signature") || errMsg.contains("thinking") || errMsg.contains("tool_use") ||
                                errMsg.contains("tool_result") || errMsg.contains("invalid_request"))
                        ) {
                            foldHistoricalToolsOnRetry = true
                            onStatus("Endpoint rejected historical thinking/tool blocks — folding prior tool history into compact text & retrying…")
                            continue
                        }
                    }
                    if (!stepEmittedOutput && !hasAnyToolUse && isTransientOrProxyTimeoutError(e)) {
                        if (stepRetryAttempt < MAX_PRE_TOKEN_RETRIES) {
                            stepRetryAttempt++
                            waitWithRetryCountdown(
                                attempt = stepRetryAttempt,
                                maxAttempts = MAX_PRE_TOKEN_RETRIES,
                                waitSeconds = RETRY_WAIT_SECONDS,
                                reasonSummary = e.message ?: "Transient API error in step $loopStep",
                                onStatus = onStatus
                            )
                            continue
                        } else {
                            throw StepRetriesExhaustedException(e.message ?: "Retries exhausted", e)
                        }
                    }
                    throw e
                }
            }

            val orderedBlocks = streamBlocksByIndex.entries.sortedBy { it.key }.map { it.value }
            val turnToolCalls = mutableListOf<Pair<String, Pair<String, String>>>()
            val assistantContentBlocksForToolLoop = mutableListOf<JsonObject>()

            for (block in orderedBlocks) {
                when (block) {
                    is AnthropicStreamBlock.Thinking -> {
                        if (block.signature.isNotEmpty()) {
                            assistantContentBlocksForToolLoop.add(buildJsonObject {
                                put("type", "thinking")
                                put("thinking", block.thinking.toString())
                                put("signature", block.signature.toString())
                            })
                        }
                    }
                    is AnthropicStreamBlock.RedactedThinking -> {
                        if (block.data.isNotEmpty()) {
                            assistantContentBlocksForToolLoop.add(buildJsonObject {
                                put("type", "redacted_thinking")
                                put("data", block.data)
                            })
                        }
                    }
                    is AnthropicStreamBlock.Text -> {
                        if (block.text.isNotEmpty()) {
                            assistantContentBlocksForToolLoop.add(buildJsonObject {
                                put("type", "text")
                                put("text", block.text.toString())
                            })
                        }
                    }
                    is AnthropicStreamBlock.ToolUse -> {
                        if (block.name.isNotBlank()) {
                            val argsStr = block.resolvedArgsJson()
                            turnToolCalls.add(block.id to (block.name to argsStr))
                            val parsedArgs = runCatching { json.parseToJsonElement(argsStr).jsonObject }
                                .getOrElse { JsonObject(emptyMap()) }
                            assistantContentBlocksForToolLoop.add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", block.id)
                                put("name", block.name)
                                put("input", parsedArgs)
                            })
                        }
                    }
                }
            }

            if (turnToolCalls.isEmpty()) {
                val stopHookPrompt = circuitBreaker.evaluateStopHookPrompt()
                if (stopHookPrompt != null) {
                    if (assistantContentBlocksForToolLoop.isNotEmpty()) {
                        extraLoopMessages.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", JsonArray(assistantContentBlocksForToolLoop))
                        })
                    }
                    extraLoopMessages.add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", stopHookPrompt)
                            })
                        }
                    })
                    onStatus("Goal Work-Check: continuing incomplete checklist steps…")
                    continue
                }
                break
            }

            extraLoopMessages.add(buildJsonObject {
                put("role", "assistant")
                put("content", JsonArray(assistantContentBlocksForToolLoop))
            })

            val firstSignedThinkingBlock = orderedBlocks.filterIsInstance<AnthropicStreamBlock.Thinking>()
                .firstOrNull { it.signature.isNotEmpty() }
            val firstSignedThinking = firstSignedThinkingBlock?.signature?.toString().orEmpty()
            val firstSignedThinkingText = firstSignedThinkingBlock?.thinking?.toString().orEmpty()
            val batchRecords = executeWorkspaceToolBatchWithLiveProgress(
                workspaceRoot = session.workspacePath,
                mode = session.workspaceMode,
                calls = turnToolCalls.map { (callId, fnPair) -> Triple(callId, fnPair.first, fnPair.second) },
                firstThoughtSignature = firstSignedThinking,
                firstPrecedingThinking = firstSignedThinkingText,
                circuitBreaker = circuitBreaker,
                onStatus = onStatus,
                onToolCall = onToolCall,
                onGoalProgress = onGoalProgress
            )
            val toolResultBlocks = batchRecords.map { rec ->
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", rec.id)
                    put("content", rec.resultText)
                    if (rec.isError) put("is_error", true)
                }
            }

            extraLoopMessages.add(buildJsonObject {
                put("role", "user")
                put("content", JsonArray(toolResultBlocks))
            })
            val trippedReason = circuitBreaker.checkTripped()
            if (trippedReason != null) {
                onStatus(trippedReason)
                break
            }
        }
    }

    /**
     * Builds coalesced alternating `user` / `assistant` messages for Anthropic `/v1/messages`,
     * preserving signed `thinking` blocks, `tool_use` + `tool_result` pairs, PDF `document` blocks,
     * and separating adjacent coalesced text blocks with `\n\n`.
     */
    internal fun buildAnthropicBaseMessages(
        activeNodes: List<MessageNode>,
        session: ChatSession,
        continuePrefix: String,
        foldHistoricalToolsToText: Boolean = false
    ): MutableList<Pair<String, MutableList<JsonObject>>> {
        val turnsWithAuthorsNote = injectAuthorsNoteIntoHistory(activeNodes, session)
        val coalescedBaseMessages = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        val lastAssistantIdx = activeNodes.indexOfLast { it.role == MessageRole.ASSISTANT }

        fun appendRoleBlocks(roleStr: String, newBlocks: List<JsonObject>) {
            if (newBlocks.isEmpty()) return
            if (coalescedBaseMessages.isEmpty() && roleStr == "assistant") {
                coalescedBaseMessages.add(
                    "user" to mutableListOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "[Begin narrative]")
                        }
                    )
                )
            }
            val last = coalescedBaseMessages.lastOrNull()
            if (last != null && last.first == roleStr) {
                val prevBlocks = last.second
                val lastPrev = prevBlocks.lastOrNull()
                val firstNew = newBlocks.firstOrNull()
                if (lastPrev != null && firstNew != null &&
                    lastPrev["type"]?.jsonPrimitive?.contentOrNull == "text" &&
                    firstNew["type"]?.jsonPrimitive?.contentOrNull == "text"
                ) {
                    val prevTxt = lastPrev["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val nextTxt = firstNew["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val joined = if (prevTxt.isBlank()) nextTxt else if (nextTxt.isBlank()) prevTxt else "$prevTxt\n\n$nextTxt"
                    prevBlocks[prevBlocks.lastIndex] = buildJsonObject {
                        put("type", "text")
                        put("text", joined)
                    }
                    prevBlocks.addAll(newBlocks.drop(1))
                } else {
                    prevBlocks.addAll(newBlocks)
                }
            } else {
                coalescedBaseMessages.add(roleStr to newBlocks.toMutableList())
            }
        }

        for ((idx, pair) in turnsWithAuthorsNote.withIndex()) {
            val (role, text) = pair
            val nodeVar = activeNodes.getOrNull(idx)?.activeVariant
            val binaryAttachments = resolveBinaryAttachments(nodeVar)

            if (role == MessageRole.ASSISTANT) {
                val cleanText = stripInlineErrorNotices(text)
                val thinkSummary = nodeVar?.thinkingSummary.orEmpty()
                val thinkSig = nodeVar?.thinkingSignature.orEmpty()
                val toolCalls = nodeVar?.toolCalls.orEmpty()
                val isOlderAssistantTurn = idx < lastAssistantIdx

                if (foldHistoricalToolsToText && toolCalls.isNotEmpty()) {
                    val foldedText = buildFoldedToolHistoryText(toolCalls, thinkSummary, cleanText)
                    appendRoleBlocks(
                        "assistant",
                        listOf(
                            buildJsonObject {
                                put("type", "text")
                                put("text", foldedText)
                            }
                        )
                    )
                } else if (toolCalls.isNotEmpty()) {
                    val batches = groupToolCallsIntoStepBatches(
                        toolCalls = toolCalls,
                        fallbackTurnSignature = thinkSig,
                        fallbackTurnThinking = thinkSummary
                    )
                    val totalCalls = toolCalls.size
                    var globalCallIdx = 0
                    for (batch in batches) {
                        val assistantToolBlocks = mutableListOf<JsonObject>()
                        val batchSig = batch.thoughtSignature.trim()
                        val batchThinking = batch.precedingThinking
                        if (batchSig.isNotBlank() &&
                            (batchThinking.isNotBlank() || batches.size == 1) &&
                            isValidAnthropicSignature(batchSig, nodeVar?.providerProtocol)
                        ) {
                            assistantToolBlocks.add(buildJsonObject {
                                put("type", "thinking")
                                put("thinking", batchThinking)
                                put("signature", batchSig)
                            })
                        }
                        for (tc in batch.calls) {
                            val parsedArgs = runCatching { json.parseToJsonElement(tc.argumentsJson.ifBlank { "{}" }).jsonObject }
                                .getOrElse { JsonObject(emptyMap()) }
                            assistantToolBlocks.add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", tc.id.ifBlank { "toolu_${tc.name}" })
                                put("name", tc.name)
                                put("input", parsedArgs)
                            })
                        }
                        appendRoleBlocks("assistant", assistantToolBlocks)

                        val userResultBlocks = batch.calls.map { tc ->
                            val wireOut = compactHistoricalToolResultForWire(
                                rawResult = tc.resultText,
                                toolName = tc.name,
                                isOlderAssistantTurn = isOlderAssistantTurn,
                                callIndexInTurn = globalCallIdx++,
                                totalCallsInTurn = totalCalls
                            )
                            buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", tc.id.ifBlank { "toolu_${tc.name}" })
                                put("content", wireOut)
                                if (tc.isError) put("is_error", true)
                            }
                        }
                        appendRoleBlocks("user", userResultBlocks)
                    }

                    if (cleanText.isNotBlank()) {
                        appendRoleBlocks(
                            "assistant",
                            listOf(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", cleanText)
                                }
                            )
                        )
                    }
                } else {
                    val assistantBlocks = mutableListOf<JsonObject>()
                    if (thinkSig.isNotBlank() &&
                        isValidAnthropicSignature(thinkSig, nodeVar?.providerProtocol)
                    ) {
                        assistantBlocks.add(buildJsonObject {
                            put("type", "thinking")
                            put("thinking", thinkSummary)
                            put("signature", thinkSig)
                        })
                    }
                    val resolvedText = when {
                        cleanText.isNotBlank() -> cleanText
                        thinkSig.isBlank() && thinkSummary.isNotBlank() -> "[Interrupted during thinking — partial thoughts:\n$thinkSummary]"
                        else -> "..."
                    }
                    assistantBlocks.add(buildJsonObject {
                        put("type", "text")
                        put("text", resolvedText)
                    })
                    appendRoleBlocks("assistant", assistantBlocks)
                }
            } else {
                val userBlocks = mutableListOf<JsonObject>()
                for (att in binaryAttachments) {
                    val m = att.mimeType.lowercase()
                    val blockType = if (m == "application/pdf") "document" else "image"
                    userBlocks.add(buildJsonObject {
                        put("type", blockType)
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", att.mimeType)
                            put("data", att.base64Data)
                        }
                    })
                }
                userBlocks.add(buildJsonObject {
                    put("type", "text")
                    put("text", text.ifBlank { " " })
                })
                appendRoleBlocks("user", userBlocks)
            }
        }

        val cleanContinue = stripInlineErrorNotices(continuePrefix)
        if (cleanContinue.isNotBlank()) {
            appendRoleBlocks(
                "user",
                listOf(
                    buildJsonObject {
                        put("type", "text")
                        put(
                            "text",
                            "[System Note: Continue the narrative seamlessly from the exact end of this partial passage without repeating it:\n\"$cleanContinue\"]"
                        )
                    }
                )
            )
        } else if (coalescedBaseMessages.lastOrNull()?.first == "assistant") {
            appendRoleBlocks(
                "user",
                listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "[Continue.]")
                    }
                )
            )
        }
        return coalescedBaseMessages
    }
    // ============================================================================================
    // 4. OPENAI DIRECT IMAGE GENERATION ENDPOINT (/v1/images/generations)
    //    For standalone GPT Image / DALL-E models and OpenAI-compatible image proxies.
    // ============================================================================================
    private suspend fun executeOpenAiDirectImagesEndpoint(
        profile: ProviderProfile,
        model: String,
        session: ChatSession,
        historyNodes: List<MessageNode>,
        circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
        onStatus: suspend (String) -> Unit,
        onFirstToken: () -> Unit,
        onThinking: suspend (String) -> Unit,
        onMedia: suspend (GeneratedMediaItem) -> Unit,
        onContent: suspend (String) -> Unit,
        onRawWire: suspend (String, String) -> Unit
    ) {
        val latestUserPrompt = historyNodes.lastOrNull { it.role == MessageRole.USER }?.activeVariant?.content.orEmpty()
            .ifBlank { historyNodes.lastOrNull()?.activeVariant?.content.orEmpty() }
        require(latestUserPrompt.isNotBlank()) { "Image generation prompt cannot be empty." }

        val size = resolveOpenAiImageSize(profile.mediaAspectRatio, profile.mediaResolution)
        val payloadObj = buildJsonObject {
            put("model", model)
            put("prompt", latestUserPrompt)
            if (size != "auto") {
                put("size", size)
            }
        }

        val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
        val reqStr = json.encodeToString(JsonObject.serializer(), finalPayload)
        val base = profile.baseUrl.trimEnd('/')
        val defaultImgPath = if (base.endsWith("/v1") || base.endsWith("/api/v3")) "/images/generations" else "/v1/images/generations"
        val path = profile.endpointPathOverride.takeIf { it.contains("images", ignoreCase = true) }
            ?: defaultImgPath
        val url = buildUrlWithAuthQuery(profile, "$base$path")

        onStatus("Generating image via $model…")
        onRawWire(reqStr, "Dispatching to $url ...")

        val resp = httpClient.post(url) {
            applyAuthAndCustomHeaders(profile)
            contentType(ContentType.Application.Json)
            setBody(reqStr)
        }
        val bodyText = resp.bodyAsText()
        val statusVal = resp.status.value
        onRawWire(reqStr, bodyText)
        if (statusVal == 404 || statusVal == 405 || statusVal == 501 ||
            (statusVal == 400 && (bodyText.contains("chat", ignoreCase = true) || bodyText.contains("messages", ignoreCase = true)))
        ) {
            onStatus("Proxy serves $model via /v1/chat/completions — routing automatically…")
            executeOpenAiChatCompletionsLoop(
                profile = profile,
                model = model,
                session = session,
                historyNodes = historyNodes,
                continuePrefix = "",
                circuitBreaker = circuitBreaker,
                onStatus = onStatus,
                onGoalProgress = {},
                onFirstToken = onFirstToken,
                onThinking = onThinking,
                onContent = onContent,
                onMedia = onMedia,
                onToolCall = {},
                onRawWire = onRawWire,
                onUsage = { _, _, _, _ -> }
            )
            return
        }
        if (!resp.status.isSuccess()) {
            error("Image Generation HTTP $statusVal: $bodyText")
        }

        val root = json.parseToJsonElement(bodyText).jsonObject
        val dataArr = root["data"] as? JsonArray ?: JsonArray(emptyList())
        for (itemEl in dataArr) {
            val itemObj = itemEl as? JsonObject ?: continue
            val b64 = itemObj["b64_json"]?.jsonPrimitive?.contentOrNull ?: ""
            val remoteUrl = itemObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
            val revised = itemObj["revised_prompt"]?.jsonPrimitive?.contentOrNull ?: ""
            saveAndEmitImage(b64, remoteUrl, "image/png", profile.mediaAspectRatio, revised, onMedia, sessionId = session.id)
            if (revised.isNotBlank()) {
                onContent("*Revised Prompt:* $revised\n")
            }
        }
    }

    // ============================================================================================
    // 5. UNIVERSAL MEDIA & AUDIO DRIVER (IMAGE, VIDEO, AND TTS AUTO-ROUTING)
    //    Supports:
    //    - Universal Image Generation (`POST /v1/images/generations` & `/api/v3/images/generations` with 3-stage payload self-healing)
    //    - Universal Video Generation (`POST /contents/generations/tasks`, `/videos/generations`, `/video/generations`, `/videos` + sync/async polling)
    //    - Universal Audio Speech / TTS (`POST /v1/audio/speech` & `/audio/speech` with fallback to `/chat/completions`)
    //    - Automatic fallback to `/v1/chat/completions` when a proxy wraps media generation inside chat completions!
    // ============================================================================================
    internal fun resolveSmartEndpointUrl(
        profile: ProviderProfile,
        defaultPathWithV1: String,
        defaultPathWithoutVersion: String = defaultPathWithV1
            .removePrefix("/v1beta")
            .removePrefix("/v1")
    ): String {
        val rawBase = profile.baseUrl.trim()
        val rawOverride = profile.endpointPathOverride.trim()

        // 1. Explicit tail-free / exact endpoint URL mode (`useExactEndpointUrl`, trailing `#`, or override == "/")
        if (profile.useExactEndpointUrl || rawBase.endsWith("#") || rawOverride == "/" || rawOverride == "#") {
            return rawBase.trimEnd('#').trimEnd('/')
        }

        val base = rawBase.trimEnd('/')

        // 2. Explicit endpointPathOverride
        if (rawOverride.isNotEmpty()) {
            if (rawOverride.startsWith("http://", ignoreCase = true) ||
                rawOverride.startsWith("https://", ignoreCase = true)
            ) {
                return rawOverride.trimEnd('#')
            }
            val cleanOverride = "/" + rawOverride.trimEnd('#').trimStart('/')
            return if (base.endsWith(cleanOverride, ignoreCase = true)) base else "$base$cleanOverride"
        }

        // 3. Auto-detect when baseUrl already ends with a concrete endpoint path (no hardcoded tail appended!)
        val lowerBase = base.lowercase()
        val concreteEndpointTails = listOf(
            "/responses",
            "/chat/completions",
            "/completions",
            "/messages",
            "/interactions",
            ":generatecontent",
            ":streamgeneratecontent",
            ":predict",
            ":predictlongrunning",
            "/images/generations",
            "/images/edits",
            "/videos/generations",
            "/video/generations",
            "/contents/generations/tasks",
            "/audio/speech",
            "/audio/transcriptions",
            "/generate",
            "/invoke",
            "/chat"
        )
        if (concreteEndpointTails.any { lowerBase.endsWith(it) || lowerBase.contains("$it?") }) {
            return base
        }

        // 4. Version-agnostic root path resolution (/v1, /v2, /v3, /v4, /v1beta, /api/v3, /api/paas/v4)
        val hasVersionSuffix = Regex("/(?:api/)?(?:paas/)?v\\d+(?:beta\\d*|alpha\\d*)?$", RegexOption.IGNORE_CASE)
            .containsMatchIn(base)
        val normalizedNoVer = "/" + defaultPathWithoutVersion.trimStart('/')
        val normalizedWithVer = "/" + defaultPathWithV1.trimStart('/')
        return if (hasVersionSuffix) "$base$normalizedNoVer" else "$base$normalizedWithVer"
    }

    private suspend fun executeOpenAiAudioSpeechEndpoint(
        profile: ProviderProfile,
        model: String,
        session: ChatSession,
        historyNodes: List<MessageNode>,
        circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
        onStatus: suspend (String) -> Unit,
        onFirstToken: () -> Unit,
        onMedia: suspend (GeneratedMediaItem) -> Unit,
        onContent: suspend (String) -> Unit,
        onRawWire: suspend (String, String) -> Unit
    ) {
        val latestUserPrompt = historyNodes.lastOrNull { it.role == MessageRole.USER }?.activeVariant?.content.orEmpty()
            .ifBlank { historyNodes.lastOrNull()?.activeVariant?.content.orEmpty() }
        require(latestUserPrompt.isNotBlank()) { "Prompt cannot be empty." }

        val targetUrl = buildUrlWithAuthQuery(
            profile,
            resolveSmartEndpointUrl(profile, "/v1/audio/speech", "/audio/speech")
        )
        val voice = profile.audioVoice.ifBlank { "alloy" }
        val speed = profile.audioSpeed.coerceIn(0.25, 4.0)

        val payloadCandidates = listOf(
            buildJsonObject {
                put("model", model.trim())
                put("input", latestUserPrompt)
                put("voice", voice)
                if (speed != 1.0) put("speed", speed)
                put("response_format", "mp3")
            },
            buildJsonObject {
                put("model", model.trim())
                put("input", latestUserPrompt)
                put("voice", "alloy")
            },
            buildJsonObject {
                put("model", model.trim())
                put("input", latestUserPrompt)
            }
        )

        var lastStatus = 0
        var lastErrorBody = ""
        for ((idx, candidateObj) in payloadCandidates.withIndex()) {
            val finalPayload = mergeRawJsonExtraBody(candidateObj, profile.rawJsonExtraBody)
            val reqStr = json.encodeToString(JsonObject.serializer(), finalPayload)
            onStatus("Synthesizing speech ($model • $voice)…")
            onRawWire(reqStr, "Dispatching to $targetUrl ...")

            val resp = httpClient.post(targetUrl) {
                applyAuthAndCustomHeaders(profile)
                contentType(ContentType.Application.Json)
                setBody(reqStr)
            }
            lastStatus = resp.status.value
            val contentTypeHeader = resp.headers["Content-Type"].orEmpty().lowercase()
            if (resp.status.isSuccess() && (contentTypeHeader.contains("audio") || contentTypeHeader.contains("octet-stream"))) {
                onFirstToken()
                val audioBytes = resp.readRawBytes()
                val ts = PlatformBridge.currentTimeMillis()
                val localPath = if (audioBytes.isNotEmpty()) {
                    PlatformBridge.saveGeneratedMediaFile("speech-$ts.mp3", audioBytes, sessionId = session.id)
                } else ""
                onRawWire(reqStr, "Received ${audioBytes.size} bytes ($contentTypeHeader)")
                onMedia(
                    GeneratedMediaItem(
                        id = "aud-$ts",
                        kind = MediaKind.AUDIO,
                        mimeType = "audio/mpeg",
                        localFilePath = localPath,
                        revisedPrompt = latestUserPrompt
                    )
                )
                onContent("Generated speech audio (`$model` • voice: `$voice`).")
                return
            }

            val bodyText = resp.bodyAsText()
            lastErrorBody = bodyText
            onRawWire(reqStr, bodyText)
            if (resp.status.isSuccess()) {
                onFirstToken()
                val root = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
                val audioUrl = root?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: root?.get("audio_url")?.jsonPrimitive?.contentOrNull
                    ?: (root?.get("data") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                val b64Audio = root?.get("b64_json")?.jsonPrimitive?.contentOrNull
                    ?: root?.get("audio")?.jsonPrimitive?.contentOrNull
                val audioBytes = when {
                    !b64Audio.isNullOrBlank() -> runCatching { Base64.decode(b64Audio) }.getOrElse { ByteArray(0) }
                    !audioUrl.isNullOrBlank() -> runCatching { httpClient.get(audioUrl).readRawBytes() }.getOrElse { ByteArray(0) }
                    else -> ByteArray(0)
                }
                val ts = PlatformBridge.currentTimeMillis()
                val localPath = if (audioBytes.isNotEmpty()) {
                    PlatformBridge.saveGeneratedMediaFile("speech-$ts.mp3", audioBytes, sessionId = session.id)
                } else ""
                onMedia(
                    GeneratedMediaItem(
                        id = "aud-$ts",
                        kind = MediaKind.AUDIO,
                        mimeType = "audio/mpeg",
                        localFilePath = localPath,
                        remoteUrl = audioUrl.orEmpty(),
                        revisedPrompt = latestUserPrompt
                    )
                )
                onContent("Generated speech audio (`$model` • voice: `$voice`).")
                return
            }
            if (lastStatus != 400 && lastStatus != 422 || idx == payloadCandidates.lastIndex) {
                break
            }
        }
        error("Audio Speech HTTP $lastStatus: $lastErrorBody")
    }

    private suspend fun executeByteDanceArkGeneration(
        profile: ProviderProfile,
        model: String,
        session: ChatSession,
        historyNodes: List<MessageNode>,
        circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
        forceVideoMode: Boolean = false,
        onStatus: suspend (String, String) -> Unit,
        onFirstToken: () -> Unit,
        onThinking: suspend (String) -> Unit,
        onMedia: suspend (GeneratedMediaItem) -> Unit,
        onContent: suspend (String) -> Unit,
        onRawWire: suspend (String, String) -> Unit
    ) {
        val latestUserPrompt = historyNodes.lastOrNull { it.role == MessageRole.USER }?.activeVariant?.content.orEmpty()
            .ifBlank { historyNodes.lastOrNull()?.activeVariant?.content.orEmpty() }
        require(latestUserPrompt.isNotBlank()) { "Prompt cannot be empty." }

        val baseClean = profile.baseUrl.trim().trimEnd('#').trimEnd('/')
        val rootBase = baseClean
            .replace(Regex("/(?:api/)?(?:paas/)?v\\d+(?:beta\\d*|alpha\\d*)?$", RegexOption.IGNORE_CASE), "")
            .trimEnd('/')
        val hasVersionSuffix = Regex("/(?:api/)?(?:paas/)?v\\d+(?:beta\\d*|alpha\\d*)?$", RegexOption.IGNORE_CASE)
            .containsMatchIn(baseClean)

        val knownModalities = resolveKnownOutputModalities(profile, model)
        val isVideoTaskMode = forceVideoMode ||
            knownModalities.contains("video") ||
            profile.endpointPathOverride.contains("contents/generations/tasks", ignoreCase = true) ||
            profile.endpointPathOverride.contains("videos/generations", ignoreCase = true) ||
            profile.endpointPathOverride.contains("video/generations", ignoreCase = true)

        if (isVideoTaskMode) {
            val videoRes = when (profile.mediaResolution) {
                "480p", "720p", "1080p", "4K" -> profile.mediaResolution
                "2K" -> "1080p"
                else -> "720p"
            }

            val latestUserNode = historyNodes.lastOrNull { it.role == MessageRole.USER } ?: historyNodes.lastOrNull()
            val refImages = resolveBinaryAttachments(latestUserNode?.activeVariant)
                .filter { it.mimeType.lowercase().startsWith("image/") }

            val arkPayloadObj = buildJsonObject {
                put("model", model)
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", latestUserPrompt)
                    })
                    for ((imgIdx, img) in refImages.withIndex()) {
                        val imgRole = when {
                            refImages.size == 1 -> "first_frame"
                            refImages.size == 2 && imgIdx == 0 -> "first_frame"
                            refImages.size == 2 && imgIdx == 1 -> "last_frame"
                            else -> "reference_image"
                        }
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:${img.mimeType};base64,${img.base64Data}")
                            }
                            put("role", imgRole)
                        })
                    }
                }
                if (profile.mediaAspectRatio.isNotBlank()) {
                    put("ratio", profile.mediaAspectRatio)
                }
                put("duration", profile.videoDurationSeconds.coerceIn(4, 15))
                put("resolution", videoRes)
                put("generate_audio", profile.videoGenerateAudio)
                put("watermark", false)
            }

            val openAiVideoPayloadObj = buildJsonObject {
                put("model", model)
                put("prompt", latestUserPrompt)
                if (profile.mediaAspectRatio.isNotBlank()) {
                    put("aspect_ratio", profile.mediaAspectRatio)
                }
                put("duration", profile.videoDurationSeconds.coerceIn(4, 15))
                put("resolution", videoRes)
                if (refImages.isNotEmpty()) {
                    val firstImg = refImages.first()
                    put("image", "data:${firstImg.mimeType};base64,${firstImg.base64Data}")
                }
            }

            val minimalVideoPayloadObj = buildJsonObject {
                put("model", model)
                put("prompt", latestUserPrompt)
            }

            val isByteDanceFamily = model.contains("seedance", ignoreCase = true) ||
                model.contains("doubao", ignoreCase = true) ||
                baseClean.contains("volces.com", ignoreCase = true) ||
                baseClean.contains("bytepluses.com", ignoreCase = true) ||
                baseClean.endsWith("/api/v3", ignoreCase = true)

            val candidateTaskUrls = when {
                profile.useExactEndpointUrl || profile.baseUrl.trim().endsWith("#") || profile.endpointPathOverride.isNotBlank() ->
                    listOf(resolveSmartEndpointUrl(profile, "/v1/videos/generations", "/videos/generations"))
                isByteDanceFamily -> buildList {
                    if (hasVersionSuffix) {
                        add("$baseClean/contents/generations/tasks")
                        add("$baseClean/videos/generations")
                        add("$baseClean/video/generations")
                        add("$baseClean/videos")
                    }
                    add("$rootBase/api/v3/contents/generations/tasks")
                    add("$rootBase/v1/contents/generations/tasks")
                    add("$rootBase/v1/videos/generations")
                    add("$rootBase/v1/video/generations")
                    add("$rootBase/v1/videos")
                }.distinct()
                else -> buildList {
                    if (hasVersionSuffix) {
                        add("$baseClean/videos/generations")
                        add("$baseClean/video/generations")
                        add("$baseClean/videos")
                        add("$baseClean/contents/generations/tasks")
                    }
                    add("$rootBase/v1/videos/generations")
                    add("$rootBase/v1/video/generations")
                    add("$rootBase/v1/videos")
                    add("$rootBase/v1/contents/generations/tasks")
                    add("$rootBase/api/v3/contents/generations/tasks")
                }.distinct()
            }

            var activeTaskUrl = candidateTaskUrls.first()
            var createBody = ""
            var createStatus = 0
            var reqStr = ""

            outerUrlLoop@ for (candUrl in candidateTaskUrls) {
                activeTaskUrl = candUrl
                val finalCreateUrl = buildUrlWithAuthQuery(profile, candUrl)
                val isTasksEndpoint = candUrl.contains("contents/generations/tasks", ignoreCase = true)
                val payloadSequence = if (isTasksEndpoint) {
                    listOf(arkPayloadObj, openAiVideoPayloadObj, minimalVideoPayloadObj)
                } else {
                    listOf(openAiVideoPayloadObj, minimalVideoPayloadObj, arkPayloadObj)
                }

                for ((pIdx, candPayload) in payloadSequence.withIndex()) {
                    val merged = mergeRawJsonExtraBody(candPayload, profile.rawJsonExtraBody)
                    reqStr = json.encodeToString(JsonObject.serializer(), merged)
                    onStatus("Submitting video generation task ($model)…", "")
                    onRawWire(reqStr, "Dispatching to $finalCreateUrl ...")

                    val createResp = httpClient.post(finalCreateUrl) {
                        applyAuthAndCustomHeaders(profile)
                        contentType(ContentType.Application.Json)
                        setBody(reqStr)
                    }
                    createBody = createResp.bodyAsText()
                    createStatus = createResp.status.value
                    onRawWire(reqStr, createBody)
                    if (createResp.status.isSuccess()) break@outerUrlLoop
                    if (createStatus == 404 || createStatus == 405 || createStatus == 501) {
                        break // Try next URL in candidateTaskUrls
                    }
                    if ((createStatus == 400 || createStatus == 422) && pIdx < payloadSequence.lastIndex) {
                        continue // Self-heal payload schema on the same URL
                    }
                    break
                }
            }

            if (createStatus == 404 || createStatus == 405 || createStatus == 501) {
                onStatus("Routing video model via /v1/chat/completions…", "")
                val chatAccumulator = StringBuilder()
                executeOpenAiChatCompletionsLoop(
                    profile = profile,
                    model = model,
                    session = session,
                    historyNodes = historyNodes,
                    continuePrefix = "",
                    circuitBreaker = circuitBreaker,
                    onStatus = { st -> onStatus(st, "") },
                    onGoalProgress = {},
                    onFirstToken = onFirstToken,
                    onThinking = onThinking,
                    onContent = { delta ->
                        chatAccumulator.append(delta)
                        onContent(delta)
                    },
                    onMedia = onMedia,
                    onToolCall = {},
                    onRawWire = onRawWire,
                    onUsage = { _, _, _, _ -> }
                )
                extractMarkdownVideosFromText(chatAccumulator.toString(), profile.mediaAspectRatio, onMedia, sessionId = session.id)
                return
            }

            if (createStatus !in 200..299) {
                error("Video Generation HTTP $createStatus: $createBody")
            }

            val createObj = json.parseToJsonElement(createBody).jsonObject

            fun extractDirectVideoUrl(obj: JsonObject): String? {
                val contentSub = (obj["content"] as? JsonObject)
                    ?: ((obj["data"] as? JsonObject)?.get("content") as? JsonObject)
                val dataObj = obj["data"] as? JsonObject
                val dataArr = obj["data"] as? JsonArray
                val firstDataItem = dataArr?.firstOrNull() as? JsonObject
                val videoObj = obj["video"] as? JsonObject
                val generationsArr = obj["generations"] as? JsonArray
                val firstGenItem = generationsArr?.firstOrNull() as? JsonObject
                return contentSub?.get("video_url")?.jsonPrimitive?.contentOrNull
                    ?: obj["video_url"]?.jsonPrimitive?.contentOrNull
                    ?: obj["result_url"]?.jsonPrimitive?.contentOrNull
                    ?: obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: (obj["output"] as? JsonPrimitive)?.contentOrNull
                    ?: videoObj?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: dataObj?.get("video_url")?.jsonPrimitive?.contentOrNull
                    ?: dataObj?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: firstDataItem?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: firstDataItem?.get("video_url")?.jsonPrimitive?.contentOrNull
                    ?: firstGenItem?.get("url")?.jsonPrimitive?.contentOrNull
            }

            val immediateVideoUrl = extractDirectVideoUrl(createObj)
            val taskId = createObj["id"]?.jsonPrimitive?.contentOrNull
                ?: createObj["task_id"]?.jsonPrimitive?.contentOrNull
                ?: createObj["generation_id"]?.jsonPrimitive?.contentOrNull
                ?: (createObj["data"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                ?: (createObj["data"] as? JsonObject)?.get("task_id")?.jsonPrimitive?.contentOrNull
                ?: if (immediateVideoUrl != null) "sync-${PlatformBridge.currentTimeMillis()}" else null
                ?: error("Video generation response did not contain a task 'id' or 'url': $createBody")

            var latestTaskObj = createObj
            if (immediateVideoUrl == null) {
                var status = createObj["status"]?.jsonPrimitive?.contentOrNull
                    ?: (createObj["data"] as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull
                    ?: "queued"
                var pollCount = 0

                while (status.equals("queued", ignoreCase = true) ||
                    status.equals("pending", ignoreCase = true) ||
                    status.equals("submitted", ignoreCase = true) ||
                    status.equals("running", ignoreCase = true) ||
                    status.equals("in_progress", ignoreCase = true) ||
                    status.equals("processing", ignoreCase = true) ||
                    status.equals("starting", ignoreCase = true)
                ) {
                    pollCount++
                    onStatus("Video Generating ($status • poll #$pollCount)…", taskId)
                    delay(3000L)
                    val pollUrl = buildUrlWithAuthQuery(profile, "${activeTaskUrl.trimEnd('/')}/$taskId")
                    val pollResp = httpClient.get(pollUrl) {
                        applyAuthAndCustomHeaders(profile)
                    }
                    val pollBody = pollResp.bodyAsText()
                    onRawWire(reqStr, pollBody)
                    if (!pollResp.status.isSuccess()) {
                        error("Video Task Poll HTTP ${pollResp.status.value}: $pollBody")
                    }
                    latestTaskObj = json.parseToJsonElement(pollBody).jsonObject
                    status = latestTaskObj["status"]?.jsonPrimitive?.contentOrNull
                        ?: (latestTaskObj["data"] as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull
                        ?: "succeeded"
                }

                if (status.equals("failed", ignoreCase = true) ||
                    status.equals("error", ignoreCase = true) ||
                    status.equals("expired", ignoreCase = true) ||
                    status.equals("cancelled", ignoreCase = true) ||
                    status.equals("canceled", ignoreCase = true)
                ) {
                    val errMsg = (latestTaskObj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: latestTaskObj.toString()
                    error("Video Task $status: $errMsg")
                }
            }

            val videoUrl = immediateVideoUrl ?: extractDirectVideoUrl(latestTaskObj)
                ?: error("Video task completed but no video URL was found in response: $latestTaskObj")

            onFirstToken()
            onStatus("Downloading generated MP4 video…", taskId)
            val videoBytes = runCatching {
                httpClient.get(videoUrl).readRawBytes()
            }.getOrElse { ByteArray(0) }

            val localPath = if (videoBytes.isNotEmpty()) {
                PlatformBridge.saveGeneratedMediaFile("video-$taskId.mp4", videoBytes, sessionId = session.id)
            } else ""

            val mediaItem = GeneratedMediaItem(
                id = "vid-$taskId",
                kind = MediaKind.VIDEO,
                mimeType = "video/mp4",
                localFilePath = localPath,
                remoteUrl = videoUrl,
                aspectRatio = profile.mediaAspectRatio
            )
            onMedia(mediaItem)
            onContent("Generated video (`$model` • ${profile.mediaAspectRatio} • ${profile.videoDurationSeconds}s).")
        } else {
            // Universal Image Generation Engine (`POST /v1/images/generations` & `/api/v3/images/generations`)
            val latestUserNode = historyNodes.lastOrNull { it.role == MessageRole.USER } ?: historyNodes.lastOrNull()
            val refImages = resolveBinaryAttachments(latestUserNode?.activeVariant)
                .filter { it.mimeType.lowercase().startsWith("image/") }

            val isHighResSeedreamFamily = model.contains("seedream", ignoreCase = true) ||
                model.contains("doubao", ignoreCase = true) ||
                model.contains("dreamina", ignoreCase = true) ||
                profile.mediaResolution in listOf("2K", "4K")
            val primarySize = if (isHighResSeedreamFamily) {
                resolveSeedreamImageSize(profile.mediaAspectRatio, profile.mediaResolution)
            } else {
                resolveOpenAiImageSize(profile.mediaAspectRatio, profile.mediaResolution)
            }
            val secondarySize = if (isHighResSeedreamFamily) {
                resolveOpenAiImageSize(profile.mediaAspectRatio, profile.mediaResolution)
            } else {
                resolveSeedreamImageSize(profile.mediaAspectRatio, profile.mediaResolution)
            }

            val candidatePayloads = listOf(
                // Candidate 1: Primary size + b64_json + optional reference image
                buildJsonObject {
                    put("model", model)
                    put("prompt", latestUserPrompt)
                    put("size", primarySize)
                    put("response_format", "b64_json")
                    if (isHighResSeedreamFamily) put("watermark", false)
                    if (refImages.isNotEmpty()) {
                        val firstImg = refImages.first()
                        put("image", "data:${firstImg.mimeType};base64,${firstImg.base64Data}")
                    }
                },
                // Candidate 2: Alternate resolution tier without watermark/response_format restrictions
                buildJsonObject {
                    put("model", model)
                    put("prompt", latestUserPrompt)
                    put("size", secondarySize)
                    if (refImages.isNotEmpty()) {
                        val firstImg = refImages.first()
                        put("image", "data:${firstImg.mimeType};base64,${firstImg.base64Data}")
                    }
                },
                // Candidate 3: Minimal universal payload (model + prompt only)
                buildJsonObject {
                    put("model", model)
                    put("prompt", latestUserPrompt)
                }
            )

            val candidateImgUrls = when {
                profile.useExactEndpointUrl || profile.baseUrl.trim().endsWith("#") || profile.endpointPathOverride.isNotBlank() ->
                    listOf(resolveSmartEndpointUrl(profile, "/v1/images/generations", "/images/generations"))
                hasVersionSuffix -> listOf(
                    "$baseClean/images/generations",
                    "$rootBase/v1/images/generations",
                    "$rootBase/api/v3/images/generations"
                ).distinct()
                else -> listOf(
                    "$rootBase/v1/images/generations",
                    "$rootBase/api/v3/images/generations",
                    "$baseClean/images/generations"
                ).distinct()
            }

            var bodyText = ""
            var lastStatus = 0
            var reqStr = ""
            outerImgLoop@ for (candidateUrl in candidateImgUrls) {
                val finalUrl = buildUrlWithAuthQuery(profile, candidateUrl)
                for ((pIdx, candPayload) in candidatePayloads.withIndex()) {
                    val finalPayload = mergeRawJsonExtraBody(candPayload, profile.rawJsonExtraBody)
                    reqStr = json.encodeToString(JsonObject.serializer(), finalPayload)
                    onStatus("Generating image ($model • ${ profile.mediaAspectRatio })…", "")
                    onRawWire(reqStr, "Dispatching to $finalUrl ...")

                    val resp = httpClient.post(finalUrl) {
                        applyAuthAndCustomHeaders(profile)
                        contentType(ContentType.Application.Json)
                        setBody(reqStr)
                    }
                    bodyText = resp.bodyAsText()
                    lastStatus = resp.status.value
                    onRawWire(reqStr, bodyText)
                    if (resp.status.isSuccess()) break@outerImgLoop
                    if (lastStatus == 404 || lastStatus == 405 || lastStatus == 501) {
                        break // Try next URL
                    }
                    if ((lastStatus == 400 || lastStatus == 422) && pIdx < candidatePayloads.lastIndex) {
                        val lowerErr = bodyText.lowercase()
                        if (lowerErr.contains("video") || lowerErr.contains("contents/generations/tasks")) {
                            break@outerImgLoop
                        }
                        continue // Self-heal payload parameters
                    }
                    break
                }
            }

            if (lastStatus == 404 || lastStatus == 405 || lastStatus == 501) {
                onStatus("Routing image model via /v1/chat/completions…", "")
                executeOpenAiChatCompletionsLoop(
                    profile = profile,
                    model = model,
                    session = session,
                    historyNodes = historyNodes,
                    continuePrefix = "",
                    circuitBreaker = circuitBreaker,
                    onStatus = { st -> onStatus(st, "") },
                    onGoalProgress = {},
                    onFirstToken = onFirstToken,
                    onThinking = onThinking,
                    onContent = onContent,
                    onMedia = onMedia,
                    onToolCall = {},
                    onRawWire = onRawWire,
                    onUsage = { _, _, _, _ -> }
                )
                return
            }

            if (lastStatus !in 200..299) {
                val lowerBody = bodyText.lowercase()
                if (!forceVideoMode && (lowerBody.contains("video") || lowerBody.contains("tasks") || lowerBody.contains("content"))) {
                    recordDiscoveredModality(model, listOf("video"))
                    executeByteDanceArkGeneration(
                        profile = profile,
                        model = model,
                        session = session,
                        historyNodes = historyNodes,
                        circuitBreaker = circuitBreaker,
                        forceVideoMode = true,
                        onStatus = onStatus,
                        onFirstToken = onFirstToken,
                        onThinking = onThinking,
                        onMedia = onMedia,
                        onContent = onContent,
                        onRawWire = onRawWire
                    )
                    return
                }
                error("Image Generation HTTP $lastStatus: $bodyText")
            }

            onFirstToken()
            val root = json.parseToJsonElement(bodyText).jsonObject
            val dataArr = root["data"] as? JsonArray
                ?: root["images"] as? JsonArray
                ?: JsonArray(emptyList())
            if (dataArr.isNotEmpty()) {
                for (itemEl in dataArr) {
                    val itemObj = itemEl as? JsonObject ?: continue
                    val b64 = itemObj["b64_json"]?.jsonPrimitive?.contentOrNull
                        ?: itemObj["base64"]?.jsonPrimitive?.contentOrNull ?: ""
                    val remoteUrl = itemObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    val revised = itemObj["revised_prompt"]?.jsonPrimitive?.contentOrNull ?: ""
                    saveAndEmitImage(b64, remoteUrl, "image/png", profile.mediaAspectRatio, revised, onMedia, sessionId = session.id)
                }
            } else {
                val directUrl = root["url"]?.jsonPrimitive?.contentOrNull ?: ""
                val directB64 = root["b64_json"]?.jsonPrimitive?.contentOrNull ?: ""
                if (directUrl.isNotBlank() || directB64.isNotBlank()) {
                    saveAndEmitImage(directB64, directUrl, "image/png", profile.mediaAspectRatio, "", onMedia, sessionId = session.id)
                }
            }
        }
    }

    private inline fun onFirstTokenIfZero(current: Long, setter: (Long) -> Unit) {
        if (current == 0L) setter(PlatformBridge.currentTimeMillis())
    }

    internal data class ResolvedBinaryAttachment(
        val fileName: String,
        val mimeType: String,
        val base64Data: String
    )

    /**
     * Resolves multimodal binary attachments (images, videos, audio, and PDFs) with filename, MIME type, and base64 payload,
     * reading lazily from `localFilePath` on disk when `base64Data` is empty so multi-megabyte base64 strings never bloat RAM or JSON state.
     */
    private fun resolveBinaryAttachments(nodeVar: MessageVariant?): List<ResolvedBinaryAttachment> {
        if (nodeVar == null || nodeVar.attachments.isEmpty()) return emptyList()
        return nodeVar.attachments.mapNotNull { att ->
            val m = att.mimeType.lowercase()
            val isBinaryMedia = m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/") ||
                m == "application/pdf" || m.contains("document") || m.contains("msword") || m.contains("officedocument")
            if (!isBinaryMedia && att.base64Data.isBlank()) return@mapNotNull null
            val b64 = if (att.base64Data.isNotBlank()) {
                att.base64Data
            } else if (att.localFilePath.isNotBlank()) {
                PlatformBridge.readBinaryFileBytes(att.localFilePath)?.let { Base64.encode(it) } ?: ""
            } else ""
            if (b64.isNotBlank()) {
                ResolvedBinaryAttachment(
                    fileName = att.fileName,
                    mimeType = att.mimeType.ifBlank { "image/png" },
                    base64Data = b64
                )
            } else null
        }
    }

    /**
     * Strips UI-only inline error banners and mid-stream interruption footers from assistant prose
     * so they are never sent back to LLM providers as part of conversation history or continuation prefixes.
     */
    fun stripInlineErrorNotices(raw: String): String {
        if (raw.isBlank()) return ""
        val trimmedStart = raw.trimStart()
        if (trimmedStart.startsWith("**[Provider Error:") ||
            trimmedStart.startsWith("**[Stream Error:") ||
            trimmedStart.startsWith("*[Stream paused/interrupted") ||
            trimmedStart.startsWith("*[Stream interrupted") ||
            trimmedStart.startsWith("*[Interrupted:")
        ) {
            return ""
        }
        var cleaned = raw
        val suffixes = listOf(
            "\n\n*[Stream paused/interrupted:",
            "\n\n*[Stream interrupted",
            "\n\n*[Interrupted:"
        )
        for (marker in suffixes) {
            val idx = cleaned.indexOf(marker)
            if (idx >= 0) {
                cleaned = cleaned.substring(0, idx)
            }
        }
        return cleaned.trimEnd()
    }

    internal fun hasMeaningfulAssistantContext(v: MessageVariant): Boolean {
        val cleanProse = stripInlineErrorNotices(v.content)
        return cleanProse.isNotBlank() ||
            v.thinkingSummary.isNotBlank() ||
            v.thinkingSignature.isNotBlank() ||
            v.toolCalls.isNotEmpty() ||
            v.mediaItems.isNotEmpty() ||
            v.attachments.isNotEmpty()
    }

    internal fun sanitizeContextNodes(historyNodes: List<MessageNode>): List<MessageNode> {
        return historyNodes.filter { node ->
            if (!node.includedInContext) return@filter false
            if (node.role == MessageRole.ASSISTANT) {
                return@filter hasMeaningfulAssistantContext(node.activeVariant)
            }
            true
        }
    }
    /**
     * Detects transient proxy/gateway/CDN timeouts (~5-minute 300s Cloudflare 524, Nginx 502/504,
     * socket read timeout, premature EOF, connection reset, 529 overloaded, or 429 rate limit) so
     * `streamGeneration` and multi-step workspace tool loops can automatically retry.
     */
    internal fun isHardAuthOrQuotaError(err: Throwable): Boolean {
        val msg = (err.message ?: err.toString()).lowercase()
        return msg.contains("http 401") ||
            msg.contains("http 403") ||
            msg.contains("invalid_api_key") ||
            msg.contains("invalid api key") ||
            msg.contains("authentication_error") ||
            msg.contains("permission_denied") ||
            msg.contains("billing_not_active") ||
            msg.contains("insufficient_quota")
    }

    internal fun isTransientOrProxyTimeoutError(err: Throwable): Boolean {
        if (err is kotlinx.coroutines.CancellationException) return false
        // Fail fast on hard authentication, permission, or billing/quota deactivation errors
        if (isHardAuthOrQuotaError(err)) {
            return false
        }
        // Allow unsupported-endpoint errors (404/405/501) to trigger protocol auto-fallback immediately without waiting 60s
        if (isEndpointUnsupportedForAutoFallback(err)) {
            return false
        }
        val msg = (err.message ?: err.toString()).lowercase()
        // Fail fast on client-side bad request (HTTP 400) unless it is an explicit rate-limit / overload / timeout wrapped in 400
        if (msg.contains("http 400")) {
            if (msg.contains("invalid_request") || msg.contains("invalid_argument") || msg.contains("bad_request")) {
                return false
            }
            val isRateOrOverload400 = msg.contains("rate limit") ||
                msg.contains("rate_limit") ||
                msg.contains("ratelimit") ||
                msg.contains("too many requests") ||
                msg.contains("resource_exhausted") ||
                msg.contains("overload") ||
                msg.contains("timeout") ||
                msg.contains("busy") ||
                msg.contains("capacity") ||
                msg.contains("try again")
            if (!isRateOrOverload400) {
                return false
            }
        }
        // Treat all other network, socket, EOF, stream, rate-limit (429),
        // gateway (502/503/504/520-529), and upstream proxy interruptions as retryable!
        return true
    }

    /**
     * Detects when a proxy or server does not implement `/v1beta/interactions` or `/v1/responses`
     * so `streamGeneration` can automatically fall back to `:streamGenerateContent` or `/v1/chat/completions`.
     */
    internal fun isEndpointUnsupportedForAutoFallback(err: Throwable): Boolean {
        val msg = (err.message ?: err.toString()).lowercase()
        return msg.contains("http 404") ||
            msg.contains("http 405") ||
            msg.contains("http 501") ||
            msg.contains("not found") ||
            msg.contains("unknown endpoint") ||
            msg.contains("unrecognized request url") ||
            msg.contains("unsupported path")
    }

    /**
     * Determines whether a failure on the primary protocol (`/v1beta/interactions` or `/v1/responses`)
     * should fall back to the legacy endpoint (`:streamGenerateContent` or `/v1/chat/completions`).
     * - Never falls back on `CancellationException` or hard auth/quota errors (HTTP 401/403).
     * - Always falls back on unsupported endpoint errors (404/405/501).
     * - When `useLegacyFallbackEndpoint` is enabled, falls back on any remaining error after the
     *   primary protocol's self-healing and fast probe retry budget (`LEGACY_FALLBACK_PRIMARY_MAX_RETRIES`) are exhausted.
     */
    internal fun shouldFallbackToLegacyProtocol(
        err: Throwable,
        useLegacyFallbackEndpoint: Boolean
    ): Boolean {
        if (err is kotlinx.coroutines.CancellationException) return false
        val actual = if (err is StepRetriesExhaustedException && err.cause != null) err.cause!! else err
        if (isHardAuthOrQuotaError(actual)) return false
        if (isEndpointUnsupportedForAutoFallback(actual)) return true
        return useLegacyFallbackEndpoint
    }

    /**
     * Synthesizes an in-progress assistant turn + resume user prompt whenever a turn has already
     * accumulated tool executions or thoughts (either before a mid-stream continuation pass or before
     * falling back from a newer protocol to a legacy protocol mid-turn), ensuring zero loss of
     * thoughts, cryptographic signatures, tool calls, or partial prose.
     */
    internal fun buildEffectiveHistoryAndPrefix(
        baseHistoryNodes: List<MessageNode>,
        continuePrefix: String,
        thinkingText: String,
        trailingThinkingText: String = "",
        signatureText: String,
        executedTools: List<ToolCallRecord>,
        generatedMedia: List<GeneratedMediaItem>,
        protocol: ProviderProtocol,
        model: String,
        passTag: String,
        forceVisibleThoughtSummary: Boolean = false
    ): Pair<List<MessageNode>, String> {
        val sanitizedTools = executedTools.map { tc ->
            if (tc.isRunning || tc.resultText == "Running shell command…" || tc.resultText == "Running ${tc.name}…") {
                tc.copy(
                    isRunning = false,
                    resultText = "[Tool execution interrupted before completion — re-run ${tc.name} if needed]"
                )
            } else tc
        }
        val hasPriorTurnProgress = sanitizedTools.isNotEmpty() ||
            (continuePrefix.isBlank() && (thinkingText.isNotEmpty() || signatureText.isNotEmpty()))
        return if (hasPriorTurnProgress) {
            val effectiveAssistantContent = when {
                sanitizedTools.isNotEmpty() && continuePrefix.isNotBlank() -> continuePrefix
                forceVisibleThoughtSummary && continuePrefix.isBlank() && thinkingText.isNotBlank() -> {
                    "[Completed internal reasoning:\n$thinkingText]"
                }
                else -> ""
            }
            val partialVar = MessageVariant(
                id = "var-in-progress-$passTag",
                content = effectiveAssistantContent,
                thinkingSummary = thinkingText,
                trailingThinking = if (sanitizedTools.isNotEmpty()) trailingThinkingText else "",
                thinkingSignature = signatureText,
                toolCalls = sanitizedTools,
                mediaItems = generatedMedia,
                providerProtocol = protocol,
                modelUsed = model
            )
            val partialAssistantNode = MessageNode(
                id = "node-in-progress-$passTag",
                role = MessageRole.ASSISTANT,
                variants = listOf(partialVar)
            )
            val resumePrompt = if (executedTools.isNotEmpty() && continuePrefix.isNotBlank()) {
                "[System Note: Continue seamlessly from the exact end of this partial passage without repeating completed tool calls or text:\n\"$continuePrefix\"]"
            } else {
                val stepDesc = if (executedTools.isNotEmpty()) "internal reasoning and tool executions" else "internal reasoning"
                "[System Note: You have already completed your $stepDesc above, but your turn ended before you wrote your final response to the user. Do NOT repeat completed tool calls or thoughts—write the complete final response to the user now.]"
            }
            val resumeUserNode = MessageNode(
                id = "node-resume-user-$passTag",
                role = MessageRole.USER,
                variants = listOf(MessageVariant(id = "var-resume-user-$passTag", content = resumePrompt))
            )
            (baseHistoryNodes + partialAssistantNode + resumeUserNode) to ""
        } else {
            baseHistoryNodes to continuePrefix
        }
    }

    /**
     * Detects HTTP 400 / 413 context-window / token-limit overflow errors across Gemini, OpenAI, Anthropic, and proxies.
     */
    internal fun isContextLengthExceededError(err: Throwable): Boolean {
        val msg = (err.message ?: err.toString()).lowercase()
        return msg.contains("context_length_exceeded") ||
            msg.contains("maximum context length") ||
            msg.contains("prompt is too long") ||
            (msg.contains("input token count") && msg.contains("exceed")) ||
            msg.contains("too many tokens") ||
            msg.contains("exceeds the maximum number of tokens") ||
            msg.contains("request too large") ||
            msg.contains("http 413")
    }

    /**
     * Condenses the oldest 50% of active unmuted conversation turns into a compact Continuity Digest
     * while keeping the newest turns in full verbatim fidelity.
     */
    internal fun compactHistoryNodesOnOverflow(nodes: List<MessageNode>): List<MessageNode> {
        val activeIndices = nodes.mapIndexedNotNull { idx, n -> if (n.includedInContext) idx else null }
        if (activeIndices.size <= 4) return nodes
        val compactCount = (activeIndices.size / 2).coerceAtLeast(2)
        val indicesToCompact = activeIndices.take(compactCount).toSet()
        val digestLines = mutableListOf<String>()
        for (idx in indicesToCompact) {
            val n = nodes[idx]
            val speaker = if (n.role == MessageRole.ASSISTANT) "Assistant" else "User"
            val snippet = stripInlineErrorNotices(n.activeVariant.content)
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(260)
            if (snippet.isNotEmpty()) {
                digestLines.add("- $speaker: $snippet")
            }
        }
        val digestText = "[Auto-Compacted Earlier Conversation Continuity Digest (${indicesToCompact.size} turns condensed):\n" +
            digestLines.take(24).joinToString("\n") + "]"
        val digestNode = MessageNode(
            id = "node-auto-digest-${PlatformBridge.currentTimeMillis()}",
            role = MessageRole.USER,
            variants = listOf(MessageVariant(id = "var-auto-digest", content = digestText))
        )
        val remaining = nodes.mapIndexed { idx, n ->
            if (idx in indicesToCompact) n.copy(includedInContext = false) else n
        }
        return listOf(digestNode) + remaining
    }
    private fun extractGoalObjectiveFromHistory(historyNodes: List<MessageNode>): String {
        val lastUserText = historyNodes.lastOrNull { it.role == MessageRole.USER }?.activeVariant?.content?.trim().orEmpty()
        if (lastUserText.startsWith("/goal", ignoreCase = true)) {
            return lastUserText.substringAfter("/goal").trim().ifBlank { "Complete the user's requested objective thoroughly." }
        }
        if (lastUserText.startsWith("[Autonomous Goal Mode:", ignoreCase = true)) {
            return lastUserText.substringAfter("[Autonomous Goal Mode:").substringBefore("]").trim()
                .ifBlank { lastUserText }
        }
        return ""
    }

    /**
     * 3-Layer Rolling Context Compaction Helpers for Gemini Interactions, OpenAI Responses,
     * OpenAI Chat Completions, and Anthropic Messages loops.
     */
    private fun compactGeminiInteractionsExtraSteps(steps: List<JsonObject>): List<JsonObject> {
        if (steps.size <= 16) return steps
        val total = steps.size
        return steps.mapIndexed { idx, obj ->
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type == "function_result") {
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val resultEl = obj["result"]
                val raw = when (resultEl) {
                    is JsonPrimitive -> resultEl.contentOrNull.orEmpty()
                    is JsonObject -> resultEl["result"]?.jsonPrimitive?.contentOrNull ?: resultEl.toString()
                    else -> resultEl?.toString().orEmpty()
                }
                val compacted = WorkspaceHarness.compactStaleToolOutputText(idx, total, name, raw)
                if (compacted != raw) {
                    buildJsonObject {
                        for ((k, v) in obj) {
                            if (k == "result") {
                                putJsonObject("result") {
                                    put("result", compacted)
                                }
                            } else {
                                put(k, v)
                            }
                        }
                    }
                } else obj
            } else obj
        }
    }

    private fun compactOpenAiResponsesExtraItems(items: List<JsonObject>): List<JsonObject> {
        if (items.size <= 16) return items
        val total = items.size
        return items.mapIndexed { idx, obj ->
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type == "function_call_output") {
                val raw = obj["output"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val compacted = WorkspaceHarness.compactStaleToolOutputText(idx, total, "tool", raw)
                if (compacted != raw) {
                    buildJsonObject {
                        for ((k, v) in obj) {
                            if (k == "output") put("output", compacted) else put(k, v)
                        }
                    }
                } else obj
            } else obj
        }
    }

    private fun compactChatCompletionsToolMessages(messages: List<JsonObject>): List<JsonObject> {
        if (messages.size <= 16) return messages
        val total = messages.size
        return messages.mapIndexed { idx, obj ->
            val role = obj["role"]?.jsonPrimitive?.contentOrNull
            if (role == "tool") {
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val raw = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val compacted = WorkspaceHarness.compactStaleToolOutputText(idx, total, name, raw)
                if (compacted != raw) {
                    buildJsonObject {
                        for ((k, v) in obj) {
                            if (k == "content") put("content", compacted) else put(k, v)
                        }
                    }
                } else obj
            } else obj
        }
    }

    private fun compactAnthropicExtraMessages(messages: List<JsonObject>): List<JsonObject> {
        if (messages.size <= 16) return messages
        val total = messages.size
        return messages.mapIndexed { idx, obj ->
            val role = obj["role"]?.jsonPrimitive?.contentOrNull
            val contentArr = obj["content"] as? JsonArray
            if (role == "user" && contentArr != null && total - idx > 16) {
                val updatedArr = buildJsonArray {
                    for (el in contentArr) {
                        val bObj = el as? JsonObject
                        if (bObj != null && bObj["type"]?.jsonPrimitive?.contentOrNull == "tool_result") {
                            val raw = bObj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val compacted = WorkspaceHarness.compactStaleToolOutputText(idx, total, "tool", raw)
                            add(buildJsonObject {
                                for ((k, v) in bObj) {
                                    if (k == "content") put("content", compacted) else put(k, v)
                                }
                            })
                        } else {
                            add(el)
                        }
                    }
                }
                buildJsonObject {
                    put("role", "user")
                    put("content", updatedArr)
                }
            } else obj
        }
    }

    internal data class ToolStepBatch(
        val thoughtSignature: String,
        val precedingThinking: String,
        val calls: List<ToolCallRecord>
    )

    private val BASE64_SEGMENT_REGEX = Regex("[A-Za-z0-9+/]+={0,2}")
    private val GEMINI_SIG_MAGIC = byteArrayOf(0x01, 0x69, 0x14, 0x7d, 0x13)
    private val GEMINI_SERVER_TOOL_TAG = byteArrayOf(0x08, 0x02, 0x12)

    private fun indexOfByteSubarray(haystack: ByteArray, needle: ByteArray, endExclusive: Int = haystack.size): Int {
        if (needle.isEmpty() || endExclusive < needle.size) return -1
        val limit = minOf(haystack.size, endExclusive) - needle.size
        for (i in 0..limit) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    internal fun isGeminiServerSideToolSignature(seg: String): Boolean {
        if (seg.length < 16 || seg.startsWith("gAAAAAB")) return false
        val decoded = runCatching { Base64.decode(seg.trim()) }.getOrNull() ?: return false
        val magicPos = indexOfByteSubarray(decoded, GEMINI_SIG_MAGIC)
        if (magicPos <= 0) return false
        return indexOfByteSubarray(decoded, GEMINI_SERVER_TOOL_TAG, endExclusive = magicPos) >= 0
    }

    internal fun isCompleteGeminiSignatureProtobuf(seg: String): Boolean {
        if (seg.length < 16 || seg.startsWith("gAAAAAB")) return false
        val decoded = runCatching { Base64.decode(seg.trim()) }.getOrNull() ?: return false
        return indexOfByteSubarray(decoded, GEMINI_SIG_MAGIC) >= 0
    }

    /**
     * Sanitizes a raw or historically concatenated Gemini `thoughtSignature` string:
     * - Splits any glued Base64 segments (`...=...==...==`) into individual Base64 strings.
     * - When `forFunctionCall == true`, filters out server-side `google_search` (`08 02`) signatures
     *   so `/v1beta/interactions` and `:streamGenerateContent` never fail with `HTTP 400`.
     */
    internal fun sanitizeGeminiThoughtSignature(rawSig: String, forFunctionCall: Boolean = false): String {
        val trimmed = rawSig.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed == "context_engineering_is_the_way_to_go" || trimmed == "skip_thought_signature_validator") {
            return if (forFunctionCall) trimmed else ""
        }
        if (trimmed.startsWith("gAAAAAB")) return ""
        val hasMidPadding = trimmed.indexOf('=') in 0 until trimmed.trimEnd('=').length
        val isRealGeminiProtobuf = isCompleteGeminiSignatureProtobuf(trimmed) || isGeminiServerSideToolSignature(trimmed)
        if (!hasMidPadding && !isRealGeminiProtobuf) {
            return trimmed
        }
        val segments = BASE64_SEGMENT_REGEX.findAll(trimmed)
            .map { it.value }
            .filter { it.length >= 16 }
            .toList()
        if (segments.isEmpty()) return ""
        if (forFunctionCall) {
            val validNonServer = segments.firstOrNull { seg ->
                isCompleteGeminiSignatureProtobuf(seg) && !isGeminiServerSideToolSignature(seg)
            }
            if (validNonServer != null) return validNonServer
            val anyNonServer = segments.firstOrNull { seg ->
                !isGeminiServerSideToolSignature(seg) && runCatching { Base64.decode(seg) }.isSuccess
            }
            return anyNonServer.orEmpty()
        } else {
            val lastNonServer = segments.lastOrNull { seg ->
                isCompleteGeminiSignatureProtobuf(seg) && !isGeminiServerSideToolSignature(seg)
            }
            if (lastNonServer != null) return lastNonServer
            return segments.lastOrNull { runCatching { Base64.decode(it) }.isSuccess }.orEmpty()
        }
    }

    internal fun isValidOpenAiEncryptedReasoning(rawSig: String, savedProtocol: ProviderProtocol? = null): Boolean {
        if (savedProtocol != null && savedProtocol != ProviderProtocol.OPENAI_RESPONSES) return false
        val trimmed = rawSig.trim()
        if (trimmed.isEmpty() || trimmed.contains('=') || trimmed.contains(' ')) return false
        if (isCompleteGeminiSignatureProtobuf(trimmed) || isGeminiServerSideToolSignature(trimmed)) return false
        return true
    }

    internal fun isValidAnthropicSignature(rawSig: String, savedProtocol: ProviderProtocol?): Boolean {
        if (savedProtocol != null && savedProtocol != ProviderProtocol.ANTHROPIC_MESSAGES) return false
        val trimmed = rawSig.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("gAAAAAB")) return false
        val hasMidPadding = trimmed.indexOf('=') in 0 until trimmed.trimEnd('=').length
        if (hasMidPadding) return false
        return !isCompleteGeminiSignatureProtobuf(trimmed) && !isGeminiServerSideToolSignature(trimmed)
    }

    /**
     * Appends an incoming signature chunk to `builder` while preventing distinct complete Base64
     * signatures (e.g. ending in `=` padding or starting a new Gemini `0x12 ...` protobuf) from
     * being concatenated into an invalid Franken-signature.
     */
    internal fun appendSignatureChunkSafe(builder: StringBuilder, incoming: String): String {
        if (incoming.isEmpty()) return ""
        val cur = builder.toString()
        if (cur == incoming || cur.endsWith(incoming)) return ""
        if (cur.isEmpty()) {
            builder.append(incoming)
            return incoming
        }
        val curEndsWithPadding = cur.endsWith('=')
        val bothAreCompleteGeminiSigs = isCompleteGeminiSignatureProtobuf(cur) && isCompleteGeminiSignatureProtobuf(incoming)
        if (curEndsWithPadding || bothAreCompleteGeminiSigs) {
            if (isGeminiServerSideToolSignature(incoming) && !isGeminiServerSideToolSignature(cur)) {
                return ""
            }
            builder.setLength(0)
            builder.append(incoming)
            return incoming
        }
        builder.append(incoming)
        return incoming
    }

    internal fun groupToolCallsIntoStepBatches(
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
            val startsNewBatch = idx == 0 || tc.thoughtSignature.isNotBlank() || tc.precedingThinking.isNotBlank()
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

    internal fun compactHistoricalToolResultForWire(
        rawResult: String,
        toolName: String,
        isOlderAssistantTurn: Boolean,
        callIndexInTurn: Int,
        totalCallsInTurn: Int
    ): String {
        val shouldCompact = (isOlderAssistantTurn && rawResult.length > 900) ||
            (totalCallsInTurn > 10 && callIndexInTurn < totalCallsInTurn - 6 && rawResult.length > 900)
        if (!shouldCompact) return rawResult
        return WorkspaceHarness.compactStaleToolOutputText(
            stepIndex = 0,
            totalSteps = 100,
            toolName = toolName,
            rawOutput = rawResult,
            keepRecentCount = 0
        )
    }

    internal fun buildFoldedToolHistoryText(
        toolCalls: List<ToolCallRecord>,
        thinkingSummary: String,
        cleanText: String
    ): String = buildString {
        if (thinkingSummary.isNotBlank()) {
            appendLine("[Completed reasoning: ${thinkingSummary.take(600).trim()}]")
        }
        if (toolCalls.isNotEmpty()) {
            appendLine("[Completed ${toolCalls.size} workspace tool call(s):")
            for (tc in toolCalls.takeLast(12)) {
                val snippet = tc.resultText.replace(Regex("\\s+"), " ").trim().take(180)
                appendLine("- ${tc.name}(${tc.argumentsJson.take(80)}) -> $snippet")
            }
            appendLine("]")
        }
        if (cleanText.isNotBlank()) {
            if (isNotEmpty()) appendLine()
            append(cleanText)
        } else if (isEmpty()) {
            append("...")
        }
    }.trim()

    private suspend fun extractMarkdownVideosFromText(
        text: String,
        aspectRatio: String,
        onMedia: suspend (GeneratedMediaItem) -> Unit,
        sessionId: String = ""
    ) {
        val urlRegex = Regex("""https?://[^\s)"'>]+\.mp4(?:\?[^\s)"'>]*)?""", RegexOption.IGNORE_CASE)
        for (match in urlRegex.findAll(text)) {
            val videoUrl = match.value.trim()
            val now = PlatformBridge.currentTimeMillis()
            val videoBytes = runCatching { httpClient.get(videoUrl).readRawBytes() }.getOrElse { ByteArray(0) }
            val localPath = if (videoBytes.isNotEmpty()) {
                PlatformBridge.saveGeneratedMediaFile("video-$now.mp4", videoBytes, sessionId = sessionId)
            } else ""
            onMedia(
                GeneratedMediaItem(
                    id = "vid-$now",
                    kind = MediaKind.VIDEO,
                    mimeType = "video/mp4",
                    localFilePath = localPath,
                    remoteUrl = videoUrl,
                    aspectRatio = aspectRatio
                )
            )
        }
    }

    private suspend fun saveAndEmitImage(
        base64Data: String,
        remoteUrl: String,
        mimeType: String,
        aspectRatio: String,
        revisedPrompt: String,
        onMedia: suspend (GeneratedMediaItem) -> Unit,
        sessionId: String = ""
    ) {
        val now = PlatformBridge.currentTimeMillis()
        val ext = when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            mimeType.contains("webp") -> "webp"
            else -> "png"
        }

        val imageBytes: ByteArray = when {
            base64Data.isNotBlank() -> {
                val cleanB64 = base64Data.substringAfter("base64,").replace("\n", "").replace("\r", "").trim()
                runCatching { Base64.decode(cleanB64) }.getOrElse { ByteArray(0) }
            }
            remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://") -> {
                runCatching { httpClient.get(remoteUrl).readRawBytes() }.getOrElse { ByteArray(0) }
            }
            else -> ByteArray(0)
        }

        if (imageBytes.isEmpty() && remoteUrl.isBlank()) return

        val localPath = if (imageBytes.isNotEmpty()) {
            PlatformBridge.saveGeneratedMediaFile("img-$now-${kotlin.random.Random.nextInt(100, 999)}.$ext", imageBytes, sessionId = sessionId)
        } else ""

        onMedia(
            GeneratedMediaItem(
                id = "media-$now",
                kind = MediaKind.IMAGE,
                mimeType = mimeType,
                localFilePath = localPath,
                remoteUrl = remoteUrl,
                revisedPrompt = revisedPrompt,
                aspectRatio = aspectRatio
            )
        )
    }

    private suspend fun extractMarkdownImagesFromText(
        text: String,
        aspectRatio: String,
        onMedia: suspend (GeneratedMediaItem) -> Unit,
        sessionId: String = ""
    ) {
        if (!text.contains("![")) return
        val regex = Regex("""!\[[^\]]*\]\(([^)]+)\)""")
        for (match in regex.findAll(text)) {
            val target = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (target.startsWith("data:image/")) {
                val mime = target.substringAfter("data:").substringBefore(";").ifBlank { "image/png" }
                val b64 = target.substringAfter("base64,")
                saveAndEmitImage(b64, "", mime, aspectRatio, "", onMedia, sessionId = sessionId)
            } else if (target.startsWith("http://") || target.startsWith("https://")) {
                saveAndEmitImage("", target, "image/png", aspectRatio, "", onMedia, sessionId = sessionId)
            }
        }
    }

    private fun resolveOpenAiImageSize(aspectRatio: String, resolution: String): String {
        if (resolution.contains("x")) return resolution
        return when (aspectRatio) {
            "16:9", "3:2", "4:3" -> "1536x1024"
            "9:16", "2:3", "3:4" -> "1024x1536"
            "1:1" -> "1024x1024"
            else -> "auto"
        }
    }

    private fun resolveSeedreamImageSize(aspectRatio: String, resolution: String): String {
        if (resolution.contains("x")) return resolution
        val is2K = resolution == "2K" || resolution == "4K"
        return when (aspectRatio) {
            "16:9" -> if (is2K) "2560x1440" else "1280x720"
            "9:16" -> if (is2K) "1440x2560" else "720x1280"
            "4:3" -> if (is2K) "2304x1728" else "1152x864"
            "3:4" -> if (is2K) "1728x2304" else "864x1152"
            else -> if (is2K) "2048x2048" else "1024x1024"
        }
    }

    // ============================================================================================
    // SHARED HELPERS: System Prompt Compiler, Cache-Preserving Author's Note Injector,
    // Auth Header Builder, SSE Stream Reader, and Raw JSON Merger.
    // ============================================================================================
    private fun buildCompiledSystemPrompt(
        session: ChatSession,
        activeGoalState: GoalTrackerState? = null
    ): String {
        val sb = StringBuilder()
        val charName = session.activeCard?.name?.ifBlank { "Character" } ?: "Character"
        fun expandMacros(text: String): String =
            text.replace("{{char}}", charName, ignoreCase = true)
                .replace("{{user}}", session.userName, ignoreCase = true)

        if (session.systemPrompt.isNotBlank()) {
            sb.appendLine(expandMacros(session.systemPrompt.trim()))
            sb.appendLine()
        }
        session.activeCard?.let { card ->
            val compiledCard = card.buildCompiledInstructions(session.userName)
            if (compiledCard.isNotBlank()) {
                sb.appendLine(compiledCard)
                sb.appendLine()
            }
        }

        // Composable Context Fragments & Card Lorebook
        val allFragments = session.fragments + session.activeCard?.embeddedFragments.orEmpty()
        if (allFragments.isNotEmpty()) {
            val recentWindowText = session.messages.takeLast(8)
                .joinToString("\n") { it.activeVariant.content }
            val triggeredFragments = allFragments.filter { frag ->
                when (frag.mode) {
                    io.parchment.ai.model.FragmentActivationMode.PINNED -> true
                    io.parchment.ai.model.FragmentActivationMode.MUTED -> false
                    io.parchment.ai.model.FragmentActivationMode.KEYWORD -> {
                        frag.keywords.isEmpty() || frag.keywords.any { kw ->
                            kw.isNotBlank() && recentWindowText.contains(kw.trim(), ignoreCase = true)
                        }
                    }
                }
            }
            if (triggeredFragments.isNotEmpty()) {
                sb.appendLine("### Context Fragments & World Knowledge")
                for (frag in triggeredFragments) {
                    sb.appendLine("#### ${frag.title}")
                    sb.appendLine(expandMacros(frag.content.trim()))
                    sb.appendLine()
                }
            }
        }

        // Rolling Story Summary / Continuity Memory
        if (session.storySummary.isNotBlank()) {
            sb.appendLine("### Story Summary & Continuity So Far")
            sb.appendLine(expandMacros(session.storySummary.trim()))
            sb.appendLine()
        }

        if (session.workspaceMode != io.parchment.ai.model.WorkspaceMode.OFF && session.workspacePath.isNotBlank()) {
            sb.appendLine("### Local Workspace Agent Harness")
            sb.appendLine("You have direct access to the local workspace directory (`${session.workspacePath}`) via native tools (`fs_list`, `fs_read`, `fs_search`${if (session.workspaceMode == io.parchment.ai.model.WorkspaceMode.READ_WRITE) ", `fs_write`, `fs_edit`, `agent_memory`, `shell_exec`" else ""}, `todo_write`, `goal_complete`, `dice_roll`). Inspect files with `fs_read` before editing, keep edits surgical with `fs_edit`, and record durable project/story conventions with `agent_memory`. For multi-step tasks, track your steps with `todo_write` and verify completion with `goal_complete`.")
            val discoveredContext = WorkspaceHarness.discoverWorkspaceContextFiles(session.workspacePath)
            if (discoveredContext.isNotBlank()) {
                sb.appendLine()
                sb.appendLine(discoveredContext)
            }
        }

        if (activeGoalState != null) {
            sb.appendLine()
            sb.appendLine("### Autonomous Goal & Verification Mode")
            sb.appendLine("Active Goal Objective: ${activeGoalState.goalObjective}")
            sb.appendLine("1. Plan: Immediately call `todo_write` to break the objective into concrete, verifiable steps (mark step 1 `in_progress`).")
            sb.appendLine("2. Execute & Verify: Complete each step thoroughly. After verifying a step, call `todo_write` to mark it `completed` and set the next step `in_progress`.")
            sb.appendLine("3. Complete: Do NOT stop early while steps remain `pending` or `in_progress`. Only when 100% of acceptance criteria are verified, call `goal_complete`.")
        }
        return sb.toString().trim()
    }

    /**
     * Injects the session's Author's Note / Post-History Instructions at `session.authorsNoteDepth`
     * turns from the bottom of the conversation history so the top prefix remains 100% static
     * for maximum Prompt Cache hit rates.
     */
    private fun injectAuthorsNoteIntoHistory(
        nodes: List<MessageNode>,
        session: ChatSession
    ): List<Pair<MessageRole, String>> {
        val baseList = nodes.map { node ->
            val v = node.activeVariant
            val baseContent = if (node.role == MessageRole.ASSISTANT) {
                stripInlineErrorNotices(v.content)
            } else {
                v.content
            }
            val textAttachmentsBlock = v.attachments.filter { it.textContent.isNotBlank() }
                .joinToString("\n\n") { att ->
                    "[Attached File: ${att.fileName}]\n```\n${att.textContent}\n```"
                }
            val combinedText = if (textAttachmentsBlock.isNotBlank()) {
                if (baseContent.isNotBlank()) "$baseContent\n\n$textAttachmentsBlock" else textAttachmentsBlock
            } else {
                baseContent
            }
            node.role to combinedText
        }.toMutableList()
        val combinedNote = buildString {
            val cardPost = session.activeCard?.postHistoryInstructions?.trim().orEmpty()
            if (cardPost.isNotEmpty()) appendLine(cardPost)
            if (session.authorsNote.isNotBlank()) appendLine(session.authorsNote.trim())
        }.trim()

        if (combinedNote.isBlank() || baseList.isEmpty()) return baseList

        val depth = session.authorsNoteDepth.coerceIn(0, baseList.size)
        val targetIdx = (baseList.size - 1 - depth).coerceIn(0, baseList.size - 1)
        val (targetRole, targetContent) = baseList[targetIdx]
        baseList[targetIdx] = targetRole to "$targetContent\n\n[Author's Note / Scene Direction: $combinedNote]"
        return baseList
    }

    private fun buildUrlWithAuthQuery(profile: ProviderProfile, rawUrl: String): String {
        if (profile.authHeaderMode == AuthHeaderMode.QUERY_PARAM && profile.apiKey.isNotBlank()) {
            val sep = if (rawUrl.contains("?")) "&" else "?"
            return "$rawUrl${sep}key=${profile.apiKey.trim()}"
        }
        return rawUrl
    }

    private fun HttpRequestBuilder.applyAuthAndCustomHeaders(profile: ProviderProfile) {
        val key = profile.apiKey.trim()
        if (key.isNotEmpty()) {
            when (profile.authHeaderMode) {
                AuthHeaderMode.BEARER_ONLY -> {
                    header("Authorization", "Bearer $key")
                }
                AuthHeaderMode.QUERY_PARAM -> {
                    // Already appended to URL query string
                }
                AuthHeaderMode.NATIVE -> {
                    when (profile.protocol) {
                        ProviderProtocol.GEMINI_INTERACTIONS -> {
                            header("x-goog-api-key", key)
                            // Custom proxies (e.g. api.zai.is, NewAPI, LiteLLM) using sk-* keys often also check Bearer
                            if (!profile.baseUrl.contains("googleapis.com", ignoreCase = true) || key.startsWith("sk-")) {
                                header("Authorization", "Bearer $key")
                            }
                        }
                        ProviderProtocol.OPENAI_RESPONSES -> header("Authorization", "Bearer $key")
                        ProviderProtocol.ANTHROPIC_MESSAGES -> {
                            header("x-api-key", key)
                            if (!profile.baseUrl.contains("anthropic.com", ignoreCase = true)) {
                                header("Authorization", "Bearer $key")
                            }
                        }
                    }
                }
            }
        }

        if (profile.customHeadersRaw.isNotBlank()) {
            profile.customHeadersRaw.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains(":")) {
                    val k = trimmed.substringBefore(":").trim()
                    val v = trimmed.substringAfter(":").trim()
                    if (k.isNotEmpty()) {
                        header(k, v)
                    }
                }
            }
        }
    }

    private fun mergeRawJsonExtraBody(base: JsonObject, rawExtraJson: String): JsonObject {
        val trimmed = rawExtraJson.trim()
        if (trimmed.isEmpty()) return base
        val extraObj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return base
        return deepMergeJsonObjects(base, extraObj)
    }

    private fun deepMergeJsonObjects(target: JsonObject, overrideObj: JsonObject): JsonObject {
        val merged = target.toMutableMap()
        for ((k, v) in overrideObj) {
            val existing = merged[k]
            merged[k] = if (existing is JsonObject && v is JsonObject) {
                deepMergeJsonObjects(existing, v)
            } else {
                v
            }
        }
        return JsonObject(merged)
    }

    private suspend fun readSseOrJsonFallback(
        channel: ByteReadChannel,
        onSseEvent: suspend (eventType: String, data: String) -> Unit,
        onPlainJsonFallback: suspend (fullJson: String) -> Unit
    ) {
        var currentEvent = ""
        val dataLines = mutableListOf<String>()
        val rawFallbackBuffer = StringBuilder()
        var sawSseLine = false

        while (!channel.isClosedForRead) {
            // Adaptive per-line SSE idle watchdog:
            // - 90 seconds once SSE lines have already started arriving (catches half-open proxy/CDN mid-sentence stalls!)
            // - 300 seconds before the first SSE line arrives (allows deep pre-token reasoning on slow endpoints)
            val perLineTimeoutMs = if (sawSseLine) 90_000L else 300_000L
            val line = withTimeoutOrNull(perLineTimeoutMs) {
                channel.readLine()
            } ?: if (channel.isClosedForRead) {
                break
            } else {
                error("SSE stream timed out waiting for next event after ${perLineTimeoutMs / 1000}s (connection stalled).")
            }
            when {
                line.startsWith("event:") -> {
                    sawSseLine = true
                    if (dataLines.isNotEmpty()) {
                        val combined = dataLines.joinToString("\n")
                        dataLines.clear()
                        onSseEvent(currentEvent, combined)
                    }
                    currentEvent = line.removePrefix("event:").trim()
                }
                line.startsWith("data:") -> {
                    sawSseLine = true
                    val dataVal = line.removePrefix("data:").trim()
                    if (dataVal == "[DONE]") {
                        if (dataLines.isNotEmpty()) {
                            onSseEvent(currentEvent, dataLines.joinToString("\n"))
                            dataLines.clear()
                        }
                        onSseEvent(currentEvent, "[DONE]")
                        currentEvent = ""
                    } else if (dataLines.isEmpty() && dataVal.startsWith("{") && dataVal.endsWith("}")) {
                        // Fast path: complete standalone JSON object on a single data: line — emit immediately
                        // without waiting for a trailing blank line (prevents proxy buffering & adjacent-record drops!)
                        onSseEvent(currentEvent, dataVal)
                        currentEvent = ""
                    } else {
                        dataLines.add(dataVal)
                    }
                }
                line.isBlank() -> {
                    if (dataLines.isNotEmpty()) {
                        val combinedData = dataLines.joinToString("\n")
                        dataLines.clear()
                        onSseEvent(currentEvent, combinedData)
                        currentEvent = ""
                    }
                }
                else -> {
                    if (!sawSseLine) {
                        rawFallbackBuffer.appendLine(line)
                    }
                }
            }
        }

        if (dataLines.isNotEmpty()) {
            onSseEvent(currentEvent, dataLines.joinToString("\n"))
        } else if (!sawSseLine && rawFallbackBuffer.isNotBlank()) {
            onPlainJsonFallback(rawFallbackBuffer.toString().trim())
        }
    }

    // ============================================================================================
    // UNIVERSAL LINE-GROUNDED WEB SOURCE CITATION ENGINE
    // Extracts citations across Gemini, OpenAI Responses, OpenAI Chat Completions, Anthropic Messages,
    // and local `web_fetch` tool executions, and binds each source to the exact line(s) it grounds!
    // ============================================================================================
    internal data class RawWebCitation(
        val url: String,
        val title: String = "",
        val snippet: String = "",
        val groundedText: String = "",
        val startIndex: Int = -1,
        val endIndex: Int = -1
    )

    internal fun extractDomainFromUrl(rawUrl: String): String {
        val withoutScheme = rawUrl.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
        return withoutScheme.substringBefore("/").substringBefore("?").substringBefore("#").lowercase()
    }

    internal fun extractCitationsFromToolCall(tc: ToolCallRecord): List<RawWebCitation> {
        if (tc.isError) return emptyList()
        if (tc.name.equals("web_search", ignoreCase = true)) {
            val results = mutableListOf<RawWebCitation>()
            val lines = tc.resultText.lines().map { it.trim() }
            val itemLinkRegex = Regex("""\*\*\[([^\]]+)\]\((https?://[^\s)]+)\)\*\*""")
            for ((idx, line) in lines.withIndex()) {
                val match = itemLinkRegex.find(line) ?: continue
                val title = match.groupValues[1].trim()
                val url = match.groupValues[2].trim()
                val snippet = lines.drop(idx + 1).take(3)
                    .firstOrNull { it.startsWith("Snippet:", ignoreCase = true) }
                    ?.removePrefix("Snippet:")?.removePrefix("snippet:")?.trim().orEmpty()
                if (url.isNotBlank()) {
                    results.add(RawWebCitation(url = url, title = title, snippet = snippet))
                }
            }
            return results
        }
        if (!tc.name.equals("web_fetch", ignoreCase = true)) return emptyList()
        val argObj = runCatching { json.parseToJsonElement(tc.argumentsJson).jsonObject }.getOrNull()
        val url = argObj?.get("url")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) return emptyList()
        val lines = tc.resultText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val titleLine = lines.firstOrNull { it.startsWith("# ") || it.startsWith("Title:", ignoreCase = true) }
            ?.removePrefix("# ")?.removePrefix("Title:")?.removePrefix("title:")?.trim()
            ?: extractDomainFromUrl(url)
        val snippetLine = lines.firstOrNull {
            !it.startsWith("URL:", ignoreCase = true) &&
                !it.startsWith("Title:", ignoreCase = true) &&
                !it.startsWith("#") &&
                it.length > 24
        }?.take(180).orEmpty()
        return listOf(RawWebCitation(url = url, title = titleLine, snippet = snippetLine))
    }

    internal fun extractCitationFromToolCall(tc: ToolCallRecord): RawWebCitation? =
        extractCitationsFromToolCall(tc).firstOrNull()

    internal fun extractMarkdownLinksAsCitations(text: String): List<RawWebCitation> {
        if (!text.contains("http")) return emptyList()
        val out = mutableListOf<RawWebCitation>()
        val mdLinkRegex = Regex("""\[([^\]]+)\]\((https?://[^\s)]+)\)""")
        for (match in mdLinkRegex.findAll(text)) {
            val label = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val url = match.groupValues.getOrNull(2)?.trim().orEmpty()
            if (url.isNotBlank() && !url.endsWith(".png", ignoreCase = true) &&
                !url.endsWith(".jpg", ignoreCase = true) && !url.endsWith(".mp4", ignoreCase = true)
            ) {
                out.add(
                    RawWebCitation(
                        url = url,
                        title = label.ifBlank { extractDomainFromUrl(url) },
                        startIndex = match.range.first,
                        endIndex = match.range.last + 1
                    )
                )
            }
        }
        return out
    }

    private suspend fun extractCitationsFromGeminiCandidate(
        candidateObj: JsonObject?,
        onCitation: suspend (RawWebCitation) -> Unit
    ) {
        if (candidateObj == null) return
        val gm = (candidateObj["groundingMetadata"] as? JsonObject)
            ?: (candidateObj["grounding_metadata"] as? JsonObject) ?: return
        val chunks = (gm["groundingChunks"] as? JsonArray) ?: (gm["grounding_chunks"] as? JsonArray)
        val chunkList = mutableListOf<Pair<String, String>>() // url to title
        chunks?.forEach { chEl ->
            val chObj = chEl as? JsonObject ?: return@forEach
            val webObj = (chObj["web"] as? JsonObject) ?: chObj
            val uri = webObj["uri"]?.jsonPrimitive?.contentOrNull
                ?: webObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
            val title = webObj["title"]?.jsonPrimitive?.contentOrNull ?: ""
            chunkList.add(uri to title)
            if (uri.isNotBlank()) {
                onCitation(RawWebCitation(url = uri, title = title))
            }
        }
        val supports = (gm["groundingSupports"] as? JsonArray) ?: (gm["grounding_supports"] as? JsonArray)
        supports?.forEach { supEl ->
            val supObj = supEl as? JsonObject ?: return@forEach
            val segObj = supObj["segment"] as? JsonObject
            val segText = segObj?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            val startIdx = segObj?.get("startIndex")?.jsonPrimitive?.intOrNull
                ?: segObj?.get("start_index")?.jsonPrimitive?.intOrNull ?: -1
            val endIdx = segObj?.get("endIndex")?.jsonPrimitive?.intOrNull
                ?: segObj?.get("end_index")?.jsonPrimitive?.intOrNull ?: -1
            val indices = (supObj["groundingChunkIndices"] as? JsonArray)
                ?: (supObj["grounding_chunk_indices"] as? JsonArray)
            indices?.forEach { idxEl ->
                val cIdx = idxEl.jsonPrimitive.intOrNull ?: return@forEach
                val pair = chunkList.getOrNull(cIdx) ?: return@forEach
                if (pair.first.isNotBlank()) {
                    onCitation(
                        RawWebCitation(
                            url = pair.first,
                            title = pair.second,
                            groundedText = segText,
                            startIndex = startIdx,
                            endIndex = endIdx
                        )
                    )
                }
            }
        }
    }

    private suspend fun extractCitationsFromGeminiObject(
        obj: JsonObject?,
        onCitation: suspend (RawWebCitation) -> Unit
    ) {
        if (obj == null) return
        extractCitationsFromGeminiCandidate(obj, onCitation)
        val cType = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (cType == "google_search_result" || cType == "url_context_result" || cType == "web_search_result") {
            val directUrl = obj["url"]?.jsonPrimitive?.contentOrNull
                ?: obj["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val directTitle = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (directUrl.isNotBlank()) {
                onCitation(RawWebCitation(url = directUrl, title = directTitle))
            }
            val resultsArr = (obj["results"] as? JsonArray) ?: (obj["content"] as? JsonArray)
            resultsArr?.forEach { rEl ->
                val rObj = rEl as? JsonObject ?: return@forEach
                val u = rObj["url"]?.jsonPrimitive?.contentOrNull
                    ?: rObj["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val t = rObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val s = rObj["snippet"]?.jsonPrimitive?.contentOrNull
                    ?: rObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (u.isNotBlank()) {
                    onCitation(RawWebCitation(url = u, title = t, snippet = s.take(180)))
                }
            }
        }
        val annotations = (obj["annotations"] as? JsonArray) ?: (obj["citations"] as? JsonArray)
        annotations?.forEach { aEl ->
            val aObj = aEl as? JsonObject ?: return@forEach
            val u = aObj["url"]?.jsonPrimitive?.contentOrNull
                ?: aObj["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val t = aObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val st = aObj["start_index"]?.jsonPrimitive?.intOrNull
                ?: aObj["startIndex"]?.jsonPrimitive?.intOrNull ?: -1
            val en = aObj["end_index"]?.jsonPrimitive?.intOrNull
                ?: aObj["endIndex"]?.jsonPrimitive?.intOrNull ?: -1
            if (u.isNotBlank()) {
                onCitation(RawWebCitation(url = u, title = t, startIndex = st, endIndex = en))
            }
        }
        (obj["content"] as? JsonArray)?.forEach { cEl ->
            extractCitationsFromGeminiObject(cEl as? JsonObject, onCitation)
        }
    }

    private suspend fun extractCitationFromOpenAiAnnotation(
        ann: JsonObject,
        onCitation: suspend (RawWebCitation) -> Unit
    ) {
        val sub = (ann["url_citation"] as? JsonObject) ?: ann
        val url = sub["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (url.isBlank()) return
        val title = sub["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val startIdx = sub["start_index"]?.jsonPrimitive?.intOrNull ?: -1
        val endIdx = sub["end_index"]?.jsonPrimitive?.intOrNull ?: -1
        onCitation(RawWebCitation(url = url, title = title, startIndex = startIdx, endIndex = endIdx))
    }

    private suspend fun extractCitationsFromOpenAiResponsesItem(
        item: JsonObject,
        onCitation: suspend (RawWebCitation) -> Unit
    ) {
        val itemType = item["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (itemType == "web_search_call") {
            val actionObj = item["action"] as? JsonObject
            val sourcesArr = (actionObj?.get("sources") as? JsonArray)
                ?: (item["sources"] as? JsonArray)
                ?: (item["results"] as? JsonArray)
            sourcesArr?.forEach { sEl ->
                val sObj = sEl as? JsonObject ?: return@forEach
                val u = sObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val t = sObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (u.isNotBlank()) {
                    onCitation(RawWebCitation(url = u, title = t))
                }
            }
        }
        val contentArr = item["content"] as? JsonArray
        contentArr?.forEach { cEl ->
            val cObj = cEl as? JsonObject ?: return@forEach
            val cTxt = cObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val anns = cObj["annotations"] as? JsonArray
            anns?.forEach { aEl ->
                val aObj = aEl as? JsonObject ?: return@forEach
                val sub = (aObj["url_citation"] as? JsonObject) ?: aObj
                val u = sub["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val t = sub["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val st = sub["start_index"]?.jsonPrimitive?.intOrNull ?: -1
                val en = sub["end_index"]?.jsonPrimitive?.intOrNull ?: -1
                if (u.isNotBlank()) {
                    onCitation(RawWebCitation(url = u, title = t, groundedText = if (st < 0) cTxt else "", startIndex = st, endIndex = en))
                }
            }
        }
    }

    private suspend fun extractCitationsFromChatCompletionsObject(
        obj: JsonObject,
        onCitation: suspend (RawWebCitation) -> Unit
    ) {
        val anns = obj["annotations"] as? JsonArray
        anns?.forEach { aEl ->
            val aObj = aEl as? JsonObject ?: return@forEach
            extractCitationFromOpenAiAnnotation(aObj, onCitation)
        }
        val citations = obj["citations"] as? JsonArray
        citations?.forEach { cEl ->
            when (cEl) {
                is JsonPrimitive -> {
                    val u = cEl.contentOrNull?.trim().orEmpty()
                    if (u.startsWith("http")) onCitation(RawWebCitation(url = u))
                }
                is JsonObject -> {
                    val u = cEl["url"]?.jsonPrimitive?.contentOrNull
                        ?: cEl["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val t = cEl["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (u.isNotBlank()) onCitation(RawWebCitation(url = u, title = t))
                }
                else -> {}
            }
        }
    }

    private suspend fun extractCitationFromAnthropicCitationObj(
        citObj: JsonObject,
        blockText: String,
        onCitation: suspend (RawWebCitation) -> Unit
    ) {
        val u = citObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (u.isBlank()) return
        val t = citObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val cited = citObj["cited_text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        onCitation(
            RawWebCitation(
                url = u,
                title = t,
                snippet = cited.take(180),
                groundedText = blockText.ifBlank { cited }
            )
        )
    }

    private suspend fun extractCitationsFromAnthropicBlock(
        cb: JsonObject,
        onCitation: suspend (RawWebCitation) -> Unit
    ) {
        val bType = cb["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (bType == "web_search_tool_result") {
            val contentArr = cb["content"] as? JsonArray
            contentArr?.forEach { rEl ->
                val rObj = rEl as? JsonObject ?: return@forEach
                val u = rObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val t = rObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (u.isNotBlank()) {
                    onCitation(RawWebCitation(url = u, title = t))
                }
            }
        }
        val blockTxt = cb["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val citationsArr = cb["citations"] as? JsonArray
        citationsArr?.forEach { cEl ->
            val cObj = cEl as? JsonObject ?: return@forEach
            extractCitationFromAnthropicCitationObj(cObj, blockTxt, onCitation)
        }
    }

    /**
     * Deduplicates raw web citations and binds each source to the exact 1-based line number(s)
     * and line excerpt(s) in `assistantContent` that it grounds!
     */
    internal fun bindSourcesToMessageLines(
        assistantContent: String,
        rawCitations: List<RawWebCitation>
    ): List<WebSourceCitation> {
        if (rawCitations.isEmpty()) return emptyList()

        data class IndexedLine(
            val lineNumber: Int,
            val startChar: Int,
            val endChar: Int,
            val cleanText: String
        )

        val indexedLines = mutableListOf<IndexedLine>()
        var cursor = 0
        var nonBlankCounter = 0
        for (rawLine in assistantContent.split('\n')) {
            val start = cursor
            val end = cursor + rawLine.length
            cursor = end + 1
            val trimmed = rawLine.trim()
                .removePrefix("- ")
                .removePrefix("* ")
                .replace(Regex("^#+\\s*"), "")
                .trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("```")) {
                nonBlankCounter++
                indexedLines.add(
                    IndexedLine(
                        lineNumber = nonBlankCounter,
                        startChar = start,
                        endChar = end,
                        cleanText = trimmed
                    )
                )
            }
        }

        // Group by normalized URL while merging titles, snippets, groundedText, and character spans
        val groupedByUrl = LinkedHashMap<String, MutableList<RawWebCitation>>()
        for (cit in rawCitations) {
            val normUrl = cit.url.trim()
            if (normUrl.isEmpty()) continue
            groupedByUrl.getOrPut(normUrl) { mutableListOf() }.add(cit)
        }

        val stopWords = setOf(
            "the", "and", "for", "with", "that", "this", "from", "are", "was", "were",
            "have", "has", "had", "not", "but", "what", "when", "where", "which", "who",
            "how", "why", "can", "will", "into", "your", "more", "about", "http", "https", "www", "com", "org", "io", "dev"
        )

        fun extractSignificantTokens(s: String): Set<String> =
            s.lowercase()
                .split(Regex("[^a-z0-9.]+"))
                .map { it.trim('.') }
                .filter { it.length >= 3 && it !in stopWords }
                .toSet()

        return groupedByUrl.entries.mapIndexed { idx, (url, group) ->
            val citationIndex = idx + 1
            val domain = extractDomainFromUrl(url)
            val bestTitle = group.firstNotNullOfOrNull { it.title.takeIf { t -> t.isNotBlank() } }
                ?: domain
            val bestSnippet = group.firstNotNullOfOrNull { it.snippet.takeIf { s -> s.isNotBlank() } }.orEmpty()

            val matchedLines = LinkedHashMap<Int, String>()

            if (indexedLines.isNotEmpty()) {
                // 1. Direct character-offset or segment-text match from provider grounding metadata
                for (g in group) {
                    if (g.startIndex >= 0 || g.endIndex > 0) {
                        val targetPos = if (g.startIndex >= 0) g.startIndex else g.endIndex
                        val hit = indexedLines.firstOrNull { targetPos in it.startChar..it.endChar }
                            ?: indexedLines.minByOrNull { kotlin.math.abs(it.startChar - targetPos) }
                        if (hit != null) {
                            matchedLines[hit.lineNumber] = hit.cleanText
                        }
                    }
                    if (g.groundedText.isNotBlank()) {
                        val segClean = g.groundedText.trim()
                        for (line in indexedLines) {
                            if (line.cleanText.contains(segClean, ignoreCase = true) ||
                                segClean.contains(line.cleanText, ignoreCase = true)
                            ) {
                                matchedLines[line.lineNumber] = line.cleanText
                            }
                        }
                    }
                }

                // 2. Inline URL, domain, or [index] citation marker match
                if (matchedLines.isEmpty()) {
                    val domainRoot = domain.substringBefore(".")
                    for (line in indexedLines) {
                        val lowerLine = line.cleanText.lowercase()
                        if (lowerLine.contains(url.lowercase()) ||
                            (domain.isNotBlank() && lowerLine.contains(domain)) ||
                            (domainRoot.length >= 4 && lowerLine.contains(domainRoot)) ||
                            line.cleanText.contains("[$citationIndex]")
                        ) {
                            matchedLines[line.lineNumber] = line.cleanText
                        }
                    }
                }

                // 3. Lexical overlap match between source title/snippet and message lines
                if (matchedLines.isEmpty()) {
                    val sourceTokens = extractSignificantTokens("$bestTitle $bestSnippet $domain")
                    if (sourceTokens.isNotEmpty()) {
                        val scored = indexedLines.map { line ->
                            val lineTokens = extractSignificantTokens(line.cleanText)
                            val overlap = lineTokens.intersect(sourceTokens).size
                            line to overlap
                        }.filter { it.second > 0 }
                            .sortedByDescending { it.second }
                        for ((line, _) in scored.take(2)) {
                            matchedLines[line.lineNumber] = line.cleanText
                        }
                    }
                }

                // 4. Fallback: point to the primary substantive line so every source has a clear line reference
                if (matchedLines.isEmpty()) {
                    val fallbackLine = indexedLines.getOrNull(idx % indexedLines.size) ?: indexedLines.first()
                    matchedLines[fallbackLine.lineNumber] = fallbackLine.cleanText
                }
            }

            WebSourceCitation(
                index = citationIndex,
                url = url,
                title = bestTitle,
                domain = domain,
                groundedLineNumbers = matchedLines.keys.toList(),
                groundedLines = matchedLines.values.map { it.take(180) },
                snippet = bestSnippet
            )
        }
    }
}
