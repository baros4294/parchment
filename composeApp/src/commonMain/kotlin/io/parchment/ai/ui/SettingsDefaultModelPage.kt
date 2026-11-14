package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import io.parchment.ai.strings.Strings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.DefaultModelConfig
import io.parchment.ai.model.ProviderProfile

// ============================================================================================
// Settings page 4: Default models (chat, titles, context compression, translation).
// ============================================================================================
@Composable
internal fun DefaultModelSubpage(
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
        verticalArrangement = Arrangement.spacedBy(Space.s16)
    ) {
        BasicText(text = Strings.defaultModels, style = p.uiHeaderStyle.copy(fontSize = 19.sp))

        // 1. Global Default Chat Model
        SepiaSectionCard(
            title = Strings.n1GlobalDefaultModel
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
                    onNotify(Strings.setGlobalDefaultModelTo(profName, modelId))
                },
                maxListHeight = 240.dp
            )
        }

        // 2. Title Summary Model
        SepiaSectionCard(
            title = Strings.n2TitleGenerationModel
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                SepiaToggleRow(
                    title = Strings.followGlobalChoiceUseCurrent,
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
                    label = Strings.titleGenerationPrompt,
                    singleLine = false,
                    minHeight = 52.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 3. Summary / Context Compression Model
        SepiaSectionCard(
            title = Strings.n3SummaryContextCompressionModel
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                SepiaToggleRow(
                    title = Strings.followGlobalChoiceUseCurrent,
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
            title = Strings.n4TranslationModelTargetLanguage
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                SepiaTextField(
                    value = defaultModels.translationTargetLanguage,
                    onValueChange = { lang -> onUpdateDefaultModels(defaultModels.copy(translationTargetLanguage = lang)) },
                    label = Strings.targetLanguageForInChat,
                    placeholder = Strings.englishVietnameseJapaneseSpanish,
                    modifier = Modifier.fillMaxWidth()
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s6)) {
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
                    title = Strings.followGlobalChoiceUseCurrent,
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
