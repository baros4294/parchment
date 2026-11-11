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
import io.parchment.ai.network.AiProviderEngine.RawWebCitation
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

// Gemini generateContent: request history and the streaming agent loop.

// ============================================================================================
// 1B. Gemini streamGenerateContent (/v1beta/models/{model}:streamGenerateContent)
//     Implements the exact Gemini 4 Argon / Gemini 3+ / Gemini 2.5 dialect:
//     - Gemini 4 Argon: `thinkingConfig: { includeThoughts: true, thinkingLevel: "LOW"|"MEDIUM"|"HIGH" }`,
//       omits `temperature`/`topP` (SamplingPolicy.never), defaults to 65536 maxOutputTokens,
//       and replays `thoughtSignature` on multi-turn history & tool calls.
// ============================================================================================
internal suspend fun AiProviderEngine.executeGeminiGenerateContentLoop(
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
    onOutputLimit: suspend () -> Unit = {},
    onRawWire: suspend (String, String) -> Unit,
    onRequestStarted: UsageRequestStarter,
    onUsage: UsageReporter,
    onCitation: suspend (RawWebCitation) -> Unit = {}
) {
    var usageRequestId: String? = null
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
    var disableMixedToolsOnRetry = AgentPermissionResolver.resolve(session, profile = profile).approvalMode != ToolApprovalMode.NEVER
    var forceImageModalityOnRetry = false
    var omitImageModalityOnRetry = false
    var foldHistoricalToolsOnRetry = false
    var loopStep = 0
    while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
        loopStep++
        val activeWorkspaceTools = WorkspaceHarness.activeToolsForMode(
            session.workspaceMode,
            session.workspacePath,
            AgentPermissionResolver.resolve(session, profile = profile).allowShell,
            goalModeActive = circuitBreaker.goalState != null,
            enableWebTools = (profile.enableWebSearch && disableMixedToolsOnRetry) || profile.enableUrlContext,
            includeLocalWebSearchTool = profile.enableWebSearch && disableMixedToolsOnRetry,
            policy = AgentPermissionResolver.resolve(session, profile = profile)
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

                // Tools and toolConfig: try built-in and function tools together, and fall back to AUTO if the API rejects it (400).
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
            val rawUrl = if (profile.endpointPathOverride.contains("interactions", ignoreCase = true) && !profile.protocol.isCustomWire) {
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
                ensureAgentPolicyCurrent(session, profile)
                usageRequestId = onRequestStarted(UsageDialect.GEMINI_CONTENT)
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

                    var streamFinished = false
                    readSseOrJsonFallback(
                        streamFinished = { streamFinished },
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

                            val finishReason = (firstCand?.get("finishReason") as? JsonPrimitive)?.contentOrNull
                            if (finishReason != null) streamFinished = true
                            if (finishReason == "MAX_TOKENS") onOutputLimit()
                            val usage = obj["usageMetadata"] as? JsonObject
                            if (firstCand?.get("groundingMetadata") is JsonObject) {
                                onUsage(usageRequestId, "", UsageDialect.GEMINI_CONTENT,
                                    parseProviderUsage(UsageDialect.GEMINI_CONTENT, null, response = obj))
                            }
                            if (usage != null) {
                                onUsage(usageRequestId, obj["responseId"]?.jsonPrimitive?.contentOrNull.orEmpty(), UsageDialect.GEMINI_CONTENT,
                                    parseProviderUsage(UsageDialect.GEMINI_CONTENT, usage, isFinal = firstCand?.get("finishReason") != null, response = obj))
                            }
                        },
                        onPlainJsonFallback = { body ->
                            onRawWire(reqStr, body)
                            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@readSseOrJsonFallback
                            onUsage(usageRequestId, obj["responseId"]?.jsonPrimitive?.contentOrNull.orEmpty(), UsageDialect.GEMINI_CONTENT,
                                parseProviderUsage(UsageDialect.GEMINI_CONTENT, obj["usageMetadata"] as? JsonObject, isFinal = true, response = obj))
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
                        onStatus(Strings.endpointRejectedSamplingParametersRetryingWith)
                        continue
                    }
                    if (!forceImageModalityOnRetry && !omitImageModalityOnRetry &&
                        (errMsg.contains("responsemodalities") || errMsg.contains("response_modalities") || errMsg.contains("image")) &&
                        (errMsg.contains("only supports") || errMsg.contains("must include") || errMsg.contains("modality"))
                    ) {
                        forceImageModalityOnRetry = true
                        recordDiscoveredModality(cleanModel, listOf("text", "image"))
                        onStatus(Strings.modelOutputsImagesEnablingTextImage)
                        continue
                    }
                    if (!omitImageModalityOnRetry && (modelOutputsImages || forceImageModalityOnRetry) &&
                        (errMsg.contains("responsemodalities") || errMsg.contains("response_modalities"))
                    ) {
                        omitImageModalityOnRetry = true
                        onStatus(Strings.endpointRejectedResponsemodalitiesRetryingWithText)
                        continue
                    }
                    if (!disableMixedToolsOnRetry && hasBuiltInTools && activeWorkspaceTools.isNotEmpty() &&
                        (errMsg.contains("validated") || errMsg.contains("includeserversidetoolinvocations") || errMsg.contains("tool") || errMsg.contains("function_declarations") || errMsg.contains("google_search"))
                    ) {
                        disableMixedToolsOnRetry = true
                        onStatus(Strings.endpointRejectedMixedBuiltInFunction)
                        continue
                    }
                    if (!upgradeMinimalToLowOnRetry && !useBudgetDialectOnRetry &&
                        (profile.thinkingEffort.equals("minimal", ignoreCase = true) || profile.thinkingEffort.equals("none", ignoreCase = true) || profile.thinkingEffort.equals("off", ignoreCase = true)) &&
                        (errMsg.contains("minimal") || errMsg.contains("thinkinglevel") || errMsg.contains("thinking_level"))
                    ) {
                        upgradeMinimalToLowOnRetry = true
                        onStatus(Strings.modelRejectedMinimalThinkinglevelSteppingUp)
                        continue
                    }
                    if (!useBudgetDialectOnRetry && (errMsg.contains("thinkinglevel") || errMsg.contains("thinking_level"))) {
                        useBudgetDialectOnRetry = true
                        onStatus(Strings.modelUsesThinkingbudgetDialectSwitchingFrom)
                        continue
                    }
                    if (!simplifyThinkingOnRetry && (errMsg.contains("thinking") || errMsg.contains("thinkinglevel") || errMsg.contains("thinkingbudget"))) {
                        simplifyThinkingOnRetry = true
                        onStatus(Strings.endpointRejectedThinkingconfigRetryingWithDefault)
                        continue
                    }
                    if (!foldHistoricalToolsOnRetry && hasHistoricalToolsOrSigs &&
                        (errMsg.contains("couldn't process") || errMsg.contains("invalid_request") || errMsg.contains("thought") ||
                            errMsg.contains("signature") || errMsg.contains("functioncall") || errMsg.contains("function_call") || errMsg.contains("tool type"))
                    ) {
                        foldHistoricalToolsOnRetry = true
                        onStatus(Strings.endpointRejectedHistoricalToolSignatureContext)
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
                onStatus(Strings.goalWorkCheckContinuingIncompleteChecklist)
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
            allowShell = AgentPermissionResolver.resolve(session, profile = profile).allowShell,
            accessScope = ToolAccessScope(
                AgentPermissionResolver.resolve(session, profile = profile),
                goalModeActive = circuitBreaker.goalState != null,
                includeLocalWebSearchTool = profile.enableWebSearch && disableMixedToolsOnRetry
            ),
            calls = roundToolCalls,
            firstThoughtSignature = cleanRoundSig,
            circuitBreaker = circuitBreaker,
            onStatus = onStatus,
            onToolCall = onToolCall,
            onGoalProgress = onGoalProgress
        )
        if (batchRecords.any { it.hasScreenshot }) extraLoopContentTurns.dropOlderGeminiScreenshots()
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
            put("parts", JsonArray(responseParts + batchRecords.filter { it.hasScreenshot }.map(::geminiScreenshotPart)))
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
internal fun AiProviderEngine.buildGeminiGenerateContentContents(
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
            val turnText = turnTextLayout(text, toolCalls)
            val finalText = stripInlineErrorNotices(turnText.finalText)
            val isOlderAssistantTurn = idx < lastAssistantIdx
            var emittedAny = false

            if (foldHistoricalToolsToText && toolCalls.isNotEmpty()) {
                val foldedText = buildFoldedToolHistoryText(toolCalls, thinkSummary, cleanText)
                appendOrMergeContentTurn(
                    "model",
                    listOf(buildJsonObject { put("text", foldedText) })
                )
            } else {
                val handoffCarriesReasoning = isForeignProtocol && cleanText.contains(REASONING_HANDOFF_MARKER)
                if (toolCalls.isEmpty() && thinkSummary.isNotBlank() && !handoffCarriesReasoning) {
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
                        beforeTool = turnText.beforeTool,
                        fallbackTurnSignature = rawSavedSig,
                        fallbackTurnThinking = thinkSummary
                    )
                    val totalCalls = toolCalls.size
                    var globalCallIdx = 0
                    for (batch in batches) {
                        val cleanBatchSig = if (isForeignProtocol) "" else sanitizeGeminiThoughtSignature(batch.thoughtSignature, forFunctionCall = true)
                        val effSig = cleanBatchSig.ifBlank { "skip_thought_signature_validator" }
                        val modelStepParts = mutableListOf<JsonObject>()
                        if (batch.precedingThinking.isNotBlank() && !isForeignProtocol) {
                            modelStepParts.add(buildJsonObject {
                                put("thought", true)
                                put("text", batch.precedingThinking)
                            })
                        }
                        if (batch.precedingText.isNotBlank()) modelStepParts.add(buildJsonObject { put("text", batch.precedingText) })
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

                if (finalText.isNotBlank() || !emittedAny) {
                    val modelTextPart = buildJsonObject {
                        put("text", finalText.ifBlank { "..." })
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
