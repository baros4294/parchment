package io.parchment.ai.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.DefaultModelConfig
import io.parchment.ai.model.MessageNavVisibility
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.keepsProviderState
import io.parchment.ai.model.SendShortcutMode
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.model.ViewMode
import io.parchment.ai.network.OtaUpdateEngine
import kotlinx.coroutines.launch

// ============================================================================================
// Settings search, item by item
// Searches across Preferences, Providers, Models, Assistants, Default Models, Workspace,
// World Book / Lorebook, and Backup / OTA with interactive inline controls and 1-tap tab jumps!
// ============================================================================================
private fun matchesSearchTokens(tokens: List<String>, vararg terms: String): Boolean {
    if (tokens.isEmpty()) return true
    val combined = terms.joinToString(" ").lowercase()
    return tokens.all { tok -> combined.contains(tok) }
}

internal fun countCategorySearchMatches(
    category: SettingsCategory,
    rawQuery: String,
    appState: AppStateSnapshot,
    activeProfile: ProviderProfile,
    activeSession: ChatSession
): Int {
    val tokens = rawQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return 0
    fun hit(vararg terms: String) = matchesSearchTokens(tokens, *terms)

    return when (category) {
        SettingsCategory.PREFERENCES -> {
            val items = listOf(
                hit("Color Mode & Preset", "Appearance & Fonts", "theme", "sepia", "dark", "light", "ivory", "oxford", "candlelight", "midnight", "oled"),
                hit("Accent Color", "Appearance & Fonts", "swatch", "hex", "canvas", "ink", "card color"),
                hit("Text Font", "Code Font", "App / Prose Font", "Appearance & Fonts", "inter", "serif", "latex", "jetbrainsmono", "monospace", "ttf", "otf", "custom font"),
                hit("Prose Font Size", "Code Font Size", "Max Reading Width", "Appearance & Fonts", "sp", "dp", "typography"),
                hit("Message Layout Style", "Card Bubbles", "Clean Page", "Chat item display", "manuscript", "editorial turns", "view mode"),
                hit("Show provider in model capsule", "Chat item display", "header", "top bar"),
                hit("Show Assistant Avatar in Title Bar", "Chat item display", "assistant icon", "top bar", "avatar"),
                hit("Show User Avatar", "Chat item display", "user icon", "profile", "avatar"),
                hit("Show User Name", "Chat item display", "username", "author"),
                hit("Show User Timestamp", "Chat item display", "time", "date", "clock", "user"),
                hit("Show User Message Actions", "Chat item display", "copy", "edit", "branch", "buttons"),
                hit("Show Model Icon / Avatar", "Chat item display", "assistant avatar", "bot icon", "avatar"),
                hit("Show Model Name", "Chat item display", "assistant name", "ai name"),
                hit("Show Model Timestamp", "Chat item display", "assistant time", "date", "clock", "model"),
                hit("Show Provider After Model Name", "Chat item display", "provider badge"),
                hit("Show Token & Context Stats", "Chat item display", "telemetry", "speed", "tok/s", "cost", "context ring"),
                hit("Count usage for the whole reply", "Chat item display", "input tokens", "output tokens", "cache", "ttft"),
                hit("Show Thinking Cards", "Chat item display", "reasoning drawer", "thoughts"),
                hit("Show Tool Cards", "Chat item display", "workspace tools", "function call"),
                hit("Show thinking level on the button", "Chat item display", "thinking effort", "composer chip"),
                hit("Inline $...$ Rendering", "Rendering settings", "math", "latex", "dollar"),
                hit("Math Formula Rendering", "Rendering settings", "latex", "katex", "equations"),
                hit("Render user messages with Markdown", "Rendering settings", "formatting", "user"),
                hit("Format thinking text (headings, math)", "Rendering settings", "thoughts", "formatting"),
                hit("Render assistant messages with Markdown", "Rendering settings", "formatting", "prose", "assistant"),
                hit("Highlight spoken dialogue & narration ink", "Rendering settings", "quotes", "italics", "roleplay"),
                hit("Auto-collapse Code Blocks", "Rendering settings", "fenced code"),
                hit("Collapse Long Messages", "Rendering settings", "truncate", "expand", "read more"),
                hit("Auto switch to Topics", "Behavior & startup", "sidebar", "chats tab", "assistant"),
                hit("Auto-collapse Thinking", "Behavior & startup", "reasoning drawer", "thoughts"),
                hit("Show Tool Result Summary", "Behavior & startup", "tool output", "agent"),
                hit("Delete messages below when regenerating", "Behavior & startup", "redo", "prune"),
                hit("Delete previous message when regenerating", "Behavior & startup", "replace", "swipe", "variant"),
                hit("Confirm before regenerating", "Behavior & startup", "redo", "dialog", "prompt"),
                hit("Confirm before deleting or clearing chats", "Behavior & startup", "trash", "dialog", "warning"),
                hit("Keep Message Versions When Branching", "Behavior & startup", "branch", "swipes", "variants"),
                hit("Show Chat List Dates", "Behavior & startup", "sidebar", "timestamp", "time", "date"),
                hit("New chat when switching assistants", "Behavior & startup", "sidebar", "assistant"),
                hit("New chat after deleting topic", "Behavior & startup", "trash", "session"),
                hit("New Chat on Launch", "Behavior & startup", "startup", "boot", "open"),
                hit("Auto-follow bottom while streaming", "Behavior & startup", "scroll", "autoscroll"),
                hit("Shorten the conversation automatically when it's 85% full", "Behavior & startup", "compaction", "context window", "summarize", "token limit"),
                hit("Message Navigation Minimap", "Behavior & startup", "tree", "scroll buttons", "map"),
                hit("Send Shortcut (Desktop)", "Behavior & startup", "enter", "ctrl+enter", "shift+enter", "keyboard"),
                hit("Paste long text as file attachment", "Conversion threshold", "Behavior & startup", "clipboard")
            )
            items.count { it }
        }
        SettingsCategory.PROVIDERS -> {
            val modelHits = appState.profiles.sumOf { prof ->
                prof.savedModels.count { m ->
                    tokens.all { tok -> m.lowercase().contains(tok) || prof.name.lowercase().contains(tok) }
                }
            }
            val fieldHits = listOf(
                hit("API Key", "Providers", "token", "auth", "bearer", "credential", activeProfile.name),
                hit("Server URL", "Base URL", "Endpoint Path Override", "Providers", "proxy", "host", activeProfile.baseUrl),
                hit("Stateful Interaction Chaining", "previous_interaction_id", "previous_response_id", "Providers"),
                hit("Background Poll & Stream Recovery", "long decode", "timeout", "Providers"),
                hit("Smart Legacy Endpoint Fallback", "useLegacyFallbackEndpoint", "generateContent", "chat/completions", "Providers"),
                hit("Temperature", "Top-P", "Max Output Tokens", "Thinking Effort", "sampling", "Providers"),
                hit("Web Search", "Code Execution", "URL Context", "Image Generation", "Video Generation", "google_search", "Providers"),
                hit("Custom HTTP Headers", "Raw JSON Extra Body", "Auth Header Mode", "Providers")
            ).count { it }
            fieldHits + modelHits.coerceAtMost(10)
        }
        SettingsCategory.ASSISTANTS -> {
            appState.assistants.count { asst ->
                hit(asst.name, asst.systemPrompt, asst.authorsNote, "assistant", "persona", "system prompt", "author's note")
            }
        }
        SettingsCategory.DEFAULT_MODEL -> {
            listOf(
                hit("Global Default Chat Model", "Default Model", "primary model"),
                hit("Title Generation", "Topic Naming", "Default Model", "prompt template"),
                hit("Summary & Context Compression", "Default Model", "compact context"),
                hit("Translation Model", "Target Language", "Default Model")
            ).count { it }
        }
        SettingsCategory.WORKSPACE -> {
            listOf(
                hit("Agent approvals and file access", "Agent permissions", "assistant defaults", "inherit", "web access", "network", "sandbox", "power", "keep awake", "tools", "Workspace Mode", "Read-Only", "Agent R/W", "fs_read", "fs_edit", "todo_write"),
                hit("Shell full process access", "shell_exec", "terminal", "powershell", "bash", "Workspace")
            ).count { it }
        }
        SettingsCategory.WORLD_BOOK -> {
            val loreHits = (activeSession.fragments + appState.globalWorldBook).distinctBy { it.id }.count { frag ->
                hit(frag.title, frag.content, frag.keywords.joinToString(" "), "world book", "lorebook", "fragment")
            }
            val cardHits = if (hit("Character Card", "PNG", "JSON", "ccv3", "chara", "System Prompt Preset", "World Book")) 1 else 0
            loreHits + cardHits
        }
        SettingsCategory.BACKUP_OTA -> {
            listOf(
                hit("Check for Updates at Launch", "GitHub Release Repository", "Check for Update Now", "Backup & Updates", "OTA", "version"),
                hit("Export Full Backup JSON", "Restore Backup JSON", "Wipe All Local Data", "Backup & Updates", "OTA", "storage")
            ).count { it }
        }
    }
}

@Composable
internal fun GlobalSettingsSearchResultsSubpage(
    searchQuery: String,
    appState: AppStateSnapshot,
    activeProfile: ProviderProfile,
    activeSession: ChatSession,
    onJumpToCategory: (SettingsCategory) -> Unit,
    onSelectProfile: (String) -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onUpdateProfileById: (String, (ProviderProfile) -> ProviderProfile) -> Unit,
    onUpdatePreferences: (PreferencesConfig) -> Unit,
    onUpdateTheme: (ThemeConfig) -> Unit,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onUpdateDefaultModels: (DefaultModelConfig) -> Unit,
    onUpdateOtaSettings: (String, Boolean) -> Unit,
    onCheckOtaNow: () -> Unit,
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    val prefs = appState.preferences
    val theme = appState.theme
    val tokens = remember(searchQuery) {
        searchQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    fun hit(vararg terms: String): Boolean = matchesSearchTokens(tokens, *terms)

    // 1. PREFERENCES INDIVIDUAL ITEM MATCHES (Every single setting in PreferencesSubpage!)
    val hitColorMode = hit("Color Mode & Preset", "Appearance & Fonts", "theme", "sepia", "dark", "light", "ivory", "oxford", "candlelight", "midnight", "oled")
    val hitAccentColor = hit("Accent Color", "Appearance & Fonts", "swatch", "hex", "canvas", "ink", "card color")
    val hitAppFont = hit("Text Font", "Code Font", "App / Prose Font", "Appearance & Fonts", "inter", "serif", "latex", "jetbrainsmono", "monospace", "ttf", "otf", "custom font")
    val hitFontSizes = hit("Prose Font Size", "Code Font Size", "Max Reading Width", "Appearance & Fonts", "sp", "dp", "typography")

    val hitLayoutStyle = hit("Message Layout Style", "Card Bubbles", "Clean Page", "Chat item display", "manuscript", "editorial turns", "view mode")
    val hitProviderCapsule = hit("Show provider in model capsule", "Chat item display", "header", "top bar")
    val hitTitleBarAvatar = hit("Show Assistant Avatar in Title Bar", "Chat item display", "assistant icon", "top bar", "avatar")
    val hitUserAvatar = hit("Show User Avatar", "Chat item display", "user icon", "profile", "avatar")
    val hitUserName = hit("Show User Name", "Chat item display", "username", "author")
    val hitUserTimestamp = hit("Show User Timestamp", "Chat item display", "time", "date", "clock", "user")
    val hitUserActions = hit("Show User Message Actions", "Chat item display", "copy", "edit", "branch", "buttons")
    val hitModelIcon = hit("Show Model Icon / Avatar", "Chat item display", "assistant avatar", "bot icon", "avatar")
    val hitModelName = hit("Show Model Name", "Chat item display", "assistant name", "ai name")
    val hitModelTimestamp = hit("Show Model Timestamp", "Chat item display", "assistant time", "date", "clock", "model")
    val hitProviderAfterModel = hit("Show Provider After Model Name", "Chat item display", "provider badge")
    val hitTokenStats = hit("Show Token & Context Stats", "Chat item display", "telemetry", "speed", "tok/s", "cost", "context ring")
    val hitTokensEntireTurn = hit("Count usage for the whole reply", "Chat item display", "input tokens", "output tokens", "cache", "ttft")
    val hitThinkingCards = hit("Show Thinking Cards", "Chat item display", "reasoning drawer", "thoughts")
    val hitToolCards = hit("Show Tool Cards", "Chat item display", "workspace tools", "function call")
    val hitReasoningButton = hit("Show thinking level on the button", "Chat item display", "thinking effort", "composer chip")

    val hitInlineDollar = hit("Inline $...$ Rendering", "Rendering settings", "math", "latex", "dollar")
    val hitMathFormula = hit("Math Formula Rendering", "Rendering settings", "latex", "katex", "equations")
    val hitUserMd = hit("Render user messages with Markdown", "Rendering settings", "formatting", "user")
    val hitReasoningMd = hit("Format thinking text (headings, math)", "Rendering settings", "thoughts", "formatting")
    val hitAssistantMd = hit("Render assistant messages with Markdown", "Rendering settings", "formatting", "prose", "assistant")
    val hitDialogueInk = hit("Highlight spoken dialogue & narration ink", "Rendering settings", "quotes", "italics", "roleplay")
    val hitAutoCollapseCode = hit("Auto-collapse Code Blocks", "Rendering settings", "fenced code")
    val hitCollapseLong = hit("Collapse Long Messages", "Rendering settings", "truncate", "expand", "read more")

    val hitAutoTopics = hit("Auto switch to Topics", "Behavior & startup", "sidebar", "chats tab", "assistant")
    val hitAutoCollapseThink = hit("Auto-collapse Thinking", "Behavior & startup", "reasoning drawer", "thoughts")
    val hitToolSummary = hit("Show Tool Result Summary", "Behavior & startup", "tool output", "agent")
    val hitDeleteBelowRegen = hit("Delete messages below when regenerating", "Behavior & startup", "redo", "prune")
    val hitDeletePrevRegen = hit("Delete previous message when regenerating", "Behavior & startup", "replace", "swipe", "variant")
    val hitConfirmRegen = hit("Confirm before regenerating", "Behavior & startup", "redo", "dialog", "prompt")
    val hitConfirmDelete = hit("Confirm before deleting or clearing chats", "Behavior & startup", "trash", "dialog", "warning")
    val hitKeepForkVersions = hit("Keep Message Versions When Branching", "Behavior & startup", "branch", "swipes", "variants")
    val hitChatListDates = hit("Show Chat List Dates", "Behavior & startup", "sidebar", "timestamp", "time", "date")
    val hitNewChatSwitchAsst = hit("New chat when switching assistants", "Behavior & startup", "sidebar", "assistant")
    val hitNewChatDeleteTopic = hit("New chat after deleting topic", "Behavior & startup", "trash", "session")
    val hitNewChatLaunch = hit("New Chat on Launch", "Behavior & startup", "startup", "boot", "open")
    val hitAutoScrollOnOpen = hit("Auto-scroll to bottom when opening a chat", "Behavior & startup", "scroll", "bottom", "latest", "open")
    val hitAutoFollowBottom = hit("Auto-follow bottom while streaming", "Behavior & startup", "scroll", "autoscroll")
    val hitAutoCompactLimit = hit("Shorten the conversation automatically when it's 85% full", "Behavior & startup", "compaction", "context window", "summarize", "token limit")
    val hitNavMinimap = hit("Message Navigation Minimap", "Behavior & startup", "tree", "scroll buttons", "map")
    val hitSendShortcut = hit("Send Shortcut (Desktop)", "Behavior & startup", "enter", "ctrl+enter", "shift+enter", "keyboard")
    val hitPasteLongFile = hit("Paste long text as file attachment", "Conversion threshold", "Behavior & startup", "clipboard")

    val anyPrefMatch = listOf(
        hitColorMode, hitAccentColor, hitAppFont, hitFontSizes,
        hitLayoutStyle, hitProviderCapsule, hitTitleBarAvatar, hitUserAvatar, hitUserName, hitUserTimestamp,
        hitUserActions, hitModelIcon, hitModelName, hitModelTimestamp, hitProviderAfterModel, hitTokenStats,
        hitTokensEntireTurn, hitThinkingCards, hitToolCards, hitReasoningButton,
        hitInlineDollar, hitMathFormula, hitUserMd, hitReasoningMd, hitAssistantMd, hitDialogueInk,
        hitAutoCollapseCode, hitCollapseLong,
        hitAutoTopics, hitAutoCollapseThink, hitToolSummary, hitDeleteBelowRegen, hitDeletePrevRegen,
        hitConfirmRegen, hitConfirmDelete, hitKeepForkVersions, hitChatListDates, hitNewChatSwitchAsst,
        hitNewChatDeleteTopic, hitNewChatLaunch, hitAutoScrollOnOpen, hitAutoFollowBottom, hitAutoCompactLimit, hitNavMinimap, hitSendShortcut, hitPasteLongFile
    ).any { it }

    // 2. PROVIDERS & MODELS MATCHES
    val hitApiKey = hit("API Key", "Providers", "token", "auth", "bearer", "credential", activeProfile.name)
    val hitBaseUrl = hit("Base URL", "Endpoint Path Override", "Providers", "proxy", "host", activeProfile.baseUrl)
    val hitStateful = hit("Stateful Interaction Chaining", "previous_interaction_id", "previous_response_id", "cache", "Providers", PROVIDER_STORAGE_TITLE)
    val hitLongDecode = hit("Background Poll & Stream Recovery", "long decode", "timeout", "poll", "Providers")
    val hitLegacyFallback = hit("Smart Legacy Endpoint Fallback", "useLegacyFallbackEndpoint", "generateContent", "chat/completions", "legacy", "fallback", "Providers")
    val hitSampling = hit("Temperature", "Top-P", "Max Output Tokens", "Thinking Effort", "sampling", "budget", "Providers")
    val hitCapabilities = hit("Web Search", "Code Execution", "URL Context", "Image Generation", "Video Generation", "google_search", "grounding", "Providers")

    val matchingModels = remember(appState.profiles, tokens) {
        appState.profiles.flatMap { prof ->
            prof.savedModels.filter { m ->
                tokens.all { tok -> m.lowercase().contains(tok) || prof.name.lowercase().contains(tok) }
            }.map { m -> prof to m }
        }.take(25)
    }
    val anyProviderMatch = hitApiKey || hitBaseUrl || hitStateful || hitLongDecode || hitLegacyFallback || hitSampling || hitCapabilities || matchingModels.isNotEmpty()

    // 3. WORKSPACE MATCHES
    val hitWorkspaceMode = hit("Agent approvals and file access", "Agent permissions", "assistant defaults", "inherit", "web access", "network", "sandbox", "power", "keep awake", "tools", "Workspace Mode", "Read-Only", "Agent R/W", "fs_read", "fs_edit", "todo_write", "folder", "workspace")
    val hitShellExec = hit("Shell full process access", "shell_exec", "terminal", "powershell", "bash", "command", "workspace")
    val anyWorkspaceMatch = hitWorkspaceMode || hitShellExec

    // 4. DEFAULT MODELS MATCHES
    val hitGlobalModel = hit("Global Default Chat Model", "Default Model", "primary model")
    val hitTitleModel = hit("Title Generation", "Topic Naming", "Default Model", "prompt template", "auto title")
    val hitSummaryModel = hit("Summary & Context Compression", "Default Model", "compact context", "summarize")
    val hitTranslationModel = hit("Translation Model", "Target Language", "Default Model", "translate")
    val anyDefaultModelMatch = hitGlobalModel || hitTitleModel || hitSummaryModel || hitTranslationModel

    // 5. ASSISTANTS, WORLD BOOK, & BACKUP / OTA MATCHES
    val matchingAssistants = remember(appState.assistants, tokens) {
        appState.assistants.filter { asst ->
            hit(asst.name, asst.systemPrompt, asst.authorsNote, "assistant", "persona", "agent", "system prompt", "author's note")
        }
    }
    val matchingLore = remember(activeSession.fragments, appState.globalWorldBook, tokens) {
        (activeSession.fragments + appState.globalWorldBook).distinctBy { it.id }.filter { frag ->
            hit(frag.title, frag.content, frag.keywords.joinToString(" "), "world book", "lorebook", "fragment")
        }
    }
    val hitWorldBookGeneral = hit("World Book", "Lorebook", "Character Card", "PNG", "JSON", "chara", "ccv3", "System Prompt Preset")
    val hitOtaBackup = hit("Backup & Updates", "OTA", "Check for Updates at Launch", "GitHub Release", "Update", "Export Backup", "Restore Backup", "Wipe All Local Data", "storage")

    val hasAnyResult = anyPrefMatch || anyProviderMatch || anyWorkspaceMatch || anyDefaultModelMatch ||
        matchingAssistants.isNotEmpty() || matchingLore.isNotEmpty() || hitWorldBookGeneral || hitOtaBackup

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (win.isCompact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KissVectorIcon(icon = KissIcon.SEARCH, tint = p.accent, size = 17.dp)
            BasicText(
                text = "Settings search results for \"$searchQuery\"",
                style = p.uiHeaderStyle.copy(fontSize = 18.sp)
            )
        }

        if (!hasAnyResult) {
            SepiaSectionCard(
                title = "No matching settings found for \"$searchQuery\"",
                subtitle = "Try a word like 'font' or 'model'."
            ) {
                SepiaButton(
                    text = "Open preferences →",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = { onJumpToCategory(SettingsCategory.PREFERENCES) }
                )
            }
        }

        if (anyPrefMatch) {
            SepiaSectionCard(
                title = "Preferences & appearance",
                subtitle = "Chat item display, typography, Markdown/LaTeX rendering, and behavior toggles",
                headerAction = {
                    SepiaButton(
                        text = "Open preferences →",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { onJumpToCategory(SettingsCategory.PREFERENCES) }
                    )
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hitColorMode) {
                        ThemePresetChoices(theme, onUpdateTheme)
                    }
                    if (hitAccentColor) {
                        SepiaAdaptiveRow(label = "Accent color") {
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(Modifier.selectableGroup()) { AccentSwatches(theme, onUpdateTheme) }
                        }
                    }
                    if (hitAppFont) {
                        FontChoiceRows(theme, onUpdateTheme, onNotify)
                    }
                    if (hitFontSizes) {
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
                    if (hitLayoutStyle) {
                        SepiaAdaptiveRow(label = "Message layout style") {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    }
                    if (hitProviderCapsule) {
                        SepiaToggleRow(
                            title = "Show provider in model capsule",
                            checked = prefs.showProviderInModelCapsule,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showProviderInModelCapsule = it)) }
                        )
                    }
                    if (hitTitleBarAvatar) {
                        SepiaToggleRow(
                            title = "Show assistant avatar in title bar",
                            checked = prefs.showAssistantAvatarInTitleBar,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showAssistantAvatarInTitleBar = it)) }
                        )
                    }
                    if (hitUserAvatar) {
                        SepiaToggleRow(
                            title = "Show user avatar",
                            checked = prefs.showUserAvatar,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showUserAvatar = it)) }
                        )
                    }
                    if (hitUserName) {
                        SepiaToggleRow(
                            title = "Show user name",
                            checked = prefs.showUserName,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showUserName = it)) }
                        )
                    }
                    if (hitUserTimestamp) {
                        SepiaToggleRow(
                            title = "Show user timestamp",
                            checked = prefs.showUserTimestamp,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showUserTimestamp = it)) }
                        )
                    }
                    if (hitUserActions) {
                        SepiaToggleRow(
                            title = "Show user message actions",
                            checked = prefs.showUserMessageActions,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showUserMessageActions = it)) }
                        )
                    }
                    if (hitModelIcon) {
                        SepiaToggleRow(
                            title = "Show model icon / avatar",
                            checked = prefs.showModelIcon,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showModelIcon = it)) }
                        )
                    }
                    if (hitModelName) {
                        SepiaToggleRow(
                            title = "Show model name",
                            checked = prefs.showModelName,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showModelName = it)) }
                        )
                    }
                    if (hitModelTimestamp) {
                        SepiaToggleRow(
                            title = "Show model timestamp",
                            checked = prefs.showModelTimestamp,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showModelTimestamp = it)) }
                        )
                    }
                    if (hitProviderAfterModel) {
                        SepiaToggleRow(
                            title = "Show provider after model name",
                            checked = prefs.showProviderAfterModelName,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showProviderAfterModelName = it)) }
                        )
                    }
                    if (hitTokenStats) {
                        SepiaToggleRow(
                            title = "Show usage stats",
                            checked = prefs.showTokenAndContextStats,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showTokenAndContextStats = it)) }
                        )
                    }
                    if (hitTokensEntireTurn) {
                        SepiaToggleRow(
                            title = "Count usage for the whole reply",
                            checked = prefs.showTokensForEntireTurn,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showTokensForEntireTurn = it)) }
                        )
                    }
                    if (hitThinkingCards) {
                        SepiaToggleRow(
                            title = "Show thinking cards",
                            checked = prefs.showThinkingCards,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showThinkingCards = it)) }
                        )
                    }
                    if (hitToolCards) {
                        SepiaToggleRow(
                            title = "Show tool cards",
                            checked = prefs.showToolCards,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showToolCards = it)) }
                        )
                    }
                    if (hitReasoningButton) {
                        SepiaToggleRow(
                            title = "Show thinking level on the button",
                            checked = prefs.showReasoningLevelOnButton,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showReasoningLevelOnButton = it)) }
                        )
                    }
                    if (hitInlineDollar) {
                        SepiaToggleRow(
                            title = "Inline $...$ Rendering",
                            checked = prefs.inlineDollarMathRendering,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(inlineDollarMathRendering = it)) }
                        )
                    }
                    if (hitMathFormula) {
                        SepiaToggleRow(
                            title = "Math formula rendering",
                            checked = prefs.mathFormulaRendering,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(mathFormulaRendering = it)) }
                        )
                    }
                    if (hitUserMd) {
                        SepiaToggleRow(
                            title = "Render user messages with Markdown",
                            checked = prefs.renderUserMessagesWithMarkdown,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(renderUserMessagesWithMarkdown = it)) }
                        )
                    }
                    if (hitReasoningMd) {
                        SepiaToggleRow(
                            title = "Format thinking text (headings, math)",
                            checked = prefs.renderReasoningWithMarkdown,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(renderReasoningWithMarkdown = it)) }
                        )
                    }
                    if (hitAssistantMd) {
                        SepiaToggleRow(
                            title = "Render assistant messages with Markdown",
                            checked = prefs.renderAssistantMessagesWithMarkdown,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(renderAssistantMessagesWithMarkdown = it)) }
                        )
                    }
                    if (hitDialogueInk) {
                        SepiaToggleRow(
                            title = "Highlight spoken \"dialogue\" & *narration* ink",
                            checked = theme.highlightDialogueAndNarration,
                            onCheckedChange = { onUpdateTheme(theme.copy(highlightDialogueAndNarration = it)) }
                        )
                    }
                    if (hitAutoCollapseCode) {
                        SepiaToggleRow(
                            title = "Auto-collapse code blocks",
                            checked = prefs.autoCollapseCodeBlocks,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(autoCollapseCodeBlocks = it)) }
                        )
                    }
                    if (hitCollapseLong) {
                        SepiaToggleRow(
                            title = "Collapse long messages (> 1,800 chars)",
                            checked = prefs.collapseLongMessages,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(collapseLongMessages = it)) }
                        )
                    }
                    if (hitAutoTopics) {
                        SepiaToggleRow(
                            title = "Auto switch to topics",
                            checked = prefs.autoSwitchToTopics,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(autoSwitchToTopics = it)) }
                        )
                    }
                    if (hitAutoCollapseThink) {
                        SepiaToggleRow(
                            title = "Auto-collapse thinking",
                            checked = prefs.autoCollapseThinking,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(autoCollapseThinking = it)) }
                        )
                    }
                    if (hitToolSummary) {
                        SepiaToggleRow(
                            title = "Show tool result summary",
                            checked = prefs.showToolResultSummary,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showToolResultSummary = it)) }
                        )
                    }
                    if (hitDeleteBelowRegen) {
                        SepiaToggleRow(
                            title = "Delete messages below when regenerating",
                            checked = prefs.deleteMessagesBelowWhenRegenerating,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(deleteMessagesBelowWhenRegenerating = it)) }
                        )
                    }
                    if (hitDeletePrevRegen) {
                        SepiaToggleRow(
                            title = "Delete previous message when regenerating",
                            checked = prefs.deletePreviousMessageWhenRegenerating,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(deletePreviousMessageWhenRegenerating = it)) }
                        )
                    }
                    if (hitConfirmRegen) {
                        SepiaToggleRow(
                            title = "Confirm before regenerating",
                            checked = prefs.confirmBeforeRegenerating,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(confirmBeforeRegenerating = it)) }
                        )
                    }
                    if (hitConfirmDelete) {
                        SepiaToggleRow(
                            title = "Confirm before deleting or clearing chats",
                            checked = prefs.confirmBeforeDeletingChat,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(confirmBeforeDeletingChat = it)) }
                        )
                    }
                    if (hitKeepForkVersions) {
                        SepiaToggleRow(
                            title = "Keep message versions when branching",
                            checked = prefs.keepMessageVersionsWhenForking,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(keepMessageVersionsWhenForking = it)) }
                        )
                    }
                    if (hitChatListDates) {
                        SepiaToggleRow(
                            title = "Show chat list dates",
                            checked = prefs.showChatListDates,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showChatListDates = it)) }
                        )
                    }
                    if (hitNewChatSwitchAsst) {
                        SepiaToggleRow(
                            title = "New chat when switching assistants",
                            checked = prefs.newChatWhenSwitchingAssistants,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(newChatWhenSwitchingAssistants = it)) }
                        )
                    }
                    if (hitNewChatDeleteTopic) {
                        SepiaToggleRow(
                            title = "New chat after deleting topic",
                            checked = prefs.newChatAfterDeletingTopic,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(newChatAfterDeletingTopic = it)) }
                        )
                    }
                    if (hitNewChatLaunch) {
                        SepiaToggleRow(
                            title = "New chat on launch",
                            checked = prefs.newChatOnLaunch,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(newChatOnLaunch = it)) }
                        )
                    }
                    if (hitAutoScrollOnOpen) {
                        SepiaToggleRow(
                            title = "Auto-scroll to bottom when opening a chat",
                            checked = prefs.autoScrollToBottomOnOpen,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(autoScrollToBottomOnOpen = it)) }
                        )
                    }
                    if (hitAutoFollowBottom) {
                        SepiaToggleRow(
                            title = "Auto-follow bottom while streaming",
                            checked = prefs.autoFollowBottom,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(autoFollowBottom = it)) }
                        )
                    }
                    if (hitAutoCompactLimit) {
                        SepiaToggleRow(
                            title = "Shorten the conversation automatically when it's 85% full",
                            checked = prefs.autoCompactWhenNearContextLimit,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(autoCompactWhenNearContextLimit = it)) }
                        )
                    }
                    if (hitNavMinimap) {
                        SepiaAdaptiveRow(label = "Message navigation minimap") {
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                MessageNavVisibility.entries.forEach { mode ->
                                    SepiaChip(
                                        label = mode.label,
                                        selected = prefs.messageNavButtons == mode,
                                        onClick = { onUpdatePreferences(prefs.copy(messageNavButtons = mode)) }
                                    )
                                }
                            }
                        }
                    }
                    if (hitSendShortcut) {
                        SepiaAdaptiveRow(label = "Send shortcut (desktop)") {
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SendShortcutMode.entries.forEach { mode ->
                                    SepiaChip(
                                        label = mode.label,
                                        selected = prefs.sendShortcut == mode,
                                        onClick = { onUpdatePreferences(prefs.copy(sendShortcut = mode)) }
                                    )
                                }
                            }
                        }
                    }
                    if (hitPasteLongFile) {
                        SepiaToggleRow(
                            title = "Paste long text as file attachment",
                            checked = prefs.pasteLongTextAsFile,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(pasteLongTextAsFile = it)) }
                        )
                    }
                }
            }
        }

        if (anyProviderMatch) {
            SepiaSectionCard(
                title = "Providers, Proxy, & Models (${activeProfile.name})",
                subtitle = "API keys, advanced settings and model choice",
                headerAction = {
                    SepiaButton(
                        text = "Open providers →",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { onJumpToCategory(SettingsCategory.PROVIDERS) }
                    )
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hitApiKey) {
                        SepiaTextField(
                            value = activeProfile.apiKey,
                            onValueChange = { k -> onUpdateProfile { it.copy(apiKey = k) } },
                            label = "API Key (${activeProfile.name})",
                            placeholder = "sk-... or AIza...",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (hitBaseUrl) {
                        SepiaTextField(
                            value = activeProfile.baseUrl,
                            onValueChange = { u -> onUpdateProfile { it.copy(baseUrl = u) } },
                            label = "Server URL (${activeProfile.name})",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (hitStateful) {
                        SepiaToggleRow(
                            title = PROVIDER_STORAGE_TITLE,
                            subtitle = PROVIDER_STORAGE_SUBTITLE,
                            checked = activeProfile.keepsProviderState,
                            onCheckedChange = { store -> onUpdateProfile { it.copy(storeServerSide = store, useStatefulChaining = it.useStatefulChaining && store) } }
                        )
                        SepiaToggleRow(
                            title = "Let the provider remember this conversation",
                            checked = activeProfile.useStatefulChaining,
                            onCheckedChange = { v -> onUpdateProfile { it.copy(useStatefulChaining = v) } }
                        )
                    }
                    if (hitLongDecode) {
                        SepiaToggleRow(
                            title = "Background poll & stream recovery",
                            checked = activeProfile.useBackgroundLongDecode,
                            onCheckedChange = { v -> onUpdateProfile { it.copy(useBackgroundLongDecode = v) } }
                        )
                    }
                    if (hitLegacyFallback) {
                        SepiaToggleRow(
                            title = "Try the older API if this one is missing",
                            checked = activeProfile.useLegacyFallbackEndpoint,
                            onCheckedChange = { v -> onUpdateProfile { it.copy(useLegacyFallbackEndpoint = v) } }
                        )
                    }
                    if (hitSampling) {
                        var tempStr by remember(activeProfile.temperature) { mutableStateOf(activeProfile.temperature?.toString().orEmpty()) }
                        var topPStr by remember(activeProfile.topP) { mutableStateOf(activeProfile.topP?.toString().orEmpty()) }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SepiaTextField(
                                value = tempStr,
                                onValueChange = { v ->
                                    tempStr = v
                                    if (v.trim().isEmpty()) onUpdateProfile { it.copy(temperature = null) }
                                    else v.toDoubleOrNull()?.let { num -> onUpdateProfile { it.copy(temperature = num) } }
                                },
                                label = "Creativity, called temperature (blank = default)",
                                monospace = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            SepiaTextField(
                                value = topPStr,
                                onValueChange = { v ->
                                    topPStr = v
                                    if (v.trim().isEmpty()) onUpdateProfile { it.copy(topP = null) }
                                    else v.toDoubleOrNull()?.let { num -> onUpdateProfile { it.copy(topP = num) } }
                                },
                                label = "Word variety, called Top-P (blank = default)",
                                monospace = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (hitCapabilities) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SepiaChip(
                                label = "🌐 Web search",
                                selected = activeProfile.enableWebSearch,
                                onClick = { onUpdateProfile { it.copy(enableWebSearch = !it.enableWebSearch) } }
                            )
                            SepiaChip(
                                label = "💻 Code execution",
                                selected = activeProfile.enableCodeExecution,
                                onClick = { onUpdateProfile { it.copy(enableCodeExecution = !it.enableCodeExecution) } }
                            )
                            SepiaChip(
                                label = "🔗 URL context",
                                selected = activeProfile.enableUrlContext,
                                onClick = { onUpdateProfile { it.copy(enableUrlContext = !it.enableUrlContext) } }
                            )
                        }
                    }
                    if (matchingModels.isNotEmpty()) {
                        BasicText(
                            text = "Matching Models (${matchingModels.size}):",
                            style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                        )
                        matchingModels.take(8).forEach { (prof, modelId) ->
                            val isCurrent = activeSession.profileId == prof.id &&
                                (activeSession.modelOverride.ifBlank { prof.defaultModel }) == modelId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isCurrent) p.accent.copy(alpha = 0.14f) else p.elevatedBg.copy(alpha = 0.45f))
                                    .kissClickable {
                                        onSelectProfile(prof.id)
                                        onUpdateProfileById(prof.id) { it.copy(defaultModel = modelId) }
                                        onUpdateSession { it.copy(profileId = prof.id, modelOverride = modelId) }
                                        onNotify("Switched active model to ${prof.name} • $modelId")
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    KissVectorIcon(
                                        icon = if (isCurrent) KissIcon.SPARKLES else KissIcon.CPU,
                                        tint = if (isCurrent) p.accent else p.mutedInk,
                                        size = 13.dp
                                    )
                                    BasicText(
                                        text = modelId,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.monoStyle.copy(
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isCurrent) p.accent else p.primaryInk
                                        )
                                    )
                                }
                                BasicText(
                                    text = prof.name,
                                    style = p.uiLabelStyle.copy(fontSize = 11.sp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (anyWorkspaceMatch) {
            SepiaSectionCard(
                title = "Workspace and agent tools",
                subtitle = "Approvals and local tool access",
                headerAction = {
                    SepiaButton(
                        text = "Open workspace →",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { onJumpToCategory(SettingsCategory.WORKSPACE) }
                    )
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText("Approval, file access, web access and shell permissions are controlled per assistant and chat.", style = p.uiLabelStyle)
                }
            }
        }

        if (anyDefaultModelMatch) {
            SepiaSectionCard(
                title = "Default models (global, title, summary, translation)",
                subtitle = "Task-specific model assignments and title prompt template",
                headerAction = {
                    SepiaButton(
                        text = "Open default models →",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { onJumpToCategory(SettingsCategory.DEFAULT_MODEL) }
                    )
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hitTitleModel || hitGlobalModel) {
                        SepiaToggleRow(
                            title = "Title generation: follow global choice",
                            checked = appState.defaultModels.titleFollowGlobal,
                            onCheckedChange = { onUpdateDefaultModels(appState.defaultModels.copy(titleFollowGlobal = it)) }
                        )
                    }
                    if (hitSummaryModel || hitGlobalModel) {
                        SepiaToggleRow(
                            title = "Summary & context compression: follow global choice",
                            checked = appState.defaultModels.summaryFollowGlobal,
                            onCheckedChange = { onUpdateDefaultModels(appState.defaultModels.copy(summaryFollowGlobal = it)) }
                        )
                    }
                    if (hitTranslationModel || hitGlobalModel) {
                        SepiaToggleRow(
                            title = "Translation: follow global choice",
                            checked = appState.defaultModels.translationFollowGlobal,
                            onCheckedChange = { onUpdateDefaultModels(appState.defaultModels.copy(translationFollowGlobal = it)) }
                        )
                    }
                }
            }
        }

        if (matchingAssistants.isNotEmpty()) {
            SepiaSectionCard(
                title = "Assistants (${matchingAssistants.size} matched)",
                subtitle = "Saved assistants, their instructions and reply settings",
                headerAction = {
                    SepiaButton(
                        text = "Open assistants →",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { onJumpToCategory(SettingsCategory.ASSISTANTS) }
                    )
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    matchingAssistants.forEach { asst ->
                        BasicText(
                            text = "• ${asst.name}: ${asst.systemPrompt.ifBlank { "(No system prompt)" }}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiBodyStyle
                        )
                    }
                }
            }
        }

        if (matchingLore.isNotEmpty() || hitWorldBookGeneral) {
            SepiaSectionCard(
                title = "World Book & character cards",
                subtitle = "Character cards and world notes that appear when keywords come up",
                headerAction = {
                    SepiaButton(
                        text = "Open World Book →",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { onJumpToCategory(SettingsCategory.WORLD_BOOK) }
                    )
                }
            ) {
                BasicText(
                    text = "${plural(matchingLore.size, "matching World Book fragment")}.",
                    style = p.uiLabelStyle
                )
            }
        }

        if (hitOtaBackup) {
            SepiaSectionCard(
                title = "Backup & updates",
                subtitle = "Installed v${OtaUpdateEngine.currentAppVersion} • Backups and updates",
                headerAction = {
                    SepiaButton(
                        text = "Open backup & updates",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { onJumpToCategory(SettingsCategory.BACKUP_OTA) }
                    )
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SepiaToggleRow(
                        title = "Check for updates at launch",
                        checked = appState.autoCheckOtaOnStartup,
                        onCheckedChange = { onUpdateOtaSettings(appState.otaGithubRepo, it) }
                    )
                    SepiaButton(
                        text = "↻ Check for update now",
                        compact = true,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = onCheckOtaNow
                    )
                }
            }
        }
    }
}
