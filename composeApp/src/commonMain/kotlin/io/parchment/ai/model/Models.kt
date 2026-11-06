package io.parchment.ai.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class ProviderProtocol(val displayName: String, val defaultBaseUrl: String, val defaultEndpointPath: String) {
    GEMINI_INTERACTIONS(
        displayName = "Google Gemini API",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        defaultEndpointPath = "/v1beta/interactions"
    ),
    OPENAI_RESPONSES(
        displayName = "OpenAI / Universal Compatible API",
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
enum class MediaKind {
    IMAGE,
    VIDEO,
    AUDIO
}

@Immutable
@Serializable
data class GeneratedMediaItem(
    val id: String,
    val kind: MediaKind,
    val mimeType: String = "image/png",
    val localFilePath: String = "",
    val remoteUrl: String = "",
    val revisedPrompt: String = "",
    val aspectRatio: String = ""
)

@Serializable
enum class AuthHeaderMode(val label: String) {
    NATIVE("Provider Native (x-goog-api-key / x-api-key / Bearer)"),
    BEARER_ONLY("Force Authorization: Bearer <key> (Proxy Mode)"),
    QUERY_PARAM("Query Parameter (?key=<key>)")
}

@Immutable
@Serializable
data class ModelPricingSpec(
    val modelId: String,
    val displayName: String = "",
    val providerName: String = "",
    val inputPricePerM: Double = 0.0,
    val outputPricePerM: Double = 0.0,
    val cacheReadPricePerM: Double = 0.0,
    val cacheWritePricePerM: Double = 0.0,
    val reasoningPricePerM: Double = 0.0,
    val contextWindowTokens: Int = 128000,
    val maxOutputTokens: Int? = null,
    val outputModalities: List<String> = emptyList(),
    val inputModalities: List<String> = emptyList()
)

@Immutable
@Serializable
data class ProviderProfile(
    val id: String,
    val name: String,
    val protocol: ProviderProtocol,
    val enabled: Boolean = true,
    val baseUrl: String = protocol.defaultBaseUrl,
    val endpointPathOverride: String = "",
    val useExactEndpointUrl: Boolean = false,
    val apiKey: String = "",
    val authHeaderMode: AuthHeaderMode = AuthHeaderMode.NATIVE,
    val customHeadersRaw: String = "",
    val defaultModel: String = "",
    val savedModels: List<String> = emptyList(),
    val autoFetchModels: Boolean = true,
    val timeoutMinutes: Int = 60,
    val useStatefulChaining: Boolean = true,
    val useBackgroundLongDecode: Boolean = true,
    val useLegacyFallbackEndpoint: Boolean = true,
    val enableWebSearch: Boolean = false,
    val enableCodeExecution: Boolean = false,
    val enableUrlContext: Boolean = false,
    val enableImageGeneration: Boolean = false,
    val enableVideoGeneration: Boolean = false,
    val allowShellExecution: Boolean = false,
    val mediaAspectRatio: String = "1:1",
    val mediaResolution: String = "1K",
    val videoDurationSeconds: Int = 5,
    val videoGenerateAudio: Boolean = true,
    val audioVoice: String = "alloy",
    val audioSpeed: Double = 1.0,
    val thinkingEffort: String = "high",
    // Nullable numeric parameters: null = omitted/stripped from outgoing JSON; 0 = unlimited where applicable
    val thinkingBudgetTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val contextWindowTokens: Int = 128000,
    val inputPricePerM: Double = 0.0,
    val outputPricePerM: Double = 0.0,
    val cacheReadPricePerM: Double = 0.0,
    val cacheWritePricePerM: Double = 0.0,
    val reasoningPricePerM: Double = 0.0,
    val customModelSpecs: Map<String, ModelPricingSpec> = emptyMap(),
    val anthropicBetaHeaders: String = "",
    val rawJsonExtraBody: String = ""
) {
    fun calculateTurnCostUsd(metrics: TokenMetrics): Double {
        val uncachedInput = (metrics.inputTokens - metrics.cachedInputTokens).coerceAtLeast(0)
        val inCost = (uncachedInput / 1_000_000.0) * inputPricePerM
        val cacheCost = (metrics.cachedInputTokens / 1_000_000.0) * cacheReadPricePerM
        val effReasoningPrice = if (reasoningPricePerM > 0.0) reasoningPricePerM else outputPricePerM
        val thinkCost = (metrics.reasoningTokens / 1_000_000.0) * effReasoningPrice
        val outCost = (metrics.outputTokens / 1_000_000.0) * outputPricePerM
        return inCost + cacheCost + thinkCost + outCost
    }
}

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

@Immutable
@Serializable
data class ToolCallRecord(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val resultText: String = "",
    val isError: Boolean = false,
    val isRunning: Boolean = false,
    val durationMs: Long = 0L,
    val thoughtSignature: String = "",
    val precedingThinking: String = ""
)

@Immutable
@Serializable
data class TodoItem(
    val id: String,
    val content: String,
    val status: String = "pending" // "pending", "in_progress", "completed", "cancelled"
)

@Immutable
@Serializable
data class GoalTrackerState(
    val goalObjective: String = "",
    val todos: List<TodoItem> = emptyList(),
    val isCompleted: Boolean = false,
    val verificationSummary: String = ""
) {
    val completedCount: Int
        get() = todos.count { it.status.equals("completed", ignoreCase = true) }

    val hasPendingOrInProgress: Boolean
        get() = !isCompleted && (todos.isEmpty() && goalObjective.isNotBlank() ||
            todos.any {
                val s = it.status.lowercase()
                s == "pending" || s == "in_progress"
            })
}

@Immutable
@Serializable
data class TokenMetrics(
    val inputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val outputTokens: Int = 0,
    val ttftMs: Long = 0L,
    val durationMs: Long = 0L,
    val tokensPerSec: Double = 0.0,
    val estimatedCostUsd: Double = 0.0
) {
    val totalTokens: Int
        get() = inputTokens + reasoningTokens + outputTokens
}

@Immutable
@Serializable
data class PromptAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val base64Data: String = "",
    val textContent: String = "",
    val localFilePath: String = ""
)

@Immutable
@Serializable
data class WebSourceCitation(
    val index: Int = 1,
    val url: String,
    val title: String = "",
    val domain: String = "",
    val groundedLineNumbers: List<Int> = emptyList(),
    val groundedLines: List<String> = emptyList(),
    val snippet: String = ""
)

@Immutable
@Serializable
data class MessageVariant(
    val id: String,
    val content: String,
    val thinkingSummary: String = "",
    val trailingThinking: String = "",
    val thinkingSignature: String = "",
    val thinkingDurationMs: Long = 0L,
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val goalTracker: GoalTrackerState? = null,
    val mediaItems: List<GeneratedMediaItem> = emptyList(),
    val attachments: List<PromptAttachment> = emptyList(),
    val webSources: List<WebSourceCitation> = emptyList(),
    val providerInteractionId: String = "",
    val pendingBackgroundInteractionId: String = "",
    val isStreaming: Boolean = false,
    val providerProtocol: ProviderProtocol? = null,
    val modelUsed: String = "",
    val createdAt: Long = 0L,
    val tokenMetrics: TokenMetrics = TokenMetrics(),
    val rawRequestPreview: String = "",
    val rawResponsePreview: String = ""
)

@Immutable
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
    MANUSCRIPT("Manuscript"),
    EDITORIAL_TURNS("Turns")
}

@Serializable
enum class WorkspaceMode(val label: String) {
    OFF("Workspace: Off"),
    READ_ONLY("Workspace: Read-Only"),
    READ_WRITE("Workspace: Agent R/W")
}

@Serializable
enum class FragmentActivationMode(val label: String) {
    PINNED("Pinned (Always In Context)"),
    KEYWORD("Keyword-Triggered"),
    MUTED("Off")
}

@Immutable
@Serializable
data class ContextFragment(
    val id: String,
    val title: String,
    val content: String,
    val keywords: List<String> = emptyList(),
    val mode: FragmentActivationMode = FragmentActivationMode.PINNED
)

@Immutable
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
    val embeddedFragments: List<ContextFragment> = emptyList(),
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

@Immutable
@Serializable
data class SystemPromptPreset(
    val id: String,
    val name: String,
    val description: String = "",
    val systemPrompt: String,
    val authorsNoteDefault: String = "",
    val suggestedTemperature: Double? = null,
    val suggestedTopP: Double? = null,
    val suggestedThinkingEffort: String = "high"
)

/**
 * Parchment Persistent Assistant / Agent Profile.
 * Lives independently of Topics (ChatSessions), so deleting a Topic never deletes your System Prompt or Agent setup.
 */
@Immutable
@Serializable
data class AssistantAgent(
    val id: String,
    val name: String,
    val iconEmoji: String = "💬",
    val systemPrompt: String = "",
    val authorsNote: String = "",
    val authorsNoteDepth: Int = 2,
    val defaultProfileId: String = "", // Blank = follow Global Model
    val defaultModelId: String = "",   // Blank = follow Global Model
    val thinkingEffort: String = "",   // Blank = follow Profile/Global
    val temperature: Double? = null,   // null = omit from API request
    val topP: Double? = null,          // null = omit from API request
    val maxOutputTokens: Int? = null,  // null = omit; 0 = unlimited
    val contextMessageLimit: Int = 0,  // 0 = unlimited history messages
    val activeCard: CharacterOrScenarioCard? = null,
    val fragments: List<ContextFragment> = emptyList(),
    val workspacePath: String = "",
    val workspaceMode: WorkspaceMode = WorkspaceMode.OFF,
    val enableWebSearch: Boolean = false
)

/**
 * Parchment Global & Task-Specific Default Models Configuration.
 */
@Immutable
@Serializable
data class DefaultModelConfig(
    val globalProfileId: String = "",
    val globalModelId: String = "",
    val titleFollowGlobal: Boolean = true,
    val titleProfileId: String = "",
    val titleModelId: String = "",
    val titlePromptTemplate: String = "Generate a concise, natural 3 to 6 word title summarizing this conversation. Return ONLY the title text without quotes or trailing punctuation.",
    val summaryFollowGlobal: Boolean = true,
    val summaryProfileId: String = "",
    val summaryModelId: String = "",
    val translationFollowGlobal: Boolean = true,
    val translationProfileId: String = "",
    val translationModelId: String = "",
    val translationTargetLanguage: String = "English"
)

@Serializable
enum class MessageNavVisibility(val label: String) {
    ALWAYS("Always show"),
    WHILE_SCROLLING("Show while scrolling"),
    HIDDEN("Hidden")
}

@Serializable
enum class SendShortcutMode(val label: String) {
    ENTER("Enter"),
    CTRL_ENTER("Ctrl + Enter"),
    SHIFT_ENTER("Shift + Enter")
}

/**
 * Parchment Granular Display, Rendering, and Behavior Preferences.
 */
@Immutable
@Serializable
data class PreferencesConfig(
    // Chat item display
    val defaultViewMode: ViewMode = ViewMode.EDITORIAL_TURNS,
    val showProviderInModelCapsule: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showUserName: Boolean = true,
    val showUserTimestamp: Boolean = true,
    val showUserMessageActions: Boolean = true,
    val showModelIcon: Boolean = true,
    val showAssistantAvatarInTitleBar: Boolean = true,
    val showModelName: Boolean = true,
    val showModelTimestamp: Boolean = true,
    val showProviderAfterModelName: Boolean = true,
    val showTokenAndContextStats: Boolean = true,
    val showTokensForEntireTurn: Boolean = false,
    val showThinkingCards: Boolean = true,
    val showToolCards: Boolean = true,
    val showFilesBelowReplies: Boolean = true,
    val showReasoningLevelOnButton: Boolean = true,
    // Rendering settings
    val inlineDollarMathRendering: Boolean = true,
    val mathFormulaRendering: Boolean = true,
    val renderUserMessagesWithMarkdown: Boolean = true,
    val renderReasoningWithMarkdown: Boolean = true,
    val renderAssistantMessagesWithMarkdown: Boolean = true,
    val autoCollapseCodeBlocks: Boolean = false,
    // Behavior & startup
    val autoSwitchToTopics: Boolean = true,
    val autoCollapseThinking: Boolean = true,
    val collapseThinkingSteps: Boolean = false,
    val showToolResultSummary: Boolean = true,
    val collapseLongMessages: Boolean = false,
    val deleteMessagesBelowWhenRegenerating: Boolean = true,
    val deletePreviousMessageWhenRegenerating: Boolean = false,
    val confirmBeforeRegenerating: Boolean = false,
    val confirmBeforeDeletingChat: Boolean = true,
    val keepMessageVersionsWhenForking: Boolean = true,
    val showChatListDates: Boolean = true,
    val newChatWhenSwitchingAssistants: Boolean = false,
    val newChatAfterDeletingTopic: Boolean = true,
    val newChatOnLaunch: Boolean = false,
    val messageNavButtons: MessageNavVisibility = MessageNavVisibility.WHILE_SCROLLING,
    val sendShortcut: SendShortcutMode = SendShortcutMode.ENTER,
    val pasteLongTextAsFile: Boolean = false,
    val pasteConversionThresholdChars: Int = 5000,
    val autoScrollToBottomOnOpen: Boolean = true,
    val autoFollowBottom: Boolean = true,
    val autoCompactWhenNearContextLimit: Boolean = true,
    val chatBackgroundOverlayOpacityPercent: Int = 100,
    val inputBoxBackgroundOpacityPercent: Int = 95
)

@Immutable
@Serializable
data class DesktopWindowConfig(
    val isMaximized: Boolean = true,
    val widthDp: Int = 1400,
    val heightDp: Int = 900,
    val xDp: Int = -1,
    val yDp: Int = -1
)

@Immutable
@Serializable
data class QueuedPromptItem(
    val id: String,
    val text: String,
    val prefill: String = "",
    val isGuidedDirection: Boolean = false,
    val attachments: List<PromptAttachment> = emptyList()
)

@Immutable
@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val profileId: String,
    val modelOverride: String = "",
    val assistantId: String = "",
    val isPinned: Boolean = false,
    val isTitleManuallySet: Boolean = false,
    val draftInput: String = "",
    val draftPrefill: String = "",
    val userName: String = "User",
    val systemPrompt: String = "",
    val activeCard: CharacterOrScenarioCard? = null,
    val storySummary: String = "",
    val fragments: List<ContextFragment> = emptyList(),
    val authorsNote: String = "",
    val authorsNoteDepth: Int = 2,
    val viewMode: ViewMode = ViewMode.EDITORIAL_TURNS,
    val workspacePath: String = "",
    val workspaceMode: WorkspaceMode = WorkspaceMode.OFF,
    val messages: List<MessageNode> = emptyList()
)

@Immutable
@Serializable
data class ThemeConfig(
    val name: String = "Parchment & Espresso Ink (Sepia)",
    val colorMode: String = "Sepia", // Sepia, Light, Dark
    val canvasBgHex: String = "#F4ECD8",
    val sheetBgHex: String = "#EBE0C5",
    val cardBgHex: String = "#FAF6EB",
    val elevatedBgHex: String = "#EFE5CC",
    val borderHex: String = "#D5C4A1",
    val primaryInkHex: String = "#261C14",
    val dialogueInkHex: String = "#4A2511",
    val narrationInkHex: String = "#5C4938",
    val mutedInkHex: String = "#826F5C",
    val accentHex: String = "#8B5A2B",
    val accentSecondaryHex: String = "#B57C2E",
    val dangerHex: String = "#A62B2B",
    val proseFontFamily: String = "Inter",
    val customProseFontPath: String = "",
    val codeFontFamily: String = "JetBrainsMono Nerd",
    val customCodeFontPath: String = "",
    val proseFontSizeSp: Float = 16.5f,
    val codeFontSizeSp: Float = 12.5f,
    val proseLineHeightMultiplier: Float = 1.54f,
    val paragraphSpacingDp: Float = 18f,
    val firstLineIndentChars: Int = 0,
    val maxReadingWidthDp: Float = 820f,
    val highlightDialogueAndNarration: Boolean = true
) {
    companion object {
        val PRESETS: List<ThemeConfig> = listOf(
            ThemeConfig(),
            ThemeConfig(
                name = "Parchment Warm Ivory (Light)",
                colorMode = "Light",
                canvasBgHex = "#F8F7F4",
                sheetBgHex = "#EFECE6",
                cardBgHex = "#FFFFFF",
                elevatedBgHex = "#F3F0EA",
                borderHex = "#E2DDD5",
                primaryInkHex = "#1E1B18",
                dialogueInkHex = "#4A2E1B",
                narrationInkHex = "#5A524C",
                mutedInkHex = "#8A8178",
                accentHex = "#7A5216",
                accentSecondaryHex = "#9E6B24"
            ),
            ThemeConfig(
                name = "Aged Oxford Manuscript (Warm Deep Sepia)",
                colorMode = "Sepia",
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
                colorMode = "Dark",
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
                name = "Midnight OLED Ink (AMOLED Dark)",
                colorMode = "Dark",
                canvasBgHex = "#0A0908",
                sheetBgHex = "#12100E",
                cardBgHex = "#181512",
                elevatedBgHex = "#221E1A",
                borderHex = "#2E2822",
                primaryInkHex = "#E5DDD0",
                dialogueInkHex = "#EABFA0",
                narrationInkHex = "#B3A48E",
                mutedInkHex = "#7A6D5B",
                accentHex = "#C97A3E",
                accentSecondaryHex = "#C49142"
            ),
            ThemeConfig(
                name = "Paper & Graphite (Modern Editorial Light)",
                colorMode = "Light",
                canvasBgHex = "#F7F7F5",
                sheetBgHex = "#EFEFED",
                cardBgHex = "#FFFFFF",
                elevatedBgHex = "#F2F2EF",
                borderHex = "#E4E4E0",
                primaryInkHex = "#18181B",
                dialogueInkHex = "#27272A",
                narrationInkHex = "#52525B",
                mutedInkHex = "#71717A",
                accentHex = "#4F46E5",
                accentSecondaryHex = "#0D9488"
            ),
            ThemeConfig(
                name = "Linear Carbon (Modern Editorial Dark)",
                colorMode = "Dark",
                canvasBgHex = "#0E0F12",
                sheetBgHex = "#131519",
                cardBgHex = "#17191E",
                elevatedBgHex = "#1F2229",
                borderHex = "#2A2E37",
                primaryInkHex = "#ECECEE",
                dialogueInkHex = "#D4D8F0",
                narrationInkHex = "#A0A6B2",
                mutedInkHex = "#6E7480",
                accentHex = "#5E6AD2",
                accentSecondaryHex = "#38BDF8"
            )
        )
    }
}

@Immutable
@Serializable
data class AppStateSnapshot(
    val profiles: List<ProviderProfile> = emptyList(),
    val activeProfileId: String = "",
    val assistants: List<AssistantAgent> = emptyList(),
    val activeAssistantId: String = "",
    val defaultModels: DefaultModelConfig = DefaultModelConfig(),
    val preferences: PreferencesConfig = PreferencesConfig(),
    val desktopWindow: DesktopWindowConfig = DesktopWindowConfig(),
    val sessions: List<ChatSession> = emptyList(),
    val activeSessionId: String = "",
    val openTabSessionIds: List<String> = emptyList(),
    val systemPromptPresets: List<SystemPromptPreset> = emptyList(),
    val savedCards: List<CharacterOrScenarioCard> = emptyList(),
    val globalWorldBook: List<ContextFragment> = emptyList(),
    val theme: ThemeConfig = ThemeConfig(),
    val otaGithubRepo: String = "baros4294/parchment",
    val autoCheckOtaOnStartup: Boolean = true
)

enum class StorageFileCategory(val label: String) {
    CHAT_SESSION("Chat"),
    UPLOADED_MEDIA("Upload"),
    GENERATED_MEDIA("AI Media"),
    SETTINGS_AND_BACKUPS("Settings/Backup"),
    OTA_AND_TEMP("Temp/Cache")
}

@Immutable
data class StoredAppFileItem(
    val fullPath: String,
    val relativePath: String,
    val fileName: String,
    val category: StorageFileCategory,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)
