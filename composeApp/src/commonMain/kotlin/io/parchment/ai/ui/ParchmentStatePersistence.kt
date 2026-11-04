package io.parchment.ai.ui

import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.CharacterOrScenarioCard
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.ContextFragment
import io.parchment.ai.model.DefaultModelConfig
import io.parchment.ai.model.DesktopWindowConfig
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.SystemPromptPreset
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Process-wide supervisor scope so switching apps on Android or recomposing UI
 * never cancels an active generation stream.
 */
internal val appProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

internal val compactStateJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = false
}

enum class SidebarTab {
    ASSISTANTS,
    TOPICS
}

sealed interface DeleteConfirmRequest {
    data class SingleSession(val sessionId: String, val title: String) : DeleteConfirmRequest
    data class ClearAllSessions(val assistantName: String, val count: Int) : DeleteConfirmRequest
    data class ClearCurrentMessages(val title: String) : DeleteConfirmRequest
}

data class RegenerateConfirmRequest(
    val sessionId: String,
    val nodeIndex: Int
)

sealed interface ActiveMediaViewerItem {
    data class ImageViewer(
        val title: String,
        val mimeType: String,
        val localFilePath: String,
        val remoteUrl: String = ""
    ) : ActiveMediaViewerItem

    data class TextOrCodeViewer(
        val title: String,
        val mimeType: String,
        val textContent: String,
        val localFilePath: String = ""
    ) : ActiveMediaViewerItem
}

data class SessionStreamRuntime(
    val sessionId: String,
    val targetNodeId: String = "",
    val isThinkingPhase: Boolean = false,
    val statusText: String = "",
    val interactionId: String = "",
    val job: Job? = null
)

data class SlashCommandSpec(
    val command: String,
    val label: String,
    val description: String,
    val takesArgument: Boolean
)

internal val BUILTIN_SLASH_COMMANDS = listOf(
    SlashCommandSpec("/goal", "🎯 /goal", "Autonomous multi-step goal & verification checklist", takesArgument = true),
    SlashCommandSpec("/btw", "💬 /btw", "Side-question (auto-muted from context once answered)", takesArgument = true),
    SlashCommandSpec("/compact", "⚡ /compact", "Summarize older turns into Memory & mute them", takesArgument = false),
    SlashCommandSpec("/summarize", "✦ /summarize", "Update rolling Story / Continuity Summary", takesArgument = false),
    SlashCommandSpec("/continue", "→ /continue", "Continue the last Assistant reply seamlessly", takesArgument = false),
    SlashCommandSpec("/rewind", "↺ /rewind", "Remove the last turn pair from this chat", takesArgument = false),
    SlashCommandSpec("/clear", "🗑 /clear", "Clear all messages in the current chat", takesArgument = false)
)

internal val THINKING_EFFORT_PRESETS = listOf(
    "minimal",
    "none",
    "low",
    "medium",
    "high",
    "xhigh",
    "max"
)

internal fun formatDurationFromMs(ms: Long): String {
    val totalTenths = (ms.coerceAtLeast(0L) + 50L) / 100L
    val totalSeconds = totalTenths / 10L
    val dec = totalTenths % 10L
    return when {
        totalSeconds < 60L -> "${totalSeconds}.${dec}s"
        totalSeconds < 3600L -> {
            val mins = totalSeconds / 60L
            val secs = totalSeconds % 60L
            "${mins}m${secs}.${dec}s"
        }
        totalSeconds < 86_400L -> {
            val hrs = totalSeconds / 3600L
            val mins = (totalSeconds % 3600L) / 60L
            "${hrs}h${mins}m"
        }
        else -> {
            val days = totalSeconds / 86_400L
            val hrs = (totalSeconds % 86_400L) / 3600L
            val mins = (totalSeconds % 3600L) / 60L
            "${days}d${hrs}h${mins}m"
        }
    }
}

internal fun formatCompactTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val now = PlatformBridge.currentTimeMillis()
    val diffSec = ((now - epochMs) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "Just now"
        diffSec < 3600L -> "${diffSec / 60L}m ago"
        diffSec < 86_400L -> "${diffSec / 3600L}h ago"
        diffSec < 172_800L -> "Yesterday"
        diffSec < 604_800L -> "${diffSec / 86_400L}d ago"
        else -> "${diffSec / 604_800L}w ago"
    }
}

/**
 * Extracts the latest single thought block (title + body) for the Compact Single-Thought Peek Window
 * when the user presses `Hide ▲` while the model is still actively reasoning.
 */
internal fun extractLatestThoughtPeek(variant: MessageVariant): Pair<String, String> {
    val raw = variant.trailingThinking.ifBlank { variant.thinkingSummary }.trim()
    if (raw.isEmpty()) return "" to ""

    val blocks = raw.split(Regex("(?m)(?=^\\*\\*[^*\\n]+\\*\\*\\s*$|^#{1,4}\\s+)|\\n\\s*\\n+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (blocks.isEmpty()) return "" to raw

    val lastIdx = blocks.lastIndex
    val lastBlock = blocks[lastIdx]
    val lines = lastBlock.lines()
    val firstLine = lines.first().trim()
    val isHeadingLine = (firstLine.startsWith("**") && firstLine.endsWith("**") && firstLine.length > 4) ||
        firstLine.startsWith("#")
    return if (isHeadingLine && lines.size > 1) {
        val cleanTitle = firstLine.removeSurrounding("**").replace(Regex("^#{1,4}\\s+"), "").trim()
        val body = lines.drop(1).joinToString(" ").replace(Regex("\\s+"), " ").trim()
        cleanTitle to body
    } else if (isHeadingLine && lines.size == 1) {
        val cleanTitle = firstLine.removeSurrounding("**").replace(Regex("^#{1,4}\\s+"), "").trim()
        cleanTitle to ""
    } else {
        val prevBlock = if (lastIdx > 0) blocks[lastIdx - 1].trim() else ""
        val prevIsStandaloneHeading = prevBlock.isNotEmpty() && !prevBlock.contains('\n') &&
            ((prevBlock.startsWith("**") && prevBlock.endsWith("**") && prevBlock.length > 4) || prevBlock.startsWith("#"))
        val cleanTitle = if (prevIsStandaloneHeading) {
            prevBlock.removeSurrounding("**").replace(Regex("^#{1,4}\\s+"), "").trim()
        } else ""
        val cleanBody = lastBlock.replace(Regex("\\s+"), " ").trim()
        cleanTitle to cleanBody
    }
}

/**
 * Builds a fresh `ChatSession` for a given `AssistantAgent`, faithfully preserving the Assistant's
 * bound profile, default model, active Character/Scenario Card, opening greeting (with `userName`),
 * and workspace configuration.
 */
internal fun buildFreshSessionForAssistant(
    appState: AppStateSnapshot,
    activeProfile: ProviderProfile,
    assistant: AssistantAgent,
    userName: String = "User",
    now: Long = PlatformBridge.currentTimeMillis()
): ChatSession {
    val profId = assistant.defaultProfileId.ifBlank {
        appState.defaultModels.globalProfileId.ifBlank { activeProfile.id }
    }
    val modId = assistant.defaultModelId.ifBlank {
        appState.defaultModels.globalModelId.ifBlank {
            (appState.profiles.find { it.id == profId } ?: activeProfile).defaultModel
        }
    }
    val card = assistant.activeCard
    val cleanUser = userName.ifBlank { "User" }
    val initialMessages = if (card != null && card.firstMessage.isNotBlank()) {
        val allGreetings = (listOf(card.firstMessage) + card.alternateGreetings).filter { it.isNotBlank() }
        listOf(
            MessageNode(
                id = "node-greeting-$now",
                role = MessageRole.ASSISTANT,
                variants = allGreetings.mapIndexed { idx, g ->
                    MessageVariant(
                        id = "var-greeting-$idx-$now",
                        content = g.replace("{{char}}", card.name, ignoreCase = true)
                            .replace("{{user}}", cleanUser, ignoreCase = true),
                        modelUsed = "Card Greeting #${idx + 1}",
                        createdAt = now
                    )
                }
            )
        )
    } else emptyList()

    return ChatSession(
        id = "sess-$now",
        title = "New Chat",
        createdAt = now,
        updatedAt = now,
        profileId = profId,
        modelOverride = modId,
        assistantId = assistant.id,
        userName = cleanUser,
        activeCard = card,
        viewMode = appState.preferences.defaultViewMode,
        workspacePath = assistant.workspacePath,
        workspaceMode = assistant.workspaceMode,
        messages = initialMessages
    )
}

internal fun loadOrCreateDefaultAppState(): AppStateSnapshot {
    val raw = PlatformBridge.loadAppStateJson()
    if (!raw.isNullOrBlank()) {
        val directResult = runCatching {
            val loaded = compactStateJson.decodeFromString<AppStateSnapshot>(raw)
            sanitizeLoadedSnapshot(loaded)
        }.getOrNull()

        if (directResult != null) {
            return directResult
        }

        val recovered = runCatching {
            recoverAppStateFromPartialJson(raw)
        }.getOrNull()

        if (recovered != null) {
            return sanitizeLoadedSnapshot(recovered)
        }
    }
    return createDefaultAppState()
}

internal fun sanitizeLoadedSnapshot(loaded: AppStateSnapshot): AppStateSnapshot {
    val defaultFallback = createDefaultAppState()
    val safeProfiles = if (loaded.profiles.isNotEmpty()) loaded.profiles else defaultFallback.profiles
    val safeActiveProfileId = safeProfiles.find { it.id == loaded.activeProfileId }?.id ?: safeProfiles.first().id
    val safeActiveProfile = safeProfiles.first { it.id == safeActiveProfileId }

    val safeAssistants = if (loaded.assistants.isNotEmpty()) {
        loaded.assistants
    } else {
        defaultFallback.assistants
    }
    val safeActiveAssistantId = safeAssistants.find { it.id == loaded.activeAssistantId }?.id
        ?: safeAssistants.first().id

    val recoveredSessions = if (loaded.sessions.isNotEmpty()) {
        loaded.sessions.map { sess ->
            sess.copy(
                assistantId = sess.assistantId.ifBlank { safeActiveAssistantId },
                messages = sess.messages.map { node ->
                    node.copy(
                        variants = node.variants.map { v ->
                            if (v.isStreaming) v.copy(isStreaming = false) else v
                        }
                    )
                }
            )
        }
    } else {
        val now = PlatformBridge.currentTimeMillis()
        listOf(
            ChatSession(
                id = "sess-$now",
                title = "New Chat",
                createdAt = now,
                updatedAt = now,
                profileId = safeActiveProfile.id,
                modelOverride = safeActiveProfile.defaultModel,
                assistantId = safeActiveAssistantId
            )
        )
    }

    val safeActiveSessionId = recoveredSessions.find { it.id == loaded.activeSessionId }?.id ?: recoveredSessions.first().id
    val validSessionIdSet = recoveredSessions.map { it.id }.toSet()
    val safeOpenTabs = (loaded.openTabSessionIds.filter { it in validSessionIdSet } + safeActiveSessionId).distinct()

    val migratedTheme = if (loaded.theme.name.endsWith("Warm Ivory (Light)") && !loaded.theme.name.startsWith("Parchment ")) {
        loaded.theme.copy(name = "Parchment Warm Ivory (Light)")
    } else {
        loaded.theme
    }

    return loaded.copy(
        profiles = safeProfiles,
        activeProfileId = safeActiveProfileId,
        assistants = safeAssistants,
        activeAssistantId = safeActiveAssistantId,
        sessions = recoveredSessions,
        activeSessionId = safeActiveSessionId,
        openTabSessionIds = safeOpenTabs,
        theme = migratedTheme
    )
}

internal fun recoverAppStateFromPartialJson(raw: String): AppStateSnapshot {
    val fallback = createDefaultAppState()
    val root = compactStateJson.parseToJsonElement(raw) as? JsonObject ?: return fallback

    fun <T> decodeListItems(key: String, itemDeserializer: kotlinx.serialization.DeserializationStrategy<T>): List<T> {
        val arr = root[key] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { elem ->
            runCatching { compactStateJson.decodeFromJsonElement(itemDeserializer, elem) }.getOrNull()
        }
    }

    val profiles = decodeListItems("profiles", ProviderProfile.serializer()).ifEmpty { fallback.profiles }
    val activeProfileId = root["activeProfileId"]?.jsonPrimitive?.contentOrNull ?: profiles.first().id
    val assistants = decodeListItems("assistants", AssistantAgent.serializer()).ifEmpty { fallback.assistants }
    val activeAssistantId = root["activeAssistantId"]?.jsonPrimitive?.contentOrNull ?: assistants.firstOrNull()?.id.orEmpty()
    val defaultModels = root["defaultModels"]?.let {
        runCatching { compactStateJson.decodeFromJsonElement(DefaultModelConfig.serializer(), it) }.getOrNull()
    } ?: fallback.defaultModels
    val preferences = root["preferences"]?.let {
        runCatching { compactStateJson.decodeFromJsonElement(PreferencesConfig.serializer(), it) }.getOrNull()
    } ?: fallback.preferences
    val desktopWindow = root["desktopWindow"]?.let {
        runCatching { compactStateJson.decodeFromJsonElement(DesktopWindowConfig.serializer(), it) }.getOrNull()
    } ?: fallback.desktopWindow
    val sessions = decodeListItems("sessions", ChatSession.serializer())
    val activeSessionId = root["activeSessionId"]?.jsonPrimitive?.contentOrNull ?: sessions.firstOrNull()?.id.orEmpty()
    val openTabs = (root["openTabSessionIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    val presets = decodeListItems("systemPromptPresets", SystemPromptPreset.serializer())
    val savedCards = decodeListItems("savedCards", CharacterOrScenarioCard.serializer())
    val globalWorldBook = decodeListItems("globalWorldBook", ContextFragment.serializer())
    val theme = root["theme"]?.let {
        runCatching { compactStateJson.decodeFromJsonElement(ThemeConfig.serializer(), it) }.getOrNull()
    } ?: fallback.theme
    val otaRepo = root["otaGithubRepo"]?.jsonPrimitive?.contentOrNull ?: fallback.otaGithubRepo
    val autoOta = root["autoCheckOtaOnStartup"]?.jsonPrimitive?.booleanOrNull ?: fallback.autoCheckOtaOnStartup

    return AppStateSnapshot(
        profiles = profiles,
        activeProfileId = activeProfileId,
        assistants = assistants,
        activeAssistantId = activeAssistantId,
        defaultModels = defaultModels,
        preferences = preferences,
        desktopWindow = desktopWindow,
        sessions = sessions,
        activeSessionId = activeSessionId,
        openTabSessionIds = openTabs,
        systemPromptPresets = presets,
        savedCards = savedCards,
        globalWorldBook = globalWorldBook,
        theme = theme,
        otaGithubRepo = otaRepo,
        autoCheckOtaOnStartup = autoOta
    )
}

internal fun createDefaultAppState(): AppStateSnapshot {
    val now = PlatformBridge.currentTimeMillis()
    val geminiProfile = ProviderProfile(
        id = "prof-gemini-interactions",
        name = "Google Gemini",
        protocol = ProviderProtocol.GEMINI_INTERACTIONS,
        useStatefulChaining = true,
        useBackgroundLongDecode = true
    )
    val openAiProfile = ProviderProfile(
        id = "prof-openai-responses",
        name = "OpenAI",
        protocol = ProviderProtocol.OPENAI_RESPONSES,
        useStatefulChaining = true
    )
    val anthropicProfile = ProviderProfile(
        id = "prof-anthropic-messages",
        name = "Anthropic",
        protocol = ProviderProtocol.ANTHROPIC_MESSAGES
    )
    val byteDanceProfile = ProviderProfile(
        id = "prof-bytedance-ark",
        name = "ByteDance Ark",
        protocol = ProviderProtocol.BYTEDANCE_ARK,
        enableImageGeneration = true
    )

    val defaultAssistant = AssistantAgent(
        id = "asst-default",
        name = "Default Assistant",
        iconEmoji = "💬",
        systemPrompt = ""
    )

    val initialSession = ChatSession(
        id = "sess-initial",
        title = "New Chat",
        createdAt = now,
        updatedAt = now,
        profileId = geminiProfile.id,
        assistantId = defaultAssistant.id
    )

    return AppStateSnapshot(
        profiles = listOf(geminiProfile, openAiProfile, anthropicProfile, byteDanceProfile),
        activeProfileId = geminiProfile.id,
        assistants = listOf(defaultAssistant),
        activeAssistantId = defaultAssistant.id,
        sessions = listOf(initialSession),
        activeSessionId = initialSession.id,
        openTabSessionIds = listOf(initialSession.id),
        theme = ThemeConfig()
    )
}
