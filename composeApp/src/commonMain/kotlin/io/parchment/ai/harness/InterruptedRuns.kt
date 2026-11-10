package io.parchment.ai.harness

import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.ToolCallRecord

/** Marks a reply the app closed on; removed before the text is sent back, and Continue resumes from it. */
internal const val INTERRUPTED_REPLY_NOTICE = "\n\n*[Interrupted: the app closed before this reply finished. Use Continue to resume.]*"

private fun ToolCallRecord.isPlaceholderResult(): Boolean = resultText.isBlank() ||
    resultText == "Running shell command…" || resultText == "Running $name…" || resultText == "Running $name in parallel…"

internal fun ToolCallRecord.wasCutOff(): Boolean = isRunning || (isPlaceholderResult() && resultText.isNotBlank())

/**
 * The honest record of a tool that never reported back. A read can simply run again; a change may or
 * may not have happened, so the model is told to look before repeating it.
 */
internal fun ToolCallRecord.withInterruptedOutcome(): ToolCallRecord {
    val outcome = if (WorkspaceHarness.isParallelSafeReadOnlyTool(name)) {
        "[Interrupted before it finished. It is safe to run again.]"
    } else {
        "[Interrupted before it finished, so its effect is unknown. Check the current state before repeating it.]"
    }
    return copy(isRunning = false, isError = true, resultText = if (isPlaceholderResult()) outcome else "$resultText\n$outcome")
}

/** What a live turn looks like if the app never gets to finish it. */
internal fun MessageVariant.settledForDisk(): MessageVariant {
    if (!isStreaming && toolCalls.none { it.wasCutOff() }) return this
    return copy(
        isStreaming = false,
        content = if (isStreaming && INTERRUPTED_REPLY_NOTICE !in content) content + INTERRUPTED_REPLY_NOTICE else content,
        toolCalls = toolCalls.map { if (it.wasCutOff()) it.withInterruptedOutcome() else it }
    )
}
