package io.parchment.ai.ui

import androidx.compose.runtime.key
import androidx.compose.ui.input.key.key
import io.parchment.ai.model.MessageRole
import io.parchment.ai.network.cancelBackgroundInteraction
import io.parchment.ai.network.stripInlineErrorNotices
import io.parchment.ai.runtime.appRunLeases
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.protocol.O200kTokenizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun StorySummaryHost.triggerAutoSummarizeStory(autoMuteOlderTurns: Boolean = false) {
    val complete = completeSessionForAction(activeSession) ?: return
    val targetSessId = complete.id
    if (complete.messages.isEmpty()) return
    val prevStream = activeStreamsBySession[targetSessId]
    val wasStreaming = prevStream != null
    val dm = appState.defaultModels
    val summaryProf = if (!dm.summaryFollowGlobal && dm.summaryProfileId.isNotBlank()) {
        appState.profiles.find { it.id == dm.summaryProfileId } ?: activeProfile
    } else activeProfile
    val summaryModel = if (!dm.summaryFollowGlobal && dm.summaryModelId.isNotBlank()) {
        dm.summaryModelId
    } else complete.modelOverride.ifBlank { summaryProf.defaultModel }

    val sumStatus = when {
        wasStreaming && autoMuteOlderTurns ->
            "Pausing the agent to shorten the conversation ($summaryModel)…"
        wasStreaming ->
            "Stopping agent — summarizing continuity & auto-resuming ($summaryModel)…"
        autoMuteOlderTurns ->
            "Shortening the conversation ($summaryModel)…"
        else ->
            "Summarizing story continuity into memory ($summaryModel)…"
    }

    val sumJob = appRunLeases.launch(appProcessScope, summaryModel) {
        var shouldResumeTurnIndex = -1
        try {
            if (prevStream != null) {
                prevStream.job?.cancel()
                if (prevStream.interactionId.isNotBlank() && (prevStream.providerContext?.profile ?: activeProfile).useBackgroundLongDecode) {
                    runCatching {
                        AiProviderEngine.cancelBackgroundInteraction(prevStream.providerContext?.profile ?: activeProfile, prevStream.interactionId)
                    }
                }
                prevStream.job?.join()
            }
            updateSessionStreamState(targetSessId) {
                SessionStreamRuntime(sessionId = targetSessId, statusText = sumStatus, job = coroutineContext[kotlinx.coroutines.Job]!!)
            }

            val sessSnap = appState.sessions.find { it.id == targetSessId }?.let(::completeSessionForAction) ?: return@launch
            val keepTurnsCount = if (wasStreaming) 2 else 4
            val plan = O200kTokenizer.prepareCompactionPlan(
                existingStorySummary = sessSnap.storySummary,
                historyNodes = sessSnap.messages,
                keepRecentTurnsCount = keepTurnsCount
            )
            val transcriptForPrompt = if (plan.transcriptToSummarize.isNotBlank()) {
                plan.transcriptToSummarize
            } else {
                buildString {
                    if (sessSnap.storySummary.isNotBlank()) {
                        appendLine("Existing Continuity Summary:")
                        appendLine(sessSnap.storySummary.trim())
                        appendLine()
                    }
                    appendLine("Conversation Turns:")
                    for (turn in sessSnap.messages.filter { it.includedInContext }.takeLast(24)) {
                        val roleLabel = if (turn.role == MessageRole.ASSISTANT) "Assistant" else "User"
                        val txt = AiProviderEngine.stripInlineErrorNotices(turn.activeVariant.content).trim().take(1600)
                        if (txt.isNotEmpty()) appendLine("$roleLabel: $txt")
                    }
                }.trim()
            }
            val sumPrompt = "Update the comprehensive bulleted continuity summary with all key events, facts, files/tools inspected, decisions, and character/project states from the context below. Do not truncate or omit important details:\n\n$transcriptForPrompt"
            val generatedSummary = withTimeoutOrNull(180_000L) {
                generateTrackedUtility(targetSessId, if (autoMuteOlderTurns) "compaction" else "summary", summaryProf, summaryModel, sumPrompt, maxOutputTokens = null).getOrNull()
            }?.takeIf { it.isNotBlank() } ?: plan.fallbackSummary.ifBlank { sessSnap.storySummary }

            updateSessionById(targetSessId, immediate = true) { sess ->
                val updatedMsgs = if (autoMuteOlderTurns) {
                    val compactedById = plan.compactedHistoryNodes.associateBy { it.id }
                    sess.messages.map { n ->
                        compactedById[n.id] ?: if (n.id in plan.mutedNodeIds) n.copy(includedInContext = false) else n
                    }
                } else {
                    sess.messages
                }
                sess.copy(
                    storySummary = generatedSummary,
                    messages = updatedMsgs
                )
            }

            if (wasStreaming) {
                val latestSess = appState.sessions.find { it.id == targetSessId }
                shouldResumeTurnIndex = latestSess?.messages?.indexOfLast { it.role == MessageRole.ASSISTANT } ?: -1
                bannerNotification = if (autoMuteOlderTurns) {
                    "Conversation shortened. Continuing."
                } else {
                    "Updated Continuity Memory — auto-resuming agent."
                }
            } else {
                bannerNotification = if (autoMuteOlderTurns) {
                    "Conversation shortened. Older messages are summarized and no longer sent."
                } else {
                    "Updated Rolling Continuity Memory."
                }
            }
        } catch (e: Throwable) {
            if (e !is kotlinx.coroutines.CancellationException) {
                bannerNotification = "Couldn't shorten the conversation: ${e.message}"
            }
        } finally {
            if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && shouldResumeTurnIndex >= 0) {
                appProcessScope.launch {
                    continueAssistantNodeAt(shouldResumeTurnIndex)
                }
            } else if (activeStreamsBySession[targetSessId]?.job === coroutineContext[kotlinx.coroutines.Job]) {
                updateSessionStreamState(targetSessId) { null }
            }
        }
    }
    if (!wasStreaming) {
        updateSessionStreamState(targetSessId) {
            SessionStreamRuntime(sessionId = targetSessId, statusText = sumStatus, job = sumJob)
        }
    }
}
