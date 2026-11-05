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

internal data class LiveAgentPeekInfo(
    val isToolPeek: Boolean,
    val title: String,
    val body: String,
    val toolName: String = "",
    val isToolRunning: Boolean = false
)

/**
 * Extracts either the active/latest Tool Peek (`isToolPeek = true`) or the latest Thought Peek (`isToolPeek = false`)
 * when the user collapses the full Reasoning & Tool Timeline into Compact Peek mode while the agent is working.
 */
internal fun extractLatestAgentPeek(variant: MessageVariant): LiveAgentPeekInfo {
    val tools = variant.toolCalls
    val runningToolIdx = tools.indexOfLast { it.isRunning }
    val showToolPeek = runningToolIdx >= 0 || (tools.isNotEmpty() && variant.trailingThinking.isBlank())
    if (showToolPeek && tools.isNotEmpty()) {
        val targetIdx = if (runningToolIdx >= 0) runningToolIdx else tools.lastIndex
        val tc = tools[targetIdx]
        val intent = io.parchment.ai.harness.WorkspaceHarness.describeToolCallIntent(tc.name, tc.argumentsJson)
        val intentLines = intent.lines()
        val headline = "Tool #${targetIdx + 1}/${tools.size} • ${intentLines.firstOrNull().orEmpty()}"
        val bodyText = buildString {
            if (intentLines.size > 1) {
                appendLine(intentLines.drop(1).joinToString("\n"))
            }
            val cleanRes = tc.resultText.trim()
            if (cleanRes.isNotEmpty() && cleanRes != "Running ${tc.name}…" && cleanRes != "Running shell command…") {
                val tailLines = cleanRes.lines().takeLast(4).joinToString("\n")
                append(tailLines)
            }
        }.trim()
        return LiveAgentPeekInfo(
            isToolPeek = true,
            title = headline,
            body = bodyText,
            toolName = tc.name,
            isToolRunning = tc.isRunning
        )
    }

    val (thoughtTitle, thoughtBody) = extractLatestThoughtPeek(variant)
    return LiveAgentPeekInfo(
        isToolPeek = false,
        title = thoughtTitle,
        body = thoughtBody
    )
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

internal fun saveSettingsOnlyToDisk(snapshot: AppStateSnapshot) {
    val settingsSnapshot = snapshot.copy(sessions = emptyList())
    val encoded = compactStateJson.encodeToString(AppStateSnapshot.serializer(), settingsSnapshot)
    PlatformBridge.saveAppStateJson(encoded)
}

internal fun saveSingleChatSessionToDisk(session: ChatSession) {
    val cleanSession = session.copy(
        messages = session.messages.map { node ->
            node.copy(
                variants = node.variants.map { v ->
                    val hasRunningTools = v.toolCalls.any { it.isRunning }
                    if (v.isStreaming || hasRunningTools) {
                        v.copy(
                            isStreaming = false,
                            toolCalls = if (hasRunningTools) v.toolCalls.map { if (it.isRunning) it.copy(isRunning = false) else it } else v.toolCalls
                        )
                    } else v
                }
            )
        }
    )
    val encoded = compactStateJson.encodeToString(ChatSession.serializer(), cleanSession)
    PlatformBridge.saveChatSessionJson(cleanSession.id, encoded)
}

internal fun saveSplitAppStateToDisk(snapshot: AppStateSnapshot) {
    saveSettingsOnlyToDisk(snapshot)
    for (sess in snapshot.sessions) {
        saveSingleChatSessionToDisk(sess)
    }
}

internal fun decodeChatSessionsFromRawJson(rawJsonText: String): List<ChatSession> {
    val trimmed = rawJsonText.trim()
    if (trimmed.isEmpty()) return emptyList()
    // 1. Single ChatSession JSON object
    runCatching {
        val elem = compactStateJson.parseToJsonElement(trimmed)
        if (elem is JsonObject) {
            if (elem.containsKey("messages") && elem.containsKey("title") && !elem.containsKey("profiles")) {
                return listOf(compactStateJson.decodeFromJsonElement(ChatSession.serializer(), elem))
            }
            if (elem.containsKey("sessions")) {
                val arr = elem["sessions"] as? JsonArray
                if (arr != null) {
                    return arr.mapNotNull { item ->
                        runCatching { compactStateJson.decodeFromJsonElement(ChatSession.serializer(), item) }.getOrNull()
                    }
                }
            }
        } else if (elem is JsonArray) {
            return elem.mapNotNull { item ->
                runCatching { compactStateJson.decodeFromJsonElement(ChatSession.serializer(), item) }.getOrNull()
            }
        }
    }
    return emptyList()
}

internal fun buildExportFileNameForChat(session: ChatSession): String {
    val safeTitle = session.title.trim()
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(42)
        .ifBlank { "chat" }
    val shortId = session.id.takeLast(8).replace(Regex("[^a-zA-Z0-9_-]"), "")
    return if (shortId.isNotEmpty()) "$safeTitle-$shortId.chat.json" else "$safeTitle.chat.json"
}

internal fun loadOrCreateDefaultAppState(): AppStateSnapshot {
    val rawSettings = PlatformBridge.loadAppStateJson()
    val rawChatFiles = PlatformBridge.loadAllChatSessionJsons()
    val diskSessions = rawChatFiles.mapNotNull { rawChat ->
        runCatching { compactStateJson.decodeFromString<ChatSession>(rawChat) }.getOrNull()
    }

    if (!rawSettings.isNullOrBlank()) {
        val baseLoaded = runCatching {
            compactStateJson.decodeFromString<AppStateSnapshot>(rawSettings)
        }.getOrElse {
            runCatching { recoverAppStateFromPartialJson(rawSettings) }.getOrNull()
        }

        if (baseLoaded != null) {
            // Merge sessions from chats/*.json with any legacy embedded sessions in parchment-state.json
            val mergedById = LinkedHashMap<String, ChatSession>()
            for (ds in diskSessions) {
                if (ds.id.isNotBlank()) mergedById[ds.id] = ds
            }
            var migratedLegacySessions = false
            for (legacySess in baseLoaded.sessions) {
                if (legacySess.id.isBlank()) continue
                val existing = mergedById[legacySess.id]
                if (existing == null || legacySess.updatedAt > existing.updatedAt) {
                    mergedById[legacySess.id] = legacySess
                    saveSingleChatSessionToDisk(legacySess)
                    migratedLegacySessions = true
                }
            }
            val combinedSessions = mergedById.values
                .sortedWith(compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt })
            val sanitized = sanitizeLoadedSnapshot(baseLoaded.copy(sessions = combinedSessions))
            if (migratedLegacySessions || baseLoaded.sessions.isNotEmpty()) {
                // Only rewrite settings (to strip legacy embedded sessions) when migrating from legacy state!
                saveSettingsOnlyToDisk(sanitized)
            } else if (diskSessions.isEmpty()) {
                saveSplitAppStateToDisk(sanitized)
            }
            return sanitized
        }
    }

    if (diskSessions.isNotEmpty()) {
        val fallback = createDefaultAppState()
        val sanitized = sanitizeLoadedSnapshot(fallback.copy(sessions = diskSessions))
        saveSplitAppStateToDisk(sanitized)
        return sanitized
    }

    val fresh = createDefaultAppState()
    saveSplitAppStateToDisk(fresh)
    return fresh
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
                            val hasRunningTools = v.toolCalls.any { it.isRunning }
                            if (v.isStreaming || hasRunningTools) {
                                v.copy(
                                    isStreaming = false,
                                    toolCalls = if (hasRunningTools) v.toolCalls.map { if (it.isRunning) it.copy(isRunning = false) else it } else v.toolCalls
                                )
                            } else v
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
