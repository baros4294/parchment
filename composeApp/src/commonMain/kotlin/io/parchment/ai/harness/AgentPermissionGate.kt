package io.parchment.ai.harness

import io.parchment.ai.model.AgentPermissionPolicy
import io.parchment.ai.model.ToolApprovalMode
import io.parchment.ai.model.WorkspaceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ToolAccessScope(
    val policy: AgentPermissionPolicy,
    val goalModeActive: Boolean = false,
    val includeLocalWebSearchTool: Boolean = policy.webAccess
)

data class ToolEffect(val requiresApproval: Boolean, val changesFiles: Boolean)

/** The same registry and policy govern advertisement and dispatch, including indirect writes. */
object AgentPermissionGate {
    private val json = Json { ignoreUnknownKeys = true }

    fun denial(scope: ToolAccessScope, name: String, argumentsJson: String): String? {
        val policy = scope.policy
        val definition = WorkspaceHarness.allTools().find { it.name == name }
            ?: return "Unknown workspace tool '$name'."
        if (!policy.allowsTool(name)) return "Tool '$name' is disabled by the current policy."
        if (definition.requiresWorkspaceFolder && !policy.hasWorkspace) return "Workspace harness is disabled or no workspace directory is mounted."
        if (definition.requiresShellPermission && !policy.allowShell) return "Shell execution is disabled. Enable full process access explicitly before running commands."
        if (definition.requiresWritePermission && policy.workspaceMode != WorkspaceMode.READ_WRITE) return "Workspace is in Read-Only mode."
        val arguments = runCatching { json.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject }.getOrNull()
            ?: return "Tool arguments must be a valid JSON object."
        if (name == "agent_memory") {
            val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull ?: "read"
            if (action.lowercase() !in setOf("read", "append", "overwrite")) return "Unknown agent_memory action '$action'."
            if (action.lowercase() != "read" && policy.workspaceMode != WorkspaceMode.READ_WRITE) return "Workspace is in Read-Only mode. Memory writes are disabled."
        }
        if (name in setOf("web_search", "web_fetch") && !policy.webAccess) return "Web access is disabled by the current policy."
        if (name == ComputerUse.TOOL_NAME && !policy.computerAccess) return "Computer control is turned off for this chat."
        if (WorkspaceHarness.activeToolsForMode(
                policy.workspaceMode, policy.workspacePath, policy.allowShell,
                scope.goalModeActive, policy.webAccess, scope.includeLocalWebSearchTool, policy
            ).none { it.name == name }) return "Tool '$name' is not available in this run."
        return null
    }

    fun effect(scope: ToolAccessScope, name: String, argumentsJson: String): ToolEffect {
        val action = runCatching {
            ((json.parseToJsonElement(argumentsJson) as? JsonObject)?.get("action") as? JsonPrimitive)?.contentOrNull
        }.getOrNull() ?: "read"
        val changesFiles = name in setOf("fs_write", "fs_edit", "fs_multi_edit", "fs_move", "fs_delete", "shell_exec") ||
            (name == "agent_memory" && action.lowercase() != "read") ||
            (name == "todo_write" && scope.policy.hasWorkspace && scope.policy.workspaceMode == WorkspaceMode.READ_WRITE)
        val changesState = name in setOf("todo_write", "goal_complete")
        val usesWeb = name in setOf("web_search", "web_fetch")
        val external = WorkspaceHarness.isMcpToolName(name) // What another app's tool does cannot be known from here.
        return ToolEffect(
            // Asking the person is itself the check, so it never needs its own approval.
            // Control of the whole device always asks, whatever the setting for other tools.
            requiresApproval = name == ComputerUse.TOOL_NAME || name != "ask_user" && when (scope.policy.approvalMode) {
                ToolApprovalMode.ASK_EVERY_TOOL -> true
                ToolApprovalMode.ASK_FOR_CHANGES -> changesFiles || changesState || usesWeb || external
                ToolApprovalMode.NEVER -> false
            },
            changesFiles = changesFiles
        )
    }
}
