package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.AuthHeaderMode
import io.parchment.ai.model.CharacterOrScenarioCard
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.ContextFragment
import io.parchment.ai.model.DefaultModelConfig
import io.parchment.ai.model.FragmentActivationMode
import io.parchment.ai.model.MessageNavVisibility
import io.parchment.ai.model.ModelPricingSpec
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.SendShortcutMode
import io.parchment.ai.model.SystemPromptPreset
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.model.ViewMode
import io.parchment.ai.model.WorkspaceMode
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.ModelsDevClient
import io.parchment.ai.network.OtaReleaseInfo
import io.parchment.ai.network.OtaUpdateEngine
import io.parchment.ai.network.OtaUpdateState
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.protocol.CardAndPromptCodec
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class SettingsCategory(val icon: String, val label: String) {
    PREFERENCES("🖥", "Preferences"),
    PROVIDERS("🖧", "Providers"),
    ASSISTANTS("🤖", "Assistant"),
    DEFAULT_MODEL("♡", "Default Model"),
    WORKSPACE("📂", "Workspace"),
    WORLD_BOOK("📖", "World Book & Cards"),
    BACKUP_OTA("☁", "Backup & OTA")
}

@Composable
fun ParchmentSettingsScreen(
    appState: AppStateSnapshot,
    activeProfile: ProviderProfile,
    activeSession: ChatSession,
    initialCategory: SettingsCategory,
    otaState: OtaUpdateState,
    onSelectProfile: (String) -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onUpdateProfileById: (String, (ProviderProfile) -> ProviderProfile) -> Unit,
    onAddProfile: (ProviderProtocol) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onUpdateAssistants: (List<AssistantAgent>, String) -> Unit,
    onUpdateDefaultModels: (DefaultModelConfig) -> Unit,
    onUpdatePreferences: (PreferencesConfig) -> Unit,
    onUpdateTheme: (ThemeConfig) -> Unit,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onUpdateGlobalWorldBook: (List<ContextFragment>) -> Unit,
    onSaveCardToLibrary: (CharacterOrScenarioCard) -> Unit,
    onSavePresetToLibrary: (SystemPromptPreset) -> Unit,
    onDeletePresetFromLibrary: (String) -> Unit,
    onUpdateOtaSettings: (String, Boolean) -> Unit,
    onCheckOtaNow: () -> Unit,
    onInstallOtaRelease: (OtaReleaseInfo) -> Unit,
    onRestoreFullBackup: (AppStateSnapshot) -> Unit,
    onWipeAllData: () -> Unit,
    onNotify: (String) -> Unit,
    onClose: () -> Unit
) {
    val p = LocalParchmentPalette.current
    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    var settingsSearchQuery by remember { mutableStateOf("") }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(p.canvasBg)) {
        val isWide = maxWidth >= 720.dp

        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left Category Rail
                Column(
                    modifier = Modifier
                        .width(225.dp)
                        .fillMaxHeight()
                        .background(p.sheetBg.copy(alpha = 0.65f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = "Settings",
                            style = p.uiHeaderStyle.copy(fontSize = 16.sp)
                        )
                        SepiaButton(
                            text = "← Chat",
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = onClose
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    SepiaTextField(
                        value = settingsSearchQuery,
                        onValueChange = { settingsSearchQuery = it },
                        placeholder = "🔍 Search settings…",
                        minHeight = 36.dp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(6.dp))

                    SettingsCategory.entries.forEach { cat ->
                        val isSelected = selectedCategory == cat
                        val shape = RoundedCornerShape(10.dp)
                        val catIcon = remember(cat.icon) { parseKissLabelIcons(cat.icon).leadingIcon ?: KissIcon.SETTINGS }
                        val interactionSource = remember { MutableInteractionSource() }
                        val isHovered by interactionSource.collectIsHoveredAsState()
                        val isPressed by interactionSource.collectIsPressedAsState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .kissClickable(
                                    pressScale = 0.97f,
                                    hoverScale = 1.01f,
                                    interactionSource = interactionSource,
                                    onClick = { selectedCategory = cat }
                                )
                                .clip(shape)
                                .background(
                                    when {
                                        isSelected -> p.accent.copy(alpha = 0.16f)
                                        isPressed -> p.accent.copy(alpha = 0.12f)
                                        isHovered -> p.accent.copy(alpha = 0.08f)
                                        else -> Color.Transparent
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            KissVectorIcon(
                                icon = catIcon,
                                tint = if (isSelected || isHovered || isPressed) p.accent else p.narrationInk,
                                size = 15.dp
                            )
                            BasicText(
                                text = cat.label,
                                style = p.uiBodyStyle.copy(
                                    color = if (isSelected || isHovered || isPressed) p.accent else p.primaryInk,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 13.5.sp
                                )
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .width(0.85.dp)
                        .fillMaxHeight()
                        .background(p.border.copy(alpha = 0.7f))
                )

                // Right Subpage Viewport
                Crossfade(
                    targetState = selectedCategory,
                    animationSpec = tween(160),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) { cat ->
                    SettingsSubpageContent(
                        category = cat,
                        searchQuery = settingsSearchQuery,
                        appState = appState,
                        activeProfile = activeProfile,
                        activeSession = activeSession,
                        otaState = otaState,
                        onSelectProfile = onSelectProfile,
                        onUpdateProfile = onUpdateProfile,
                        onUpdateProfileById = onUpdateProfileById,
                        onAddProfile = onAddProfile,
                        onDeleteProfile = onDeleteProfile,
                        onUpdateAssistants = onUpdateAssistants,
                        onUpdateDefaultModels = onUpdateDefaultModels,
                        onUpdatePreferences = onUpdatePreferences,
                        onUpdateTheme = onUpdateTheme,
                        onUpdateSession = onUpdateSession,
                        onUpdateGlobalWorldBook = onUpdateGlobalWorldBook,
                        onSaveCardToLibrary = onSaveCardToLibrary,
                        onSavePresetToLibrary = onSavePresetToLibrary,
                        onDeletePresetFromLibrary = onDeletePresetFromLibrary,
                        onUpdateOtaSettings = onUpdateOtaSettings,
                        onCheckOtaNow = onCheckOtaNow,
                        onInstallOtaRelease = onInstallOtaRelease,
                        onRestoreFullBackup = onRestoreFullBackup,
                        onWipeAllData = onWipeAllData,
                        onNotify = onNotify
                    )
                }
            }
        } else {
            // Compact / Portrait Mobile Layout: Clean top category bar + full-width subpage + swipe-right-to-return-to-chat
            var dragX by remember { mutableStateOf(0f) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragX = 0f },
                            onHorizontalDrag = { _, dragAmount -> dragX += dragAmount },
                            onDragEnd = {
                                if (dragX > 72.dp.toPx()) {
                                    onClose()
                                }
                                dragX = 0f
                            }
                        )
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.sheetBg.copy(alpha = 0.75f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SepiaTextField(
                            value = settingsSearchQuery,
                            onValueChange = { settingsSearchQuery = it },
                            placeholder = "🔍 Search settings…",
                            minHeight = 34.dp,
                            modifier = Modifier.weight(1f)
                        )
                        SepiaButton(
                            text = "← Chat",
                            compact = true,
                            style = SepiaButtonStyle.PRIMARY,
                            onClick = onClose
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SettingsCategory.entries.forEach { cat ->
                            SepiaChip(
                                label = "${cat.icon} ${cat.label}",
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = cat }
                            )
                        }
                    }
                }
                SepiaDivider()
                Crossfade(
                    targetState = selectedCategory,
                    animationSpec = tween(160),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) { cat ->
                    SettingsSubpageContent(
                        category = cat,
                        searchQuery = settingsSearchQuery,
                        appState = appState,
                        activeProfile = activeProfile,
                        activeSession = activeSession,
                        otaState = otaState,
                        onSelectProfile = onSelectProfile,
                        onUpdateProfile = onUpdateProfile,
                        onUpdateProfileById = onUpdateProfileById,
                        onAddProfile = onAddProfile,
                        onDeleteProfile = onDeleteProfile,
                        onUpdateAssistants = onUpdateAssistants,
                        onUpdateDefaultModels = onUpdateDefaultModels,
                        onUpdatePreferences = onUpdatePreferences,
                        onUpdateTheme = onUpdateTheme,
                        onUpdateSession = onUpdateSession,
                        onUpdateGlobalWorldBook = onUpdateGlobalWorldBook,
                        onSaveCardToLibrary = onSaveCardToLibrary,
                        onSavePresetToLibrary = onSavePresetToLibrary,
                        onDeletePresetFromLibrary = onDeletePresetFromLibrary,
                        onUpdateOtaSettings = onUpdateOtaSettings,
                        onCheckOtaNow = onCheckOtaNow,
                        onInstallOtaRelease = onInstallOtaRelease,
                        onRestoreFullBackup = onRestoreFullBackup,
                        onWipeAllData = onWipeAllData,
                        onNotify = onNotify
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSubpageContent(
    category: SettingsCategory,
    searchQuery: String,
    appState: AppStateSnapshot,
    activeProfile: ProviderProfile,
    activeSession: ChatSession,
    otaState: OtaUpdateState,
    onSelectProfile: (String) -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onUpdateProfileById: (String, (ProviderProfile) -> ProviderProfile) -> Unit,
    onAddProfile: (ProviderProtocol) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onUpdateAssistants: (List<AssistantAgent>, String) -> Unit,
    onUpdateDefaultModels: (DefaultModelConfig) -> Unit,
    onUpdatePreferences: (PreferencesConfig) -> Unit,
    onUpdateTheme: (ThemeConfig) -> Unit,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onUpdateGlobalWorldBook: (List<ContextFragment>) -> Unit,
    onSaveCardToLibrary: (CharacterOrScenarioCard) -> Unit,
    onSavePresetToLibrary: (SystemPromptPreset) -> Unit,
    onDeletePresetFromLibrary: (String) -> Unit,
    onUpdateOtaSettings: (String, Boolean) -> Unit,
    onCheckOtaNow: () -> Unit,
    onInstallOtaRelease: (OtaReleaseInfo) -> Unit,
    onRestoreFullBackup: (AppStateSnapshot) -> Unit,
    onWipeAllData: () -> Unit,
    onNotify: (String) -> Unit
) {
    when (category) {
        SettingsCategory.PREFERENCES -> PreferencesSubpage(
            prefs = appState.preferences,
            theme = appState.theme,
            activeSession = activeSession,
            searchQuery = searchQuery,
            onUpdatePreferences = onUpdatePreferences,
            onUpdateTheme = onUpdateTheme,
            onUpdateSession = onUpdateSession,
            onNotify = onNotify
        )
        SettingsCategory.PROVIDERS -> ProvidersSubpage(
            appState = appState,
            activeProfile = activeProfile,
            activeSession = activeSession,
            onSelectProfile = onSelectProfile,
            onUpdateProfile = onUpdateProfile,
            onUpdateProfileById = onUpdateProfileById,
            onAddProfile = onAddProfile,
            onDeleteProfile = onDeleteProfile,
            onUpdateSession = onUpdateSession,
            onNotify = onNotify
        )
        SettingsCategory.ASSISTANTS -> AssistantsSubpage(
            appState = appState,
            onUpdateAssistants = onUpdateAssistants,
            onNotify = onNotify
        )
        SettingsCategory.DEFAULT_MODEL -> DefaultModelSubpage(
            appState = appState,
            defaultModels = appState.defaultModels,
            onUpdateDefaultModels = onUpdateDefaultModels,
            onSelectProfile = onSelectProfile,
            onUpdateProfileById = onUpdateProfileById,
            onUpdateSession = onUpdateSession,
            onNotify = onNotify
        )
        SettingsCategory.WORKSPACE -> WorkspaceSubpage(
            activeSession = activeSession,
            activeProfile = activeProfile,
            onUpdateSession = onUpdateSession,
            onUpdateProfile = onUpdateProfile
        )
        SettingsCategory.WORLD_BOOK -> WorldBookAndCardsSubpage(
            appState = appState,
            activeSession = activeSession,
            onUpdateSession = onUpdateSession,
            onUpdateGlobalWorldBook = onUpdateGlobalWorldBook,
            onSaveCardToLibrary = onSaveCardToLibrary,
            onSavePresetToLibrary = onSavePresetToLibrary,
            onDeletePresetFromLibrary = onDeletePresetFromLibrary,
            onUpdateTheme = onUpdateTheme,
            onNotify = onNotify
        )
        SettingsCategory.BACKUP_OTA -> BackupAndOtaSubpage(
            appState = appState,
            otaState = otaState,
            onUpdateOtaSettings = onUpdateOtaSettings,
            onCheckOtaNow = onCheckOtaNow,
            onInstallOtaRelease = onInstallOtaRelease,
            onRestoreFullBackup = onRestoreFullBackup,
            onWipeAllData = onWipeAllData,
            onNotify = onNotify
        )
    }
}

// ============================================================================================
// SUBPAGE 1: PREFERENCES
// ============================================================================================
@Composable
private fun PreferencesSubpage(
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

        // 1. Appearance & Fonts
        if (matchesSearch("color mode", "theme", "font", "sepia", "dark", "light", "opacity", "size")) {
            SepiaSectionCard(title = "Appearance & Fonts") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SepiaAdaptiveRow(label = "Color Mode & Preset") {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ThemeConfig.PRESETS.forEach { preset ->
                                SepiaChip(
                                    label = preset.name.substringBefore(" ("),
                                    selected = theme.name == preset.name,
                                    onClick = { onUpdateTheme(preset) }
                                )
                            }
                        }
                    }

                    SepiaDivider()

                    // Theme Swatches + Custom Hex Toggle (FlowRow so swatches & Hex button never clip on mobile!)
                    SepiaAdaptiveRow(label = "Accent Color") {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val accentSwatches = listOf(
                                "#8B5A2B", "#9E4730", "#3B5998", "#1F6F54",
                                "#5E4B8B", "#7A5216", "#8C3823", "#2D3748"
                            )
                            accentSwatches.forEach { hex ->
                                val col = parseHexColor(hex)
                                val isSel = theme.accentHex.equals(hex, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(col)
                                        .border(if (isSel) 2.dp else 0.8.dp, if (isSel) p.primaryInk else p.border, CircleShape)
                                        .clickable { onUpdateTheme(theme.copy(name = "Custom Theme", accentHex = hex)) }
                                )
                            }
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
                                        label = "Canvas Hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.cardBgHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", cardBgHex = it)) },
                                        label = "Card Hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SepiaTextField(
                                        value = theme.primaryInkHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", primaryInkHex = it)) },
                                        label = "Ink Hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.accentHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", accentHex = it)) },
                                        label = "Accent Hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SepiaTextField(
                                        value = theme.canvasBgHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", canvasBgHex = it)) },
                                        label = "Canvas Hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.cardBgHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", cardBgHex = it)) },
                                        label = "Card Hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.primaryInkHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", primaryInkHex = it)) },
                                        label = "Ink Hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SepiaTextField(
                                        value = theme.accentHex,
                                        onValueChange = { onUpdateTheme(theme.copy(name = "Custom", accentHex = it)) },
                                        label = "Accent Hex",
                                        monospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    SepiaDivider()

                    // App Font & Code Font (FlowRow so all font chips & .TTF/.OTF button fit on mobile!)
                    SepiaAdaptiveRow(label = "App / Prose Font") {
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

                    if (win.isCompact) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SepiaTextField(
                                value = proseSizeText,
                                onValueChange = { v ->
                                    proseSizeText = v
                                    v.toFloatOrNull()?.takeIf { it > 4f }?.let { onUpdateTheme(theme.copy(proseFontSizeSp = it)) }
                                },
                                label = "Prose Font Size (sp)",
                                monospace = true,
                                modifier = Modifier.weight(1f)
                            )
                            SepiaTextField(
                                value = codeSizeText,
                                onValueChange = { v ->
                                    codeSizeText = v
                                    v.toFloatOrNull()?.takeIf { it > 4f }?.let { onUpdateTheme(theme.copy(codeFontSizeSp = it)) }
                                },
                                label = "Code Font Size (sp)",
                                monospace = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        SepiaTextField(
                            value = maxWidthText,
                            onValueChange = { v ->
                                maxWidthText = v
                                v.toFloatOrNull()?.takeIf { it >= 300f }?.let { onUpdateTheme(theme.copy(maxReadingWidthDp = it)) }
                            },
                            label = "Max Reading Width (dp)",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SepiaTextField(
                                value = proseSizeText,
                                onValueChange = { v ->
                                    proseSizeText = v
                                    v.toFloatOrNull()?.takeIf { it > 4f }?.let { onUpdateTheme(theme.copy(proseFontSizeSp = it)) }
                                },
                                label = "Prose Font Size (sp)",
                                monospace = true,
                                modifier = Modifier.weight(1f)
                            )
                            SepiaTextField(
                                value = codeSizeText,
                                onValueChange = { v ->
                                    codeSizeText = v
                                    v.toFloatOrNull()?.takeIf { it > 4f }?.let { onUpdateTheme(theme.copy(codeFontSizeSp = it)) }
                                },
                                label = "Code Font Size (sp)",
                                monospace = true,
                                modifier = Modifier.weight(1f)
                            )
                            SepiaTextField(
                                value = maxWidthText,
                                onValueChange = { v ->
                                    maxWidthText = v
                                    v.toFloatOrNull()?.takeIf { it >= 300f }?.let { onUpdateTheme(theme.copy(maxReadingWidthDp = it)) }
                                },
                                label = "Max Reading Width (dp)",
                                monospace = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 2. Chat Item Display (Matching Screenshots 4 & 5)
        if (matchesSearch("chat item display", "layout", "bubble", "page", "avatar", "timestamp", "tokens", "thinking cards", "tool cards", "model name", "provider")) {
            SepiaSectionCard(title = "Chat item display") {
                Column {
                    SepiaAdaptiveRow(label = "Message Layout Style") {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SepiaChip(
                                label = "Card Bubbles",
                                selected = prefs.defaultViewMode == ViewMode.EDITORIAL_TURNS,
                                onClick = {
                                    onUpdatePreferences(prefs.copy(defaultViewMode = ViewMode.EDITORIAL_TURNS))
                                    onUpdateSession { it.copy(viewMode = ViewMode.EDITORIAL_TURNS) }
                                }
                            )
                            SepiaChip(
                                label = "Clean Page",
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
                        title = "Show User Avatar",
                        checked = prefs.showUserAvatar,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserAvatar = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show User Name",
                        checked = prefs.showUserName,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserName = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show User Timestamp",
                        checked = prefs.showUserTimestamp,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserTimestamp = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show User Message Actions",
                        checked = prefs.showUserMessageActions,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserMessageActions = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show Model Name",
                        checked = prefs.showModelName,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showModelName = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show Model Timestamp",
                        checked = prefs.showModelTimestamp,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showModelTimestamp = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show Provider After Model Name",
                        checked = prefs.showProviderAfterModelName,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showProviderAfterModelName = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show Token & Context Stats",
                        checked = prefs.showTokenAndContextStats,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showTokenAndContextStats = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show tokens for the entire turn",
                        checked = prefs.showTokensForEntireTurn,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showTokensForEntireTurn = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show Thinking Cards",
                        checked = prefs.showThinkingCards,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showThinkingCards = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show Tool Cards",
                        checked = prefs.showToolCards,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showToolCards = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show reasoning level on the button",
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
                        title = "Math Formula Rendering",
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
                        title = "Render reasoning (thinking) with Markdown & LaTeX",
                        subtitle = "Parses **bold**, *italic*, code blocks, and LaTeX math inside thinking traces",
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
                        title = "Auto-collapse Code Blocks",
                        checked = prefs.autoCollapseCodeBlocks,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoCollapseCodeBlocks = it)) }
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
                                    text = "Android Unrestricted Background Mode",
                                    style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, color = p.accent)
                                )
                                BasicText(
                                    text = "Prevents the system from suspending active background generations when switching apps",
                                    style = p.uiLabelStyle
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
                        title = "Auto switch to Topics",
                        subtitle = "Automatically switches sidebar to Topics list when selecting an Assistant",
                        checked = prefs.autoSwitchToTopics,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoSwitchToTopics = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Auto-collapse Thinking",
                        subtitle = "Briefly opens live reasoning box while thinking, then collapses when reply starts",
                        checked = prefs.autoCollapseThinking,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoCollapseThinking = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show Tool Result Summary",
                        checked = prefs.showToolResultSummary,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showToolResultSummary = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Delete messages below when regenerating",
                        subtitle = "Removes subsequent turns below the regenerated message",
                        checked = prefs.deleteMessagesBelowWhenRegenerating,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(deleteMessagesBelowWhenRegenerating = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Delete previous message when regenerating",
                        subtitle = "Replaces the previous reply in-place instead of adding a swipe variant",
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
                        subtitle = "Shows an 'Are you sure?' prompt before deleting a chat or clearing all chats",
                        checked = prefs.confirmBeforeDeletingChat,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(confirmBeforeDeletingChat = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Keep Message Versions When Forking",
                        checked = prefs.keepMessageVersionsWhenForking,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(keepMessageVersionsWhenForking = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show Chat List Dates",
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
                        title = "New Chat on Launch",
                        checked = prefs.newChatOnLaunch,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(newChatOnLaunch = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Auto-follow bottom while streaming",
                        checked = prefs.autoFollowBottom,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoFollowBottom = it)) }
                    )
                    SepiaDivider()

                    // Message Navigation Buttons (Minimap) Visibility
                    SepiaAdaptiveRow(label = "Message Navigation Minimap") {
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
                    SepiaAdaptiveRow(label = "Send Shortcut (Desktop)") {
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

// ============================================================================================
// SUBPAGE 2: PROVIDERS (3-Pane Provider List + Detail + Model Subpage)
// ============================================================================================
@Composable
private fun ProvidersSubpage(
    appState: AppStateSnapshot,
    activeProfile: ProviderProfile,
    activeSession: ChatSession,
    onSelectProfile: (String) -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onUpdateProfileById: (String, (ProviderProfile) -> ProviderProfile) -> Unit,
    onAddProfile: (ProviderProtocol) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    var providerSearch by remember { mutableStateOf("") }
    var modelSearch by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var showAddProviderMenu by remember { mutableStateOf(false) }
    var showProtocolSettingsSubpage by remember(activeProfile.id) { mutableStateOf(false) }
    var selectedModelForConfigSubpage by remember(activeProfile.id) { mutableStateOf<String?>(null) }
    var manualModelToAdd by remember { mutableStateOf("") }
    var showAddModelBox by remember { mutableStateOf(false) }
    var isTestingOrFetching by remember { mutableStateOf(false) }

    val filteredProfiles = remember(appState.profiles, providerSearch) {
        val q = providerSearch.trim()
        if (q.isEmpty()) appState.profiles
        else appState.profiles.filter { it.name.contains(q, ignoreCase = true) || it.baseUrl.contains(q, ignoreCase = true) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 680.dp

        Row(modifier = Modifier.fillMaxSize()) {
            // Middle Column (on Desktop): Provider List
            if (isWide) {
                Column(
                    modifier = Modifier
                        .width(235.dp)
                        .fillMaxHeight()
                        .background(p.sheetBg.copy(alpha = 0.35f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SepiaTextField(
                        value = providerSearch,
                        onValueChange = { providerSearch = it },
                        placeholder = "🔍 Search providers…",
                        minHeight = 34.dp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        filteredProfiles.forEach { prof ->
                            val isSel = prof.id == activeProfile.id
                            val shape = RoundedCornerShape(10.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .background(if (isSel) p.cardBg else Color.Transparent)
                                    .border(0.8.dp, if (isSel) p.border else Color.Transparent, shape)
                                    .clickable { onSelectProfile(prof.id) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SepiaAvatarCircle(
                                        symbol = prof.name.take(1).uppercase(),
                                        selected = isSel,
                                        sizeDp = 26.dp
                                    )
                                    BasicText(
                                        text = prof.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiBodyStyle.copy(
                                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        )
                                    )
                                }
                                val statusOn = prof.enabled && (prof.apiKey.isNotBlank() || prof.baseUrl != prof.protocol.defaultBaseUrl)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (statusOn) Color(0xFF2E7D32).copy(alpha = 0.14f) else p.accentSecondary.copy(alpha = 0.14f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    BasicText(
                                        text = if (statusOn) "ON" else "OFF",
                                        style = p.uiLabelStyle.copy(
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (statusOn) Color(0xFF2E7D32) else p.accentSecondary
                                        )
                                    )
                                }
                            }
                        }
                    }

                    SepiaButton(
                        text = "+ Add Provider",
                        style = SepiaButtonStyle.OUTLINE,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showAddProviderMenu = !showAddProviderMenu }
                    )

                    AnimatedVisibility(visible = showAddProviderMenu) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ProviderProtocol.entries.forEach { proto ->
                                SepiaButton(
                                    text = "+ ${proto.displayName.substringBefore(" API")}",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        onAddProfile(proto)
                                        showAddProviderMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.width(0.85.dp).fillMaxHeight().background(p.border.copy(alpha = 0.6f)))
            }

            // Right Pane: Provider Detail OR Per-Model Config Subpage
            val win = LocalParchmentWindowSize.current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(if (win.isCompact) 12.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!isWide) {
                    // Mobile Horizontal Provider Selector
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        appState.profiles.forEach { prof ->
                            SepiaChip(
                                label = prof.name,
                                selected = prof.id == activeProfile.id,
                                onClick = { onSelectProfile(prof.id) }
                            )
                        }
                        SepiaButton(
                            text = "+ Add",
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = { showAddProviderMenu = !showAddProviderMenu }
                        )
                    }
                    AnimatedVisibility(visible = showAddProviderMenu) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ProviderProtocol.entries.forEach { proto ->
                                SepiaButton(
                                    text = "+ ${proto.displayName.substringBefore(" API")}",
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = {
                                        onAddProfile(proto)
                                        showAddProviderMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                // If a specific model's ⚙ subpage is open, show the dedicated Per-Model Token & Pricing Subpage!
                val configModelId = selectedModelForConfigSubpage
                if (configModelId != null) {
                    ModelConfigSubpage(
                        profile = activeProfile,
                        modelId = configModelId,
                        onUpdateProfile = onUpdateProfile,
                        onNotify = onNotify,
                        onBack = { selectedModelForConfigSubpage = null }
                    )
                    return@Column
                }

                // If Protocol & Request Overrides Subpage is open
                if (showProtocolSettingsSubpage) {
                    ProviderProtocolSubpage(
                        profile = activeProfile,
                        onUpdateProfile = onUpdateProfile,
                        onDeleteProfile = { onDeleteProfile(activeProfile.id) },
                        onBack = { showProtocolSettingsSubpage = false }
                    )
                    return@Column
                }

                // Main Provider Detail Header (Matching Screenshot 9: `zAI ⚙` + Enable Switch)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BasicText(
                            text = activeProfile.name,
                            style = p.uiHeaderStyle.copy(fontSize = 18.sp),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        SepiaButton(
                            text = "⚙ Protocol & Params",
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = { showProtocolSettingsSubpage = true }
                        )
                        SepiaButton(
                            text = "🗑",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onDeleteProfile(activeProfile.id) }
                        )
                    }
                    SepiaToggleSwitch(
                        checked = activeProfile.enabled,
                        onCheckedChange = { en -> onUpdateProfile { it.copy(enabled = en) } }
                    )
                }

                // API Key Row with Show/Hide & `♡ Test` Connection Button
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(text = "API Key", style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold, color = p.narrationInk))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SepiaButton(
                                text = if (showApiKey) "🙈 Hide" else "👁 Show",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = { showApiKey = !showApiKey }
                            )
                            SepiaButton(
                                text = if (isTestingOrFetching) "Testing…" else "♡ Test",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = {
                                    isTestingOrFetching = true
                                    scope.launch {
                                        val start = PlatformBridge.currentTimeMillis()
                                        AiProviderEngine.fetchAvailableModels(activeProfile).fold(
                                            onSuccess = { list ->
                                                val ms = PlatformBridge.currentTimeMillis() - start
                                                val secStr = "${(ms / 10L) / 100.0}s"
                                                onNotify("✓ Connection OK ($secStr • ${list.size} models reachable)")
                                                isTestingOrFetching = false
                                            },
                                            onFailure = { err ->
                                                onNotify("✕ Connection Test Failed: ${err.message}")
                                                isTestingOrFetching = false
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                    SepiaTextField(
                        value = if (showApiKey) activeProfile.apiKey else "•".repeat(activeProfile.apiKey.length.coerceAtMost(48)),
                        onValueChange = { newVal ->
                            if (showApiKey || activeProfile.apiKey.isEmpty()) {
                                onUpdateProfile { it.copy(apiKey = newVal.trim()) }
                            }
                        },
                        placeholder = "Paste API Key (click 👁 Show to edit masked key)…",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // API Base URL
                SepiaTextField(
                    value = activeProfile.baseUrl,
                    onValueChange = { url -> onUpdateProfile { it.copy(baseUrl = url.trim()) } },
                    label = "API Base URL",
                    placeholder = activeProfile.protocol.defaultBaseUrl,
                    monospace = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))

                // Models Section Header (Matching Screenshot 9: `Models (count)` + `🔍` `+` `🗑` `🔄 Fetch`)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(
                            text = "Models",
                            style = p.uiHeaderStyle.copy(fontSize = 15.5.sp)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(p.sheetBg)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            BasicText(
                                text = "${activeProfile.savedModels.size}",
                                style = p.uiLabelStyle.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SepiaButton(
                            text = "+",
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = { showAddModelBox = !showAddModelBox }
                        )
                        if (activeProfile.savedModels.isNotEmpty()) {
                            SepiaButton(
                                text = "🗑",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = {
                                    onUpdateProfile { it.copy(savedModels = emptyList()) }
                                    onNotify("Cleared model list for ${activeProfile.name}.")
                                }
                            )
                        }
                        SepiaButton(
                            text = if (isTestingOrFetching) "Fetching…" else "🔄 Fetch",
                            compact = true,
                            style = SepiaButtonStyle.PRIMARY,
                            onClick = {
                                isTestingOrFetching = true
                                scope.launch {
                                    AiProviderEngine.fetchAvailableModels(activeProfile).fold(
                                        onSuccess = { fetched ->
                                            val merged = (activeProfile.savedModels + fetched).distinct()
                                            // NEVER overwrite an existing defaultModel!
                                            // If defaultModel is blank, pick a chat model (skipping deep-research/embedding)
                                            val preferredDefault = activeProfile.defaultModel.ifBlank {
                                                pickBestInitialChatModel(merged)
                                            }
                                            onUpdateProfile {
                                                it.copy(savedModels = merged, defaultModel = preferredDefault)
                                            }
                                            onNotify("Fetched ${fetched.size} models.")
                                            isTestingOrFetching = false
                                        },
                                        onFailure = { err ->
                                            onNotify("Fetch failed: ${err.message}")
                                            isTestingOrFetching = false
                                        }
                                    )
                                }
                            }
                        )
                    }
                }

                // Manual Model Add Drawer
                AnimatedVisibility(visible = showAddModelBox || activeProfile.savedModels.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SepiaTextField(
                            value = manualModelToAdd,
                            onValueChange = { manualModelToAdd = it },
                            placeholder = "Enter Model ID…",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        SepiaButton(
                            text = "Add & Select",
                            style = SepiaButtonStyle.PRIMARY,
                            onClick = {
                                val clean = manualModelToAdd.trim()
                                if (clean.isNotEmpty()) {
                                    val updated = (listOf(clean) + activeProfile.savedModels).distinct()
                                    onUpdateProfile { it.copy(defaultModel = clean, savedModels = updated) }
                                    onUpdateSession { it.copy(modelOverride = clean, profileId = activeProfile.id) }
                                    manualModelToAdd = ""
                                    showAddModelBox = false
                                    onNotify("Selected model: $clean")
                                }
                            }
                        )
                    }
                }

                if (activeProfile.savedModels.size > 4) {
                    SepiaTextField(
                        value = modelSearch,
                        onValueChange = { modelSearch = it },
                        placeholder = "🔍 Filter models…",
                        minHeight = 34.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Grouped Model List Card (Matching Screenshot 9: `▾ gemini` -> `Gemini 4 Argon [1M] [⚙] [—]`)
                val visibleModels = remember(activeProfile.savedModels, modelSearch) {
                    val q = modelSearch.trim()
                    if (q.isEmpty()) activeProfile.savedModels
                    else activeProfile.savedModels.filter { it.contains(q, ignoreCase = true) }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(p.cardBg)
                        .border(0.85.dp, p.border.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (visibleModels.isEmpty()) {
                        BasicText(
                            text = "No models added yet. Type a model ID above or click '🔄 Fetch'.",
                            style = p.uiLabelStyle,
                            modifier = Modifier.padding(8.dp)
                        )
                    } else {
                        visibleModels.forEach { modelId ->
                            val isSelectedModel = activeProfile.defaultModel == modelId
                            val customSpec = activeProfile.customModelSpecs[modelId]
                            val ctxTokens = customSpec?.contextWindowTokens ?: activeProfile.contextWindowTokens
                            val rowInteraction = remember { MutableInteractionSource() }
                            val rowHovered by rowInteraction.collectIsHoveredAsState()
                            val rowPressed by rowInteraction.collectIsPressedAsState()
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .kissClickable(
                                        pressScale = 0.985f,
                                        hoverScale = 1.008f,
                                        interactionSource = rowInteraction,
                                        onClick = {
                                            onUpdateProfile { it.copy(defaultModel = modelId) }
                                            onUpdateSession { it.copy(modelOverride = modelId, profileId = activeProfile.id) }
                                        }
                                    )
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isSelectedModel -> p.accent.copy(alpha = 0.14f)
                                            rowPressed -> p.accent.copy(alpha = 0.12f)
                                            rowHovered -> p.accent.copy(alpha = 0.08f)
                                            else -> p.elevatedBg.copy(alpha = 0.35f)
                                        }
                                    )
                                    .border(
                                        0.85.dp,
                                        if (isSelectedModel || rowHovered) p.accent.copy(alpha = if (isSelectedModel) 1f else 0.55f) else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    KissVectorIcon(
                                        icon = if (isSelectedModel) KissIcon.SPARKLES else KissIcon.CIRCLE_EMPTY,
                                        tint = if (isSelectedModel || rowHovered) p.accent else p.mutedInk,
                                        size = 13.dp
                                    )
                                    BasicText(
                                        text = modelId,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiBodyStyle.copy(
                                            fontFamily = p.codeFontFamily,
                                            fontWeight = if (isSelectedModel) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isSelectedModel || rowHovered) p.accent else p.primaryInk
                                        )
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Capability & Context Window Badges
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(p.sheetBg)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        BasicText(
                                            text = if (ctxTokens <= 0) "∞" else formatCompactTokens(ctxTokens),
                                            style = p.uiLabelStyle.copy(fontSize = 10.sp, fontFamily = p.codeFontFamily)
                                        )
                                    }
                                    SepiaButton(
                                        text = "⚙",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = { selectedModelForConfigSubpage = modelId }
                                    )
                                    SepiaButton(
                                        text = "—",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = {
                                            val remaining = activeProfile.savedModels.filterNot { it == modelId }
                                            val nextDef = if (activeProfile.defaultModel == modelId) remaining.firstOrNull().orEmpty() else activeProfile.defaultModel
                                            onUpdateProfile { it.copy(savedModels = remaining, defaultModel = nextDef) }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Subpage for Protocol, Auth Header, & Nullable Sampling/Token Parameters.
 * Supports clearing any default value so it is stripped from outgoing API JSON (`null`), or setting `0 = Unlimited`.
 */
@Composable
private fun ProviderProtocolSubpage(
    profile: ProviderProfile,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onDeleteProfile: () -> Unit,
    onBack: () -> Unit
) {
    val p = LocalParchmentPalette.current
    var tempText by remember(profile.id, profile.temperature) { mutableStateOf(profile.temperature?.toString() ?: "") }
    var topPText by remember(profile.id, profile.topP) { mutableStateOf(profile.topP?.toString() ?: "") }
    var maxOutText by remember(profile.id, profile.maxOutputTokens) { mutableStateOf(profile.maxOutputTokens?.toString() ?: "") }
    var budgetText by remember(profile.id, profile.thinkingBudgetTokens) { mutableStateOf(profile.thinkingBudgetTokens?.toString() ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicText(
                text = "Protocol & Request Shaping — ${profile.name}",
                style = p.uiHeaderStyle,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp)
            )
            SepiaButton(text = "← Back to Provider", compact = true, style = SepiaButtonStyle.PRIMARY, onClick = onBack)
        }

        SepiaSectionCard(
            title = "Identity, Wire Protocol & Endpoint Override",
            subtitle = "Switch between Gemini Interactions, OpenAI Responses, Anthropic Messages, and ByteDance Ark"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaTextField(
                    value = profile.name,
                    onValueChange = { n -> onUpdateProfile { it.copy(name = n) } },
                    label = "Provider Display Name",
                    modifier = Modifier.fillMaxWidth()
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderProtocol.entries.forEach { proto ->
                        SepiaChip(
                            label = proto.displayName,
                            selected = profile.protocol == proto,
                            onClick = { onUpdateProfile { it.copy(protocol = proto, baseUrl = proto.defaultBaseUrl) } }
                        )
                    }
                }
                SepiaTextField(
                    value = profile.endpointPathOverride,
                    onValueChange = { ep -> onUpdateProfile { it.copy(endpointPathOverride = ep.trim()) } },
                    label = "Custom Endpoint Path Override (Optional — leave blank for default ${profile.protocol.defaultEndpointPath})",
                    placeholder = profile.protocol.defaultEndpointPath,
                    monospace = true,
                    modifier = Modifier.fillMaxWidth()
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AuthHeaderMode.entries.forEach { mode ->
                        SepiaChip(
                            label = mode.label,
                            selected = profile.authHeaderMode == mode,
                            onClick = { onUpdateProfile { it.copy(authHeaderMode = mode) } }
                        )
                    }
                }
                SepiaToggleRow(
                    title = "Auto-Fetch Models on Startup when Saved List is Empty",
                    checked = profile.autoFetchModels,
                    onCheckedChange = { af -> onUpdateProfile { it.copy(autoFetchModels = af) } }
                )
                SepiaToggleRow(
                    title = "Stateful Chain (previous_interaction_id / previous_response_id)",
                    subtitle = "Enabled by default for multi-turn continuity",
                    checked = profile.useStatefulChaining,
                    onCheckedChange = { sc -> onUpdateProfile { it.copy(useStatefulChaining = sc) } }
                )
                SepiaToggleRow(
                    title = "Background Poll & Stream Recovery",
                    subtitle = "Enabled by default — automatically recovers via GET /interactions/{id} if stream is interrupted",
                    checked = profile.useBackgroundLongDecode,
                    onCheckedChange = { bg -> onUpdateProfile { it.copy(useBackgroundLongDecode = bg) } }
                )
                SepiaToggleRow(
                    title = "Use Legacy Fallback Endpoint (:streamGenerateContent or /v1/chat/completions)",
                    checked = profile.useLegacyFallbackEndpoint,
                    onCheckedChange = { leg -> onUpdateProfile { it.copy(useLegacyFallbackEndpoint = leg) } }
                )
            }
        }

        SepiaSectionCard(
            title = "Nullable Sampling & Token Parameters (Delete Value = Strip from API)",
            subtitle = "Leave any box blank to omit that field completely from the outgoing JSON payload. Set 0 for Unlimited where supported."
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val win = LocalParchmentWindowSize.current
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SepiaTextField(
                        value = tempText,
                        onValueChange = { v ->
                            tempText = v
                            val trimmed = v.trim()
                            if (trimmed.isEmpty()) {
                                onUpdateProfile { it.copy(temperature = null) }
                            } else {
                                trimmed.toDoubleOrNull()?.let { num -> onUpdateProfile { it.copy(temperature = num) } }
                            }
                        },
                        label = "Temperature (Blank = Strip)",
                        placeholder = "Omitted from API request",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    SepiaTextField(
                        value = topPText,
                        onValueChange = { v ->
                            topPText = v
                            val trimmed = v.trim()
                            if (trimmed.isEmpty()) {
                                onUpdateProfile { it.copy(topP = null) }
                            } else {
                                trimmed.toDoubleOrNull()?.let { num -> onUpdateProfile { it.copy(topP = num) } }
                            }
                        },
                        label = "Top-P (Blank = Strip)",
                        placeholder = "Omitted from API request",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (win.isCompact) {
                    SepiaTextField(
                        value = maxOutText,
                        onValueChange = { v ->
                            maxOutText = v
                            val trimmed = v.trim()
                            if (trimmed.isEmpty()) {
                                onUpdateProfile { it.copy(maxOutputTokens = null) }
                            } else {
                                trimmed.toIntOrNull()?.let { num -> onUpdateProfile { it.copy(maxOutputTokens = num) } }
                            }
                        },
                        label = "Max Output Tokens (Blank or 0 = Unlimited / Strip)",
                        placeholder = "Omitted / Unlimited",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SepiaTextField(
                        value = budgetText,
                        onValueChange = { v ->
                            budgetText = v
                            val trimmed = v.trim()
                            if (trimmed.isEmpty()) {
                                onUpdateProfile { it.copy(thinkingBudgetTokens = null) }
                            } else {
                                trimmed.toIntOrNull()?.let { num -> onUpdateProfile { it.copy(thinkingBudgetTokens = num) } }
                            }
                        },
                        label = "Thinking Budget Tokens (Blank or 0 = Adaptive / Strip)",
                        placeholder = "Omitted / Adaptive",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SepiaTextField(
                            value = maxOutText,
                            onValueChange = { v ->
                                maxOutText = v
                                val trimmed = v.trim()
                                if (trimmed.isEmpty()) {
                                    onUpdateProfile { it.copy(maxOutputTokens = null) }
                                } else {
                                    trimmed.toIntOrNull()?.let { num -> onUpdateProfile { it.copy(maxOutputTokens = num) } }
                                }
                            },
                            label = "Max Output Tokens (Blank or 0 = Unlimited / Strip)",
                            placeholder = "Omitted / Unlimited",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        SepiaTextField(
                            value = budgetText,
                            onValueChange = { v ->
                                budgetText = v
                                val trimmed = v.trim()
                                if (trimmed.isEmpty()) {
                                    onUpdateProfile { it.copy(thinkingBudgetTokens = null) }
                                } else {
                                    trimmed.toIntOrNull()?.let { num -> onUpdateProfile { it.copy(thinkingBudgetTokens = num) } }
                                }
                            },
                            label = "Thinking Budget Tokens (Blank or 0 = Adaptive / Strip)",
                            placeholder = "Omitted / Adaptive",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (profile.protocol == ProviderProtocol.ANTHROPIC_MESSAGES && (profile.maxOutputTokens == null || profile.maxOutputTokens <= 0)) {
                    BasicText(
                        text = "⚠ Info: Anthropic's /v1/messages endpoint requires a positive `max_tokens` field in JSON; when left blank or 0, Parchment sends 64000 so output is uncapped.",
                        style = p.uiLabelStyle.copy(color = p.accentSecondary)
                    )
                }

                SepiaTextField(
                    value = profile.customHeadersRaw,
                    onValueChange = { h -> onUpdateProfile { it.copy(customHeadersRaw = h) } },
                    label = "Custom HTTP Headers (One 'Header: Value' per line)",
                    singleLine = false,
                    monospace = true,
                    minHeight = 56.dp,
                    modifier = Modifier.fillMaxWidth()
                )

                SepiaTextField(
                    value = profile.rawJsonExtraBody,
                    onValueChange = { b -> onUpdateProfile { it.copy(rawJsonExtraBody = b) } },
                    label = "Raw JSON Extra Body Override (Deep-merged into outgoing request)",
                    singleLine = false,
                    monospace = true,
                    minHeight = 64.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Dedicated Subpage for Per-Model Context Window, Max Output Tokens, and `models.dev` Pricing.
 * Zero forced minimums (`0 = Unlimited`, `Blank = Strip`).
 */
@Composable
private fun ModelConfigSubpage(
    profile: ProviderProfile,
    modelId: String,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onNotify: (String) -> Unit,
    onBack: () -> Unit
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    val existingSpec = profile.customModelSpecs[modelId]

    var ctxText by remember(modelId, existingSpec) {
        mutableStateOf((existingSpec?.contextWindowTokens ?: profile.contextWindowTokens).toString())
    }
    var maxOutText by remember(modelId, existingSpec) {
        mutableStateOf(existingSpec?.maxOutputTokens?.toString() ?: profile.maxOutputTokens?.toString() ?: "")
    }
    var inPriceText by remember(modelId, existingSpec) {
        mutableStateOf((existingSpec?.inputPricePerM ?: profile.inputPricePerM).toString())
    }
    var outPriceText by remember(modelId, existingSpec) {
        mutableStateOf((existingSpec?.outputPricePerM ?: profile.outputPricePerM).toString())
    }
    var cachePriceText by remember(modelId, existingSpec) {
        mutableStateOf((existingSpec?.cacheReadPricePerM ?: profile.cacheReadPricePerM).toString())
    }
    var thinkPriceText by remember(modelId, existingSpec) {
        mutableStateOf((existingSpec?.reasoningPricePerM ?: profile.reasoningPricePerM).toString())
    }

    fun saveSpec() {
        val ctx = ctxText.trim().toIntOrNull() ?: 0 // 0 = Unlimited!
        val maxOut = maxOutText.trim().toIntOrNull() // null = Stripped!
        val inP = inPriceText.trim().toDoubleOrNull() ?: 0.0
        val outP = outPriceText.trim().toDoubleOrNull() ?: 0.0
        val cacheP = cachePriceText.trim().toDoubleOrNull() ?: 0.0
        val thinkP = thinkPriceText.trim().toDoubleOrNull() ?: 0.0

        val spec = ModelPricingSpec(
            modelId = modelId,
            inputPricePerM = inP,
            outputPricePerM = outP,
            cacheReadPricePerM = cacheP,
            reasoningPricePerM = thinkP,
            contextWindowTokens = ctx,
            maxOutputTokens = maxOut
        )
        onUpdateProfile { prof ->
            prof.copy(
                contextWindowTokens = if (prof.defaultModel == modelId) ctx else prof.contextWindowTokens,
                maxOutputTokens = if (prof.defaultModel == modelId) maxOut else prof.maxOutputTokens,
                inputPricePerM = if (prof.defaultModel == modelId) inP else prof.inputPricePerM,
                outputPricePerM = if (prof.defaultModel == modelId) outP else prof.outputPricePerM,
                cacheReadPricePerM = if (prof.defaultModel == modelId) cacheP else prof.cacheReadPricePerM,
                reasoningPricePerM = if (prof.defaultModel == modelId) thinkP else prof.reasoningPricePerM,
                customModelSpecs = prof.customModelSpecs + (modelId to spec)
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicText(
                text = "Model Settings — $modelId",
                style = p.uiHeaderStyle,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp)
            )
            SepiaButton(
                text = "← Back to Models",
                compact = true,
                style = SepiaButtonStyle.PRIMARY,
                onClick = {
                    saveSpec()
                    onBack()
                }
            )
        }

        SepiaSectionCard(
            title = "Context Window & Output Token Limits",
            subtitle = "Set Context Window to 0 for Unlimited (∞). Leave Max Output Tokens blank to strip it from API requests.",
            headerAction = {
                SepiaButton(
                    text = "🌐 Sync from models.dev",
                    compact = true,
                    style = SepiaButtonStyle.SECONDARY,
                    onClick = {
                        scope.launch {
                            ModelsDevClient.fetchModelSpec(modelId).fold(
                                onSuccess = { spec ->
                                    ctxText = spec.contextWindowTokens.toString()
                                    maxOutText = spec.maxOutputTokens?.toString() ?: ""
                                    inPriceText = spec.inputPricePerM.toString()
                                    outPriceText = spec.outputPricePerM.toString()
                                    cachePriceText = spec.cacheReadPricePerM.toString()
                                    thinkPriceText = spec.reasoningPricePerM.toString()
                                    saveSpec()
                                    onNotify("Synced $modelId specs from models.dev.")
                                },
                                onFailure = { err -> onNotify("models.dev lookup: ${err.message}") }
                            )
                        }
                    }
                )
            }
        ) {
            val win = LocalParchmentWindowSize.current
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (win.isCompact) {
                    SepiaTextField(
                        value = ctxText,
                        onValueChange = { ctxText = it; saveSpec() },
                        label = "Context Window Tokens (0 = Unlimited ∞)",
                        placeholder = "0 = Unlimited",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SepiaTextField(
                        value = maxOutText,
                        onValueChange = { maxOutText = it; saveSpec() },
                        label = "Max Output Tokens (Blank = Strip from API)",
                        placeholder = "Omitted from API request",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SepiaTextField(
                            value = inPriceText,
                            onValueChange = { inPriceText = it; saveSpec() },
                            label = "Input $/1M tok",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        SepiaTextField(
                            value = cachePriceText,
                            onValueChange = { cachePriceText = it; saveSpec() },
                            label = "Cached $/1M tok",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SepiaTextField(
                            value = thinkPriceText,
                            onValueChange = { thinkPriceText = it; saveSpec() },
                            label = "Reasoning $/1M tok",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        SepiaTextField(
                            value = outPriceText,
                            onValueChange = { outPriceText = it; saveSpec() },
                            label = "Output $/1M tok",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SepiaTextField(
                            value = ctxText,
                            onValueChange = { ctxText = it; saveSpec() },
                            label = "Context Window Tokens (0 = Unlimited ∞)",
                            placeholder = "0 = Unlimited",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        SepiaTextField(
                            value = maxOutText,
                            onValueChange = { maxOutText = it; saveSpec() },
                            label = "Max Output Tokens (Blank = Strip from API)",
                            placeholder = "Omitted from API request",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SepiaTextField(
                            value = inPriceText,
                            onValueChange = { inPriceText = it; saveSpec() },
                            label = "Input $/1M tok",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        SepiaTextField(
                            value = cachePriceText,
                            onValueChange = { cachePriceText = it; saveSpec() },
                            label = "Cached $/1M tok",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        SepiaTextField(
                            value = thinkPriceText,
                            onValueChange = { thinkPriceText = it; saveSpec() },
                            label = "Reasoning $/1M tok",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        SepiaTextField(
                            value = outPriceText,
                            onValueChange = { outPriceText = it; saveSpec() },
                            label = "Output $/1M tok",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================================
// SUBPAGE 3: ASSISTANTS (Assistant Cards + Editor Subpage)
// ============================================================================================
@Composable
private fun AssistantsSubpage(
    appState: AppStateSnapshot,
    onUpdateAssistants: (List<AssistantAgent>, String) -> Unit,
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    var editingAssistantId by remember { mutableStateOf<String?>(null) }

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
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                SepiaButton(
                    text = "← All Assistants",
                    compact = true,
                    style = SepiaButtonStyle.PRIMARY,
                    onClick = { editingAssistantId = null }
                )
            }

            SepiaSectionCard(title = "Assistant Identity & System Prompt") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SepiaTextField(
                            value = editingAssistant.iconEmoji,
                            onValueChange = { em -> updateCurrent { it.copy(iconEmoji = em.take(4)) } },
                            label = "Icon",
                            modifier = Modifier.width(72.dp)
                        )
                        SepiaTextField(
                            value = editingAssistant.name,
                            onValueChange = { nm -> updateCurrent { it.copy(name = nm) } },
                            label = "Assistant Name",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("💬", "🧪", "✏️", "🤖", "📚", "⚡", "🎭", "🧭").forEach { emoji ->
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
                        label = "System Prompt (Persisted independently of Topics/Chats)",
                        placeholder = "Enter system prompt (optional)…",
                        singleLine = false,
                        minHeight = 140.dp,
                        maxHeight = 360.dp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    SepiaTextField(
                        value = editingAssistant.authorsNote,
                        onValueChange = { an -> updateCurrent { it.copy(authorsNote = an) } },
                        label = "Default Author's Note / Post-History Injection (Optional)",
                        singleLine = false,
                        minHeight = 60.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SepiaSectionCard(
                title = "Assistant Sampling & Context Overrides (Blank = Strip / Follow Profile, 0 = Unlimited)",
                subtitle = "Leave Temperature, Top-P, or Max Tokens blank to strip or inherit."
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SepiaTextField(
                            value = tempText,
                            onValueChange = { v ->
                                tempText = v
                                val t = v.trim()
                                updateCurrent { it.copy(temperature = if (t.isEmpty()) null else t.toDoubleOrNull()) }
                            },
                            label = "Temperature (Blank = Strip)",
                            placeholder = "Omitted",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        SepiaTextField(
                            value = topPText,
                            onValueChange = { v ->
                                topPText = v
                                val t = v.trim()
                                updateCurrent { it.copy(topP = if (t.isEmpty()) null else t.toDoubleOrNull()) }
                            },
                            label = "Top-P (Blank = Strip)",
                            placeholder = "Omitted",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (win.isCompact) {
                        SepiaTextField(
                            value = maxOutText,
                            onValueChange = { v ->
                                maxOutText = v
                                val t = v.trim()
                                updateCurrent { it.copy(maxOutputTokens = if (t.isEmpty()) null else t.toIntOrNull()) }
                            },
                            label = "Max Output Tokens (Blank/0 = Unlimited)",
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
                            label = "History Message Limit (0 = Unlimited)",
                            placeholder = "0 = Unlimited",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SepiaTextField(
                                value = maxOutText,
                                onValueChange = { v ->
                                    maxOutText = v
                                    val t = v.trim()
                                    updateCurrent { it.copy(maxOutputTokens = if (t.isEmpty()) null else t.toIntOrNull()) }
                                },
                                label = "Max Output Tokens (Blank/0 = Unlimited)",
                                placeholder = "Unlimited",
                                monospace = true,
                                modifier = Modifier.weight(1f)
                            )
                            SepiaTextField(
                                value = ctxMsgLimitText,
                                onValueChange = { v ->
                                    ctxMsgLimitText = v
                                    val n = v.trim().toIntOrNull() ?: 0
                                    updateCurrent { it.copy(contextMessageLimit = n.coerceAtLeast(0)) }
                                },
                                label = "History Message Limit (0 = Unlimited)",
                                placeholder = "0 = Unlimited",
                                monospace = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            return@Column
        }

        // Master View: Assistant Cards List (Zero hardcoded assistants — 100% user-created)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicText(
                text = "Assistants",
                style = p.uiHeaderStyle.copy(fontSize = 19.sp),
                modifier = Modifier.padding(top = 2.dp, end = 8.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SepiaButton(
                    text = "+ New Assistant",
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
                title = "Default Assistant Active",
                subtitle = "Click '+ New Assistant' above to create additional assistant profiles."
            ) {}
        }

        appState.assistants.forEach { asst ->
            val isSelected = asst.id == appState.activeAssistantId
            val shape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(p.cardBg)
                    .border(if (isSelected) 1.2.dp else 0.85.dp, if (isSelected) p.accent else p.border.copy(alpha = 0.75f), shape)
                    .clickable { editingAssistantId = asst.id }
                    .padding(16.dp),
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
                            style = p.uiHeaderStyle.copy(fontSize = 15.5.sp)
                        )
                        BasicText(
                            text = asst.systemPrompt.ifBlank { "No system prompt configured — click to edit" },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = p.uiLabelStyle.copy(color = p.narrationInk)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SepiaButton(
                        text = "📋",
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
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            val remaining = appState.assistants.filterNot { it.id == asst.id }
                            val nextActive = if (appState.activeAssistantId == asst.id) remaining.firstOrNull()?.id.orEmpty() else appState.activeAssistantId
                            onUpdateAssistants(remaining, nextActive)
                            onNotify("Deleted assistant '${asst.name}'.")
                        }
                    )
                }
            }
        }
    }
}

// ============================================================================================
// SUBPAGE 4: DEFAULT MODEL (Global, Title Summary, Context Compression, Translation)
// ============================================================================================
@Composable
private fun DefaultModelSubpage(
    appState: AppStateSnapshot,
    defaultModels: DefaultModelConfig,
    onUpdateDefaultModels: (DefaultModelConfig) -> Unit,
    onSelectProfile: (String) -> Unit,
    onUpdateProfileById: (String, (ProviderProfile) -> ProviderProfile) -> Unit,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    val allModelPairs = remember(appState.profiles) {
        appState.profiles.flatMap { prof ->
            val models = if (prof.savedModels.isNotEmpty()) prof.savedModels
            else if (prof.defaultModel.isNotBlank()) listOf(prof.defaultModel)
            else emptyList()
            models.map { m -> Triple(prof.id, prof.name, m) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (win.isCompact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BasicText(text = "Default Models", style = p.uiHeaderStyle.copy(fontSize = 19.sp))

        // 1. Global Default Chat Model
        SepiaSectionCard(
            title = "1. Global Default Model",
            subtitle = "Primary model used across new chats and any task set to 'Follow Global Choice'"
        ) {
            if (allModelPairs.isEmpty()) {
                BasicText(
                    text = "No models saved yet. Add or fetch models in the '🖧 Providers' tab first.",
                    style = p.uiLabelStyle
                )
            } else {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    allModelPairs.forEach { (profId, profName, modelId) ->
                        val isGlobal = (defaultModels.globalProfileId == profId && defaultModels.globalModelId == modelId) ||
                            (defaultModels.globalModelId.isBlank() && appState.activeProfileId == profId && appState.profiles.find { it.id == profId }?.defaultModel == modelId)
                        SepiaChip(
                            label = "$profName • $modelId",
                            selected = isGlobal,
                            onClick = {
                                onUpdateDefaultModels(defaultModels.copy(globalProfileId = profId, globalModelId = modelId))
                                onSelectProfile(profId)
                                onUpdateProfileById(profId) { it.copy(defaultModel = modelId) }
                                onUpdateSession { it.copy(profileId = profId, modelOverride = modelId) }
                                onNotify("Set Global Default Model to $profName • $modelId")
                            }
                        )
                    }
                }
            }
        }

        // 2. Title Summary Model
        SepiaSectionCard(
            title = "2. Title Generation Model",
            subtitle = "Automatically generates a concise 3–6 word topic title on the first turn"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaToggleRow(
                    title = "Follow Global Choice (Use Current Global Model)",
                    checked = defaultModels.titleFollowGlobal,
                    onCheckedChange = { onUpdateDefaultModels(defaultModels.copy(titleFollowGlobal = it)) }
                )
                AnimatedVisibility(visible = !defaultModels.titleFollowGlobal) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        allModelPairs.forEach { (profId, profName, modelId) ->
                            SepiaChip(
                                label = "$profName • $modelId",
                                selected = defaultModels.titleProfileId == profId && defaultModels.titleModelId == modelId,
                                onClick = {
                                    onUpdateDefaultModels(defaultModels.copy(titleProfileId = profId, titleModelId = modelId))
                                }
                            )
                        }
                    }
                }
                SepiaTextField(
                    value = defaultModels.titlePromptTemplate,
                    onValueChange = { onUpdateDefaultModels(defaultModels.copy(titlePromptTemplate = it)) },
                    label = "Title Generation Prompt",
                    singleLine = false,
                    minHeight = 52.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 3. Summary / Context Compression Model
        SepiaSectionCard(
            title = "3. Summary & Context Compression Model",
            subtitle = "Used when summarizing long conversations into Rolling Continuity Memory"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaToggleRow(
                    title = "Follow Global Choice (Use Current Global Model)",
                    checked = defaultModels.summaryFollowGlobal,
                    onCheckedChange = { onUpdateDefaultModels(defaultModels.copy(summaryFollowGlobal = it)) }
                )
                AnimatedVisibility(visible = !defaultModels.summaryFollowGlobal) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        allModelPairs.forEach { (profId, profName, modelId) ->
                            SepiaChip(
                                label = "$profName • $modelId",
                                selected = defaultModels.summaryProfileId == profId && defaultModels.summaryModelId == modelId,
                                onClick = {
                                    onUpdateDefaultModels(defaultModels.copy(summaryProfileId = profId, summaryModelId = modelId))
                                }
                            )
                        }
                    }
                }
            }
        }

        // 4. Translation Model
        SepiaSectionCard(
            title = "4. Translation Model",
            subtitle = "Used for translating messages or passages"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaToggleRow(
                    title = "Follow Global Choice (Use Current Global Model)",
                    checked = defaultModels.translationFollowGlobal,
                    onCheckedChange = { onUpdateDefaultModels(defaultModels.copy(translationFollowGlobal = it)) }
                )
                AnimatedVisibility(visible = !defaultModels.translationFollowGlobal) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        allModelPairs.forEach { (profId, profName, modelId) ->
                            SepiaChip(
                                label = "$profName • $modelId",
                                selected = defaultModels.translationProfileId == profId && defaultModels.translationModelId == modelId,
                                onClick = {
                                    onUpdateDefaultModels(defaultModels.copy(translationProfileId = profId, translationModelId = modelId))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================================
// SUBPAGE 5: WORKSPACE
// ============================================================================================
@Composable
private fun WorkspaceSubpage(
    activeSession: ChatSession,
    activeProfile: ProviderProfile,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (win.isCompact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BasicText(text = "Workspace & Local Agent Harness", style = p.uiHeaderStyle.copy(fontSize = 19.sp))

        SepiaSectionCard(
            title = "Sandboxed Local File System Agent",
            subtitle = "Exposes fs_list, fs_read, fs_search, fs_write, fs_edit, dice_roll, agent_memory, and shell_exec"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BasicText(
                        text = if (activeSession.workspacePath.isBlank()) "Mounted Folder: None" else "Mounted: ${activeSession.workspacePath}",
                        style = p.uiBodyStyle.copy(fontFamily = p.codeFontFamily),
                        modifier = Modifier.padding(top = 4.dp, end = 8.dp)
                    )
                    SepiaButton(
                        text = "📂 Mount Folder…",
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = {
                            scope.launch {
                                val picked = PlatformBridge.pickWorkspaceDirectory(activeSession.workspacePath)
                                if (!picked.isNullOrBlank()) {
                                    onUpdateSession {
                                        it.copy(
                                            workspacePath = picked,
                                            workspaceMode = if (it.workspaceMode == WorkspaceMode.OFF) WorkspaceMode.READ_ONLY else it.workspaceMode
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WorkspaceMode.entries.forEach { mode ->
                        SepiaChip(
                            label = mode.label,
                            selected = activeSession.workspaceMode == mode,
                            onClick = { onUpdateSession { it.copy(workspaceMode = mode) } }
                        )
                    }
                }

                SepiaToggleRow(
                    title = "Allow Sandboxed Shell Execution (shell_exec)",
                    subtitle = "Available when Workspace Mode is set to Agent R/W",
                    checked = activeProfile.allowShellExecution,
                    onCheckedChange = { sh -> onUpdateProfile { it.copy(allowShellExecution = sh) } }
                )
            }
        }
    }
}

// ============================================================================================
// SUBPAGE 6: WORLD BOOK & CARDS (.png / .json Character Cards & Lorebook)
// ============================================================================================
@Composable
private fun WorldBookAndCardsSubpage(
    appState: AppStateSnapshot,
    activeSession: ChatSession,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onUpdateGlobalWorldBook: (List<ContextFragment>) -> Unit,
    onSaveCardToLibrary: (CharacterOrScenarioCard) -> Unit,
    onSavePresetToLibrary: (SystemPromptPreset) -> Unit,
    onDeletePresetFromLibrary: (String) -> Unit,
    onUpdateTheme: (ThemeConfig) -> Unit,
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    val scope = rememberCoroutineScope()

    val currentCard = activeSession.activeCard ?: CharacterOrScenarioCard()
    var cardName by remember(activeSession.activeCard) { mutableStateOf(currentCard.name) }
    var cardDesc by remember(activeSession.activeCard) { mutableStateOf(currentCard.description) }
    var cardFirstMes by remember(activeSession.activeCard) { mutableStateOf(currentCard.firstMessage) }
    var cardSysPrompt by remember(activeSession.activeCard) { mutableStateOf(currentCard.systemPrompt) }

    var fragTitle by remember { mutableStateOf("") }
    var fragKeywords by remember { mutableStateOf("") }
    var fragContent by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (win.isCompact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BasicText(text = "World Book & Character Cards (.png / .json)", style = p.uiHeaderStyle.copy(fontSize = 19.sp))

        SepiaSectionCard(
            title = "Import / Export .PNG & .JSON Cards",
            subtitle = "Reads/writes embedded PNG tEXt/iTXt chunks (chara, ccv3, parchment_prompt) and extracts character_book lore"
        ) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SepiaButton(
                    text = "📥 Import .PNG or .JSON",
                    style = SepiaButtonStyle.PRIMARY,
                    onClick = {
                        scope.launch {
                            val picked = PlatformBridge.pickFileForImport(listOf("png", "json")) ?: return@launch
                            CardAndPromptCodec.importFromBytes(picked.fileName, picked.bytes).fold(
                                onSuccess = { res ->
                                    when (res) {
                                        is CardAndPromptCodec.ImportResult.CardImported -> {
                                            val importedCard = res.card
                                            val mergedFrags = (activeSession.fragments + importedCard.embeddedFragments).distinctBy { it.title }
                                            onUpdateSession { it.copy(activeCard = importedCard, fragments = mergedFrags) }
                                            onSaveCardToLibrary(importedCard)
                                            onNotify("Imported '${importedCard.name}' (+${importedCard.embeddedFragments.size} World Book entries).")
                                        }
                                        is CardAndPromptCodec.ImportResult.SystemPromptImported -> {
                                            onUpdateSession { it.copy(systemPrompt = res.preset.systemPrompt) }
                                            onSavePresetToLibrary(res.preset)
                                            onNotify("Imported System Prompt '${res.preset.name}'.")
                                        }
                                        is CardAndPromptCodec.ImportResult.ThemeImported -> {
                                            onUpdateTheme(res.theme)
                                            onNotify("Imported Theme '${res.theme.name}'.")
                                        }
                                    }
                                },
                                onFailure = { err -> onNotify("Import failed: ${err.message}") }
                            )
                        }
                    }
                )
                SepiaButton(
                    text = "📤 Export Card as .PNG",
                    style = SepiaButtonStyle.SECONDARY,
                    onClick = {
                        scope.launch {
                            val card = currentCard.copy(
                                name = cardName.trim().ifBlank { "Card" },
                                description = cardDesc.trim(),
                                firstMessage = cardFirstMes.trim(),
                                systemPrompt = cardSysPrompt.trim()
                            )
                            val bytes = CardAndPromptCodec.exportCardToPngBytes(card)
                            val saved = PlatformBridge.saveFileForExport("${card.name}.card.png", bytes)
                            if (saved != null) onNotify("Exported PNG Card to $saved")
                        }
                    }
                )
            }
        }

        SepiaSectionCard(
            title = "Active Session Character / Scenario Card",
            headerAction = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaButton(
                        text = "Apply to Session",
                        compact = true,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = {
                            val card = currentCard.copy(
                                name = cardName.trim(),
                                description = cardDesc.trim(),
                                firstMessage = cardFirstMes.trim(),
                                systemPrompt = cardSysPrompt.trim()
                            )
                            onUpdateSession { it.copy(activeCard = card) }
                            if (card.name.isNotBlank()) onSaveCardToLibrary(card)
                            onNotify("Applied card '${card.name}'.")
                        }
                    )
                    if (activeSession.activeCard != null) {
                        SepiaButton(
                            text = "Detach",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onUpdateSession { it.copy(activeCard = null) } }
                        )
                    }
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SepiaTextField(value = cardName, onValueChange = { cardName = it }, label = "Name ({{char}})")
                SepiaTextField(value = cardDesc, onValueChange = { cardDesc = it }, label = "Description / Persona", singleLine = false, minHeight = 70.dp)
                SepiaTextField(value = cardFirstMes, onValueChange = { cardFirstMes = it }, label = "Opening Greeting (first_mes)", singleLine = false, minHeight = 60.dp)
            }
        }

        SepiaSectionCard(
            title = "World Book / Lore Fragments (${activeSession.fragments.size} entries)",
            subtitle = "Pinned or keyword-triggered lore entries injected into context automatically"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (win.isCompact) {
                    SepiaTextField(value = fragTitle, onValueChange = { fragTitle = it }, label = "Entry Title", modifier = Modifier.fillMaxWidth())
                    SepiaTextField(value = fragKeywords, onValueChange = { fragKeywords = it }, label = "Trigger Keywords (comma-separated)", modifier = Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SepiaTextField(value = fragTitle, onValueChange = { fragTitle = it }, label = "Entry Title", modifier = Modifier.weight(1f))
                        SepiaTextField(value = fragKeywords, onValueChange = { fragKeywords = it }, label = "Trigger Keywords (comma-separated)", modifier = Modifier.weight(1f))
                    }
                }
                SepiaTextField(value = fragContent, onValueChange = { fragContent = it }, label = "Lore Content", singleLine = false, minHeight = 56.dp)
                SepiaButton(
                    text = "+ Add World Book Entry",
                    compact = true,
                    style = SepiaButtonStyle.PRIMARY,
                    onClick = {
                        if (fragTitle.isNotBlank() && fragContent.isNotBlank()) {
                            val keys = fragKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            val frag = ContextFragment(
                                id = "frag-${PlatformBridge.currentTimeMillis()}",
                                title = fragTitle.trim(),
                                content = fragContent.trim(),
                                keywords = keys,
                                mode = if (keys.isEmpty()) FragmentActivationMode.PINNED else FragmentActivationMode.KEYWORD
                            )
                            onUpdateSession { it.copy(fragments = it.fragments + frag) }
                            fragTitle = ""
                            fragKeywords = ""
                            fragContent = ""
                        }
                    }
                )

                activeSession.fragments.forEach { frag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(p.elevatedBg.copy(alpha = 0.45f))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            BasicText(text = frag.title, style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold))
                            BasicText(text = frag.content, maxLines = 2, overflow = TextOverflow.Ellipsis, style = p.uiLabelStyle)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SepiaChip(
                                label = frag.mode.label.substringBefore(" "),
                                selected = frag.mode != FragmentActivationMode.MUTED,
                                onClick = {
                                    val next = when (frag.mode) {
                                        FragmentActivationMode.PINNED -> FragmentActivationMode.KEYWORD
                                        FragmentActivationMode.KEYWORD -> FragmentActivationMode.MUTED
                                        FragmentActivationMode.MUTED -> FragmentActivationMode.PINNED
                                    }
                                    onUpdateSession { s ->
                                        s.copy(fragments = s.fragments.map { if (it.id == frag.id) it.copy(mode = next) else it })
                                    }
                                }
                            )
                            SepiaButton(
                                text = "×",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = {
                                    onUpdateSession { s -> s.copy(fragments = s.fragments.filterNot { it.id == frag.id }) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================================
// SUBPAGE 7: BACKUP & OTA
// ============================================================================================
@Composable
private fun BackupAndOtaSubpage(
    appState: AppStateSnapshot,
    otaState: OtaUpdateState,
    onUpdateOtaSettings: (String, Boolean) -> Unit,
    onCheckOtaNow: () -> Unit,
    onInstallOtaRelease: (OtaReleaseInfo) -> Unit,
    onRestoreFullBackup: (AppStateSnapshot) -> Unit,
    onWipeAllData: () -> Unit,
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (win.isCompact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BasicText(text = "Backup & In-App OTA Updates", style = p.uiHeaderStyle.copy(fontSize = 19.sp))

        SepiaSectionCard(
            title = "In-App OTA Updates (Android .APK & Windows .EXE)",
            subtitle = "Installed Version: v${OtaUpdateEngine.currentAppVersion} • Platform: ${PlatformBridge.platformName}"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaTextField(
                    value = appState.otaGithubRepo,
                    onValueChange = { repo -> onUpdateOtaSettings(repo, appState.autoCheckOtaOnStartup) },
                    label = "GitHub Release Repository (owner/repo)",
                    monospace = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SepiaToggleRow(
                    title = "Auto-Check for OTA Updates on Launch",
                    checked = appState.autoCheckOtaOnStartup,
                    onCheckedChange = { onUpdateOtaSettings(appState.otaGithubRepo, it) }
                )
                SepiaButton(
                    text = if (otaState is OtaUpdateState.Checking) "Checking…" else "↻ Check for Update Now",
                    style = SepiaButtonStyle.PRIMARY,
                    onClick = onCheckOtaNow
                )
                when (otaState) {
                    is OtaUpdateState.Available -> {
                        val rel = otaState.release
                        val sizeMb = (rel.assetSizeBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                        BasicText(
                            text = "New Release Available: ${rel.tagName} (${rel.assetName} • $sizeMb MB)",
                            style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                        )
                        SepiaButton(
                            text = "⬇ Download & Install ${rel.tagName} In-App",
                            style = SepiaButtonStyle.PRIMARY,
                            onClick = { onInstallOtaRelease(rel) }
                        )
                    }
                    is OtaUpdateState.UpToDate -> {
                        BasicText(
                            text = "You are on the latest version (v${otaState.currentVersion}).",
                            style = p.uiBodyStyle.copy(color = p.accentSecondary)
                        )
                        otaState.latestRelease?.let { rel ->
                            SepiaButton(
                                text = "Force Re-Install ${rel.tagName}",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = { onInstallOtaRelease(rel) }
                            )
                        }
                    }
                    is OtaUpdateState.Downloading -> {
                        val downMb = (otaState.downloadedBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                        val totalMb = (otaState.totalBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                        BasicText(
                            text = "Downloading ${otaState.release.assetName}: $downMb MB / $totalMb MB (${otaState.progressPercent}%)…",
                            style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                        )
                    }
                    is OtaUpdateState.Installing -> {
                        BasicText(text = otaState.message, style = p.uiBodyStyle.copy(color = p.accentSecondary))
                    }
                    is OtaUpdateState.Error -> {
                        BasicText(text = "OTA Error: ${otaState.message}", style = p.uiBodyStyle.copy(color = p.danger))
                    }
                    else -> {}
                }
            }
        }

        SepiaSectionCard(
            title = "Portable Full Backup & Zero-Trace Storage",
            subtitle = "Storage directory: ${PlatformBridge.getAppStorageDirectoryPath()}"
        ) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SepiaButton(
                    text = "📤 Export Full Backup (.json)",
                    style = SepiaButtonStyle.PRIMARY,
                    onClick = {
                        scope.launch {
                            val bytes = CardAndPromptCodec.prettyJson.encodeToString(AppStateSnapshot.serializer(), appState).encodeToByteArray()
                            val saved = PlatformBridge.saveFileForExport("parchment-full-backup.json", bytes)
                            if (saved != null) onNotify("Exported backup to $saved")
                        }
                    }
                )
                SepiaButton(
                    text = "📥 Restore Full Backup (.json)",
                    style = SepiaButtonStyle.SECONDARY,
                    onClick = {
                        scope.launch {
                            val picked = PlatformBridge.pickFileForImport(listOf("json")) ?: return@launch
                            runCatching {
                                CardAndPromptCodec.prettyJson.decodeFromString<AppStateSnapshot>(picked.bytes.decodeToString())
                            }.fold(
                                onSuccess = { restored -> onRestoreFullBackup(restored) },
                                onFailure = { err -> onNotify("Backup restore failed: ${err.message}") }
                            )
                        }
                    }
                )
                SepiaButton(
                    text = "Wipe All Local Data & Reset",
                    style = SepiaButtonStyle.DANGER,
                    onClick = onWipeAllData
                )
            }
        }
    }
}

/**
 * Selects a sensible initial chat model when a brand-new profile fetches models for the first time,
 * skipping utility/research/embedding endpoints like `deep-research*` or `text-embedding*`.
 */
fun pickBestInitialChatModel(models: List<String>): String {
    if (models.isEmpty()) return ""
    val nonUtility = models.filterNot { m ->
        val low = m.lowercase()
        low.contains("deep-research") ||
            low.contains("embedding") ||
            low.contains("tts") ||
            low.contains("whisper") ||
            low.contains("moderation") ||
            low.contains("aqa") ||
            low.contains("imagen") ||
            low.contains("veo")
    }
    return nonUtility.firstOrNull() ?: models.first()
}
