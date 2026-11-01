package io.parchment.ai.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderProtocol(val displayName: String, val defaultBaseUrl: String, val defaultEndpointPath: String) {
    GEMINI_INTERACTIONS(
        displayName = "Google Gemini Interactions API",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        defaultEndpointPath = "/v1beta/interactions"
    ),
    OPENAI_RESPONSES(
        displayName = "OpenAI Responses API",
        defaultBaseUrl = "https://api.openai.com",
        defaultEndpointPath = "/v1/responses"
    ),
    ANTHROPIC_MESSAGES(
        displayName = "Anthropic Messages API",
        defaultBaseUrl = "https://api.anthropic.com",
        defaultEndpointPath = "/v1/messages"
    )
}

@Serializable
enum class AuthHeaderMode(val label: String) {
    NATIVE("Provider Native (x-goog-api-key / x-api-key / Bearer)"),
    BEARER_ONLY("Force Authorization: Bearer <key> (Proxy Mode)"),
    QUERY_PARAM("Query Parameter (?key=<key>)")
}

@Serializable
data class ProviderProfile(
    val id: String,
    val name: String,
    val protocol: ProviderProtocol,
    val baseUrl: String = protocol.defaultBaseUrl,
    val endpointPathOverride: String = "",
    val apiKey: String = "",
    val authHeaderMode: AuthHeaderMode = AuthHeaderMode.NATIVE,
    val customHeadersRaw: String = "",
    val defaultModel: String = "",
    val savedModels: List<String> = emptyList(),
    val autoFetchModels: Boolean = true,
    val timeoutMinutes: Int = 60,
    val useStatefulChaining: Boolean = true,
    val useBackgroundLongDecode: Boolean = false,
    val useLegacyFallbackEndpoint: Boolean = false,
    val enableWebSearch: Boolean = false,
    val enableCodeExecution: Boolean = false,
    val enableUrlContext: Boolean = false,
    val thinkingEffort: String = "high", // none, low, medium, high, max
    val thinkingBudgetTokens: Int = 16384,
    val temperature: Double = 0.85,
    val topP: Double = 0.95,
    val maxOutputTokens: Int = 32768,
    val anthropicBetaHeaders: String = "interleaved-thinking-2025-05-14,output-128k-2025-02-19",
    val rawJsonExtraBody: String = ""
)

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

@Serializable
data class ToolCallRecord(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val resultText: String = "",
    val isError: Boolean = false
)

@Serializable
data class TokenMetrics(
    val inputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val outputTokens: Int = 0,
    val ttftMs: Long = 0L,
    val durationMs: Long = 0L,
    val tokensPerSec: Double = 0.0
)

@Serializable
data class MessageVariant(
    val id: String,
    val content: String,
    val thinkingSummary: String = "",
    val thinkingSignature: String = "",
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val providerInteractionId: String = "",
    val providerProtocol: ProviderProtocol? = null,
    val modelUsed: String = "",
    val createdAt: Long = 0L,
    val tokenMetrics: TokenMetrics = TokenMetrics(),
    val rawRequestPreview: String = "",
    val rawResponsePreview: String = ""
)

@Serializable
data class MessageNode(
    val id: String,
    val role: MessageRole,
    val variants: List<MessageVariant>,
    val activeVariantIndex: Int = 0,
    val includedInContext: Boolean = true
) {
    val activeVariant: MessageVariant
        get() = variants.getOrElse(activeVariantIndex.coerceIn(0, (variants.size - 1).coerceAtLeast(0))) {
            MessageVariant(id = id, content = "")
        }
}

@Serializable
enum class ViewMode(val label: String) {
    MANUSCRIPT("Manuscript (Continuous Book Flow)"),
    EDITORIAL_TURNS("Editorial Turns (Structured)")
}

@Serializable
enum class WorkspaceMode(val label: String) {
    OFF("Workspace: Off"),
    READ_ONLY("Workspace: Read-Only"),
    READ_WRITE("Workspace: Agent R/W")
}

@Serializable
data class CharacterOrScenarioCard(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val exampleDialogue: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val sourceFormat: String = "parchment-v1"
) {
    fun buildCompiledInstructions(userName: String = "User"): String {
        val sb = StringBuilder()
        fun replaceMacros(text: String): String =
            text.replace("{{char}}", name.ifBlank { "Character" }, ignoreCase = true)
                .replace("{{user}}", userName, ignoreCase = true)

        if (systemPrompt.isNotBlank()) {
            sb.appendLine(replaceMacros(systemPrompt.trim()))
            sb.appendLine()
        }
        if (name.isNotBlank()) {
            sb.appendLine("### Character / Persona: ${name.trim()}")
        }
        if (description.isNotBlank()) {
            sb.appendLine(replaceMacros(description.trim()))
            sb.appendLine()
        }
        if (personality.isNotBlank()) {
            sb.appendLine("### Personality & Voice")
            sb.appendLine(replaceMacros(personality.trim()))
            sb.appendLine()
        }
        if (scenario.isNotBlank()) {
            sb.appendLine("### Scenario & Setting")
            sb.appendLine(replaceMacros(scenario.trim()))
            sb.appendLine()
        }
        if (exampleDialogue.isNotBlank()) {
            sb.appendLine("### Style & Dialogue Examples")
            sb.appendLine(replaceMacros(exampleDialogue.trim()))
            sb.appendLine()
        }
        return sb.toString().trim()
    }
}

@Serializable
data class SystemPromptPreset(
    val id: String,
    val name: String,
    val description: String = "",
    val systemPrompt: String,
    val authorsNoteDefault: String = "",
    val suggestedTemperature: Double = 0.85,
    val suggestedTopP: Double = 0.95,
    val suggestedThinkingEffort: String = "high"
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val profileId: String,
    val modelOverride: String = "",
    val userName: String = "Traveler",
    val systemPrompt: String = DEFAULT_LITERARY_SYSTEM_PROMPT,
    val activeCard: CharacterOrScenarioCard? = null,
    val authorsNote: String = "",
    val authorsNoteDepth: Int = 2,
    val viewMode: ViewMode = ViewMode.MANUSCRIPT,
    val workspacePath: String = "",
    val workspaceMode: WorkspaceMode = WorkspaceMode.OFF,
    val messages: List<MessageNode> = emptyList()
) {
    companion object {
        const val DEFAULT_LITERARY_SYSTEM_PROMPT =
            "You are a master literary collaborator, researcher, and prose stylist. Prioritize vivid sensory grounding, authentic dialogue, psychological depth, and rhythmic sentence craft. Avoid repetitive clichés, purple melodrama, or unprompted moralizing summaries at the end of scenes."
    }
}

@Serializable
data class ThemeConfig(
    val name: String = "Parchment & Espresso Ink (Sepia)",
    val canvasBgHex: String = "#F4ECD8",
    val sheetBgHex: String = "#EBE0C5",
    val cardBgHex: String = "#FAF6EB",
    val elevatedBgHex: String = "#EFE5CC",
    val borderHex: String = "#D5C4A1",
    val primaryInkHex: String = "#261C14",
    val dialogueInkHex: String = "#4A2511",
    val narrationInkHex: String = "#5C4938",
    val mutedInkHex: String = "#826F5C",
    val accentHex: String = "#9E4730",
    val accentSecondaryHex: String = "#B57C2E",
    val dangerHex: String = "#A62B2B",
    val proseFontFamily: String = "Serif", // Serif, Sans, Monospace, Custom
    val customProseFontPath: String = "",
    val codeFontFamily: String = "Monospace", // Monospace, Sans, Serif, Custom
    val customCodeFontPath: String = "",
    val proseFontSizeSp: Float = 17.5f,
    val codeFontSizeSp: Float = 12.5f,
    val proseLineHeightMultiplier: Float = 1.62f,
    val paragraphSpacingDp: Float = 12f,
    val firstLineIndentChars: Int = 0,
    val maxReadingWidthDp: Float = 760f,
    val highlightDialogueAndNarration: Boolean = true
) {
    companion object {
        val PRESETS: List<ThemeConfig> = listOf(
            ThemeConfig(),
            ThemeConfig(
                name = "Aged Oxford Manuscript (Warm Deep Sepia)",
                canvasBgHex = "#EADBC0",
                sheetBgHex = "#DECBA8",
                cardBgHex = "#F3E7D0",
                elevatedBgHex = "#E4D3B5",
                borderHex = "#C4AE88",
                primaryInkHex = "#1F160F",
                dialogueInkHex = "#421E0D",
                narrationInkHex = "#523F2E",
                mutedInkHex = "#75614D",
                accentHex = "#8C3823",
                accentSecondaryHex = "#9E6B24"
            ),
            ThemeConfig(
                name = "Candlelight Study (Dark Warm Sepia)",
                canvasBgHex = "#1C1612",
                sheetBgHex = "#15100D",
                cardBgHex = "#251E18",
                elevatedBgHex = "#2E251E",
                borderHex = "#3D3127",
                primaryInkHex = "#E8DCC4",
                dialogueInkHex = "#F2C69D",
                narrationInkHex = "#BFB099",
                mutedInkHex = "#8C7D6B",
                accentHex = "#D4724E",
                accentSecondaryHex = "#D9A14E"
            ),
            ThemeConfig(
                name = "Midnight OLED Ink (OnePlus 15T AMOLED)",
                canvasBgHex = "#080706",
                sheetBgHex = "#120F0C",
                cardBgHex = "#171310",
                elevatedBgHex = "#211C17",
                borderHex = "#2E261F",
                primaryInkHex = "#DFD3BD",
                dialogueInkHex = "#EABFA0",
                narrationInkHex = "#B3A48E",
                mutedInkHex = "#7A6D5B",
                accentHex = "#C96A47",
                accentSecondaryHex = "#C49142"
            )
        )
    }
}

@Serializable
data class AppStateSnapshot(
    val profiles: List<ProviderProfile> = emptyList(),
    val activeProfileId: String = "",
    val sessions: List<ChatSession> = emptyList(),
    val activeSessionId: String = "",
    val systemPromptPresets: List<SystemPromptPreset> = emptyList(),
    val savedCards: List<CharacterOrScenarioCard> = emptyList(),
    val theme: ThemeConfig = ThemeConfig()
)
