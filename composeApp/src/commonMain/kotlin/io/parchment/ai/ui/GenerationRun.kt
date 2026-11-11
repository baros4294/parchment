package io.parchment.ai.ui

import io.parchment.ai.harness.wasCutOff
import io.parchment.ai.harness.withInterruptedOutcome
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.key
import io.parchment.ai.model.GoalTrackerState
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.harness.AgentToolRunContext
import io.parchment.ai.network.appendSignatureChunkSafe
import io.parchment.ai.network.stripInlineErrorNotices
import io.parchment.ai.runtime.appRunLeases
import io.parchment.ai.harness.ToolApprovalBroker
import io.parchment.ai.harness.ToolAccessScope
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.asOverrides
import io.parchment.ai.model.unclaimedStepText
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.StreamUpdate
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.protocol.O200kTokenizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun GenerationHost.beginGeneration(
    sessionId: String,
    historyForCall: List<MessageNode>,
    targetAssistantNodeId: String,
    targetVariantId: String,
    continuePrefix: String = "",
    autoMuteNodeIdsOnFinish: Set<String> = emptySet()
) {
    val sessionSnap = appState.sessions.find { it.id == sessionId } ?: return
    if (sessionSnap.isCompactStub) {
        bannerNotification = "Chat history is still loading. Try again when it is ready."
        return
    }
    if (sessionSnap.messages.none { node -> node.id == targetAssistantNodeId && node.variants.any { it.id == targetVariantId } }) {
        bannerNotification = "The requested turn is no longer available."
        return
    }
    val assistantSnap = appState.assistants.find { it.id == sessionSnap.assistantId } ?: activeAssistant
    val profileSnap = appState.profiles.find { it.id == sessionSnap.profileId } ?: activeProfile

    // Merge Assistant-level system prompt, fragments, and nullable sampling overrides
    val mergedSystemPrompt = buildString {
        if (assistantSnap.systemPrompt.isNotBlank()) {
            appendLine(assistantSnap.systemPrompt.trim())
        }
        if (sessionSnap.systemPrompt.isNotBlank()) {
            if (isNotEmpty()) appendLine()
            appendLine(sessionSnap.systemPrompt.trim())
        }
    }.trim()

    val toolPolicy = AgentPermissionResolver.resolve(sessionSnap, assistantSnap, profileSnap)

    val mergedSessionForApi = sessionSnap.copy(
        systemPrompt = mergedSystemPrompt,
        authorsNote = sessionSnap.authorsNote.ifBlank { assistantSnap.authorsNote },
        activeCard = sessionSnap.activeCard ?: assistantSnap.activeCard,
        fragments = (appState.globalWorldBook + assistantSnap.fragments + sessionSnap.fragments).distinctBy { it.id },
        workspacePath = toolPolicy.workspacePath,
        workspaceMode = toolPolicy.workspaceMode,
        permissionOverrides = toolPolicy.asOverrides()
    )

    val effectiveHistory = if (assistantSnap.contextMessageLimit > 0 && historyForCall.size > assistantSnap.contextMessageLimit) {
        historyForCall.takeLast(assistantSnap.contextMessageLimit)
    } else {
        historyForCall
    }

    val effectiveProfileForApi = profileSnap.copy(
        temperature = assistantSnap.temperature ?: profileSnap.temperature,
        topP = assistantSnap.topP ?: profileSnap.topP,
        maxOutputTokens = assistantSnap.maxOutputTokens ?: profileSnap.maxOutputTokens,
        thinkingEffort = assistantSnap.thinkingEffort.ifBlank { profileSnap.thinkingEffort },
        enableWebSearch = toolPolicy.toolsEnabled && toolPolicy.webAccess && toolPolicy.allowsTool("web_search"),
        enableUrlContext = toolPolicy.toolsEnabled && toolPolicy.webAccess && toolPolicy.allowsTool("web_fetch") && profileSnap.enableUrlContext,
        allowShellExecution = false
    )

    val modelName = mergedSessionForApi.modelOverride.ifBlank {
        assistantSnap.defaultModelId.ifBlank {
            effectiveProfileForApi.defaultModel.ifBlank { appState.defaultModels.globalModelId }
        }
    }

    val existingTargetVariant = sessionSnap.messages
        .find { it.id == targetAssistantNodeId }
        ?.variants
        ?.find { it.id == targetVariantId }
    val cleanContinuePrefix = AiProviderEngine.stripInlineErrorNotices(continuePrefix)
    val initialThinking = existingTargetVariant?.thinkingSummary.orEmpty()
    val initialTrailingThinking = existingTargetVariant?.trailingThinking.orEmpty()
    val initialSignature = existingTargetVariant?.thinkingSignature.orEmpty()
    val initialTools = existingTargetVariant?.toolCalls.orEmpty()
    val initialMedia = existingTargetVariant?.mediaItems.orEmpty()
    val initialWebSources = existingTargetVariant?.webSources.orEmpty()
    val initialDurationMs = existingTargetVariant?.thinkingDurationMs ?: 0L
    val identity = AgentRunIdentity(
        PlatformBridge.createRunId(), sessionId, targetAssistantNodeId, targetVariantId,
        effectiveProfileForApi.id, modelName,
        if (effectiveProfileForApi.protocol == ProviderProtocol.CUSTOM) effectiveProfileForApi.customDialect
        else io.parchment.ai.model.mapProtocolToDialect(effectiveProfileForApi.protocol.name)
    )

    var owningJob: kotlinx.coroutines.Job? = null
    val runStartedWithGoal = existingTargetVariant?.goalTracker != null || historyForCall.any { it.activeVariant.content.startsWith("/goal", true) }
    val approvalBroker = ToolApprovalBroker(
        runId = identity.runId,
        chatId = sessionId,
        policySource = {
            val current = appState.sessions.find { it.id == sessionId }
            if (current == null || owningJob?.isActive == false) null else ToolAccessScope(
                AgentPermissionResolver.resolve(current, appState.assistants.find { it.id == current.assistantId }, appState.profiles.find { it.id == current.profileId }),
                goalModeActive = isGoalModeEnabled || runStartedWithGoal
            )
        },
        onStop = { processState.runs.requestStop(identity); owningJob?.cancel() }
    )
    fun updateOwnedStream(updater: (SessionStreamRuntime?) -> SessionStreamRuntime?) {
        processState.runs.update(identity) { current -> updater(current) ?: current }
    }
    val newJob = appRunLeases.launch(appProcessScope, modelName, AgentToolRunContext(approvalBroker),
        start = kotlinx.coroutines.CoroutineStart.LAZY) { activity ->
        var terminalPhase = AgentRunPhase.INTERRUPTED
        updateOwnedStream { it?.copy(phase = AgentRunPhase.RUNNING) }
        val approvalObserver = launch {
            activity.waitingForApproval.collect { waiting ->
                updateOwnedStream { current -> current?.copy(phase = when {
                    waiting -> AgentRunPhase.WAITING_APPROVAL
                    current.phase == AgentRunPhase.WAITING_APPROVAL -> AgentRunPhase.RUNNING
                    else -> current.phase
                }) }
            }
        }
        val contentWords = StreamWordCounter()
        val thinkingWords = StreamWordCounter()
        val networkContentTarget = StringBuilder(cleanContinuePrefix)
        val networkThinkingTarget = StringBuilder(initialThinking)
        val currentStepThinkingTarget = StringBuilder(initialTrailingThinking)
        val signatureSb = StringBuilder(initialSignature)
        var displayedContentLen = cleanContinuePrefix.length
        var displayedThinkingLen = initialThinking.length
        var displayedStepThinkingLen = initialTrailingThinking.length
        val accumulatedTools = initialTools.toMutableList()
        var latestGoalTracker: GoalTrackerState? = existingTargetVariant?.goalTracker
        var accumulatedUsageRecords = existingTargetVariant?.apiUsageRecords.orEmpty()
        var latestTokenMetrics = existingTargetVariant?.tokenMetrics ?: io.parchment.ai.model.TokenMetrics()
        val accumulatedMedia = initialMedia.toMutableList()
        var accumulatedWebSources = initialWebSources
        var localInteractionId = existingTargetVariant?.providerInteractionId.orEmpty()
        var rawReq = ""
        var rawResp = ""
        var networkDone = false
        val turnRequestStartMs = activity.elapsedMs
        var activeThinkingPhaseStartMs = -1L
        var accumulatedThinkingMs = initialDurationMs
        var thinkingDurationMs = initialDurationMs
        var firstLiveTokenMs = -1L
        var liveTokensPerSec = 0.0
        var lastStopwatchTickMs = 0L
        var lastDiskCheckpointMs = turnRequestStartMs
        fun pauseActiveThinkingStopwatch() {
            if (activeThinkingPhaseStartMs >= 0L) {
                val now = activity.elapsedMs
                accumulatedThinkingMs += (now - activeThinkingPhaseStartMs).coerceAtLeast(0L)
                thinkingDurationMs = accumulatedThinkingMs.coerceAtLeast(100L)
                activeThinkingPhaseStartMs = -1L
            }
        }
        fun patchVariantMemory(updater: (MessageVariant) -> MessageVariant) {
            updateActiveSessionInMemoryOnly(sessionId) { sess ->
                val msgs = sess.messages
                val lastIdx = msgs.lastIndex
                if (lastIdx >= 0 && msgs[lastIdx].id == targetAssistantNodeId) {
                    // Fast O(1) path for the active streaming turn at the bottom of the chat
                    val lastNode = msgs[lastIdx]
                    val updatedLast = lastNode.copy(
                        variants = lastNode.variants.map { v ->
                            if (v.id == targetVariantId) updater(v) else v
                        }
                    )
                    val nextList = ArrayList<MessageNode>(msgs.size)
                    for (i in 0 until lastIdx) nextList.add(msgs[i])
                    nextList.add(updatedLast)
                    sess.copy(messages = nextList)
                } else {
                    sess.copy(
                        messages = msgs.map { node ->
                            if (node.id != targetAssistantNodeId) node
                            else node.copy(
                                variants = node.variants.map { v ->
                                    if (v.id == targetVariantId) updater(v) else v
                                }
                            )
                        }
                    )
                }
            }
        }

        fun flushCheckpointToDisk(isFinal: Boolean, finalUpdater: ((MessageVariant) -> MessageVariant)? = null) {
            if (isFinal) pauseActiveThinkingStopwatch()
            val curContent = if (isFinal) networkContentTarget.toString() else networkContentTarget.substring(0, displayedContentLen.coerceAtMost(networkContentTarget.length))
            val curThink = if (isFinal) networkThinkingTarget.toString() else networkThinkingTarget.substring(0, displayedThinkingLen.coerceAtMost(networkThinkingTarget.length))
            val curStepThink = if (accumulatedTools.isEmpty()) "" else if (isFinal) currentStepThinkingTarget.toString() else currentStepThinkingTarget.substring(0, displayedStepThinkingLen.coerceAtMost(currentStepThinkingTarget.length))
            val curSig = signatureSb.toString()
            val curTools = if (isFinal) {
                accumulatedTools.map { tc -> if (tc.wasCutOff()) tc.withInterruptedOutcome() else tc }
            } else {
                accumulatedTools.toList()
            }
            val curGoal = latestGoalTracker
            val curMedia = accumulatedMedia.toList()
            val curWebSources = accumulatedWebSources
            updateSessionById(sessionId, immediate = true) { sess ->
                sess.copy(
                    messages = sess.messages.map { node ->
                        val shouldAutoMute = isFinal && terminalPhase == AgentRunPhase.COMPLETED && node.id in autoMuteNodeIdsOnFinish
                        if (node.id != targetAssistantNodeId) {
                            if (shouldAutoMute) node.copy(includedInContext = false) else node
                        } else {
                            node.copy(
                                includedInContext = if (shouldAutoMute) false else node.includedInContext,
                                variants = node.variants.map { v ->
                                    if (v.id != targetVariantId) v
                                    else {
                                        val base = v.copy(
                                            content = curContent,
                                            thinkingSummary = curThink,
                                            trailingThinking = curStepThink,
                                            thinkingSignature = curSig,
                                            thinkingDurationMs = thinkingDurationMs,
                                            toolCalls = curTools,
                                            goalTracker = curGoal ?: v.goalTracker,
                                            mediaItems = curMedia,
                                            webSources = curWebSources,
                                            pendingBackgroundInteractionId = if (isFinal) "" else localInteractionId,
                                            isStreaming = !isFinal
                                        )
                                        if (finalUpdater != null) finalUpdater(base) else base
                                    }
                                }
                            )
                        }
                    }
                )
            }
        }

        // Hardware VSync Letter-by-Letter Character Pacer (locks to 60Hz / 120Hz / 144Hz / 240Hz display clock via uiScope's MonotonicFrameClock,
        // using sub-pixel fractional accumulators so prose & thoughts glide onto the page letter-by-letter instead of jumping in word chunks!)
        val pacerJob = launch {
            var lastFrameNanos = 0L
            var thinkCarry = 0f
            var stepThinkCarry = 0f
            var contCarry = 0f

            fun computeLetterStep(diff: Int, dtMs: Long, carry: Float, done: Boolean): Pair<Int, Float> {
                if (diff <= 0) return 0 to 0f
                if (processState.frameClock.value == null) return diff to 0f
                val dtSec = (dtMs.coerceIn(2L, 32L)) / 1000f
                // Base letter-by-letter speed (~92 chars/sec -> ~0.76 chars per 120Hz frame, ~1.4 chars per 60Hz frame)
                // plus a gentle elastic catch-up only when buffer backlog grows beyond 28 chars
                val backlog = (diff - 28).coerceAtLeast(0)
                val catchUpPerSec = if (done) {
                    maxOf(220f, diff * 10f)
                } else {
                    backlog * 4.2f
                }
                val exactChars = carry + (92f + catchUpPerSec) * dtSec
                val rawStep = exactChars.toInt()
                return if (rawStep <= 0) {
                    0 to exactChars
                } else {
                    val maxPerFrame = when {
                        done -> maxOf(4, diff / 4)
                        diff <= 28 -> 1
                        diff <= 72 -> 2
                        diff <= 160 -> 4
                        else -> maxOf(6, diff / 14)
                    }
                    val clamped = rawStep.coerceAtMost(maxPerFrame).coerceAtMost(diff)
                    clamped to (exactChars - clamped).coerceIn(0f, 0.99f)
                }
            }

            var cachedVisibleCont = networkContentTarget.substring(0, displayedContentLen.coerceAtMost(networkContentTarget.length))
            var cachedVisibleContLen = displayedContentLen
            var lastContentCopyMs = 0L
            var cachedVisibleThink = networkThinkingTarget.substring(0, displayedThinkingLen.coerceAtMost(networkThinkingTarget.length))
            var cachedVisibleThinkLen = displayedThinkingLen
            var cachedVisibleStepThink = ""
            var cachedVisibleStepThinkLen = -1
            var cachedSig = signatureSb.toString()
            var cachedSigLen = signatureSb.length
            var cachedThinkWords = 0
            var lastThinkWordScanIdx = 0
            var lastThinkCharWasSpace = true

            // Low-memory devices publish every other frame (about 30 a second): each publish redraws the chat.
            val framesPerPublish = if (PlatformBridge.isLowMemoryDevice) 2 else 1
            while (!networkDone || displayedContentLen < networkContentTarget.length || displayedThinkingLen < networkThinkingTarget.length) {
                var frameNanos = processState.nextFrameNanos()
                for (skipped in 1 until framesPerPublish) {
                    if (frameNanos == null) break
                    frameNanos = processState.nextFrameNanos() ?: frameNanos
                }

                val dtMs = if (frameNanos != null) {
                    val delta = if (lastFrameNanos > 0L) ((frameNanos - lastFrameNanos) / 1_000_000L).coerceIn(2L, 32L) else 8L
                    lastFrameNanos = frameNanos
                    delta
                } else {
                    delay(if (processState.frameClock.value == null && !networkDone) 100L else 8L)
                    lastFrameNanos = 0L
                    8L
                }
                var changed = false
                val now = activity.elapsedMs
                if (firstLiveTokenMs >= 0L && now - lastStopwatchTickMs >= 100L) {
                    val netGenerationMs = ((now - turnRequestStartMs) - activity.toolMs).coerceAtLeast(500L)
                    val approxToolChars = accumulatedTools.sumOf { it.name.length + it.argumentsJson.length }
                    val approxGeneratedTok = ((networkContentTarget.length + networkThinkingTarget.length + approxToolChars) / 4).coerceAtLeast(1)
                    val rawLiveRate = (approxGeneratedTok * 1000.0) / netGenerationMs.toDouble()
                    val newLiveRate = ((rawLiveRate * 10.0).toInt()) / 10.0
                    if (newLiveRate != liveTokensPerSec) {
                        liveTokensPerSec = newLiveRate
                        changed = true
                    }
                }
                if (activeThinkingPhaseStartMs >= 0L && now - lastStopwatchTickMs >= 100L) {
                    lastStopwatchTickMs = now
                    thinkingDurationMs = (accumulatedThinkingMs + (now - activeThinkingPhaseStartMs)).coerceAtLeast(100L)
                    changed = true
                    val hasRunningToolNow = accumulatedTools.any { it.isRunning }
                    if (!hasRunningToolNow) {
                        val curThinkTargetLen = networkThinkingTarget.length
                        if (curThinkTargetLen < lastThinkWordScanIdx) {
                            cachedThinkWords = 0
                            lastThinkWordScanIdx = 0
                            lastThinkCharWasSpace = true
                        }
                        while (lastThinkWordScanIdx < curThinkTargetLen) {
                            val c = networkThinkingTarget[lastThinkWordScanIdx]
                            val isSp = c == ' ' || c == '\n' || c == '\r' || c == '\t'
                            if (!isSp && lastThinkCharWasSpace) cachedThinkWords++
                            lastThinkCharWasSpace = isSp
                            lastThinkWordScanIdx++
                        }
                        val thinkWords = cachedThinkWords
                        val toolSuffix = if (accumulatedTools.isNotEmpty()) " • ${accumulatedTools.size} tools" else ""
                        updateOwnedStream { cur ->
                            if (cur != null && cur.isThinkingPhase && !cur.statusText.startsWith("Auto-retrying") && !cur.statusText.startsWith("Context ")) {
                                cur.copy(
                                    statusText = "Thinking ($modelName • $thinkWords words$toolSuffix • ${formatDurationFromMs(thinkingDurationMs)})…"
                                )
                            } else cur
                        }
                    }
                } else if (now - lastStopwatchTickMs >= 100L) {
                    lastStopwatchTickMs = now
                }
                val targetThinkLen = networkThinkingTarget.length
                if (displayedThinkingLen < targetThinkLen) {
                    val (step, nextCarry) = computeLetterStep(targetThinkLen - displayedThinkingLen, dtMs, thinkCarry, networkDone)
                    thinkCarry = nextCarry
                    if (step > 0) {
                        displayedThinkingLen = (displayedThinkingLen + step).coerceAtMost(targetThinkLen)
                        changed = true
                    }
                } else if (displayedThinkingLen > targetThinkLen) {
                    displayedThinkingLen = targetThinkLen
                    thinkCarry = 0f
                    changed = true
                } else {
                    thinkCarry = 0f
                }
                val targetStepThinkLen = currentStepThinkingTarget.length
                if (displayedStepThinkingLen < targetStepThinkLen) {
                    val (step, nextCarry) = computeLetterStep(targetStepThinkLen - displayedStepThinkingLen, dtMs, stepThinkCarry, networkDone)
                    stepThinkCarry = nextCarry
                    if (step > 0) {
                        displayedStepThinkingLen = (displayedStepThinkingLen + step).coerceAtMost(targetStepThinkLen)
                        changed = true
                    }
                } else if (displayedStepThinkingLen > targetStepThinkLen) {
                    displayedStepThinkingLen = targetStepThinkLen
                    stepThinkCarry = 0f
                    changed = true
                } else {
                    stepThinkCarry = 0f
                }
                val targetContLen = networkContentTarget.length
                if (displayedContentLen < targetContLen) {
                    val (step, nextCarry) = computeLetterStep(targetContLen - displayedContentLen, dtMs, contCarry, networkDone)
                    contCarry = nextCarry
                    if (step > 0) {
                        displayedContentLen = (displayedContentLen + step).coerceAtMost(targetContLen)
                        changed = true
                    }
                } else if (displayedContentLen > targetContLen) {
                    displayedContentLen = targetContLen
                    contCarry = 0f
                    changed = true
                } else {
                    contCarry = 0f
                }
                val contentBehind = displayedContentLen != cachedVisibleContLen
                val holdLongText = contentBehind && displayedThinkingLen == cachedVisibleThinkLen &&
                    now - lastContentCopyMs < visibleTextCopyIntervalMs(displayedContentLen)
                if ((changed || contentBehind) && !holdLongText) {
                    if (contentBehind) {
                        cachedVisibleCont = networkContentTarget.substring(0, displayedContentLen.coerceAtMost(networkContentTarget.length))
                        cachedVisibleContLen = displayedContentLen
                        lastContentCopyMs = now
                    }
                    if (displayedThinkingLen != cachedVisibleThinkLen) {
                        cachedVisibleThink = networkThinkingTarget.substring(0, displayedThinkingLen.coerceAtMost(networkThinkingTarget.length))
                        cachedVisibleThinkLen = displayedThinkingLen
                    }
                    val effStepLen = if (accumulatedTools.isEmpty()) 0 else displayedStepThinkingLen.coerceAtMost(currentStepThinkingTarget.length)
                    if (effStepLen != cachedVisibleStepThinkLen) {
                        cachedVisibleStepThink = if (effStepLen == 0) "" else currentStepThinkingTarget.substring(0, effStepLen)
                        cachedVisibleStepThinkLen = effStepLen
                    }
                    if (signatureSb.length != cachedSigLen) {
                        cachedSig = signatureSb.toString()
                        cachedSigLen = signatureSb.length
                    }
                    patchVariantMemory { v ->
                        v.copy(
                            content = cachedVisibleCont,
                            thinkingSummary = cachedVisibleThink,
                            trailingThinking = cachedVisibleStepThink,
                            thinkingSignature = cachedSig,
                            thinkingDurationMs = thinkingDurationMs,
                            tokenMetrics = if (liveTokensPerSec > 0.0) {
                                v.tokenMetrics.copy(tokensPerSec = liveTokensPerSec)
                            } else v.tokenMetrics,
                            isStreaming = true
                        )
                    }
                }
                // Atomic background disk checkpoint every 3.5 seconds so switching apps NEVER loses progress without disk/JSON thrashing
                if (now - lastDiskCheckpointMs >= 3500L) {
                    lastDiskCheckpointMs = now
                    flushCheckpointToDisk(isFinal = false)
                }
            }
        }

        try {
            var finalSessionForCall = mergedSessionForApi
            var finalHistoryForCall = effectiveHistory
            val effectiveCtxLimit = (effectiveProfileForApi.customModelSpecs[modelName]?.contextWindowTokens
                ?: effectiveProfileForApi.contextWindowTokens)
            val autoCompactThresholdTokens = (effectiveCtxLimit * 85) / 100
            var minTokensForNextAutoCompact = autoCompactThresholdTokens
            var lastMidStreamCompactCheckMs = activity.elapsedMs

            class MidStreamAutoCompactSignal(val usagePct: Long) : Exception("Auto-compact triggered at $usagePct%")

            fun computeBaseContextTokens(): Int {
                return O200kTokenizer.computeContextWindowBreakdown(
                    session = finalSessionForCall.copy(messages = finalHistoryForCall),
                    assistant = assistantSnap,
                    profile = effectiveProfileForApi,
                    draftPrompt = "",
                    draftPrefill = "",
                    pendingAttachments = emptyList(),
                    goalModeActive = latestGoalTracker != null,
                    globalWorldBook = appState.globalWorldBook
                ).totalUsedTokens
            }

            var cachedBaseTokens = computeBaseContextTokens()

            fun computeLiveTurnAndContextTokens(forceExact: Boolean = false): Int {
                if (!forceExact) {
                    // Fast O(1) upper-bound check (~3 chars/token) so 1.5s mid-stream checks skip regex tokenization when well below threshold
                    val approxToolsChars = accumulatedTools.sumOf { 24 + it.name.length + it.argumentsJson.length + it.resultText.length }
                    val approxUpperBound = cachedBaseTokens + ((networkContentTarget.length + networkThinkingTarget.length + approxToolsChars) / 3)
                    if (approxUpperBound < (minTokensForNextAutoCompact * 85) / 100) {
                        return approxUpperBound
                    }
                }
                val liveAssistantTokens = O200kTokenizer.countTokens(networkContentTarget.toString()) +
                    O200kTokenizer.countTokens(networkThinkingTarget.toString()) +
                    accumulatedTools.sumOf { tc ->
                        6 + O200kTokenizer.countTokens(tc.name) +
                            O200kTokenizer.countTokens(tc.argumentsJson) +
                            O200kTokenizer.countTokens(tc.resultText)
                    }
                return cachedBaseTokens + liveAssistantTokens
            }

            suspend fun executeAutoCompactionInPlace(usagePct: Long, isMidStream: Boolean): Boolean {
                val plan = O200kTokenizer.prepareCompactionPlan(
                    existingStorySummary = finalSessionForCall.storySummary,
                    historyNodes = finalHistoryForCall,
                    keepRecentTurnsCount = 2,
                    liveTargetTools = accumulatedTools.toList(),
                    liveTargetThinking = networkThinkingTarget.toString()
                )
                if (!plan.didCompactAnything) return false

                val statusPrefix = if (isMidStream) {
                    "Conversation $usagePct% full, pausing to shorten it…"
                } else {
                    "Conversation $usagePct% full, shortening older messages…"
                }
                updateOwnedStream { cur ->
                    cur?.copy(isThinkingPhase = false, statusText = statusPrefix, phase = AgentRunPhase.COMPACTING)
                }

                val dm = appState.defaultModels
                val sumProf = if (!dm.summaryFollowGlobal && dm.summaryProfileId.isNotBlank()) {
                    appState.profiles.find { it.id == dm.summaryProfileId } ?: effectiveProfileForApi
                } else effectiveProfileForApi
                val sumModel = if (!dm.summaryFollowGlobal && dm.summaryModelId.isNotBlank()) {
                    dm.summaryModelId
                } else modelName

                val sumPrompt = "Update the comprehensive bulleted continuity summary with all key events, facts, files/tools inspected, decisions, and character/project states from the context below. Do not truncate or omit important details:\n\n${plan.transcriptToSummarize}"
                val generatedSummary = withTimeoutOrNull(180_000L) {
                    generateTrackedUtility(sessionId, "compaction", sumProf, sumModel, sumPrompt, maxOutputTokens = null).getOrNull()
                }?.takeIf { it.isNotBlank() } ?: plan.fallbackSummary.ifBlank { finalSessionForCall.storySummary }

                finalSessionForCall = finalSessionForCall.copy(storySummary = generatedSummary)
                finalHistoryForCall = plan.compactedHistoryNodes
                accumulatedTools.clear()
                accumulatedTools.addAll(plan.compactedLiveTools)
                if (plan.compactedLiveThinking != networkThinkingTarget.toString()) {
                    networkThinkingTarget.clear()
                    thinkingWords.reset()
                    networkThinkingTarget.append(plan.compactedLiveThinking)
                    displayedThinkingLen = displayedThinkingLen.coerceAtMost(networkThinkingTarget.length)
                }
                currentStepThinkingTarget.clear()
                displayedStepThinkingLen = 0
                localInteractionId = ""

                val compactedHistoryById = plan.compactedHistoryNodes.associateBy { it.id }
                val toolsSnap = accumulatedTools.toList()
                val thinkSnap = networkThinkingTarget.toString()
                updateSessionById(sessionId, immediate = true) { sess ->
                    sess.copy(
                        storySummary = generatedSummary,
                        messages = sess.messages.map { n ->
                            when {
                                n.id == targetAssistantNodeId -> n.copy(
                                    variants = n.variants.map { v ->
                                        if (v.id == targetVariantId) {
                                            v.copy(
                                                toolCalls = toolsSnap,
                                                thinkingSummary = thinkSnap,
                                                trailingThinking = "",
                                                providerInteractionId = "",
                                                pendingBackgroundInteractionId = ""
                                            )
                                        } else v
                                    }
                                )
                                compactedHistoryById.containsKey(n.id) -> compactedHistoryById[n.id]!!
                                n.id in plan.mutedNodeIds -> n.copy(includedInContext = false)
                                else -> n
                            }
                        }
                    )
                }
                cachedBaseTokens = computeBaseContextTokens()
                val newTokens = computeLiveTurnAndContextTokens()
                val newPct = if (effectiveCtxLimit > 0) ((newTokens.toLong() * 100L) / effectiveCtxLimit.toLong()) else 0L
                minTokensForNextAutoCompact = maxOf(
                    autoCompactThresholdTokens,
                    newTokens + maxOf(1000, effectiveCtxLimit / 15)
                )
                updateOwnedStream { cur ->
                    cur?.copy(
                        statusText = "Conversation shortened ($usagePct% → $newPct%), continuing ($modelName)…",
                        interactionId = "",
                        phase = AgentRunPhase.RUNNING
                    )
                }
                return true
            }

            fun checkMidStreamAutoCompactAndThrowIfNeeded() {
                if (!appState.preferences.autoCompactWhenNearContextLimit || effectiveCtxLimit <= 0) return
                if (accumulatedTools.any { it.isRunning }) return
                val liveTokens = computeLiveTurnAndContextTokens()
                if (liveTokens >= minTokensForNextAutoCompact &&
                    O200kTokenizer.hasCompactableContext(
                        historyNodes = finalHistoryForCall,
                        keepRecentTurnsCount = 2,
                        liveTargetTools = accumulatedTools,
                        liveTargetThinking = networkThinkingTarget.toString()
                    )
                ) {
                    val usagePct = ((liveTokens.toLong() * 100L) / effectiveCtxLimit.toLong()).coerceAtMost(999L)
                    throw MidStreamAutoCompactSignal(usagePct)
                }
            }

            // Layer 1: Proactive Pre-Send Auto-Compaction when active context is already >= 85%
            val initialTokens = computeLiveTurnAndContextTokens()
            if (appState.preferences.autoCompactWhenNearContextLimit &&
                effectiveCtxLimit > 0 &&
                initialTokens >= minTokensForNextAutoCompact &&
                O200kTokenizer.hasCompactableContext(
                    historyNodes = finalHistoryForCall,
                    keepRecentTurnsCount = 2,
                    liveTargetTools = accumulatedTools,
                    liveTargetThinking = networkThinkingTarget.toString()
                )
            ) {
                val usagePct = ((initialTokens.toLong() * 100L) / effectiveCtxLimit.toLong()).coerceAtMost(999L)
                executeAutoCompactionInPlace(usagePct = usagePct, isMidStream = false)
            }

            // Layer 2: Real-Time Stream Loop with Mid-Stream >=85% Auto-Compaction & Instant Auto-Resume
            while (true) {
                try {
                    AiProviderEngine.streamGeneration(
                        profile = effectiveProfileForApi,
                        session = finalSessionForCall.withProfileName(appState.preferences),
                        historyNodes = finalHistoryForCall,
                        continueFromAssistantPrefix = AiProviderEngine.stripInlineErrorNotices(networkContentTarget.toString()),
                        initialThinking = networkThinkingTarget.toString(),
                        initialSignature = signatureSb.toString(),
                        resumeMediaTask = existingTargetVariant?.pendingBackgroundInteractionId.orEmpty(),
                        spendLimitUsd = appState.preferences.chatSpendLimitUsd,
                        spendBeforeRunUsd = if (appState.preferences.chatSpendLimitUsd > 0.0) sessionSpendSummary(finalSessionForCall, effectiveProfileForApi).towardLimitUsd else 0.0,
                        initialToolCalls = accumulatedTools.toList(),
                        initialMediaItems = accumulatedMedia.toList(),
                        initialWebSources = accumulatedWebSources,
                        initialGoalTracker = latestGoalTracker,
                        initialElapsedMs = (activity.elapsedMs - turnRequestStartMs).coerceAtLeast(0L),
                        initialApiUsageRecords = accumulatedUsageRecords,
                        initialTokenMetrics = latestTokenMetrics
                    ).collect { update ->
                        when (update) {
                            is StreamUpdate.Status -> {
                                if (update.interactionId.isNotBlank()) {
                                    localInteractionId = update.interactionId
                                    flushCheckpointToDisk(isFinal = false)
                                }
                                updateOwnedStream { cur ->
                                    cur?.copy(statusText = update.message, interactionId = localInteractionId)
                                }
                            }
                            is StreamUpdate.ThinkingDelta -> {
                                val now = activity.elapsedMs
                                if (firstLiveTokenMs < 0L) firstLiveTokenMs = now
                                if (activeThinkingPhaseStartMs < 0L) activeThinkingPhaseStartMs = now
                                thinkingDurationMs = (accumulatedThinkingMs + (now - activeThinkingPhaseStartMs)).coerceAtLeast(100L)
                                networkThinkingTarget.append(update.delta)
                                currentStepThinkingTarget.append(update.delta)
                                AiProviderEngine.appendSignatureChunkSafe(signatureSb, update.signatureDelta)
                                val thinkWords = thinkingWords.count(networkThinkingTarget)
                                val toolSuffix = if (accumulatedTools.isNotEmpty()) " • ${accumulatedTools.size} tools" else ""
                                updateOwnedStream { cur ->
                                    cur?.copy(
                                        isThinkingPhase = true,
                                        statusText = "Thinking ($modelName • $thinkWords words$toolSuffix • ${formatDurationFromMs(thinkingDurationMs)})…"
                                    )
                                }
                                if (now - lastMidStreamCompactCheckMs >= 1500L) {
                                    lastMidStreamCompactCheckMs = now
                                    checkMidStreamAutoCompactAndThrowIfNeeded()
                                }
                            }
                            is StreamUpdate.ContentDelta -> {
                                val now = activity.elapsedMs
                                if (firstLiveTokenMs < 0L) firstLiveTokenMs = now
                                pauseActiveThinkingStopwatch()
                                networkContentTarget.append(update.delta)
                                val wordCount = contentWords.count(networkContentTarget)
                                updateOwnedStream { cur ->
                                    cur?.copy(
                                        isThinkingPhase = false,
                                        statusText = "Writing ($modelName • +$wordCount words)…"
                                    )
                                }
                                if (now - lastMidStreamCompactCheckMs >= 1500L) {
                                    lastMidStreamCompactCheckMs = now
                                    checkMidStreamAutoCompactAndThrowIfNeeded()
                                }
                            }
                            is StreamUpdate.ToolCallEvent -> {
                                val now = activity.elapsedMs
                                // Keep the Reasoning + Tool stopwatch ticking continuously across tool calls!
                                if (activeThinkingPhaseStartMs < 0L) activeThinkingPhaseStartMs = now
                                thinkingDurationMs = (accumulatedThinkingMs + (now - activeThinkingPhaseStartMs)).coerceAtLeast(100L)
                                val stepThinkFallback = currentStepThinkingTarget.toString().trim()
                                val stepSigFallback = signatureSb.toString().trim()
                                if (stepThinkFallback.isNotEmpty()) {
                                    currentStepThinkingTarget.clear()
                                    displayedStepThinkingLen = 0
                                }
                                if (stepSigFallback.isNotEmpty()) {
                                    signatureSb.clear()
                                }
                                val existingIdx = accumulatedTools.indexOfFirst {
                                    it.id == update.toolCall.id && it.id.isNotBlank()
                                }
                                val enrichedTool = if (existingIdx >= 0) {
                                    val prev = accumulatedTools[existingIdx]
                                    update.toolCall.copy(
                                        precedingThinking = update.toolCall.precedingThinking.ifBlank { prev.precedingThinking },
                                        precedingText = prev.precedingText,
                                        thoughtSignature = update.toolCall.thoughtSignature.ifBlank { prev.thoughtSignature }
                                    )
                                } else {
                                    update.toolCall.copy(
                                        precedingThinking = update.toolCall.precedingThinking.ifBlank { stepThinkFallback },
                                        precedingText = unclaimedStepText(networkContentTarget, accumulatedTools),
                                        thoughtSignature = update.toolCall.thoughtSignature.ifBlank { stepSigFallback }
                                    )
                                }
                                if (existingIdx >= 0) {
                                    accumulatedTools[existingIdx] = enrichedTool
                                } else {
                                    accumulatedTools.add(enrichedTool)
                                }
                                val toolsSnap = accumulatedTools.toList()
                                patchVariantMemory { v ->
                                    v.copy(
                                        toolCalls = toolsSnap,
                                        trailingThinking = "",
                                        thinkingDurationMs = thinkingDurationMs
                                    )
                                }
                                if (!enrichedTool.isRunning) {
                                    updateOwnedStream { cur ->
                                        cur?.copy(
                                            isThinkingPhase = true,
                                            statusText = "Executed tool: ${enrichedTool.name} (${formatDurationFromMs(thinkingDurationMs)})…"
                                        )
                                    }
                                    flushCheckpointToDisk(isFinal = false)
                                    checkMidStreamAutoCompactAndThrowIfNeeded()
                                } else {
                                    updateOwnedStream { cur ->
                                        cur?.copy(isThinkingPhase = true)
                                    }
                                    // Saved before it runs, so a crash mid-change leaves a record instead of a blank.
                                    if (!WorkspaceHarness.isParallelSafeReadOnlyTool(enrichedTool.name)) flushCheckpointToDisk(isFinal = false)
                                }
                            }
                            is StreamUpdate.GoalProgress -> {
                                latestGoalTracker = update.goalTracker
                                val done = update.goalTracker.completedCount
                                val total = update.goalTracker.todos.size
                                val goalMsg = if (total > 0) {
                                    "Goal Progress ($done/$total steps done)…"
                                } else {
                                    "Planning Goal: ${update.goalTracker.goalObjective.take(48)}…"
                                }
                                updateOwnedStream { cur -> cur?.copy(statusText = goalMsg) }
                                patchVariantMemory { v -> v.copy(goalTracker = update.goalTracker) }
                                flushCheckpointToDisk(isFinal = false)
                            }
                            is StreamUpdate.MediaGenerated -> {
                                val relocatedItem = if (update.item.localFilePath.isNotBlank()) {
                                    update.item.copy(localFilePath = PlatformBridge.relocateLegacyMediaToChatFolder(update.item.localFilePath, sessionId))
                                } else update.item
                                accumulatedMedia.add(relocatedItem)
                                val mediaSnap = accumulatedMedia.toList()
                                patchVariantMemory { v -> v.copy(mediaItems = mediaSnap) }
                                updateOwnedStream { cur ->
                                    cur?.copy(statusText = "Generated ${relocatedItem.kind.name.lowercase()} (${accumulatedMedia.size})")
                                }
                            }
                            is StreamUpdate.WebSourcesUpdated -> {
                                accumulatedWebSources = update.sources
                                patchVariantMemory { v -> v.copy(webSources = update.sources) }
                            }
                            is StreamUpdate.RawWireSnapshot -> {
                                if (update.requestJson.isNotEmpty()) rawReq = update.requestJson.take(4096)
                                if (update.latestResponseChunk.isNotEmpty()) rawResp = update.latestResponseChunk.take(4096)
                            }
                            is StreamUpdate.UsageUpdated -> {
                                updateOwnedStream { it?.copy(actualWireDialect = update.record.wireDialect) }
                                val index = accumulatedUsageRecords.indexOfFirst { it.requestId == update.record.requestId }
                                accumulatedUsageRecords = if (index < 0) accumulatedUsageRecords + update.record else {
                                    accumulatedUsageRecords.toMutableList().also { it[index] = update.record }
                                }
                                latestTokenMetrics = update.metrics.copy(durationMs = latestTokenMetrics.durationMs,
                                    ttftMs = latestTokenMetrics.ttftMs, tokensPerSec = latestTokenMetrics.tokensPerSec)
                                patchVariantMemory { v -> v.copy(apiUsageRecords = accumulatedUsageRecords, tokenMetrics = latestTokenMetrics) }
                            }
                            is StreamUpdate.Completed -> {
                                terminalPhase = if ((update.goalTracker ?: latestGoalTracker)?.let {
                                    it.goalObjective.isNotBlank() && !it.isCompleted
                                } == true) AgentRunPhase.INTERRUPTED else AgentRunPhase.COMPLETED
                                accumulatedUsageRecords = update.apiUsageRecords
                                latestTokenMetrics = update.metrics
                                if (update.fullContent.length > networkContentTarget.length) {
                                    networkContentTarget.clear()
                                    contentWords.reset()
                                    networkContentTarget.append(update.fullContent)
                                }
                                if (update.fullThinking.length > networkThinkingTarget.length) {
                                    networkThinkingTarget.clear()
                                    thinkingWords.reset()
                                    networkThinkingTarget.append(update.fullThinking)
                                }
                                if (update.goalTracker != null) {
                                    latestGoalTracker = update.goalTracker
                                }
                                networkDone = true
                                pacerJob.join()
                                flushCheckpointToDisk(isFinal = true) { v ->
                                    v.copy(
                                        content = update.fullContent,
                                        thinkingSummary = update.fullThinking,
                                        trailingThinking = update.trailingThinking,
                                        thinkingSignature = update.thinkingSignature,
                                        thinkingDurationMs = thinkingDurationMs,
                                        toolCalls = update.toolCalls,
                                        goalTracker = update.goalTracker ?: latestGoalTracker,
                                        mediaItems = update.mediaItems,
                                        webSources = update.webSources.ifEmpty { accumulatedWebSources },
                                        providerInteractionId = update.providerInteractionId,
                                        pendingBackgroundInteractionId = "",
                                        isStreaming = false,
                                        providerProtocol = AiProviderEngine.resolveWireProtocol(effectiveProfileForApi),
                                        modelUsed = modelName,
                                        tokenMetrics = update.metrics,
                                        apiUsageRecords = update.apiUsageRecords,
                                        rawRequestPreview = update.rawRequest.take(4096),
                                        rawResponsePreview = update.rawResponse.take(4096)
                                    )
                                }
                                if (isLiveVoiceMode && sessionId == appState.activeSessionId && update.fullContent.isNotBlank()) {
                                    PlatformBridge.speakTextAloud(stripMarkdownToPlainText(update.fullContent))
                                }
                            }
                            is StreamUpdate.Error -> {
                                networkDone = true
                                pacerJob.cancel()
                                val partialProse = networkContentTarget.toString()
                                val hasPartialReasoningOrTools = networkThinkingTarget.isNotEmpty() || accumulatedTools.isNotEmpty()
                                flushCheckpointToDisk(isFinal = true) { v ->
                                    val errorNotice = when {
                                        partialProse.isNotBlank() -> {
                                            "$partialProse\n\n*[Stream paused/interrupted: ${update.message} — tap '→ Continue' to resume seamlessly]*"
                                        }
                                        hasPartialReasoningOrTools -> {
                                            "*[Stream paused/interrupted: ${update.message} — tap '→ Continue' or reply 'continue' to resume seamlessly]*"
                                        }
                                        else -> {
                                            "**[Provider Error: ${update.message}]**\n\nCheck your API key, server URL (`${effectiveProfileForApi.baseUrl}`), or Model ID (`$modelName`) in **⚙ Settings → Providers**."
                                        }
                                    }
                                    v.copy(
                                        content = errorNotice,
                                        thinkingSummary = networkThinkingTarget.toString(),
                                        trailingThinking = if (accumulatedTools.isNotEmpty()) currentStepThinkingTarget.toString().trim() else "",
                                        thinkingSignature = signatureSb.toString(),
                                        thinkingDurationMs = thinkingDurationMs,
                                        toolCalls = accumulatedTools.toList(),
                                        goalTracker = latestGoalTracker ?: v.goalTracker,
                                        mediaItems = accumulatedMedia.toList(),
                                        webSources = accumulatedWebSources,
                                        isStreaming = false,
                                        rawRequestPreview = rawReq,
                                        rawResponsePreview = update.rawDetails.ifBlank { rawResp }.take(4096)
                                    )
                                }
                            }
                        }
                    }
                    break
                } catch (compactSignal: MidStreamAutoCompactSignal) {
                    pauseActiveThinkingStopwatch()
                    flushCheckpointToDisk(isFinal = false)
                    val compacted = executeAutoCompactionInPlace(
                        usagePct = compactSignal.usagePct,
                        isMidStream = true
                    )
                    if (!compacted) {
                        minTokensForNextAutoCompact = Int.MAX_VALUE
                    }
                    continue
                }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            terminalPhase = if (activeStreamsBySession[sessionId]?.phase == AgentRunPhase.STOPPED) AgentRunPhase.STOPPED else AgentRunPhase.INTERRUPTED
            networkDone = true
            pacerJob.cancel()
            flushCheckpointToDisk(isFinal = true)
            throw c
        } catch (t: Throwable) {
            terminalPhase = AgentRunPhase.INTERRUPTED
            networkDone = true
            pacerJob.cancel()
            val partialProse = networkContentTarget.toString()
            val hasPartialReasoningOrTools = networkThinkingTarget.isNotEmpty() || accumulatedTools.isNotEmpty()
            flushCheckpointToDisk(isFinal = true) { v ->
                val errLabel = t.message ?: t::class.simpleName ?: "Unknown error"
                val notice = when {
                    partialProse.isNotBlank() -> "$partialProse\n\n*[Interrupted: $errLabel]*"
                    hasPartialReasoningOrTools -> "*[Interrupted: $errLabel — tap '→ Continue' or reply 'continue' to resume]*"
                    else -> "**[Stream Error: $errLabel]**"
                }
                v.copy(
                    content = notice,
                    thinkingSummary = networkThinkingTarget.toString(),
                    trailingThinking = if (accumulatedTools.isNotEmpty()) currentStepThinkingTarget.toString().trim() else "",
                    thinkingSignature = signatureSb.toString(),
                    thinkingDurationMs = thinkingDurationMs,
                    toolCalls = accumulatedTools.toList(),
                    goalTracker = latestGoalTracker ?: v.goalTracker,
                    mediaItems = accumulatedMedia.toList(),
                    webSources = accumulatedWebSources,
                    isStreaming = false
                )
            }
        } finally {
            approvalObserver.cancel()
            networkDone = true
            if (activeStreamsBySession[sessionId]?.phase == AgentRunPhase.STOPPED) terminalPhase = AgentRunPhase.STOPPED
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { approvalBroker.close() }
            val finishedOwnRun = processState.runs.finish(identity, terminalPhase)

            // Auto-generate Topic Title after the first assistant turn completes with non-empty content
            val updatedSess = appState.sessions.find { it.id == sessionId }
            if (finishedOwnRun && terminalPhase == AgentRunPhase.COMPLETED && networkContentTarget.isNotBlank() && updatedSess != null && !updatedSess.isTitleManuallySet && updatedSess.title == "New Chat" && updatedSess.messages.size in 2..3) {
                generateTopicTitle(sessionId, forceRegenerate = false)
            }

            // If there are queued prompts for this session (`⏳ Queue` mode), automatically dispatch the next one!
            val sessQueue = queuedPromptsBySession[sessionId].orEmpty()
            if (finishedOwnRun && terminalPhase == AgentRunPhase.COMPLETED && sessQueue.isNotEmpty()) {
                val nextItem = sessQueue.first()
                queuedPromptsBySession = queuedPromptsBySession + (sessionId to sessQueue.drop(1))
                dispatchNextQueuedItem?.invoke(sessionId, nextItem)
            }
        }
    }

    processState.runs.attach(identity,
        SessionStreamRuntime(
            sessionId = sessionId,
            targetNodeId = targetAssistantNodeId,
            isThinkingPhase = false,
            statusText = "Connecting to ${effectiveProfileForApi.name} ($modelName)…",
            interactionId = "",
            job = newJob,
            toolApprovalBroker = approvalBroker,
            phase = AgentRunPhase.PREPARING,
            providerContext = RunProviderContext(effectiveProfileForApi),
            actualWireDialect = identity.declaredWireDialect
        ),
        cancelPrevious = false
    )
    owningJob = newJob
    newJob.start()
}
