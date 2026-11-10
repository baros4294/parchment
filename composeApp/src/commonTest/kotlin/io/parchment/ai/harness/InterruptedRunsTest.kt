package io.parchment.ai.harness

import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.stripInlineErrorNotices
import kotlin.test.*

class InterruptedRunsTest {
    private fun running(name: String, result: String = "Running $name…") =
        ToolCallRecord(id = "t", name = name, argumentsJson = "{}", resultText = result, isRunning = true)

    @Test fun anUnfinishedChangeIsReportedAsUnknownAndAReadAsRepeatable() {
        val change = running("fs_write").withInterruptedOutcome()
        assertFalse(change.isRunning)
        assertTrue(change.isError)
        assertTrue("effect is unknown" in change.resultText, change.resultText)
        assertTrue("safe to run again" in running("fs_read").withInterruptedOutcome().resultText)
    }

    @Test fun partialOutputIsKeptWithTheOutcome() {
        val shell = running("shell_exec", result = "line one").withInterruptedOutcome()
        assertTrue(shell.resultText.startsWith("line one\n"), shell.resultText)
        assertTrue("effect is unknown" in shell.resultText)
    }

    @Test fun aSavedLiveTurnSaysItWasInterruptedOnceAndTheNoteIsNotReplayed() {
        val saved = MessageVariant("v", "partial answer", isStreaming = true, toolCalls = listOf(running("fs_edit"))).settledForDisk()
        assertFalse(saved.isStreaming)
        assertEquals("partial answer$INTERRUPTED_REPLY_NOTICE", saved.content)
        assertTrue("effect is unknown" in saved.toolCalls.single().resultText)
        assertEquals(saved.content, saved.copy(isStreaming = true).settledForDisk().content)
        assertEquals("partial answer", AiProviderEngine.stripInlineErrorNotices(saved.content))
    }

    @Test fun finishedTurnsAreLeftAlone() {
        val done = MessageVariant("v", "answer", toolCalls = listOf(ToolCallRecord("t", "fs_write", "{}", "Wrote 1 file")))
        assertSame(done, done.settledForDisk())
    }
}
