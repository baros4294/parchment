package io.parchment.ai.ui

import androidx.compose.ui.draw.alpha
import io.parchment.ai.strings.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.platform.PlatformRuntime

/** Chat notes and memory: extra instructions, a style reminder and the remembered summary, opened from Notes in the top bar. */
@Composable
internal fun ChatNotesPanel(
    activeSession: ChatSession,
    isStreaming: Boolean,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onExportChatMarkdown: () -> Unit,
    onRequestClearCurrentChat: () -> Unit,
    onToggleSessionInspector: () -> Unit,
    onAutoSummarizeStory: () -> Unit,
    onCompactContext: () -> Unit
) {
    val p = LocalParchmentPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState())
            .background(p.elevatedBg.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = Space.s8),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = p.maxReadingWidth)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.r10))
                .background(p.cardBg)
                .border(LineWidth.hairline, p.border, RoundedCornerShape(Radius.r10))
                .padding(Space.s12),
            verticalArrangement = Arrangement.spacedBy(Space.s8)
        ) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Space.s6)
            ) {
                BasicText(
                    text = Strings.chatNotesMemory,
                    style = p.uiHeaderStyle.copy(fontSize = 15.sp),
                    modifier = Modifier.padding(top = Space.s4)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalAlignment = Alignment.CenterVertically) {
                    if (activeSession.messages.isNotEmpty()) {
                        SepiaButton(
                            text = Strings.exportMd,
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = onExportChatMarkdown
                        )
                        SepiaButton(
                            text = Strings.clearChat,
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = onRequestClearCurrentChat
                        )
                    }
                    SepiaButton(
                        text = "×",
                        contentDescription = Strings.closeSessionInspector,
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = onToggleSessionInspector
                    )
                }
            }
            SepiaDivider()
            SepiaTextField(
                value = activeSession.systemPrompt,
                onValueChange = { sp -> onUpdateSession { it.copy(systemPrompt = sp) } },
                label = Strings.extraInstructionsForThisChat,
                placeholder = Strings.optionalInstructionsAddedSpecificallyTo,
                singleLine = false,
                minHeight = 64.dp,
                maxHeight = 320.dp,
                modifier = Modifier.fillMaxWidth()
            )
            SepiaTextField(
                value = activeSession.authorsNote,
                onValueChange = { note -> onUpdateSession { it.copy(authorsNote = note) } },
                label = Strings.styleToneReminder,
                placeholder = Strings.optionalReminderKeptNearThe,
                singleLine = false,
                minHeight = 52.dp,
                maxHeight = 260.dp,
                modifier = Modifier.fillMaxWidth()
            )
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Space.s6)
            ) {
                BasicText(
                    text = Strings.chatSummaryMemory,
                    style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(top = Space.s4, end = Space.s8)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
                    val canCompactSession = activeSession.messages.count { it.includedInContext } > 1 ||
                        activeSession.messages.any { it.includedInContext && it.activeVariant.toolCalls.isNotEmpty() }
                    SepiaButton(
                        text = if (isStreaming) Strings.summarizeResume else Strings.summarize,
                        compact = true,
                        enabled = activeSession.messages.isNotEmpty(),
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = onAutoSummarizeStory
                    )
                    SepiaButton(
                        text = if (isStreaming) Strings.shortenAndContinue else Strings.shortenConversation,
                        compact = true,
                        enabled = canCompactSession,
                        style = SepiaButtonStyle.SECONDARY,
                        onClick = onCompactContext
                    )
                }
            }
            SepiaTextField(
                value = activeSession.storySummary,
                onValueChange = { sum -> onUpdateSession { it.copy(storySummary = sum) } },
                placeholder = Strings.keyFactsOrSummaryRemembered,
                singleLine = false,
                minHeight = 110.dp,
                maxHeight = 380.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** What an empty chat shows: who you're talking to, the model, setup when no key is set, and a card's opening scene. */
@Composable
internal fun EmptyChatIntro(
    activeSession: ChatSession,
    activeAssistant: AssistantAgent,
    activeProfile: ProviderProfile,
    preferences: PreferencesConfig,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onOpenProviderSettings: () -> Unit
) {
    val p = LocalParchmentPalette.current
    val activeModel = activeSession.modelOverride.ifBlank { activeProfile.defaultModel }
    val card = activeSession.activeCard ?: activeAssistant.activeCard
    val displayName = card?.name?.takeIf { it.isNotBlank() }
        ?: activeAssistant.name.takeIf { it.isNotBlank() }
        ?: Strings.ready
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 44.dp)
            .rubricMark(p.accent)
            .padding(start = Space.s16, top = Space.s4, bottom = Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s6)
    ) {
        if (activeAssistant.iconEmoji != "💬") {
            SepiaAvatarCircle(symbol = activeAssistant.iconEmoji, selected = true, sizeDp = 40.dp)
        }
        BasicText(
            text = if (displayName == "Default Assistant") "Start with a question, a draft or a file." else displayName,
            style = p.uiHeaderStyle.copy(fontSize = 21.sp, lineHeight = 28.sp)
        )
        BasicText(
            text = activeModel.ifBlank { Strings.chooseAModelInSettings },
            style = p.uiLabelStyle.copy(color = p.mutedInk)
        )
        // First run: without a key nothing can be sent, so say so here and lead straight to setup.
        if (needsApiKey(activeProfile)) {
            Spacer(Modifier.height(Space.s4))
            BasicText(
                text = Strings.addYourApiKeyToStart(activeProfile.name),
                style = p.uiBodyStyle
            )
            SepiaButton(
                text = Strings.setUp(activeProfile.name),
                compact = true,
                style = SepiaButtonStyle.PRIMARY,
                onClick = onOpenProviderSettings
            )
        }
        if (card != null && card.firstMessage.isNotBlank()) {
            Spacer(Modifier.height(Space.s4))
            SepiaButton(
                text = Strings.startOpeningScene,
                compact = true,
                style = SepiaButtonStyle.PRIMARY,
                onClick = {
                    val now = PlatformRuntime.currentTimeMillis()
                    val allGreetings = (listOf(card.firstMessage) + card.alternateGreetings).filter { it.isNotBlank() }
                    if (allGreetings.isNotEmpty()) {
                        val greetingNode = MessageNode(
                            id = "node-greeting-$now",
                            role = MessageRole.ASSISTANT,
                            variants = allGreetings.mapIndexed { idx, g ->
                                MessageVariant(
                                    id = "var-greeting-$idx-$now",
                                    content = g.replace("{{char}}", card.name)
                                        .replace("{{user}}", activeSession.withProfileName(preferences).userName),
                                    modelUsed = "Card Greeting #${idx + 1}",
                                    createdAt = now
                                )
                            }
                        )
                        onUpdateSession { it.copy(messages = listOf(greetingNode)) }
                    }
                }
            )
        }
    }
}
