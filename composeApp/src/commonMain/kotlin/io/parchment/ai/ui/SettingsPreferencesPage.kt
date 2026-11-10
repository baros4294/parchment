package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageNavVisibility
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.SendShortcutMode
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.model.ViewMode
import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.launch

// ============================================================================================
// SUBPAGE 1: PREFERENCES
// ============================================================================================
private val ACCENT_SWATCHES = listOf(
    "#8B5A2B" to "Brown", "#9E4730" to "Rust", "#3B5998" to "Blue", "#1F6F54" to "Green",
    "#5E4B8B" to "Purple", "#7A5216" to "Bronze", "#8C3823" to "Brick", "#2D3748" to "Slate"
)

@Composable
internal fun AccentSwatches(theme: ThemeConfig, onUpdateTheme: (ThemeConfig) -> Unit) {
    ACCENT_SWATCHES.forEach { (hex, name) ->
        SepiaAccentSwatch(
            color = parseHexColor(hex),
            name = "$name accent",
            selected = theme.accentHex.equals(hex, ignoreCase = true),
            onClick = { onUpdateTheme(theme.copy(name = "Custom Theme", accentHex = hex)) }
        )
    }
}

@Composable
internal fun PreferencesSubpage(
    prefs: PreferencesConfig,
    theme: ThemeConfig,
    activeSession: ChatSession,
    searchQuery: String,
    onUpdatePreferences: (PreferencesConfig) -> Unit,
    onUpdateTheme: (ThemeConfig) -> Unit,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    val scope = rememberCoroutineScope()
    var showCustomColorHexDrawer by remember { mutableStateOf(false) }

    fun matchesSearch(vararg terms: String): Boolean {
        val q = searchQuery.trim()
        if (q.isEmpty()) return true
        return terms.any { it.contains(q, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (win.isCompact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BasicText(text = "Preferences", style = p.uiHeaderStyle.copy(fontSize = 19.sp))

        if (matchesSearch("profile", "name", "picture", "avatar")) ProfileCard(prefs, onUpdatePreferences, onNotify)
        if (matchesSearch("spend", "budget", "limit", "cost")) SpendLimitCard(prefs, onUpdatePreferences)

        // 1. Appearance & Fonts
        if (matchesSearch("color mode", "theme", "font", "sepia", "dark", "light", "opacity", "size")) {
            SepiaSectionCard(title = "Appearance & fonts") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemePresetChoices(theme, onUpdateTheme)

                    SepiaDivider()

                    SepiaToggleRow(
                        title = "Reduce motion",
                        checked = prefs.reduceMotion,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(reduceMotion = it)) }
                    )

                    // Theme Swatches + Custom Hex Toggle (FlowRow so swatches & Hex button never clip on mobile!)
                    SepiaAdaptiveRow(label = "Accent color") {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(Modifier.selectableGroup(), itemVerticalAlignment = Alignment.CenterVertically) {
                            AccentSwatches(theme, onUpdateTheme)
                            SepiaButton(
                                text = if (showCustomColorHexDrawer) "▾ Hex" else "✎ Hex",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = { showCustomColorHexDrawer = !showCustomColorHexDrawer }
                            )
                        }
                    }

                    AnimatedVisibility(visible = showCustomColorHexDrawer) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (win.isCompact) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SepiaTextField(
                                        value = theme.canvasBgHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", canvasBgHex = it)) },
                                        label = "Canvas hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.cardBgHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", cardBgHex = it)) },
                                        label = "Card hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SepiaTextField(
                                        value = theme.primaryInkHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", primaryInkHex = it)) },
                                        label = "Ink hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.accentHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", accentHex = it)) },
                                        label = "Accent hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SepiaTextField(
                                        value = theme.canvasBgHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", canvasBgHex = it)) },
                                        label = "Canvas hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.cardBgHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", cardBgHex = it)) },
                                        label = "Card hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.primaryInkHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", primaryInkHex = it)) },
                                        label = "Ink hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.accentHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", accentHex = it)) },
                                        label = "Accent hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    SepiaDivider()

                    // App Font & Code Font (FlowRow so all font chips & .TTF/.OTF button fit on mobile!)
                    SepiaAdaptiveRow(label = "App / prose font") {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Inter", "Serif", "LaTeX", "JetBrainsMono Nerd").forEach { f ->
                                SepiaChip(
                                    label = f,
                                    selected = theme.customProseFontPath.isBlank() && theme.proseFontFamily.equals(f, ignoreCase = true),
                                    onClick = { onUpdateTheme(theme.copy(proseFontFamily = f, customProseFontPath = "")) }
                                )
                            }
                            SepiaButton(
                                text = "📂 .TTF/.OTF",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    scope.launch {
                                        val picked = PlatformBridge.pickFontFile("Select Prose Font (.ttf / .otf)")
                                        if (!picked.isNullOrBlank()) {
                                            onUpdateTheme(theme.copy(customProseFontPath = picked))
                                            onNotify("Loaded custom font: $picked")
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // Typography Sizes (No artificial clamping when typing! Stacked cleanly on mobile so labels never truncate)
                    var proseSizeText by remember(theme.proseFontSizeSp) { mutableStateOf(theme.proseFontSizeSp.toString()) }
                    var codeSizeText by remember(theme.codeFontSizeSp) { mutableStateOf(theme.codeFontSizeSp.toString()) }
                    var maxWidthText by remember(theme.maxReadingWidthDp) { mutableStateOf(theme.maxReadingWidthDp.toInt().toString()) }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SepiaTextField(
                            value = proseSizeText,
                            onValueChange = { v ->
                                proseSizeText = v
                                v.toFloatOrNull()?.takeIf { it > 4f }?.let { onUpdateTheme(theme.copy(proseFontSizeSp = it)) }
                            },
                            label = "Prose font size (sp)",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        SepiaTextField(
                            value = codeSizeText,
                            onValueChange = { v ->
                                codeSizeText = v
                                v.toFloatOrNull()?.takeIf { it > 4f }?.let { onUpdateTheme(theme.copy(codeFontSizeSp = it)) }
                            },
                            label = "Code font size (sp)",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    SepiaTextField(
                        value = maxWidthText,
                        onValueChange = { v ->
                            maxWidthText = v
                            v.toFloatOrNull()?.takeIf { it >= 300f }?.let { onUpdateTheme(theme.copy(maxReadingWidthDp = it)) }
                        },
                        label = "Max reading width (dp)",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 2. Chat Item Display (Matching Screenshots 4 & 5)
        if (matchesSearch("chat item display", "layout", "bubble", "page", "avatar", "timestamp", "tokens", "thinking cards", "tool cards", "model name", "provider")) {
            SepiaSectionCard(title = "Chat item display") {
                Column {
                    SepiaAdaptiveRow(label = "Message layout style") {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SepiaChip(
                                label = "Bubbles",
                                selected = prefs.defaultViewMode == ViewMode.EDITORIAL_TURNS,
                                onClick = {
                                    onUpdatePreferences(prefs.copy(defaultViewMode = ViewMode.EDITORIAL_TURNS))
                                    onUpdateSession { it.copy(viewMode = ViewMode.EDITORIAL_TURNS) }
                                }
                            )
                            SepiaChip(
                                label = "Page",
                                selected = prefs.defaultViewMode == ViewMode.MANUSCRIPT,
                                onClick = {
                                    onUpdatePreferences(prefs.copy(defaultViewMode = ViewMode.MANUSCRIPT))
                                    onUpdateSession { it.copy(viewMode = ViewMode.MANUSCRIPT) }
                                }
                            )
                        }
                    }
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show provider in model capsule",
                        checked = prefs.showProviderInModelCapsule,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showProviderInModelCapsule = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show assistant avatar in title bar",
                        checked = prefs.showAssistantAvatarInTitleBar,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showAssistantAvatarInTitleBar = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show user avatar",
                        checked = prefs.showUserAvatar,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserAvatar = it)) }
                    )
                    SepiaToggleRow(
                        title = "Show user name",
                        checked = prefs.showUserName,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserName = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show user timestamp",
                        checked = prefs.showUserTimestamp,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserTimestamp = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show user message actions",
                        checked = prefs.showUserMessageActions,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserMessageActions = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show model icon / avatar",
                        checked = prefs.showModelIcon,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showModelIcon = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show model name",
                        checked = prefs.showModelName,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showModelName = it)) }
                    )
                    SepiaToggleRow(
                        title = "Show model timestamp",
                        checked = prefs.showModelTimestamp,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showModelTimestamp = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show provider after model name",
                        checked = prefs.showProviderAfterModelName,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showProviderAfterModelName = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show usage stats",
                        checked = prefs.showTokenAndContextStats,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showTokenAndContextStats = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Count usage for the whole reply",
                        checked = prefs.showTokensForEntireTurn,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showTokensForEntireTurn = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show thinking cards",
                        checked = prefs.showThinkingCards,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showThinkingCards = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show tool cards",
                        checked = prefs.showToolCards,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showToolCards = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show thinking level on the button",
                        checked = prefs.showReasoningLevelOnButton,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showReasoningLevelOnButton = it)) }
                    )
                }
            }
        }

        // 3. Rendering Settings (Matching Screenshot 6)
        if (matchesSearch("rendering", "markdown", "math", "latex", "reasoning", "thinking", "code blocks", "dialogue")) {
            SepiaSectionCard(title = "Rendering settings") {
                Column {
                    SepiaToggleRow(
                        title = "Inline $...$ Rendering",
                        checked = prefs.inlineDollarMathRendering,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(inlineDollarMathRendering = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Math formula rendering",
                        checked = prefs.mathFormulaRendering,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(mathFormulaRendering = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Render user messages with Markdown",
                        checked = prefs.renderUserMessagesWithMarkdown,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(renderUserMessagesWithMarkdown = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Format thinking text (headings, math)",
                        checked = prefs.renderReasoningWithMarkdown,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(renderReasoningWithMarkdown = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Render assistant messages with Markdown",
                        checked = prefs.renderAssistantMessagesWithMarkdown,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(renderAssistantMessagesWithMarkdown = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Highlight spoken \"dialogue\" & *narration* ink",
                        checked = theme.highlightDialogueAndNarration,
                        onCheckedChange = { onUpdateTheme(theme.copy(highlightDialogueAndNarration = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Auto-collapse code blocks",
                        checked = prefs.autoCollapseCodeBlocks,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoCollapseCodeBlocks = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Collapse long messages (> 1,800 chars)",
                        checked = prefs.collapseLongMessages,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(collapseLongMessages = it)) }
                    )
                }
            }
        }

        // 4. Behavior & Startup (Matching Screenshots 7 & 8)
        if (matchesSearch("behavior", "startup", "thinking", "regenerate", "delete", "navigation", "minimap", "shortcut", "paste", "battery", "background", "scroll")) {
            SepiaSectionCard(title = "Behavior & startup") {
                Column {
                    if (PlatformBridge.isAndroid) {
                        val isUnrestricted = remember { PlatformBridge.isIgnoringBatteryOptimizations() }
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false).padding(end = 10.dp)) {
                                BasicText(
                                    text = "Android unrestricted background mode",
                                    style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, color = p.accent)
                                )
                            }
                            SepiaButton(
                                text = if (isUnrestricted) "✓ Unrestricted" else "⚡ Allow Now",
                                compact = true,
                                style = if (isUnrestricted) SepiaButtonStyle.OUTLINE else SepiaButtonStyle.PRIMARY,
                                onClick = { PlatformBridge.requestUnrestrictedBackgroundBattery() }
                            )
                        }
                        SepiaDivider()
                    }

                    SepiaToggleRow(
                        title = "Auto switch to topics",
                        checked = prefs.autoSwitchToTopics,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoSwitchToTopics = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Auto-collapse thinking",
                        checked = prefs.autoCollapseThinking,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoCollapseThinking = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show tool result summary",
                        checked = prefs.showToolResultSummary,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showToolResultSummary = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Delete messages below when regenerating",
                        checked = prefs.deleteMessagesBelowWhenRegenerating,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(deleteMessagesBelowWhenRegenerating = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Delete previous message when regenerating",
                        checked = prefs.deletePreviousMessageWhenRegenerating,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(deletePreviousMessageWhenRegenerating = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Confirm before regenerating",
                        checked = prefs.confirmBeforeRegenerating,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(confirmBeforeRegenerating = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Confirm before deleting or clearing chats",
                        checked = prefs.confirmBeforeDeletingChat,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(confirmBeforeDeletingChat = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Keep message versions when branching",
                        checked = prefs.keepMessageVersionsWhenForking,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(keepMessageVersionsWhenForking = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show chat list dates",
                        checked = prefs.showChatListDates,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showChatListDates = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "New chat when switching assistants",
                        checked = prefs.newChatWhenSwitchingAssistants,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(newChatWhenSwitchingAssistants = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "New chat after deleting topic",
                        checked = prefs.newChatAfterDeletingTopic,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(newChatAfterDeletingTopic = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "New chat on launch",
                        checked = prefs.newChatOnLaunch,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(newChatOnLaunch = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Auto-scroll to bottom when opening a chat",
                        checked = prefs.autoScrollToBottomOnOpen,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoScrollToBottomOnOpen = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Auto-follow bottom while streaming",
                        checked = prefs.autoFollowBottom,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoFollowBottom = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Shorten the conversation automatically when it's 85% full",
                        checked = prefs.autoCompactWhenNearContextLimit,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoCompactWhenNearContextLimit = it)) }
                    )
                    SepiaDivider()
                    // Message Navigation Buttons (Minimap) Visibility
                    SepiaAdaptiveRow(label = "Message navigation minimap") {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MessageNavVisibility.entries.forEach { mode ->
                                SepiaChip(
                                    label = mode.label,
                                    selected = prefs.messageNavButtons == mode,
                                    onClick = { onUpdatePreferences(prefs.copy(messageNavButtons = mode)) }
                                )
                            }
                        }
                    }
                    SepiaDivider()

                    // Send Shortcut
                    SepiaAdaptiveRow(label = "Send shortcut (desktop)") {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SendShortcutMode.entries.forEach { mode ->
                                SepiaChip(
                                    label = mode.label,
                                    selected = prefs.sendShortcut == mode,
                                    onClick = { onUpdatePreferences(prefs.copy(sendShortcut = mode)) }
                                )
                            }
                        }
                    }
                    SepiaDivider()

                    SepiaToggleRow(
                        title = "Paste long text as file attachment",
                        checked = prefs.pasteLongTextAsFile,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(pasteLongTextAsFile = it)) }
                    )
                    if (prefs.pasteLongTextAsFile) {
                        var threshStr by remember(prefs.pasteConversionThresholdChars) {
                            mutableStateOf(prefs.pasteConversionThresholdChars.toString())
                        }
                        SepiaTextField(
                            value = threshStr,
                            onValueChange = { v ->
                                threshStr = v
                                v.toIntOrNull()?.takeIf { it > 0 }?.let {
                                    onUpdatePreferences(prefs.copy(pasteConversionThresholdChars = it))
                                }
                            },
                            label = "Conversion threshold (characters)",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
