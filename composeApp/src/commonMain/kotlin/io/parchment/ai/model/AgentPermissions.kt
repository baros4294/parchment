package io.parchment.ai.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class ToolApprovalMode(val label: String) {
    ASK_FOR_CHANGES("Ask before changes, commands and web access"),
    ASK_EVERY_TOOL("Ask before every tool"),
    NEVER("Run allowed tools without asking")
}

/** Shell commands are not confined by a working directory or by file-tool permissions. */
@Serializable
enum class ShellAccess(val label: String) {
    DISABLED("Shell disabled"),
    FULL_ACCESS("Shell with full process access")
}

@Immutable
@Serializable
data class AgentPermissionPolicy(
    val approvalMode: ToolApprovalMode = ToolApprovalMode.ASK_FOR_CHANGES,
    val workspacePath: String = "",
    val workspaceMode: WorkspaceMode = WorkspaceMode.OFF,
    val toolsEnabled: Boolean = true,
    val toolOverrides: Map<String, Boolean> = emptyMap(),
    val shellAccess: ShellAccess = ShellAccess.DISABLED,
    val webAccess: Boolean = false
) {
    val hasWorkspace: Boolean get() = workspaceMode != WorkspaceMode.OFF && workspacePath.isNotBlank()
    val allowShell: Boolean get() = shellAccess == ShellAccess.FULL_ACCESS
    fun allowsTool(name: String): Boolean = toolsEnabled && toolOverrides[name] != false
}

/** null means inherit; empty path / OFF / false explicitly revoke the inherited setting. */
@Immutable
@Serializable
data class AgentPermissionOverrides(
    val approvalMode: ToolApprovalMode? = null,
    val workspacePath: String? = null,
    val workspaceMode: WorkspaceMode? = null,
    val toolsEnabled: Boolean? = null,
    val toolOverrides: Map<String, Boolean>? = null,
    val shellAccess: ShellAccess? = null,
    val webAccess: Boolean? = null
) {
    fun applyTo(defaults: AgentPermissionPolicy): AgentPermissionPolicy = defaults.copy(
        approvalMode = approvalMode ?: defaults.approvalMode,
        workspacePath = workspacePath ?: defaults.workspacePath,
        workspaceMode = workspaceMode ?: defaults.workspaceMode,
        toolsEnabled = toolsEnabled ?: defaults.toolsEnabled,
        toolOverrides = defaults.toolOverrides + toolOverrides.orEmpty(),
        shellAccess = shellAccess ?: defaults.shellAccess,
        webAccess = webAccess ?: defaults.webAccess
    )
}

object AgentPermissionResolver {
    fun defaults(assistant: AssistantAgent?): AgentPermissionPolicy = assistant?.permissionDefaults
        ?: AgentPermissionPolicy(
            workspacePath = assistant?.workspacePath.orEmpty(),
            workspaceMode = assistant?.workspaceMode ?: WorkspaceMode.OFF,
            webAccess = assistant?.enableWebSearch == true
        )

    /** Old provider shell flags never constitute explicit consent to unrestricted shell access. */
    fun legacyOverrides(session: ChatSession, profile: ProviderProfile?): AgentPermissionOverrides =
        AgentPermissionOverrides(
            workspacePath = session.workspacePath.takeIf {
                it.isNotBlank() || session.workspaceMode != WorkspaceMode.OFF
            },
            workspaceMode = session.workspaceMode.takeIf {
                session.workspacePath.isNotBlank() || it != WorkspaceMode.OFF
            },
            webAccess = true.takeIf { profile?.enableWebSearch == true || profile?.enableUrlContext == true }
        )

    fun resolve(session: ChatSession, assistant: AssistantAgent? = null, profile: ProviderProfile? = null): AgentPermissionPolicy =
        (session.permissionOverrides ?: legacyOverrides(session, profile)).applyTo(defaults(assistant))
}

fun ChatSession.withPermissionOverrides(overrides: AgentPermissionOverrides): ChatSession = copy(permissionOverrides = overrides)

fun AgentPermissionPolicy.asOverrides(): AgentPermissionOverrides = AgentPermissionOverrides(
    approvalMode, workspacePath, workspaceMode, toolsEnabled, toolOverrides, shellAccess, webAccess
)

fun AppStateSnapshot.withImportedPermissions(): AppStateSnapshot = copy(
    assistants = assistants.map { it.withImportedPermissions() },
    sessions = sessions.map { it.withImportedPermissions() },
    profiles = profiles.map { it.copy(allowShellExecution = false) }
)

/** Compatibility for existing folder controls, including an explicit Off or unmount action. */
fun ChatSession.withLegacyWorkspaceChangeFrom(previous: ChatSession): ChatSession {
    if (permissionOverrides != previous.permissionOverrides ||
        (workspacePath == previous.workspacePath && workspaceMode == previous.workspaceMode)) return this
    return copy(permissionOverrides = (permissionOverrides ?: AgentPermissionResolver.legacyOverrides(previous, null)).copy(
        workspacePath = workspacePath,
        workspaceMode = workspaceMode
    ))
}

/** Imports carry content and configuration, never local execution authority or machine paths. */
fun ChatSession.withImportedPermissions(): ChatSession = copy(
    workspacePath = "", workspaceMode = WorkspaceMode.OFF,
    permissionOverrides = AgentPermissionOverrides(
        approvalMode = ToolApprovalMode.ASK_EVERY_TOOL,
        workspacePath = "", workspaceMode = WorkspaceMode.OFF,
        toolsEnabled = false, toolOverrides = emptyMap(), shellAccess = ShellAccess.DISABLED, webAccess = false
    )
)

fun AssistantAgent.withImportedPermissions(): AssistantAgent = copy(
    workspacePath = "", workspaceMode = WorkspaceMode.OFF, enableWebSearch = false,
    permissionDefaults = AgentPermissionPolicy(approvalMode = ToolApprovalMode.ASK_EVERY_TOOL, toolsEnabled = false)
)
