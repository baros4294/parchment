package io.parchment.ai.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.harness.AgentToolRunContext
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.ToolApprovalMode
import kotlinx.coroutines.currentCoroutineContext
import io.parchment.ai.model.AuthHeaderMode
import io.parchment.ai.model.ApiUsageRecord
import io.parchment.ai.model.ProviderTokenUsage
import io.parchment.ai.model.saturatingTokenSum
import io.parchment.ai.model.CUSTOM_DIALECT_ANTHROPIC
import io.parchment.ai.model.CUSTOM_DIALECT_GEMINI
import io.parchment.ai.model.CUSTOM_DIALECT_OPENAI_CHAT
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.GoalTrackerState
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.TokenMetrics
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.model.WebSourceCitation
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.runtime.runActivityFlow
import io.parchment.ai.protocol.O200kTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import androidx.compose.runtime.Immutable
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
    data class UsageUpdated(val record: ApiUsageRecord, val metrics: TokenMetrics) : StreamUpdate()
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
        val rawResponse: String,
        val apiUsageRecords: List<ApiUsageRecord> = emptyList()
    ) : StreamUpdate()
    data class Error(val message: String, val rawDetails: String = "") : StreamUpdate()
}

internal const val MAX_INLINE_ATTACHMENT_BYTES = 50L * 1024L * 1024L
internal const val MAX_DOWNLOADED_IMAGE_BYTES = 64 * 1024 * 1024
internal const val MAX_DOWNLOADED_AUDIO_BYTES = 128 * 1024 * 1024
internal const val MAX_DOWNLOADED_VIDEO_BYTES = 512 * 1024 * 1024

internal fun oversizedAttachmentMessage(fileName: String, sizeBytes: Long): String =
    "\"$fileName\" is ${sizeBytes / (1024L * 1024L)} MB, over the ${MAX_INLINE_ATTACHMENT_BYTES / (1024L * 1024L)} MB limit for files sent with a request. Remove it or attach a smaller file."

@OptIn(ExperimentalEncodingApi::class)
object AiProviderEngine {

    /**
     * Long-horizon autonomous execution engine.
     * Uses a Repeated-Failure Circuit Breaker + Stop-Hook Work-Check Gate instead of a brittle 64-step limit,
     * allowing tasks to run productively for hours, days, or weeks while halting repeated identical errors.
     */
    internal const val MAX_SAFETY_HORIZON_STEPS = 10_000
    internal const val MAX_PRE_TOKEN_RETRIES = 10
    internal const val RETRY_WAIT_SECONDS = 60

/** Marker prefixing a carried-forward reasoning narrative from a different provider. */
internal const val REASONING_HANDOFF_MARKER = "[Prior reasoning summary]"

/** Upper bound on carried reasoning text per historical turn (keeps context lean). */
internal const val MAX_REASONING_HANDOFF_CHARS = 2000
    internal const val LEGACY_FALLBACK_PRIMARY_MAX_RETRIES = 2
    internal const val LEGACY_FALLBACK_PRIMARY_WAIT_SECONDS = 3
    private const val MAX_GOAL_NETWORK_RETRIES = 120
    private const val MAX_MID_STREAM_CONTINUATION_PASSES = 64
    internal val discoveredModelModalities = mutableMapOf<String, List<String>>()
    internal val discoveredModelVendors = mutableMapOf<String, String>()

    internal class StepRetriesExhaustedException(message: String, cause: Throwable) : RuntimeException(message, cause)

    /** The provider already accepted this job, so retrying the request could pay for it twice. */
    internal class AcceptedJobException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    internal suspend fun waitWithRetryCountdown(
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

    internal val json = Json {
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
        initialElapsedMs: Long = 0L,
        initialApiUsageRecords: List<ApiUsageRecord> = emptyList(),
        initialTokenMetrics: TokenMetrics = TokenMetrics(),
        usageOwnerSessionId: String = session.id,
        usageOwnerRunId: String = "",
        usagePurpose: String = "chat"
    ): Flow<StreamUpdate> = runActivityFlow(session.modelOverride.ifBlank { profile.defaultModel }) { activity ->
        val model = session.modelOverride.ifBlank { profile.defaultModel }.trim()
        if (model.isBlank()) {
            emit(
                StreamUpdate.Error(
                    message = "No Model ID is selected. Fetch models automatically from the endpoint or enter a Model ID manually in Providers & Proxy."
                )
            )
            return@runActivityFlow
        }
        val startTime = activity.elapsedMs
        var firstTokenTime = -1L

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
        val usageLedger = ProviderUsageLedger(profile, model, initialApiUsageRecords, initialTokenMetrics,
            usageOwnerSessionId, usageOwnerRunId.ifBlank { currentCoroutineContext()[AgentToolRunContext]?.broker?.runId ?: PlatformBridge.createRunId() }, usagePurpose)
        var latestUsageMetrics = usageLedger.metrics()
        val toolPolicy = AgentPermissionResolver.resolve(session, profile = profile)
        val transportProfile = profile.copy(
            useLegacyFallbackEndpoint = profile.protocol != ProviderProtocol.CUSTOM && profile.useLegacyFallbackEndpoint,
            enableWebSearch = profile.enableWebSearch && toolPolicy.webAccess && toolPolicy.allowsTool("web_search"),
            enableUrlContext = profile.enableUrlContext && toolPolicy.webAccess && toolPolicy.allowsTool("web_fetch"),
            enableCodeExecution = profile.enableCodeExecution && toolPolicy.allowsTool("code_execution") && toolPolicy.approvalMode == ToolApprovalMode.NEVER,
            enableImageGeneration = profile.enableImageGeneration && toolPolicy.allowsTool("image_generation"),
            enableVideoGeneration = profile.enableVideoGeneration && toolPolicy.allowsTool("video_generation"),
            allowShellExecution = false
        )
        var activeDecodeAccumulatedMs = 0L
        var currentBurstStartMs = -1L
        var currentBurstLastMs = -1L
        var lastRawReq = ""
        var lastRawResp = ""
        fun recordTokenActivity() {
            val now = activity.elapsedMs
            if (firstTokenTime < 0L) firstTokenTime = now
            if (currentBurstStartMs < 0L) {
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
            if (currentBurstStartMs >= 0L && currentBurstLastMs >= currentBurstStartMs) {
                activeDecodeAccumulatedMs += (currentBurstLastMs - currentBurstStartMs).coerceAtLeast(40L)
            }
            currentBurstStartMs = -1L
            currentBurstLastMs = -1L
        }

        fun recordRawWireBounded(req: String, resp: String) {
            if (req.isNotEmpty()) {
                lastRawReq = if (req.length > 4096) req.substring(0, 4096) else req
            }
            if (resp.isNotEmpty()) {
                lastRawResp = if (resp.length > 4096) resp.substring(0, 4096) else resp
            }
        }

        suspend fun beginUsageRequest(dialect: UsageDialect): String {
            val mediaInputs = buildSet {
                val fees = requestChargePricing(profile, model).perUnitUsd
                historyNodes.filter { it.includedInContext }.forEach { node ->
                    node.activeVariant.attachments.forEach { attachment ->
                        if (attachment.base64Data.isNotBlank() || attachment.localFilePath.isNotBlank()) {
                            when {
                                attachment.mimeType.startsWith("audio/", true) -> add("input_audio")
                                attachment.mimeType.startsWith("video/", true) -> add("input_video")
                                attachment.mimeType.startsWith("image/", true) && (fees["input_image_count"] ?: 0.0) > 0 -> add("input_image_count")
                            }
                        }
                    }
                }
            }
            val record = usageLedger.begin(dialect, mediaInputs)
            latestUsageMetrics = usageLedger.metrics()
            emit(StreamUpdate.UsageUpdated(record, latestUsageMetrics))
            return record.requestId
        }

        suspend fun recordRequestUsage(requestId: String?, responseId: String, dialect: UsageDialect, usage: ProviderTokenUsage) {
            if (responseId.isNotBlank()) lastInteractionId = responseId
            val record = usageLedger.update(requestId, responseId, dialect, usage) ?: return
            latestUsageMetrics = usageLedger.metrics()
            emit(StreamUpdate.UsageUpdated(record, latestUsageMetrics))
        }

        suspend fun beginMediaUsage(wireDialect: String, component: String) {
            val record = usageLedger.beginMedia(wireDialect, component)
            latestUsageMetrics = usageLedger.metrics()
            emit(StreamUpdate.UsageUpdated(record, latestUsageMetrics))
        }

        suspend fun recordGeneratedMedia(item: GeneratedMediaItem) {
            recordTokenActivity()
            usageLedger.observeChargeComponent("output_${item.kind.name.lowercase()}")?.let { record ->
                latestUsageMetrics = usageLedger.metrics()
                emit(StreamUpdate.UsageUpdated(record, latestUsageMetrics))
            }
            generatedMedia.add(item)
            emit(StreamUpdate.MediaGenerated(item))
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
                extractCitationsFromToolCall(enriched).forEach { recordRawCitationAndEmit(it) }
            }
        }
        val initialGoalObjective = if (toolPolicy.toolsEnabled) extractGoalObjectiveFromHistory(historyNodes) else ""
        val circuitBreaker = WorkspaceHarness.LoopCircuitBreaker(
            initialGoal = initialGoalTracker?.takeIf { toolPolicy.toolsEnabled } ?: if (initialGoalObjective.isNotBlank()) {
                GoalTrackerState(goalObjective = initialGoalObjective)
            } else null
        )
        circuitBreaker.goalState?.let { emit(StreamUpdate.GoalProgress(it)) }

        try {
            var activeBaseHistoryNodes = applyCrossProviderReasoningHandoff(
                nodes = historyNodes,
                currentProtocol = resolveWireProtocol(profile),
                chainingActive = profile.useStatefulChaining
            )
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
                    emptyAnswerNudgePass == 2 -> transportProfile.copy(thinkingEffort = "low")
                    emptyAnswerNudgePass >= 3 -> transportProfile.copy(thinkingEffort = "minimal")
                    else -> transportProfile
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
                    protocol = resolveWireProtocol(effectiveProfileForPass),
                    model = model,
                    passTag = "pass-$continuationPass-nudge-$emptyAnswerNudgePass",
                    forceVisibleThoughtSummary = emptyAnswerNudgePass >= 2
                )

                try {
                    when (resolveWireProtocol(effectiveProfileForPass)) {
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
                                        val approxOut = (contentBuilder.length / 4).coerceAtLeast(latestUsageMetrics.outputTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                                        val elapsedSec = ((activity.elapsedMs - startTime) / 1000L).toInt()
                                        PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onMedia = { item ->
                                        recordGeneratedMedia(item)
                                    },
                                    onToolCall = { tc ->
                                        upsertToolCallAndEmit(tc)
                                    },
                                    onRawWire = { req, resp ->
                                        recordRawWireBounded(req, resp)
                                    },
                                    onRequestStarted = ::beginUsageRequest,
                                    onUsage = ::recordRequestUsage,
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
                                        val approxOut = (contentBuilder.length / 4).coerceAtLeast(latestUsageMetrics.outputTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                                        val elapsedSec = ((activity.elapsedMs - startTime) / 1000L).toInt()
                                        PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onMedia = { item ->
                                        recordGeneratedMedia(item)
                                    },
                                    onToolCall = { tc ->
                                        upsertToolCallAndEmit(tc)
                                    },
                                    onRawWire = { req, resp ->
                                        recordRawWireBounded(req, resp)
                                    },
                                    onRequestStarted = ::beginUsageRequest,
                                    onUsage = ::recordRequestUsage,
                                    onCitation = { cit -> recordRawCitationAndEmit(cit) }
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                if (profile.protocol != ProviderProtocol.CUSTOM && shouldFallbackToLegacyProtocol(e, profile.useLegacyFallbackEndpoint)) {
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
                                        val approxOut = (contentBuilder.length / 4).coerceAtLeast(latestUsageMetrics.outputTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                                        val elapsedSec = ((activity.elapsedMs - startTime) / 1000L).toInt()
                                        PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onMedia = { item ->
                                        recordGeneratedMedia(item)
                                    },
                                    onToolCall = { tc ->
                                        upsertToolCallAndEmit(tc)
                                    },
                                    onRawWire = { req, resp ->
                                        recordRawWireBounded(req, resp)
                                    },
                                    onRequestStarted = ::beginUsageRequest,
                                    onUsage = ::recordRequestUsage,
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
                                        recordGeneratedMedia(item)
                                    },
                                    onContent = { delta ->
                                        if (delta.isNotEmpty()) recordTokenActivity()
                                        contentBuilder.append(delta)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onRawWire = { req, resp ->
                                        recordRawWireBounded(req, resp)
                                    },
                                    onRequestStarted = ::beginUsageRequest,
                                    onUsage = ::recordRequestUsage,
                                    onMediaRequestStarted = ::beginMediaUsage
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
                                        recordGeneratedMedia(item)
                                    },
                                    onContent = { delta ->
                                        if (delta.isNotEmpty()) recordTokenActivity()
                                        contentBuilder.append(delta)
                                        emit(StreamUpdate.ContentDelta(delta))
                                    },
                                    onRawWire = { req, resp ->
                                        recordRawWireBounded(req, resp)
                                    },
                                    onRequestStarted = { beginMediaUsage("openai_audio_speech", "output_audio") }
                                )
                            }

                            val customProfile = profile.protocol == ProviderProtocol.CUSTOM
                            val explicitChat = customProfile && profile.customDialect.trim().lowercase() == CUSTOM_DIALECT_OPENAI_CHAT
                            if (explicitChat) {
                                runLegacyOpenAiChatCompletions(effectiveHistoryNodes, effectiveContinuePrefix)
                            } else if (!customProfile && (modalityProfile.isDedicatedVideo || isExplicitVideoPath)) {
                                try {
                                    runUniversalMediaGeneration(forceVideo = true)
                                } catch (videoErr: Throwable) {
                                    if (videoErr is kotlinx.coroutines.CancellationException || videoErr is AcceptedJobException) throw videoErr
                                    if (generatedMedia.isEmpty() && contentBuilder.isEmpty() && profile.useLegacyFallbackEndpoint) {
                                        runLegacyOpenAiChatCompletions(effectiveHistoryNodes, effectiveContinuePrefix)
                                    } else {
                                        throw videoErr
                                    }
                                }
                            } else if (!customProfile && isDirectImagesEndpoint) {
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
                            } else if (!customProfile && isDirectAudioEndpoint) {
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
                                            val approxOut = (contentBuilder.length / 4).coerceAtLeast(latestUsageMetrics.outputTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                                            val elapsedSec = ((activity.elapsedMs - startTime) / 1000L).toInt()
                                            PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                            emit(StreamUpdate.ContentDelta(delta))
                                        },
                                        onMedia = { item ->
                                            recordGeneratedMedia(item)
                                        },
                                        onToolCall = { tc ->
                                            upsertToolCallAndEmit(tc)
                                        },
                                        onRawWire = { req, resp ->
                                            recordRawWireBounded(req, resp)
                                        },
                                        onRequestStarted = ::beginUsageRequest,
                                        onUsage = ::recordRequestUsage,
                                        onCitation = { cit -> recordRawCitationAndEmit(cit) }
                                    )
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    // A Custom profile declares its URL and dialect, so no failure may reroute it.
                                    if (profile.protocol == ProviderProtocol.CUSTOM) throw e
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
                                    val approxOut = (contentBuilder.length / 4).coerceAtLeast(latestUsageMetrics.outputTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                                    val elapsedSec = ((activity.elapsedMs - startTime) / 1000L).toInt()
                                    PlatformBridge.notifyLongDecodeProgress(model, approxOut, elapsedSec)
                                    emit(StreamUpdate.ContentDelta(delta))
                                },
                                onToolCall = { tc ->
                                    upsertToolCallAndEmit(tc)
                                },
                                onRawWire = { req, resp ->
                                    recordRawWireBounded(req, resp)
                                },
                                onRequestStarted = ::beginUsageRequest,
                                onUsage = ::recordRequestUsage,
                                onCitation = { cit -> recordRawCitationAndEmit(cit) }
                            )
                        }
                        ProviderProtocol.CUSTOM -> {
                            // Unresolved dialect must never silently guess a wire shape.
                            throw IllegalStateException(
                                "Custom Protocol has no wire dialect selected. Choose one in Provider settings → Wire Dialect."
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
            val endTime = activity.elapsedMs
            val totalDurationMs = (endTime - startTime + initialElapsedMs.coerceAtLeast(0L)).coerceAtLeast(1L)
            val ttftMs = if (firstTokenTime >= startTime) (firstTokenTime - startTime) else totalDurationMs
            val reportedUsage = usageLedger.metrics()
            val fallbackInTokens = if (usageLedger.hasReportedInput) {
                reportedUsage.inputTokens
            } else {
                val sysTok = O200kTokenizer.countTokens(buildCompiledSystemPrompt(session, circuitBreaker.goalState))
                val histTok = historyNodes.filter { it.includedInContext }.sumOf {
                    O200kTokenizer.countMessageVariantTokens(it.role, it.activeVariant, includeReasoningAndTools = false)
                }
                (sysTok.toLong() + histTok).coerceAtLeast(1L)
            }
            val rawMetrics = computeTurnTokenMetrics(
                reportedInputTokens = reportedUsage.inputTokens,
                reportedCachedTokens = reportedUsage.cachedInputTokens,
                reportedReasoningTokens = reportedUsage.reasoningTokens,
                reportedOutputTokens = reportedUsage.outputTokens,
                fallbackInputTokens = fallbackInTokens,
                contentText = contentBuilder.toString(),
                thinkingText = thinkingBuilder.toString(),
                executedTools = executedTools,
                totalDurationMs = totalDurationMs,
                ttftMs = ttftMs,
                activeDecodeDurationMs = activeDecodeAccumulatedMs,
                totalToolExecutionMs = activity.toolMs,
                hasReportedInput = usageLedger.hasReportedInput,
                hasReportedOutput = usageLedger.hasReportedOutput,
                hasReportedReasoning = usageLedger.hasReportedReasoning
            ).copy(cacheCreationInputTokens = reportedUsage.cacheCreationInputTokens,
                cacheCreationCostUnits = reportedUsage.cacheCreationCostUnits, reportedRequests = reportedUsage.reportedRequests,
                usageIsComplete = reportedUsage.usageIsComplete, costIsAvailable = reportedUsage.costIsAvailable,
                estimatedCostUsd = reportedUsage.estimatedCostUsd,
                outputIncludesUnreportedReasoning = reportedUsage.outputIncludesUnreportedReasoning,
                unpricedCostComponents = reportedUsage.unpricedCostComponents,
                reportedCostRequests = reportedUsage.reportedCostRequests)

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
                    metrics = rawMetrics,
                    rawRequest = lastRawReq,
                    rawResponse = lastRawResp,
                    apiUsageRecords = usageLedger.snapshots()
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            val cleanErr = extractCleanApiErrorSummary(t.message ?: t::class.simpleName ?: "Unknown network error")
            emit(StreamUpdate.Error(message = cleanErr, rawDetails = lastRawResp))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Computes accurate turn-level `TokenMetrics` and `tokensPerSec`:
     * - Numerator includes both `finalOutTokens` (prose + tool call arguments across all rounds) and `finalThinkTokens`.
     * - Denominator uses `activeDecodeDurationMs` (active token streaming bursts excluding TTFT wait and local tool execution time).
     */
    internal fun computeTurnTokenMetrics(
        reportedInputTokens: Long,
        reportedCachedTokens: Long,
        reportedReasoningTokens: Long,
        reportedOutputTokens: Long,
        fallbackInputTokens: Long,
        contentText: String,
        thinkingText: String,
        executedTools: List<ToolCallRecord>,
        totalDurationMs: Long,
        ttftMs: Long,
        activeDecodeDurationMs: Long,
        totalToolExecutionMs: Long,
        hasReportedInput: Boolean = reportedInputTokens > 0L,
        hasReportedOutput: Boolean = reportedOutputTokens > 0L,
        hasReportedReasoning: Boolean = reportedReasoningTokens > 0L
    ): TokenMetrics {
        val finalInTokens = if (hasReportedInput) reportedInputTokens.coerceAtLeast(0L) else fallbackInputTokens.coerceAtLeast(0L)
        val bpeThinkTokens = if (thinkingText.isNotEmpty()) {
            O200kTokenizer.countTokens(thinkingText).coerceAtLeast(1)
        } else 0
        val bpeToolCallTokens = executedTools.sumOf { tc ->
            O200kTokenizer.countTokens(tc.name).toLong() + O200kTokenizer.countTokens(tc.argumentsJson)
        }
        val bpeContentTokens = if (contentText.isNotEmpty()) {
            O200kTokenizer.countTokens(contentText).coerceAtLeast(1)
        } else 0

        val finalThinkTokens = if (hasReportedReasoning) reportedReasoningTokens.coerceAtLeast(0L)
            else if (hasReportedOutput) 0L else bpeThinkTokens.toLong()
        val finalOutTokens = if (hasReportedOutput) reportedOutputTokens.coerceAtLeast(0L)
            else saturatingTokenSum(bpeContentTokens.toLong(), bpeToolCallTokens)
        val totalGeneratedTokens = saturatingTokenSum(finalOutTokens, finalThinkTokens)

        val netLlmGenerationMs = (totalDurationMs - totalToolExecutionMs.coerceAtLeast(0L)).coerceAtLeast(250L)
        val minRealisticBurstMs = maxOf(500L, totalGeneratedTokens.coerceAtMost(Long.MAX_VALUE / 8L) * 8L)
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
            tokensPerSec = kotlin.math.floor(tokPerSec * 10.0) / 10.0
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
        maxOutputTokens: Int? = null,
        ownerSessionId: String = "",
        ownerRunId: String = "",
        purpose: String = "utility",
        onUsage: suspend (ApiUsageRecord, TokenMetrics) -> Unit = { _, _ -> }
    ): Result<String> = kotlinx.coroutines.withContext(Dispatchers.IO + AgentToolRunContext(null)) {
        runCatching {
            val cleanModel = model.trim().ifBlank { profile.defaultModel.trim() }
            require(cleanModel.isNotBlank()) { "No model selected for utility generation." }
            val tempSession = ChatSession(
                id = "util-temp",
                title = "Utility",
                createdAt = 0L,
                updatedAt = 0L,
                profileId = profile.id,
                modelOverride = cleanModel,
                permissionOverrides = io.parchment.ai.model.AgentPermissionOverrides(toolsEnabled = false)
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
            var completed = false
            streamGeneration(
                profile = fastProfile,
                session = tempSession,
                historyNodes = listOf(userNode),
                usageOwnerSessionId = ownerSessionId,
                usageOwnerRunId = ownerRunId,
                usagePurpose = purpose
            ).collect { upd ->
                when (upd) {
                    is StreamUpdate.ContentDelta -> sb.append(upd.delta)
                    is StreamUpdate.Completed -> { completed = true; sb.clear(); sb.append(upd.fullContent) }
                    is StreamUpdate.UsageUpdated -> onUsage(upd.record, upd.metrics)
                    is StreamUpdate.Error -> streamErr = upd.message
                    else -> {}
                }
            }
            val finalStr = sb.toString().trim().removeSurrounding("\"").removeSurrounding("'").trim()
            if (!streamErr.isNullOrBlank()) {
                error(streamErr)
            }
            require(completed && finalStr.isNotBlank()) { "Utility response ended without a complete result." }
            finalStr
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    /** Resolves the concrete wire dialect for a profile (Custom delegates to its explicit [ProviderProfile.customDialect]). */
    internal fun resolveWireProtocol(profile: ProviderProfile): ProviderProtocol {
        if (!profile.protocol.isCustomWire) return profile.protocol
        return when (profile.customDialect.trim().lowercase()) {
            "gemini_interactions", "gemini" -> ProviderProtocol.GEMINI_INTERACTIONS
            "anthropic_messages", "anthropic" -> ProviderProtocol.ANTHROPIC_MESSAGES
            "openai_responses", "responses" -> ProviderProtocol.OPENAI_RESPONSES
            "openai_chat", "chat" -> ProviderProtocol.OPENAI_RESPONSES
            else -> ProviderProtocol.CUSTOM
        }
    }

    /** True when the custom profile has no dialect chosen yet — Send must be blocked. */
    internal fun isCustomDialectUnresolved(profile: ProviderProfile): Boolean =
        profile.protocol.isCustomWire && resolveWireProtocol(profile) == ProviderProtocol.CUSTOM

    internal fun resolveSmartEndpointUrl(
        profile: ProviderProfile,
        defaultPathWithV1: String,
        defaultPathWithoutVersion: String = defaultPathWithV1
            .removePrefix("/v1beta")
            .removePrefix("/v1")
    ): String {
        val rawBase = profile.baseUrl.trim()
        val rawOverride = profile.endpointPathOverride.trim()

        // 1. Custom protocol / explicit tail-free mode: the URL is sent VERBATIM, never rebuilt.
        if (profile.protocol.isCustomWire) {
            if (rawOverride.startsWith("http://", ignoreCase = true) || rawOverride.startsWith("https://", ignoreCase = true)) {
                return rawOverride.trimEnd('#')
            }
            if (rawOverride.isNotEmpty() && rawOverride != "/" && rawOverride != "#") {
                val cleanOverride = "/" + rawOverride.trimEnd('#').trimStart('/')
                return if (rawBase.trimEnd('/').endsWith(cleanOverride, ignoreCase = true)) {
                    rawBase.trimEnd('#')
                } else {
                    rawBase.trimEnd('/') + cleanOverride
                }
            }
            return rawBase.trimEnd('#').trimEnd('/')
        }

        // 1b. Trailing `#` opt-out on managed protocols.
        if (rawBase.endsWith("#") || rawOverride == "/" || rawOverride == "#") {
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

    internal data class ResolvedBinaryAttachment(
        val fileName: String,
        val mimeType: String,
        val base64Data: String
    )

    internal data class ToolStepBatch(
        val thoughtSignature: String,
        val precedingThinking: String,
        val calls: List<ToolCallRecord>
    )

    internal fun buildUrlWithAuthQuery(profile: ProviderProfile, rawUrl: String): String {
        if (profile.authHeaderMode == AuthHeaderMode.QUERY_PARAM && profile.apiKey.isNotBlank()) {
            val sep = if (rawUrl.contains("?")) "&" else "?"
            return "$rawUrl${sep}key=${profile.apiKey.trim()}"
        }
        return rawUrl
    }

    internal fun HttpRequestBuilder.applyAuthAndCustomHeaders(profile: ProviderProfile) {
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
                        ProviderProtocol.CUSTOM -> {
                            when (profile.customDialect) {
                                CUSTOM_DIALECT_GEMINI -> {
                                    header("x-goog-api-key", key)
                                    if (key.startsWith("sk-")) header("Authorization", "Bearer $key")
                                }
                                CUSTOM_DIALECT_ANTHROPIC -> {
                                    header("x-api-key", key)
                                    header("anthropic-version", "2023-06-01")
                                }
                                else -> header("Authorization", "Bearer $key")
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

    internal fun mergeRawJsonExtraBody(base: JsonObject, rawExtraJson: String): JsonObject {
        val trimmed = rawExtraJson.trim()
        if (trimmed.isEmpty()) return base
        val extraObj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return base
        // Raw provider options cannot grant tools or replace the harness's execution authority.
        return deepMergeJsonObjects(base, JsonObject(extraObj.filterKeys { it !in setOf("tools", "tool_choice", "tool_config") }))
    }

    internal fun deepMergeJsonObjects(target: JsonObject, overrideObj: JsonObject): JsonObject {
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

    internal suspend fun readSseOrJsonFallback(
        channel: ByteReadChannel,
        streamFinished: () -> Boolean,
        onSseEvent: suspend (eventType: String, data: String) -> Unit,
        onPlainJsonFallback: suspend (fullJson: String) -> Unit
    ) {
        var currentEvent = ""
        val dataLines = mutableListOf<String>()
        val rawFallbackBuffer = StringBuilder()
        var sawSseLine = false
        var sawEvent = false
        var sawDone = false
        suspend fun deliver(eventType: String, data: String) {
            if (data == "[DONE]") sawDone = true else sawEvent = true
            onSseEvent(eventType, data)
        }

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
                        deliver(currentEvent, combined)
                    }
                    currentEvent = line.removePrefix("event:").trim()
                }
                line.startsWith("data:") -> {
                    sawSseLine = true
                    val dataVal = line.removePrefix("data:").trim()
                    if (dataVal == "[DONE]") {
                        if (dataLines.isNotEmpty()) {
                            deliver(currentEvent, dataLines.joinToString("\n"))
                            dataLines.clear()
                        }
                        deliver(currentEvent, "[DONE]")
                        currentEvent = ""
                    } else if (dataLines.isEmpty() && isCompleteJsonObject(dataVal)) {
                        // Fast path: complete standalone JSON object on a single data: line — emit immediately
                        // without waiting for a trailing blank line (prevents proxy buffering & adjacent-record drops!)
                        deliver(currentEvent, dataVal)
                        currentEvent = ""
                    } else {
                        dataLines.add(dataVal)
                    }
                }
                line.isBlank() -> {
                    if (dataLines.isNotEmpty()) {
                        val combinedData = dataLines.joinToString("\n")
                        dataLines.clear()
                        deliver(currentEvent, combinedData)
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
            deliver(currentEvent, dataLines.joinToString("\n"))
        } else if (!sawSseLine && rawFallbackBuffer.isNotBlank()) {
            onPlainJsonFallback(rawFallbackBuffer.toString().trim())
        }
        // A clean close is not a finished answer: proxies can cut a stream without a socket error.
        if (sawEvent && !sawDone && !streamFinished()) error("Stream ended before the provider finished its response.")
    }

    private fun isCompleteJsonObject(text: String): Boolean {
        if (!text.startsWith("{")) return false
        var depth = 0
        var inString = false
        var escaped = false
        for ((index, ch) in text.withIndex()) {
            when {
                escaped -> escaped = false
                inString -> if (ch == '\\') escaped = true else if (ch == '"') inString = false
                ch == '"' -> inString = true
                ch == '{' || ch == '[' -> depth++
                ch == '}' || ch == ']' -> if (--depth == 0) return index == text.lastIndex
            }
        }
        return false
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

}
