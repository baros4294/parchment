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
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.AuthHeaderMode
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.TokenMetrics
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

sealed class StreamUpdate {
    data class Status(val message: String, val interactionId: String = "") : StreamUpdate()
    data class ThinkingDelta(val delta: String, val signatureDelta: String = "") : StreamUpdate()
    data class ContentDelta(val delta: String) : StreamUpdate()
    data class ToolCallEvent(val toolCall: ToolCallRecord) : StreamUpdate()
    data class RawWireSnapshot(val requestJson: String, val latestResponseChunk: String) : StreamUpdate()
    data class Completed(
        val fullContent: String,
        val fullThinking: String,
        val thinkingSignature: String,
        val toolCalls: List<ToolCallRecord>,
        val providerInteractionId: String,
        val metrics: TokenMetrics,
        val rawRequest: String,
        val rawResponse: String
    ) : StreamUpdate()
    data class Error(val message: String, val rawDetails: String = "") : StreamUpdate()
}

object AiProviderEngine {

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
            requestTimeoutMillis = 60 * 60 * 1000L // 60 minutes for Gemini 4 Argon long decode
            connectTimeoutMillis = 30_000L
            socketTimeoutMillis = 60 * 60 * 1000L
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
        var lastInteractionId = ""
        var inputTokens = 0
        var cachedTokens = 0
        var reasoningTokens = 0
        var outputTokens = 0
        var lastRawReq = ""
        var lastRawResp = ""

        PlatformBridge.notifyLongDecodeStart(model)

        try {
            when (profile.protocol) {
                ProviderProtocol.GEMINI_INTERACTIONS -> {
                    if (profile.useLegacyFallbackEndpoint) {
                        executeGeminiGenerateContentLoop(
                            profile = profile,
                            model = model,
                            session = session,
                            historyNodes = historyNodes,
                            continuePrefix = continueFromAssistantPrefix,
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
                            onUsage = { inTok, cacheTok, thinkTok, outTok ->
                                inputTokens = inTok
                                cachedTokens = cacheTok
                                reasoningTokens = thinkTok
                                outputTokens = outTok
                            }
                        )
                    } else {
                        executeGeminiInteractionsLoop(
                            profile = profile,
                            model = model,
                            session = session,
                            historyNodes = historyNodes,
                            continuePrefix = continueFromAssistantPrefix,
                            onStatus = { statusMsg, id ->
                                if (id.isNotBlank()) lastInteractionId = id
                                emit(StreamUpdate.Status(statusMsg, lastInteractionId))
                            },
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
                            onUsage = { id, inTok, cacheTok, thinkTok, outTok ->
                                if (id.isNotBlank()) lastInteractionId = id
                                inputTokens = inTok
                                cachedTokens = cacheTok
                                reasoningTokens = thinkTok
                                outputTokens = outTok
                            }
                        )
                    }
                }

                ProviderProtocol.OPENAI_RESPONSES -> {
                    if (profile.useLegacyFallbackEndpoint) {
                        executeOpenAiChatCompletionsLoop(
                            profile = profile,
                            model = model,
                            session = session,
                            historyNodes = historyNodes,
                            continuePrefix = continueFromAssistantPrefix,
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
                    } else {
                        executeOpenAiResponsesLoop(
                            profile = profile,
                            model = model,
                            session = session,
                            historyNodes = historyNodes,
                            continuePrefix = continueFromAssistantPrefix,
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
                    }
                }

                ProviderProtocol.ANTHROPIC_MESSAGES -> {
                    executeAnthropicMessagesLoop(
                        profile = profile,
                        model = model,
                        session = session,
                        historyNodes = historyNodes,
                        continuePrefix = continueFromAssistantPrefix,
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
            }

            val endTime = PlatformBridge.currentTimeMillis()
            val totalDurationMs = (endTime - startTime).coerceAtLeast(1L)
            val ttftMs = if (firstTokenTime > startTime) (firstTokenTime - startTime) else totalDurationMs
            val finalOutTokens = if (outputTokens > 0) outputTokens else (contentBuilder.length / 4).coerceAtLeast(1)
            val tokPerSec = (finalOutTokens * 1000.0) / totalDurationMs.toDouble()

            emit(
                StreamUpdate.Completed(
                    fullContent = contentBuilder.toString(),
                    fullThinking = thinkingBuilder.toString(),
                    thinkingSignature = signatureBuilder.toString(),
                    toolCalls = executedTools,
                    providerInteractionId = lastInteractionId,
                    metrics = TokenMetrics(
                        inputTokens = inputTokens,
                        cachedInputTokens = cachedTokens,
                        reasoningTokens = reasoningTokens,
                        outputTokens = finalOutTokens,
                        ttftMs = ttftMs,
                        durationMs = totalDurationMs,
                        tokensPerSec = (tokPerSec * 10.0).toInt() / 10.0
                    ),
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
        onStatus: suspend (String, String) -> Unit,
        onFirstToken: () -> Unit,
        onThinking: suspend (String, String) -> Unit,
        onContent: suspend (String) -> Unit,
        onToolCall: suspend (ToolCallRecord) -> Unit,
        onRawWire: suspend (String, String) -> Unit,
        onUsage: (String, Int, Int, Int, Int) -> Unit
    ) {
        val activeNodes = historyNodes.filter { it.includedInContext }
        val systemInstruction = buildCompiledSystemPrompt(session)

        // Check if we can use stateful previous_interaction_id chaining
        val previousAssistantIdx = activeNodes.indexOfLast { it.role == MessageRole.ASSISTANT }
        val previousInteractionId = if (profile.useStatefulChaining && continuePrefix.isBlank() && previousAssistantIdx >= 0) {
            val prevVar = activeNodes[previousAssistantIdx].activeVariant
            if (prevVar.providerProtocol == ProviderProtocol.GEMINI_INTERACTIONS && prevVar.providerInteractionId.isNotBlank()) {
                prevVar.providerInteractionId
            } else ""
        } else ""

        var currentPrevInteractionId = previousInteractionId
        var pendingToolResults: List<ToolCallRecord> = emptyList()
        var loopStep = 0

        while (loopStep < 8) {
            loopStep++
            val payloadObj = buildJsonObject {
                put("model", model)
                if (systemInstruction.isNotBlank()) {
                    put("system_instruction", systemInstruction)
                }

                if (!profile.useStatefulChaining) {
                    put("store", false)
                }

                if (profile.useBackgroundLongDecode) {
                    put("background", true)
                    put("stream", false)
                } else {
                    put("stream", true)
                }

                if (currentPrevInteractionId.isNotBlank()) {
                    put("previous_interaction_id", currentPrevInteractionId)
                }

                // Build `input`
                if (pendingToolResults.isNotEmpty()) {
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
                } else if (currentPrevInteractionId.isNotBlank() && previousAssistantIdx >= 0 && previousAssistantIdx < activeNodes.size - 1) {
                    // Send only turns after the chained assistant turn
                    val trailingNodes = activeNodes.subList(previousAssistantIdx + 1, activeNodes.size)
                    put("input", buildGeminiInteractionsTurnsArray(trailingNodes, session, continuePrefix))
                } else {
                    put("input", buildGeminiInteractionsTurnsArray(activeNodes, session, continuePrefix))
                }

                // Generation config (including Gemini 4 Argon long decode & thinking configuration)
                putJsonObject("generation_config") {
                    put("temperature", profile.temperature)
                    put("top_p", profile.topP)
                    if (profile.maxOutputTokens > 0) {
                        put("max_output_tokens", profile.maxOutputTokens)
                    }
                    if (profile.thinkingEffort != "none") {
                        put("thinking_level", profile.thinkingEffort)
                        put("thinking_summaries", "auto")
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
                    val workspaceTools = WorkspaceHarness.buildGeminiInteractionsTools(session.workspaceMode, session.workspacePath)
                    workspaceTools.forEach { add(it) }
                }
                if (toolsArray.isNotEmpty()) {
                    put("tools", toolsArray)
                }
            }

            val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
            val requestJsonStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)

            val base = profile.baseUrl.trimEnd('/')
            val path = profile.endpointPathOverride.ifBlank { "/v1beta/interactions" }
            val rawUrl = if (profile.useBackgroundLongDecode) {
                "$base$path"
            } else {
                val sep = if (path.contains("?")) "&" else "?"
                "$base$path${sep}alt=sse"
            }
            val finalUrl = buildUrlWithAuthQuery(profile, rawUrl)

            onRawWire(requestJsonStr, "Dispatching to $finalUrl ...")

            val turnToolCalls = mutableListOf< Pair<String, Pair<String, String>> >() // callId -> (name, argsJson)

            if (profile.useBackgroundLongDecode) {
                // Background Long Decode Polling Mode (`background = true`)
                val createResp = httpClient.post(finalUrl) {
                    applyAuthAndCustomHeaders(profile)
                    contentType(ContentType.Application.Json)
                    setBody(requestJsonStr)
                }
                val createBody = createResp.bodyAsText()
                onRawWire(requestJsonStr, createBody)
                if (!createResp.status.isSuccess()) {
                    error("Gemini Interactions HTTP ${createResp.status.value}: $createBody")
                }

                var interactionObj = json.parseToJsonElement(createBody).jsonObject
                var interactionId = interactionObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                var status = interactionObj["status"]?.jsonPrimitive?.contentOrNull ?: "in_progress"
                if (interactionId.isNotBlank()) {
                    currentPrevInteractionId = interactionId
                }

                var pollCount = 0
                while (status == "in_progress" || status == "queued" || status == "processing") {
                    pollCount++
                    onStatus("Gemini 4 Argon Background Decode ($status • poll #$pollCount)...", interactionId)
                    delay(2000L)
                    val pollUrl = buildUrlWithAuthQuery(profile, "$base${path.substringBefore("?")}/$interactionId")
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

                parseCompletedGeminiInteractionObject(
                    interactionObj = interactionObj,
                    onFirstToken = onFirstToken,
                    onThinking = onThinking,
                    onContent = onContent,
                    onFunctionCall = { callId, fnName, argsStr ->
                        turnToolCalls.add(callId to (fnName to argsStr))
                    },
                    onUsage = { id, inTok, cacheTok, thinkTok, outTok ->
                        if (id.isNotBlank()) currentPrevInteractionId = id
                        onUsage(id, inTok, cacheTok, thinkTok, outTok)
                    }
                )
            } else {
                // Live SSE Streaming Mode (`stream = true`)
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

                    readSseOrJsonFallback(
                        channel = channel,
                        onSseEvent = { eventType, dataLine ->
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
                                "interaction.start", "interaction.status_update" -> {
                                    val st = interactionSub?.get("status")?.jsonPrimitive?.contentOrNull
                                        ?: evObj["status"]?.jsonPrimitive?.contentOrNull ?: "streaming"
                                    onStatus("Interaction $st", currentPrevInteractionId)
                                }
                                "content.start" -> {
                                    val idx = evObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                    val contentObj = evObj["content"] as? JsonObject
                                    if (contentObj?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                                        val callId = contentObj["id"]?.jsonPrimitive?.contentOrNull
                                            ?: contentObj["call_id"]?.jsonPrimitive?.contentOrNull
                                            ?: "call_$idx"
                                        val fnName = contentObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val initialArgs = contentObj["arguments"]?.let {
                                            if (it is JsonPrimitive) it.content else it.toString()
                                        } ?: ""
                                        pendingFnArgsByIndex[idx] = callId to (fnName to StringBuilder(initialArgs))
                                    }
                                }
                                "content.delta" -> {
                                    val idx = evObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                    val deltaObj = evObj["delta"] as? JsonObject ?: evObj
                                    val deltaType = deltaObj["type"]?.jsonPrimitive?.contentOrNull ?: ""
                                    when (deltaType) {
                                        "text" -> {
                                            val txt = deltaObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                            if (txt.isNotEmpty()) {
                                                onFirstToken()
                                                onContent(txt)
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
                                            val sig = deltaObj["signature"]?.jsonPrimitive?.contentOrNull ?: ""
                                            if (sig.isNotEmpty()) {
                                                onThinking("", sig)
                                            }
                                        }
                                        "function_call" -> {
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
                                                onContent(fallbackTxt)
                                            }
                                        }
                                    }
                                }
                                "interaction.complete" -> {
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
                                    // If a proxy returned a full non-delta interaction object inside an event
                                    if (evObj.containsKey("outputs")) {
                                        parseCompletedGeminiInteractionObject(
                                            interactionObj = evObj,
                                            onFirstToken = onFirstToken,
                                            onThinking = onThinking,
                                            onContent = onContent,
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
                            parseCompletedGeminiInteractionObject(
                                interactionObj = rootObj,
                                onFirstToken = onFirstToken,
                                onThinking = onThinking,
                                onContent = onContent,
                                onFunctionCall = { callId, fnName, argsStr ->
                                    turnToolCalls.add(callId to (fnName to argsStr))
                                },
                                onUsage = { id, inTok, cacheTok, thinkTok, outTok ->
                                    if (id.isNotBlank()) currentPrevInteractionId = id
                                    onUsage(id, inTok, cacheTok, thinkTok, outTok)
                                }
                            )
                        }
                    )

                    for ((_, pair) in pendingFnArgsByIndex.entries.sortedBy { it.key }) {
                        val callId = pair.first
                        val fnName = pair.second.first
                        val argsStr = pair.second.second.toString()
                        if (fnName.isNotBlank()) {
                            turnToolCalls.add(callId to (fnName to argsStr))
                        }
                    }
                }
            }

            if (turnToolCalls.isEmpty()) {
                break
            }

            // Execute workspace tools and loop back with `function_result`
            val results = mutableListOf<ToolCallRecord>()
            for ((callId, fnPair) in turnToolCalls) {
                val record = WorkspaceHarness.executeToolCall(
                    workspaceRoot = session.workspacePath,
                    mode = session.workspaceMode,
                    callId = callId,
                    toolName = fnPair.first,
                    argumentsJson = fnPair.second
                )
                onToolCall(record)
                results.add(record)
            }
            pendingToolResults = results
        }
    }

    private suspend fun parseCompletedGeminiInteractionObject(
        interactionObj: JsonObject,
        onFirstToken: () -> Unit,
        onThinking: suspend (String, String) -> Unit,
        onContent: suspend (String) -> Unit,
        onFunctionCall: (String, String, String) -> Unit,
        onUsage: (String, Int, Int, Int, Int) -> Unit
    ) {
        val id = interactionObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val outputs = interactionObj["outputs"] as? JsonArray
        outputs?.forEach { itemEl ->
            val item = itemEl as? JsonObject ?: return@forEach
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val txt = item["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (txt.isNotEmpty()) {
                        onFirstToken()
                        onContent(txt)
                    }
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

    private fun buildGeminiInteractionsTurnsArray(
        nodes: List<MessageNode>,
        session: ChatSession,
        continuePrefix: String
    ): JsonArray = buildJsonArray {
        val withAuthorsNote = injectAuthorsNoteIntoHistory(nodes, session)
        for ((role, text) in withAuthorsNote) {
            add(buildJsonObject {
                put("role", if (role == MessageRole.ASSISTANT) "model" else "user")
                put("content", text)
            })
        }
        if (continuePrefix.isNotBlank()) {
            add(buildJsonObject {
                put("role", "user")
                put(
                    "content",
                    "[System Note: Continue the narrative seamlessly from the exact end of this partial passage without repeating it:\n\"$continuePrefix\"]"
                )
            })
        }
    }

    // ============================================================================================
    // 1B. GEMINI FALLBACK DRIVER (/v1beta/models/{model}:streamGenerateContent)
    //     For custom proxies that only expose generateContent instead of /v1beta/interactions.
    // ============================================================================================
    private suspend fun executeGeminiGenerateContentLoop(
        profile: ProviderProfile,
        model: String,
        session: ChatSession,
        historyNodes: List<MessageNode>,
        continuePrefix: String,
        onFirstToken: () -> Unit,
        onThinking: suspend (String, String) -> Unit,
        onContent: suspend (String) -> Unit,
        onToolCall: suspend (ToolCallRecord) -> Unit,
        onRawWire: suspend (String, String) -> Unit,
        onUsage: (Int, Int, Int, Int) -> Unit
    ) {
        val activeNodes = historyNodes.filter { it.includedInContext }
        val systemInstruction = buildCompiledSystemPrompt(session)
        val turns = injectAuthorsNoteIntoHistory(activeNodes, session)

        val payloadObj = buildJsonObject {
            if (systemInstruction.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", systemInstruction) })
                    }
                }
            }
            putJsonArray("contents") {
                for ((role, text) in turns) {
                    add(buildJsonObject {
                        put("role", if (role == MessageRole.ASSISTANT) "model" else "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", text) })
                        }
                    })
                }
                if (continuePrefix.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("text", "Continue seamlessly from this exact text without repeating it:\n$continuePrefix")
                            })
                        }
                    })
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", profile.temperature)
                put("topP", profile.topP)
                if (profile.maxOutputTokens > 0) {
                    put("maxOutputTokens", profile.maxOutputTokens)
                }
                if (profile.thinkingEffort != "none" && profile.thinkingBudgetTokens > 0) {
                    putJsonObject("thinkingConfig") {
                        put("includeThoughts", true)
                        put("thinkingBudget", profile.thinkingBudgetTokens)
                    }
                }
            }
        }

        val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
        val reqStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)
        val base = profile.baseUrl.trimEnd('/')
        val url = buildUrlWithAuthQuery(profile, "$base/v1beta/models/$model:streamGenerateContent?alt=sse")

        onRawWire(reqStr, "Dispatching to $url ...")

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
                    onRawWire(reqStr, dataLine)
                    val obj = runCatching { json.parseToJsonElement(dataLine).jsonObject }.getOrNull() ?: return@readSseOrJsonFallback
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
                }
            )
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
        onFirstToken: () -> Unit,
        onThinking: suspend (String) -> Unit,
        onContent: suspend (String) -> Unit,
        onToolCall: suspend (ToolCallRecord) -> Unit,
        onRawWire: suspend (String, String) -> Unit,
        onUsage: (String, Int, Int, Int, Int) -> Unit
    ) {
        val activeNodes = historyNodes.filter { it.includedInContext }
        val systemInstruction = buildCompiledSystemPrompt(session)

        val previousAssistantIdx = activeNodes.indexOfLast { it.role == MessageRole.ASSISTANT }
        var currentPrevResponseId = if (profile.useStatefulChaining && continuePrefix.isBlank() && previousAssistantIdx >= 0) {
            val prevVar = activeNodes[previousAssistantIdx].activeVariant
            if (prevVar.providerProtocol == ProviderProtocol.OPENAI_RESPONSES && prevVar.providerInteractionId.isNotBlank()) {
                prevVar.providerInteractionId
            } else ""
        } else ""

        var pendingToolResults: List<ToolCallRecord> = emptyList()
        var loopStep = 0

        while (loopStep < 8) {
            loopStep++
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

                if (pendingToolResults.isNotEmpty()) {
                    putJsonArray("input") {
                        for (tr in pendingToolResults) {
                            add(buildJsonObject {
                                put("type", "function_call_output")
                                put("call_id", tr.id)
                                put("output", tr.resultText)
                            })
                        }
                    }
                } else if (currentPrevResponseId.isNotBlank() && previousAssistantIdx >= 0 && previousAssistantIdx < activeNodes.size - 1) {
                    val trailing = activeNodes.subList(previousAssistantIdx + 1, activeNodes.size)
                    put("input", buildOpenAiResponsesInputArray(trailing, session, continuePrefix))
                } else {
                    put("input", buildOpenAiResponsesInputArray(activeNodes, session, continuePrefix))
                }

                if (profile.maxOutputTokens > 0) {
                    put("max_output_tokens", profile.maxOutputTokens)
                }

                if (profile.thinkingEffort != "none") {
                    putJsonObject("reasoning") {
                        put("effort", if (profile.thinkingEffort == "max") "high" else profile.thinkingEffort)
                        put("summary", "auto")
                    }
                } else {
                    put("temperature", profile.temperature)
                    put("top_p", profile.topP)
                }

                val toolsArr = buildJsonArray {
                    if (profile.enableWebSearch) {
                        add(buildJsonObject { put("type", "web_search_preview") })
                    }
                    val wsTools = WorkspaceHarness.buildOpenAiResponsesTools(session.workspaceMode, session.workspacePath)
                    wsTools.forEach { add(it) }
                }
                if (toolsArr.isNotEmpty()) {
                    put("tools", toolsArr)
                }
            }

            val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
            val reqStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)
            val base = profile.baseUrl.trimEnd('/')
            val path = profile.endpointPathOverride.ifBlank { "/v1/responses" }
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
                                if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                        ?: item["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val name = item["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val args = item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                                    if (name.isNotBlank()) {
                                        turnToolCalls.add(callId to (name to args))
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
                            }
                        }
                    }
                )
            }

            if (turnToolCalls.isEmpty()) break

            val results = mutableListOf<ToolCallRecord>()
            for ((callId, fnPair) in turnToolCalls) {
                val rec = WorkspaceHarness.executeToolCall(
                    workspaceRoot = session.workspacePath,
                    mode = session.workspaceMode,
                    callId = callId,
                    toolName = fnPair.first,
                    argumentsJson = fnPair.second
                )
                onToolCall(rec)
                results.add(rec)
            }
            pendingToolResults = results
        }
    }

    private fun buildOpenAiResponsesInputArray(
        nodes: List<MessageNode>,
        session: ChatSession,
        continuePrefix: String
    ): JsonArray = buildJsonArray {
        val withAuthorsNote = injectAuthorsNoteIntoHistory(nodes, session)
        for ((role, text) in withAuthorsNote) {
            add(buildJsonObject {
                put("role", if (role == MessageRole.ASSISTANT) "assistant" else "user")
                put("content", text)
            })
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
    //     For OpenAI-compatible 3rd-party proxies (OpenRouter, DeepSeek, local vLLM/llama.cpp).
    // ============================================================================================
    private suspend fun executeOpenAiChatCompletionsLoop(
        profile: ProviderProfile,
        model: String,
        session: ChatSession,
        historyNodes: List<MessageNode>,
        continuePrefix: String,
        onFirstToken: () -> Unit,
        onThinking: suspend (String) -> Unit,
        onContent: suspend (String) -> Unit,
        onToolCall: suspend (ToolCallRecord) -> Unit,
        onRawWire: suspend (String, String) -> Unit,
        onUsage: (Int, Int, Int, Int) -> Unit
    ) {
        val activeNodes = historyNodes.filter { it.includedInContext }
        val systemInstruction = buildCompiledSystemPrompt(session)
        val turns = injectAuthorsNoteIntoHistory(activeNodes, session)

        val payloadObj = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", profile.temperature)
            put("top_p", profile.topP)
            if (profile.maxOutputTokens > 0) {
                put("max_tokens", profile.maxOutputTokens)
            }
            putJsonArray("messages") {
                if (systemInstruction.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemInstruction)
                    })
                }
                for ((role, text) in turns) {
                    add(buildJsonObject {
                        put("role", if (role == MessageRole.ASSISTANT) "assistant" else "user")
                        put("content", text)
                    })
                }
                if (continuePrefix.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "assistant")
                        put("content", continuePrefix)
                    })
                }
            }
            val wsTools = WorkspaceHarness.buildOpenAiChatCompletionsTools(session.workspaceMode, session.workspacePath)
            if (wsTools.isNotEmpty()) {
                put("tools", wsTools)
            }
        }

        val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
        val reqStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)
        val base = profile.baseUrl.trimEnd('/')
        val path = profile.endpointPathOverride.ifBlank { "/v1/chat/completions" }
        val url = buildUrlWithAuthQuery(profile, "$base$path")

        onRawWire(reqStr, "Dispatching to $url ...")

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
                    val choices = obj["choices"] as? JsonArray
                    val delta = (choices?.firstOrNull() as? JsonObject)?.get("delta") as? JsonObject
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
                            onContent(content)
                        }
                    }
                    val usage = obj["usage"] as? JsonObject
                    if (usage != null) {
                        val inTok = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                        val outTok = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                        onUsage(inTok, 0, 0, outTok)
                    }
                },
                onPlainJsonFallback = { body ->
                    onRawWire(reqStr, body)
                }
            )
        }
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
        onFirstToken: () -> Unit,
        onThinking: suspend (String, String) -> Unit,
        onContent: suspend (String) -> Unit,
        onToolCall: suspend (ToolCallRecord) -> Unit,
        onRawWire: suspend (String, String) -> Unit,
        onUsage: (String, Int, Int, Int) -> Unit
    ) {
        val activeNodes = historyNodes.filter { it.includedInContext }
        val systemInstruction = buildCompiledSystemPrompt(session)
        val turnsWithAuthorsNote = injectAuthorsNoteIntoHistory(activeNodes, session)

        // Mutable conversation list for multi-step tool loop
        val extraLoopMessages = mutableListOf<JsonObject>()
        var loopStep = 0

        while (loopStep < 8) {
            loopStep++
            val enableThinking = profile.thinkingEffort != "none" && profile.thinkingBudgetTokens >= 1024 && continuePrefix.isBlank()

            val payloadObj = buildJsonObject {
                put("model", model)
                put("stream", true)
                val maxTok = if (enableThinking) {
                    maxOf(profile.maxOutputTokens, profile.thinkingBudgetTokens + 4096)
                } else {
                    profile.maxOutputTokens.coerceAtLeast(4096)
                }
                put("max_tokens", maxTok)

                if (enableThinking) {
                    put("temperature", 1.0) // Required by Anthropic when extended thinking is active
                    putJsonObject("thinking") {
                        put("type", "enabled")
                        put("budget_tokens", profile.thinkingBudgetTokens)
                    }
                } else {
                    put("temperature", profile.temperature)
                    put("top_p", profile.topP)
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

                // Messages array with automatic cache_control on the penultimate turn
                putJsonArray("messages") {
                    val lastIdx = turnsWithAuthorsNote.size - 1
                    for ((idx, pair) in turnsWithAuthorsNote.withIndex()) {
                        val (role, text) = pair
                        add(buildJsonObject {
                            put("role", if (role == MessageRole.ASSISTANT) "assistant" else "user")
                            putJsonArray("content") {
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
                    for (extraMsg in extraLoopMessages) {
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

                val wsTools = WorkspaceHarness.buildAnthropicTools(session.workspaceMode, session.workspacePath)
                if (wsTools.isNotEmpty()) {
                    put("tools", wsTools)
                }
            }

            val finalPayload = mergeRawJsonExtraBody(payloadObj, profile.rawJsonExtraBody)
            val reqStr = prettyJson.encodeToString(JsonObject.serializer(), finalPayload)
            val base = profile.baseUrl.trimEnd('/')
            val path = profile.endpointPathOverride.ifBlank { "/v1/messages" }
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

            if (turnToolCalls.isEmpty()) break

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
                val rec = WorkspaceHarness.executeToolCall(
                    workspaceRoot = session.workspacePath,
                    mode = session.workspaceMode,
                    callId = callId,
                    toolName = fnPair.first,
                    argumentsJson = fnPair.second
                )
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
        }
    }

    // ============================================================================================
    // SHARED HELPERS: System Prompt Compiler, Cache-Preserving Author's Note Injector,
    // Auth Header Builder, SSE Stream Reader, and Raw JSON Merger.
    // ============================================================================================
    private fun buildCompiledSystemPrompt(session: ChatSession): String {
        val sb = StringBuilder()
        if (session.systemPrompt.isNotBlank()) {
            sb.appendLine(session.systemPrompt.trim())
            sb.appendLine()
        }
        session.activeCard?.let { card ->
            val compiledCard = card.buildCompiledInstructions(session.userName)
            if (compiledCard.isNotBlank()) {
                sb.appendLine(compiledCard)
                sb.appendLine()
            }
        }
        if (session.workspaceMode != io.parchment.ai.model.WorkspaceMode.OFF && session.workspacePath.isNotBlank()) {
            sb.appendLine("### Local Workspace Agent Access")
            sb.appendLine("You have direct access to the local workspace directory (`${session.workspacePath}`) via native file tools (`fs_list`, `fs_read`, `fs_search`${if (session.workspaceMode == io.parchment.ai.model.WorkspaceMode.READ_WRITE) ", `fs_write`, `fs_edit`" else ""}). Use them proactively when asked to inspect, reference, or edit files.")
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
        val baseList = nodes.map { it.role to it.activeVariant.content }.toMutableList()
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
                        ProviderProtocol.GEMINI_INTERACTIONS -> header("x-goog-api-key", key)
                        ProviderProtocol.OPENAI_RESPONSES -> header("Authorization", "Bearer $key")
                        ProviderProtocol.ANTHROPIC_MESSAGES -> header("x-api-key", key)
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
            val line = channel.readUTF8Line() ?: break
            when {
                line.startsWith("event:") -> {
                    sawSseLine = true
                    currentEvent = line.removePrefix("event:").trim()
                }
                line.startsWith("data:") -> {
                    sawSseLine = true
                    dataLines.add(line.removePrefix("data:").trim())
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
