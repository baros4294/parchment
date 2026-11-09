package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.harness.WorkspaceHarness
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
import io.parchment.ai.model.CUSTOM_DIALECTS
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.customDialectLabel
import io.parchment.ai.model.SendShortcutMode
import io.parchment.ai.model.SystemPromptPreset
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.model.ViewMode
import io.parchment.ai.model.WorkspaceMode
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.AgentPermissionOverrides
import io.parchment.ai.model.AgentPermissionPolicy
import io.parchment.ai.model.asOverrides
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.ModelsDevClient
import io.parchment.ai.network.OtaReleaseInfo
import io.parchment.ai.network.OtaUpdateEngine
import io.parchment.ai.network.OtaUpdateState
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.protocol.CardAndPromptCodec
import kotlinx.coroutines.launch

enum class SettingsCategory(val icon: String, val label: String) {
    PREFERENCES("🖥", "Preferences"),
    PROVIDERS("🖧", "Providers"),
    ASSISTANTS("🤖", "Assistant"),
    DEFAULT_MODEL("♡", "Default Model"),
    WORKSPACE("📂", "Workspace"),
    WORLD_BOOK("📖", "World Book & Cards"),
    BACKUP_OTA("☁", "Storage & OTA")
}

@Composable
fun ParchmentSettingsScreen(
    appState: AppStateSnapshot,
    activeProfile: ProviderProfile,
    activeSession: ChatSession,
    initialCategory: SettingsCategory,
    startAtMobileRoot: Boolean = false,
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
    onImportChatSessions: (List<ChatSession>) -> Unit = {},
    onWipeAllData: () -> Unit,
    onDeleteChatSessionById: (String) -> Unit = {},
    onInspectAttachment: (io.parchment.ai.model.PromptAttachment) -> Unit = {},
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SepiaTextField(
                            value = settingsSearchQuery,
                            onValueChange = { settingsSearchQuery = it },
                            placeholder = "🔍 Search all settings…",
                            minHeight = 36.dp,
                            modifier = Modifier.weight(1f)
                        )
                        if (settingsSearchQuery.isNotEmpty()) {
                            SepiaButton(
                                text = "×",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = { settingsSearchQuery = "" }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    val categoryMatchCounts = remember(settingsSearchQuery, appState, activeProfile, activeSession) {
                        val q = settingsSearchQuery.trim()
                        if (q.isEmpty()) emptyMap()
                        else SettingsCategory.entries.associateWith { cat ->
                            countCategorySearchMatches(cat, q, appState, activeProfile, activeSession)
                        }
                    }

                    SettingsCategory.entries.forEach { cat ->
                        val isSelected = selectedCategory == cat
                        val matchCount = categoryMatchCounts[cat] ?: 0
                        val isHighlightedBySearch = settingsSearchQuery.isNotBlank() && matchCount > 0
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
                                    onClick = {
                                        selectedCategory = cat
                                        settingsSearchQuery = ""
                                    }
                                )
                                .clip(shape)
                                .background(
                                    when {
                                        isSelected -> p.accent.copy(alpha = 0.16f)
                                        isHighlightedBySearch -> p.accent.copy(alpha = 0.10f)
                                        isPressed -> p.accent.copy(alpha = 0.12f)
                                        isHovered -> p.accent.copy(alpha = 0.08f)
                                        else -> Color.Transparent
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                KissVectorIcon(
                                    icon = catIcon,
                                    tint = if (isSelected || isHovered || isPressed || isHighlightedBySearch) p.accent else p.narrationInk,
                                    size = 15.dp
                                )
                                BasicText(
                                    text = cat.label,
                                    style = p.uiBodyStyle.copy(
                                        color = if (isSelected || isHovered || isPressed || isHighlightedBySearch) p.accent else p.primaryInk,
                                        fontWeight = if (isSelected || isHighlightedBySearch) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = 13.5.sp
                                    )
                                )
                            }
                            if (settingsSearchQuery.isNotBlank() && matchCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(p.accent.copy(alpha = 0.18f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    BasicText(
                                        text = matchCount.toString(),
                                        style = p.monoStyle.copy(fontSize = 10.sp, color = p.accent, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
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
                        onJumpToCategory = { targetCat ->
                            selectedCategory = targetCat
                            settingsSearchQuery = ""
                        },
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
                        onImportChatSessions = onImportChatSessions,
                        onWipeAllData = onWipeAllData,
                        onDeleteChatSessionById = onDeleteChatSessionById,
                        onInspectAttachment = onInspectAttachment,
                        onNotify = onNotify
                    )
                }
            }
        } else {
            // Compact / Portrait Mobile Layout:
            // Hierarchical Vertical Root Menu List -> Subpage Drill-Down (Zero awkward horizontal chip strip!)
            var mobileOpenedCategory by remember(initialCategory, startAtMobileRoot) {
                mutableStateOf<SettingsCategory?>(if (startAtMobileRoot) null else initialCategory)
            }
            var dragX by remember { mutableStateOf(0f) }

            LaunchedEffect(mobileOpenedCategory, settingsSearchQuery) {
                PlatformBridge.registerBackHandler {
                    when {
                        settingsSearchQuery.isNotEmpty() -> {
                            settingsSearchQuery = ""
                            true
                        }
                        mobileOpenedCategory != null -> {
                            mobileOpenedCategory = null
                            true
                        }
                        else -> {
                            onClose()
                            true
                        }
                    }
                }
            }

            fun categorySubtitle(cat: SettingsCategory): String = when (cat) {
                SettingsCategory.PREFERENCES -> "Theme (${appState.theme.name.substringBefore(" (")}), fonts, display & behavior"
                SettingsCategory.PROVIDERS -> "${activeProfile.name} • ${appState.profiles.size} provider profile(s)"
                SettingsCategory.ASSISTANTS -> "${appState.assistants.size} assistant persona(s) & system prompts"
                SettingsCategory.DEFAULT_MODEL -> {
                    val curModel = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }.ifBlank { "Not selected" }
                    "Global, topic title, summary & translation ($curModel)"
                }
                SettingsCategory.WORKSPACE -> "Agent approvals, file access, web and shell controls"
                SettingsCategory.WORLD_BOOK -> "Character cards (PNG/JSON) & ${appState.globalWorldBook.size + activeSession.fragments.size} lore fragment(s)"
                SettingsCategory.BACKUP_OTA -> "Storage manager, v${OtaUpdateEngine.currentAppVersion} OTA & JSON backups"
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(mobileOpenedCategory, settingsSearchQuery) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragX = 0f },
                            onHorizontalDrag = { _, dragAmount -> dragX += dragAmount },
                            onDragEnd = {
                                if (dragX > 72.dp.toPx()) {
                                    if (settingsSearchQuery.isNotEmpty()) {
                                        settingsSearchQuery = ""
                                    } else if (mobileOpenedCategory != null) {
                                        mobileOpenedCategory = null
                                    } else {
                                        onClose()
                                    }
                                }
                                dragX = 0f
                            }
                        )
                    }
            ) {
                // Mobile Top Header Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.sheetBg.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val openedCat = mobileOpenedCategory
                        if (openedCat != null && settingsSearchQuery.isBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                SepiaButton(
                                    text = "← Settings",
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { mobileOpenedCategory = null }
                                )
                                val catIcon = remember(openedCat.icon) {
                                    parseKissLabelIcons(openedCat.icon).leadingIcon ?: KissIcon.SETTINGS
                                }
                                KissVectorIcon(icon = catIcon, tint = p.accent, size = 15.dp)
                                BasicText(
                                    text = openedCat.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiHeaderStyle.copy(fontSize = 16.sp)
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                KissVectorIcon(icon = KissIcon.SETTINGS, tint = p.accent, size = 17.dp)
                                BasicText(
                                    text = "Settings",
                                    style = p.uiHeaderStyle.copy(fontSize = 18.sp)
                                )
                            }
                        }
                        SepiaButton(
                            text = "← Chat",
                            compact = true,
                            style = SepiaButtonStyle.PRIMARY,
                            onClick = onClose
                        )
                    }

                    if (mobileOpenedCategory == null || settingsSearchQuery.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SepiaTextField(
                                value = settingsSearchQuery,
                                onValueChange = { settingsSearchQuery = it },
                                placeholder = "🔍 Search all settings…",
                                minHeight = 36.dp,
                                modifier = Modifier.weight(1f)
                            )
                            if (settingsSearchQuery.isNotEmpty()) {
                                SepiaButton(
                                    text = "×",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { settingsSearchQuery = "" }
                                )
                            }
                        }
                    }
                }
                SepiaDivider()

                Crossfade(
                    targetState = Pair(mobileOpenedCategory, settingsSearchQuery.isNotBlank()),
                    animationSpec = tween(160),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) { (openedCat, isSearching) ->
                    if (isSearching || openedCat != null) {
                        SettingsSubpageContent(
                            category = openedCat ?: selectedCategory,
                            searchQuery = settingsSearchQuery,
                            appState = appState,
                            activeProfile = activeProfile,
                            activeSession = activeSession,
                            otaState = otaState,
                            onJumpToCategory = { targetCat ->
                                selectedCategory = targetCat
                                mobileOpenedCategory = targetCat
                                settingsSearchQuery = ""
                            },
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
                            onImportChatSessions = onImportChatSessions,
                            onWipeAllData = onWipeAllData,
                            onDeleteChatSessionById = onDeleteChatSessionById,
                            onInspectAttachment = onInspectAttachment,
                            onNotify = onNotify
                        )
                    } else {
                        // Mobile Vertical Settings Root Menu List
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SettingsCategory.entries.forEach { cat ->
                                val catIcon = remember(cat.icon) {
                                    parseKissLabelIcons(cat.icon).leadingIcon ?: KissIcon.SETTINGS
                                }
                                val cardShape = RoundedCornerShape(12.dp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(cardShape)
                                        .background(p.cardBg)
                                        .border(0.85.dp, p.border, cardShape)
                                        .kissClickable(pressScale = 0.985f) {
                                            selectedCategory = cat
                                            mobileOpenedCategory = cat
                                        }
                                        .padding(horizontal = 14.dp, vertical = 13.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f).padding(end = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(9.dp))
                                                .background(p.accent.copy(alpha = 0.14f))
                                                .border(0.8.dp, p.accent.copy(alpha = 0.45f), RoundedCornerShape(9.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            KissVectorIcon(icon = catIcon, tint = p.accent, size = 17.dp)
                                        }
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            BasicText(
                                                text = cat.label,
                                                style = p.uiHeaderStyle.copy(fontSize = 15.sp, color = p.primaryInk)
                                            )
                                            BasicText(
                                                text = categorySubtitle(cat),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = p.uiLabelStyle.copy(fontSize = 11.5.sp, color = p.narrationInk)
                                            )
                                        }
                                    }
                                    KissVectorIcon(
                                        icon = KissIcon.CHEVRON_RIGHT,
                                        tint = p.mutedInk,
                                        size = 15.dp
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

@Composable
private fun SettingsSubpageContent(
    category: SettingsCategory,
    searchQuery: String,
    appState: AppStateSnapshot,
    activeProfile: ProviderProfile,
    activeSession: ChatSession,
    otaState: OtaUpdateState,
    onJumpToCategory: (SettingsCategory) -> Unit,
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
    onImportChatSessions: (List<ChatSession>) -> Unit,
    onWipeAllData: () -> Unit,
    onDeleteChatSessionById: (String) -> Unit = {},
    onInspectAttachment: (io.parchment.ai.model.PromptAttachment) -> Unit = {},
    onNotify: (String) -> Unit
) {
    if (searchQuery.trim().isNotEmpty()) {
        GlobalSettingsSearchResultsSubpage(
            searchQuery = searchQuery.trim(),
            appState = appState,
            activeProfile = activeProfile,
            activeSession = activeSession,
            onJumpToCategory = onJumpToCategory,
            onSelectProfile = onSelectProfile,
            onUpdateProfile = onUpdateProfile,
            onUpdateProfileById = onUpdateProfileById,
            onUpdatePreferences = onUpdatePreferences,
            onUpdateTheme = onUpdateTheme,
            onUpdateSession = onUpdateSession,
            onUpdateDefaultModels = onUpdateDefaultModels,
            onUpdateOtaSettings = onUpdateOtaSettings,
            onCheckOtaNow = onCheckOtaNow,
            onNotify = onNotify
        )
        return
    }

    when (category) {
        SettingsCategory.PREFERENCES -> PreferencesSubpage(
            prefs = appState.preferences,
            theme = appState.theme,
            activeSession = activeSession,
            searchQuery = "",
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
            appState = appState,
            activeSession = activeSession,
            activeProfile = activeProfile,
            onUpdateSession = onUpdateSession,
            onUpdateProfile = onUpdateProfile,
            onNotify = onNotify
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
            activeSession = activeSession,
            otaState = otaState,
            onUpdateOtaSettings = onUpdateOtaSettings,
            onCheckOtaNow = onCheckOtaNow,
            onInstallOtaRelease = onInstallOtaRelease,
            onRestoreFullBackup = onRestoreFullBackup,
            onImportChatSessions = onImportChatSessions,
            onWipeAllData = onWipeAllData,
            onDeleteChatSessionById = onDeleteChatSessionById,
            onInspectAttachment = onInspectAttachment,
            onNotify = onNotify
        )
    }
}

// ============================================================================================
// SUBPAGE 1: PREFERENCES
// ============================================================================================
@Composable
private fun ThemePresetChoices(theme: ThemeConfig, onUpdateTheme: (ThemeConfig) -> Unit) {
    val p = LocalParchmentPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("Color Mode & Preset", style = p.uiBodyStyle.copy(fontWeight = FontWeight.Medium))
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            ThemeConfig.PRESETS.forEach { preset ->
                val selected = theme.name == preset.name
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected, role = Role.RadioButton, onClick = { onUpdateTheme(preset) })
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BasicText(preset.name.substringBefore(" ("), modifier = Modifier.weight(1f), style = p.uiBodyStyle)
                    KissVectorIcon(if (selected) KissIcon.CIRCLE_DOT else KissIcon.CIRCLE_EMPTY,
                        tint = if (selected) p.accent else p.mutedInk, size = 20.dp)
                }
            }
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
                    ThemePresetChoices(theme, onUpdateTheme)

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

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SepiaTextField(
                            value = proseSizeText,
                            onValueChange = { v ->
                                proseSizeText = v
                                v.toFloatOrNull()?.takeIf { it > 4f }?.let { onUpdateTheme(theme.copy(proseFontSizeSp = it)) }
                            },
                            label = "Prose Font Size (sp)",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        SepiaTextField(
                            value = codeSizeText,
                            onValueChange = { v ->
                                codeSizeText = v
                                v.toFloatOrNull()?.takeIf { it > 4f }?.let { onUpdateTheme(theme.copy(codeFontSizeSp = it)) }
                            },
                            label = "Code Font Size (sp)",
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
                        label = "Max Reading Width (dp)",
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
                        title = "Show Assistant Avatar in Title Bar",
                        checked = prefs.showAssistantAvatarInTitleBar,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showAssistantAvatarInTitleBar = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show User Avatar",
                        checked = prefs.showUserAvatar,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showUserAvatar = it)) }
                    )
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
                        title = "Show Model Icon / Avatar",
                        checked = prefs.showModelIcon,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showModelIcon = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Show Model Name",
                        checked = prefs.showModelName,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(showModelName = it)) }
                    )
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
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Collapse Long Messages (> 1,800 chars)",
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
                                    text = "Android Unrestricted Background Mode",
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
                        title = "Auto switch to Topics",
                        checked = prefs.autoSwitchToTopics,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoSwitchToTopics = it)) }
                    )
                    SepiaDivider()
                    SepiaToggleRow(
                        title = "Auto-collapse Thinking",
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
                        title = "Auto-compact context when reaching 85% of context limit",
                        checked = prefs.autoCompactWhenNearContextLimit,
                        onCheckedChange = { onUpdatePreferences(prefs.copy(autoCompactWhenNearContextLimit = it)) }
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
    var mobileEditingProfileId by remember { mutableStateOf<String?>(null) }

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
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(if (isSel) p.accent.copy(alpha = 0.16f) else p.elevatedBg)
                                            .border(0.8.dp, if (isSel) p.accent else p.border, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ModelLabSvgIcon(
                                            modelOrProvider = prof.defaultModel.ifBlank { prof.name },
                                            tint = if (isSel) p.accent else p.narrationInk,
                                            size = 14.dp,
                                            fallbackProtocolName = prof.protocol.displayName
                                        )
                                    }
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
                    // Mobile Hierarchical Provider List -> Provider Detail Subpage Drill-Down
                    if (mobileEditingProfileId == null) {
                        BasicText(
                            text = "Select a Provider Profile to Configure",
                            style = p.uiHeaderStyle.copy(fontSize = 16.sp)
                        )
                        appState.profiles.forEach { prof ->
                            val isSel = prof.id == activeProfile.id
                            val statusOn = prof.enabled && (prof.apiKey.isNotBlank() || prof.baseUrl != prof.protocol.defaultBaseUrl)
                            val shape = RoundedCornerShape(11.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .background(p.cardBg)
                                    .border(
                                        width = if (isSel) 1.2.dp else 0.85.dp,
                                        color = if (isSel) p.accent else p.border,
                                        shape = shape
                                    )
                                    .kissClickable(pressScale = 0.985f) {
                                        onSelectProfile(prof.id)
                                        mobileEditingProfileId = prof.id
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).padding(end = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(if (isSel) p.accent.copy(alpha = 0.16f) else p.elevatedBg)
                                            .border(0.8.dp, if (isSel) p.accent else p.border, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ModelLabSvgIcon(
                                            modelOrProvider = prof.defaultModel.ifBlank { prof.name },
                                            tint = if (isSel) p.accent else p.narrationInk,
                                            size = 17.dp,
                                            fallbackProtocolName = prof.protocol.displayName
                                        )
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        BasicText(
                                            text = prof.name,
                                            style = p.uiHeaderStyle.copy(fontSize = 14.5.sp)
                                        )
                                        val modelCountInfo = if (prof.savedModels.isNotEmpty()) "${prof.savedModels.size} models" else prof.defaultModel.ifBlank { "No models yet" }
                                        BasicText(
                                            text = "${prof.protocol.displayName.substringBefore(" API")} • $modelCountInfo",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.narrationInk)
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
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
                                    KissVectorIcon(icon = KissIcon.CHEVRON_RIGHT, tint = p.mutedInk, size = 15.dp)
                                }
                            }
                        }

                        SepiaButton(
                            text = "+ Add Provider Profile",
                            style = SepiaButtonStyle.OUTLINE,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showAddProviderMenu = !showAddProviderMenu }
                        )
                        AnimatedVisibility(visible = showAddProviderMenu) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ProviderProtocol.entries.forEach { proto ->
                                    SepiaButton(
                                        text = "+ ${proto.displayName.substringBefore(" API")}",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            onAddProfile(proto)
                                            showAddProviderMenu = false
                                            mobileEditingProfileId = "newly-added"
                                        }
                                    )
                                }
                            }
                        }
                        return@Column
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SepiaButton(
                                text = "← All Providers",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = { mobileEditingProfileId = null }
                            )
                            BasicText(
                                text = activeProfile.name,
                                style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                            )
                        }
                        SepiaDivider()
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

                // Main Provider Detail Header with Inline Editable Name + Enable Switch
                var isRenamingProvider by remember(activeProfile.id) { mutableStateOf(false) }
                var providerNameBuffer by remember(activeProfile.id, activeProfile.name) { mutableStateOf(activeProfile.name) }

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
                        if (isRenamingProvider) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SepiaTextField(
                                    value = providerNameBuffer,
                                    onValueChange = { providerNameBuffer = it },
                                    placeholder = "Provider Name…",
                                    minHeight = 32.dp,
                                    modifier = Modifier.width(210.dp)
                                )
                                SepiaButton(
                                    text = "Save",
                                    compact = true,
                                    style = SepiaButtonStyle.PRIMARY,
                                    onClick = {
                                        val cleanName = providerNameBuffer.trim().ifBlank { "Custom Provider" }
                                        onUpdateProfileById(activeProfile.id) { it.copy(name = cleanName) }
                                        isRenamingProvider = false
                                    }
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .kissClickable { isRenamingProvider = true }
                                    .padding(top = 2.dp)
                            ) {
                                ModelLabSvgIcon(
                                    modelOrProvider = activeProfile.defaultModel.ifBlank { activeProfile.name },
                                    tint = p.accent,
                                    size = 17.dp,
                                    fallbackProtocolName = activeProfile.protocol.displayName
                                )
                                BasicText(
                                    text = activeProfile.name,
                                    style = p.uiHeaderStyle.copy(fontSize = 18.sp)
                                )
                                KissVectorIcon(
                                    icon = KissIcon.EDIT,
                                    tint = p.mutedInk,
                                    size = 13.dp
                                )
                            }
                        }
                        SepiaButton(
                            text = "⚙ Protocol & Params",
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = { showProtocolSettingsSubpage = true }
                        )
                        if (appState.profiles.size > 1) {
                            SepiaButton(
                                text = "🗑",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = {
                                    onDeleteProfile(activeProfile.id)
                                    mobileEditingProfileId = null
                                }
                            )
                        }
                    }
                    SepiaToggleSwitch(
                        checked = activeProfile.enabled,
                        onCheckedChange = { en -> onUpdateProfileById(activeProfile.id) { it.copy(enabled = en) } }
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
                                text = if (showApiKey) "🙈" else "👁",
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
                                                val secStr = formatDurationFromMs(ms)
                                                onNotify("✓ Connection OK ($secStr • ${list.size} models reachable)")
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
                                onUpdateProfileById(activeProfile.id) { it.copy(apiKey = newVal.trim()) }
                            }
                        },
                        placeholder = "🔑 Paste API Key (toggle visibility above to edit masked key)…",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // API Base URL (Keyed by activeProfile.id with 1-tap Clear / Reset Default buttons)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = "API Base URL",
                            style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold, color = p.narrationInk)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (activeProfile.baseUrl.isNotEmpty()) {
                                SepiaButton(
                                    text = "× Clear URL",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { onUpdateProfileById(activeProfile.id) { it.copy(baseUrl = "") } }
                                )
                            }
                            if (activeProfile.baseUrl != activeProfile.protocol.defaultBaseUrl) {
                                SepiaButton(
                                    text = "↺ Default URL",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = {
                                        onUpdateProfileById(activeProfile.id) { it.copy(baseUrl = activeProfile.protocol.defaultBaseUrl) }
                                    }
                                )
                            }
                        }
                    }
                    SepiaTextField(
                        value = activeProfile.baseUrl,
                        onValueChange = { url -> onUpdateProfileById(activeProfile.id) { it.copy(baseUrl = url) } },
                        placeholder = activeProfile.protocol.defaultBaseUrl,
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (activeProfile.protocol.isCustomWire) {
                        Spacer(Modifier.height(6.dp))
                        BasicText(
                            text = "Wire Dialect",
                            style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.SemiBold)
                        )
                        BasicText(
                            text = "The URL above is sent verbatim. Pick the payload shape your endpoint speaks.",
                            style = p.uiLabelStyle.copy(color = p.mutedInk, fontSize = 11.sp)
                        )
                        Spacer(Modifier.height(4.dp))
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CUSTOM_DIALECTS.forEach { dialect ->
                                SepiaChip(
                                    label = customDialectLabel(dialect),
                                    selected = activeProfile.customDialect == dialect,
                                    onClick = {
                                        onUpdateProfileById(activeProfile.id) { it.copy(customDialect = dialect) }
                                    }
                                )
                            }
                        }
                        if (activeProfile.customDialect.isBlank()) {
                            Spacer(Modifier.height(4.dp))
                            BasicText(
                                text = "No dialect selected — Send stays blocked until you choose one.",
                                style = p.uiLabelStyle.copy(color = p.danger, fontSize = 11.sp)
                            )
                        }
                    }
                }

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

                var selectedVendorFamily by remember(activeProfile.id) { mutableStateOf("") }
                val vendorFamilies = remember(activeProfile.savedModels) {
                    activeProfile.savedModels
                        .filter { it.contains('/') }
                        .groupBy { it.substringBefore('/').trim() }
                        .map { (vendor, list) -> vendor to list.size }
                        .sortedByDescending { it.second }
                }

                if (activeProfile.savedModels.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SepiaTextField(
                            value = modelSearch,
                            onValueChange = { modelSearch = it },
                            placeholder = "🔍 Filter ${activeProfile.savedModels.size} models…",
                            minHeight = 34.dp,
                            modifier = Modifier.weight(1f)
                        )
                        if (modelSearch.isNotEmpty() || selectedVendorFamily.isNotEmpty()) {
                            SepiaButton(
                                text = "×",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = {
                                    modelSearch = ""
                                    selectedVendorFamily = ""
                                }
                            )
                        }
                    }
                }

                if (vendorFamilies.size >= 2) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        SepiaChip(
                            label = "All Families (${activeProfile.savedModels.size})",
                            selected = selectedVendorFamily.isEmpty(),
                            onClick = { selectedVendorFamily = "" }
                        )
                        vendorFamilies.forEach { (vendor, count) ->
                            SepiaChip(
                                label = "$vendor ($count)",
                                selected = selectedVendorFamily.equals(vendor, ignoreCase = true),
                                onClick = {
                                    selectedVendorFamily = if (selectedVendorFamily.equals(vendor, ignoreCase = true)) "" else vendor
                                }
                            )
                        }
                    }
                }

                // Grouped & Bounded Vertical Model List Card
                val visibleModels = remember(activeProfile.savedModels, modelSearch, selectedVendorFamily) {
                    val tokens = modelSearch.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    activeProfile.savedModels.filter { m ->
                        val vendOk = selectedVendorFamily.isEmpty() ||
                            (m.contains('/') && m.substringBefore('/').trim().equals(selectedVendorFamily, ignoreCase = true))
                        val searchOk = tokens.isEmpty() || tokens.all { tok -> m.lowercase().contains(tok) }
                        vendOk && searchOk
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(p.cardBg)
                        .border(0.85.dp, p.border.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (visibleModels.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(8.dp)
                        ) {
                            KissVectorIcon(icon = KissIcon.REFRESH, tint = p.mutedInk, size = 13.dp)
                            BasicText(
                                text = "No models added yet. Type a model ID above or click Fetch.",
                                style = p.uiLabelStyle
                            )
                        }
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
                                    ModelLabSvgIcon(
                                        modelOrProvider = modelId,
                                        tint = if (isSelectedModel || rowHovered) p.accent else p.narrationInk,
                                        size = 14.5.dp,
                                        fallbackProtocolName = activeProfile.protocol.displayName
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
internal fun ProviderProtocolSubpage(
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
            title = "Identity, Wire Protocol & Endpoint Override"
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
                    label = "Custom Endpoint Path (Optional — default ${profile.protocol.defaultEndpointPath})",
                    placeholder = profile.protocol.defaultEndpointPath,
                    monospace = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (profile.protocol.isCustomWire) {
                    BasicText(
                        text = "Wire Dialect",
                        style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.SemiBold)
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CUSTOM_DIALECTS.forEach { dialect ->
                            SepiaChip(
                                label = customDialectLabel(dialect),
                                selected = profile.customDialect == dialect,
                                onClick = { onUpdateProfile { it.copy(customDialect = dialect) } }
                            )
                        }
                    }
                }
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
                if (profile.protocol == ProviderProtocol.GEMINI_INTERACTIONS) {
                    SepiaToggleRow(
                        title = "Stateful Chain (previous_interaction_id)",
                        subtitle = "Off by default: full history is sent locally and nothing is stored provider-side.",
                        checked = profile.useStatefulChaining,
                        onCheckedChange = { sc -> onUpdateProfile { it.copy(useStatefulChaining = sc) } }
                    )
                    SepiaToggleRow(
                        title = "Background Poll & Stream Recovery",
                        checked = profile.useBackgroundLongDecode,
                        onCheckedChange = { bg -> onUpdateProfile { it.copy(useBackgroundLongDecode = bg) } }
                    )
                }
                SepiaToggleRow(
                    title = "Smart Legacy Endpoint Fallback (:streamGenerateContent or /v1/chat/completions)",
                    checked = profile.useLegacyFallbackEndpoint,
                    onCheckedChange = { leg -> onUpdateProfile { it.copy(useLegacyFallbackEndpoint = leg) } }
                )
            }
        }

        SepiaSectionCard(
            title = "Nullable Sampling & Token Parameters (Blank = Strip from API, 0 = ∞)"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        label = "Temperature (blank = omit)",
                        placeholder = "Omitted",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
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
                        label = "Top-P (blank = omit)",
                        placeholder = "Omitted",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

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
                    label = "Max Output Tokens (0 / blank = ∞)",
                    placeholder = "Unlimited",
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
                    label = "Legacy Thinking Budget (pre-4.7 only — blank = adaptive)",
                    placeholder = "Adaptive",
                    monospace = true,
                    modifier = Modifier.fillMaxWidth()
                )
                BasicText(
                    text = "Deprecated: Claude 4.7+ rejects budget_tokens with HTTP 400. Leave blank to use adaptive thinking steered by effort.",
                    style = p.uiLabelStyle.copy(color = p.mutedInk, fontSize = 11.sp)
                )

                if (profile.protocol == ProviderProtocol.ANTHROPIC_MESSAGES && (profile.maxOutputTokens == null || profile.maxOutputTokens <= 0)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KissVectorIcon(icon = KissIcon.WARNING, tint = p.accentSecondary, size = 13.5.dp)
                        BasicText(
                            text = "Info: Anthropic's /v1/messages endpoint requires a positive `max_tokens` field in JSON; when left blank or 0, Parchment sends 64000 so output is uncapped.",
                            style = p.uiLabelStyle.copy(color = p.accentSecondary),
                            modifier = Modifier.weight(1f)
                        )
                    }
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
    val initialPricing = profile.tokenPricing(modelId)

    var ctxText by remember(modelId, existingSpec) {
        mutableStateOf((existingSpec?.contextWindowTokens ?: profile.contextWindowTokens).toString())
    }
    var maxOutText by remember(modelId, existingSpec) {
        mutableStateOf(existingSpec?.maxOutputTokens?.toString() ?: profile.maxOutputTokens?.toString() ?: "")
    }
    var inPriceText by remember(modelId, existingSpec) {
        mutableStateOf(initialPricing.inputPerM?.toString() ?: "")
    }
    var outPriceText by remember(modelId, existingSpec) {
        mutableStateOf(initialPricing.outputPerM?.toString() ?: "")
    }
    var cachePriceText by remember(modelId, existingSpec) {
        mutableStateOf(initialPricing.cacheReadPerM?.toString() ?: "")
    }
    var thinkPriceText by remember(modelId, existingSpec) {
        mutableStateOf(initialPricing.reasoningPerM?.toString() ?: "")
    }

    var writePriceText by remember(modelId, existingSpec) { mutableStateOf(initialPricing.cacheWritePerM?.toString() ?: "") }
    var longWritePriceText by remember(modelId, existingSpec) { mutableStateOf(initialPricing.longCacheWritePerM?.toString() ?: "") }

    fun saveSpec(syncedSpec: ModelPricingSpec? = null) {
        val ctx = ctxText.trim().toIntOrNull() ?: 0 // 0 = Unlimited!
        val maxOut = maxOutText.trim().toIntOrNull() // null = Stripped!
        val pricing = io.parchment.ai.model.TokenPricing(
            io.parchment.ai.model.parsePriceText(inPriceText), io.parchment.ai.model.parsePriceText(outPriceText),
            io.parchment.ai.model.parsePriceText(cachePriceText), io.parchment.ai.model.parsePriceText(writePriceText),
            io.parchment.ai.model.parsePriceText(thinkPriceText), io.parchment.ai.model.parsePriceText(longWritePriceText))
        val inP = pricing.inputPerM ?: 0.0
        val outP = pricing.outputPerM ?: 0.0
        val cacheP = pricing.cacheReadPerM ?: 0.0
        val thinkP = pricing.reasoningPerM ?: 0.0
        val priceChanged = pricing != initialPricing


        val spec = ModelPricingSpec(
            modelId = modelId,
            displayName = syncedSpec?.displayName ?: existingSpec?.displayName.orEmpty(),
            providerName = syncedSpec?.providerName ?: existingSpec?.providerName.orEmpty(),
            inputPricePerM = inP,
            outputPricePerM = outP,
            cacheReadPricePerM = cacheP,
            cacheWritePricePerM = pricing.cacheWritePerM ?: 0.0,
            knownPricing = pricing,
            contextPricingTiers = syncedSpec?.contextPricingTiers ?: if (priceChanged) emptyList() else existingSpec?.contextPricingTiers.orEmpty(),
            reasoningPricePerM = thinkP,
            contextWindowTokens = ctx,
            maxOutputTokens = maxOut,
            outputModalities = syncedSpec?.outputModalities
                ?: existingSpec?.outputModalities
                ?: ModelsDevClient.getCachedOutputModalities(modelId),
            inputModalities = syncedSpec?.inputModalities
                ?: existingSpec?.inputModalities
                ?: ModelsDevClient.getCachedInputModalities(modelId)
        )
        onUpdateProfile { prof ->
            prof.copy(
                contextWindowTokens = if (prof.defaultModel == modelId) ctx else prof.contextWindowTokens,
                maxOutputTokens = if (prof.defaultModel == modelId) maxOut else prof.maxOutputTokens,
                inputPricePerM = if (prof.defaultModel == modelId) inP else prof.inputPricePerM,
                outputPricePerM = if (prof.defaultModel == modelId) outP else prof.outputPricePerM,
                cacheReadPricePerM = if (prof.defaultModel == modelId) cacheP else prof.cacheReadPricePerM,
                cacheWritePricePerM = if (prof.defaultModel == modelId) (pricing.cacheWritePerM ?: 0.0) else prof.cacheWritePricePerM,
                knownPricing = if (prof.defaultModel == modelId) pricing else prof.knownPricing,
                pricingModelId = if (prof.defaultModel == modelId) modelId else prof.pricingModelId,
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
            headerAction = {
                SepiaButton(
                    text = "Sync from Model Registry",
                    compact = true,
                    style = SepiaButtonStyle.SECONDARY,
                    onClick = {
                        scope.launch {
                            ModelsDevClient.fetchModelSpec(modelId).fold(
                                onSuccess = { spec ->
                                    ctxText = spec.contextWindowTokens.toString()
                                    maxOutText = spec.maxOutputTokens?.toString() ?: ""
                                    val syncedPricing = spec.knownPricing ?: io.parchment.ai.model.legacyTokenPricing(spec.inputPricePerM, spec.outputPricePerM, spec.cacheReadPricePerM, spec.cacheWritePricePerM, spec.reasoningPricePerM)
                                    inPriceText = syncedPricing.inputPerM?.toString() ?: ""
                                    outPriceText = syncedPricing.outputPerM?.toString() ?: ""
                                    cachePriceText = syncedPricing.cacheReadPerM?.toString() ?: ""
                                    thinkPriceText = syncedPricing.reasoningPerM?.toString() ?: ""
                                    writePriceText = syncedPricing.cacheWritePerM?.toString() ?: ""
                                    longWritePriceText = syncedPricing.longCacheWritePerM?.toString() ?: ""
                                    saveSpec(spec)
                                    onNotify("Synced $modelId specs from unified model registry.")
                                },
                                onFailure = { err -> onNotify("Model registry lookup: ${err.message}") }
                            )
                        }
                    }
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaTextField(value = ctxText, onValueChange = { ctxText = it; saveSpec() },
                    label = "Context window tokens (0 = unlimited)", monospace = true, modifier = Modifier.fillMaxWidth())
                SepiaTextField(value = maxOutText, onValueChange = { maxOutText = it; saveSpec() },
                    label = "Max output tokens (blank = omit)", monospace = true, modifier = Modifier.fillMaxWidth())
                BasicText("Prices are USD per million tokens. Blank means unknown; 0 means free.", style = p.uiLabelStyle)
                SepiaTextField(value = inPriceText, onValueChange = { inPriceText = it; saveSpec() },
                    label = "Input", monospace = true, modifier = Modifier.fillMaxWidth())
                SepiaTextField(value = outPriceText, onValueChange = { outPriceText = it; saveSpec() },
                    label = "Output", monospace = true, modifier = Modifier.fillMaxWidth())
                SepiaTextField(value = cachePriceText, onValueChange = { cachePriceText = it; saveSpec() },
                    label = "Cache read", monospace = true, modifier = Modifier.fillMaxWidth())
                SepiaTextField(value = writePriceText, onValueChange = { writePriceText = it; saveSpec() },
                    label = "Cache write", monospace = true, modifier = Modifier.fillMaxWidth())
                SepiaTextField(value = longWritePriceText, onValueChange = { longWritePriceText = it; saveSpec() },
                    label = "Long-lived cache write", monospace = true, modifier = Modifier.fillMaxWidth())
                SepiaTextField(value = thinkPriceText, onValueChange = { thinkPriceText = it; saveSpec() },
                    label = "Reasoning", monospace = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ============================================================================================
// SUBPAGE 3: ASSISTANTS (Assistant Cards + Editor Subpage)
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                            label = "Assistant Name",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                        label = "System Prompt (Persisted independently of Topics/Chats)",
                        placeholder = "Enter system prompt (optional)…",
                        singleLine = false,
                        minHeight = 140.dp,
                        maxHeight = 520.dp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    SepiaTextField(
                        value = editingAssistant.authorsNote,
                        onValueChange = { an -> updateCurrent { it.copy(authorsNote = an) } },
                        label = "Default Author's Note / Post-History Injection (Optional)",
                        singleLine = false,
                        minHeight = 60.dp,
                        maxHeight = 320.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            SepiaSectionCard(
                title = "Assistant Model, Thinking Effort & Workspace"
            ) {
                val scope = rememberCoroutineScope()
                val followsGlobalModel = editingAssistant.defaultModelId.isBlank()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SepiaToggleRow(
                        title = "Follow Global Default Model",
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
                    SepiaAdaptiveRow(label = "Thinking Effort Override") {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            Spacer(Modifier.height(6.dp))
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
                title = "Assistant Sampling & Context Overrides (Blank = Follow Profile, 0 = ∞)"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SepiaTextField(
                            value = tempText,
                            onValueChange = { v ->
                                tempText = v
                                val t = v.trim()
                                updateCurrent { it.copy(temperature = if (t.isEmpty()) null else t.toDoubleOrNull()) }
                            },
                            label = "Temperature (blank = omit)",
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
                            label = "Top-P (blank = omit)",
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
    val activeGlobalProfId = defaultModels.globalProfileId.ifBlank { appState.activeProfileId }
    val activeGlobalModelId = defaultModels.globalModelId.ifBlank {
        appState.profiles.find { it.id == activeGlobalProfId }?.defaultModel.orEmpty()
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
            title = "1. Global Default Model"
        ) {
            SepiaSearchableModelPicker(
                profiles = appState.profiles,
                selectedProfileId = activeGlobalProfId,
                selectedModelId = activeGlobalModelId,
                onSelectModel = { profId, modelId ->
                    val profName = appState.profiles.find { it.id == profId }?.name ?: profId
                    onUpdateDefaultModels(defaultModels.copy(globalProfileId = profId, globalModelId = modelId))
                    onSelectProfile(profId)
                    onUpdateProfileById(profId) { it.copy(defaultModel = modelId) }
                    onUpdateSession { it.copy(profileId = profId, modelOverride = modelId) }
                    onNotify("Set Global Default Model to $profName • $modelId")
                },
                maxListHeight = 240.dp
            )
        }

        // 2. Title Summary Model
        SepiaSectionCard(
            title = "2. Title Generation Model"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaToggleRow(
                    title = "Follow Global Choice (Use Current Global Model)",
                    checked = defaultModels.titleFollowGlobal,
                    onCheckedChange = { onUpdateDefaultModels(defaultModels.copy(titleFollowGlobal = it)) }
                )
                AnimatedVisibility(visible = !defaultModels.titleFollowGlobal) {
                    SepiaSearchableModelPicker(
                        profiles = appState.profiles,
                        selectedProfileId = defaultModels.titleProfileId,
                        selectedModelId = defaultModels.titleModelId,
                        onSelectModel = { profId, modelId ->
                            onUpdateDefaultModels(defaultModels.copy(titleProfileId = profId, titleModelId = modelId))
                        },
                        maxListHeight = 210.dp
                    )
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
            title = "3. Summary & Context Compression Model"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaToggleRow(
                    title = "Follow Global Choice (Use Current Global Model)",
                    checked = defaultModels.summaryFollowGlobal,
                    onCheckedChange = { onUpdateDefaultModels(defaultModels.copy(summaryFollowGlobal = it)) }
                )
                AnimatedVisibility(visible = !defaultModels.summaryFollowGlobal) {
                    SepiaSearchableModelPicker(
                        profiles = appState.profiles,
                        selectedProfileId = defaultModels.summaryProfileId,
                        selectedModelId = defaultModels.summaryModelId,
                        onSelectModel = { profId, modelId ->
                            onUpdateDefaultModels(defaultModels.copy(summaryProfileId = profId, summaryModelId = modelId))
                        },
                        maxListHeight = 210.dp
                    )
                }
            }
        }

        // 4. Translation Model & Target Language
        SepiaSectionCard(
            title = "4. Translation Model & Target Language"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaTextField(
                    value = defaultModels.translationTargetLanguage,
                    onValueChange = { lang -> onUpdateDefaultModels(defaultModels.copy(translationTargetLanguage = lang)) },
                    label = "Target Language for In-Chat Translation",
                    placeholder = "English, Vietnamese, Japanese, Spanish…",
                    modifier = Modifier.fillMaxWidth()
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("English", "Vietnamese", "Japanese", "Chinese", "Korean", "Spanish", "French", "German").forEach { lang ->
                        SepiaChip(
                            label = lang,
                            selected = defaultModels.translationTargetLanguage.equals(lang, ignoreCase = true),
                            onClick = { onUpdateDefaultModels(defaultModels.copy(translationTargetLanguage = lang)) }
                        )
                    }
                }
                SepiaDivider()
                SepiaToggleRow(
                    title = "Follow Global Choice (Use Current Global Model)",
                    checked = defaultModels.translationFollowGlobal,
                    onCheckedChange = { onUpdateDefaultModels(defaultModels.copy(translationFollowGlobal = it)) }
                )
                AnimatedVisibility(visible = !defaultModels.translationFollowGlobal) {
                    SepiaSearchableModelPicker(
                        profiles = appState.profiles,
                        selectedProfileId = defaultModels.translationProfileId,
                        selectedModelId = defaultModels.translationModelId,
                        onSelectModel = { profId, modelId ->
                            onUpdateDefaultModels(defaultModels.copy(translationProfileId = profId, translationModelId = modelId))
                        },
                        maxListHeight = 210.dp
                    )
                }
            }
        }
    }
}

// ============================================================================================
// SUBPAGE 5: WORKSPACE & LOCAL AGENT HARNESS (Full 16-Tool Live Registry + 1-Tap Mount)
// ============================================================================================
@Composable
private fun WorkspaceSubpage(
    appState: AppStateSnapshot,
    activeSession: ChatSession,
    activeProfile: ProviderProfile,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
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

    val discoveredFiles = remember(effectiveWsPath) {
        WorkspaceHarness.listDiscoveredContextFileNames(effectiveWsPath)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (win.isCompact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BasicText(text = "Workspace & Local Agent Harness", style = p.uiHeaderStyle.copy(fontSize = 19.sp))

        SepiaSectionCard(
            title = "Workspace and agent permissions"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AgentPermissionEditor(
                    defaults = permissionDefaults, overrides = permissionOverrides,
                    onChange = { value -> onUpdateSession { it.copy(permissionOverrides = value) } },
                    onReset = { onUpdateSession { it.copy(permissionOverrides = AgentPermissionOverrides()) } }
                )
                if (effectiveWsPath.isNotBlank()) {
                    SepiaDivider()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column {
                            BasicText(
                                text = "Auto-Loaded Project Memory & Context Files",
                                style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            )
                            BasicText(
                                text = if (discoveredFiles.isEmpty()) {
                                    "No AGENTS.md, CLAUDE.md, PARCHMENT.md, or PARCHMENT_MEMORY.md found in root yet."
                                } else {
                                    "Auto-injected into system prompt: ${discoveredFiles.joinToString(", ")}"
                                },
                                style = p.uiLabelStyle.copy(color = if (discoveredFiles.isNotEmpty()) p.accent else p.mutedInk)
                            )
                        }
                        if ("PARCHMENT_MEMORY.md" !in discoveredFiles) {
                            SepiaButton(
                                text = "Create project memory file",
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), wrapText = true,
                                enabled = effectiveWsMode == WorkspaceMode.READ_WRITE,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    PlatformBridge.workspaceWrite(
                                        effectiveWsPath,
                                        "PARCHMENT_MEMORY.md",
                                        "# Project & Story Memory\n\n- Record key architectural rules, character notes, and decisions here.\n"
                                    ).onSuccess {
                                        onNotify("Created PARCHMENT_MEMORY.md in workspace root.")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        SepiaSectionCard(title = "Agent tools") {
            AgentToolRegistry(
                policy = toolPolicy, overrides = permissionOverrides,
                onChange = { value -> onUpdateSession { it.copy(permissionOverrides = value) } }
            )
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
            title = "Import / Export .PNG & .JSON Cards"
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

        // Saved Character Cards Library & System Prompt Presets Library
        if (appState.savedCards.isNotEmpty()) {
            SepiaSectionCard(
                title = "Saved Character & Scenario Cards Library (${appState.savedCards.size})"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    appState.savedCards.forEach { savedCard ->
                        val isActive = activeSession.activeCard?.name == savedCard.name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isActive) p.accent.copy(alpha = 0.14f) else p.elevatedBg.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                BasicText(
                                    text = savedCard.name.ifBlank { "Untitled Card" },
                                    style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, color = if (isActive) p.accent else p.primaryInk)
                                )
                                BasicText(
                                    text = savedCard.description.ifBlank { "No description" },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = p.uiLabelStyle
                                )
                            }
                            SepiaButton(
                                text = if (isActive) "✓ Active" else "Apply",
                                compact = true,
                                style = if (isActive) SepiaButtonStyle.SECONDARY else SepiaButtonStyle.OUTLINE,
                                onClick = {
                                    val mergedFrags = (activeSession.fragments + savedCard.embeddedFragments).distinctBy { it.title }
                                    onUpdateSession { it.copy(activeCard = savedCard, fragments = mergedFrags) }
                                    onNotify("Applied card '${savedCard.name}'.")
                                }
                            )
                        }
                    }
                }
            }
        }

        if (appState.systemPromptPresets.isNotEmpty() || activeSession.systemPrompt.isNotBlank()) {
            SepiaSectionCard(
                title = "System Prompt Presets Library (${appState.systemPromptPresets.size})",
                headerAction = {
                    if (activeSession.systemPrompt.isNotBlank()) {
                        SepiaButton(
                            text = "+ Save Current Prompt",
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = {
                                val now = PlatformBridge.currentTimeMillis()
                                val preset = SystemPromptPreset(
                                    id = "preset-$now",
                                    name = activeSession.title.takeIf { it != "New Chat" } ?: "Preset #${appState.systemPromptPresets.size + 1}",
                                    systemPrompt = activeSession.systemPrompt
                                )
                                onSavePresetToLibrary(preset)
                                onNotify("Saved '${preset.name}' to System Prompt Presets.")
                            }
                        )
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (appState.systemPromptPresets.isEmpty()) {
                        BasicText(
                            text = "No saved presets yet. Import a .PNG/.JSON prompt preset or click '+ Save Current Prompt'.",
                            style = p.uiLabelStyle
                        )
                    }
                    appState.systemPromptPresets.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(p.elevatedBg.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                BasicText(text = preset.name, style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold))
                                BasicText(text = preset.systemPrompt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = p.uiLabelStyle)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                SepiaButton(
                                    text = "Apply",
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = {
                                        onUpdateSession { it.copy(systemPrompt = preset.systemPrompt) }
                                        onNotify("Applied System Prompt '${preset.name}'.")
                                    }
                                )
                                SepiaButton(
                                    text = "×",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { onDeletePresetFromLibrary(preset.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        var editingGlobalWorldBook by remember { mutableStateOf(false) }
        val displayedFragments = if (editingGlobalWorldBook) appState.globalWorldBook else activeSession.fragments

        SepiaSectionCard(
            title = "World Book / Lore Fragments (${displayedFragments.size} entries)"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaChip(
                        label = "This Chat (${activeSession.fragments.size})",
                        selected = !editingGlobalWorldBook,
                        onClick = { editingGlobalWorldBook = false }
                    )
                    SepiaChip(
                        label = "Global World Book (${appState.globalWorldBook.size})",
                        selected = editingGlobalWorldBook,
                        onClick = { editingGlobalWorldBook = true }
                    )
                }
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
                    text = if (editingGlobalWorldBook) "+ Add to Global World Book" else "+ Add to Chat World Book",
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
                            if (editingGlobalWorldBook) {
                                onUpdateGlobalWorldBook(appState.globalWorldBook + frag)
                            } else {
                                onUpdateSession { it.copy(fragments = it.fragments + frag) }
                            }
                            fragTitle = ""
                            fragKeywords = ""
                            fragContent = ""
                        }
                    }
                )

                displayedFragments.forEach { frag ->
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
                                    if (editingGlobalWorldBook) {
                                        onUpdateGlobalWorldBook(
                                            appState.globalWorldBook.map { if (it.id == frag.id) it.copy(mode = next) else it }
                                        )
                                    } else {
                                        onUpdateSession { s ->
                                            s.copy(fragments = s.fragments.map { if (it.id == frag.id) it.copy(mode = next) else it })
                                        }
                                    }
                                }
                            )
                            SepiaButton(
                                text = "×",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = {
                                    if (editingGlobalWorldBook) {
                                        onUpdateGlobalWorldBook(appState.globalWorldBook.filterNot { it.id == frag.id })
                                    } else {
                                        onUpdateSession { s -> s.copy(fragments = s.fragments.filterNot { it.id == frag.id }) }
                                    }
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
// SUBPAGE 7: BACKUP & OTA (Split Storage: Settings & Assistants vs Per-Chat .json Files)
// ============================================================================================
@Composable
private fun BackupAndOtaSubpage(
    appState: AppStateSnapshot,
    activeSession: ChatSession,
    otaState: OtaUpdateState,
    onUpdateOtaSettings: (String, Boolean) -> Unit,
    onCheckOtaNow: () -> Unit,
    onInstallOtaRelease: (OtaReleaseInfo) -> Unit,
    onRestoreFullBackup: (AppStateSnapshot) -> Unit,
    onImportChatSessions: (List<ChatSession>) -> Unit,
    onWipeAllData: () -> Unit,
    onDeleteChatSessionById: (String) -> Unit = {},
    onInspectAttachment: (io.parchment.ai.model.PromptAttachment) -> Unit = {},
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    val scope = rememberCoroutineScope()
    var storageRefreshTick by remember { mutableStateOf(0) }
    var storageCategoryFilter by remember { mutableStateOf<io.parchment.ai.model.StorageFileCategory?>(null) }
    var storageSearchQuery by remember { mutableStateOf("") }

    val allStoredFiles = remember(storageRefreshTick, appState.sessions.size) {
        PlatformBridge.listAppStorageFiles()
    }
    val sessionsByFileStem = remember(appState.sessions) {
        appState.sessions.associateBy { sess ->
            sess.id.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "session" }
        }
    }
    val referencedMediaPaths = remember(appState.sessions) {
        val set = HashSet<String>()
        val rootNorm = PlatformBridge.getAppStorageDirectoryPath().replace('\\', '/').trimEnd('/').lowercase()
        for (sess in appState.sessions) {
            for (node in sess.messages) {
                for (v in node.variants) {
                    for (att in v.attachments) {
                        if (att.localFilePath.isNotBlank()) {
                            val norm = att.localFilePath.replace('\\', '/').lowercase()
                            set.add(norm)
                            if (!norm.startsWith("/") && !norm.contains(":/")) {
                                set.add("$rootNorm/$norm")
                            }
                        }
                    }
                    for (m in v.mediaItems) {
                        if (m.localFilePath.isNotBlank()) {
                            val norm = m.localFilePath.replace('\\', '/').lowercase()
                            set.add(norm)
                            if (!norm.startsWith("/") && !norm.contains(":/")) {
                                set.add("$rootNorm/$norm")
                            }
                        }
                    }
                }
            }
        }
        set
    }

    fun resolveSessionForStoredFile(item: io.parchment.ai.model.StoredAppFileItem): ChatSession? {
        val normRel = item.relativePath.replace('\\', '/')
        if (normRel.startsWith("chats/")) {
            val afterChats = normRel.removePrefix("chats/")
            val folderOrFileStem = if (afterChats.contains('/')) {
                afterChats.substringBefore('/')
            } else {
                afterChats.removeSuffix(".json")
            }
            sessionsByFileStem[folderOrFileStem]?.let { return it }
        }
        if (item.category == io.parchment.ai.model.StorageFileCategory.CHAT_SESSION) {
            return sessionsByFileStem[item.fileName.removeSuffix(".json")]
        }
        return null
    }

    fun isOrphanOrTempFile(item: io.parchment.ai.model.StoredAppFileItem): Boolean {
        if (item.category == io.parchment.ai.model.StorageFileCategory.OTA_AND_TEMP) return true
        if (item.category == io.parchment.ai.model.StorageFileCategory.UPLOADED_MEDIA ||
            item.category == io.parchment.ai.model.StorageFileCategory.GENERATED_MEDIA
        ) {
            val normFull = item.fullPath.replace('\\', '/').lowercase()
            val normRel = item.relativePath.replace('\\', '/').lowercase()
            return normFull !in referencedMediaPaths && normRel !in referencedMediaPaths
        }
        return false
    }

    fun formatBytesCompact(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> "${((bytes / (1024.0 * 1024.0)) * 10).toInt() / 10.0} MB"
            bytes >= 1024L -> "${((bytes / 1024.0) * 10).toInt() / 10.0} KB"
            else -> "$bytes B"
        }
    }

        val totalBytes = remember(allStoredFiles) { allStoredFiles.sumOf { it.sizeBytes } }
        val orphanAndTempFiles = remember(allStoredFiles, referencedMediaPaths) {
            allStoredFiles.filter { isOrphanOrTempFile(it) }
        }
        val orphanBytes = remember(orphanAndTempFiles) { orphanAndTempFiles.sumOf { it.sizeBytes } }

    val filteredStorageFiles = remember(allStoredFiles, storageCategoryFilter, storageSearchQuery, sessionsByFileStem) {
        val q = storageSearchQuery.trim().lowercase()
        allStoredFiles.filter { item ->
            val catOk = storageCategoryFilter == null || item.category == storageCategoryFilter
            if (!catOk) return@filter false
            if (q.isEmpty()) return@filter true
            val chatTitle = resolveSessionForStoredFile(item)?.title.orEmpty()
            item.fileName.lowercase().contains(q) ||
                item.relativePath.lowercase().contains(q) ||
                chatTitle.lowercase().contains(q)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (win.isCompact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BasicText(text = "Storage, Backup & OTA Updates", style = p.uiHeaderStyle.copy(fontSize = 19.sp))

        // Card 1: Storage & File Manager (Inspect & delete Chats, Media Uploads, AI Media, Backups, & Temp files!)
        SepiaSectionCard(
            title = "Storage & File Manager (${allStoredFiles.size} files • ${formatBytesCompact(totalBytes)})",
            subtitle = PlatformBridge.getAppStorageDirectoryPath(),
            headerAction = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaButton(
                        text = "📂 Open Folder",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { PlatformBridge.openOrPlayMedia(PlatformBridge.getAppStorageDirectoryPath()) }
                    )
                    SepiaButton(
                        text = "↻",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { storageRefreshTick++ }
                    )
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Visual Storage Breakdown Bar by Category
                val catTotals = remember(allStoredFiles) {
                    io.parchment.ai.model.StorageFileCategory.entries.associateWith { cat ->
                        allStoredFiles.filter { it.category == cat }.sumOf { it.sizeBytes }
                    }
                }
                val safeTotal = totalBytes.coerceAtLeast(1L).toFloat()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(9.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(p.elevatedBg)
                        .border(0.65.dp, p.border.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
                ) {
                    io.parchment.ai.model.StorageFileCategory.entries.forEach { cat ->
                        val catBytes = catTotals[cat] ?: 0L
                        if (catBytes > 0L) {
                            val col = when (cat) {
                                io.parchment.ai.model.StorageFileCategory.CHAT_SESSION -> p.accent
                                io.parchment.ai.model.StorageFileCategory.UPLOADED_MEDIA -> Color(0xFF3D6B8E)
                                io.parchment.ai.model.StorageFileCategory.GENERATED_MEDIA -> Color(0xFF4A7C59)
                                io.parchment.ai.model.StorageFileCategory.SETTINGS_AND_BACKUPS -> p.accentSecondary
                                io.parchment.ai.model.StorageFileCategory.OTA_AND_TEMP -> p.mutedInk
                            }
                            Box(
                                modifier = Modifier
                                    .weight((catBytes.toFloat() / safeTotal).coerceAtLeast(0.02f))
                                    .fillMaxHeight()
                                    .background(col)
                            )
                        }
                    }
                }

                // Category Filter Pills + Orphan Cleanup Action
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SepiaChip(
                        label = "All (${allStoredFiles.size} • ${formatBytesCompact(totalBytes)})",
                        selected = storageCategoryFilter == null,
                        onClick = { storageCategoryFilter = null }
                    )
                    io.parchment.ai.model.StorageFileCategory.entries.forEach { cat ->
                        val count = allStoredFiles.count { it.category == cat }
                        val bytes = catTotals[cat] ?: 0L
                        if (count > 0 || storageCategoryFilter == cat) {
                            SepiaChip(
                                label = "${cat.label} ($count • ${formatBytesCompact(bytes)})",
                                selected = storageCategoryFilter == cat,
                                onClick = {
                                    storageCategoryFilter = if (storageCategoryFilter == cat) null else cat
                                }
                            )
                        }
                    }
                }

                if (orphanAndTempFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = "${orphanAndTempFiles.size} unused media/temp file(s) (${formatBytesCompact(orphanBytes)}) can be safely freed.",
                            style = p.uiLabelStyle.copy(color = p.accent)
                        )
                        SepiaButton(
                            text = "🗑 Clean Unused (${formatBytesCompact(orphanBytes)})",
                            compact = true,
                            style = SepiaButtonStyle.SECONDARY,
                            onClick = {
                                var deletedCount = 0
                                var freedBytes = 0L
                                orphanAndTempFiles.forEach { item ->
                                    if (PlatformBridge.deleteAppStorageFile(item.fullPath)) {
                                        deletedCount++
                                        freedBytes += item.sizeBytes
                                    }
                                }
                                storageRefreshTick++
                                onNotify("Cleaned $deletedCount unused file(s) (${formatBytesCompact(freedBytes)} freed).")
                            }
                        )
                    }
                }

                // Search bar for files
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SepiaTextField(
                        value = storageSearchQuery,
                        onValueChange = { storageSearchQuery = it },
                        placeholder = "🔍 Filter files by name, path, or chat title…",
                        singleLine = true,
                        minHeight = 34.dp,
                        modifier = Modifier.weight(1f)
                    )
                    if (storageSearchQuery.isNotEmpty()) {
                        SepiaButton(
                            text = "×",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { storageSearchQuery = "" }
                        )
                    }
                }

                // Scrollable File List
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(p.sheetBg.copy(alpha = 0.55f))
                        .border(0.75.dp, p.border.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (filteredStorageFiles.isEmpty()) {
                        BasicText(
                            text = "No matching files in storage.",
                            style = p.uiLabelStyle.copy(color = p.mutedInk),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    filteredStorageFiles.forEach { item ->
                        val matchedSession = resolveSessionForStoredFile(item)
                        val isOrphan = remember(item.fullPath, referencedMediaPaths) { isOrphanOrTempFile(item) }
                        val itemIcon = when (item.category) {
                            io.parchment.ai.model.StorageFileCategory.CHAT_SESSION -> KissIcon.FILE_TEXT
                            io.parchment.ai.model.StorageFileCategory.UPLOADED_MEDIA -> KissIcon.PAPERCLIP
                            io.parchment.ai.model.StorageFileCategory.GENERATED_MEDIA -> KissIcon.IMAGE
                            io.parchment.ai.model.StorageFileCategory.SETTINGS_AND_BACKUPS -> KissIcon.SETTINGS
                            io.parchment.ai.model.StorageFileCategory.OTA_AND_TEMP -> KissIcon.PACKAGE_BOX
                        }
                        val displayTitle = when {
                            item.category == io.parchment.ai.model.StorageFileCategory.CHAT_SESSION && matchedSession != null ->
                                "${matchedSession.title} (${matchedSession.messages.size} msgs)"
                            matchedSession != null ->
                                "${item.fileName} • ${matchedSession.title}"
                            else -> item.fileName
                        }
                        val isPrimarySettingsFile = item.fileName.equals("parchment-settings.json", ignoreCase = true)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(p.cardBg)
                                .border(0.65.dp, p.border.copy(alpha = 0.65f), RoundedCornerShape(7.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                KissVectorIcon(icon = itemIcon, tint = p.accent, size = 14.dp)
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    BasicText(
                                        text = displayTitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                    )
                                    val orphanTag = if (isOrphan) " • Unused" else ""
                                    BasicText(
                                        text = "${item.relativePath} • ${formatBytesCompact(item.sizeBytes)} • ${formatCompactTimestamp(item.lastModifiedMs)}$orphanTag",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.monoStyle.copy(
                                            fontSize = 10.sp,
                                            color = if (isOrphan) p.accentSecondary else p.mutedInk
                                        )
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SepiaButton(
                                    text = "👁",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = {
                                        val lower = item.fileName.lowercase()
                                        val mime = when {
                                            lower.endsWith(".png") -> "image/png"
                                            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                                            lower.endsWith(".webp") -> "image/webp"
                                            lower.endsWith(".gif") -> "image/gif"
                                            lower.endsWith(".svg") -> "image/svg+xml"
                                            lower.endsWith(".mp4") -> "video/mp4"
                                            lower.endsWith(".pdf") -> "application/pdf"
                                            lower.endsWith(".json") -> "application/json"
                                            else -> "text/plain"
                                        }
                                        onInspectAttachment(
                                            io.parchment.ai.model.PromptAttachment(
                                                id = "storage-${item.fileName}",
                                                fileName = item.fileName,
                                                mimeType = mime,
                                                localFilePath = item.fullPath
                                            )
                                        )
                                    }
                                )
                                SepiaButton(
                                    text = "📂",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { PlatformBridge.openOrPlayMedia(item.fullPath) }
                                )
                                if (!isPrimarySettingsFile) {
                                    SepiaButton(
                                        text = "🗑",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = {
                                            if (matchedSession != null) {
                                                onDeleteChatSessionById(matchedSession.id)
                                                storageRefreshTick++
                                                onNotify("Deleted chat '${matchedSession.title}' (${formatBytesCompact(item.sizeBytes)} freed).")
                                            } else if (PlatformBridge.deleteAppStorageFile(item.fullPath)) {
                                                storageRefreshTick++
                                                onNotify("Deleted '${item.fileName}' (${formatBytesCompact(item.sizeBytes)} freed).")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SepiaSectionCard(
            title = "In-App OTA Updates (Android .APK & Windows .EXE)",
            subtitle = "v${OtaUpdateEngine.currentAppVersion} • ${PlatformBridge.platformName}"
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(p.border.copy(alpha = 0.45f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((otaState.progressPercent / 100f).coerceIn(0.02f, 1f))
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(p.accent)
                            )
                        }
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

        // Card 3: Self-Contained Per-Chat Folder Export & Import (chats/<sessionId>/chat.json + media/)
        SepiaSectionCard(
            title = "Portable Chats & Media Export / Import (Per-Chat Folder & .zip Bundles)",
            subtitle = PlatformBridge.getChatsStorageDirectoryPath()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaButton(
                        text = "📦 Export All Chats + Media (${appState.sessions.size} Chats .zip)",
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = {
                            scope.launch {
                                val zipBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    encodeChatSessionsToZipBundleBytes(appState.sessions)
                                }
                                val saved = PlatformBridge.saveFileForExport("parchment-chats-backup.zip", zipBytes)
                                if (saved != null) {
                                    onNotify("Exported ${appState.sessions.size} chat(s) + media to $saved")
                                }
                            }
                        }
                    )
                    SepiaButton(
                        text = "📦 Export Current Chat (.chat.zip + Media)",
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = {
                            scope.launch {
                                val zipName = buildExportZipFileNameForChat(activeSession)
                                val zipBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    encodeChatSessionsToZipBundleBytes(listOf(activeSession))
                                }
                                val saved = PlatformBridge.saveFileForExport(zipName, zipBytes)
                                if (saved != null) onNotify("Exported chat bundle (chat.json + media/) to $saved")
                            }
                        }
                    )
                    SepiaButton(
                        text = "📤 Export Current Chat (.json Only)",
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            scope.launch {
                                val fileName = buildExportFileNameForChat(activeSession)
                                val bytes = runCatching {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        encodeChatSessionJsonForExport(activeSession)
                                    }
                                }.getOrElse { error ->
                                    onNotify("Chat export failed: ${error.message}")
                                    return@launch
                                }
                                val saved = PlatformBridge.saveFileForExport(fileName, bytes)
                                if (saved != null) onNotify("Exported chat JSON to $saved")
                            }
                        }
                    )
                    SepiaButton(
                        text = "📥 Import Chat(s) (.zip / .json)",
                        style = SepiaButtonStyle.SECONDARY,
                        onClick = {
                            scope.launch {
                                val pickedFiles = PlatformBridge.pickFilesForImport(listOf("zip", "json"))
                                if (pickedFiles.isEmpty()) return@launch
                                val importedChats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    pickedFiles.flatMap { picked ->
                                        runCatching { decodeChatSessionsFromPickedFile(picked.fileName, picked.bytes) }.getOrDefault(emptyList())
                                    }
                                }
                                if (importedChats.isNotEmpty()) {
                                    onImportChatSessions(importedChats)
                                } else {
                                    onNotify("No valid chat sessions found in selected .zip / .json file(s).")
                                }
                            }
                        }
                    )
                }
            }
        }

        // Card 4: Settings, Providers, Assistants & System Prompts Backup (Decoupled from Chats!)
        SepiaSectionCard(
            title = "Settings, Providers & Assistants Backup (.json)",
            subtitle = "${PlatformBridge.getAppStorageDirectoryPath()}/parchment-settings.json"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaButton(
                        text = "📤 Export Settings & Assistants (.json)",
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = {
                            scope.launch {
                                val settingsOnly = appState.copy(sessions = emptyList())
                                val bytes = CardAndPromptCodec.prettyJson.encodeToString(AppStateSnapshot.serializer(), settingsOnly).encodeToByteArray()
                                val saved = PlatformBridge.saveFileForExport("parchment-settings.json", bytes)
                                if (saved != null) onNotify("Exported settings & assistants to $saved")
                            }
                        }
                    )
                    SepiaButton(
                        text = "📥 Import Settings & Assistants (.json)",
                        style = SepiaButtonStyle.SECONDARY,
                        onClick = {
                            scope.launch {
                                val picked = PlatformBridge.pickFileForImport(listOf("json")) ?: return@launch
                                runCatching {
                                    CardAndPromptCodec.prettyJson.decodeFromString<AppStateSnapshot>(picked.bytes.decodeToString())
                                }.fold(
                                    onSuccess = { restored -> onRestoreFullBackup(restored) },
                                    onFailure = { err -> onNotify("Settings import failed: ${err.message}") }
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
}

/**
 * Selects a sensible initial chat model when a brand-new profile fetches models for the first time,
 * skipping utility/research/embedding endpoints like `deep-research*` or `text-embedding*`.
 */
fun pickBestInitialChatModel(models: List<String>): String {
    return models.firstOrNull() ?: ""
}

// ============================================================================================
// UNIVERSAL CROSS-CATEGORY GLOBAL SETTINGS SEARCH ENGINE (100% ITEM-LEVEL GRANULARITY)
// Searches across Preferences, Providers, Models, Assistants, Default Models, Workspace,
// World Book / Lorebook, and Backup / OTA with interactive inline controls and 1-tap tab jumps!
// ============================================================================================
private fun matchesSearchTokens(tokens: List<String>, vararg terms: String): Boolean {
    if (tokens.isEmpty()) return true
    val combined = terms.joinToString(" ").lowercase()
    return tokens.all { tok -> combined.contains(tok) }
}

private fun countCategorySearchMatches(
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
                hit("App / Prose Font", "Appearance & Fonts", "inter", "serif", "latex", "jetbrainsmono", "ttf", "otf", "custom font"),
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
                hit("Show tokens for the entire turn", "Chat item display", "input tokens", "output tokens", "cache", "ttft"),
                hit("Show Thinking Cards", "Chat item display", "reasoning drawer", "thoughts"),
                hit("Show Tool Cards", "Chat item display", "workspace tools", "function call"),
                hit("Show reasoning level on the button", "Chat item display", "thinking effort", "composer chip"),
                hit("Inline $...$ Rendering", "Rendering settings", "math", "latex", "dollar"),
                hit("Math Formula Rendering", "Rendering settings", "latex", "katex", "equations"),
                hit("Render user messages with Markdown", "Rendering settings", "formatting", "user"),
                hit("Render reasoning (thinking) with Markdown & LaTeX", "Rendering settings", "thoughts", "formatting"),
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
                hit("Keep Message Versions When Forking", "Behavior & startup", "branch", "swipes", "variants"),
                hit("Show Chat List Dates", "Behavior & startup", "sidebar", "timestamp", "time", "date"),
                hit("New chat when switching assistants", "Behavior & startup", "sidebar", "assistant"),
                hit("New chat after deleting topic", "Behavior & startup", "trash", "session"),
                hit("New Chat on Launch", "Behavior & startup", "startup", "boot", "open"),
                hit("Auto-follow bottom while streaming", "Behavior & startup", "scroll", "autoscroll"),
                hit("Auto-compact context when reaching 85% of context limit", "Behavior & startup", "compaction", "context window", "summarize", "token limit"),
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
                hit("Base URL", "Endpoint Path Override", "Providers", "proxy", "host", activeProfile.baseUrl),
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
                hit("Auto-Check for OTA Updates on Launch", "GitHub Release Repository", "Check for Update Now", "Backup & OTA", "version"),
                hit("Export Full Backup JSON", "Restore Backup JSON", "Wipe All Local Data", "Backup & OTA", "storage")
            ).count { it }
        }
    }
}

@Composable
private fun GlobalSettingsSearchResultsSubpage(
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
    val hitAppFont = hit("App / Prose Font", "Appearance & Fonts", "inter", "serif", "latex", "jetbrainsmono", "ttf", "otf", "custom font")
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
    val hitTokensEntireTurn = hit("Show tokens for the entire turn", "Chat item display", "input tokens", "output tokens", "cache", "ttft")
    val hitThinkingCards = hit("Show Thinking Cards", "Chat item display", "reasoning drawer", "thoughts")
    val hitToolCards = hit("Show Tool Cards", "Chat item display", "workspace tools", "function call")
    val hitReasoningButton = hit("Show reasoning level on the button", "Chat item display", "thinking effort", "composer chip")

    val hitInlineDollar = hit("Inline $...$ Rendering", "Rendering settings", "math", "latex", "dollar")
    val hitMathFormula = hit("Math Formula Rendering", "Rendering settings", "latex", "katex", "equations")
    val hitUserMd = hit("Render user messages with Markdown", "Rendering settings", "formatting", "user")
    val hitReasoningMd = hit("Render reasoning (thinking) with Markdown & LaTeX", "Rendering settings", "thoughts", "formatting")
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
    val hitKeepForkVersions = hit("Keep Message Versions When Forking", "Behavior & startup", "branch", "swipes", "variants")
    val hitChatListDates = hit("Show Chat List Dates", "Behavior & startup", "sidebar", "timestamp", "time", "date")
    val hitNewChatSwitchAsst = hit("New chat when switching assistants", "Behavior & startup", "sidebar", "assistant")
    val hitNewChatDeleteTopic = hit("New chat after deleting topic", "Behavior & startup", "trash", "session")
    val hitNewChatLaunch = hit("New Chat on Launch", "Behavior & startup", "startup", "boot", "open")
    val hitAutoScrollOnOpen = hit("Auto-scroll to bottom when opening a chat", "Behavior & startup", "scroll", "bottom", "latest", "open")
    val hitAutoFollowBottom = hit("Auto-follow bottom while streaming", "Behavior & startup", "scroll", "autoscroll")
    val hitAutoCompactLimit = hit("Auto-compact context when reaching 85% of context limit", "Behavior & startup", "compaction", "context window", "summarize", "token limit")
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
    val hitStateful = hit("Stateful Interaction Chaining", "previous_interaction_id", "previous_response_id", "cache", "Providers")
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
    val hitOtaBackup = hit("Backup & OTA", "Auto-Check for OTA Updates on Launch", "GitHub Release", "Update", "Export Backup", "Restore Backup", "Wipe All Local Data", "storage")

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
                text = "Settings Search Results for \"$searchQuery\"",
                style = p.uiHeaderStyle.copy(fontSize = 18.sp)
            )
        }

        if (!hasAnyResult) {
            SepiaSectionCard(
                title = "No matching settings found for \"$searchQuery\"",
                subtitle = "Try searching for terms like 'avatar', 'timestamp', 'font', 'minimap', 'fallback', 'shell', 'model', or 'ota'."
            ) {
                SepiaButton(
                    text = "Open Preferences →",
                    compact = true,
                    style = SepiaButtonStyle.OUTLINE,
                    onClick = { onJumpToCategory(SettingsCategory.PREFERENCES) }
                )
            }
        }

        if (anyPrefMatch) {
            SepiaSectionCard(
                title = "Preferences & Appearance",
                subtitle = "Chat item display, typography, Markdown/LaTeX rendering, and behavior toggles",
                headerAction = {
                    SepiaButton(
                        text = "Open Preferences →",
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
                        SepiaAdaptiveRow(label = "Accent Color") {
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val accentSwatches = listOf("#8B5A2B", "#9E4730", "#3B5998", "#1F6F54", "#5E4B8B", "#7A5216", "#8C3823", "#2D3748")
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
                            }
                        }
                    }
                    if (hitAppFont) {
                        SepiaAdaptiveRow(label = "App / Prose Font") {
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Inter", "Serif", "LaTeX", "JetBrainsMono Nerd").forEach { f ->
                                    SepiaChip(
                                        label = f,
                                        selected = theme.customProseFontPath.isBlank() && theme.proseFontFamily.equals(f, ignoreCase = true),
                                        onClick = { onUpdateTheme(theme.copy(proseFontFamily = f, customProseFontPath = "")) }
                                    )
                                }
                            }
                        }
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
                                label = "Prose Font Size (sp)",
                                monospace = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            SepiaTextField(
                                value = codeSizeText,
                                onValueChange = { v ->
                                    codeSizeText = v
                                    v.toFloatOrNull()?.takeIf { it > 4f }?.let { onUpdateTheme(theme.copy(codeFontSizeSp = it)) }
                                },
                                label = "Code Font Size (sp)",
                                monospace = true,
                                modifier = Modifier.fillMaxWidth()
                            )
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
                        }
                    }
                    if (hitLayoutStyle) {
                        SepiaAdaptiveRow(label = "Message Layout Style") {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            title = "Show Assistant Avatar in Title Bar",
                            checked = prefs.showAssistantAvatarInTitleBar,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showAssistantAvatarInTitleBar = it)) }
                        )
                    }
                    if (hitUserAvatar) {
                        SepiaToggleRow(
                            title = "Show User Avatar",
                            checked = prefs.showUserAvatar,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showUserAvatar = it)) }
                        )
                    }
                    if (hitUserName) {
                        SepiaToggleRow(
                            title = "Show User Name",
                            checked = prefs.showUserName,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showUserName = it)) }
                        )
                    }
                    if (hitUserTimestamp) {
                        SepiaToggleRow(
                            title = "Show User Timestamp",
                            checked = prefs.showUserTimestamp,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showUserTimestamp = it)) }
                        )
                    }
                    if (hitUserActions) {
                        SepiaToggleRow(
                            title = "Show User Message Actions",
                            checked = prefs.showUserMessageActions,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showUserMessageActions = it)) }
                        )
                    }
                    if (hitModelIcon) {
                        SepiaToggleRow(
                            title = "Show Model Icon / Avatar",
                            checked = prefs.showModelIcon,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showModelIcon = it)) }
                        )
                    }
                    if (hitModelName) {
                        SepiaToggleRow(
                            title = "Show Model Name",
                            checked = prefs.showModelName,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showModelName = it)) }
                        )
                    }
                    if (hitModelTimestamp) {
                        SepiaToggleRow(
                            title = "Show Model Timestamp",
                            checked = prefs.showModelTimestamp,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showModelTimestamp = it)) }
                        )
                    }
                    if (hitProviderAfterModel) {
                        SepiaToggleRow(
                            title = "Show Provider After Model Name",
                            checked = prefs.showProviderAfterModelName,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showProviderAfterModelName = it)) }
                        )
                    }
                    if (hitTokenStats) {
                        SepiaToggleRow(
                            title = "Show Token & Context Stats",
                            checked = prefs.showTokenAndContextStats,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showTokenAndContextStats = it)) }
                        )
                    }
                    if (hitTokensEntireTurn) {
                        SepiaToggleRow(
                            title = "Show tokens for the entire turn",
                            checked = prefs.showTokensForEntireTurn,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showTokensForEntireTurn = it)) }
                        )
                    }
                    if (hitThinkingCards) {
                        SepiaToggleRow(
                            title = "Show Thinking Cards",
                            checked = prefs.showThinkingCards,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showThinkingCards = it)) }
                        )
                    }
                    if (hitToolCards) {
                        SepiaToggleRow(
                            title = "Show Tool Cards",
                            checked = prefs.showToolCards,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(showToolCards = it)) }
                        )
                    }
                    if (hitReasoningButton) {
                        SepiaToggleRow(
                            title = "Show reasoning level on the button",
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
                            title = "Math Formula Rendering",
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
                            title = "Render reasoning (thinking) with Markdown & LaTeX",
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
                            title = "Auto-collapse Code Blocks",
                            checked = prefs.autoCollapseCodeBlocks,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(autoCollapseCodeBlocks = it)) }
                        )
                    }
                    if (hitCollapseLong) {
                        SepiaToggleRow(
                            title = "Collapse Long Messages (> 1,800 chars)",
                            checked = prefs.collapseLongMessages,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(collapseLongMessages = it)) }
                        )
                    }
                    if (hitAutoTopics) {
                        SepiaToggleRow(
                            title = "Auto switch to Topics",
                            checked = prefs.autoSwitchToTopics,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(autoSwitchToTopics = it)) }
                        )
                    }
                    if (hitAutoCollapseThink) {
                        SepiaToggleRow(
                            title = "Auto-collapse Thinking",
                            checked = prefs.autoCollapseThinking,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(autoCollapseThinking = it)) }
                        )
                    }
                    if (hitToolSummary) {
                        SepiaToggleRow(
                            title = "Show Tool Result Summary",
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
                            title = "Keep Message Versions When Forking",
                            checked = prefs.keepMessageVersionsWhenForking,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(keepMessageVersionsWhenForking = it)) }
                        )
                    }
                    if (hitChatListDates) {
                        SepiaToggleRow(
                            title = "Show Chat List Dates",
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
                            title = "New Chat on Launch",
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
                            title = "Auto-compact context when reaching 85% of context limit",
                            checked = prefs.autoCompactWhenNearContextLimit,
                            onCheckedChange = { onUpdatePreferences(prefs.copy(autoCompactWhenNearContextLimit = it)) }
                        )
                    }
                    if (hitNavMinimap) {
                        SepiaAdaptiveRow(label = "Message Navigation Minimap") {
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
                        SepiaAdaptiveRow(label = "Send Shortcut (Desktop)") {
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
                subtitle = "API credentials, protocol parameters, built-in capabilities, and model selection",
                headerAction = {
                    SepiaButton(
                        text = "Open Providers →",
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
                            label = "Base URL (${activeProfile.name})",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (hitStateful) {
                        SepiaToggleRow(
                            title = "Stateful Interaction Chaining (previous_interaction_id / previous_response_id)",
                            checked = activeProfile.useStatefulChaining,
                            onCheckedChange = { v -> onUpdateProfile { it.copy(useStatefulChaining = v) } }
                        )
                    }
                    if (hitLongDecode) {
                        SepiaToggleRow(
                            title = "Background Poll & Stream Recovery",
                            checked = activeProfile.useBackgroundLongDecode,
                            onCheckedChange = { v -> onUpdateProfile { it.copy(useBackgroundLongDecode = v) } }
                        )
                    }
                    if (hitLegacyFallback) {
                        SepiaToggleRow(
                            title = "Smart Legacy Endpoint Fallback (:streamGenerateContent or /v1/chat/completions)",
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
                                label = "Temperature (blank = omit)",
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
                                label = "Top-P (blank = omit)",
                                monospace = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (hitCapabilities) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SepiaChip(
                                label = "🌐 Web Search",
                                selected = activeProfile.enableWebSearch,
                                onClick = { onUpdateProfile { it.copy(enableWebSearch = !it.enableWebSearch) } }
                            )
                            SepiaChip(
                                label = "💻 Code Execution",
                                selected = activeProfile.enableCodeExecution,
                                onClick = { onUpdateProfile { it.copy(enableCodeExecution = !it.enableCodeExecution) } }
                            )
                            SepiaChip(
                                label = "🔗 URL Context",
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
                                    .clip(RoundedCornerShape(7.dp))
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
                                    style = p.uiLabelStyle.copy(fontSize = 10.5.sp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (anyWorkspaceMatch) {
            SepiaSectionCard(
                title = "Workspace & Local Agent Harness",
                subtitle = "Approvals and local tool access",
                headerAction = {
                    SepiaButton(
                        text = "Open Workspace →",
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
                title = "Default Models (Global, Title, Summary, Translation)",
                subtitle = "Task-specific model assignments and title prompt template",
                headerAction = {
                    SepiaButton(
                        text = "Open Default Models →",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { onJumpToCategory(SettingsCategory.DEFAULT_MODEL) }
                    )
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hitTitleModel || hitGlobalModel) {
                        SepiaToggleRow(
                            title = "Title Generation: Follow Global Choice",
                            checked = appState.defaultModels.titleFollowGlobal,
                            onCheckedChange = { onUpdateDefaultModels(appState.defaultModels.copy(titleFollowGlobal = it)) }
                        )
                    }
                    if (hitSummaryModel || hitGlobalModel) {
                        SepiaToggleRow(
                            title = "Summary & Context Compression: Follow Global Choice",
                            checked = appState.defaultModels.summaryFollowGlobal,
                            onCheckedChange = { onUpdateDefaultModels(appState.defaultModels.copy(summaryFollowGlobal = it)) }
                        )
                    }
                    if (hitTranslationModel || hitGlobalModel) {
                        SepiaToggleRow(
                            title = "Translation: Follow Global Choice",
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
                subtitle = "Persistent assistant profiles, system prompts, and sampling overrides",
                headerAction = {
                    SepiaButton(
                        text = "Open Assistants →",
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
                title = "World Book & Character Cards",
                subtitle = "PNG / JSON Character Cards and keyword-triggered lore fragments",
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
                    text = "${matchingLore.size} matching World Book fragment(s).",
                    style = p.uiLabelStyle
                )
            }
        }

        if (hitOtaBackup) {
            SepiaSectionCard(
                title = "Backup & In-App OTA Updates",
                subtitle = "Installed v${OtaUpdateEngine.currentAppVersion} • Portable JSON backups & GitHub release updater",
                headerAction = {
                    SepiaButton(
                        text = "Open Backup & OTA →",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { onJumpToCategory(SettingsCategory.BACKUP_OTA) }
                    )
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SepiaToggleRow(
                        title = "Auto-Check for OTA Updates on Launch",
                        checked = appState.autoCheckOtaOnStartup,
                        onCheckedChange = { onUpdateOtaSettings(appState.otaGithubRepo, it) }
                    )
                    SepiaButton(
                        text = "↻ Check for Update Now",
                        compact = true,
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = onCheckOtaNow
                    )
                }
            }
        }
    }
}
