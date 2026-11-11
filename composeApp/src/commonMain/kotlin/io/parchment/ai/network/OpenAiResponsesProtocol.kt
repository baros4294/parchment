package io.parchment.ai.network

import io.parchment.ai.ui.Strings

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
import io.parchment.ai.model.turnTextLayout
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.GoalTrackerState
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.ToolApprovalMode
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.model.keepsProviderState
import io.parchment.ai.network.AiProviderEngine.RawWebCitation
import io.parchment.ai.network.AiProviderEngine.ResolvedBinaryAttachment
import io.parchment.ai.network.AiProviderEngine.StepRetriesExhaustedException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// OpenAI Responses: request history and the streaming agent loop.

// ============================================================================================
// 2. OpenAI Responses API (/v1/responses)
//    Supports stateful previous_response_id chaining, reasoning effort & summary,
//    web_search_preview, and Local Workspace Function Tools loop.
// ============================================================================================
internal suspend fun AiProviderEngine.executeOpenAiResponsesLoop(
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
    var sawMessagePhase = false
    val reportToolCall: suspend (ToolCallRecord) -> Unit = { onToolCall(it.copy(messagePhases = sawMessagePhase)) }
    val activeNodes = sanitizeContextNodes(historyNodes)
    val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)

    val previousAssistantIdx = activeNodes.indexOfLast { it.role == MessageRole.ASSISTANT }
    var currentPrevResponseId = if (profile.useStatefulChaining && continuePrefix.isBlank() && previousAssistantIdx >= 0) {
        reusableProviderChainId(activeNodes[previousAssistantIdx].activeVariant, ProviderProtocol.OPENAI_RESPONSES, model)
    } else ""

    var pendingToolResults: List<ToolCallRecord> = emptyList()
    var pendingStopHookPrompt: String = ""
    val extraResponsesLoopItems = mutableListOf<JsonObject>()
    var stripSamplingOnRetry = false
    var omitReasoningOnRetry = false
    var useLegacyWebSearchPreviewOnRetry = false
    var omitOpenAiNativeWebSearchOnRetry = AgentPermissionResolver.resolve(session, profile = profile).approvalMode != ToolApprovalMode.NEVER
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
        var roundPhase = ""
        fun noteMessagePhase(item: JsonObject?) {
            val phase = (item?.get("phase") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (phase.isNotEmpty() && (item?.get("type") as? JsonPrimitive)?.contentOrNull == "message") {
                roundPhase = phase
                sawMessagePhase = true
            }
        }
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
                put("store", profile.keepsProviderState)

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
                        pendingToolResults.filter { it.hasScreenshot }.forEach { add(responsesScreenshotMessage(it)) }
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
                        AgentPermissionResolver.resolve(session, profile = profile).allowShell,
                        goalModeActive = circuitBreaker.goalState != null,
                        enableWebTools = profile.enableWebSearch || profile.enableUrlContext,
                        includeLocalWebSearchTool = profile.enableWebSearch && omitOpenAiNativeWebSearchOnRetry,
                        policy = AgentPermissionResolver.resolve(session, profile = profile)
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
                ensureAgentPolicyCurrent(session, profile)
                usageRequestId = onRequestStarted(UsageDialect.OPENAI_RESPONSES)
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

                    var streamFinished = false
                    readSseOrJsonFallback(
                        streamFinished = { streamFinished },
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
                                    onUsage(usageRequestId, id, UsageDialect.OPENAI_RESPONSES,
                                        parseProviderUsage(UsageDialect.OPENAI_RESPONSES, respSub?.get("usage") as? JsonObject))
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
                                    noteMessagePhase(item)
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
                                    noteMessagePhase(item)
                                    onUsage(usageRequestId, "", UsageDialect.OPENAI_RESPONSES,
                                        parseProviderUsage(UsageDialect.OPENAI_RESPONSES, null, response = evObj))
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
                                "response.failed" -> {
                                    val failure = ((evObj["response"] as? JsonObject)?.get("error") as? JsonObject)
                                    error("OpenAI Responses Error: ${failure?.get("message")?.jsonPrimitive?.contentOrNull ?: dataLine}")
                                }
                                "response.completed", "response.incomplete" -> {
                                    streamFinished = true
                                    val incomplete = ((evObj["response"] as? JsonObject)?.get("incomplete_details") as? JsonObject)
                                    if (incomplete?.get("reason")?.jsonPrimitive?.contentOrNull == "max_output_tokens") onOutputLimit()
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
                                        onUsage(usageRequestId, respSub.get("id")?.jsonPrimitive?.contentOrNull ?: currentPrevResponseId, UsageDialect.OPENAI_RESPONSES,
                                            parseProviderUsage(UsageDialect.OPENAI_RESPONSES, usage, isFinal = true, response = respSub))
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
                            onUsage(usageRequestId, id, UsageDialect.OPENAI_RESPONSES,
                                parseProviderUsage(UsageDialect.OPENAI_RESPONSES, root["usage"] as? JsonObject, isFinal = true, response = root))
                            val outputArr = root["output"] as? JsonArray
                            outputArr?.forEach { itemEl ->
                                val item = itemEl as? JsonObject ?: return@forEach
                                when (item["type"]?.jsonPrimitive?.contentOrNull) {
                                    "message" -> {
                                        noteMessagePhase(item)
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
                        onStatus(Strings.statefulResponseChainRejectedRetryingStep)
                        continue
                    }
                    if (!omitEncryptedContentIncludeOnRetry && (errMsg.contains("encrypted_content") || errMsg.contains("include"))) {
                        omitEncryptedContentIncludeOnRetry = true
                        onStatus(Strings.endpointRejectedReasoningEncryptedContentInclude)
                        continue
                    }
                    if (!useLegacyWebSearchPreviewOnRetry && profile.enableWebSearch &&
                        (errMsg.contains("web_search") && !errMsg.contains("web_search_preview"))
                    ) {
                        useLegacyWebSearchPreviewOnRetry = true
                        onStatus(Strings.endpointUsesLegacyWebSearchPreview)
                        continue
                    }
                    if (useLegacyWebSearchPreviewOnRetry && !omitOpenAiNativeWebSearchOnRetry && profile.enableWebSearch &&
                        errMsg.contains("web_search")
                    ) {
                        omitOpenAiNativeWebSearchOnRetry = true
                        onStatus(Strings.endpointDoesNotSupportNativeWeb2)
                        continue
                    }
                    if (!omitImageGenToolOnRetry && modelOutputsImages && errMsg.contains("image_generation")) {
                        omitImageGenToolOnRetry = true
                        onStatus(Strings.endpointRejectedImageGenerationToolRetrying)
                        continue
                    }
                    if (!omitReasoningOnRetry && (errMsg.contains("reasoning") || errMsg.contains("effort") || errMsg.contains("summary"))) {
                        omitReasoningOnRetry = true
                        onStatus(Strings.endpointRejectedReasoningParameterRetryingWithout)
                        continue
                    }
                    if (!stripSamplingOnRetry && (errMsg.contains("temperature") || errMsg.contains("top_p") || errMsg.contains("sampling"))) {
                        stripSamplingOnRetry = true
                        onStatus(Strings.endpointRejectedSamplingParametersRetryingWith)
                        continue
                    }
                    if (!foldHistoricalToolsOnRetry && hasHistoricalToolsOrSigs &&
                        (errMsg.contains("invalid_request") || errMsg.contains("reasoning") || errMsg.contains("encrypted_content") ||
                            errMsg.contains("function_call") || errMsg.contains("call_id"))
                    ) {
                        foldHistoricalToolsOnRetry = true
                        onStatus(Strings.endpointRejectedHistoricalToolReasoningItems)
                        continue
                    }
                }
                if (useChainedId && !stepEmittedOutput &&
                    (errMsg.contains("http 404") || errMsg.contains("previous_response_id") || errMsg.contains("no tool call found"))
                ) {
                    currentPrevResponseId = ""
                    onStatus(Strings.statefulResponseChainRejectedRetryingStep)
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
                        if (roundPhase.isNotEmpty()) put("phase", roundPhase)
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
                onStatus(Strings.goalWorkCheckContinuingIncompleteChecklist)
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
                if (roundPhase.isNotEmpty()) put("phase", roundPhase)
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
            allowShell = AgentPermissionResolver.resolve(session, profile = profile).allowShell,
            accessScope = ToolAccessScope(
                AgentPermissionResolver.resolve(session, profile = profile),
                goalModeActive = circuitBreaker.goalState != null,
                includeLocalWebSearchTool = profile.enableWebSearch && omitOpenAiNativeWebSearchOnRetry
            ),
            calls = turnToolCalls.map { (callId, fnPair) -> Triple(callId, fnPair.first, fnPair.second) },
            firstThoughtSignature = roundEncryptedReasoning,
            firstPrecedingThinking = roundReasoningBuilder.toString().trim(),
            circuitBreaker = circuitBreaker,
            onStatus = onStatus,
            onToolCall = reportToolCall,
            onGoalProgress = onGoalProgress
        )
        for (rec in results) {
            extraResponsesLoopItems.add(buildJsonObject {
                put("type", "function_call_output")
                put("call_id", rec.id)
                put("output", rec.resultText)
            })
        }
        if (results.any { it.hasScreenshot }) extraResponsesLoopItems.dropOlderScreenshotEntries()
        results.filter { it.hasScreenshot }.forEach { extraResponsesLoopItems.add(responsesScreenshotMessage(it)) }
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
internal fun AiProviderEngine.buildOpenAiResponsesInputArray(
    nodes: List<MessageNode>,
    session: ChatSession,
    continuePrefix: String,
    foldHistoricalToolsToText: Boolean = false
): JsonArray {
    val items = mutableListOf<JsonObject>()
    val replayIds = ReplayToolCallIds()
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
                        when {
                            att.isDocument -> add(buildJsonObject {
                                put("type", "input_file")
                                put("filename", att.fileName.ifBlank { "document.pdf" })
                                put("file_data", "data:${att.mimeType};base64,${att.base64Data}")
                            })
                            att.mimeType.lowercase().startsWith("image/") -> add(buildJsonObject {
                                put("type", "input_image")
                                put("image_url", "data:${att.mimeType};base64,${att.base64Data}")
                            })
                            else -> add(buildJsonObject {
                                put("type", "input_text")
                                put("text", att.unsupportedAttachmentNote())
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
            val turnText = turnTextLayout(text, toolCalls)
            val finalText = stripInlineErrorNotices(turnText.finalText)
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
                        beforeTool = turnText.beforeTool,
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
                        if (batch.precedingText.isNotBlank()) {
                            items.add(buildJsonObject {
                                put("type", "message")
                                put("role", "assistant")
                                put("status", "completed")
                                if (toolCalls.any { it.messagePhases }) put("phase", "commentary")
                                putJsonArray("content") {
                                    add(buildJsonObject {
                                        put("type", "output_text")
                                        put("text", batch.precedingText)
                                    })
                                }
                            })
                            emittedAny = true
                        }
                        val callIds = replayIds.assign(batch.calls)
                        for ((tc, callId) in batch.calls.zip(callIds)) {
                            items.add(buildJsonObject {
                                put("type", "function_call")
                                put("call_id", callId)
                                put("name", tc.name)
                                put("arguments", tc.argumentsJson.ifBlank { "{}" })
                                put("status", "completed")
                            })
                        }
                        for ((tc, callId) in batch.calls.zip(callIds)) {
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
                    finalText.isNotBlank() -> finalText
                    thinkSummary.isNotBlank() && toolCalls.isEmpty() -> "[Interrupted during reasoning — partial thoughts:\n$thinkSummary]"
                    !emittedAny -> "..."
                    else -> ""
                }
                if (outputText.isNotBlank()) {
                    items.add(buildJsonObject {
                        put("type", "message")
                        put("role", "assistant")
                        put("status", "completed")
                        if (toolCalls.any { it.messagePhases }) put("phase", "final_answer")
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

private fun AiProviderEngine.compactOpenAiResponsesExtraItems(items: List<JsonObject>): List<JsonObject> {
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
