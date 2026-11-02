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
    private const val MAX_PRE_TOKEN_RETRIES = 6
    private const val MAX_GOAL_NETWORK_RETRIES = 120
    private const val MAX_MID_STREAM_CONTINUATION_PASSES = 32
    private val GEMINI_4_REGEX = Regex("""(^|[/:_-])gemini-4(?:\.\d+)?-""")

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
        continueFromAssistantPrefix: String = ""
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

        val contentBuilder = StringBuilder(continueFromAssistantPrefix)
        val thinkingBuilder = StringBuilder()
        val signatureBuilder = StringBuilder()
        val executedTools = mutableListOf<ToolCallRecord>()
        val generatedMedia = mutableListOf<GeneratedMediaItem>()
        var lastInteractionId = ""
        var inputTokens = 0
        var cachedTokens = 0
        var reasoningTokens = 0
        var outputTokens = 0
        var lastRawReq = ""
        var lastRawResp = ""

        val initialGoalObjective = extractGoalObjectiveFromHistory(historyNodes)
        val circuitBreaker = WorkspaceHarness.LoopCircuitBreaker(
            initialGoal = if (initialGoalObjective.isNotBlank()) {
                GoalTrackerState(goalObjective = initialGoalObjective)
            } else null
        )
        circuitBreaker.goalState?.let { emit(StreamUpdate.GoalProgress(it)) }

        PlatformBridge.notifyLongDecodeStart(model)

        try {
            var currentContinuePrefix = continueFromAssistantPrefix
            var retryAttempt = 0
            var continuationPass = 0

            while (true) {
                val charsBeforePass = contentBuilder.length
                val toolsBeforePass = executedTools.size
                val mediaBeforePass = generatedMedia.size

                try {
                    when (profile.protocol) {
                        ProviderProtocol.GEMINI_INTERACTIONS -> {
                            suspend fun runLegacyGeminiGenerateContent() {
                                executeGeminiGenerateContentLoop(
                                    profile = profile,
                                    model = model,
                                    session = session,
                                    historyNodes = historyNodes,
                                    continuePrefix = currentContinuePrefix,
                                    circuitBreaker = circuitBreaker,
                                    onStatus = { statusMsg ->
                                        emit(StreamUpdate.Status(statusMsg, lastInteractionId))
                                    },
                                    onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                    onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                    onThinking = { delta, sig ->
                                        thinkingBuilder.append(delta)
                                        if (sig.isNotEmpty()) signatureBuilder.append(sig)
                                        emit(StreamUpdate.ThinkingDelta(delta, sig))
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
                                        executedTools.add(tc)
                                        emit(StreamUpdate.ToolCallEvent(tc))
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

                            if (profile.useLegacyFallbackEndpoint) {
                                runLegacyGeminiGenerateContent()
                            } else {
                                try {
                                    executeGeminiInteractionsLoop(
                                        profile = profile,
                                        model = model,
                                        session = session,
                                        historyNodes = historyNodes,
                                        continuePrefix = currentContinuePrefix,
                                        circuitBreaker = circuitBreaker,
                                        onStatus = { statusMsg, id ->
                                            if (id.isNotBlank()) lastInteractionId = id
                                            emit(StreamUpdate.Status(statusMsg, lastInteractionId))
                                        },
                                        onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                        onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                        onThinking = { delta, sig ->
                                            thinkingBuilder.append(delta)
                                            if (sig.isNotEmpty()) signatureBuilder.append(sig)
                                            emit(StreamUpdate.ThinkingDelta(delta, sig))
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
                                            executedTools.add(tc)
                                            emit(StreamUpdate.ToolCallEvent(tc))
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
                                    if (contentBuilder.length == charsBeforePass && isEndpointUnsupportedForAutoFallback(e)) {
                                        emit(StreamUpdate.Status("Endpoint does not support /v1beta/interactions — auto-falling back to :streamGenerateContent…"))
                                        runLegacyGeminiGenerateContent()
                                    } else {
                                        throw e
                                    }
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

                            suspend fun runLegacyOpenAiChatCompletions() {
                                executeOpenAiChatCompletionsLoop(
                                    profile = profile,
                                    model = model,
                                    session = session,
                                    historyNodes = historyNodes,
                                    continuePrefix = currentContinuePrefix,
                                    circuitBreaker = circuitBreaker,
                                    onStatus = { st -> emit(StreamUpdate.Status(st)) },
                                    onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                    onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                    onThinking = { delta ->
                                        thinkingBuilder.append(delta)
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
                                        executedTools.add(tc)
                                        emit(StreamUpdate.ToolCallEvent(tc))
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
                            } else if (profile.useLegacyFallbackEndpoint) {
                                runLegacyOpenAiChatCompletions()
                            } else {
                                try {
                                    executeOpenAiResponsesLoop(
                                        profile = profile,
                                        model = model,
                                        session = session,
                                        historyNodes = historyNodes,
                                        continuePrefix = currentContinuePrefix,
                                        circuitBreaker = circuitBreaker,
                                        onStatus = { st -> emit(StreamUpdate.Status(st, lastInteractionId)) },
                                        onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                        onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                        onThinking = { delta ->
                                            thinkingBuilder.append(delta)
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
                                            executedTools.add(tc)
                                            emit(StreamUpdate.ToolCallEvent(tc))
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
                                    if (contentBuilder.length == charsBeforePass && isEndpointUnsupportedForAutoFallback(e)) {
                                        emit(StreamUpdate.Status("Proxy does not support /v1/responses — auto-falling back to /v1/chat/completions…"))
                                        runLegacyOpenAiChatCompletions()
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
                                historyNodes = historyNodes,
                                continuePrefix = currentContinuePrefix,
                                circuitBreaker = circuitBreaker,
                                onStatus = { st -> emit(StreamUpdate.Status(st, lastInteractionId)) },
                                onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                onThinking = { delta, sig ->
                                    thinkingBuilder.append(delta)
                                    if (sig.isNotEmpty()) signatureBuilder.append(sig)
                                    emit(StreamUpdate.ThinkingDelta(delta, sig))
                                },
                                onContent = { delta ->
                                    contentBuilder.append(delta)
                                    val approxOut = (contentBuilder.length / 4).coerceAtLeast(outputTokens)
                                    val elapsedSec = ((PlatformBridge.currentTimeMillis() - startTime) / 1000L).toInt()
                                    PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                    emit(StreamUpdate.ContentDelta(delta))
                                },
                                onToolCall = { tc ->
                                    executedTools.add(tc)
                                    emit(StreamUpdate.ToolCallEvent(tc))
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
                                    historyNodes = historyNodes,
                                    continuePrefix = currentContinuePrefix,
                                    circuitBreaker = circuitBreaker,
                                    onStatus = { st -> emit(StreamUpdate.Status(st, lastInteractionId)) },
                                    onGoalProgress = { gs -> emit(StreamUpdate.GoalProgress(gs)) },
                                    onFirstToken = { if (firstTokenTime == 0L) firstTokenTime = PlatformBridge.currentTimeMillis() },
                                    onThinking = { delta ->
                                        thinkingBuilder.append(delta)
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
                                        executedTools.add(tc)
                                        emit(StreamUpdate.ToolCallEvent(tc))
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
                    val producedNewContent = contentBuilder.length > charsBeforePass
                    val producedMediaOrTools = generatedMedia.size > mediaBeforePass || executedTools.size > toolsBeforePass
                    val isRetryable = isTransientOrProxyTimeoutError(err)
                    val isLongGoalRun = circuitBreaker.goalState != null
                    val maxRetries = if (isLongGoalRun) MAX_GOAL_NETWORK_RETRIES else MAX_PRE_TOKEN_RETRIES

                    if (!producedNewContent && !producedMediaOrTools && isRetryable && retryAttempt < maxRetries) {
                        retryAttempt++
                        val backoffMs = (2000L * retryAttempt).coerceAtMost(60_000L)
                        emit(
                            StreamUpdate.Status(
                                "Network/gateway timeout during reasoning — auto-retrying ($retryAttempt/$maxRetries in ${backoffMs / 1000}s)…",
                                lastInteractionId
                            )
                        )
                        delay(backoffMs)
                        continue
                    } else if ((producedNewContent || producedMediaOrTools) && isRetryable && (isLongGoalRun || continuationPass < MAX_MID_STREAM_CONTINUATION_PASSES)) {
                        // Defeat proxy/Cloudflare stream cutoffs or overnight Wi-Fi drops by seamlessly stitching a continuation pass
                        continuationPass++
                        retryAttempt = 0
                        currentContinuePrefix = contentBuilder.toString()
                        emit(
                            StreamUpdate.Status(
                                "Stream interrupted — seamlessly continuing generation (pass ${continuationPass + 1})…",
                                lastInteractionId
                            )
                        )
                        delay(2000L)
                        continue
                    } else {
                        throw err
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
        val activeNodes = historyNodes.filter { it.includedInContext }
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
        val extraLoopSteps = mutableListOf<JsonObject>()
        var pendingToolResults: List<ToolCallRecord> = emptyList()
        var pendingStopHookPrompt: String = ""
        var loopStep = 0

        val lowerModel = model.lowercase()
        val isGemini4 = GEMINI_4_REGEX.containsMatchIn(lowerModel) || lowerModel.startsWith("gemini-4")

        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val baseInputSteps = if (currentPrevInteractionId.isNotBlank() && previousAssistantIdx >= 0 && previousAssistantIdx < activeNodes.size - 1) {
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

                if (currentPrevInteractionId.isNotBlank()) {
                    put("previous_interaction_id", currentPrevInteractionId)
                }

                // Build `input` using official Interactions API Steps schema
                if (pendingToolResults.isNotEmpty() && currentPrevInteractionId.isNotBlank()) {
                    putJsonArray("input") {
                        for (tr in pendingToolResults) {
                            add(buildJsonObject {
                                put("type", "function_result")
                                put("name", tr.name)
                                put("call_id", tr.id)
                                put("result", tr.resultText)
                            })
                        }
                    }
                } else if (pendingStopHookPrompt.isNotBlank() && currentPrevInteractionId.isNotBlank()) {
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

                if (profile.enableImageGeneration) {
                    putJsonArray("response_modalities") {
                        add(JsonPrimitive("TEXT"))
                        add(JsonPrimitive("IMAGE"))
                    }
                }

                // Generation config (nullable parameters are stripped when null or <= 0)
                putJsonObject("generation_config") {
                    if (!isGemini4) {
                        profile.temperature?.let { put("temperature", it) }
                        profile.topP?.let { put("top_p", it) }
                    }
                    val maxOut = profile.maxOutputTokens
                    if (maxOut != null && maxOut > 0) {
                        put("max_output_tokens", maxOut)
                    }
                    val rawEffort = profile.thinkingEffort.trim()
                    val lowerEffort = rawEffort.lowercase()
                    if (rawEffort.isNotEmpty() && lowerEffort != "none" && lowerEffort != "off" && lowerEffort != "default") {
                        val mappedLevel = if (isGemini4) {
                            when (lowerEffort) {
                                "minimal", "low" -> "low"
                                "medium" -> "medium"
                                "high", "xhigh", "max" -> "high"
                                else -> rawEffort
                            }
                        } else {
                            when (lowerEffort) {
                                "xhigh", "max" -> "high"
                                else -> rawEffort
                            }
                        }
                        put("thinking_level", mappedLevel)
                        put("thinking_summaries", "auto")
                    }
                    if (profile.enableImageGeneration && profile.mediaAspectRatio.isNotBlank()) {
                        putJsonObject("image_config") {
                            put("aspect_ratio", profile.mediaAspectRatio)
                            if (profile.mediaResolution in listOf("1K", "2K", "4K")) {
                                put("image_size", profile.mediaResolution)
                            }
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

            val turnToolCalls = mutableListOf< Pair<String, Pair<String, String>> >() // callId -> (name, argsJson)
            val roundThoughtSig = StringBuilder()
            var receivedAnyStreamContent = false

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
                    parseCompletedGeminiInteractionObject(
                        interactionObj = interactionObj,
                        onFirstToken = onFirstToken,
                        onThinking = { th, sig ->
                            if (sig.isNotEmpty()) roundThoughtSig.append(sig)
                            onThinking(th, sig)
                        },
                        onContent = { txt ->
                            receivedAnyStreamContent = true
                            onContent(txt)
                        },
                        onMedia = onMedia,
                        onFunctionCall = { callId, fnName, argsStr ->
                            turnToolCalls.add(callId to (fnName to argsStr))
                        },
                        onUsage = { id, inTok, cacheTok, thinkTok, outTok ->
                            if (id.isNotBlank()) currentPrevInteractionId = id
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
                    var currentStreamStepType = ""

                    readSseOrJsonFallback(
                        channel = channel,
                        onSseEvent = { eventType, dataLine ->
                            if (dataLine == "[DONE]") return@readSseOrJsonFallback
                            onRawWire(requestJsonStr, dataLine)
                            val evObj = runCatching { json.parseToJsonElement(dataLine).jsonObject }.getOrNull() ?: return@readSseOrJsonFallback
                            val type = evObj["event_type"]?.jsonPrimitive?.contentOrNull
                                ?: evObj["type"]?.jsonPrimitive?.contentOrNull
                                ?: eventType

                            // Extract interaction ID if present
                            val interactionSub = evObj["interaction"] as? JsonObject
                            val idCandidate = interactionSub?.get("id")?.jsonPrimitive?.contentOrNull
                                ?: evObj["id"]?.jsonPrimitive?.contentOrNull
                            if (!idCandidate.isNullOrBlank()) {
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
                                    val cType = contentObj?.get("type")?.jsonPrimitive?.contentOrNull ?: ""
                                    if (cType.isNotBlank()) currentStreamStepType = cType
                                    if (cType == "function_call") {
                                        val callId = contentObj?.get("id")?.jsonPrimitive?.contentOrNull
                                            ?: contentObj?.get("call_id")?.jsonPrimitive?.contentOrNull
                                            ?: "call_$idx"
                                        val fnName = contentObj?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                                        val argEl = contentObj?.get("arguments")
                                        val initialArgs = when {
                                            argEl is JsonPrimitive -> argEl.content
                                            argEl is JsonObject && argEl.isNotEmpty() -> argEl.toString()
                                            else -> ""
                                        }
                                        pendingFnArgsByIndex[idx] = callId to (fnName to StringBuilder(initialArgs))
                                    } else if (cType == "thought") {
                                        val sig = contentObj?.get("signature")?.jsonPrimitive?.contentOrNull ?: ""
                                        if (sig.isNotEmpty()) {
                                            roundThoughtSig.append(sig)
                                            onThinking("", sig)
                                        }
                                        val summaryArr = (contentObj?.get("summary") as? JsonArray)
                                            ?: (contentObj?.get("content") as? JsonArray)
                                        summaryArr?.forEach { sEl ->
                                            val txt = (sEl as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                                            if (txt.isNotEmpty()) {
                                                onFirstToken()
                                                onThinking(txt, "")
                                            }
                                        }
                                    } else if (cType == "model_output") {
                                        val contentArr = contentObj?.get("content") as? JsonArray
                                        contentArr?.forEach { cEl ->
                                            val txt = (cEl as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                                            if (txt.isNotEmpty()) {
                                                onFirstToken()
                                                receivedAnyStreamContent = true
                                                onContent(txt)
                                            }
                                        }
                                    }
                                }
                                "content.delta", "step.delta" -> {
                                    val idx = evObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                    val deltaObj = evObj["delta"] as? JsonObject ?: evObj
                                    val deltaType = deltaObj["type"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val sigDelta = deltaObj["signature"]?.jsonPrimitive?.contentOrNull ?: ""
                                    if (sigDelta.isNotEmpty()) {
                                        roundThoughtSig.append(sigDelta)
                                        onThinking("", sigDelta)
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
                                                        if (currentStreamStepType == "thought") {
                                                            onThinking(txt, "")
                                                        } else {
                                                            receivedAnyStreamContent = true
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
                                        when (deltaType) {
                                            "text" -> {
                                                val txt = deltaObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                                if (txt.isNotEmpty()) {
                                                    onFirstToken()
                                                    if (currentStreamStepType == "thought") {
                                                        onThinking(txt, "")
                                                    } else {
                                                        receivedAnyStreamContent = true
                                                        onContent(txt)
                                                    }
                                                }
                                            }
                                            "thought_summary", "thought", "thinking" -> {
                                                val thoughtTxt = (deltaObj["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                                                    ?: deltaObj["text"]?.jsonPrimitive?.contentOrNull
                                                    ?: deltaObj["thought"]?.jsonPrimitive?.contentOrNull
                                                    ?: ""
                                                if (thoughtTxt.isNotEmpty()) {
                                                    onFirstToken()
                                                    onThinking(thoughtTxt, "")
                                                }
                                            }
                                            "thought_signature" -> {
                                                // Already handled via sigDelta above
                                            }
                                            "image" -> {
                                                val b64 = deltaObj["data"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val uri = deltaObj["uri"]?.jsonPrimitive?.contentOrNull
                                                    ?: deltaObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val mime = deltaObj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                                                saveAndEmitImage(b64, uri, mime, profile.mediaAspectRatio, "", onMedia)
                                            }
                                            "function_call", "arguments_delta" -> {
                                                val callId = deltaObj["id"]?.jsonPrimitive?.contentOrNull
                                                    ?: deltaObj["call_id"]?.jsonPrimitive?.contentOrNull
                                                    ?: pendingFnArgsByIndex[idx]?.first
                                                    ?: "call_$idx"
                                                val fnName = deltaObj["name"]?.jsonPrimitive?.contentOrNull
                                                    ?: pendingFnArgsByIndex[idx]?.second?.first
                                                    ?: ""
                                                val argChunk = deltaObj["arguments"]?.let {
                                                    if (it is JsonPrimitive) it.content else it.toString()
                                                } ?: ""
                                                val existing = pendingFnArgsByIndex[idx]
                                                if (existing == null) {
                                                    pendingFnArgsByIndex[idx] = callId to (fnName to StringBuilder(argChunk))
                                                } else {
                                                    val updatedName = fnName.ifBlank { existing.second.first }
                                                    existing.second.second.append(argChunk)
                                                    pendingFnArgsByIndex[idx] = callId to (updatedName to existing.second.second)
                                                }
                                            }
                                            else -> {
                                                val fallbackTxt = deltaObj["text"]?.jsonPrimitive?.contentOrNull
                                                if (!fallbackTxt.isNullOrEmpty()) {
                                                    onFirstToken()
                                                    if (currentStreamStepType == "thought") {
                                                        onThinking(fallbackTxt, "")
                                                    } else {
                                                        onContent(fallbackTxt)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                "interaction.complete", "interaction.completed" -> {
                                    val completedObj = interactionSub ?: evObj
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
                                        parseCompletedGeminiInteractionObject(
                                            interactionObj = evObj,
                                            onFirstToken = onFirstToken,
                                            onThinking = { th, sig ->
                                                if (sig.isNotEmpty()) roundThoughtSig.append(sig)
                                                onThinking(th, sig)
                                            },
                                            onContent = onContent,
                                            onMedia = onMedia,
                                            onFunctionCall = { callId, fnName, argsStr ->
                                                turnToolCalls.add(callId to (fnName to argsStr))
                                            },
                                            onUsage = { id, inTok, cacheTok, thinkTok, outTok ->
                                                if (id.isNotBlank()) currentPrevInteractionId = id
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
                            if (id.isNotBlank()) currentPrevInteractionId = id
                            val st = rootObj["status"]?.jsonPrimitive?.contentOrNull ?: "completed"
                            if ((st == "in_progress" || st == "queued" || st == "processing") && currentPrevInteractionId.isNotBlank()) {
                                pollInteractionUntilComplete(currentPrevInteractionId)
                            } else {
                                parseCompletedGeminiInteractionObject(
                                    interactionObj = rootObj,
                                    onFirstToken = onFirstToken,
                                    onThinking = { th, sig ->
                                        if (sig.isNotEmpty()) roundThoughtSig.append(sig)
                                        onThinking(th, sig)
                                    },
                                    onContent = { txt ->
                                        receivedAnyStreamContent = true
                                        onContent(txt)
                                    },
                                    onMedia = onMedia,
                                    onFunctionCall = { callId, fnName, argsStr ->
                                        turnToolCalls.add(callId to (fnName to argsStr))
                                    },
                                    onUsage = { uId, inTok, cacheTok, thinkTok, outTok ->
                                        if (uId.isNotBlank()) currentPrevInteractionId = uId
                                        onUsage(uId, inTok, cacheTok, thinkTok, outTok)
                                    }
                                )
                            }
                        }
                    )

                    for ((_, pair) in pendingFnArgsByIndex.entries.sortedBy { it.key }) {
                        val callId = pair.first
                        val fnName = pair.second.first
                        val argsStr = pair.second.second.toString().ifBlank { "{}" }
                        if (fnName.isNotBlank()) {
                            turnToolCalls.add(callId to (fnName to argsStr))
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (profile.useBackgroundLongDecode && currentPrevInteractionId.isNotBlank() && isTransientOrProxyTimeoutError(e)) {
                    pollInteractionUntilComplete(currentPrevInteractionId)
                } else {
                    throw e
                }
            }

            if (turnToolCalls.isEmpty()) {
                val stopHookPrompt = circuitBreaker.evaluateStopHookPrompt()
                if (stopHookPrompt != null) {
                    pendingToolResults = emptyList()
                    pendingStopHookPrompt = stopHookPrompt
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

            // Record thought + function_call + function_result steps for next round
            if (roundThoughtSig.isNotEmpty()) {
                extraLoopSteps.add(buildJsonObject {
                    put("type", "thought")
                    put("signature", roundThoughtSig.toString())
                })
            }

            val results = mutableListOf<ToolCallRecord>()
            for ((callId, fnPair) in turnToolCalls) {
                val parsedArgs = runCatching { json.parseToJsonElement(fnPair.second).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
                extraLoopSteps.add(buildJsonObject {
                    put("id", callId)
                    put("type", "function_call")
                    put("name", fnPair.first)
                    put("arguments", parsedArgs)
                })

                val rawRecord = WorkspaceHarness.executeToolCall(
                    workspaceRoot = session.workspacePath,
                    mode = session.workspaceMode,
                    callId = callId,
                    toolName = fnPair.first,
                    argumentsJson = fnPair.second
                )
                val record = circuitBreaker.onToolExecuted(rawRecord)
                circuitBreaker.goalState?.let { onGoalProgress(it) }
                onToolCall(record)
                results.add(record)

                extraLoopSteps.add(buildJsonObject {
                    put("type", "function_result")
                    put("name", record.name)
                    put("call_id", record.id)
                    put("result", record.resultText)
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
     * - User turns -> `{"type": "user_input", "content": [{"type": "text", "text": "..."}]}`
     * - Assistant turns -> optional `{"type": "thought", "signature": "..."}` +
     *   `{"type": "model_output", "content": [{"type": "text", "text": "..."}]}`
     */
    private fun buildGeminiInteractionsTurnsArray(
        nodes: List<MessageNode>,
        session: ChatSession,
        continuePrefix: String
    ): JsonArray = buildJsonArray {
        val withAuthorsNote = injectAuthorsNoteIntoHistory(nodes, session)
        for ((idx, pair) in withAuthorsNote.withIndex()) {
            val (role, text) = pair
            val nodeVar = nodes.getOrNull(idx)?.activeVariant
            val imageAttachments = resolveImageAttachments(nodeVar)

            if (role == MessageRole.ASSISTANT) {
                val sig = nodeVar?.thinkingSignature.orEmpty()
                if (sig.isNotBlank()) {
                    add(buildJsonObject {
                        put("type", "thought")
                        put("signature", sig)
                    })
                }
                add(buildJsonObject {
                    put("type", "model_output")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text.ifBlank { "..." })
                        })
                    }
                })
            } else {
                add(buildJsonObject {
                    put("type", "user_input")
                    putJsonArray("content") {
                        if (text.isNotBlank() || imageAttachments.isEmpty()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            })
                        }
                        for ((mime, b64) in imageAttachments) {
                            add(buildJsonObject {
                                put("type", "image")
                                put("mime_type", mime)
                                put("data", b64)
                            })
                        }
                    }
                })
            }
        }
        if (continuePrefix.isNotBlank()) {
            add(buildJsonObject {
                put("type", "user_input")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put(
                            "text",
                            "[System Note: Continue the narrative seamlessly from the exact end of this partial passage without repeating it:\n\"$continuePrefix\"]"
                        )
                    })
                }
            })
        }
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

        val activeNodes = historyNodes.filter { it.includedInContext }
        val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)
        val turns = injectAuthorsNoteIntoHistory(activeNodes, session)

        // Rolling conversation contents for multi-round tool execution
        val rollingContents = mutableListOf<JsonObject>()
        for ((idx, pair) in turns.withIndex()) {
            val (role, text) = pair
            val nodeVar = activeNodes.getOrNull(idx)?.activeVariant
            val imageAttachments = resolveImageAttachments(nodeVar)
            val savedSig = nodeVar?.thinkingSignature.orEmpty()

            rollingContents.add(buildJsonObject {
                put("role", if (role == MessageRole.ASSISTANT) "model" else "user")
                putJsonArray("parts") {
                    if (text.isNotBlank() || imageAttachments.isEmpty()) {
                        add(buildJsonObject {
                            put("text", text.ifBlank { "..." })
                            if (role == MessageRole.ASSISTANT && savedSig.isNotBlank()) {
                                put("thoughtSignature", savedSig)
                            }
                        })
                    }
                    for ((mime, b64) in imageAttachments) {
                        add(buildJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", mime)
                                put("data", b64)
                            }
                        })
                    }
                }
            })
        }
        if (continuePrefix.isNotBlank()) {
            rollingContents.add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put("text", "Continue seamlessly from this exact text without repeating it:\n$continuePrefix")
                    })
                }
            })
        }

        val hasBuiltInTools = profile.enableWebSearch || profile.enableCodeExecution || profile.enableUrlContext

        var loopStep = 0
        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val activeWorkspaceTools = WorkspaceHarness.activeToolsForMode(
                session.workspaceMode,
                session.workspacePath,
                profile.allowShellExecution,
                goalModeActive = circuitBreaker.goalState != null
            )

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
                    // Gemini 4 Argon enforces SamplingPolicy.never (rejects or ignores temperature/topP)
                    if (!isGemini4) {
                        profile.temperature?.let { put("temperature", it) }
                        profile.topP?.let { put("topP", it) }
                    }
                    val maxOut = profile.maxOutputTokens
                    if (maxOut != null && maxOut > 0) {
                        put("maxOutputTokens", maxOut)
                    }
                    if (profile.enableImageGeneration) {
                        putJsonArray("responseModalities") {
                            add(JsonPrimitive("TEXT"))
                            add(JsonPrimitive("IMAGE"))
                        }
                    }

                    // Thinking configuration: Gemini 4 / Gemini 3 / Gemma 4 use thinkingLevel (NEVER thinkingBudget!)
                    if (!profile.enableImageGeneration || !lowerModel.contains("pro-image")) {
                        val rawEffort = profile.thinkingEffort.trim()
                        val lowerEffort = rawEffort.lowercase()
                        val thinkBudget = profile.thinkingBudgetTokens
                        if (isThinkingLevelDialect) {
                            val hideThoughts = lowerEffort == "none" || lowerEffort == "off"
                            val resolvedLevel = when {
                                isGemini4 -> {
                                    // Gemini 4 Argon supports LOW, MEDIUM, HIGH (rejects MINIMAL with HTTP 400)
                                    when (lowerEffort) {
                                        "none", "off", "minimal", "low" -> "LOW"
                                        "medium", "default", "auto", "" -> if (lowerModel.contains("cyber-permissive")) "HIGH" else "MEDIUM"
                                        "high", "xhigh", "max" -> "HIGH"
                                        else -> rawEffort.uppercase()
                                    }
                                }
                                else -> {
                                    // Gemini 3.x / Gemma 4
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
                            // Gemini 2.5 budget dialect
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

            val roundModelParts = mutableListOf<JsonObject>()
            val roundToolCalls = mutableListOf<Triple<String, String, String>>() // (callId, name, argsJson)

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
                            for (p in parts) {
                                val partObj = p as? JsonObject ?: continue
                                val isThought = partObj["thought"]?.jsonPrimitive?.contentOrNull == "true"
                                val sig = partObj["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                                    ?: partObj["thought_signature"]?.jsonPrimitive?.contentOrNull ?: ""
                                val txt = partObj["text"]?.jsonPrimitive?.contentOrNull ?: ""

                                if (!isThought) {
                                    roundModelParts.add(partObj)
                                }

                                if (txt.isNotEmpty() || sig.isNotEmpty()) {
                                    onFirstToken()
                                    if (isThought) {
                                        onThinking(txt, sig)
                                    } else {
                                        if (sig.isNotEmpty()) onThinking("", sig)
                                        if (txt.isNotEmpty()) onContent(txt)
                                    }
                                }

                                val fcObj = partObj["functionCall"] as? JsonObject
                                if (fcObj != null) {
                                    val callId = fcObj["id"]?.jsonPrimitive?.contentOrNull
                                        ?: "call_${roundToolCalls.size + 1}"
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
                                        saveAndEmitImage(b64, "", mime, profile.mediaAspectRatio, "", onMedia)
                                    }
                                }
                            }
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
                        parts?.forEach { p ->
                            val partObj = p as? JsonObject ?: return@forEach
                            val isThought = partObj["thought"]?.jsonPrimitive?.contentOrNull == "true"
                            val txt = partObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                            if (txt.isNotEmpty()) {
                                onFirstToken()
                                if (isThought) onThinking(txt, "") else onContent(txt)
                            }
                        }
                    }
                )
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

            // Ensure at least the first functionCall part has a thoughtSignature (required by Gemini 3+ / 4)
            val signedModelParts = roundModelParts.mapIndexed { idx, part ->
                if (idx == 0 && part.containsKey("functionCall") && !part.containsKey("thoughtSignature") && !part.containsKey("thought_signature")) {
                    val mutable = part.toMutableMap()
                    mutable["thoughtSignature"] = JsonPrimitive("context_engineering_is_the_way_to_go")
                    JsonObject(mutable)
                } else part
            }
            rollingContents.add(buildJsonObject {
                put("role", "model")
                put("parts", JsonArray(signedModelParts))
            })

            val responseParts = mutableListOf<JsonObject>()
            for ((callId, fnName, argsJson) in roundToolCalls) {
                val rawRec = WorkspaceHarness.executeToolCall(
                    workspaceRoot = session.workspacePath,
                    mode = session.workspaceMode,
                    callId = callId,
                    toolName = fnName,
                    argumentsJson = argsJson
                )
                val rec = circuitBreaker.onToolExecuted(rawRec)
                circuitBreaker.goalState?.let { onGoalProgress(it) }
                onToolCall(rec)
                responseParts.add(buildJsonObject {
                    putJsonObject("functionResponse") {
                        put("name", fnName)
                        if (!callId.startsWith("call_")) {
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
        val activeNodes = historyNodes.filter { it.includedInContext }
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
        var loopStep = 0

        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val baseInputItems = if (currentPrevResponseId.isNotBlank() && previousAssistantIdx >= 0 && previousAssistantIdx < activeNodes.size - 1) {
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

                if (currentPrevResponseId.isNotBlank()) {
                    put("previous_response_id", currentPrevResponseId)
                }

                if (pendingToolResults.isNotEmpty() && currentPrevResponseId.isNotBlank()) {
                    putJsonArray("input") {
                        for (tr in pendingToolResults) {
                            add(buildJsonObject {
                                put("type", "function_call_output")
                                put("call_id", tr.id)
                                put("output", tr.resultText)
                            })
                        }
                    }
                } else if (pendingStopHookPrompt.isNotBlank() && currentPrevResponseId.isNotBlank()) {
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
                if (effort.isNotEmpty() && !effort.equals("none", ignoreCase = true) && !effort.equals("default", ignoreCase = true)) {
                    putJsonObject("reasoning") {
                        put("effort", effort)
                        put("summary", "auto")
                    }
                } else {
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

            val turnToolCalls = mutableListOf<Pair<String, Pair<String, String>>>()

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
                                if (id.isNotBlank()) currentPrevResponseId = id
                            }
                            "response.output_text.delta" -> {
                                val delta = evObj["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                                if (delta.isNotEmpty()) {
                                    onFirstToken()
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
                                    val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: itemId
                                    val name = item["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                    fnBuildersByItemId[itemId] = callId to (name to StringBuilder())
                                }
                            }
                            "response.function_call_arguments.delta" -> {
                                val itemId = evObj["item_id"]?.jsonPrimitive?.contentOrNull ?: ""
                                val delta = evObj["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                                fnBuildersByItemId[itemId]?.second?.second?.append(delta)
                            }
                            "response.output_item.done" -> {
                                val item = evObj["item"] as? JsonObject
                                val itemType = item?.get("type")?.jsonPrimitive?.contentOrNull
                                if (itemType == "function_call") {
                                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                        ?: item["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val name = item["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val args = item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                                    if (name.isNotBlank()) {
                                        turnToolCalls.add(callId to (name to args))
                                    }
                                } else if (itemType == "image_generation_call") {
                                    val b64 = item["result"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val revised = item["revised_prompt"]?.jsonPrimitive?.contentOrNull ?: ""
                                    if (b64.isNotBlank()) {
                                        saveAndEmitImage(b64, "", "image/png", profile.mediaAspectRatio, revised, onMedia)
                                    }
                                }
                            }
                            "response.completed" -> {
                                val respSub = evObj["response"] as? JsonObject
                                val id = respSub?.get("id")?.jsonPrimitive?.contentOrNull ?: currentPrevResponseId
                                if (id.isNotBlank()) currentPrevResponseId = id
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
                        if (id.isNotBlank()) currentPrevResponseId = id
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
                                            onContent(txt)
                                        }
                                    }
                                }
                                "function_call" -> {
                                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val name = item["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val args = item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                                    if (name.isNotBlank()) turnToolCalls.add(callId to (name to args))
                                }
                                "image_generation_call" -> {
                                    val b64 = item["result"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val revised = item["revised_prompt"]?.jsonPrimitive?.contentOrNull ?: ""
                                    if (b64.isNotBlank()) {
                                        saveAndEmitImage(b64, "", "image/png", profile.mediaAspectRatio, revised, onMedia)
                                    }
                                }
                            }
                        }
                    }
                )
            }

            if (turnToolCalls.isEmpty()) {
                val stopHookPrompt = circuitBreaker.evaluateStopHookPrompt()
                if (stopHookPrompt != null) {
                    pendingToolResults = emptyList()
                    pendingStopHookPrompt = stopHookPrompt
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

            val results = mutableListOf<ToolCallRecord>()
            for ((callId, fnPair) in turnToolCalls) {
                extraResponsesLoopItems.add(buildJsonObject {
                    put("type", "function_call")
                    put("call_id", callId)
                    put("name", fnPair.first)
                    put("arguments", fnPair.second)
                })
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
     * - Assistant turns use `{"type": "message", "role": "assistant", "status": "completed", "content": [{"type": "output_text", "text": "..."}]}`
     * - User turns use `{"role": "user", "content": "..."}` or multimodal `input_text` + `input_image` parts.
     */
    private fun buildOpenAiResponsesInputArray(
        nodes: List<MessageNode>,
        session: ChatSession,
        continuePrefix: String
    ): JsonArray = buildJsonArray {
        val withAuthorsNote = injectAuthorsNoteIntoHistory(nodes, session)
        for ((idx, pair) in withAuthorsNote.withIndex()) {
            val (role, text) = pair
            val imageAttachments = resolveImageAttachments(nodes.getOrNull(idx)?.activeVariant)

            if (role == MessageRole.ASSISTANT) {
                add(buildJsonObject {
                    put("type", "message")
                    put("role", "assistant")
                    put("status", "completed")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "output_text")
                            put("text", text.ifBlank { "..." })
                        })
                    }
                })
            } else {
                add(buildJsonObject {
                    put("role", "user")
                    if (imageAttachments.isEmpty()) {
                        put("content", text)
                    } else {
                        putJsonArray("content") {
                            if (text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "input_text")
                                    put("text", text)
                                })
                            }
                            for ((mime, b64) in imageAttachments) {
                                add(buildJsonObject {
                                    put("type", "input_image")
                                    put("image_url", "data:$mime;base64,$b64")
                                })
                            }
                        }
                    }
                })
            }
        }
        if (continuePrefix.isNotBlank()) {
            add(buildJsonObject {
                put("role", "user")
                put(
                    "content",
                    "[Continue the narrative seamlessly from the exact end of this partial passage without repeating it:\n\"$continuePrefix\"]"
                )
            })
        }
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
        val activeNodes = historyNodes.filter { it.includedInContext }
        val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)
        val turns = injectAuthorsNoteIntoHistory(activeNodes, session)
        val extraToolMessages = mutableListOf<JsonObject>()
        val fullTextAccumulator = StringBuilder()

        var loopStep = 0
        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val compactedToolMessages = compactChatCompletionsToolMessages(extraToolMessages)
            val payloadObj = buildJsonObject {
                put("model", model)
                put("stream", true)
                putJsonObject("stream_options") {
                    put("include_usage", true)
                }
                profile.temperature?.let { put("temperature", it) }
                profile.topP?.let { put("top_p", it) }
                val maxOut = profile.maxOutputTokens
                if (maxOut != null && maxOut > 0) {
                    put("max_tokens", maxOut)
                }
                val effort = profile.thinkingEffort.trim()
                if (effort.isNotEmpty() && !effort.equals("none", ignoreCase = true) && !effort.equals("default", ignoreCase = true)) {
                    put("reasoning_effort", effort)
                }
                if (profile.enableImageGeneration) {
                    putJsonArray("modalities") {
                        add(JsonPrimitive("text"))
                        add(JsonPrimitive("image"))
                    }
                }
                putJsonArray("messages") {
                    if (systemInstruction.isNotBlank()) {
                        add(buildJsonObject {
                            put("role", "system")
                            put("content", systemInstruction)
                        })
                    }
                    for ((idx, pair) in turns.withIndex()) {
                        val (role, text) = pair
                        val imageAttachments = resolveImageAttachments(activeNodes.getOrNull(idx)?.activeVariant)
                        add(buildJsonObject {
                            put("role", if (role == MessageRole.ASSISTANT) "assistant" else "user")
                            if (imageAttachments.isEmpty()) {
                                put("content", text)
                            } else {
                                putJsonArray("content") {
                                    if (text.isNotBlank()) {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", text)
                                        })
                                    }
                                    for ((mime, b64) in imageAttachments) {
                                        add(buildJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") {
                                                put("url", "data:$mime;base64,$b64")
                                            }
                                        })
                                    }
                                }
                            }
                        })
                    }
                    compactedToolMessages.forEach { add(it) }
                    if (continuePrefix.isNotBlank() && extraToolMessages.isEmpty()) {
                        add(buildJsonObject {
                            put("role", "assistant")
                            put("content", continuePrefix)
                        })
                    }
                }
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
            val roundContentBuilder = StringBuilder()
            // index -> Triple(id, name, argsBuilder)
            val roundToolCalls = mutableMapOf<Int, Triple<String, String, StringBuilder>>()

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
                                onThinking(reasoning)
                            }
                            val content = delta["content"]?.jsonPrimitive?.contentOrNull ?: ""
                            if (content.isNotEmpty()) {
                                onFirstToken()
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
                                        saveAndEmitImage(b64, "", mime, profile.mediaAspectRatio, "", onMedia)
                                    } else if (urlVal.startsWith("http")) {
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
                        val txt = firstMsg?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
                        if (txt.isNotEmpty()) {
                            onFirstToken()
                            roundContentBuilder.append(txt)
                            fullTextAccumulator.append(txt)
                            onContent(txt)
                        }
                    }
                )
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
                put("content", roundContentBuilder.toString().ifBlank { "\n" })
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
                val rawRec = WorkspaceHarness.executeToolCall(
                    workspaceRoot = session.workspacePath,
                    mode = session.workspaceMode,
                    callId = callId,
                    toolName = fnName,
                    argumentsJson = argsSb.toString().ifBlank { "{}" }
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

    // ============================================================================================
    // 3. ANTHROPIC MESSAGES API DRIVER (/v1/messages)
    //    Supports Extended Thinking + cryptographic signature preservation,
    //    Automatic Prompt Caching (`cache_control: {"type": "ephemeral"}`),
    //    Native Assistant Prefill (`continuePrefix`), and Local Workspace Function Tools loop.
    // ============================================================================================
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
        val activeNodes = historyNodes.filter { it.includedInContext }
        val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)
        val turnsWithAuthorsNote = injectAuthorsNoteIntoHistory(activeNodes, session)

        // Mutable conversation list for multi-step tool loop
        val extraLoopMessages = mutableListOf<JsonObject>()
        var loopStep = 0

        while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
            loopStep++
            val effort = profile.thinkingEffort.trim()
            val lowerModel = model.lowercase()
            val thinkBudget = profile.thinkingBudgetTokens ?: 0
            val isAdaptiveEffortModel = lowerModel.contains("5-5") ||
                lowerModel.contains("5.5") ||
                lowerModel.contains("5-1") ||
                lowerModel.contains("5.1") ||
                lowerModel.contains("4-6") ||
                lowerModel.contains("4.6") ||
                lowerModel.contains("4-7") ||
                lowerModel.contains("4.7") ||
                lowerModel.contains("4-8") ||
                lowerModel.contains("4.8") ||
                lowerModel.contains("fable") ||
                lowerModel.contains("mythos") ||
                thinkBudget <= 0

            val enableBudgetThinking = !isAdaptiveEffortModel &&
                effort.isNotEmpty() &&
                !effort.equals("none", ignoreCase = true) &&
                thinkBudget >= 1024 &&
                continuePrefix.isBlank()

            val payloadObj = buildJsonObject {
                put("model", model)
                put("stream", true)
                val userMaxOut = profile.maxOutputTokens
                val resolvedMaxOut = if (userMaxOut != null && userMaxOut > 0) userMaxOut else 64000
                val maxTok = if (enableBudgetThinking) {
                    maxOf(resolvedMaxOut, thinkBudget + 4096)
                } else {
                    resolvedMaxOut
                }
                put("max_tokens", maxTok)

                if (isAdaptiveEffortModel) {
                    // Claude 4.6+ / Opus 5.5 / Sonnet 5.5 adaptive effort dialect
                    if (effort.equals("none", ignoreCase = true) || effort.equals("off", ignoreCase = true)) {
                        putJsonObject("thinking") {
                            put("type", "disabled")
                        }
                    } else {
                        putJsonObject("thinking") {
                            put("type", "adaptive")
                            put("display", "summarized")
                        }
                        if (effort.isNotEmpty() && !effort.equals("default", ignoreCase = true) && !effort.equals("auto", ignoreCase = true)) {
                            putJsonObject("output_config") {
                                put("effort", effort.lowercase())
                            }
                        }
                    }
                } else if (enableBudgetThinking) {
                    put("temperature", 1.0) // Required by Anthropic when budget_tokens thinking is active
                    putJsonObject("thinking") {
                        put("type", "enabled")
                        put("budget_tokens", thinkBudget)
                    }
                } else {
                    profile.temperature?.let { put("temperature", it) }
                    profile.topP?.let { put("top_p", it) }
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
                    if (turnsWithAuthorsNote.firstOrNull()?.first == MessageRole.ASSISTANT) {
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", "[Begin narrative]")
                                })
                            }
                        })
                    }
                    val lastIdx = turnsWithAuthorsNote.size - 1
                    for ((idx, pair) in turnsWithAuthorsNote.withIndex()) {
                        val (role, text) = pair
                        val imageAttachments = resolveImageAttachments(activeNodes.getOrNull(idx)?.activeVariant)
                        add(buildJsonObject {
                            put("role", if (role == MessageRole.ASSISTANT) "assistant" else "user")
                            putJsonArray("content") {
                                for ((mime, b64) in imageAttachments) {
                                    add(buildJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", mime)
                                            put("data", b64)
                                        }
                                    })
                                }
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", text.ifBlank { "..." })
                                    if (idx == lastIdx && extraLoopMessages.isEmpty()) {
                                        putJsonObject("cache_control") {
                                            put("type", "ephemeral")
                                        }
                                    }
                                })
                            }
                        })
                    }
                    val compactedAnthropicExtras = compactAnthropicExtraMessages(extraLoopMessages)
                    for (extraMsg in compactedAnthropicExtras) {
                        add(extraMsg)
                    }
                    // Native Assistant Prefill for seamless mid-sentence continuation
                    if (continuePrefix.isNotBlank() && extraLoopMessages.isEmpty()) {
                        add(buildJsonObject {
                            put("role", "assistant")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", continuePrefix)
                                })
                            }
                        })
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

            val turnToolCalls = mutableListOf<Pair<String, Pair<String, String>>>()
            val assistantContentBlocksForToolLoop = mutableListOf<JsonObject>()
            var turnThinkingText = StringBuilder()
            var turnThinkingSig = StringBuilder()
            var turnText = StringBuilder()

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
                val toolBlocksByIndex = mutableMapOf<Int, Pair<String, Pair<String, StringBuilder>>>()

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
                                val idx = evObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                val cb = evObj["content_block"] as? JsonObject
                                if (cb?.get("type")?.jsonPrimitive?.contentOrNull == "tool_use") {
                                    val id = cb["id"]?.jsonPrimitive?.contentOrNull ?: "toolu_$idx"
                                    val name = cb["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                    toolBlocksByIndex[idx] = id to (name to StringBuilder())
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
                                            turnText.append(txt)
                                            onContent(txt)
                                        }
                                    }
                                    "thinking_delta" -> {
                                        val th = deltaObj["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                                        if (th.isNotEmpty()) {
                                            onFirstToken()
                                            turnThinkingText.append(th)
                                            onThinking(th, "")
                                        }
                                    }
                                    "signature_delta" -> {
                                        val sig = deltaObj["signature"]?.jsonPrimitive?.contentOrNull ?: ""
                                        if (sig.isNotEmpty()) {
                                            turnThinkingSig.append(sig)
                                            onThinking("", sig)
                                        }
                                    }
                                    "input_json_delta" -> {
                                        val partial = deltaObj["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                                        toolBlocksByIndex[idx]?.second?.second?.append(partial)
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
                    }
                )

                for ((_, pair) in toolBlocksByIndex.entries.sortedBy { it.key }) {
                    val callId = pair.first
                    val fnName = pair.second.first
                    val argsStr = pair.second.second.toString().ifBlank { "{}" }
                    if (fnName.isNotBlank()) {
                        turnToolCalls.add(callId to (fnName to argsStr))
                    }
                }
            }

            if (turnToolCalls.isEmpty()) {
                val stopHookPrompt = circuitBreaker.evaluateStopHookPrompt()
                if (stopHookPrompt != null) {
                    if (turnThinkingText.isNotEmpty() && turnThinkingSig.isNotEmpty()) {
                        assistantContentBlocksForToolLoop.add(buildJsonObject {
                            put("type", "thinking")
                            put("thinking", turnThinkingText.toString())
                            put("signature", turnThinkingSig.toString())
                        })
                    }
                    if (turnText.isNotEmpty()) {
                        assistantContentBlocksForToolLoop.add(buildJsonObject {
                            put("type", "text")
                            put("text", turnText.toString())
                        })
                    }
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

            // Preserve assistant thinking + signature + tool_use blocks for Anthropic multi-turn tool loop
            if (turnThinkingText.isNotEmpty() && turnThinkingSig.isNotEmpty()) {
                assistantContentBlocksForToolLoop.add(buildJsonObject {
                    put("type", "thinking")
                    put("thinking", turnThinkingText.toString())
                    put("signature", turnThinkingSig.toString())
                })
            }
            if (turnText.isNotEmpty()) {
                assistantContentBlocksForToolLoop.add(buildJsonObject {
                    put("type", "text")
                    put("text", turnText.toString())
                })
            }
            for ((callId, fnPair) in turnToolCalls) {
                val parsedArgs = runCatching { json.parseToJsonElement(fnPair.second).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
                assistantContentBlocksForToolLoop.add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", callId)
                    put("name", fnPair.first)
                    put("input", parsedArgs)
                })
            }

            extraLoopMessages.add(buildJsonObject {
                put("role", "assistant")
                put("content", JsonArray(assistantContentBlocksForToolLoop))
            })

            val toolResultBlocks = mutableListOf<JsonObject>()
            for ((callId, fnPair) in turnToolCalls) {
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

            val payloadObj = buildJsonObject {
                put("model", model)
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", latestUserPrompt)
                    })
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

    /**
     * Resolves multimodal binary attachments (images, videos, audio, and PDFs) as `(mimeType, base64String)` pairs,
     * reading lazily from `localFilePath` on disk when `base64Data` is empty so multi-megabyte base64 strings never bloat RAM or JSON state.
     */
    private fun resolveImageAttachments(nodeVar: MessageVariant?): List<Pair<String, String>> {
        if (nodeVar == null || nodeVar.attachments.isEmpty()) return emptyList()
        return nodeVar.attachments.mapNotNull { att ->
            val m = att.mimeType.lowercase()
            val isBinaryMedia = m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/") || m == "application/pdf"
            if (!isBinaryMedia && att.base64Data.isBlank()) return@mapNotNull null
            val b64 = if (att.base64Data.isNotBlank()) {
                att.base64Data
            } else if (att.localFilePath.isNotBlank()) {
                PlatformBridge.readBinaryFileBytes(att.localFilePath)?.let { Base64.encode(it) } ?: ""
            } else ""
            if (b64.isNotBlank()) att.mimeType.ifBlank { "image/png" } to b64 else null
        }
    }

    /**
     * Detects transient proxy/gateway/CDN timeouts (~5-minute 300s Cloudflare 524, Nginx 502/504,
     * socket read timeout, premature EOF, connection reset, or 429 rate limit) so `streamGeneration`
     * can automatically retry pre-token or seamlessly stitch a mid-stream continuation pass.
     */
    private fun isTransientOrProxyTimeoutError(err: Throwable): Boolean {
        val msg = (err.message ?: err.toString()).lowercase()
        if (msg.contains("401") || msg.contains("403") || msg.contains("invalid_api_key") || msg.contains("authentication")) {
            return false
        }
        return msg.contains("timeout") ||
            msg.contains("timed out") ||
            msg.contains("http 429") ||
            msg.contains("http 500") ||
            msg.contains("http 502") ||
            msg.contains("http 503") ||
            msg.contains("http 504") ||
            msg.contains("http 520") ||
            msg.contains("http 522") ||
            msg.contains("http 524") ||
            msg.contains("connection reset") ||
            msg.contains("connection closed") ||
            msg.contains("closed prematurely") ||
            msg.contains("broken pipe") ||
            msg.contains("eof") ||
            msg.contains("socket") ||
            msg.contains("unknownhost") ||
            msg.contains("unresolvedaddress") ||
            msg.contains("network is unreachable") ||
            msg.contains("incomplete")
    }

    /**
     * Detects when a proxy or server does not implement `/v1beta/interactions` or `/v1/responses`
     * so `streamGeneration` can automatically fall back to `:streamGenerateContent` or `/v1/chat/completions`.
     */
    private fun isEndpointUnsupportedForAutoFallback(err: Throwable): Boolean {
        val msg = (err.message ?: err.toString()).lowercase()
        return msg.contains("http 404") ||
            msg.contains("http 405") ||
            msg.contains("http 501") ||
            msg.contains("not found") ||
            msg.contains("unknown endpoint") ||
            msg.contains("unrecognized request url") ||
            msg.contains("unsupported path")
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
                val raw = obj["result"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val compacted = WorkspaceHarness.compactStaleToolOutputText(idx, total, name, raw)
                if (compacted != raw) {
                    buildJsonObject {
                        for ((k, v) in obj) {
                            if (k == "result") put("result", compacted) else put(k, v)
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
            val textAttachmentsBlock = v.attachments.filter { it.textContent.isNotBlank() }
                .joinToString("\n\n") { att ->
                    "[Attached File: ${att.fileName}]\n```\n${att.textContent}\n```"
                }
            val combinedText = if (textAttachmentsBlock.isNotBlank()) {
                if (v.content.isNotBlank()) "${v.content}\n\n$textAttachmentsBlock" else textAttachmentsBlock
            } else {
                v.content
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
