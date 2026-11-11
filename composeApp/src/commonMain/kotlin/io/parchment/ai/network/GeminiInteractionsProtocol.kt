package io.parchment.ai.network

import io.parchment.ai.ui.Strings

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
import io.parchment.ai.model.ProviderTokenUsage
import io.parchment.ai.model.ToolApprovalMode
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.model.keepsProviderState
import io.parchment.ai.network.AiProviderEngine.RawWebCitation
import io.parchment.ai.network.AiProviderEngine.ResolvedBinaryAttachment
import io.parchment.ai.network.AiProviderEngine.StepRetriesExhaustedException
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// Gemini Interactions: request history, the streaming agent loop and background jobs.

/**
 * Cancels a server-side background interaction on Google Gemini Interactions API.
 */
suspend fun AiProviderEngine.cancelBackgroundInteraction(profile: ProviderProfile, interactionId: String): Result<String> = runCatching {
    if (interactionId.isBlank()) return@runCatching "No interaction ID to cancel."
    // Only a stored Gemini interaction exists to cancel; other wires have no such endpoint.
    if (!profile.keepsProviderState || resolveWireProtocol(profile) != ProviderProtocol.GEMINI_INTERACTIONS) {
        return@runCatching "Nothing stored to cancel."
    }
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
// 1. Gemini Interactions API (/v1beta/interactions)
//    Supports Gemini 4 Argon Long Decode, SSE Streaming, Background=true Polling,
//    previous_interaction_id stateful chaining, Built-in Tools & Local Workspace Harness.
// ============================================================================================
internal suspend fun AiProviderEngine.executeGeminiInteractionsLoop(
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
    onOutputLimit: suspend () -> Unit = {},
    onRawWire: suspend (String, String) -> Unit,
    onRequestStarted: UsageRequestStarter,
    onUsage: UsageReporter,
    onCitation: suspend (RawWebCitation) -> Unit = {}
) {
    var usageRequestId: String? = null
    val activeNodes = sanitizeContextNodes(historyNodes)
    val systemInstruction = buildCompiledSystemPrompt(session, circuitBreaker.goalState)

    // Check if we can use stateful previous_interaction_id chaining
    val previousAssistantIdx = activeNodes.indexOfLast { it.role == MessageRole.ASSISTANT }
    val previousInteractionId = if (profile.useStatefulChaining && continuePrefix.isBlank() && previousAssistantIdx >= 0) {
        // Chaining omits local history, so a chain from another model would leave the new one with nothing.
        reusableProviderChainId(activeNodes[previousAssistantIdx].activeVariant, ProviderProtocol.GEMINI_INTERACTIONS, model)
    } else ""
    var currentPrevInteractionId = previousInteractionId
    var currentContinuationToken = ""
    var stripSamplingOnRetry = false
    var upgradeMinimalToLowOnRetry = false
    var simplifyThinkingOnRetry = false
    var omitResponseFormatOnRetry = false
    var forceImageResponseFormatOnRetry = false
    var omitBuiltInToolsOnRetry = AgentPermissionResolver.resolve(session, profile = profile).approvalMode != ToolApprovalMode.NEVER
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

                if (!profile.keepsProviderState) {
                    put("store", false)
                }

                // Always stream. With `useBackgroundLongDecode`, a dropped stream falls back to polling `/interactions/{id}`.
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
                            pendingToolResults.filter { it.hasScreenshot }.forEach { add(interactionsScreenshotStep(it)) }
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
                    AgentPermissionResolver.resolve(session, profile = profile).allowShell,
                    goalModeActive = circuitBreaker.goalState != null,
                    enableWebTools = (profile.enableWebSearch && omitBuiltInToolsOnRetry) || profile.enableUrlContext,
                    includeLocalWebSearchTool = profile.enableWebSearch && omitBuiltInToolsOnRetry,
                    policy = AgentPermissionResolver.resolve(session, profile = profile)
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
                    onStatus(Strings.backgroundPollRecoveryPoll(status, pollCount), interactionIdToPoll)
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
                        onUsage = { id, usage ->
                            if (profile.keepsProviderState && id.isNotBlank()) currentPrevInteractionId = id
                            onUsage(usageRequestId, id, UsageDialect.GEMINI_INTERACTIONS, usage)
                        },
                        onCitation = onCitation
                    )
                }
            }

            try {
                // Live SSE Streaming Mode (`stream = true`) with automatic Background Poll Recovery
                ensureAgentPolicyCurrent(session, profile)
                usageRequestId = onRequestStarted(UsageDialect.GEMINI_INTERACTIONS)
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

                    var streamFinished = false
                    readSseOrJsonFallback(
                        streamFinished = { streamFinished },
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
                            if (profile.keepsProviderState && !idCandidate.isNullOrBlank()) {
                                currentPrevInteractionId = idCandidate
                            }

                            val eventUsage = evObj["usage"] as? JsonObject
                                ?: (evObj["metadata"] as? JsonObject)?.get("total_usage") as? JsonObject
                            if (eventUsage != null) {
                                onUsage(usageRequestId, "", UsageDialect.GEMINI_INTERACTIONS,
                                    parseProviderUsage(UsageDialect.GEMINI_INTERACTIONS, eventUsage, response = evObj))
                            }

                            when (type) {
                                "interaction.start", "interaction.created", "interaction.status_update" -> {
                                    onUsage(usageRequestId, idCandidate.orEmpty(), UsageDialect.GEMINI_INTERACTIONS,
                                        parseProviderUsage(UsageDialect.GEMINI_INTERACTIONS, interactionSub?.get("usage") as? JsonObject))
                                    val st = interactionSub?.get("status")?.jsonPrimitive?.contentOrNull
                                        ?: evObj["status"]?.jsonPrimitive?.contentOrNull ?: "streaming"
                                    onStatus(Strings.interaction(st), currentPrevInteractionId)
                                }
                                "content.start", "step.start" -> {
                                    onUsage(usageRequestId, "", UsageDialect.GEMINI_INTERACTIONS,
                                        parseProviderUsage(UsageDialect.GEMINI_INTERACTIONS, null, response = evObj))
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
                                    streamFinished = true
                                    val completedObj = interactionSub ?: evObj
                                    roundStatus = completedObj["status"]?.jsonPrimitive?.contentOrNull
                                        ?: evObj["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    roundContinuationToken = completedObj["continuation_token"]?.jsonPrimitive?.contentOrNull
                                        ?: evObj["continuation_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if (roundStatus == "incomplete") onOutputLimit()
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
                                    val finalText = extractGeminiInteractionText(completedObj)
                                    val streamed = roundModelText.toString()
                                    if (finalText.length > streamed.length && finalText.startsWith(streamed)) {
                                        val suffix = finalText.substring(streamed.length)
                                        onFirstToken()
                                        receivedAnyStreamContent = true
                                        roundModelText.append(suffix)
                                        onContent(suffix)
                                    }
                                    val usageObj = completedObj["usage"] as? JsonObject
                                    onUsage(usageRequestId, completedObj["id"]?.jsonPrimitive?.contentOrNull ?: currentPrevInteractionId, UsageDialect.GEMINI_INTERACTIONS,
                                        parseProviderUsage(UsageDialect.GEMINI_INTERACTIONS, usageObj, isFinal = true, response = completedObj))
                                }
                                "error" -> {
                                    val errObj = evObj["error"] as? JsonObject
                                    val msg = errObj?.get("message")?.jsonPrimitive?.contentOrNull ?: dataLine
                                    error("Gemini Interactions Stream Error: $msg")
                                }
                                else -> {
                                    if (evObj.containsKey("outputs") || evObj.containsKey("steps")) {
                                        streamFinished = true
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
                                            onUsage = { id, usage ->
                                                if (profile.keepsProviderState && id.isNotBlank()) currentPrevInteractionId = id
                                                onUsage(usageRequestId, id, UsageDialect.GEMINI_INTERACTIONS, usage)
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
                            if (profile.keepsProviderState && id.isNotBlank()) currentPrevInteractionId = id
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
                                    onUsage = { uId, usage ->
                                        if (profile.keepsProviderState && uId.isNotBlank()) currentPrevInteractionId = uId
                                        onUsage(usageRequestId, uId, UsageDialect.GEMINI_INTERACTIONS, usage)
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
                        onStatus(Strings.statefulInteractionChainRejectedRetryingStep, "")
                        continue
                    }
                    if (!stripSamplingOnRetry && (errMsg.contains("temperature") || errMsg.contains("top_p") || errMsg.contains("sampling"))) {
                        stripSamplingOnRetry = true
                        onStatus(Strings.endpointRejectedSamplingParametersRetryingWith, currentPrevInteractionId)
                        continue
                    }
                    if (!upgradeMinimalToLowOnRetry && profile.thinkingEffort.equals("minimal", ignoreCase = true) &&
                        (errMsg.contains("minimal") || errMsg.contains("thinking_level") || errMsg.contains("thinking"))
                    ) {
                        upgradeMinimalToLowOnRetry = true
                        onStatus(Strings.modelDoesNotSupportMinimalThinking, currentPrevInteractionId)
                        continue
                    }
                    if (!simplifyThinkingOnRetry && (errMsg.contains("thinking") || errMsg.contains("thinking_level"))) {
                        simplifyThinkingOnRetry = true
                        onStatus(Strings.endpointRejectedThinkingLevelRetryingWith, currentPrevInteractionId)
                        continue
                    }
                    if (!forceImageResponseFormatOnRetry && !omitResponseFormatOnRetry &&
                        (errMsg.contains("only supports image") || (errMsg.contains("response_format") && errMsg.contains("image")))
                    ) {
                        forceImageResponseFormatOnRetry = true
                        recordDiscoveredModality(model, listOf("text", "image"))
                        onStatus(Strings.modelOutputsImagesEnablingImageResponse, currentPrevInteractionId)
                        continue
                    }
                    if (!omitResponseFormatOnRetry && (errMsg.contains("response_format") || errMsg.contains("image_size") || errMsg.contains("aspect_ratio"))) {
                        omitResponseFormatOnRetry = true
                        onStatus(Strings.endpointRejectedResponseFormatRetryingWithout, currentPrevInteractionId)
                        continue
                    }
                    if (!omitBuiltInToolsOnRetry && (errMsg.contains("google_search") || errMsg.contains("url_context") || errMsg.contains("built-in") || errMsg.contains("multiple tools"))) {
                        omitBuiltInToolsOnRetry = true
                        onStatus(Strings.endpointRejectedMixedBuiltInFunction, currentPrevInteractionId)
                        continue
                    }
                    if (!foldHistoricalToolsOnRetry && hasHistoricalToolsOrSigs &&
                        (errMsg.contains("couldn't process") || errMsg.contains("invalid_request") || errMsg.contains("thought") ||
                            errMsg.contains("signature") || errMsg.contains("function_call") || errMsg.contains("tool type"))
                    ) {
                        foldHistoricalToolsOnRetry = true
                        onStatus(Strings.endpointRejectedHistoricalToolSignatureContext, currentPrevInteractionId)
                        continue
                    }
                }
                if (useChainedId && !receivedAnyStreamContent &&
                    (errMsg.contains("http 404") || errMsg.contains("previous_interaction_id") || errMsg.contains("function response turn") || errMsg.contains("function_result"))
                ) {
                    currentPrevInteractionId = ""
                    currentContinuationToken = ""
                    onStatus(Strings.statefulInteractionChainRejectedRetryingStep, "")
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
            onStatus(Strings.longDecodeHopCompletedContinuingVia, currentPrevInteractionId)
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
                onStatus(Strings.goalWorkCheckContinuingIncompleteChecklist, currentPrevInteractionId)
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
            allowShell = AgentPermissionResolver.resolve(session, profile = profile).allowShell,
            accessScope = ToolAccessScope(
                AgentPermissionResolver.resolve(session, profile = profile),
                goalModeActive = circuitBreaker.goalState != null,
                includeLocalWebSearchTool = profile.enableWebSearch && omitBuiltInToolsOnRetry
            ),
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
        if (results.any { it.hasScreenshot }) extraLoopSteps.dropOlderScreenshotEntries()
        results.filter { it.hasScreenshot }.forEach { extraLoopSteps.add(interactionsScreenshotStep(it)) }
        pendingToolResults = results
        val trippedReason = circuitBreaker.checkTripped()
        if (trippedReason != null) {
            onStatus(trippedReason, currentPrevInteractionId)
            break
        }
    }
}

internal fun AiProviderEngine.extractGeminiInteractionText(interaction: JsonObject): String = buildString {
    val steps = interaction["steps"] as? JsonArray ?: interaction["outputs"] as? JsonArray
    steps?.forEach stepLoop@ { element ->
        val step = element as? JsonObject ?: return@stepLoop
        when (step["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> append(step["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "model_output" -> (step["content"] as? JsonArray)?.forEach contentLoop@ { content ->
                val block = content as? JsonObject ?: return@contentLoop
                if (block["type"]?.jsonPrimitive?.contentOrNull in listOf("text", null)) {
                    append(block["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }
            }
        }
    }
}

private suspend fun AiProviderEngine.parseCompletedGeminiInteractionObject(
    interactionObj: JsonObject,
    onFirstToken: () -> Unit,
    onThinking: suspend (String, String) -> Unit,
    onContent: suspend (String) -> Unit,
    onMedia: suspend (GeneratedMediaItem) -> Unit,
    onFunctionCall: (String, String, String) -> Unit,
    onUsage: suspend (String, ProviderTokenUsage) -> Unit,
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
    onUsage(id, parseProviderUsage(UsageDialect.GEMINI_INTERACTIONS, usageObj, isFinal = true, response = interactionObj))
}

/**
 * Builds the official Gemini Interactions API `input` Steps array:
 * - User turns -> `{"type": "user_input", "content": [{"type": "text"|"image"|"video"|"audio"|"document", ...}]}`
 *   (automatically separating consecutive `user_input` turns with `\n\n` so prompts never glue together)
 * - Assistant turns -> chronological `thought` (`signature` + `summary`) + `function_call` + `function_result` + `model_output` steps.
 */
internal fun AiProviderEngine.buildGeminiInteractionsTurnsArray(
    nodes: List<MessageNode>,
    session: ChatSession,
    continuePrefix: String,
    foldHistoricalToolsToText: Boolean = false
): JsonArray {
    val stepsList = mutableListOf<JsonObject>()
    val replayIds = ReplayToolCallIds()
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
            val turnText = turnTextLayout(text, toolCalls)
            val finalText = stripInlineErrorNotices(turnText.finalText)
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
                        beforeTool = turnText.beforeTool,
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
                        if (batch.precedingText.isNotBlank()) {
                            stepsList.add(buildJsonObject {
                                put("type", "model_output")
                                putJsonArray("content") {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", batch.precedingText)
                                    })
                                }
                            })
                            emittedAnyStepForTurn = true
                        }
                        val callIds = replayIds.assign(batch.calls)
                        for ((tc, callId) in batch.calls.zip(callIds)) {
                            val parsedArgs = runCatching { json.parseToJsonElement(tc.argumentsJson.ifBlank { "{}" }).jsonObject }
                                .getOrElse { JsonObject(emptyMap()) }
                            stepsList.add(buildJsonObject {
                                put("type", "function_call")
                                put("id", callId)
                                put("name", tc.name)
                                put("arguments", parsedArgs)
                            })
                        }
                        for ((tc, callId) in batch.calls.zip(callIds)) {
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
                    finalText.isNotBlank() -> finalText
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

/**
 * 3-Layer Rolling Context Compaction Helpers for Gemini Interactions, OpenAI Responses,
 * OpenAI Chat Completions, and Anthropic Messages loops.
 */
private fun AiProviderEngine.compactGeminiInteractionsExtraSteps(steps: List<JsonObject>): List<JsonObject> {
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
