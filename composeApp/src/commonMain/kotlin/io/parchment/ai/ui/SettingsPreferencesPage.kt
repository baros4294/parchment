package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import io.parchment.ai.strings.Strings
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
import io.parchment.ai.platform.PlatformPickers
import io.parchment.ai.platform.PlatformRuntime
import kotlinx.coroutines.launch

// ============================================================================================
// Settings page 1: Preferences.
// ============================================================================================
private val ACCENT_SWATCHES = listOf(
    "#8B5A2B" to Strings.brown, "#9E4730" to Strings.rust, "#3B5998" to Strings.blue, "#1F6F54" to Strings.green,
    "#5E4B8B" to Strings.purple, "#7A5216" to Strings.bronze, "#8C3823" to Strings.brick, "#2D3748" to Strings.slate
)

@Composable
internal fun AccentSwatches(theme: ThemeConfig, onUpdateTheme: (ThemeConfig) -> Unit) {
    ACCENT_SWATCHES.forEach { (hex, name) ->
        SepiaAccentSwatch(
            color = parseHexColor(hex),
            name = "$name accent",
            selected = theme.accentHex.equals(hex, ignoreCase = true),
            onClick = { onUpdateTheme(theme.copy(name = Strings.customTheme, accentHex = hex)) }
        )
    }
}

private val TEXT_FONTS = listOf("Inter", "Serif", "LaTeX", "JetBrains Mono")
private val CODE_FONTS = listOf("JetBrains Mono", "Inter", "Serif")

/** The text and code font pickers, shared by Preferences and settings search. */
@Composable
internal fun FontChoiceRows(theme: ThemeConfig, onUpdateTheme: (ThemeConfig) -> Unit, onNotify: (String) -> Unit) {
    FontChoiceRow(
        label = Strings.textFont,
        fonts = TEXT_FONTS,
        family = theme.proseFontFamily,
        customPath = theme.customProseFontPath,
        onChoose = { onUpdateTheme(theme.copy(proseFontFamily = it, customProseFontPath = "")) },
        onCustom = { onUpdateTheme(theme.copy(customProseFontPath = it)) },
        onNotify = onNotify
    )
    FontChoiceRow(
        label = Strings.codeFont,
        fonts = CODE_FONTS,
        family = theme.codeFontFamily,
        customPath = theme.customCodeFontPath,
        onChoose = { onUpdateTheme(theme.copy(codeFontFamily = it, customCodeFontPath = "")) },
        onCustom = { onUpdateTheme(theme.copy(customCodeFontPath = it)) },
        onNotify = onNotify
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FontChoiceRow(
    label: String,
    fonts: List<String>,
    family: String,
    customPath: String,
    onChoose: (String) -> Unit,
    onCustom: (String) -> Unit,
    onNotify: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val customName = customPath.substringAfterLast('/').substringAfterLast('\\')
    SepiaAdaptiveRow(label = label) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s6)) {
            fonts.forEach { f ->
                SepiaChip(label = f, selected = customPath.isBlank() && canonicalFontName(family) == canonicalFontName(f), onClick = { onChoose(f) })
            }
            if (customName.isNotBlank()) SepiaChip(label = customName, selected = true, onClick = {})
            SepiaButton(
                text = Strings.ttfOtf,
                compact = true,
                style = SepiaButtonStyle.OUTLINE,
                onClick = {
                    scope.launch {
                        val picked = PlatformPickers.pickFontFile(Strings.chooseATtfOrOtf(label.lowercase()))
                        if (!picked.isNullOrBlank()) {
                            onCustom(picked)
                            onNotify(Strings.setTo(label, picked.substringAfterLast('/').substringAfterLast('\\')))
                        }
                    }
                }
            )
        }
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
        verticalArrangement = Arrangement.spacedBy(Space.s16)
    ) {
        BasicText(text = Strings.preferences, style = p.uiHeaderStyle.copy(fontSize = 19.sp))

        if (matchesSearch("profile", "name", "picture", "avatar")) ProfileCard(prefs, onUpdatePreferences, onNotify)
        if (matchesSearch("spend", "budget", "limit", "cost")) SpendLimitCard(prefs, onUpdatePreferences)

        // 1. Appearance & Fonts
        if (matchesSearch("color mode", "theme", "font", "sepia", "dark", "light", "opacity", "size")) {
            SepiaSectionCard(title = Strings.appearanceFonts) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                    ThemePresetChoices(theme, onUpdateTheme)

                    SepiaDivider()

                    SepiaToggleRow(
                        title = Strings.reduceMotion,
                        checked = prefs.reduceMotion,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(reduceMotion = it)) }
                    )

                    // Accent swatches and a custom hex color, in a wrapping row so nothing clips on a phone.
                    SepiaAdaptiveRow(label = Strings.accentColor) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(Modifier.selectableGroup(), itemVerticalAlignment = Alignment.CenterVertically) {
                            AccentSwatches(theme, onUpdateTheme)
                            SepiaButton(
                                text = if (showCustomColorHexDrawer) Strings.hex else Strings.hex2,
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = { showCustomColorHexDrawer = !showCustomColorHexDrawer }
                            )
                        }
                    }

                    AnimatedVisibility(visible = showCustomColorHexDrawer) {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                            if (win.isCompact) {
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                                    SepiaTextField(
                                        value = theme.canvasBgHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = Strings.custom2, canvasBgHex = it)) },
                                        label = Strings.canvasHex,
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.cardBgHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = Strings.custom2, cardBgHex = it)) },
                                        label = Strings.cardHex,
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                                    SepiaTextField(
                                        value = theme.primaryInkHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = Strings.custom2, primaryInkHex = it)) },
                                        label = Strings.inkHex,
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.accentHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = Strings.custom2, accentHex = it)) },
                                        label = Strings.accentHex,
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                                    SepiaTextField(
                                        value = theme.canvasBgHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = Strings.custom2, canvasBgHex = it)) },
                                        label = Strings.canvasHex,
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.cardBgHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = Strings.custom2, cardBgHex = it)) },
                                        label = Strings.cardHex,
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.primaryInkHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = Strings.custom2, primaryInkHex = it)) },
                                        label = Strings.inkHex,
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.accentHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = Strings.custom2, accentHex = it)) },
                                        label = Strings.accentHex,
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    SepiaDivider()

                    FontChoiceRows(theme, onUpdateTheme, onNotify)

                    // Text sizes: values aren't clamped while typing, and fields stack on phones so labels never truncate.
                    var proseSizeText by remember(theme.proseFontSizeSp) { mutableStateOf(theme.proseFontSizeSp.toString()) }
                    var codeSizeText by remember(theme.codeFontSizeSp) { mutableStateOf(theme.codeFontSizeSp.toString()) }
                    var maxWidthText by remember(theme.maxReadingWidthDp) { mutableStateOf(theme.maxReadingWidthDp.toInt().toString()) }

                    Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                        SepiaTextField(
                            value = proseSizeText,
                            onValueChange = { v ->
                                proseSizeText = v
                                v.toFloatOrNull()?.takeIf { it > 4f }?.let { onUpdateTheme(theme.copy(proseFontSizeSp = it)) }
                            },
                            label = Strings.proseFontSizeSp,
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        SepiaTextField(
                            value = codeSizeText,
                            onValueChange = { v ->
                                codeSizeText = v
                                v.toFloatOrNull()?.takeIf { it > 4f }?.let { onUpdateTheme(theme.copy(codeFontSizeSp = it)) }
                            },
                            label = Strings.codeFontSizeSp,
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
                        label = Strings.maxReadingWidthDp,
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 2. Chat Item Display (Matching Screenshots 4 & 5)
        if (matchesSearch("chat item display", "layout", "bubble", "page", "avatar", "timestamp", "tokens", "thinking cards", "tool cards", "model name", "provider")) {
            SepiaSectionCard(title = Strings.chatItemDisplay) {
                Column {
                    SepiaAdaptiveRow(label = Strings.messageLayoutStyle) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.s6),
                            verticalArrangement = Arrangement.spacedBy(Space.s6)
                        ) {
                            SepiaChip(
                                label = Strings.bubbles,
                                selected = prefs.defaultViewMode == ViewMode.EDITORIAL_TURNS,
                                onClick = {
                                    onUpdatePreferences(prefs.copy(defaultViewMode = ViewMode.EDITORIAL_TURNS))
                                    onUpdateSession { it.copy(viewMode = ViewMode.EDITORIAL_TURNS) }
                                }
                            )
                            SepiaChip(
                                label = Strings.page,
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
                        title = Strings.showProviderInModelCapsule,
                        checked = prefs.showProviderInModelCapsule,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showProviderInModelCapsule = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showAssistantAvatarInTitle,
                        checked = prefs.showAssistantAvatarInTitleBar,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showAssistantAvatarInTitleBar = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showUserAvatar,
                        checked = prefs.showUserAvatar,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserAvatar = it)) }
                    )
                    SepiaToggleRow(
                        title = Strings.showUserName,
                        checked = prefs.showUserName,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserName = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showUserTimestamp,
                        checked = prefs.showUserTimestamp,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserTimestamp = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showUserMessageActions,
                        checked = prefs.showUserMessageActions,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserMessageActions = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showModelIconAvatar,
                        checked = prefs.showModelIcon,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showModelIcon = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showModelName,
                        checked = prefs.showModelName,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showModelName = it)) }
                    )
                    SepiaToggleRow(
                        title = Strings.showModelTimestamp,
                        checked = prefs.showModelTimestamp,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showModelTimestamp = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showProviderAfterModelName,
                        checked = prefs.showProviderAfterModelName,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showProviderAfterModelName = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showUsageStats,
                        checked = prefs.showTokenAndContextStats,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showTokenAndContextStats = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.countUsageForTheWhole,
                        checked = prefs.showTokensForEntireTurn,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showTokensForEntireTurn = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showThinkingCards,
                        checked = prefs.showThinkingCards,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showThinkingCards = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showToolCards,
                        checked = prefs.showToolCards,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showToolCards = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showThinkingLevelOnThe,
                        checked = prefs.showReasoningLevelOnButton,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showReasoningLevelOnButton = it)) }
                    )
                }
            }
        }

        // 3. Rendering Settings (Matching Screenshot 6)
        if (matchesSearch("rendering", "markdown", "math", "latex", "reasoning", "thinking", "code blocks", "dialogue")) {
            SepiaSectionCard(title = Strings.renderingSettings) {
                Column {
                    SepiaToggleRow(
                        title = Strings.inlineRendering,
                        checked = prefs.inlineDollarMathRendering,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(inlineDollarMathRendering = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.mathFormulaRendering,
                        checked = prefs.mathFormulaRendering,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(mathFormulaRendering = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.renderUserMessagesWithMarkdown,
                        checked = prefs.renderUserMessagesWithMarkdown,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(renderUserMessagesWithMarkdown = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.formatThinkingTextHeadingsMath,
                        checked = prefs.renderReasoningWithMarkdown,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(renderReasoningWithMarkdown = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.renderAssistantMessagesWithMarkdown,
                        checked = prefs.renderAssistantMessagesWithMarkdown,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(renderAssistantMessagesWithMarkdown = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.highlightSpokenDialogueNarrationInk,
                        checked = theme.highlightDialogueAndNarration,
                        onCheckedChange = { onUpdateTheme(theme.copy(highlightDialogueAndNarration = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.autoCollapseCodeBlocks,
                        checked = prefs.autoCollapseCodeBlocks,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoCollapseCodeBlocks = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.collapseLongMessages1800,
                        checked = prefs.collapseLongMessages,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(collapseLongMessages = it)) }
                    )
                }
            }
        }

        // 4. Behavior & Startup (Matching Screenshots 7 & 8)
        if (matchesSearch("behavior", "startup", "thinking", "regenerate", "delete", "navigation", "minimap", "shortcut", "paste", "battery", "background", "scroll")) {
            SepiaSectionCard(title = Strings.behaviorStartup) {
                Column {
                    if (PlatformRuntime.isAndroid) {
                        val isUnrestricted = remember { PlatformRuntime.isIgnoringBatteryOptimizations() }
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(vertical = Space.s8),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(Space.s8)
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false).padding(end = Space.s10)) {
                                BasicText(
                                    text = Strings.androidUnrestrictedBackgroundMode,
                                    style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, color = p.accent)
                                )
                            }
                            SepiaButton(
                                text = if (isUnrestricted) Strings.unrestricted else Strings.allowNow,
                                compact = true,
                                style = if (isUnrestricted) SepiaButtonStyle.OUTLINE else SepiaButtonStyle.PRIMARY,
                                onClick = { PlatformRuntime.requestUnrestrictedBackgroundBattery() }
                            )
                        }
                        SepiaDivider()
                    }

                    SepiaToggleRow(
                        title = Strings.autoSwitchToTopics,
                        checked = prefs.autoSwitchToTopics,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoSwitchToTopics = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.autoCollapseThinking,
                        checked = prefs.autoCollapseThinking,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoCollapseThinking = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showToolResultSummary,
                        checked = prefs.showToolResultSummary,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showToolResultSummary = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.deleteMessagesBelowWhenRegenerating,
                        checked = prefs.deleteMessagesBelowWhenRegenerating,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(deleteMessagesBelowWhenRegenerating = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.deletePreviousMessageWhenRegenerating,
                        checked = prefs.deletePreviousMessageWhenRegenerating,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(deletePreviousMessageWhenRegenerating = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.confirmBeforeRegenerating,
                        checked = prefs.confirmBeforeRegenerating,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(confirmBeforeRegenerating = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.confirmBeforeDeletingOrClearing,
                        checked = prefs.confirmBeforeDeletingChat,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(confirmBeforeDeletingChat = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.keepMessageVersionsWhenBranching,
                        checked = prefs.keepMessageVersionsWhenForking,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(keepMessageVersionsWhenForking = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.showChatListDates,
                        checked = prefs.showChatListDates,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showChatListDates = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.newChatWhenSwitchingAssistants,
                        checked = prefs.newChatWhenSwitchingAssistants,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(newChatWhenSwitchingAssistants = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.newChatAfterDeletingTopic,
                        checked = prefs.newChatAfterDeletingTopic,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(newChatAfterDeletingTopic = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.newChatOnLaunch,
                        checked = prefs.newChatOnLaunch,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(newChatOnLaunch = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.autoScrollToBottomWhen,
                        checked = prefs.autoScrollToBottomOnOpen,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoScrollToBottomOnOpen = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.autoFollowBottomWhileStreaming,
                        checked = prefs.autoFollowBottom,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoFollowBottom = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = Strings.shortenTheConversationAutomaticallyWhen,
                        checked = prefs.autoCompactWhenNearContextLimit,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoCompactWhenNearContextLimit = it)) }
                    )
                    SepiaDivider()
                    // Message Navigation Buttons (Minimap) Visibility
                    SepiaAdaptiveRow(label = Strings.messageNavigationMinimap) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.s6),
                            verticalArrangement = Arrangement.spacedBy(Space.s6)
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
                    SepiaAdaptiveRow(label = Strings.sendShortcutDesktop) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.s6),
                            verticalArrangement = Arrangement.spacedBy(Space.s6)
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
                        title = Strings.pasteLongTextAsFile,
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
                            label = Strings.conversionThresholdCharacters,
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
