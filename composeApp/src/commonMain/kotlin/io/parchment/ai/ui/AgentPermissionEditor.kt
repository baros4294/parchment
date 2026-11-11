package io.parchment.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.parchment.ai.harness.McpRegistry
import io.parchment.ai.harness.ToolApprovalBroker
import io.parchment.ai.harness.ToolApprovalDecision
import io.parchment.ai.harness.AgentPermissionGate
import io.parchment.ai.harness.ToolAccessScope
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.AgentPermissionOverrides
import io.parchment.ai.model.AgentPermissionPolicy
import io.parchment.ai.model.ShellAccess
import io.parchment.ai.model.ToolApprovalMode
import io.parchment.ai.model.WorkspaceMode
import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.launch

/** A single focus/touch/reader target; policy choices have radio semantics and no motion. */
@Composable
private fun PermissionOption(
    title: String, checked: Boolean, onChange: (Boolean) -> Unit,
    subtitle: String = "", choice: Boolean = false
) {
    val p = LocalParchmentPalette.current
    val action = if (choice) Modifier.selectable(checked, role = Role.RadioButton, onClick = { onChange(true) })
        else Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(action).padding(horizontal = Space.s6, vertical = Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s12)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            BasicText(title, style = p.uiBodyStyle)
            if (subtitle.isNotBlank()) BasicText(subtitle, style = p.uiLabelStyle)
        }
        KissVectorIcon(
            if (!checked) KissIcon.CIRCLE_EMPTY else if (choice) KissIcon.CIRCLE_DOT else KissIcon.CHECK,
            tint = if (checked) p.accent else p.mutedInk, size = 20.dp
        )
    }
}

/** Shared by assistant defaults, the Workspace page and the composer Tools drawer. */
@Composable
internal fun AgentPermissionEditor(
    defaults: AgentPermissionPolicy,
    overrides: AgentPermissionOverrides,
    onChange: (AgentPermissionOverrides) -> Unit,
    onReset: (() -> Unit)? = null
) {
    val palette = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    val policy = overrides.applyTo(defaults)
    val backgroundNotice by PlatformBridge.backgroundExecutionNotice.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s10)) {
        BasicText(Strings.agentPermissions, style = palette.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold))
        if (onReset != null) {
            BasicText(
                if (overrides == AgentPermissionOverrides()) "Following assistant defaults" else "Chat overrides are active",
                style = palette.uiLabelStyle
            )
            SepiaButton(text = Strings.followAssistantDefaults, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                wrapText = true, style = SepiaButtonStyle.OUTLINE, onClick = onReset)
        }
        PermissionOption(Strings.enableAgentTools, policy.toolsEnabled, { onChange(overrides.copy(toolsEnabled = it)) },
            subtitle = if (onReset != null && overrides.toolsEnabled == null) "Follows assistant default" else "")
        if (!policy.toolsEnabled) BasicText(Strings.agentToolsAreOffTheChoices, style = palette.uiLabelStyle)
        BasicText(Strings.approval, style = palette.uiBodyStyle.copy(fontWeight = FontWeight.Medium))
        Column(Modifier.selectableGroup()) {
            ToolApprovalMode.entries.forEach { mode ->
                PermissionOption(mode.label, policy.approvalMode == mode, { onChange(overrides.copy(approvalMode = mode)) }, choice = true,
                    subtitle = if (onReset != null && overrides.approvalMode == null && defaults.approvalMode == mode) "Assistant default" else "")
            }
        }
        BasicText(Strings.fileAccess, style = palette.uiBodyStyle.copy(fontWeight = FontWeight.Medium))
        Column(Modifier.selectableGroup()) { WorkspaceMode.entries.forEach { mode ->
            val label = when (mode) {
                WorkspaceMode.OFF -> Strings.off
                WorkspaceMode.READ_ONLY -> Strings.readFiles
                WorkspaceMode.READ_WRITE -> Strings.readAndEditFiles
            }
            PermissionOption(label, policy.workspaceMode == mode, { onChange(overrides.copy(workspaceMode = mode)) }, choice = true,
                subtitle = if (onReset != null && overrides.workspaceMode == null && defaults.workspaceMode == mode) "Assistant default" else "")
        } }
        SelectionContainer { BasicText(policy.workspacePath.ifBlank { Strings.noFolderChosen }, style = palette.monoStyle) }
        if (policy.workspacePath.isNotBlank() && policy.workspaceMode == WorkspaceMode.OFF) {
            BasicText(Strings.folderSavedFileAccessIsOff, style = palette.uiLabelStyle)
        }
        SepiaButton(
            text = if (policy.workspacePath.isBlank()) Strings.chooseFolder else Strings.changeFolder,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), wrapText = true,
            style = SepiaButtonStyle.OUTLINE,
            onClick = {
                scope.launch {
                    val path = PlatformBridge.pickWorkspaceDirectory(policy.workspacePath)
                    if (!path.isNullOrBlank()) onChange(overrides.copy(
                        workspacePath = path,
                        workspaceMode = if (policy.workspaceMode == WorkspaceMode.OFF) WorkspaceMode.READ_ONLY else policy.workspaceMode
                    ))
                }
            }
        )
        if (policy.workspacePath.isNotBlank()) SepiaButton(
            text = Strings.unmountFolder, compact = true, style = SepiaButtonStyle.GHOST,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), wrapText = true,
            onClick = { onChange(overrides.copy(workspacePath = "", workspaceMode = WorkspaceMode.OFF)) }
        )
        SepiaDivider()
        PermissionOption(Strings.allowWebSearchAndUrlFetching, policy.webAccess, { onChange(overrides.copy(webAccess = it)) },
            subtitle = if (onReset != null && overrides.webAccess == null) "Follows assistant default" else "")
        BasicText(Strings.thisControlsWhetherTheAgentCan, style = palette.uiLabelStyle)
        BasicText(Strings.approvalModesUseLocalWebTools, style = palette.uiLabelStyle)
        Column(Modifier.selectableGroup()) { ShellAccess.entries.forEach { access ->
            PermissionOption(access.label, policy.shellAccess == access, { onChange(overrides.copy(shellAccess = access)) }, choice = true,
                subtitle = if (onReset != null && overrides.shellAccess == null && defaults.shellAccess == access) "Assistant default" else "")
        } }
        BasicText(
            Strings.fileToolsUseTheChosenFolder,
            style = palette.uiLabelStyle
        )
        PermissionOption(Strings.letTheAgentSeeAndControl, policy.computerAccess, { onChange(overrides.copy(computerAccess = it)) },
            subtitle = Strings.offByDefaultScreenshotsGo)
        BasicText(Strings.keepAwakeRequestsFollowActiveWork, style = palette.uiLabelStyle)
        if (backgroundNotice.isNotBlank()) BasicText(backgroundNotice, style = palette.uiLabelStyle)
    }
}

/** Shared registry: the saved per-tool choice is distinct from its effective availability. */
@Composable
internal fun AgentToolRegistry(
    policy: AgentPermissionPolicy,
    overrides: AgentPermissionOverrides,
    onChange: (AgentPermissionOverrides) -> Unit,
    goalModeActive: Boolean = false
) {
    val p = LocalParchmentPalette.current
    var query by remember { mutableStateOf("") }
    val remoteTools by McpRegistry.definitions.collectAsState()
    val tools = remember(remoteTools) { WorkspaceHarness.allTools() }
    val available = remember(policy, goalModeActive, remoteTools) {
        WorkspaceHarness.activeToolsForMode(policy.workspaceMode, policy.workspacePath, policy.allowShell,
            goalModeActive = goalModeActive, enableWebTools = policy.webAccess, policy = policy).map { it.name }.toSet()
    }
    val matches = remember(query, tools) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        tools.filter { tool -> words.all { it in "${tool.name} ${tool.description}".lowercase() } }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s10)) {
        BasicText(Strings.ofToolsAvailable(available.size, tools.size), style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold))
        BasicText(Strings.perToolChoicesAlsoRequireThe, style = p.uiLabelStyle)
        SepiaTextField(query, { query = it }, placeholder = Strings.searchToolNamesAndDescriptions,
            modifier = Modifier.fillMaxWidth(), singleLine = true, minHeight = 48.dp)
        if (matches.isEmpty()) BasicText(Strings.noMatchingTools, style = p.uiLabelStyle)
        matches.forEach { tool ->
            val allowed = policy.toolOverrides[tool.name] != false
            val status = when {
                !policy.toolsEnabled -> Strings.agentToolsAreOff
                !allowed -> Strings.disabledBySavedToolChoice
                tool.name in available -> "Available · ${if (policy.approvalMode == ToolApprovalMode.NEVER) "runs without asking" else "approval policy applies"}"
                else -> AgentPermissionGate.denial(ToolAccessScope(policy, goalModeActive), tool.name, "{}") ?: Strings.unavailableInThisRun
            }
            SepiaDivider()
            PermissionOption(tool.name, allowed, { enabled ->
                onChange(overrides.copy(toolOverrides = overrides.toolOverrides.orEmpty() + (tool.name to enabled)))
            }, subtitle = "${tool.description}\n$status")
        }
    }
}

@Composable
internal fun UserQuestionPanel(broker: ToolApprovalBroker?, chatTitle: String) {
    if (broker == null) return
    val questions by broker.questions.collectAsState()
    if (questions.isEmpty()) return
    val palette = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(Space.s12),
        verticalArrangement = Arrangement.spacedBy(Space.s12)
    ) {
        questions.forEach { question ->
            var typed by remember(question.requestId) { mutableStateOf("") }
            SepiaSectionCard(title = Strings.questionForYou, subtitle = chatTitle) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                    SelectionContainer { BasicText(question.question, style = palette.uiBodyStyle) }
                    val actionModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    question.options.forEach { option ->
                        SepiaButton(option, modifier = actionModifier, wrapText = true, style = SepiaButtonStyle.OUTLINE, onClick = { scope.launch { broker.answerQuestion(question.requestId, option) } })
                    }
                    SepiaTextField(value = typed, onValueChange = { typed = it }, placeholder = Strings.typeYourAnswer, modifier = Modifier.fillMaxWidth())
                    SepiaButton(Strings.sendAnswer, modifier = actionModifier, wrapText = true, enabled = typed.isNotBlank(), style = SepiaButtonStyle.PRIMARY, onClick = { scope.launch { broker.answerQuestion(question.requestId, typed.trim()) } })
                }
            }
        }
    }
}

@Composable
internal fun ToolApprovalPanel(broker: ToolApprovalBroker?, chatTitle: String) {
    if (broker == null) return
    val requests by broker.requests.collectAsState()
    if (requests.isEmpty()) return
    val palette = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(Space.s12),
        verticalArrangement = Arrangement.spacedBy(Space.s12)
    ) {
        requests.forEach { request ->
            SepiaSectionCard(title = Strings.approval2(chatTitle), subtitle = request.toolName) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                    SelectionContainer {
                        BasicText(
                            request.argumentsJson,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                            style = palette.monoStyle
                        )
                    }
                    BasicText(Strings.workspace(request.scope.policy.workspacePath.ifBlank { Strings.none }), style = palette.uiLabelStyle)
                    if (request.toolName == "shell_exec") BasicText(Strings.fullProcessAccessFilesystemAndNetwork, style = palette.uiLabelStyle)
                    if (request.toolName == "computer_use") BasicText(WorkspaceHarness.describeToolCallIntent(request.toolName, request.argumentsJson), style = palette.uiBodyStyle)
                    val actionModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    SepiaButton(Strings.allowOnce, modifier = actionModifier, wrapText = true, style = SepiaButtonStyle.PRIMARY, onClick = { scope.launch { broker.respond(request.requestId, ToolApprovalDecision.ALLOW_ONCE) } })
                    if (request.toolName != "computer_use") SepiaButton(Strings.allowThisExactActionForThis, modifier = actionModifier, wrapText = true, style = SepiaButtonStyle.OUTLINE, onClick = { scope.launch { broker.respond(request.requestId, ToolApprovalDecision.ALLOW_EXACT_ACTION_FOR_RUN) } })
                    SepiaButton(Strings.deny, modifier = actionModifier, wrapText = true, style = SepiaButtonStyle.OUTLINE, onClick = { scope.launch { broker.respond(request.requestId, ToolApprovalDecision.DENY) } })
                    SepiaButton(Strings.stopRun, modifier = actionModifier, wrapText = true, style = SepiaButtonStyle.DANGER, onClick = { scope.launch { broker.respond(request.requestId, ToolApprovalDecision.STOP) } })
                }
            }
        }
    }
}
