package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.AgentPermissionPolicy
import io.parchment.ai.model.asOverrides
import io.parchment.ai.platform.PlatformBridge

// ============================================================================================
// Settings page 3: Assistants (the list and the editor).
// ============================================================================================
@Composable
internal fun AssistantsSubpage(
    appState: AppStateSnapshot,
    onUpdateAssistants: (List<AssistantAgent>, String) -> Unit,
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    var editingAssistantId by remember { mutableStateOf<String?>(null) }
    var showCustomAssistantEffort by remember { mutableStateOf(false) }
    var customAssistantEffortDraft by remember { mutableStateOf("") }

    val editingAssistant = appState.assistants.find { it.id == editingAssistantId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (win.isCompact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (editingAssistant != null) {
            // Dedicated Assistant Editor Subpage
            var tempText by remember(editingAssistant.id) { mutableStateOf(editingAssistant.temperature?.toString() ?: "") }
            var topPText by remember(editingAssistant.id) { mutableStateOf(editingAssistant.topP?.toString() ?: "") }
            var maxOutText by remember(editingAssistant.id) { mutableStateOf(editingAssistant.maxOutputTokens?.toString() ?: "") }
            var ctxMsgLimitText by remember(editingAssistant.id) { mutableStateOf(editingAssistant.contextMessageLimit.toString()) }

            fun updateCurrent(transform: (AssistantAgent) -> AssistantAgent) {
                val updatedList = appState.assistants.map { if (it.id == editingAssistant.id) transform(it) else it }
                onUpdateAssistants(updatedList, appState.activeAssistantId)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = "Edit Assistant — ${editingAssistant.name}",
                    style = p.uiHeaderStyle,
                    modifier = Modifier.weight(1f).padding(end = Space.s8)
                )
                SepiaButton(
                    text = "← All assistants",
                    compact = true,
                    style = SepiaButtonStyle.PRIMARY,
                    onClick = { editingAssistantId = null }
                )
            }

            SepiaSectionCard(title = "Assistant identity & system prompt") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.s12),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        SepiaAvatarCircle(
                            symbol = editingAssistant.iconEmoji,
                            selected = true,
                            sizeDp = 42.dp
                        )
                        SepiaTextField(
                            value = editingAssistant.name,
                            onValueChange = { nm -> updateCurrent { it.copy(name = nm) } },
                            label = "Assistant name",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Space.s6),
                        verticalArrangement = Arrangement.spacedBy(Space.s6)
                    ) {
                        listOf(
                            "💬", "🤖", "🧪", "✏️", "📚", "⚡",
                            "🎭", "🧭", "🧠", "🌐", "🎯", "💻",
                            "🔧", "✦", "🛡", "🎨", "👑", "🔥"
                        ).forEach { emoji ->
                            SepiaChip(
                                label = emoji,
                                selected = editingAssistant.iconEmoji == emoji,
                                onClick = { updateCurrent { it.copy(iconEmoji = emoji) } }
                            )
                        }
                    }

                    SepiaTextField(
                        value = editingAssistant.systemPrompt,
                        onValueChange = { sp -> updateCurrent { it.copy(systemPrompt = sp) } },
                        label = "Instructions (kept across chats)",
                        placeholder = "Enter system prompt (optional)…",
                        singleLine = false,
                        minHeight = 140.dp,
                        maxHeight = 520.dp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    SepiaTextField(
                        value = editingAssistant.authorsNote,
                        onValueChange = { an -> updateCurrent { it.copy(authorsNote = an) } },
                        label = "Default Author's note / Post-History injection (optional)",
                        singleLine = false,
                        minHeight = 60.dp,
                        maxHeight = 320.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            SepiaSectionCard(
                title = "Assistant model, thinking effort & workspace"
            ) {
                val scope = rememberCoroutineScope()
                val followsGlobalModel = editingAssistant.defaultModelId.isBlank()
                Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                    SepiaToggleRow(
                        title = "Follow global default model",
                        checked = followsGlobalModel,
                        onCheckedChange = { follow ->
                            if (follow) {
                                updateCurrent { it.copy(defaultProfileId = "", defaultModelId = "") }
                            } else {
                                val fallbackProf = appState.profiles.find { it.id == appState.activeProfileId } ?: appState.profiles.first()
                                updateCurrent {
                                    it.copy(
                                        defaultProfileId = fallbackProf.id,
                                        defaultModelId = fallbackProf.defaultModel.ifBlank { fallbackProf.savedModels.firstOrNull().orEmpty() }
                                    )
                                }
                            }
                        }
                    )
                    AnimatedVisibility(visible = !followsGlobalModel) {
                        SepiaSearchableModelPicker(
                            profiles = appState.profiles,
                            selectedProfileId = editingAssistant.defaultProfileId.ifBlank { appState.activeProfileId },
                            selectedModelId = editingAssistant.defaultModelId,
                            onSelectModel = { profId, modId ->
                                updateCurrent { it.copy(defaultProfileId = profId, defaultModelId = modId) }
                            },
                            maxListHeight = 200.dp
                        )
                    }
                    SepiaDivider()
                    SepiaAdaptiveRow(label = "Thinking effort override") {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s6)) {
                            listOf(
                                "" to "Inherit",
                                "none" to "None",
                                "low" to "Low",
                                "medium" to "Medium",
                                "high" to "High",
                                "xhigh" to "XHigh",
                                "max" to "Max",
                                "adaptive" to "Adaptive",
                                "auto" to "Auto"
                            ).forEach { (effVal, effLabel) ->
                                SepiaChip(
                                    label = effLabel,
                                    selected = editingAssistant.thinkingEffort.equals(effVal, ignoreCase = true),
                                    onClick = { updateCurrent { it.copy(thinkingEffort = effVal) } }
                                )
                            }
                            SepiaChip(
                                label = "Custom…",
                                selected = showCustomAssistantEffort,
                                onClick = {
                                    showCustomAssistantEffort = !showCustomAssistantEffort
                                    if (showCustomAssistantEffort) customAssistantEffortDraft = editingAssistant.thinkingEffort
                                }
                            )
                        }
                        if (showCustomAssistantEffort) {
                            Spacer(Modifier.height(Space.s6))
                            SepiaTextField(
                                value = customAssistantEffortDraft,
                                onValueChange = { raw ->
                                    customAssistantEffortDraft = raw
                                    updateCurrent { it.copy(thinkingEffort = raw.trim()) }
                                },
                                placeholder = "Raw effort value (e.g. adaptive, auto, budget:4096)",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                    SepiaDivider()
                    val defaults = AgentPermissionResolver.defaults(editingAssistant)
                    AgentPermissionEditor(
                        defaults = AgentPermissionPolicy(), overrides = defaults.asOverrides(),
                        onChange = { value -> updateCurrent { it.copy(permissionDefaults = value.applyTo(AgentPermissionPolicy())) } }
                    )
                    SepiaDivider()
                    AgentToolRegistry(
                        policy = defaults, overrides = defaults.asOverrides(),
                        onChange = { value -> updateCurrent { it.copy(permissionDefaults = value.applyTo(AgentPermissionPolicy())) } }
                    )
                }
            }

            SepiaSectionCard(
                title = "Assistant sampling & context overrides (blank = follow profile, 0 = ∞)"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                        SepiaTextField(
                            value = tempText,
                            onValueChange = { v ->
                                tempText = v
                                val t = v.trim()
                                updateCurrent { it.copy(temperature = if (t.isEmpty()) null else t.toDoubleOrNull()) }
                            },
                            label = "Creativity, called temperature (blank = default)",
                            placeholder = "Omitted",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        SepiaTextField(
                            value = topPText,
                            onValueChange = { v ->
                                topPText = v
                                val t = v.trim()
                                updateCurrent { it.copy(topP = if (t.isEmpty()) null else t.toDoubleOrNull()) }
                            },
                            label = "Word variety, called Top-P (blank = default)",
                            placeholder = "Omitted",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    SepiaTextField(
                        value = maxOutText,
                        onValueChange = { v ->
                            maxOutText = v
                            val t = v.trim()
                            updateCurrent { it.copy(maxOutputTokens = if (t.isEmpty()) null else t.toIntOrNull()) }
                        },
                        label = "Reply limit in tokens (blank or 0 = none)",
                        placeholder = "Unlimited",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SepiaTextField(
                        value = ctxMsgLimitText,
                        onValueChange = { v ->
                            ctxMsgLimitText = v
                            val n = v.trim().toIntOrNull() ?: 0
                            updateCurrent { it.copy(contextMessageLimit = n.coerceAtLeast(0)) }
                        },
                        label = "History message limit (0 = unlimited)",
                        placeholder = "0 = unlimited",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            return@Column
        }

        // Assistant list. Assistants are all user-created; none are hardcoded.
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Space.s8)
        ) {
            BasicText(
                text = "Assistants",
                style = p.uiHeaderStyle.copy(fontSize = 19.sp),
                modifier = Modifier.padding(top = Space.s2, end = Space.s8)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Space.s8),
                verticalArrangement = Arrangement.spacedBy(Space.s6)
            ) {
                SepiaButton(
                    text = "+ New assistant",
                    compact = true,
                    style = SepiaButtonStyle.PRIMARY,
                    onClick = {
                        val now = PlatformBridge.currentTimeMillis()
                        val newAsst = AssistantAgent(
                            id = "asst-$now",
                            name = "New Assistant",
                            iconEmoji = "🤖"
                        )
                        onUpdateAssistants(appState.assistants + newAsst, newAsst.id)
                        editingAssistantId = newAsst.id
                    }
                )
            }
        }

        if (appState.assistants.isEmpty()) {
            SepiaSectionCard(
                title = "Default assistant active",
                subtitle = "Click '+ New Assistant' above to create additional assistant profiles."
            ) {}
        }

        appState.assistants.forEach { asst ->
            val isSelected = asst.id == appState.activeAssistantId
            val shape = RoundedCornerShape(Radius.r12)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(p.cardBg)
                    .border(if (isSelected) 1.dp else LineWidth.hairline, if (isSelected) p.accent else p.border.copy(alpha = 0.75f), shape)
                    .clickable(role = Role.Button, onClickLabel = "Edit assistant") { editingAssistantId = asst.id }
                    .semantics { selected = isSelected }
                    .padding(Space.s16),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SepiaAvatarCircle(
                        symbol = asst.iconEmoji,
                        selected = isSelected,
                        sizeDp = 42.dp
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        BasicText(
                            text = asst.name,
                            style = p.uiHeaderStyle.copy(fontSize = 16.sp)
                        )
                        BasicText(
                            text = asst.systemPrompt.ifBlank { "No system prompt configured — click to edit" },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiLabelStyle.copy(color = p.narrationInk)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalAlignment = Alignment.CenterVertically) {
                    SepiaButton(
                        text = "📋",
                        contentDescription = "Duplicate assistant",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            val now = PlatformBridge.currentTimeMillis()
                            val copy = asst.copy(id = "asst-$now", name = "${asst.name} Copy")
                            onUpdateAssistants(appState.assistants + copy, appState.activeAssistantId)
                            onNotify("Duplicated '${asst.name}'.")
                        }
                    )
                    SepiaButton(
                        text = "🗑",
                        contentDescription = "Delete assistant",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            val remaining = appState.assistants.filterNot { it.id == asst.id }.ifEmpty {
                                listOf(AssistantAgent(id = "asst-default", name = "Default Assistant", iconEmoji = "💬"))
                            }
                            val nextActive = if (appState.activeAssistantId == asst.id) remaining.first().id else appState.activeAssistantId
                            onUpdateAssistants(remaining, nextActive)
                            onNotify("Deleted assistant '${asst.name}'.")
                        }
                    )
                }
            }
        }
    }
}
