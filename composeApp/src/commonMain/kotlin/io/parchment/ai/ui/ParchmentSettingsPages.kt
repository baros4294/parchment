package io.parchment.ai.ui

import androidx.compose.animation.Crossfade
import io.parchment.ai.strings.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.CharacterOrScenarioCard
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.ContextFragment
import io.parchment.ai.model.DefaultModelConfig
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.SystemPromptPreset
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.network.OtaReleaseInfo
import io.parchment.ai.network.OtaUpdateEngine
import io.parchment.ai.network.OtaUpdateState
import io.parchment.ai.platform.PlatformRuntime

enum class SettingsCategory(val icon: String, val label: String) {
    PREFERENCES("🖥", Strings.preferences),
    PROVIDERS("🖧", Strings.providers2),
    ASSISTANTS("🤖", Strings.assistant),
    DEFAULT_MODEL("♡", Strings.defaultModel),
    WORKSPACE("📂", Strings.workspace2),
    WORLD_BOOK("📖", Strings.worldBookCards),
    BACKUP_OTA("☁", Strings.storageUpdates)
}

internal const val PROVIDER_STORAGE_TITLE = "Keep Responses on Provider Servers"
internal const val PROVIDER_STORAGE_SUBTITLE = "Off by default. Needed to pick up interrupted replies and for provider-side conversation memory."

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
                        .textScaledWidth(225.dp)
                        .fillMaxHeight()
                        .background(p.sheetBg.copy(alpha = 0.65f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.s6)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = Strings.settings,
                            style = p.uiHeaderStyle.copy(fontSize = 16.sp)
                        )
                        SepiaButton(
                            text = Strings.chat,
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = onClose
                        )
                    }

                    Spacer(Modifier.height(Space.s4))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.s4),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SepiaTextField(
                            value = settingsSearchQuery,
                            onValueChange = { settingsSearchQuery = it },
                            placeholder = Strings.searchAllSettings,
                            minHeight = 36.dp,
                            modifier = Modifier.weight(1f)
                        )
                        if (settingsSearchQuery.isNotEmpty()) {
                            SepiaButton(
                                text = "×",
                                contentDescription = Strings.clearSearch,
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = { settingsSearchQuery = "" }
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.s6))

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
                        val shape = RoundedCornerShape(Radius.r10)
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
                                    selected = isSelected,
                                    onClick = {
                                        selectedCategory = cat
                                        settingsSearchQuery = ""
                                    }
                                )
                                .clip(shape)
                                .background(
                                    when {
                                        isSelected -> p.cardBg
                                        isHighlightedBySearch -> p.accent.copy(alpha = 0.10f)
                                        isPressed -> p.accent.copy(alpha = 0.12f)
                                        isHovered -> p.accent.copy(alpha = 0.08f)
                                        else -> Color.Transparent
                                    }
                                )
                                .then(if (isSelected) Modifier.rubricMark(p.accent, verticalInset = 9.dp) else Modifier)
                                .padding(horizontal = Space.s12, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Space.s10),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                KissVectorIcon(
                                    icon = catIcon,
                                    tint = if (isSelected || isHovered || isPressed || isHighlightedBySearch) p.accent else p.mutedInk,
                                    size = 15.dp
                                )
                                BasicText(
                                    text = cat.label,
                                    style = p.uiBodyStyle.copy(
                                        fontWeight = if (isSelected || isHighlightedBySearch) FontWeight.Medium else FontWeight.Normal,
                                        fontSize = 14.sp
                                    )
                                )
                            }
                            if (settingsSearchQuery.isNotBlank() && matchCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.pill))
                                        .background(p.accent.copy(alpha = 0.18f))
                                        .padding(horizontal = Space.s6, vertical = Space.s2)
                                ) {
                                    BasicText(
                                        text = matchCount.toString(),
                                        style = p.monoStyle.copy(fontSize = 11.sp, color = p.accent, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .width(LineWidth.hairline)
                        .fillMaxHeight()
                        .background(p.border.copy(alpha = 0.7f))
                )

                // Right Subpage Viewport
                Crossfade(
                    targetState = selectedCategory,
                    animationSpec = motionTween(Motion.STANDARD),
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
            // A vertical list of sections, each opening its own page (no horizontal chip strip).
            var mobileOpenedCategory by remember(initialCategory, startAtMobileRoot) {
                mutableStateOf<SettingsCategory?>(if (startAtMobileRoot) null else initialCategory)
            }
            var dragX by remember { mutableStateOf(0f) }

            LaunchedEffect(mobileOpenedCategory, settingsSearchQuery) {
                PlatformRuntime.registerBackHandler {
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
                SettingsCategory.PREFERENCES -> Strings.themeFontsDisplayBehavior(appState.theme.name.substringBefore(" ("))
                SettingsCategory.PROVIDERS -> "${activeProfile.name} • ${plural(appState.profiles.size, "provider profile")}"
                SettingsCategory.ASSISTANTS -> "${plural(appState.assistants.size, "assistant persona")} & system prompts"
                SettingsCategory.DEFAULT_MODEL -> {
                    val curModel = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }.ifBlank { Strings.notSelected }
                    Strings.globalTopicTitleSummaryTranslation(curModel)
                }
                SettingsCategory.WORKSPACE -> Strings.agentApprovalsFileAccessWebAnd
                SettingsCategory.WORLD_BOOK -> Strings.characterCardsPngJson(plural(appState.globalWorldBook.size + activeSession.fragments.size, "lore fragment"))
                SettingsCategory.BACKUP_OTA -> Strings.storageBackupsAndAppUpdatesV(OtaUpdateEngine.currentAppVersion)
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
                        .padding(horizontal = Space.s12, vertical = Space.s10),
                    verticalArrangement = Arrangement.spacedBy(Space.s8)
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
                                horizontalArrangement = Arrangement.spacedBy(Space.s8),
                                modifier = Modifier.weight(1f)
                            ) {
                                SepiaButton(
                                    text = Strings.settings2,
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
                                horizontalArrangement = Arrangement.spacedBy(Space.s8)
                            ) {
                                KissVectorIcon(icon = KissIcon.SETTINGS, tint = p.accent, size = 17.dp)
                                BasicText(
                                    text = Strings.settings,
                                    style = p.uiHeaderStyle.copy(fontSize = 18.sp)
                                )
                            }
                        }
                        SepiaButton(
                            text = Strings.chat,
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onClose
                        )
                    }

                    if (mobileOpenedCategory == null || settingsSearchQuery.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Space.s6),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SepiaTextField(
                                value = settingsSearchQuery,
                                onValueChange = { settingsSearchQuery = it },
                                placeholder = Strings.searchAllSettings,
                                minHeight = 36.dp,
                                modifier = Modifier.weight(1f)
                            )
                            if (settingsSearchQuery.isNotEmpty()) {
                                SepiaButton(
                                    text = "×",
                                    contentDescription = Strings.clearSearch,
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
                    animationSpec = motionTween(Motion.STANDARD),
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
                                .padding(horizontal = Space.s12, vertical = Space.s12),
                            verticalArrangement = Arrangement.spacedBy(Space.s10)
                        ) {
                            SettingsCategory.entries.forEach { cat ->
                                val catIcon = remember(cat.icon) {
                                    parseKissLabelIcons(cat.icon).leadingIcon ?: KissIcon.SETTINGS
                                }
                                val cardShape = RoundedCornerShape(Radius.r12)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(cardShape)
                                        .background(p.cardBg)
                                        .border(LineWidth.hairline, p.border, cardShape)
                                        .kissClickable(pressScale = 0.985f) {
                                            selectedCategory = cat
                                            mobileOpenedCategory = cat
                                        }
                                        .padding(horizontal = 14.dp, vertical = 13.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f).padding(end = Space.s10),
                                        horizontalArrangement = Arrangement.spacedBy(Space.s12),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(Radius.r8))
                                                .background(p.accent.copy(alpha = 0.14f))
                                                .border(LineWidth.hairline, p.accent.copy(alpha = 0.45f), RoundedCornerShape(Radius.r8)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            KissVectorIcon(icon = catIcon, tint = p.accent, size = 17.dp)
                                        }
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(Space.s2)
                                        ) {
                                            BasicText(
                                                text = cat.label,
                                                style = p.uiHeaderStyle.copy(fontSize = 15.sp, color = p.primaryInk)
                                            )
                                            BasicText(
                                                text = categorySubtitle(cat),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = p.uiLabelStyle.copy(fontSize = 12.sp, color = p.narrationInk)
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
            onUpdatePreferences = onUpdatePreferences,
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

/**
 * Selects a sensible initial chat model when a brand-new profile fetches models for the first time,
 * skipping utility/research/embedding endpoints like `deep-research*` or `text-embedding*`.
 */
fun pickBestInitialChatModel(models: List<String>): String {
    return models.firstOrNull() ?: ""
}
