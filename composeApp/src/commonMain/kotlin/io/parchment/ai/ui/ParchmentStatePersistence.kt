package io.parchment.ai.ui

import io.parchment.ai.harness.settledForDisk
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AgentPermissionOverrides
import io.parchment.ai.model.AgentPermissionResolver
import io.parchment.ai.model.withImportedPermissions
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.CharacterOrScenarioCard
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.ContextFragment
import io.parchment.ai.model.DefaultModelConfig
import io.parchment.ai.model.DesktopWindowConfig
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.mapProtocolToDialect
import io.parchment.ai.model.SystemPromptPreset
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.network.isGeminiServerSideToolSignature
import io.parchment.ai.network.sanitizeGeminiThoughtSignature
import io.parchment.ai.platform.ChatBundleEntry
import io.parchment.ai.platform.ChatImportOutcome
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

internal val appDiskWriter by lazy {
    ParchmentDiskWriter(
        appProcessScope,
        saveSettings = ::saveSettingsOnlyToDisk,
        saveSession = ::saveSingleChatSessionToDisk,
        deleteSession = PlatformBridge::deleteChatSessionFile
    )
}

internal val compactStateJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = false
}

enum class SidebarTab {
    ASSISTANTS,
    TOPICS,
    PINNED
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
    val job: Job? = null,
    val toolApprovalBroker: io.parchment.ai.harness.ToolApprovalBroker? = null,
    internal val identity: AgentRunIdentity? = null,
    internal val phase: AgentRunPhase = AgentRunPhase.RUNNING,
    internal val providerContext: RunProviderContext? = null,
    val actualWireDialect: String = "",
    internal val isPreparingHandoff: Boolean = false
)

data class SlashCommandSpec(
    val command: String,
    val label: String,
    val description: String,
    val takesArgument: Boolean
)

internal val BUILTIN_SLASH_COMMANDS = listOf(
    SlashCommandSpec("/goal", "🎯 /goal", Strings.autonomousMultiStepGoalVerificationChecklist, takesArgument = true),
    SlashCommandSpec("/btw", "💬 /btw", Strings.sideQuestionLeftOutOfThe, takesArgument = true),
    SlashCommandSpec("/compact", "⚡ /compact", Strings.summarizeOlderTurnsIntoMemoryMute, takesArgument = false),
    SlashCommandSpec("/summarize", "✦ /summarize", Strings.updateRollingStoryContinuitySummary, takesArgument = false),
    SlashCommandSpec("/continue", "→ /continue", Strings.continueTheLastAssistantReplySeamlessly, takesArgument = false),
    SlashCommandSpec("/rewind", "↺ /rewind", Strings.removeTheLastTurnPairFrom, takesArgument = false),
    SlashCommandSpec("/clear", "🗑 /clear", Strings.clearAllMessagesInTheCurrent, takesArgument = false)
)

/** Maximum number of simultaneously open chat tabs before MRU eviction kicks in. */
internal const val MAX_OPEN_TABS = 12

/**
 * Normalizes the open-tab strip: de-duplicates, guarantees [activeId] is present,
 * and evicts the least-recently-used tabs beyond [MAX_OPEN_TABS] (active always kept).
 */
internal fun normalizeOpenTabs(tabs: List<String>, activeId: String): List<String> {
    val deduped = tabs.filter { it.isNotBlank() }.distinct()
    val withActive = if (activeId.isBlank() || activeId in deduped) deduped else deduped + activeId
    if (withActive.size <= MAX_OPEN_TABS) return withActive
    val keep = withActive.takeLast(MAX_OPEN_TABS).toMutableList()
    if (activeId.isNotBlank() && activeId !in keep) {
        keep.removeAt(0)
        keep.add(activeId)
    }
    return keep
}

/**
 * One-shot, idempotent migration of legacy typeface defaults (16.5 / 12.5) to the
 * v2.0.53 baseline (14 / 14). Only rewrites values that still equal the OLD defaults,
 * so any deliberate user choice survives untouched.
 */
internal fun migrateThemeTypefaceDefaults(theme: ThemeConfig): ThemeConfig {
    val prose = if (theme.proseFontSizeSp == 16.5f) 14.0f else theme.proseFontSizeSp
    val code = if (theme.codeFontSizeSp == 12.5f) 14.0f else theme.codeFontSizeSp
    return if (prose != theme.proseFontSizeSp || code != theme.codeFontSizeSp) {
        theme.copy(proseFontSizeSp = prose, codeFontSizeSp = code)
    } else {
        theme
    }
}

/**
 * Migrates legacy provider JSON shapes before deserialization:
 *  - `BYTEDANCE_ARK` -> `OPENAI_RESPONSES`
 *  - `useExactEndpointUrl = true` -> `protocol = CUSTOM` (verbatim URL, dialect preserved)
 *  - legacy unsurfaced `useStatefulChaining = true` -> stateless/privacy-first defaults
 *  - settings saved before `providerStorageReset` -> built-in profiles reset to stateless once
 */
private val BUILT_IN_STORAGE_RESET_PROFILES = setOf("prof-gemini-interactions", "prof-openai-responses")

internal fun migrateLegacyProviderJson(raw: String): String {
    val preprocessed = raw.replace("\"BYTEDANCE_ARK\"", "\"OPENAI_RESPONSES\"")
    if (!preprocessed.contains("useExactEndpointUrl") && !preprocessed.contains("\"useStatefulChaining\"")) {
        return preprocessed
    }
    val root = runCatching { compactStateJson.parseToJsonElement(preprocessed) as? JsonObject }.getOrNull()
        ?: return preprocessed
    val profiles = root["profiles"] as? JsonArray ?: return preprocessed
    val resetBuiltInStorage = !root.containsKey("providerStorageReset")
    var mutated = false
    val migratedProfiles = profiles.map { elem ->
        val obj = elem as? JsonObject ?: return@map elem
        val legacyProtocol = obj["protocol"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val exactUrl = obj["useExactEndpointUrl"]?.jsonPrimitive?.booleanOrNull == true
        val hadStoreKey = obj.containsKey("storeServerSide")
        val legacyChain = obj["useStatefulChaining"]?.jsonPrimitive?.booleanOrNull == true
        val stored = obj["storeServerSide"]?.jsonPrimitive?.booleanOrNull == true
        // These profiles once shipped with storage on, which users never chose.
        val resetStorage = resetBuiltInStorage && (legacyChain || stored) &&
            obj["id"]?.jsonPrimitive?.contentOrNull in BUILT_IN_STORAGE_RESET_PROFILES
        val withoutLegacy = JsonObject(
            obj.filterKeys { it != "useExactEndpointUrl" }.mapValues { (_, v) -> v } +
                buildMap {
                    if (exactUrl) {
                        // Keep the concrete dialect so payload shape is unchanged; only URL handling differs.
                        put("protocol", kotlinx.serialization.json.JsonPrimitive("CUSTOM"))
                        if (legacyProtocol.isNotBlank() && !obj.containsKey("customDialect")) {
                            put("customDialect", kotlinx.serialization.json.JsonPrimitive(mapProtocolToDialect(legacyProtocol)))
                        }
                    }
                    if ((legacyChain && !hadStoreKey) || resetStorage) {
                        // Previously an unsurfaced default; reset to privacy-first stateless.
                        put("useStatefulChaining", kotlinx.serialization.json.JsonPrimitive(false))
                        put("storeServerSide", kotlinx.serialization.json.JsonPrimitive(false))
                    }
                }
        )
        if (exactUrl || (legacyChain && !hadStoreKey) || resetStorage) mutated = true
        withoutLegacy
    }
    if (!mutated) return preprocessed
    val rebuilt = JsonObject(root + ("profiles" to JsonArray(migratedProfiles)))
    return compactStateJson.encodeToString(JsonObject.serializer(), rebuilt)
}

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
        diffSec < 60L -> Strings.justNow
        diffSec < 3600L -> "${diffSec / 60L}m ago"
        diffSec < 86_400L -> "${diffSec / 3600L}h ago"
        diffSec < 172_800L -> Strings.yesterday
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
 * when the user collapses the full Thinking and tool steps into Compact Peek mode while the agent is working.
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
        val headline = Strings.tool(targetIdx + 1, tools.size, intentLines.firstOrNull().orEmpty())
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

private val THOUGHT_BLOCK_SPLIT_REGEX = Regex("(?m)(?=^\\*\\*[^*\\n]+\\*\\*\\s*$|^#{1,4}\\s+)|\\n\\s*\\n+")
private val HEADING_HASH_PREFIX_REGEX = Regex("^#{1,4}\\s+")
private val PERSISTENCE_WHITESPACE_REGEX = Regex("\\s+")
private val EXPORT_UNSAFE_CHAR_REGEX = Regex("[^a-zA-Z0-9._-]")
internal const val MAX_TEXT_PREVIEW_BYTES = 4L * 1024L * 1024L
private val MULTIPLE_UNDERSCORES_REGEX = Regex("_+")
private val EXPORT_SHORT_ID_REGEX = Regex("[^a-zA-Z0-9_-]")

/**
 * Extracts the latest single thought block (title + body) for the Compact Single-Thought Peek Window
 * when the user presses `Hide ▲` while the model is still actively reasoning.
 */
internal fun extractLatestThoughtPeek(variant: MessageVariant): Pair<String, String> {
    val raw = variant.trailingThinking.ifBlank { variant.thinkingSummary }.trim()
    if (raw.isEmpty()) return "" to ""

    val blocks = raw.split(THOUGHT_BLOCK_SPLIT_REGEX)
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
        val cleanTitle = firstLine.removeSurrounding("**").replace(HEADING_HASH_PREFIX_REGEX, "").trim()
        val body = lines.drop(1).joinToString(" ").replace(PERSISTENCE_WHITESPACE_REGEX, " ").trim()
        cleanTitle to body
    } else if (isHeadingLine && lines.size == 1) {
        val cleanTitle = firstLine.removeSurrounding("**").replace(HEADING_HASH_PREFIX_REGEX, "").trim()
        cleanTitle to ""
    } else {
        val prevBlock = if (lastIdx > 0) blocks[lastIdx - 1].trim() else ""
        val prevIsStandaloneHeading = prevBlock.isNotEmpty() && !prevBlock.contains('\n') &&
            ((prevBlock.startsWith("**") && prevBlock.endsWith("**") && prevBlock.length > 4) || prevBlock.startsWith("#"))
        val cleanTitle = if (prevIsStandaloneHeading) {
            prevBlock.removeSurrounding("**").replace(HEADING_HASH_PREFIX_REGEX, "").trim()
        } else ""
        val cleanBody = lastBlock.replace(PERSISTENCE_WHITESPACE_REGEX, " ").trim()
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
    userName: String = Strings.user,
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
    val cleanUser = userName.ifBlank { Strings.user }
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
        title = Strings.newChat,
        createdAt = now,
        updatedAt = now,
        profileId = profId,
        modelOverride = modId,
        assistantId = assistant.id,
        userName = cleanUser,
        activeCard = card,
        viewMode = appState.preferences.defaultViewMode,
        permissionOverrides = AgentPermissionOverrides(),
        messages = initialMessages
    )
}

private val EMPTY_STUB_VARIANT = MessageVariant(id = "stub-var", content = "")

/**
 * Strips duplicate in-memory base64 strings from attachments when `localFilePath` is already persisted on disk,
 * automatically relocates any legacy root `media/` files into `chats/<sessionId>/media/`, and caps raw wire previews.
 */
internal fun stripRedundantAttachmentBase64(session: ChatSession): ChatSession {
    var changed = false
    val sessId = session.id
    val cleanedMessages = session.messages.map { node ->
        var nodeChanged = false
        val cleanedVariants = node.variants.map { v ->
            var varAttChanged = false
            val updatedAtts = if (v.attachments.isNotEmpty()) {
                v.attachments.map { att ->
                    val relocatedPath = if (att.localFilePath.isNotBlank() && sessId.isNotBlank()) {
                        PlatformBridge.relocateLegacyMediaToChatFolder(att.localFilePath, sessId)
                    } else att.localFilePath
                    val shouldStripB64 = relocatedPath.isNotBlank() && att.base64Data.isNotBlank() &&
                        PlatformBridge.isReadableLocalFile(relocatedPath)
                    if (relocatedPath != att.localFilePath || shouldStripB64) {
                        varAttChanged = true
                        att.copy(
                            localFilePath = relocatedPath,
                            base64Data = if (shouldStripB64) "" else att.base64Data
                        )
                    } else att
                }
            } else v.attachments

            var varMediaChanged = false
            val updatedMedia = if (v.mediaItems.isNotEmpty() && sessId.isNotBlank()) {
                v.mediaItems.map { m ->
                    val relocatedPath = if (m.localFilePath.isNotBlank()) {
                        PlatformBridge.relocateLegacyMediaToChatFolder(m.localFilePath, sessId)
                    } else m.localFilePath
                    if (relocatedPath != m.localFilePath) {
                        varMediaChanged = true
                        m.copy(localFilePath = relocatedPath)
                    } else m
                }
            } else v.mediaItems

            val rawVarSig = v.thinkingSignature
            val cleanVarSig = if (rawVarSig.length > 32 && rawVarSig.indexOf('=') in 0 until rawVarSig.trimEnd('=').length) {
                io.parchment.ai.network.AiProviderEngine.sanitizeGeminiThoughtSignature(rawVarSig, forFunctionCall = false)
            } else rawVarSig
            var varToolsSigChanged = false
            val updatedTools = if (v.toolCalls.isNotEmpty()) {
                v.toolCalls.map { tc ->
                    val rawTcSig = tc.thoughtSignature
                    val hasGluedTcSig = rawTcSig.length > 32 &&
                        (rawTcSig.indexOf('=') in 0 until rawTcSig.trimEnd('=').length ||
                            io.parchment.ai.network.AiProviderEngine.isGeminiServerSideToolSignature(rawTcSig))
                    if (hasGluedTcSig) {
                        val sanitizedTcSig = io.parchment.ai.network.AiProviderEngine.sanitizeGeminiThoughtSignature(rawTcSig, forFunctionCall = true)
                        if (sanitizedTcSig != rawTcSig) {
                            varToolsSigChanged = true
                            tc.copy(thoughtSignature = sanitizedTcSig)
                        } else tc
                    } else tc
                }
            } else v.toolCalls

            val hasOversizedWire = v.rawRequestPreview.length > 4096 || v.rawResponsePreview.length > 4096
            val varSigChanged = cleanVarSig != rawVarSig
            if (varAttChanged || varMediaChanged || hasOversizedWire || varSigChanged || varToolsSigChanged) {
                nodeChanged = true
                v.copy(
                    attachments = updatedAtts,
                    mediaItems = updatedMedia,
                    thinkingSignature = cleanVarSig,
                    toolCalls = updatedTools,
                    rawRequestPreview = if (v.rawRequestPreview.length > 4096) v.rawRequestPreview.take(4096) else v.rawRequestPreview,
                    rawResponsePreview = if (v.rawResponsePreview.length > 4096) v.rawResponsePreview.take(4096) else v.rawResponsePreview
                )
            } else v
        }
        if (nodeChanged) {
            changed = true
            node.copy(variants = cleanedVariants)
        } else node
    }
    return if (changed) session.copy(messages = cleanedMessages) else session
}

internal fun mergeSessionMetadata(full: ChatSession, metadata: ChatSession): ChatSession = full.copy(
    title = metadata.title,
    isPinned = metadata.isPinned,
    isTitleManuallySet = metadata.isTitleManuallySet,
    updatedAt = maxOf(full.updatedAt, metadata.updatedAt),
    assistantId = metadata.assistantId,
    profileId = metadata.profileId,
    modelOverride = metadata.modelOverride,
    workspacePath = metadata.workspacePath,
    workspaceMode = metadata.workspaceMode,
    permissionOverrides = metadata.permissionOverrides,
    draftInput = metadata.draftInput,
    draftPrefill = metadata.draftPrefill,
    userName = metadata.userName,
    viewMode = metadata.viewMode,
    systemPrompt = metadata.systemPrompt,
    activeCard = metadata.activeCard,
    authorsNoteDepth = metadata.authorsNoteDepth,
    isCompactStub = false
)

/**
 * Converts an inactive `ChatSession` into a compact preview while preserving
 * exact `messages.size` and a 140-char preview of the final message so sidebar badges and tab switchers
 * work identically without retaining full conversation content.
 */
internal fun dehydrateChatSessionToCompactStub(session: ChatSession): ChatSession {
    if (session.isCompactStub || session.id.isBlank() || session.messages.isEmpty()) return session
    val msgCount = session.messages.size
    val lastNode = session.messages.last()
    val lastPreviewText = lastNode.activeVariant.content.take(140)
    // Preserve only localFilePath references so Storage Manager orphan detection knows which media files belong to this chat
    val refAttachments = session.messages
        .asSequence()
        .flatMap { it.variants.asSequence() }
        .flatMap { it.attachments.asSequence() }
        .filter { it.localFilePath.isNotBlank() }
        .map { PromptAttachment(id = "", fileName = "", mimeType = "", localFilePath = it.localFilePath) }
        .distinctBy { it.localFilePath }
        .toList()
    val refMedia = session.messages
        .asSequence()
        .flatMap { it.variants.asSequence() }
        .flatMap { it.mediaItems.asSequence() }
        .filter { it.localFilePath.isNotBlank() }
        .map { GeneratedMediaItem(id = "", kind = io.parchment.ai.model.MediaKind.IMAGE, mimeType = "", localFilePath = it.localFilePath) }
        .distinctBy { it.localFilePath }
        .toList()
    val stubLastNode = MessageNode(
        id = lastNode.id,
        role = lastNode.role,
        variants = listOf(
            MessageVariant(
                id = lastNode.activeVariant.id,
                content = lastPreviewText,
                attachments = refAttachments,
                mediaItems = refMedia
            )
        ),
        pinned = lastNode.pinned
    )
    val stubList = ArrayList<MessageNode>(msgCount)
    for (i in 0 until msgCount - 1) {
        val node = session.messages[i]
        // Pinned replies keep their id and a preview so the all-chats pin list works without loading the chat.
        stubList.add(
            if (node.pinned) {
                MessageNode(
                    id = node.id,
                    role = node.role,
                    variants = listOf(MessageVariant(id = node.activeVariant.id, content = node.activeVariant.content.take(PIN_PREVIEW_CHARS))),
                    pinned = true
                )
            } else {
                // The chat list needs a unique id for each row, and a stub is on screen until the real chat loads.
                MessageNode(id = "stub-node-$i", role = MessageRole.USER, variants = listOf(EMPTY_STUB_VARIANT))
            }
        )
    }
    stubList.add(stubLastNode)
    return session.copy(
        storySummary = "",
        fragments = emptyList(),
        authorsNote = "",
        messages = stubList,
        isCompactStub = true
    )
}

/**
 * Loads the full `ChatSession` from pending writes or `chats/<sessionId>/chat.json` if `session` is
 * a dehydrated stub in RAM.
 */
internal fun hydrateSessionFromDiskIfNeeded(session: ChatSession): ChatSession {
    if (!session.isCompactStub) return session
    val pendingFull = appDiskWriter.pendingFullSession(session.id)
    if (pendingFull != null) {
        return stripRedundantAttachmentBase64(mergeSessionMetadata(pendingFull, session))
    }
    val raw = runCatching { PlatformBridge.loadChatSessionJsonById(session.id) }.getOrNull()
    if (!raw.isNullOrBlank()) {
        val decoded = runCatching { compactStateJson.decodeFromString<ChatSession>(raw) }.getOrNull()
        if (decoded != null && decoded.id == session.id) {
            return stripRedundantAttachmentBase64(mergeSessionMetadata(decoded, session))
        }
    }
    return session
}

/**
 * Keeps the active session (and any streaming sessions) fully loaded in memory while
 * dehydrating inactive historical sessions to compact stubs after saving them to disk if needed.
 */
internal fun ensureActiveSessionHydratedAndDehydrateOthers(
    state: AppStateSnapshot,
    keepHydratedIds: Set<String> = emptySet()
): AppStateSnapshot {
    val activeId = state.activeSessionId
    var anyChanged = false
    val updatedSessions = state.sessions.map { sess ->
        val shouldStayHydrated = sess.id == activeId || sess.id in keepHydratedIds
        if (shouldStayHydrated) {
            if (sess.isCompactStub) {
                hydrateSessionFromDiskIfNeeded(sess).also { if (it !== sess) anyChanged = true }
            } else {
                val stripped = stripRedundantAttachmentBase64(sess)
                if (stripped !== sess) anyChanged = true
                stripped
            }
        } else {
            if (!sess.isCompactStub && sess.messages.isNotEmpty()) {
                anyChanged = true
                dehydrateChatSessionToCompactStub(sess)
            } else {
                sess
            }
        }
    }
    return if (anyChanged) state.copy(sessions = updatedSessions) else state
}

internal fun saveSettingsOnlyToDisk(snapshot: AppStateSnapshot) {
    val settingsSnapshot = snapshot.copy(sessions = emptyList())
    val encoded = compactStateJson.encodeToString(AppStateSnapshot.serializer(), settingsSnapshot)
    PlatformBridge.saveAppStateJson(encoded)
}

/** Applies background I/O results without replacing concurrent edits or resurrecting deleted chats. */
internal fun mergeLoadedSessions(
    current: AppStateSnapshot,
    beforeLoad: AppStateSnapshot,
    loaded: AppStateSnapshot
): AppStateSnapshot {
    val originals = beforeLoad.sessions.associateBy { it.id }
    val results = loaded.sessions.associateBy { it.id }
    val currentIds = current.sessions.map { it.id }.toSet()
    val sessions = current.sessions.map { session ->
        val result = results[session.id] ?: return@map session
        when {
            session.isCompactStub && !result.isCompactStub -> mergeSessionMetadata(result, session)
            session === originals[session.id] -> result
            else -> session
        }
    } + loaded.sessions.filter { it.id !in currentIds && it.id !in originals }
    return current.copy(sessions = sessions)
}

internal fun saveSingleChatSessionToDisk(session: ChatSession) {
    if (session.isCompactStub) {
        // Never overwrite a populated chat file on disk with a dehydrated stub; if metadata changed, merge onto disk copy
        val pendingFull = appDiskWriter.pendingFullSession(session.id)
        val raw = if (pendingFull == null) PlatformBridge.loadChatSessionJsonById(session.id) else null
        val fullDiskSession = pendingFull ?: raw?.let {
            runCatching { compactStateJson.decodeFromString<ChatSession>(it) }.getOrNull()
        }?.takeIf { it.id == session.id }
        check(fullDiskSession != null) { "This chat's full data isn't available, so it wasn't saved." }
        val merged = mergeSessionMetadata(fullDiskSession, session)
        val encoded = compactStateJson.encodeToString(ChatSession.serializer(), merged)
        PlatformBridge.saveChatSessionJson(merged.id, encoded)
        return
    }
    val cleanSession = stripRedundantAttachmentBase64(session).let { stripped ->
        stripped.copy(
            messages = stripped.messages.map { node ->
                node.copy(
                    variants = node.variants.map { v -> v.settledForDisk() }
                )
            }
        )
    }
    val encoded = compactStateJson.encodeToString(ChatSession.serializer(), cleanSession)
    PlatformBridge.saveChatSessionJson(cleanSession.id, encoded)
}

internal fun decodeChatSessionsFromRawJson(rawJsonText: String): List<ChatSession> {
    val trimmed = rawJsonText.trim()
    if (trimmed.isEmpty()) return emptyList()
    // 1. Single ChatSession JSON object
    runCatching {
        val elem = compactStateJson.parseToJsonElement(trimmed)
        if (elem is JsonObject) {
            if (elem.containsKey("id") && elem.containsKey("title") && elem.containsKey("profileId") &&
                !elem.containsKey("profiles") && !elem.containsKey("sessions")) {
                return listOf(compactStateJson.decodeFromJsonElement(ChatSession.serializer(), elem).withImportedPermissions())
            }
            if (elem.containsKey("sessions")) {
                val arr = elem["sessions"] as? JsonArray
                if (arr != null) {
                    return arr.mapNotNull { item ->
                        runCatching { compactStateJson.decodeFromJsonElement(ChatSession.serializer(), item).withImportedPermissions() }.getOrNull()
                    }
                }
            }
        } else if (elem is JsonArray) {
            return elem.mapNotNull { item ->
                runCatching { compactStateJson.decodeFromJsonElement(ChatSession.serializer(), item).withImportedPermissions() }.getOrNull()
            }
        }
    }
    return emptyList()
}

internal fun buildExportFileNameForChat(session: ChatSession): String {
    val safeTitle = session.title.trim()
        .replace(EXPORT_UNSAFE_CHAR_REGEX, "_")
        .replace(MULTIPLE_UNDERSCORES_REGEX, "_")
        .trim('_')
        .take(42)
        .ifBlank { "chat" }
    val shortId = session.id.takeLast(8).replace(EXPORT_SHORT_ID_REGEX, "")
    return if (shortId.isNotEmpty()) "$safeTitle-$shortId.chat.json" else "$safeTitle.chat.json"
}

internal fun buildExportZipFileNameForChat(session: ChatSession): String =
    buildExportFileNameForChat(session).removeSuffix(".json") + ".zip"

internal fun encodeChatSessionJsonForExport(session: ChatSession): ByteArray {
    val full = hydrateSessionFromDiskIfNeeded(session)
    check(!full.isCompactStub) { "This chat's full data isn't available, so it wasn't exported." }
    return io.parchment.ai.protocol.CardAndPromptCodec.prettyJson
        .encodeToString(ChatSession.serializer(), full).encodeToByteArray()
}

/**
 * Bundles one or more `ChatSession`s into a self-contained `.zip` archive where each chat has its own
 * subfolder (`<sessionId>/chat.json` + `<sessionId>/media/<fileName>`) and portable relative media paths.
 */
internal fun encodeChatSessionsToZipBundleBytes(sessions: List<ChatSession>): ByteArray {
    val chats = sessions.map(::chatBundleEntry)
    return PlatformBridge.createZipArchive(
        entries = chats.map { it.jsonPath to it.json },
        fileEntries = chats.flatMap { it.mediaFiles }
    )
}

/** Streams [sessions] to a chosen file, hydrating one chat at a time. Null means the user cancelled. */
internal suspend fun exportChatSessionsBundle(fileName: String, sessions: List<ChatSession>): String? =
    PlatformBridge.exportChatBundle(fileName, sessions.asSequence().map(::chatBundleEntry))

private fun chatBundleEntry(rawSession: ChatSession): ChatBundleEntry {
    val mediaFileEntries = mutableListOf<Pair<String, String>>()
    val prettyJson = io.parchment.ai.protocol.CardAndPromptCodec.prettyJson
    val fullSession = stripRedundantAttachmentBase64(hydrateSessionFromDiskIfNeeded(rawSession))
    check(!fullSession.isCompactStub) { "This chat's full data isn't available, so it wasn't exported." }
    val safeSessId = fullSession.id.trim().replace(EXPORT_UNSAFE_CHAR_REGEX, "_").ifBlank { "session" }
    val portableMessages = fullSession.messages.map { node ->
        node.copy(
            variants = node.variants.map { v ->
                val portableAtts = v.attachments.map { att ->
                    if (att.localFilePath.isNotBlank()) {
                        val mediaName = att.localFilePath.replace('\\', '/').substringAfterLast('/')
                            .replace(EXPORT_UNSAFE_CHAR_REGEX, "_")
                        if (mediaName.isNotEmpty()) {
                            val zipEntryPath = "$safeSessId/media/$mediaName"
                            mediaFileEntries.add(zipEntryPath to att.localFilePath)
                            att.copy(localFilePath = "chats/$zipEntryPath", base64Data = "")
                        } else att
                    } else att
                }
                val portableMedia = v.mediaItems.map { m ->
                    if (m.localFilePath.isNotBlank()) {
                        val mediaName = m.localFilePath.replace('\\', '/').substringAfterLast('/')
                            .replace(EXPORT_UNSAFE_CHAR_REGEX, "_")
                        if (mediaName.isNotEmpty()) {
                            val zipEntryPath = "$safeSessId/media/$mediaName"
                            mediaFileEntries.add(zipEntryPath to m.localFilePath)
                            m.copy(localFilePath = "chats/$zipEntryPath")
                        } else m
                    } else m
                }
                v.copy(
                    isStreaming = false,
                    attachments = portableAtts,
                    mediaItems = portableMedia
                )
            }
        )
    }
    val portableSession = fullSession.copy(messages = portableMessages)
    val chatJsonBytes = prettyJson.encodeToString(ChatSession.serializer(), portableSession).encodeToByteArray()
    return ChatBundleEntry("$safeSessId/chat.json", chatJsonBytes, mediaFileEntries)
}

private fun decodeImportedChats(rawJsons: List<String>): List<ChatSession> =
    rawJsons.flatMap(::decodeChatSessionsFromRawJson).also {
        check(it.isNotEmpty()) { "No valid chats were found." }
    }

/** Lets the user choose chat exports and decodes each one, reporting per-file success or failure. */
internal suspend fun pickAndDecodeChatImports(): List<ChatImportOutcome<List<ChatSession>>> =
    PlatformBridge.pickAndImportChatFiles(::decodeImportedChats).map { outcome ->
        ChatImportOutcome(outcome.fileName, outcome.result.mapCatching { chats -> chats.map(::stripRedundantAttachmentBase64) })
    }

/**
 * Decodes one or more `ChatSession`s from either a `.zip` chat bundle (streaming its `media/` files directly
 * into `chats/<sessionId>/media/` on disk, all-or-nothing) or a `.json` chat file.
 */
internal fun decodeChatSessionsFromPickedFile(
    fileName: String,
    bytes: ByteArray
): List<ChatSession> {
    if (bytes.isEmpty()) return emptyList()
    val isZip = fileName.endsWith(".zip", ignoreCase = true) ||
        (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())
    val sessions = if (isZip) {
        PlatformBridge.extractZipArchiveToChatStorage(bytes, ::decodeImportedChats)
    } else {
        runCatching { bytes.decodeToString() }.getOrNull()?.let(::decodeChatSessionsFromRawJson).orEmpty()
    }
    return sessions.map { stripRedundantAttachmentBase64(it) }
}

/**
 * Start-up loader for the first frame: reads only `parchment-settings.json` and the single active
 * `chats/<activeSessionId>/chat.json` file synchronously before drawing the first frame.
 * Remaining historical chats are hydrated as lightweight stubs in the background on `Dispatchers.Default`.
 */
internal fun loadFastInitialAppState(): Pair<AppStateSnapshot, Boolean> {
    val rawSettings = PlatformBridge.loadAppStateJson()?.let { migrateLegacyProviderJson(it) }
    if (!rawSettings.isNullOrBlank()) {
        val baseLoaded: AppStateSnapshot? = runCatching {
            compactStateJson.decodeFromString<AppStateSnapshot>(rawSettings)
        }.getOrElse {
            runCatching { recoverAppStateFromPartialJson(rawSettings) }.getOrNull()
        }
        if (baseLoaded != null && baseLoaded.sessions.isEmpty()) {
            val activeRaw = PlatformBridge.loadChatSessionJsonById(baseLoaded.activeSessionId)
            val activeSession = activeRaw?.let {
                runCatching { compactStateJson.decodeFromString<ChatSession>(it) }.getOrNull()
            }?.let { stripRedundantAttachmentBase64(it) }
            if (activeSession != null) {
                val sanitized = sanitizeLoadedSnapshot(baseLoaded.copy(sessions = listOf(activeSession)))
                return sanitized to true
            }
        }
    }
    return ensureActiveSessionHydratedAndDehydrateOthers(loadOrCreateDefaultAppState()) to false
}

/**
 * Background worker (`Dispatchers.Default`) that loads remaining `chats/<sessionId>/chat.json` files after Frame 1,
 * converting inactive sessions into compact previews.
 */
internal fun loadRemainingHistoricalSessionsDehydrated(
    currentState: AppStateSnapshot,
    keepHydratedIds: Set<String> = emptySet()
): AppStateSnapshot {
    val activeId = currentState.activeSessionId
    val mergedById = LinkedHashMap<String, ChatSession>()
    for (existing in currentState.sessions) {
        if (existing.id.isNotBlank()) mergedById[existing.id] = existing
    }
    var loadedAny = false
    // One chat at a time: each file's text and full decode are dropped once its stub is kept.
    for (rawChat in PlatformBridge.chatSessionJsons()) {
        loadedAny = true
        val decoded = runCatching { compactStateJson.decodeFromString<ChatSession>(rawChat) }.getOrNull() ?: continue
        if (decoded.id.isBlank()) continue
        val alreadyInState = mergedById[decoded.id]
        if (alreadyInState != null && (decoded.id == activeId || decoded.id in keepHydratedIds)) {
            // Preserve live in-memory state of active / streaming sessions
            continue
        }
        val prepared = if (decoded.id == activeId || decoded.id in keepHydratedIds) {
            stripRedundantAttachmentBase64(decoded)
        } else {
            dehydrateChatSessionToCompactStub(decoded)
        }
        mergedById[decoded.id] = prepared
    }
    if (!loadedAny) return currentState
    val combinedSessions = mergedById.values
        .sortedWith(compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt })
    val validSessionIdSet = combinedSessions.map { it.id }.toSet()
    val safeOpenTabs = (currentState.openTabSessionIds.filter { it in validSessionIdSet } + activeId).distinct()
    return currentState.copy(
        sessions = combinedSessions,
        openTabSessionIds = safeOpenTabs
    )
}

internal fun loadOrCreateDefaultAppState(): AppStateSnapshot {
    val rawSettings = PlatformBridge.loadAppStateJson()?.let { migrateLegacyProviderJson(it) }
    val rawChatFiles = PlatformBridge.loadAllChatSessionJsons()
    val diskSessions = rawChatFiles.mapNotNull { rawChat ->
        runCatching { compactStateJson.decodeFromString<ChatSession>(rawChat) }.getOrNull()
    }

    if (!rawSettings.isNullOrBlank()) {
        val baseLoaded: AppStateSnapshot? = runCatching {
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
            val migratedLegacySessions = mutableListOf<ChatSession>()
            for (legacySess in baseLoaded.sessions) {
                if (legacySess.id.isBlank()) continue
                val existing = mergedById[legacySess.id]
                if (existing == null || legacySess.updatedAt > existing.updatedAt) {
                    mergedById[legacySess.id] = legacySess
                    migratedLegacySessions.add(legacySess)
                }
            }
            val combinedSessions = mergedById.values
                .sortedWith(compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt })
            val sanitized = sanitizeLoadedSnapshot(baseLoaded.copy(sessions = combinedSessions))
            if (migratedLegacySessions.isNotEmpty() || baseLoaded.sessions.isNotEmpty()) {
                // The writer saves full chats before committing settings without embedded history.
                appDiskWriter.enqueue(settings = sanitized, sessions = migratedLegacySessions, urgent = true, settingsRequiresSessions = true)
            } else if (diskSessions.isEmpty()) {
                appDiskWriter.enqueue(settings = sanitized, sessions = sanitized.sessions, urgent = true, settingsRequiresSessions = true)
            }
            return sanitized
        }
    }

    if (diskSessions.isNotEmpty()) {
        val fallback = createDefaultAppState()
        val sanitized = sanitizeLoadedSnapshot(fallback.copy(sessions = diskSessions))
        appDiskWriter.enqueue(settings = sanitized, sessions = sanitized.sessions, urgent = true, settingsRequiresSessions = true)
        return sanitized
    }

    val fresh = createDefaultAppState()
    appDiskWriter.enqueue(settings = fresh, sessions = fresh.sessions, urgent = true, settingsRequiresSessions = true)
    return fresh
}

/** Moves display settings to the page design once. Every choice stays available in Settings. */
private fun applyDesignDefaultsOnce(saved: AppStateSnapshot): AppStateSnapshot {
    if (saved.designDefaultsApplied) return saved
    val builtInProfiles = setOf("prof-gemini-interactions", "prof-openai-responses", "prof-anthropic-messages")
    return saved.copy(
        designDefaultsApplied = true,
        profiles = saved.profiles.map {
            if (it.id in builtInProfiles && it.thinkingEffort == "high") it.copy(thinkingEffort = "none") else it
        },
        preferences = saved.preferences.copy(
            showUserAvatar = false, showUserName = false, showUserTimestamp = false,
            showProviderAfterModelName = false, showAssistantAvatarInTitleBar = false
        )
    )
}

internal fun sanitizeLoadedSnapshot(saved: AppStateSnapshot): AppStateSnapshot {
    val loaded = applyDesignDefaultsOnce(saved)
    val defaultFallback = createDefaultAppState()
    val safeProfiles = (if (loaded.profiles.isNotEmpty()) loaded.profiles else defaultFallback.profiles)
        .map { if (it.allowShellExecution) it.copy(allowShellExecution = false) else it }
    val safeActiveProfileId = safeProfiles.find { it.id == loaded.activeProfileId }?.id ?: safeProfiles.first().id
    val safeActiveProfile = safeProfiles.first { it.id == safeActiveProfileId }

    val safeAssistants = if (loaded.assistants.isNotEmpty()) {
        loaded.assistants.map { assistant ->
            if (assistant.permissionDefaults == null) assistant.copy(permissionDefaults = AgentPermissionResolver.defaults(assistant)) else assistant
        }
    } else {
        defaultFallback.assistants
    }
    val safeActiveAssistantId = safeAssistants.find { it.id == loaded.activeAssistantId }?.id
        ?: safeAssistants.first().id

    val recoveredSessions = if (loaded.sessions.isNotEmpty()) {
        loaded.sessions.map { sess ->
            sess.copy(
                assistantId = sess.assistantId.ifBlank { safeActiveAssistantId },
                permissionOverrides = sess.permissionOverrides ?: AgentPermissionResolver.legacyOverrides(
                    sess, safeProfiles.find { it.id == sess.profileId }
                ),
                messages = sess.messages.map { node ->
                    node.copy(
                        variants = node.variants.map { v -> v.settledForDisk() }
                    )
                }
            )
        }
    } else {
        val now = PlatformBridge.currentTimeMillis()
        listOf(
            ChatSession(
                id = "sess-$now",
                title = Strings.newChat,
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

    val migratedTheme = migrateThemeTypefaceDefaults(
        if (loaded.theme.name.endsWith("Warm Ivory (Light)") && !loaded.theme.name.startsWith("Parchment ")) {
            loaded.theme.copy(name = "Parchment Warm Ivory (Light)")
        } else {
            loaded.theme
        }
    )

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
    val migratedRaw = migrateLegacyProviderJson(raw)
    val root = compactStateJson.parseToJsonElement(migratedRaw) as? JsonObject ?: return fallback

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
    }?.let { migrateThemeTypefaceDefaults(it) } ?: fallback.theme
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
        protocol = ProviderProtocol.GEMINI_INTERACTIONS
    )
    val openAiProfile = ProviderProfile(
        id = "prof-openai-responses",
        name = "OpenAI",
        protocol = ProviderProtocol.OPENAI_RESPONSES
    )
    val anthropicProfile = ProviderProfile(
        id = "prof-anthropic-messages",
        name = "Anthropic",
        protocol = ProviderProtocol.ANTHROPIC_MESSAGES
    )

    val defaultAssistant = AssistantAgent(
        id = "asst-default",
        name = "Default Assistant",
        iconEmoji = "💬",
        systemPrompt = ""
    )

    val initialSession = ChatSession(
        id = "sess-initial",
        title = Strings.newChat,
        createdAt = now,
        updatedAt = now,
        profileId = geminiProfile.id,
        assistantId = defaultAssistant.id
    )

    return AppStateSnapshot(
        profiles = listOf(geminiProfile, openAiProfile, anthropicProfile),
        activeProfileId = geminiProfile.id,
        assistants = listOf(defaultAssistant),
        activeAssistantId = defaultAssistant.id,
        sessions = listOf(initialSession),
        activeSessionId = initialSession.id,
        openTabSessionIds = listOf(initialSession.id),
        theme = ThemeConfig(),
        designDefaultsApplied = true,
        preferences = PreferencesConfig(
            showUserAvatar = false, showUserName = false, showUserTimestamp = false,
            showProviderAfterModelName = false, showAssistantAvatarInTitleBar = false
        )
    )
}
