package io.parchment.ai.network

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.parchment.ai.harness.ToolAccessScope
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.GoalTrackerState
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.network.AiProviderEngine.RawWebCitation
import io.parchment.ai.network.AiProviderEngine.ResolvedBinaryAttachment
import io.parchment.ai.network.AiProviderEngine.StepRetriesExhaustedException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// OpenAI Chat Completions: request history and the streaming agent loop.

// ============================================================================================
// 2B. OPENAI LEGACY FALLBACK (/v1/chat/completions)
//     For OpenAI-compatible 3rd-party proxies (OpenRouter, DeepSeek, Doubao Ark, vLLM/llama.cpp).
//     Supports full multi-step tool loops (up to 64 steps) and streaming usage telemetry.
// ============================================================================================
internal suspend fun AiProviderEngine.executeOpenAiChatCompletionsLoop(
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
    onOutputLimit: suspend () -> Unit = {},
    onRawWire: suspend (String, String) -> Unit,
    onRequestStarted: UsageRequestStarter,
    onUsage: UsageReporter,
    onCitation: suspend (RawWebCitation) -> Unit = {}
) {
    var usageRequestId: String? = null
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
    val hasReplayableHistory = activeNodes.any {
        it.role == MessageRole.ASSISTANT && (it.activeVariant.toolCalls.isNotEmpty() || it.activeVariant.thinkingSummary.isNotBlank())
    }
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
                    AgentPermissionResolver.resolve(session, profile = profile).allowShell,
                    goalModeActive = circuitBreaker.goalState != null,
                    enableWebTools = profile.enableWebSearch || profile.enableUrlContext,
                    policy = AgentPermissionResolver.resolve(session, profile = profile)
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
                ensureAgentPolicyCurrent(session, profile)
                usageRequestId = onRequestStarted(UsageDialect.OPENAI_CHAT)
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


                    var streamFinished = false
                    readSseOrJsonFallback(
                        streamFinished = { streamFinished },
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
                            val finishReason = (firstChoice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
                            if (finishReason != null) streamFinished = true
                            if (finishReason == "length") onOutputLimit()
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
                                tcArray?.forEach { tcEl ->
                                    val tcObj = tcEl as? JsonObject ?: return@forEach
                                    val idPart = tcObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val fnObj = tcObj["function"] as? JsonObject
                                    val namePart = fnObj?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                                    val argsPart = fnObj?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""
                                    // Some endpoints omit `index`, so separate calls must not share a slot.
                                    val nextSlot = (roundToolCalls.keys.maxOrNull() ?: -1) + 1
                                    val idx = tcObj["index"]?.jsonPrimitive?.intOrNull ?: when {
                                        idPart.isNotBlank() -> roundToolCalls.entries.firstOrNull { it.value.first == idPart }?.key ?: nextSlot
                                        namePart.isNotBlank() -> nextSlot
                                        else -> (nextSlot - 1).coerceAtLeast(0)
                                    }
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
                                onUsage(usageRequestId, obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), UsageDialect.OPENAI_CHAT,
                                    parseProviderUsage(UsageDialect.OPENAI_CHAT, usage, isFinal = firstChoice?.get("finish_reason") != null || choices?.isEmpty() == true))
                            }
                        },
                        onPlainJsonFallback = { body ->
                            onRawWire(reqStr, body)
                            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@readSseOrJsonFallback
                            onUsage(usageRequestId, root["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), UsageDialect.OPENAI_CHAT,
                                parseProviderUsage(UsageDialect.OPENAI_CHAT, root["usage"] as? JsonObject, isFinal = true))
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
                    if (!foldHistoricalToolsOnRetry && hasReplayableHistory &&
                        (errMsg.contains("tool_calls") || errMsg.contains("tool_call_id") || errMsg.contains("role 'tool'") ||
                            errMsg.contains("reasoning_content") || errMsg.contains("invalid_request"))
                    ) {
                        foldHistoricalToolsOnRetry = true
                        onStatus("Endpoint rejected historical tool or reasoning data — folding prior history into compact text & retrying…")
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
            allowShell = AgentPermissionResolver.resolve(session, profile = profile).allowShell,
            accessScope = ToolAccessScope(
                AgentPermissionResolver.resolve(session, profile = profile),
                goalModeActive = circuitBreaker.goalState != null,
                includeLocalWebSearchTool = profile.enableWebSearch
            ),
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

/** Reasoning text stays with its own wire family; these turns carry it in the handoff note instead. */
private val REASONING_FOREIGN_TO_CHAT = setOf<ProviderProtocol?>(
    ProviderProtocol.ANTHROPIC_MESSAGES,
    ProviderProtocol.GEMINI_INTERACTIONS
)

internal fun AiProviderEngine.buildOpenAiChatCompletionsMessages(
    activeNodes: List<MessageNode>,
    session: ChatSession,
    systemInstruction: String,
    continuePrefix: String,
    extraToolMessages: List<JsonObject> = emptyList(),
    foldHistoricalToolsToText: Boolean = false
): JsonArray {
    val turns = injectAuthorsNoteIntoHistory(activeNodes, session)
    val messages = mutableListOf<JsonObject>()
    val replayIds = ReplayToolCallIds(maxLength = 40)
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
                        when {
                            att.isDocument -> add(buildJsonObject {
                                put("type", "file")
                                putJsonObject("file") {
                                    put("filename", att.fileName.ifBlank { "document.pdf" })
                                    put("file_data", "data:${att.mimeType};base64,${att.base64Data}")
                                }
                            })
                            att.mimeType.lowercase().startsWith("image/") -> add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:${att.mimeType};base64,${att.base64Data}")
                                }
                            })
                            else -> add(buildJsonObject {
                                put("type", "text")
                                put("text", att.unsupportedAttachmentNote())
                            })
                        }
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
            val replayReasoning = !foldHistoricalToolsToText && nodeVar?.providerProtocol !in REASONING_FOREIGN_TO_CHAT
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
                    val callIds = replayIds.assign(batch.calls)
                    messages.add(buildJsonObject {
                        put("role", "assistant")
                        if (replayReasoning && batch.precedingThinking.isNotBlank()) {
                            put("reasoning_content", batch.precedingThinking)
                        }
                        putJsonArray("tool_calls") {
                            for ((tc, callId) in batch.calls.zip(callIds)) {
                                add(buildJsonObject {
                                    put("id", callId)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", tc.name)
                                        put("arguments", tc.argumentsJson.ifBlank { "{}" })
                                    }
                                })
                            }
                        }
                    })
                    for ((tc, callId) in batch.calls.zip(callIds)) {
                        val wireOut = compactHistoricalToolResultForWire(
                            rawResult = tc.resultText,
                            toolName = tc.name,
                            isOlderAssistantTurn = isOlderAssistantTurn,
                            callIndexInTurn = globalCallIdx++,
                            totalCallsInTurn = totalCalls
                        )
                        messages.add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", callId)
                            put("name", tc.name)
                            put("content", wireOut)
                        })
                    }
                }
                if (cleanText.isNotBlank()) {
                    val closingReasoning = nodeVar?.trailingThinking.orEmpty()
                    messages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", cleanText)
                        if (replayReasoning && closingReasoning.isNotBlank()) {
                            put("reasoning_content", closingReasoning)
                        }
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
                    if (replayReasoning && thinkSummary.isNotBlank() && cleanText.isNotBlank()) {
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

private fun AiProviderEngine.compactChatCompletionsToolMessages(messages: List<JsonObject>): List<JsonObject> {
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
