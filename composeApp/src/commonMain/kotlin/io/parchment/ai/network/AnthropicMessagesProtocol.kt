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
import io.parchment.ai.model.turnTextLayout
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.GoalTrackerState
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ToolApprovalMode
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.network.AiProviderEngine.RawWebCitation
import io.parchment.ai.network.AiProviderEngine.StepRetriesExhaustedException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// Anthropic Messages: request history and the streaming agent loop.

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

    /** A provider-run block, such as a web search call or result, replayed exactly as received. */
    data class ServerBlock(val raw: JsonObject, val streamedInput: StringBuilder = StringBuilder()) : AnthropicStreamBlock() {
        fun resolved(parse: (String) -> JsonObject?): JsonObject {
            val input = streamedInput.toString().takeIf { it.isNotBlank() }?.let(parse) ?: return raw
            return JsonObject(raw + ("input" to input))
        }
    }

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

internal suspend fun AiProviderEngine.executeAnthropicMessagesLoop(
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
    onOutputLimit: suspend () -> Unit = {},
    onRawWire: suspend (String, String) -> Unit,
    onRequestStarted: UsageRequestStarter,
    onUsage: UsageReporter,
    onCitation: suspend (RawWebCitation) -> Unit = {}
) {
    var usageRequestId: String? = null
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
    var omitNativeWebSearchOnRetry = AgentPermissionResolver.resolve(session, profile = profile).approvalMode != ToolApprovalMode.NEVER
    var loopStep = 0

    while (loopStep < MAX_SAFETY_HORIZON_STEPS) {
        loopStep++
        val streamBlocksByIndex = mutableMapOf<Int, AnthropicStreamBlock>()
        var stepEmittedOutput = false
        var stepRetryAttempt = 0
        var turnPaused = false

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
                        // Official tiers: low | medium | high | xhigh | max. `xhigh` is its own
                        // level and must pass through verbatim; only the non-official
                        // `minimal` is folded down to `low`.
                        val normalizedAnthropicEffort = when (lowerEffort) {
                            "minimal" -> "low"
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
                    // The newest turn carries the marker, so each tool step reads the steps before it from cache.
                    compactedAnthropicExtras.forEachIndexed { index, extraMsg ->
                        add(if (index == compactedAnthropicExtras.lastIndex) extraMsg.withCacheMarkerOnLastBlock() else extraMsg)
                    }
                }

                val wsTools = WorkspaceHarness.buildAnthropicTools(
                    session.workspaceMode,
                    session.workspacePath,
                    AgentPermissionResolver.resolve(session, profile = profile).allowShell,
                    goalModeActive = circuitBreaker.goalState != null,
                    enableWebTools = profile.enableWebSearch || profile.enableUrlContext,
                    includeLocalWebSearchTool = profile.enableWebSearch && omitNativeWebSearchOnRetry,
                    policy = AgentPermissionResolver.resolve(session, profile = profile)
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
                ensureAgentPolicyCurrent(session, profile)
                usageRequestId = onRequestStarted(UsageDialect.ANTHROPIC)
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

                    var streamFinished = false
                    readSseOrJsonFallback(
                        streamFinished = { streamFinished },
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
                                        onUsage(usageRequestId, msgId, UsageDialect.ANTHROPIC,
                                            parseProviderUsage(UsageDialect.ANTHROPIC, usage))
                                    }
                                }
                                "content_block_start" -> {
                                    val idx = evObj["index"]?.jsonPrimitive?.intOrNull ?: streamBlocksByIndex.size
                                    val cb = evObj["content_block"] as? JsonObject
                                    onUsage(usageRequestId, msgId, UsageDialect.ANTHROPIC,
                                        parseProviderUsage(UsageDialect.ANTHROPIC, null, response = evObj))
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
                                        "server_tool_use", "web_search_tool_result", "web_fetch_tool_result", "code_execution_tool_result" -> {
                                            if (cb != null) streamBlocksByIndex[idx] = AnthropicStreamBlock.ServerBlock(cb)
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
                                            (streamBlocksByIndex[idx] as? AnthropicStreamBlock.ServerBlock)?.streamedInput?.append(partial)
                                        }
                                        "citations_delta" -> {
                                            val citObj = deltaObj["citation"] as? JsonObject ?: deltaObj
                                            val currentBlockTxt = (streamBlocksByIndex[idx] as? AnthropicStreamBlock.Text)?.text?.toString().orEmpty()
                                            extractCitationFromAnthropicCitationObj(citObj, currentBlockTxt, onCitation)
                                        }
                                    }
                                }
                                "message_stop" -> {
                                    streamFinished = true
                                    onUsage(usageRequestId, msgId, UsageDialect.ANTHROPIC,
                                        parseProviderUsage(UsageDialect.ANTHROPIC, null, isFinal = true))
                                }
                                "message_delta" -> {
                                    val stopReason = (evObj["delta"] as? JsonObject)?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                                    if (stopReason != null) streamFinished = true
                                    if (stopReason == "max_tokens") onOutputLimit()
                                    if (stopReason == "pause_turn") turnPaused = true
                                    val usage = evObj["usage"] as? JsonObject
                                    if (usage != null) {
                                        onUsage(usageRequestId, msgId, UsageDialect.ANTHROPIC,
                                            parseProviderUsage(UsageDialect.ANTHROPIC, usage))
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
                                onUsage(usageRequestId, id, UsageDialect.ANTHROPIC,
                                    parseProviderUsage(UsageDialect.ANTHROPIC, usage, isFinal = true, response = root))
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
                is AnthropicStreamBlock.ServerBlock -> {
                    assistantContentBlocksForToolLoop.add(block.resolved { raw ->
                        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    })
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
            if (turnPaused && assistantContentBlocksForToolLoop.isNotEmpty()) {
                // A long provider-run step paused: sending the turn back as it stands lets it continue.
                extraLoopMessages.add(buildJsonObject {
                    put("role", "assistant")
                    put("content", JsonArray(assistantContentBlocksForToolLoop))
                })
                onStatus("Continuing a long-running step…")
                continue
            }
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
            allowShell = AgentPermissionResolver.resolve(session, profile = profile).allowShell,
            accessScope = ToolAccessScope(
                AgentPermissionResolver.resolve(session, profile = profile),
                goalModeActive = circuitBreaker.goalState != null,
                includeLocalWebSearchTool = profile.enableWebSearch && omitNativeWebSearchOnRetry
            ),
            calls = turnToolCalls.map { (callId, fnPair) -> Triple(callId, fnPair.first, fnPair.second) },
            firstThoughtSignature = firstSignedThinking,
            firstPrecedingThinking = firstSignedThinkingText,
            circuitBreaker = circuitBreaker,
            onStatus = onStatus,
            onToolCall = onToolCall,
            onGoalProgress = onGoalProgress
        )
        if (batchRecords.any { it.hasScreenshot }) extraLoopMessages.dropOlderAnthropicScreenshots()
        val toolResultBlocks = batchRecords.map { rec ->
            buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", rec.id)
                put("content", anthropicToolResultContent(rec))
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
private fun JsonObject.withCacheMarkerOnLastBlock(): JsonObject {
    val blocks = (this["content"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return this
    val last = blocks.last() as? JsonObject ?: return this
    if (last["type"]?.jsonPrimitive?.contentOrNull in setOf("thinking", "redacted_thinking")) return this
    val marked = JsonObject(last + ("cache_control" to buildJsonObject { put("type", "ephemeral") }))
    return JsonObject(this + ("content" to JsonArray(blocks.dropLast(1) + marked)))
}

private val ANTHROPIC_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

internal fun AiProviderEngine.buildAnthropicBaseMessages(
    activeNodes: List<MessageNode>,
    session: ChatSession,
    continuePrefix: String,
    foldHistoricalToolsToText: Boolean = false
): MutableList<Pair<String, MutableList<JsonObject>>> {
    val turnsWithAuthorsNote = injectAuthorsNoteIntoHistory(activeNodes, session)
    val coalescedBaseMessages = mutableListOf<Pair<String, MutableList<JsonObject>>>()
    val replayIds = ReplayToolCallIds()
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
            val turnText = turnTextLayout(text, toolCalls)
            val finalText = stripInlineErrorNotices(turnText.finalText)
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
                    beforeTool = turnText.beforeTool,
                    fallbackTurnSignature = thinkSig,
                    fallbackTurnThinking = thinkSummary
                )
                val totalCalls = toolCalls.size
                var globalCallIdx = 0
                for (batch in batches) {
                    val callIds = replayIds.assign(batch.calls)
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
                    if (batch.precedingText.isNotBlank()) {
                        assistantToolBlocks.add(buildJsonObject {
                            put("type", "text")
                            put("text", batch.precedingText.trimEnd())
                        })
                    }
                    for ((tc, callId) in batch.calls.zip(callIds)) {
                        val parsedArgs = runCatching { json.parseToJsonElement(tc.argumentsJson.ifBlank { "{}" }).jsonObject }
                            .getOrElse { JsonObject(emptyMap()) }
                        assistantToolBlocks.add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", callId)
                            put("name", tc.name)
                            put("input", parsedArgs)
                        })
                    }
                    appendRoleBlocks("assistant", assistantToolBlocks)

                    val userResultBlocks = batch.calls.zip(callIds).map { (tc, callId) ->
                        val wireOut = compactHistoricalToolResultForWire(
                            rawResult = tc.resultText,
                            toolName = tc.name,
                            isOlderAssistantTurn = isOlderAssistantTurn,
                            callIndexInTurn = globalCallIdx++,
                            totalCallsInTurn = totalCalls
                        )
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", callId)
                            put("content", wireOut)
                            if (tc.isError) put("is_error", true)
                        }
                    }
                    appendRoleBlocks("user", userResultBlocks)
                }

                if (finalText.isNotBlank()) {
                    appendRoleBlocks(
                        "assistant",
                        listOf(
                            buildJsonObject {
                                put("type", "text")
                                put("text", finalText)
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
                if (m != "application/pdf" && m !in ANTHROPIC_IMAGE_TYPES) {
                    userBlocks.add(buildJsonObject {
                        put("type", "text")
                        put("text", att.unsupportedAttachmentNote())
                    })
                    continue
                }
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

private fun AiProviderEngine.compactAnthropicExtraMessages(messages: List<JsonObject>): List<JsonObject> {
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
