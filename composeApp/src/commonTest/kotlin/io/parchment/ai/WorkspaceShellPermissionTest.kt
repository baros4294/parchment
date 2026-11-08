package io.parchment.ai

import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.WorkspaceMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceShellPermissionTest {
    @Test
    fun readWriteModeDoesNotImplicitlyAuthorizeShell() {
        var outputReceived = false
        val result = WorkspaceHarness.executeToolCall(
            workspaceRoot = "unused-permission-test-root",
            mode = WorkspaceMode.READ_WRITE,
            callId = "denied-default",
            toolName = "shell_exec",
            argumentsJson = """{"command":"echo must-not-run"}""",
            onOutputDelta = { outputReceived = true }
        )
        assertTrue(result.isError)
        assertTrue(result.resultText.contains("Shell execution is disabled"))
        assertFalse(outputReceived)
    }

    @Test
    fun explicitlyDisabledShellIsRejectedAtDispatch() {
        val result = WorkspaceHarness.executeToolCall(
            workspaceRoot = "unused-permission-test-root",
            mode = WorkspaceMode.READ_WRITE,
            callId = "denied-explicit",
            toolName = "shell_exec",
            argumentsJson = """{"command":"echo must-not-run"}""",
            allowShell = false
        )
        assertTrue(result.isError)
        assertTrue(result.resultText.contains("Shell execution is disabled"))
        assertFalse(WorkspaceHarness.activeToolsForMode(
            WorkspaceMode.READ_WRITE, "unused-permission-test-root", allowShell = false
        ).any { it.name == "shell_exec" })
    }

    @Test
    fun shellPermissionDoesNotOverrideReadOnlyAccess() {
        val result = WorkspaceHarness.executeToolCall(
            workspaceRoot = "unused-permission-test-root",
            mode = WorkspaceMode.READ_ONLY,
            callId = "denied-read-only",
            toolName = "shell_exec",
            argumentsJson = """{"command":"echo must-not-run"}""",
            allowShell = true
        )
        assertTrue(result.isError)
        assertTrue(result.resultText.contains("Read-Only"))
    }
}
