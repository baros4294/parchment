package io.parchment.ai.ui

import androidx.compose.runtime.getValue
import io.parchment.ai.strings.Strings
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.WorkspaceMode
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.AgentPermissionOverrides
import io.parchment.ai.platform.PlatformWorkspace

// ============================================================================================
// Settings page 5: Workspace and agent tools (the folder, permissions, skills and MCP servers).
// ============================================================================================
@Composable
internal fun WorkspaceSubpage(
    appState: AppStateSnapshot,
    activeSession: ChatSession,
    activeProfile: ProviderProfile,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onUpdatePreferences: (io.parchment.ai.model.PreferencesConfig) -> Unit,
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    val scope = rememberCoroutineScope()

    val activeAssistant = appState.assistants.find { it.id == activeSession.assistantId }
        ?: appState.assistants.find { it.id == appState.activeAssistantId }
        ?: appState.assistants.firstOrNull()
    val permissionDefaults = AgentPermissionResolver.defaults(activeAssistant)
    val permissionOverrides = activeSession.permissionOverrides ?: AgentPermissionResolver.legacyOverrides(activeSession, activeProfile)
    val toolPolicy = permissionOverrides.applyTo(permissionDefaults)
    val effectiveWsPath = toolPolicy.workspacePath
    val effectiveWsMode = toolPolicy.workspaceMode

    val discoveredFiles by produceState(emptyList<String>(), effectiveWsPath) {
        value = withContext(Dispatchers.IO) { WorkspaceHarness.listDiscoveredContextFileNames(effectiveWsPath) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (win.isCompact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s16)
    ) {
        McpServersCard(appState.preferences, onUpdatePreferences)
        SkillInstallCard(effectiveWsPath, effectiveWsMode == io.parchment.ai.model.WorkspaceMode.READ_WRITE, onNotify)
        BasicText(text = Strings.workspaceAndAgentTools, style = p.uiHeaderStyle.copy(fontSize = 19.sp))

        SepiaSectionCard(
            title = Strings.workspaceAndAgentPermissions
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                AgentPermissionEditor(
                    defaults = permissionDefaults, overrides = permissionOverrides,
                    onChange = { value -> onUpdateSession { it.copy(permissionOverrides = value) } },
                    onReset = { onUpdateSession { it.copy(permissionOverrides = AgentPermissionOverrides()) } }
                )
                if (effectiveWsPath.isNotBlank()) {
                    SepiaDivider()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Space.s10)
                    ) {
                        Column {
                            BasicText(
                                text = Strings.autoLoadedProjectMemoryContext,
                                style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            )
                            BasicText(
                                text = if (discoveredFiles.isEmpty()) {
                                    Strings.noAgentsMdClaudeMdParchment
                                } else {
                                    "Auto-injected into system prompt: ${discoveredFiles.joinToString(", ")}"
                                },
                                style = p.uiLabelStyle.copy(color = if (discoveredFiles.isNotEmpty()) p.accent else p.mutedInk)
                            )
                        }
                        if ("PARCHMENT_MEMORY.md" !in discoveredFiles) {
                            SepiaButton(
                                text = Strings.createProjectMemoryFile,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), wrapText = true,
                                enabled = effectiveWsMode == WorkspaceMode.READ_WRITE,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    PlatformWorkspace.workspaceWrite(
                                        effectiveWsPath,
                                        "PARCHMENT_MEMORY.md",
                                        "# Project & Story Memory\n\n- Record key architectural rules, character notes, and decisions here.\n"
                                    ).onSuccess {
                                        onNotify(Strings.createdParchmentMemoryMdInWorkspace)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        SepiaSectionCard(title = Strings.agentTools) {
            AgentToolRegistry(
                policy = toolPolicy, overrides = permissionOverrides,
                onChange = { value -> onUpdateSession { it.copy(permissionOverrides = value) } }
            )
        }
    }
}
