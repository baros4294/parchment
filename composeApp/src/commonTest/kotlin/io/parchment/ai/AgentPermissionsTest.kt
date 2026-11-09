package io.parchment.ai

import io.parchment.ai.harness.AgentPermissionGate
import io.parchment.ai.harness.ToolAccessScope
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.*
import io.parchment.ai.ui.decodeChatSessionsFromRawJson
import io.parchment.ai.ui.mergeSessionMetadata
import kotlinx.serialization.json.Json
import kotlin.test.*

class AgentPermissionsTest {
    private val assistant = AssistantAgent("assistant", "Agent", permissionDefaults = AgentPermissionPolicy(
        workspacePath = "project", workspaceMode = WorkspaceMode.READ_WRITE,
        shellAccess = ShellAccess.FULL_ACCESS, webAccess = true
    ))
    private val chat = ChatSession("chat", "Chat", 1L, 1L, "provider", assistantId = assistant.id,
        permissionOverrides = AgentPermissionOverrides())

    @Test fun offAndUnmountAreExplicitOverrides() {
        assertEquals(WorkspaceMode.READ_WRITE, AgentPermissionResolver.resolve(chat, assistant).workspaceMode)
        val off = chat.copy(permissionOverrides = AgentPermissionOverrides(workspaceMode = WorkspaceMode.OFF))
        assertFalse(AgentPermissionResolver.resolve(off, assistant).hasWorkspace)
        val unmounted = chat.copy(permissionOverrides = AgentPermissionOverrides(workspacePath = ""))
        assertEquals("", AgentPermissionResolver.resolve(unmounted, assistant).workspacePath)
        assertFalse(AgentPermissionResolver.resolve(unmounted, assistant).hasWorkspace)
        val legacyEdit = chat.copy(workspacePath = "", workspaceMode = WorkspaceMode.OFF)
        // A known legacy path is cleared through the old mount control.
        val previous = chat.copy(workspacePath = "project", workspaceMode = WorkspaceMode.READ_WRITE)
        assertFalse(AgentPermissionResolver.resolve(legacyEdit.withLegacyWorkspaceChangeFrom(previous), assistant).hasWorkspace)
    }

    @Test fun overridesPreserveIndependentInheritanceAndRoundTrip() {
        val changed = chat.copy(permissionOverrides = AgentPermissionOverrides(webAccess = false,
            toolOverrides = mapOf("fs_read" to false)))
        val policy = AgentPermissionResolver.resolve(changed, assistant)
        assertEquals(WorkspaceMode.READ_WRITE, policy.workspaceMode)
        assertFalse(policy.webAccess)
        assertFalse(policy.allowsTool("fs_read"))
        val encoded = Json.encodeToString(ChatSession.serializer(), changed)
        assertEquals(changed, Json.decodeFromString(ChatSession.serializer(), encoded))
        assertEquals(changed.permissionOverrides, mergeSessionMetadata(chat, changed).permissionOverrides)
    }

    @Test fun legacySettingsNeverGrantUnrestrictedShell() {
        val legacy = chat.copy(permissionOverrides = null, workspacePath = "project", workspaceMode = WorkspaceMode.READ_WRITE)
        val profile = ProviderProfile("provider", "Provider", ProviderProtocol.OPENAI_RESPONSES, allowShellExecution = true)
        assertEquals(ShellAccess.DISABLED, AgentPermissionResolver.resolve(legacy, AssistantAgent("a", "A"), profile).shellAccess)
        assertEquals(ToolApprovalMode.ASK_FOR_CHANGES, AgentPermissionResolver.resolve(legacy, profile = profile).approvalMode)
    }

    @Test fun importsCannotAuthorizeToolsOrLocalPaths() {
        val unsafe = chat.copy(permissionOverrides = assistant.permissionDefaults!!.copy(approvalMode = ToolApprovalMode.NEVER).asOverrides())
        val imported = decodeChatSessionsFromRawJson(Json.encodeToString(ChatSession.serializer(), unsafe)).single()
        val policy = AgentPermissionResolver.resolve(imported, assistant)
        assertFalse(policy.toolsEnabled)
        assertFalse(policy.hasWorkspace)
        assertFalse(policy.webAccess)
        assertFalse(policy.allowShell)
        assertEquals(ToolApprovalMode.ASK_EVERY_TOOL, policy.approvalMode)
        assertEquals(unsafe.title, imported.title)
    }

    @Test fun disabledUnknownAndUnadvertisedCallsAreRejectedBeforeEffects() {
        val policy = AgentPermissionPolicy(workspacePath = "unused", workspaceMode = WorkspaceMode.READ_WRITE,
            toolOverrides = mapOf("fs_write" to false))
        val scope = ToolAccessScope(policy)
        assertNotNull(AgentPermissionGate.denial(scope, "fs_write", """{"path":"file","content":"data"}"""))
        assertNotNull(AgentPermissionGate.denial(scope, "FS_READ", "{}"))
        assertNotNull(AgentPermissionGate.denial(scope, "web_fetch", """{"url":"https://example.com"}"""))
        assertNotNull(AgentPermissionGate.denial(scope, "fs_delete", "[]"))
        assertFalse(WorkspaceHarness.activeToolsForMode(policy.workspaceMode, policy.workspacePath, policy = policy).any { it.name == "web_fetch" || it.name == "fs_write" })
        val result = WorkspaceHarness.executeToolCall("unused", WorkspaceMode.READ_WRITE, "disabled", "fs_write", "{}", accessScope = scope)
        assertTrue(result.isError)
        assertTrue(result.resultText.contains("disabled"))
    }

    @Test fun indirectWritesRequirePermissionAndApproval() {
        val rw = ToolAccessScope(AgentPermissionPolicy(workspacePath = "unused", workspaceMode = WorkspaceMode.READ_WRITE))
        assertTrue(AgentPermissionGate.effect(rw, "todo_write", "{}").requiresApproval)
        assertTrue(AgentPermissionGate.effect(rw, "agent_memory", """{"action":"append"}""").changesFiles)
        assertFalse(AgentPermissionGate.effect(rw, "agent_memory", """{"action":"read"}""").requiresApproval)
        assertNotNull(AgentPermissionGate.denial(rw.copy(policy = rw.policy.copy(workspaceMode = WorkspaceMode.READ_ONLY)), "agent_memory", """{"action":"overwrite"}"""))
        assertTrue(WorkspaceHarness.executeToolCall("unused", WorkspaceMode.READ_WRITE, "ask", "todo_write", "{}", accessScope = rw).isError)
    }
}
