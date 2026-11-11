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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.CharacterOrScenarioCard
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.ContextFragment
import io.parchment.ai.model.FragmentActivationMode
import io.parchment.ai.model.SystemPromptPreset
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.protocol.CardAndPromptCodec
import kotlinx.coroutines.launch

// ============================================================================================
// Settings page 6: World Book and character cards (.png or .json).
// ============================================================================================
@Composable
internal fun WorldBookAndCardsSubpage(
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
        BasicText(text = "World Book & character cards (.png / .json)", style = p.uiHeaderStyle.copy(fontSize = 19.sp))

        SepiaSectionCard(
            title = "Import / export .PNG & .JSON cards"
        ) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SepiaButton(
                    text = "📥 Import .PNG or .JSON",
                    style = SepiaButtonStyle.PRIMARY,
                    onClick = {
                        scope.launch {
                            val picked = PlatformBridge.pickFileForImport(listOf("png", "json")) ?: return@launch
                            if (picked.oversized) {
                                onNotify("${picked.fileName} is too large to import.")
                                return@launch
                            }
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
                    text = "📤 Export card as .PNG",
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
            title = "Active session character / scenario card",
            headerAction = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaButton(
                        text = "Apply to session",
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
                SepiaTextField(value = cardDesc, onValueChange = { cardDesc = it }, label = "Description / persona", singleLine = false, minHeight = 70.dp)
                SepiaTextField(value = cardFirstMes, onValueChange = { cardFirstMes = it }, label = "Opening greeting (first_mes)", singleLine = false, minHeight = 60.dp)
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
                            text = "+ Save current prompt",
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
                            text = "No saved presets yet. Import a .PNG/.JSON prompt preset or click '+ save current Prompt'.",
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
                                    contentDescription = "Delete preset",
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
            title = "World Book entries (${displayedFragments.size} entries)"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaChip(
                        label = "This chat (${activeSession.fragments.size})",
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
                    SepiaTextField(value = fragTitle, onValueChange = { fragTitle = it }, label = "Entry title", modifier = Modifier.fillMaxWidth())
                    SepiaTextField(value = fragKeywords, onValueChange = { fragKeywords = it }, label = "Trigger keywords (comma-separated)", modifier = Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SepiaTextField(value = fragTitle, onValueChange = { fragTitle = it }, label = "Entry title", modifier = Modifier.weight(1f))
                        SepiaTextField(value = fragKeywords, onValueChange = { fragKeywords = it }, label = "Trigger keywords (comma-separated)", modifier = Modifier.weight(1f))
                    }
                }
                SepiaTextField(value = fragContent, onValueChange = { fragContent = it }, label = "Entry text", singleLine = false, minHeight = 56.dp)
                SepiaButton(
                    text = if (editingGlobalWorldBook) "+ Add to Global World Book" else "+ Add to chat World Book",
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
                            .clip(RoundedCornerShape(8.dp))
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
                                contentDescription = "Remove entry",
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
