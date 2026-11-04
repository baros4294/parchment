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
import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

sealed class StreamUpdate {
    data class Status(val message: String, val interactionId: String = "") : StreamUpdate()
    data class ThinkingDelta(val delta: String, val signatureDelta: String = "") : StreamUpdate()
    data class ContentDelta(val delta: String) : StreamUpdate()
    data class ToolCallEvent(val toolCall: ToolCallRecord) : StreamUpdate()
    data class GoalProgress(val goalTracker: GoalTrackerState) : StreamUpdate()
    data class MediaGenerated(val item: GeneratedMediaItem) : StreamUpdate()
    data class RawWireSnapshot(val requestJson: String, val latestResponseChunk: String) : StreamUpdate()
    data class Completed(
        val fullContent: String,
        val fullThinking: String,
        val trailingThinking: String = "",
        val thinkingSignature: String,
        val toolCalls: List<ToolCallRecord>,
        val goalTracker: GoalTrackerState?,
        val mediaItems: List<GeneratedMediaItem>,
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
    private val GEMINI_4_REGEX = Regex("""(^|[/:_-])gemini-4(?:\.\d+)?-""")

    internal class StepRetriesExhaustedException(message: String, cause: Throwable) : RuntimeException(message, cause)
    private suspend fun waitWithRetryCountdown(
        attempt: Int,
        maxAttempts: Int,
        waitSeconds: Int = RETRY_WAIT_SECONDS,
        reasonSummary: String,
        onStatus: suspend (String) -> Unit
    ) {
        val cleanReason = reasonSummary.lineSequence().firstOrNull()?.trim()?.take(90).orEmpty()
        for (remaining in waitSeconds downTo 1) {
            onStatus("Auto-retrying ($attempt/$maxAttempts in ${remaining}s) — $cleanReason")
            delay(1000L)
        }
        onStatus("Retrying now (attempt $attempt/$maxAttempts)…")
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

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(this@AiProviderEngine.json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 24 * 60 * 60 * 1000L // 24-hour per-request ceiling for ultra-long reasoning & decodes
            connectTimeoutMillis = 45_000L
            socketTimeoutMillis = 24 * 60 * 60 * 1000L
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
        initialMediaItems: List<GeneratedMediaItem> = emptyList()
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
        var lastInteractionId = ""
        var inputTokens = 0
        var cachedTokens = 0
        var reasoningTokens = 0
        var outputTokens = 0
        var lastRawReq = ""
        var lastRawResp = ""

        fun appendSignatureIfUnique(sig: String): String {
            if (sig.isEmpty()) return ""
            val current = signatureBuilder.toString()
            if (current == sig || current.endsWith(sig)) return ""
            signatureBuilder.append(sig)
            return sig
        }

        fun attachStepThinkingToToolCall(tc: ToolCallRecord): ToolCallRecord {
            val stepThink = currentStepThinkingBuilder.toString().trim()
            currentStepThinkingBuilder.clear()
            return if (tc.precedingThinking.isBlank() && stepThink.isNotEmpty()) {
                tc.copy(precedingThinking = stepThink)
            } else {
                tc
            }
        }
        val initialGoalObjective = extractGoalObjectiveFromHistory(historyNodes)
        val circuitBreaker = WorkspaceHarness.LoopCircuitBreaker(
            initialGoal = if (initialGoalObjective.isNotBlank()) {
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

            while (true) {
                val charsBeforePass = contentBuilder.length
                val thinkBeforePass = thinkingBuilder.length
                val sigBeforePass = signatureBuilder.length
                val toolsBeforePass = executedTools.size
                val mediaBeforePass = generatedMedia.size

                // If continuing a turn that already accumulated thoughts or tool calls (either before clicking
                // '→ Continue' or before a mid-stream network drop), synthesize the partial assistant turn in
                // history so no protocol loses prior thoughts/signatures or re-executes completed tools.
                val (effectiveHistoryNodes, effectiveContinuePrefix) = buildEffectiveHistoryAndPrefix(
                    baseHistoryNodes = activeBaseHistoryNodes,
                    continuePrefix = currentContinuePrefix,
                    thinkingText = thinkingBuilder.toString(),
                    trailingThinkingText = currentStepThinkingBuilder.toString().trim(),
                    signatureText = signatureBuilder.toString(),
                    executedTools = executedTools.toList(),
                    generatedMedia = generatedMedia.toList(),
                    protocol = profile.protocol,
                    model = model,
                    passTag = "pass-$continuationPass"
                )

                try {
                    when (profile.protocol) {
                        ProviderProtocol.GEMINI_INTERACTIONS -> {
                            suspend fun runLegacyGeminiGenerateContent(
                                targetHistoryNodes: List<MessageNode>,
                                targetContinuePrefix: String
                            ) {
                                executeGeminiGenerateContentLoop(
                                    profile = profile,
                                    model = model,
                                    session = session,
                                    historyNodes = targetHistoryNodes,
                                    continuePrefix = targetContinuePrefix,
                                    circuitBreaker = circuitBreaker,
                                    onStatus = { statusMsg ->
                                        emit(StreamUpdate.Status(statusMsg, lastInteractionId))
                                    },
                                    onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                    onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                    onThinking = { delta, sig ->
                                        thinkingBuilder.append(delta)
                                        currentStepThinkingBuilder.append(delta)
                                        val uniqueSig = appendSignatureIfUnique(sig)
                                        if (delta.isNotEmpty() || uniqueSig.isNotEmpty()) {
                                            emit(StreamUpdate.ThinkingDelta(delta, uniqueSig))
                                        }
                                    },
                                    onContent = { delta ->
                                        contentBuilder.append(delta)
                                        val approxOut = (contentBuilder.length / 4).coerceAtLeast(outputTokens)
                                        val elapsedSec = ((PlatformBridge.currentTimeMillis() - startTime) / 1000L).toInt()
                                        PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onMedia = { item ->
                                        onFirstTokenIfZero(firstTokenTime) { firstTokenTime = it }
                                        generatedMedia.add(item)
                                        emit(StreamUpdate.MediaGenerated(item))
                                    },
                                    onToolCall = { tc ->
                                        val enriched = attachStepThinkingToToolCall(tc)
                                        executedTools.add(enriched)
                                        emit(StreamUpdate.ToolCallEvent(enriched))
                                    },
                                    onRawWire = { req, resp ->
                                        lastRawReq = req
                                        lastRawResp = resp
                                        emit(StreamUpdate.RawWireSnapshot(req, resp))
                                    },
                                    onUsage = { inTok, cacheTok, thinkTok, outTok ->
                                        inputTokens = inTok
                                        cachedTokens = cacheTok
                                        reasoningTokens = thinkTok
                                        outputTokens = outTok
                                    }
                                )
                            }
                            // Always attempt the newer /v1beta/interactions protocol first; if it errors out
                            // (or exhausts fast probe retries when useLegacyFallbackEndpoint is ON), seamlessly
                            // hand off any accumulated thoughts/tools/prose to :streamGenerateContent!
                            try {
                                executeGeminiInteractionsLoop(
                                    profile = profile,
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
                                    onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                    onThinking = { delta, sig ->
                                        thinkingBuilder.append(delta)
                                        currentStepThinkingBuilder.append(delta)
                                        val uniqueSig = appendSignatureIfUnique(sig)
                                        if (delta.isNotEmpty() || uniqueSig.isNotEmpty()) {
                                            emit(StreamUpdate.ThinkingDelta(delta, uniqueSig))
                                        }
                                    },
                                    onContent = { delta ->
                                        contentBuilder.append(delta)
                                        val approxOut = (contentBuilder.length / 4).coerceAtLeast(outputTokens)
                                        val elapsedSec = ((PlatformBridge.currentTimeMillis() - startTime) / 1000L).toInt()
                                        PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onMedia = { item ->
                                        onFirstTokenIfZero(firstTokenTime) { firstTokenTime = it }
                                        generatedMedia.add(item)
                                        emit(StreamUpdate.MediaGenerated(item))
                                    },
                                    onToolCall = { tc ->
                                        val enriched = attachStepThinkingToToolCall(tc)
                                        executedTools.add(enriched)
                                        emit(StreamUpdate.ToolCallEvent(enriched))
                                    },
                                    onRawWire = { req, resp ->
                                        lastRawReq = req
                                        lastRawResp = resp
                                        emit(StreamUpdate.RawWireSnapshot(req, resp))
                                    },
                                    onUsage = { id, inTok, cacheTok, thinkTok, outTok ->
                                        if (id.isNotBlank()) lastInteractionId = id
                                        inputTokens = inTok
                                        cachedTokens = cacheTok
                                        reasoningTokens = thinkTok
                                        outputTokens = outTok
                                    }
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                if (shouldFallbackToLegacyProtocol(e, profile.useLegacyFallbackEndpoint)) {
                                    val actualCause = if (e is StepRetriesExhaustedException && e.cause != null) e.cause!! else e
                                    val reasonShort = actualCause.message?.lineSequence()?.firstOrNull()?.trim()?.take(72) ?: "endpoint error"
                                    emit(
                                        StreamUpdate.Status(
                                            "Primary /v1beta/interactions failed ($reasonShort) — falling back to :streamGenerateContent…",
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

                        ProviderProtocol.OPENAI_RESPONSES -> {
                            val lowerModel = model.lowercase()
                            val isByteDanceMediaModel = lowerModel.contains("seedream") ||
                                lowerModel.contains("seedance") ||
                                profile.enableVideoGeneration
                            val isStandaloneImageModel = lowerModel.contains("gpt-image") ||
                                lowerModel.contains("dall-e") ||
                                lowerModel.contains("imagen") ||
                                lowerModel.contains("flux") ||
                                lowerModel.contains("stable-diffusion") ||
                                lowerModel.contains("sdxl") ||
                                lowerModel.contains("ideogram") ||
                                lowerModel.contains("recraft") ||
                                lowerModel.contains("kolors")

                            val isDirectImagesEndpoint = profile.endpointPathOverride.contains("images/generations", ignoreCase = true) ||
                                (profile.enableImageGeneration && isStandaloneImageModel)

                            suspend fun runLegacyOpenAiChatCompletions(
                                targetHistoryNodes: List<MessageNode>,
                                targetContinuePrefix: String
                            ) {
                                executeOpenAiChatCompletionsLoop(
                                    profile = profile,
                                    model = model,
                                    session = session,
                                    historyNodes = targetHistoryNodes,
                                    continuePrefix = targetContinuePrefix,
                                    circuitBreaker = circuitBreaker,
                                    onStatus = { st -> emit(StreamUpdate.Status(st)) },
                                    onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                    onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                    onThinking = { delta ->
                                        thinkingBuilder.append(delta)
                                        currentStepThinkingBuilder.append(delta)
                                        emit(StreamUpdate.ThinkingDelta(delta))
                                    },
                                    onContent = { delta ->
                                        contentBuilder.append(delta)
                                        val approxOut = (contentBuilder.length / 4).coerceAtLeast(outputTokens)
                                        val elapsedSec = ((PlatformBridge.currentTimeMillis() - startTime) / 1000L).toInt()
                                        PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onMedia = { item ->
                                        onFirstTokenIfZero(firstTokenTime) { firstTokenTime = it }
                                        generatedMedia.add(item)
                                        emit(StreamUpdate.MediaGenerated(item))
                                    },
                                    onToolCall = { tc ->
                                        val enriched = attachStepThinkingToToolCall(tc)
                                        executedTools.add(enriched)
                                        emit(StreamUpdate.ToolCallEvent(enriched))
                                    },
                                    onRawWire = { req, resp ->
                                        lastRawReq = req
                                        lastRawResp = resp
                                        emit(StreamUpdate.RawWireSnapshot(req, resp))
                                    },
                                    onUsage = { inTok, cacheTok, thinkTok, outTok ->
                                        inputTokens = inTok
                                        cachedTokens = cacheTok
                                        reasoningTokens = thinkTok
                                        outputTokens = outTok
                                    }
                                )
                            }

                            if (isByteDanceMediaModel) {
                                executeByteDanceArkGeneration(
                                    profile = profile,
                                    model = model,
                                    session = session,
                                    historyNodes = historyNodes,
                                    circuitBreaker = circuitBreaker,
                                    onStatus = { st, id ->
                                        if (id.isNotBlank()) lastInteractionId = id
                                        emit(StreamUpdate.Status(st, lastInteractionId))
                                    },
                                    onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                    onThinking = { delta ->
                                        thinkingBuilder.append(delta)
                                        emit(StreamUpdate.ThinkingDelta(delta))
                                    },
                                    onMedia = { item ->
                                        onFirstTokenIfZero(firstTokenTime) { firstTokenTime = it }
                                        generatedMedia.add(item)
                                        emit(StreamUpdate.MediaGenerated(item))
                                    },
                                    onContent = { delta ->
                                        contentBuilder.append(delta)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onRawWire = { req, resp ->
                                        lastRawReq = req
                                        lastRawResp = resp
                                        emit(StreamUpdate.RawWireSnapshot(req, resp))
                                    }
                                )
                            } else if (isDirectImagesEndpoint) {
                                executeOpenAiDirectImagesEndpoint(
                                    profile = profile,
                                    model = model,
                                    historyNodes = historyNodes,
                                    onStatus = { st -> emit(StreamUpdate.Status(st)) },
                                    onMedia = { item ->
                                        onFirstTokenIfZero(firstTokenTime) { firstTokenTime = it }
                                        generatedMedia.add(item)
                                        emit(StreamUpdate.MediaGenerated(item))
                                    },
                                    onContent = { delta ->
                                        contentBuilder.append(delta)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onRawWire = { req, resp ->
                                        lastRawReq = req
                                        lastRawResp = resp
                                        emit(StreamUpdate.RawWireSnapshot(req, resp))
                                    }
                                )
                            } else {
                                // Always attempt the newer /v1/responses protocol first; if it errors out
                                // (or exhausts fast probe retries when useLegacyFallbackEndpoint is ON), seamlessly
                                // hand off any accumulated thoughts/tools/prose to /v1/chat/completions!
                                try {
                                    executeOpenAiResponsesLoop(
                                        profile = profile,
                                        model = model,
                                        session = session,
                                        historyNodes = effectiveHistoryNodes,
                                        continuePrefix = effectiveContinuePrefix,
                                        circuitBreaker = circuitBreaker,
                                        onStatus = { st -> emit(StreamUpdate.Status(st, lastInteractionId)) },
                                        onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                        onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                        onThinking = { delta ->
                                            thinkingBuilder.append(delta)
                                            currentStepThinkingBuilder.append(delta)
                                            emit(StreamUpdate.ThinkingDelta(delta))
                                        },
                                        onContent = { delta ->
                                            contentBuilder.append(delta)
                                            val approxOut = (contentBuilder.length / 4).coerceAtLeast(outputTokens)
                                            val elapsedSec = ((PlatformBridge.currentTimeMillis() - startTime) / 1000L).toInt()
                                            PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                            emit(StreamUpdate.ContentDelta(delta))
                                        },
                                        onMedia = { item ->
                                            onFirstTokenIfZero(firstTokenTime) { firstTokenTime = it }
                                            generatedMedia.add(item)
                                            emit(StreamUpdate.MediaGenerated(item))
                                        },
                                        onToolCall = { tc ->
                                            val enriched = attachStepThinkingToToolCall(tc)
                                            executedTools.add(enriched)
                                            emit(StreamUpdate.ToolCallEvent(enriched))
                                        },
                                        onRawWire = { req, resp ->
                                            lastRawReq = req
                                            lastRawResp = resp
                                            emit(StreamUpdate.RawWireSnapshot(req, resp))
                                        },
                                        onUsage = { id, inTok, cacheTok, thinkTok, outTok ->
                                            if (id.isNotBlank()) lastInteractionId = id
                                            inputTokens = inTok
                                            cachedTokens = cacheTok
                                            reasoningTokens = thinkTok
                                            outputTokens = outTok
                                        }
                                    )
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    if (shouldFallbackToLegacyProtocol(e, profile.useLegacyFallbackEndpoint)) {
                                        val actualCause = if (e is StepRetriesExhaustedException && e.cause != null) e.cause!! else e
                                        val reasonShort = actualCause.message?.lineSequence()?.firstOrNull()?.trim()?.take(72) ?: "endpoint error"
                                        emit(
                                            StreamUpdate.Status(
                                                "Primary /v1/responses failed ($reasonShort) — falling back to /v1/chat/completions…",
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
                                        runLegacyOpenAiChatCompletions(fbNodes, fbPrefix)
                                    } else {
                                        throw e
                                    }
                                }
                            }
                        }

                        ProviderProtocol.ANTHROPIC_MESSAGES -> {
                            executeAnthropicMessagesLoop(
                                profile = profile,
                                model = model,
                                session = session,
                                historyNodes = effectiveHistoryNodes,
                                continuePrefix = effectiveContinuePrefix,
                                circuitBreaker = circuitBreaker,
                                onStatus = { st -> emit(StreamUpdate.Status(st, lastInteractionId)) },
                                onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                onThinking = { delta, sig ->
                                    thinkingBuilder.append(delta)
                                    currentStepThinkingBuilder.append(delta)
                                    val uniqueSig = appendSignatureIfUnique(sig)
                                    if (delta.isNotEmpty() || uniqueSig.isNotEmpty()) {
                                        emit(StreamUpdate.ThinkingDelta(delta, uniqueSig))
                                    }
                                },
                                onContent = { delta ->
                                    contentBuilder.append(delta)
                                    val approxOut = (contentBuilder.length / 4).coerceAtLeast(outputTokens)
                                    val elapsedSec = ((PlatformBridge.currentTimeMillis() - startTime) / 1000L).toInt()
                                    PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                    emit(StreamUpdate.ContentDelta(delta))
                                },
                                onToolCall = { tc ->
                                    val enriched = attachStepThinkingToToolCall(tc)
                                    executedTools.add(enriched)
                                    emit(StreamUpdate.ToolCallEvent(enriched))
                                },
                                onRawWire = { req, resp ->
                                    lastRawReq = req
                                    lastRawResp = resp
                                    emit(StreamUpdate.RawWireSnapshot(req, resp))
                                },
                                onUsage = { id, inTok, cacheTok, outTok ->
                                    if (id.isNotBlank()) lastInteractionId = id
                                    inputTokens = inTok
                                    cachedTokens = cacheTok
                                    outputTokens = outTok
                                }
                            )
                        }

                        ProviderProtocol.BYTEDANCE_ARK -> {
                            val lowerModel = model.lowercase()
                            val isMediaMode = profile.enableVideoGeneration ||
                                profile.enableImageGeneration ||
                                lowerModel.contains("seedream") ||
                                lowerModel.contains("seedance")
                            if (isMediaMode) {
                                executeByteDanceArkGeneration(
                                    profile = profile,
                                    model = model,
                                    session = session,
                                    historyNodes = historyNodes,
                                    circuitBreaker = circuitBreaker,
                                    onStatus = { st, id ->
                                        if (id.isNotBlank()) lastInteractionId = id
                                        emit(StreamUpdate.Status(st, lastInteractionId))
                                    },
                                    onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                    onThinking = { delta ->
                                        thinkingBuilder.append(delta)
                                        emit(StreamUpdate.ThinkingDelta(delta))
                                    },
                                    onMedia = { item ->
                                        onFirstTokenIfZero(firstTokenTime) { firstTokenTime = it }
                                        generatedMedia.add(item)
                                        emit(StreamUpdate.MediaGenerated(item))
                                    },
                                    onContent = { delta ->
                                        contentBuilder.append(delta)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onRawWire = { req, resp ->
                                        lastRawReq = req
                                        lastRawResp = resp
                                        emit(StreamUpdate.RawWireSnapshot(req, resp))
                                    }
                                )
                            } else {
                                executeOpenAiChatCompletionsLoop(
                                    profile = profile,
                                    model = model,
                                    session = session,
                                    historyNodes = effectiveHistoryNodes,
                                    continuePrefix = effectiveContinuePrefix,
                                    circuitBreaker = circuitBreaker,
                                    onStatus = { st -> emit(StreamUpdate.Status(st, lastInteractionId)) },
                                    onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                    onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                    onThinking = { delta ->
                                        thinkingBuilder.append(delta)
                                        currentStepThinkingBuilder.append(delta)
                                        emit(StreamUpdate.ThinkingDelta(delta))
                                    },
                                    onContent = { delta ->
                                        contentBuilder.append(delta)
                                        val approxOut = (contentBuilder.length / 4).coerceAtLeast(outputTokens)
                                        val elapsedSec = ((PlatformBridge.currentTimeMillis() - startTime) / 1000L).toInt()
                                        PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onMedia = { item ->
                                        onFirstTokenIfZero(firstTokenTime) { firstTokenTime = it }
                                        generatedMedia.add(item)
                                        emit(StreamUpdate.MediaGenerated(item))
                                    },
                                    onToolCall = { tc ->
                                        val enriched = attachStepThinkingToToolCall(tc)
                                        executedTools.add(enriched)
                                        emit(StreamUpdate.ToolCallEvent(enriched))
                                    },
                                    onRawWire = { req, resp ->
                                        lastRawReq = req
                                        lastRawResp = resp
                                        emit(StreamUpdate.RawWireSnapshot(req, resp))
                                    },
                                    onUsage = { inTok, cacheTok, thinkTok, outTok ->
                                        inputTokens = inTok
                                        cachedTokens = cacheTok
                                        reasoningTokens = thinkTok
                                        outputTokens = outTok
                                    }
                                )
                            }
                        }
                    }
                    // Completed cleanly without mid-stream cutoff
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
                        waitWithRetryCountdown(
                            attempt = continuationPass,
                            maxAttempts = if (isLongGoalRun) MAX_GOAL_NETWORK_RETRIES else MAX_MID_STREAM_CONTINUATION_PASSES,
                            waitSeconds = RETRY_WAIT_SECONDS,
                            reasonSummary = "Stream interrupted (${actualErr.message?.take(60) ?: "connection reset"}) — resuming seamlessly",
                            onStatus = { msg -> emit(StreamUpdate.Status(msg, lastInteractionId)) }
                        )
                        continue
                    } else {
                        throw actualErr
                    }
                }
            }

            val endTime = PlatformBridge.currentTimeMillis()
            val totalDurationMs = (endTime - startTime).coerceAtLeast(1L)
            val ttftMs = if (firstTokenTime > startTime) (firstTokenTime - startTime) else totalDurationMs
            val finalInTokens = if (inputTokens > 0) {
                inputTokens
            } else {
                val approxChars = buildCompiledSystemPrompt(session, circuitBreaker.goalState).length +
                    historyNodes.filter { it.includedInContext }.sumOf { it.activeVariant.content.length }
                (approxChars / 4).coerceAtLeast(1)
            }
            val finalThinkTokens = if (reasoningTokens > 0) {
                reasoningTokens
            } else if (thinkingBuilder.isNotEmpty()) {
                (thinkingBuilder.length / 4).coerceAtLeast(1)
            } else 0
            val finalOutTokens = if (outputTokens > 0) outputTokens else (contentBuilder.length / 4).coerceAtLeast(1)
            val tokPerSec = (finalOutTokens * 1000.0) / totalDurationMs.toDouble()

            val rawMetrics = TokenMetrics(
                inputTokens = finalInTokens,
                cachedInputTokens = cachedTokens,
                reasoningTokens = finalThinkTokens,
                outputTokens = finalOutTokens,
                ttftMs = ttftMs,
                durationMs = totalDurationMs,
                tokensPerSec = (tokPerSec * 10.0).toInt() / 10.0
            )
            val costUsd = profile.calculateTurnCostUsd(rawMetrics)

            emit(
                StreamUpdate.Completed(
                    fullContent = contentBuilder.toString(),
                    fullThinking = thinkingBuilder.toString(),
                    trailingThinking = if (executedTools.isNotEmpty()) currentStepThinkingBuilder.toString().trim() else "",
                    thinkingSignature = signatureBuilder.toString(),
                    toolCalls = executedTools,
                    goalTracker = circuitBreaker.goalState,
                    mediaItems = generatedMedia,
                    providerInteractionId = lastInteractionId,
                    metrics = rawMetrics.copy(estimatedCostUsd = costUsd),
                    rawRequest = lastRawReq,
                    rawResponse = lastRawResp
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            emit(StreamUpdate.Error(message = t.message ?: t::class.simpleName ?: "Unknown network error", rawDetails = lastRawResp))
        } finally {
            PlatformBridge.notifyLongDecodeComplete()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Lightweight one-shot utility text generation for Auto-Title Generation,
     * Title Regeneration (`✦`), Context Summarization, and Translation.
     */
    suspend fun generateUtilityText(
        profile: ProviderProfile,
        model: String,
        prompt: String
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
                thinkingEffort = "none",
                enableWebSearch = false,
                enableCodeExecution = false,
                enableUrlContext = false,
                enableImageGeneration = false,
                enableVideoGeneration = false,
                useBackgroundLongDecode = false
            )
            val sb = StringBuilder()
            streamGeneration(
                profile = fastProfile,
                session = tempSession,
                historyNodes = listOf(userNode)
            ).collect { upd ->
                when (upd) {
                    is StreamUpdate.ContentDelta -> sb.append(upd.delta)
                    is StreamUpdate.Completed -> if (sb.isEmpty()) sb.append(upd.fullContent)
                    is StreamUpdate.Error -> error(upd.message)
                    else -> {}
                }
            }
            sb.toString().trim().removeSurrounding("\"").removeSurrounding("'").trim()
        }
    }

    /**
     * Dynamically fetches the available model IDs from the active provider or custom proxy endpoint.
     * Supports Google `/v1beta/models`, OpenAI `/v1/models`, and Anthropic `/v1/models` formats.
     */
    suspend fun fetchAvailableModels(profile: ProviderProfile): Result<List<String>> = runCatching {
        val base = profile.baseUrl.trimEnd('/')
        val candidateUrls = when (profile.protocol) {
            ProviderProtocol.GEMINI_INTERACTIONS -> listOf(
                "$base/v1beta/models?pageSize=200",
                "$base/v1/models"
            )
            ProviderProtocol.OPENAI_RESPONSES -> listOf(
                "$base/v1/models"
            )
            ProviderProtocol.ANTHROPIC_MESSAGES -> listOf(
                "$base/v1/models?limit=100"
            )
            ProviderProtocol.BYTEDANCE_ARK -> {
                if (base.endsWith("/api/v3")) {
                    listOf("$base/models", "${base.removeSuffix("/api/v3")}/v1/models")
                } else {
                    listOf("$base/api/v3/models", "$base/v1/models")
                }
            }
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

    private fun parseModelsListJson(bodyText: String): List<String> {
        val rootEl = runCatching { json.parseToJsonElement(bodyText) }.getOrNull() ?: return emptyList()
        val results = LinkedHashSet<String>()

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
                            results.add(idCandidate.trim().removePrefix("models/"))
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
        onUsage: (String, Int, Int, Int, Int) -> Unit
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
        var simplifyThinkingOnRetry = false
        var omitResponseFormatOnRetry = false
        val extraLoopSteps = mutableListOf<JsonObject>()
        var pendingToolResults: List<ToolCallRecord> = emptyList()
        var pendingStopHookPrompt: String = ""
        var loopStep = 0

        val lowerModel = model.lowercase()
        val isGemini4 = GEMINI_4_REGEX.containsMatchIn(lowerModel) || lowerModel.startsWith("gemini-4")

        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val turnToolCalls = mutableListOf<Pair<String, Pair<String, String>>>() // callId -> (name, argsJson)
            val roundThoughtSig = StringBuilder()
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

            fun emitUniqueRoundSig(sig: String): String {
                if (sig.isEmpty()) return ""
                val cur = roundThoughtSig.toString()
                if (cur == sig || cur.endsWith(sig)) return ""
                roundThoughtSig.append(sig)
                return sig
            }

            while (true) {
                turnToolCalls.clear()
                roundThoughtSig.clear()
                roundThoughtText.clear()
                roundModelText.clear()
                roundStatus = ""
                roundContinuationToken = ""
                receivedAnyStreamContent = false

                val useChainedId = profile.useStatefulChaining && currentPrevInteractionId.isNotBlank()
                val isContinuationHop = useChainedId && currentContinuationToken.isNotBlank()
                val baseInputSteps = if (useChainedId && previousAssistantIdx >= 0 && previousAssistantIdx < activeNodes.size - 1) {
                    val trailingNodes = activeNodes.subList(previousAssistantIdx + 1, activeNodes.size)
                    buildGeminiInteractionsTurnsArray(trailingNodes, session, continuePrefix)
                } else {
                    buildGeminiInteractionsTurnsArray(activeNodes, session, continuePrefix)
                }

                // 3-Layer Rolling Context Compaction: prune verbose tool outputs older than 8 steps
                val compactedExtraSteps = compactGeminiInteractionsExtraSteps(extraLoopSteps)

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

                    // Official June 2026 GA polymorphic `response_format` array for text + image output
                    if (profile.enableImageGeneration && !isGemini4 && !omitResponseFormatOnRetry) {
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

                    // Generation config (nullable parameters are stripped when null or <= 0)
                    putJsonObject("generation_config") {
                        if (!isGemini4 && !stripSamplingOnRetry) {
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
                                    "minimal" -> if (isGemini4) "low" else "minimal"
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
                    val toolsArray = buildJsonArray {
                        if (profile.enableWebSearch) {
                            add(buildJsonObject { put("type", "google_search") })
                        }
                        if (profile.enableCodeExecution) {
                            add(buildJsonObject { put("type", "code_execution") })
                        }
                        if (profile.enableUrlContext) {
                            add(buildJsonObject { put("type", "url_context") })
                        }
                        val workspaceTools = WorkspaceHarness.buildGeminiInteractionsTools(
                            session.workspaceMode,
                            session.workspacePath,
                            profile.allowShellExecution,
                            goalModeActive = circuitBreaker.goalState != null
                        )
                        workspaceTools.forEach { add(it) }
                    }
                    if (toolsArray.isNotEmpty()) {
                        put("tools", toolsArray)
                    }
                }

                val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
                val requestJsonStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)

                val base = profile.baseUrl.trimEnd('/')
                val defaultPath = if (base.endsWith("/v1beta") || base.endsWith("/v1")) "/interactions" else "/v1beta/interactions"
                val path = profile.endpointPathOverride.ifBlank { defaultPath }
                val rawUrl = "$base$path"
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
                            }
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
                                            val uniqueSig = emitUniqueRoundSig(sig)
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
                                    }
                                    "content.delta", "step.delta" -> {
                                        val idx = evObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                        val stepTypeForIdx = stepTypeByIndex[idx] ?: fallbackStreamStepType
                                        val deltaObj = evObj["delta"] as? JsonObject ?: evObj
                                        val deltaType = deltaObj["type"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val sigDelta = deltaObj["signature"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val uniqueSig = emitUniqueRoundSig(sigDelta)
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
                                    }
                                    "interaction.complete", "interaction.completed" -> {
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
                                        }
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
                                                }
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
                                        }
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
                        if (!simplifyThinkingOnRetry && (errMsg.contains("thinking") || errMsg.contains("thinking_level"))) {
                            simplifyThinkingOnRetry = true
                            onStatus("Endpoint rejected thinking_level — retrying with automatic thinking…", currentPrevInteractionId)
                            continue
                        }
                        if (!omitResponseFormatOnRetry && (errMsg.contains("response_format") || errMsg.contains("image"))) {
                            omitResponseFormatOnRetry = true
                            onStatus("Endpoint rejected response_format — retrying without response_format…", currentPrevInteractionId)
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
                    if (roundThoughtSig.isNotEmpty()) {
                        extraLoopSteps.add(buildJsonObject {
                            put("type", "thought")
                            put("signature", roundThoughtSig.toString())
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
            val stepSigString = roundThoughtSig.toString()
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

            val results = mutableListOf<ToolCallRecord>()
            for ((idx, callEntry) in turnToolCalls.withIndex()) {
                val (callId, fnPair) = callEntry
                val parsedArgs = runCatching { json.parseToJsonElement(fnPair.second).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
                extraLoopSteps.add(buildJsonObject {
                    put("id", callId)
                    put("type", "function_call")
                    put("name", fnPair.first)
                    put("arguments", parsedArgs)
                })

                onStatus("${WorkspaceHarness.describeToolCallIntent(fnPair.first, fnPair.second)}…", currentPrevInteractionId)
                val rawRecord = WorkspaceHarness.executeToolCall(
                    workspaceRoot = session.workspacePath,
                    mode = session.workspaceMode,
                    callId = callId,
                    toolName = fnPair.first,
                    argumentsJson = fnPair.second
                )
                val stepThoughtTxt = roundThoughtText.toString().trim()
                val signedRecord = if (idx == 0 && (stepSigString.isNotEmpty() || stepThoughtTxt.isNotEmpty())) {
                    rawRecord.copy(
                        thoughtSignature = stepSigString,
                        precedingThinking = stepThoughtTxt
                    )
                } else {
                    rawRecord
                }
                val record = circuitBreaker.onToolExecuted(signedRecord)
                circuitBreaker.goalState?.let { onGoalProgress(it) }
                onToolCall(record)
                results.add(record)

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
        onUsage: (String, Int, Int, Int, Int) -> Unit
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
                                saveAndEmitImage(b64, uri, mime, "", "", onMedia)
                            }
                        }
                    }
                }
                "image" -> {
                    val b64 = item["data"]?.jsonPrimitive?.contentOrNull ?: ""
                    val uri = item["uri"]?.jsonPrimitive?.contentOrNull
                        ?: item["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    val mime = item["mime_type"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                    saveAndEmitImage(b64, uri, mime, "", "", onMedia)
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
        }

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
        continuePrefix: String
    ): JsonArray {
        val stepsList = mutableListOf<JsonObject>()
        val withAuthorsNote = injectAuthorsNoteIntoHistory(nodes, session)

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
                val sig = nodeVar?.thinkingSignature.orEmpty()
                val thinkSummary = nodeVar?.thinkingSummary.orEmpty()
                val trailingThink = nodeVar?.trailingThinking.orEmpty()
                val toolCalls = nodeVar?.toolCalls.orEmpty()
                val hasAnyPrecedingThinking = toolCalls.any { it.precedingThinking.isNotBlank() }
                var emittedThoughtForTurn = false
                var emittedAnyStepForTurn = false

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

                for ((tcIdx, tc) in toolCalls.withIndex()) {
                    val stepSig = tc.thoughtSignature.ifBlank { if (tcIdx == 0) sig else "" }
                    val stepSummary = tc.precedingThinking.ifBlank { if (tcIdx == 0 && !hasAnyPrecedingThinking) thinkSummary else "" }
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
                    val parsedArgs = runCatching { json.parseToJsonElement(tc.argumentsJson.ifBlank { "{}" }).jsonObject }
                        .getOrElse { JsonObject(emptyMap()) }
                    val callId = tc.id.ifBlank { "call_${tc.name}" }
                    stepsList.add(buildJsonObject {
                        put("type", "function_call")
                        put("id", callId)
                        put("name", tc.name)
                        put("arguments", parsedArgs)
                    })
                    stepsList.add(buildJsonObject {
                        put("type", "function_result")
                        put("name", tc.name.ifBlank { "tool" })
                        put("call_id", callId)
                        put("is_error", tc.isError)
                        putJsonObject("result") {
                            put("result", tc.resultText)
                        }
                    })
                    emittedAnyStepForTurn = true
                }

                if (toolCalls.isNotEmpty() && trailingThink.isNotBlank() && sig.isNotBlank() &&
                    toolCalls.none { it.thoughtSignature == sig }
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
        onUsage: (Int, Int, Int, Int) -> Unit
    ) {
        val cleanModel = model.trim().removePrefix("models/")
        val lowerModel = cleanModel.lowercase()
        val isGemini4 = GEMINI_4_REGEX.containsMatchIn(lowerModel) || lowerModel.startsWith("gemini-4")
        val isGemini3 = lowerModel.contains("gemini-3")
        val isGemma4 = lowerModel.contains("gemma-4") || lowerModel.contains("gemma4")
        val isThinkingLevelDialect = isGemini4 || isGemini3 || isGemma4
        val supportsMixedTools = isGemini4 || isGemini3

        val activeNodes = sanitizeContextNodes(historyNodes)
        val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)
        val rollingContents = buildGeminiGenerateContentContents(activeNodes, session, continuePrefix)

        val hasBuiltInTools = profile.enableWebSearch || profile.enableCodeExecution || profile.enableUrlContext
        var stripSamplingOnRetry = false
        var simplifyThinkingOnRetry = false

        var loopStep = 0
        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val activeWorkspaceTools = WorkspaceHarness.activeToolsForMode(
                session.workspaceMode,
                session.workspacePath,
                profile.allowShellExecution,
                goalModeActive = circuitBreaker.goalState != null
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

                    put("contents", JsonArray(rollingContents))

                    // Tools & toolConfig (handling Gemini 3+ / Gemini 4 coexistence vs 2.x)
                    val toolsArr = buildJsonArray {
                        if (supportsMixedTools || activeWorkspaceTools.isEmpty()) {
                            if (profile.enableCodeExecution) add(buildJsonObject { putJsonObject("code_execution") {} })
                            if (profile.enableWebSearch) add(buildJsonObject { putJsonObject("google_search") {} })
                            if (profile.enableUrlContext) add(buildJsonObject { putJsonObject("url_context") {} })
                        }
                        if (activeWorkspaceTools.isNotEmpty() && (supportsMixedTools || !hasBuiltInTools)) {
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
                                if (supportsMixedTools && hasBuiltInTools) {
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
                        if (!isGemini4 && !stripSamplingOnRetry) {
                            profile.temperature?.let { put("temperature", it) }
                            profile.topP?.let { put("topP", it) }
                        }
                        val maxOut = profile.maxOutputTokens
                        if (maxOut != null && maxOut > 0) {
                            put("maxOutputTokens", maxOut)
                        }
                        if (profile.enableImageGeneration && !isGemini4) {
                            putJsonArray("responseModalities") {
                                add(JsonPrimitive("TEXT"))
                                add(JsonPrimitive("IMAGE"))
                            }
                        }

                        if ((!profile.enableImageGeneration || !lowerModel.contains("pro-image")) && !simplifyThinkingOnRetry) {
                            val rawEffort = profile.thinkingEffort.trim()
                            val lowerEffort = rawEffort.lowercase()
                            val thinkBudget = profile.thinkingBudgetTokens
                            if (isThinkingLevelDialect) {
                                val hideThoughts = lowerEffort == "none" || lowerEffort == "off"
                                val resolvedLevel = when {
                                    isGemini4 -> {
                                        when (lowerEffort) {
                                            "none", "off", "minimal", "low" -> "LOW"
                                            "medium", "default", "auto", "" -> if (lowerModel.contains("cyber-permissive")) "HIGH" else "MEDIUM"
                                            "high", "xhigh", "max" -> "HIGH"
                                            else -> rawEffort.uppercase()
                                        }
                                    }
                                    else -> {
                                        when (lowerEffort) {
                                            "none", "off" -> if (lowerModel.contains("3.8-flash") || lowerModel.contains("3.7-flash") || lowerModel.contains("3.1-pro")) "LOW" else "MINIMAL"
                                            "minimal" -> "MINIMAL"
                                            "low" -> "LOW"
                                            "medium", "default", "auto", "" -> "MEDIUM"
                                            "high", "xhigh", "max" -> "HIGH"
                                            else -> rawEffort.uppercase()
                                        }
                                    }
                                }
                                putJsonObject("thinkingConfig") {
                                    put("includeThoughts", !hideThoughts)
                                    put("thinkingLevel", resolvedLevel)
                                }
                            } else if (thinkBudget != null && thinkBudget > 0) {
                                val hideThoughts = lowerEffort == "none" || lowerEffort == "off"
                                putJsonObject("thinkingConfig") {
                                    put("includeThoughts", !hideThoughts)
                                    if (!hideThoughts) {
                                        put("thinkingBudget", thinkBudget)
                                    }
                                }
                            }
                        }
                    }
                }

                val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
                val reqStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)
                val base = profile.baseUrl.trimEnd('/')
                val rawUrl = when {
                    profile.endpointPathOverride.isNotBlank() && !profile.endpointPathOverride.contains("interactions") -> {
                        "$base${profile.endpointPathOverride}"
                    }
                    base.endsWith("/v1beta") || base.endsWith("/v1") -> {
                        "$base/models/$cleanModel:streamGenerateContent?alt=sse"
                    }
                    else -> {
                        "$base/v1beta/models/$cleanModel:streamGenerateContent?alt=sse"
                    }
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
                                val sig = partObj["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                                    ?: partObj["thought_signature"]?.jsonPrimitive?.contentOrNull ?: ""
                                val txt = partObj["text"]?.jsonPrimitive?.contentOrNull ?: ""

                                val uniqueSig = if (sig.isNotEmpty() && !roundThoughtSig.toString().endsWith(sig)) {
                                    roundThoughtSig.append(sig)
                                    sig
                                } else ""

                                if (!isThought) {
                                    roundModelParts.add(partObj)
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
                                        saveAndEmitImage(b64, "", mime, profile.mediaAspectRatio, "", onMedia)
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
                        if (!simplifyThinkingOnRetry && (errMsg.contains("thinking") || errMsg.contains("thinkinglevel") || errMsg.contains("thinkingbudget"))) {
                            simplifyThinkingOnRetry = true
                            onStatus("Endpoint rejected thinkingConfig — retrying with default thinking…")
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
                        rollingContents.add(buildJsonObject {
                            put("role", "model")
                            put("parts", JsonArray(roundModelParts))
                        })
                    }
                    rollingContents.add(buildJsonObject {
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
            val fallbackSig = roundThoughtSig.toString().ifBlank { "context_engineering_is_the_way_to_go" }
            val signedModelParts = roundModelParts.mapIndexed { idx, part ->
                if (idx == firstFunctionCallIdx && !part.containsKey("thoughtSignature") && !part.containsKey("thought_signature")) {
                    val mutable = part.toMutableMap()
                    mutable["thoughtSignature"] = JsonPrimitive(fallbackSig)
                    JsonObject(mutable)
                } else part
            }
            rollingContents.add(buildJsonObject {
                put("role", "model")
                put("parts", JsonArray(signedModelParts))
            })

            val responseParts = mutableListOf<JsonObject>()
            for ((idx, triple) in roundToolCalls.withIndex()) {
                val (callId, fnName, argsJson) = triple
                onStatus("${WorkspaceHarness.describeToolCallIntent(fnName, argsJson)}…")
                val rawRec = WorkspaceHarness.executeToolCall(
                    workspaceRoot = session.workspacePath,
                    mode = session.workspaceMode,
                    callId = callId,
                    toolName = fnName,
                    argumentsJson = argsJson
                )
                val signedRec = if (idx == 0 && roundThoughtSig.isNotEmpty()) {
                    rawRec.copy(thoughtSignature = roundThoughtSig.toString())
                } else {
                    rawRec
                }
                val rec = circuitBreaker.onToolExecuted(signedRec)
                circuitBreaker.goalState?.let { onGoalProgress(it) }
                onToolCall(rec)
                responseParts.add(buildJsonObject {
                    putJsonObject("functionResponse") {
                        put("name", fnName)
                        if (!callId.startsWith("synth_call_")) {
                            put("id", callId)
                        }
                        putJsonObject("response") {
                            put("result", rec.resultText)
                        }
                    }
                })
            }
            rollingContents.add(buildJsonObject {
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
        continuePrefix: String
    ): MutableList<JsonObject> {
        val turns = injectAuthorsNoteIntoHistory(activeNodes, session)
        val rollingContents = mutableListOf<JsonObject>()

        fun appendOrMergeContentTurn(roleStr: String, newParts: List<JsonObject>) {
            if (newParts.isEmpty()) return
            val last = rollingContents.lastOrNull()
            if (last != null && last["role"]?.jsonPrimitive?.contentOrNull == roleStr) {
                val existingParts = (last["parts"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty().toMutableList()
                val existingTextIdx = existingParts.indexOfLast {
                    it.containsKey("text") &&
                        it["thought"]?.jsonPrimitive?.contentOrNull != "true" &&
                        !it.containsKey("functionCall") &&
                        !it.containsKey("functionResponse")
                }
                val firstNew = newParts.firstOrNull()
                val firstIsPlainText = firstNew != null &&
                    firstNew.containsKey("text") &&
                    firstNew["thought"]?.jsonPrimitive?.contentOrNull != "true" &&
                    !firstNew.containsKey("functionCall") &&
                    !firstNew.containsKey("functionResponse")

                if (existingTextIdx >= 0 && firstIsPlainText) {
                    val targetExisting = existingParts[existingTextIdx]
                    val prevTxt = targetExisting["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val nextTxt = firstNew!!["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val joined = if (prevTxt.isBlank()) nextTxt else if (nextTxt.isBlank()) prevTxt else "$prevTxt\n\n$nextTxt"
                    val mergedMap = targetExisting.toMutableMap()
                    mergedMap["text"] = JsonPrimitive(joined)
                    firstNew["thoughtSignature"]?.let { mergedMap["thoughtSignature"] = it }
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
                val savedSig = nodeVar?.thinkingSignature.orEmpty()
                val thinkSummary = nodeVar?.thinkingSummary.orEmpty()
                val toolCalls = nodeVar?.toolCalls.orEmpty()
                var emittedAny = false

                if (thinkSummary.isNotBlank()) {
                    val thoughtPart = buildJsonObject {
                        put("thought", true)
                        put("text", thinkSummary)
                        if (savedSig.isNotBlank() && toolCalls.isEmpty() && cleanText.isBlank()) {
                            put("thoughtSignature", savedSig)
                        }
                    }
                    appendOrMergeContentTurn("model", listOf(thoughtPart))
                    emittedAny = true
                }

                if (toolCalls.isNotEmpty()) {
                    val fcParts = toolCalls.mapIndexed { tcIdx, tc ->
                        val parsedArgs = runCatching { json.parseToJsonElement(tc.argumentsJson.ifBlank { "{}" }).jsonObject }
                            .getOrElse { JsonObject(emptyMap()) }
                        val effSig = tc.thoughtSignature.ifBlank { savedSig }.ifBlank { "context_engineering_is_the_way_to_go" }
                        buildJsonObject {
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
                        }
                    }
                    appendOrMergeContentTurn("model", fcParts)

                    val frParts = toolCalls.map { tc ->
                        buildJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", tc.name)
                                if (tc.id.isNotBlank() && !tc.id.startsWith("synth_call_")) {
                                    put("id", tc.id)
                                }
                                putJsonObject("response") {
                                    put("result", tc.resultText)
                                }
                            }
                        }
                    }
                    appendOrMergeContentTurn("user", frParts)
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
        onUsage: (String, Int, Int, Int, Int) -> Unit
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
        var loopStep = 0
        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val turnToolCalls = mutableListOf<Pair<String, Pair<String, String>>>()
            val roundTextBuilder = StringBuilder()
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
                stepEmittedOutput = false

                val useChainedId = profile.useStatefulChaining && currentPrevResponseId.isNotBlank()
                val baseInputItems = if (useChainedId && previousAssistantIdx >= 0 && previousAssistantIdx < activeNodes.size - 1) {
                    val trailing = activeNodes.subList(previousAssistantIdx + 1, activeNodes.size)
                    buildOpenAiResponsesInputArray(trailing, session, continuePrefix)
                } else {
                    buildOpenAiResponsesInputArray(activeNodes, session, continuePrefix)
                }

                val compactedLoopItems = compactOpenAiResponsesExtraItems(extraResponsesLoopItems)

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
                    if (!omitReasoningOnRetry && effort.isNotEmpty() && lowerEffort != "none" && lowerEffort != "off" && lowerEffort != "default") {
                        val normalizedEffort = when (lowerEffort) {
                            "max" -> "high"
                            else -> lowerEffort
                        }
                        putJsonObject("reasoning") {
                            put("effort", normalizedEffort)
                            put("summary", "auto")
                        }
                    } else if (!stripSamplingOnRetry) {
                        profile.temperature?.let { put("temperature", it) }
                        profile.topP?.let { put("top_p", it) }
                    }
                    val toolsArr = buildJsonArray {
                        if (profile.enableWebSearch) {
                            add(buildJsonObject { put("type", "web_search_preview") })
                        }
                        if (profile.enableImageGeneration) {
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
                            goalModeActive = circuitBreaker.goalState != null
                        )
                        wsTools.forEach { add(it) }
                    }
                    if (toolsArr.isNotEmpty()) {
                        put("tools", toolsArr)
                        put("tool_choice", "auto")
                    }
                }

                val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
                val reqStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)
                val base = profile.baseUrl.trimEnd('/')
                val defaultPath = when {
                    base.endsWith("/v1") || base.endsWith("/api/v3") -> "/responses"
                    else -> "/v1/responses"
                }
                val path = profile.endpointPathOverride.ifBlank { defaultPath }
                val url = buildUrlWithAuthQuery(profile, "$base$path")

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
                                            onThinking(delta)
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
                                                saveAndEmitImage(b64, "", "image/png", profile.mediaAspectRatio, revised, onMedia)
                                            }
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
                                        }
                                        val usage = respSub?.get("usage") as? JsonObject
                                        if (usage != null) {
                                            val inTok = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                            val cacheTok = (usage["input_tokens_details"] as? JsonObject)
                                                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                            val thinkTok = (usage["output_tokens_details"] as? JsonObject)
                                                ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                            val outTok = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
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
                                                saveAndEmitImage(b64, "", "image/png", profile.mediaAspectRatio, revised, onMedia)
                                            }
                                        }
                                    }
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

            val results = mutableListOf<ToolCallRecord>()
            for ((callId, fnPair) in turnToolCalls) {
                extraResponsesLoopItems.add(buildJsonObject {
                    put("type", "function_call")
                    put("call_id", callId)
                    put("name", fnPair.first)
                    put("arguments", fnPair.second)
                    put("status", "completed")
                })
                onStatus("${WorkspaceHarness.describeToolCallIntent(fnPair.first, fnPair.second)}…")
                val rawRec = WorkspaceHarness.executeToolCall(
                    workspaceRoot = session.workspacePath,
                    mode = session.workspaceMode,
                    callId = callId,
                    toolName = fnPair.first,
                    argumentsJson = fnPair.second
                )
                val rec = circuitBreaker.onToolExecuted(rawRec)
                circuitBreaker.goalState?.let { onGoalProgress(it) }
                onToolCall(rec)
                results.add(rec)
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
        continuePrefix: String
    ): JsonArray {
        val items = mutableListOf<JsonObject>()
        val withAuthorsNote = injectAuthorsNoteIntoHistory(nodes, session)

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
                val toolCalls = nodeVar?.toolCalls.orEmpty()
                var emittedAny = false

                for (tc in toolCalls) {
                    val callId = tc.id.ifBlank { "call_${tc.name}" }
                    items.add(buildJsonObject {
                        put("type", "function_call")
                        put("call_id", callId)
                        put("name", tc.name)
                        put("arguments", tc.argumentsJson.ifBlank { "{}" })
                        put("status", "completed")
                    })
                    items.add(buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", callId)
                        put("output", tc.resultText)
                    })
                    emittedAny = true
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
        onUsage: (Int, Int, Int, Int) -> Unit
    ) {
        val activeNodes = sanitizeContextNodes(historyNodes)
        val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)
        val extraToolMessages = mutableListOf<JsonObject>()
        val fullTextAccumulator = StringBuilder()
        var stripSamplingOnRetry = false
        var omitReasoningEffortOnRetry = false
        var omitStreamOptionsOnRetry = false

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
                    val maxOut = profile.maxOutputTokens
                    if (maxOut != null && maxOut > 0) {
                        put("max_tokens", maxOut)
                    }
                    val effort = profile.thinkingEffort.trim()
                    val lowerEffort = effort.lowercase()
                    if (!omitReasoningEffortOnRetry && effort.isNotEmpty() && lowerEffort != "none" && lowerEffort != "off" && lowerEffort != "default") {
                        val normalizedEffort = when (lowerEffort) {
                            "max" -> "high"
                            else -> lowerEffort
                        }
                        put("reasoning_effort", normalizedEffort)
                    }
                    if (profile.enableImageGeneration) {
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
                            extraToolMessages = compactedToolMessages
                        )
                    )
                    val wsTools = WorkspaceHarness.buildOpenAiChatCompletionsTools(
                        session.workspaceMode,
                        session.workspacePath,
                        profile.allowShellExecution,
                        goalModeActive = circuitBreaker.goalState != null
                    )
                    if (wsTools.isNotEmpty()) {
                        put("tools", wsTools)
                        put("tool_choice", "auto")
                    }
                }

                val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
                val reqStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)
                val base = profile.baseUrl.trimEnd('/')
                val defaultPath = when {
                    base.endsWith("/v1") || base.endsWith("/api/v3") -> "/chat/completions"
                    profile.protocol == ProviderProtocol.BYTEDANCE_ARK -> "/api/v3/chat/completions"
                    else -> "/v1/chat/completions"
                }
                val path = profile.endpointPathOverride.ifBlank { defaultPath }
                val url = buildUrlWithAuthQuery(profile, "$base$path")

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
                                                saveAndEmitImage(b64, "", mime, profile.mediaAspectRatio, "", onMedia)
                                            } else if (urlVal.startsWith("http")) {
                                                stepEmittedOutput = true
                                                saveAndEmitImage("", urlVal, "image/png", profile.mediaAspectRatio, "", onMedia)
                                            }
                                        }
                                    }
                                }
                                val usage = obj["usage"] as? JsonObject
                                if (usage != null) {
                                    val inTok = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                    val cacheTok = (usage["prompt_tokens_details"] as? JsonObject)
                                        ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                    val thinkTok = (usage["completion_tokens_details"] as? JsonObject)
                                        ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                    val outTok = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
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
                            }
                        )
                    }
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val errMsg = (e.message ?: e.toString()).lowercase()
                    if (!stepEmittedOutput && roundToolCalls.isEmpty() && errMsg.contains("http 400")) {
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

            for ((callId, fnName, argsSb) in validCalls) {
                val cleanArgs = argsSb.toString().ifBlank { "{}" }
                onStatus("${WorkspaceHarness.describeToolCallIntent(fnName, cleanArgs)}…")
                val rawRec = WorkspaceHarness.executeToolCall(
                    workspaceRoot = session.workspacePath,
                    mode = session.workspaceMode,
                    callId = callId,
                    toolName = fnName,
                    argumentsJson = cleanArgs
                )
                val rec = circuitBreaker.onToolExecuted(rawRec)
                circuitBreaker.goalState?.let { onGoalProgress(it) }
                onToolCall(rec)
                extraToolMessages.add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", rec.id)
                    put("name", fnName)
                    put("content", rec.resultText)
                })
            }
            val trippedReason = circuitBreaker.checkTripped()
            if (trippedReason != null) {
                onStatus(trippedReason)
                break
            }
        }
        extractMarkdownImagesFromText(fullTextAccumulator.toString(), profile.mediaAspectRatio, onMedia)
    }

    internal fun buildOpenAiChatCompletionsMessages(
        activeNodes: List<MessageNode>,
        session: ChatSession,
        systemInstruction: String,
        continuePrefix: String,
        extraToolMessages: List<JsonObject> = emptyList()
    ): JsonArray {
        val turns = injectAuthorsNoteIntoHistory(activeNodes, session)
        val messages = mutableListOf<JsonObject>()
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
                if (toolCalls.isNotEmpty()) {
                    messages.add(buildJsonObject {
                        put("role", "assistant")
                        if (cleanText.isNotEmpty()) {
                            put("content", cleanText)
                        }
                        if (thinkSummary.isNotBlank()) {
                            put("reasoning_content", thinkSummary)
                        }
                        putJsonArray("tool_calls") {
                            for (tc in toolCalls) {
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
                    for (tc in toolCalls) {
                        messages.add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tc.id.ifBlank { "call_${tc.name}" })
                            put("name", tc.name)
                            put("content", tc.resultText)
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
        onUsage: (String, Int, Int, Int) -> Unit
    ) {
        val activeNodes = sanitizeContextNodes(historyNodes)
        val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)
        val coalescedBaseMessages = buildAnthropicBaseMessages(activeNodes, session, continuePrefix)

        // Mutable conversation list for multi-step tool loop
        val extraLoopMessages = mutableListOf<JsonObject>()
        var stripSamplingOnRetry = false
        // "auto" -> adaptive (or budget if thinkingBudgetTokens >= 1024); "budget" -> fallback enabled+budget; "omit" -> omit thinking object
        var thinkingModeOverride = "auto"
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
                val useBudgetMode = isThinkingRequested && (thinkingModeOverride == "budget" || thinkBudget >= 1024)
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
                    } else if (!stripSamplingOnRetry) {
                        // Anthropic rejects requests that specify both temperature and top_p; prefer temperature when set
                        if (profile.temperature != null) {
                            put("temperature", profile.temperature)
                        } else if (profile.topP != null) {
                            put("top_p", profile.topP)
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
                        for (extraMsg in compactedAnthropicExtras) {
                            add(extraMsg)
                        }
                    }

                    val wsTools = WorkspaceHarness.buildAnthropicTools(
                        session.workspaceMode,
                        session.workspacePath,
                        profile.allowShellExecution,
                        goalModeActive = circuitBreaker.goalState != null
                    )
                    if (wsTools.isNotEmpty()) {
                        put("tools", wsTools)
                    }
                }

                val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
                val reqStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)
                val base = profile.baseUrl.trimEnd('/')
                val defaultPath = if (base.endsWith("/v1")) "/messages" else "/v1/messages"
                val path = profile.endpointPathOverride.ifBlank { defaultPath }
                val url = buildUrlWithAuthQuery(profile, "$base$path")

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
                                        }
                                    }
                                    "message_delta" -> {
                                        val usage = evObj["usage"] as? JsonObject
                                        if (usage != null) {
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
                        if (thinkingModeOverride == "auto" && (errMsg.contains("adaptive") || errMsg.contains("output_config") || errMsg.contains("effort") || errMsg.contains("display"))) {
                            thinkingModeOverride = "budget"
                            onStatus("Endpoint does not support adaptive thinking — retrying with budget_tokens thinking…")
                            continue
                        }
                        if (thinkingModeOverride != "omit" && (errMsg.contains("thinking") || errMsg.contains("budget_tokens") || errMsg.contains("output_config") || errMsg.contains("effort"))) {
                            thinkingModeOverride = "omit"
                            onStatus("Endpoint rejected thinking configuration — retrying with default model configuration…")
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

            val toolResultBlocks = mutableListOf<JsonObject>()
            val firstSignedThinking = orderedBlocks.filterIsInstance<AnthropicStreamBlock.Thinking>()
                .firstOrNull { it.signature.isNotEmpty() }?.signature?.toString().orEmpty()
            for ((idx, callEntry) in turnToolCalls.withIndex()) {
                val (callId, fnPair) = callEntry
                onStatus("${WorkspaceHarness.describeToolCallIntent(fnPair.first, fnPair.second)}…")
                val rawRec = WorkspaceHarness.executeToolCall(
                    workspaceRoot = session.workspacePath,
                    mode = session.workspaceMode,
                    callId = callId,
                    toolName = fnPair.first,
                    argumentsJson = fnPair.second
                )
                val signedRec = if (idx == 0 && firstSignedThinking.isNotEmpty()) {
                    rawRec.copy(thoughtSignature = firstSignedThinking)
                } else {
                    rawRec
                }
                val rec = circuitBreaker.onToolExecuted(signedRec)
                circuitBreaker.goalState?.let { onGoalProgress(it) }
                onToolCall(rec)
                toolResultBlocks.add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", callId)
                    put("content", rec.resultText)
                    if (rec.isError) put("is_error", true)
                })
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
        continuePrefix: String
    ): MutableList<Pair<String, MutableList<JsonObject>>> {
        val turnsWithAuthorsNote = injectAuthorsNoteIntoHistory(activeNodes, session)
        val coalescedBaseMessages = mutableListOf<Pair<String, MutableList<JsonObject>>>()

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

                if (toolCalls.isNotEmpty()) {
                    val assistantToolBlocks = mutableListOf<JsonObject>()
                    val effectiveSig = thinkSig.ifBlank { toolCalls.firstOrNull()?.thoughtSignature.orEmpty() }
                    if (effectiveSig.isNotBlank()) {
                        assistantToolBlocks.add(buildJsonObject {
                            put("type", "thinking")
                            put("thinking", thinkSummary)
                            put("signature", effectiveSig)
                        })
                    }
                    for (tc in toolCalls) {
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

                    val userResultBlocks = toolCalls.map { tc ->
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", tc.id.ifBlank { "toolu_${tc.name}" })
                            put("content", tc.resultText)
                            if (tc.isError) put("is_error", true)
                        }
                    }
                    appendRoleBlocks("user", userResultBlocks)

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
                    if (thinkSig.isNotBlank()) {
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
        historyNodes: List<MessageNode>,
        onStatus: suspend (String) -> Unit,
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
        val reqStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)
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
        onRawWire(reqStr, bodyText)
        if (!resp.status.isSuccess()) {
            error("Image Generation HTTP ${resp.status.value}: $bodyText")
        }

        val root = json.parseToJsonElement(bodyText).jsonObject
        val dataArr = root["data"] as? JsonArray ?: JsonArray(emptyList())
        for (itemEl in dataArr) {
            val itemObj = itemEl as? JsonObject ?: continue
            val b64 = itemObj["b64_json"]?.jsonPrimitive?.contentOrNull ?: ""
            val remoteUrl = itemObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
            val revised = itemObj["revised_prompt"]?.jsonPrimitive?.contentOrNull ?: ""
            saveAndEmitImage(b64, remoteUrl, "image/png", profile.mediaAspectRatio, revised, onMedia)
            if (revised.isNotBlank()) {
                onContent("*Revised Prompt:* $revised\n")
            }
        }
    }

    // ============================================================================================
    // 5. BYTEDANCE ARK & OPENAI-PROXY DRIVER: SEEDREAM (IMAGE) & SEEDANCE (VIDEO)
    //    Supports:
    //    - Seedream Image Generation (`POST /api/v3/images/generations` & `/v1/images/generations`)
    //    - Seedance Video Generation (`POST /api/v3/contents/generations/tasks` & `/v1/contents/generations/tasks` + async polling)
    //    - Automatic fallback to `/v1/chat/completions` when an OpenAI proxy wraps Seedream/Seedance in chat completions!
    // ============================================================================================
    private suspend fun executeByteDanceArkGeneration(
        profile: ProviderProfile,
        model: String,
        session: ChatSession,
        historyNodes: List<MessageNode>,
        circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
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

        val baseClean = profile.baseUrl.trimEnd('/')
        val rootBase = baseClean.removeSuffix("/v1").removeSuffix("/api/v3").trimEnd('/')

        val isSeedanceVideo = profile.enableVideoGeneration ||
            model.contains("seedance", ignoreCase = true) ||
            profile.endpointPathOverride.contains("contents/generations/tasks", ignoreCase = true)

        if (isSeedanceVideo) {
            // Seedance Video Generation Task API
            val videoRes = if (profile.mediaResolution in listOf("480p", "720p", "1080p")) {
                profile.mediaResolution
            } else "720p"

            val latestUserNode = historyNodes.lastOrNull { it.role == MessageRole.USER } ?: historyNodes.lastOrNull()
            val refImages = resolveBinaryAttachments(latestUserNode?.activeVariant)
                .filter { it.mimeType.lowercase().startsWith("image/") }
            val payloadObj = buildJsonObject {
                put("model", model)
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", latestUserPrompt)
                    })
                    for (img in refImages) {
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:${img.mimeType};base64,${img.base64Data}")
                            }
                        })
                    }
                }
                if (profile.mediaAspectRatio.isNotBlank()) {
                    put("ratio", profile.mediaAspectRatio)
                }
                put("duration", profile.videoDurationSeconds.coerceIn(2, 15))
                put("resolution", videoRes)
                put("generate_audio", profile.videoGenerateAudio)
                put("watermark", false)
            }

            val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
            val reqStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)
            val candidateTaskUrls = if (profile.endpointPathOverride.isNotBlank() && profile.endpointPathOverride.contains("tasks")) {
                listOf("$baseClean${profile.endpointPathOverride}")
            } else if (baseClean.endsWith("/v1")) {
                listOf(
                    "$baseClean/contents/generations/tasks",
                    "$rootBase/api/v3/contents/generations/tasks",
                    "$baseClean/videos/generations"
                )
            } else {
                listOf(
                    "$rootBase/api/v3/contents/generations/tasks",
                    "$rootBase/v1/contents/generations/tasks"
                )
            }

            var activeTaskUrl = candidateTaskUrls.first()
            var createBody = ""
            var createStatus = 0
            for (candUrl in candidateTaskUrls) {
                activeTaskUrl = candUrl
                val finalCreateUrl = buildUrlWithAuthQuery(profile, candUrl)
                onStatus("Submitting Seedance video task ($model)…", "")
                onRawWire(reqStr, "Dispatching to $finalCreateUrl ...")

                val createResp = httpClient.post(finalCreateUrl) {
                    applyAuthAndCustomHeaders(profile)
                    contentType(ContentType.Application.Json)
                    setBody(reqStr)
                }
                createBody = createResp.bodyAsText()
                createStatus = createResp.status.value
                onRawWire(reqStr, createBody)
                if (createResp.status.isSuccess()) break
                if (createStatus != 404 && createStatus != 405 && createStatus != 501) break
            }

            if (createStatus == 404 || createStatus == 405 || createStatus == 501) {
                // Proxy wraps Seedance inside /v1/chat/completions — fall back seamlessly!
                onStatus("Proxy wraps Seedance in /v1/chat/completions — routing via Chat Completions…", "")
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
                extractMarkdownVideosFromText(chatAccumulator.toString(), profile.mediaAspectRatio, onMedia)
                return
            }

            if (createStatus !in 200..299) {
                error("ByteDance Seedance HTTP $createStatus: $createBody")
            }

            val createObj = json.parseToJsonElement(createBody).jsonObject
            val taskId = createObj["id"]?.jsonPrimitive?.contentOrNull
                ?: (createObj["data"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                ?: error("Seedance response did not contain a task 'id': $createBody")

            var status = createObj["status"]?.jsonPrimitive?.contentOrNull ?: "queued"
            var latestTaskObj = createObj
            var pollCount = 0

            while (status.equals("queued", ignoreCase = true) ||
                status.equals("running", ignoreCase = true) ||
                status.equals("in_progress", ignoreCase = true) ||
                status.equals("processing", ignoreCase = true)
            ) {
                pollCount++
                onStatus("Seedance Video Generating ($status • poll #$pollCount)…", taskId)
                delay(3000L)
                val pollUrl = buildUrlWithAuthQuery(profile, "${activeTaskUrl.trimEnd('/')}/$taskId")
                val pollResp = httpClient.get(pollUrl) {
                    applyAuthAndCustomHeaders(profile)
                }
                val pollBody = pollResp.bodyAsText()
                onRawWire(reqStr, pollBody)
                if (!pollResp.status.isSuccess()) {
                    error("Seedance Task Poll HTTP ${pollResp.status.value}: $pollBody")
                }
                latestTaskObj = json.parseToJsonElement(pollBody).jsonObject
                status = latestTaskObj["status"]?.jsonPrimitive?.contentOrNull
                    ?: (latestTaskObj["data"] as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull
                    ?: "succeeded"
            }

            if (status.equals("failed", ignoreCase = true) || status.equals("cancelled", ignoreCase = true) || status.equals("canceled", ignoreCase = true)) {
                val errMsg = (latestTaskObj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: latestTaskObj.toString()
                error("Seedance Video Task $status: $errMsg")
            }

            val contentSub = (latestTaskObj["content"] as? JsonObject)
                ?: ((latestTaskObj["data"] as? JsonObject)?.get("content") as? JsonObject)
            val videoUrl = contentSub?.get("video_url")?.jsonPrimitive?.contentOrNull
                ?: latestTaskObj["video_url"]?.jsonPrimitive?.contentOrNull
                ?: latestTaskObj["result_url"]?.jsonPrimitive?.contentOrNull
                ?: error("Seedance task completed but no 'video_url' was found in response.")

            onStatus("Downloading generated Seedance MP4 video…", taskId)
            val videoBytes = runCatching {
                httpClient.get(videoUrl).readRawBytes()
            }.getOrElse { ByteArray(0) }

            val localPath = if (videoBytes.isNotEmpty()) {
                PlatformBridge.saveGeneratedMediaFile("seedance-$taskId.mp4", videoBytes)
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
            onContent("Generated Seedance video (`$model` • ${profile.mediaAspectRatio} • ${profile.videoDurationSeconds}s).")
        } else {
            // Seedream Image Generation API (`POST /api/v3/images/generations` & `/v1/images/generations`)
            val size = resolveSeedreamImageSize(profile.mediaAspectRatio, profile.mediaResolution)
            val payloadObj = buildJsonObject {
                put("model", model)
                put("prompt", latestUserPrompt)
                put("size", size)
                put("response_format", "b64_json")
                put("watermark", false)
            }

            val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
            val reqStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)
            val candidateImgUrls = if (profile.endpointPathOverride.isNotBlank()) {
                listOf("$baseClean${profile.endpointPathOverride}")
            } else if (baseClean.endsWith("/v1")) {
                listOf("$baseClean/images/generations", "$rootBase/api/v3/images/generations")
            } else {
                listOf("$rootBase/api/v3/images/generations", "$rootBase/v1/images/generations")
            }

            var bodyText = ""
            var lastStatus = 0
            for (candidate in candidateImgUrls) {
                val finalUrl = buildUrlWithAuthQuery(profile, candidate)
                onStatus("Generating Seedream image ($model • $size)…", "")
                onRawWire(reqStr, "Dispatching to $finalUrl ...")

                val resp = httpClient.post(finalUrl) {
                    applyAuthAndCustomHeaders(profile)
                    contentType(ContentType.Application.Json)
                    setBody(reqStr)
                }
                bodyText = resp.bodyAsText()
                lastStatus = resp.status.value
                onRawWire(reqStr, bodyText)
                if (resp.status.isSuccess()) break
                if (lastStatus != 404 && lastStatus != 405 && lastStatus != 501) break
            }

            if (lastStatus == 404 || lastStatus == 405 || lastStatus == 501) {
                // Proxy wraps Seedream in /v1/chat/completions — fall back seamlessly!
                onStatus("Proxy wraps Seedream in /v1/chat/completions — routing via Chat Completions…", "")
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
                error("ByteDance Seedream HTTP $lastStatus: $bodyText")
            }

            val root = json.parseToJsonElement(bodyText).jsonObject
            val dataArr = root["data"] as? JsonArray ?: JsonArray(emptyList())
            for (itemEl in dataArr) {
                val itemObj = itemEl as? JsonObject ?: continue
                val b64 = itemObj["b64_json"]?.jsonPrimitive?.contentOrNull ?: ""
                val remoteUrl = itemObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                val revised = itemObj["revised_prompt"]?.jsonPrimitive?.contentOrNull ?: ""
                saveAndEmitImage(b64, remoteUrl, "image/png", profile.mediaAspectRatio, revised, onMedia)
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
        // Fail fast on client-side bad request (HTTP 400) unless it is a rate-limit / overload / timeout wrapped in 400
        if (msg.contains("http 400") &&
            !msg.contains("rate") &&
            !msg.contains("overload") &&
            !msg.contains("timeout") &&
            !msg.contains("busy") &&
            !msg.contains("capacity") &&
            !msg.contains("try again")
        ) {
            return false
        }
        // Inspired by `omp`: treat all other network, socket, EOF, stream, rate-limit (429),
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
        passTag: String
    ): Pair<List<MessageNode>, String> {
        val hasPriorTurnProgress = executedTools.isNotEmpty() ||
            (continuePrefix.isBlank() && (thinkingText.isNotEmpty() || signatureText.isNotEmpty()))
        return if (hasPriorTurnProgress) {
            val partialVar = MessageVariant(
                id = "var-in-progress-$passTag",
                content = if (executedTools.isNotEmpty() && continuePrefix.isNotBlank()) continuePrefix else "",
                thinkingSummary = thinkingText,
                trailingThinking = if (executedTools.isNotEmpty()) trailingThinkingText else "",
                thinkingSignature = signatureText,
                toolCalls = executedTools,
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
                "[System Note: Continue seamlessly from your reasoning and tool executions above without repeating completed steps.]"
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

    private suspend fun extractMarkdownVideosFromText(
        text: String,
        aspectRatio: String,
        onMedia: suspend (GeneratedMediaItem) -> Unit
    ) {
        val urlRegex = Regex("""https?://[^\s)"'>]+\.mp4(?:\?[^\s)"'>]*)?""", RegexOption.IGNORE_CASE)
        for (match in urlRegex.findAll(text)) {
            val videoUrl = match.value.trim()
            val now = PlatformBridge.currentTimeMillis()
            val videoBytes = runCatching { httpClient.get(videoUrl).readRawBytes() }.getOrElse { ByteArray(0) }
            val localPath = if (videoBytes.isNotEmpty()) {
                PlatformBridge.saveGeneratedMediaFile("video-$now.mp4", videoBytes)
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
        onMedia: suspend (GeneratedMediaItem) -> Unit
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
            PlatformBridge.saveGeneratedMediaFile("img-$now-${kotlin.random.Random.nextInt(100, 999)}.$ext", imageBytes)
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
        onMedia: suspend (GeneratedMediaItem) -> Unit
    ) {
        if (!text.contains("![")) return
        val regex = Regex("""!\[[^\]]*\]\(([^)]+)\)""")
        for (match in regex.findAll(text)) {
            val target = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (target.startsWith("data:image/")) {
                val mime = target.substringAfter("data:").substringBefore(";").ifBlank { "image/png" }
                val b64 = target.substringAfter("base64,")
                saveAndEmitImage(b64, "", mime, aspectRatio, "", onMedia)
            } else if (target.startsWith("http://") || target.startsWith("https://")) {
                saveAndEmitImage("", target, "image/png", aspectRatio, "", onMedia)
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
                        ProviderProtocol.BYTEDANCE_ARK -> header("Authorization", "Bearer $key")
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
            val line = channel.readLine() ?: break
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
}
