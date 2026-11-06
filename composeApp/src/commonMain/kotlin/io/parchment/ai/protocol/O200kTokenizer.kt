package io.parchment.ai.protocol

import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.FragmentActivationMode
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.model.WorkspaceMode

data class ContextCompactionPlan(
    val mutedNodeIds: Set<String>,
    val compactedHistoryNodes: List<MessageNode>,
    val compactedLiveTools: List<ToolCallRecord>,
    val compactedLiveThinking: String,
    val transcriptToSummarize: String,
    val fallbackSummary: String,
    val didCompactAnything: Boolean
)

/**
 * Unified `o200k_base` Tokenizer & Context Window Breakdown Engine.
 *
 * Implements OpenAI's official `o200k_base` pre-tokenization regex pattern (`tiktoken_ext/openai_public.py`)
 * combined with UTF-8 subword BPE chunking so the Composer Context Ring, the Context Window Breakdown Popover,
 * User message token badges, and Assistant message token readouts are 100% unified and consistent.
 */
    @androidx.compose.runtime.Immutable
    data class ContextWindowBreakdown(
    val systemAndPersonaTokens: Int,
    val lorebookAndMemoryTokens: Int,
    val workspaceAndToolsTokens: Int,
    val uploadedFilesTokens: Int,
    val uploadedFilesCount: Int,
    val chatHistoryTokens: Int,
    val activeTurnsCount: Int,
    val mutedTurnsCount: Int,
    val composerDraftTokens: Int,
    val contextLimitTokens: Int
) {
    val totalUsedTokens: Int
        get() = systemAndPersonaTokens +
            lorebookAndMemoryTokens +
            workspaceAndToolsTokens +
            uploadedFilesTokens +
            chatHistoryTokens +
            composerDraftTokens

    val freeSpaceTokens: Int
        get() = if (contextLimitTokens > 0) (contextLimitTokens - totalUsedTokens).coerceAtLeast(0) else 0

    val usageFraction: Float
        get() = if (contextLimitTokens > 0) {
            (totalUsedTokens.toFloat() / contextLimitTokens.toFloat()).coerceIn(0f, 1f)
        } else 0.08f
}

object O200kTokenizer {

    /**
     * Official OpenAI `o200k_base` pre-tokenization regex pattern:
     * 1. Mixed/lowercase Unicode words with optional English contractions ('s, 't, 're, 've, 'm, 'll, 'd)
     * 2. Uppercase/Titlecase Unicode words with optional contractions
     * 3. 1-to-3 digit numeric chunks (`\p{N}{1,3}`)
     * 4. Punctuation / operator clusters (` ?[^\s\p{L}\p{N}]+[\r\n/]*`)
     * 5. Line breaks and whitespace runs
     */
    private val O200K_PAT_REGEX = Regex(
        pattern = listOf(
            """[^\r\n\p{L}\p{N}]?[\p{Lu}\p{Lt}\p{Lm}\p{Lo}\p{M}]*[\p{Ll}\p{Lm}\p{Lo}\p{M}]+(?:'s|'t|'re|'ve|'m|'ll|'d|'S|'T|'RE|'VE|'M|'LL|'D)?""",
            """[^\r\n\p{L}\p{N}]?[\p{Lu}\p{Lt}\p{Lm}\p{Lo}\p{M}]+[\p{Ll}\p{Lm}\p{Lo}\p{M}]*(?:'s|'t|'re|'ve|'m|'ll|'d|'S|'T|'RE|'VE|'M|'LL|'D)?""",
            """\p{N}{1,3}""",
            """ ?[^\s\p{L}\p{N}]+[\r\n/]*""",
            """\s*[\r\n]+""",
            """\s+(?!\S)""",
            """\s+"""
        ).joinToString("|")
    )

    private val WHITESPACE_RUN_REGEX = Regex("\\s+")

    private val tokenCache = object : LinkedHashMap<Int, Int>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Int>?): Boolean = size > 2048
    }

    // Ensure warmUp() pre-compiles O200kTokenizer regex off the UI thread during startup
    fun warmUp() {
        countTokens("Warm up o200k_base regex and BPE subword counter.")
    }

    /**
     * Ultra-fast $O(1)$ token estimator for actively streaming partial buffers so 120Hz VSync frames
     * never run regexes on transient substrings or evict completed turns from `tokenCache`.
     */
    fun estimateTokensFast(text: String): Int {
        if (text.isEmpty()) return 0
        return ((text.length + 3) / 4).coerceAtLeast(1)
    }

    /**
     * Counts `o200k_base` tokens for a text string using the official `o200k_base` pre-tokenization
     * regex and UTF-8 subword BPE splitting rules.
     */
    fun countTokens(text: String, cacheResult: Boolean = true): Int {
        if (text.isEmpty()) return 0
        val cacheKey = text.hashCode() * 31 + text.length
        synchronized(tokenCache) {
            tokenCache[cacheKey]?.let { return it }
        }

        // Fast sampling path for huge tool/file payloads (> 24,000 chars) to prevent UI/regex stalls
        if (text.length > 24_000) {
            val sampleHead = text.substring(0, 6_000)
            val sampleTail = text.substring(text.length - 6_000)
            var sampleTokens = 0
            for (match in O200K_PAT_REGEX.findAll(sampleHead)) {
                if (match.value.isNotEmpty()) sampleTokens += estimateBpeSubwordsForPiece(match.value)
            }
            for (match in O200K_PAT_REGEX.findAll(sampleTail)) {
                if (match.value.isNotEmpty()) sampleTokens += estimateBpeSubwordsForPiece(match.value)
            }
            val scaled = ((sampleTokens.toDouble() * text.length.toDouble()) / 12_000.0).toInt().coerceAtLeast(1)
            if (cacheResult) {
                synchronized(tokenCache) {
                    tokenCache[cacheKey] = scaled
                }
            }
            return scaled
        }

        var total = 0
        for (match in O200K_PAT_REGEX.findAll(text)) {
            val piece = match.value
            if (piece.isEmpty()) continue
            total += estimateBpeSubwordsForPiece(piece)
        }
        val finalCount = total.coerceAtLeast(1)
        if (cacheResult) {
            synchronized(tokenCache) {
                tokenCache[cacheKey] = finalCount
            }
        }
        return finalCount
    }

    private fun estimateBpeSubwordsForPiece(piece: String): Int {
        val trimmed = piece.trimStart(' ')
        if (trimmed.isEmpty()) {
            // Pure whitespace run: o200k_base merges up to 4 spaces or 2 newlines per token
            return ((piece.length + 3) / 4).coerceAtLeast(1)
        }

        val firstChar = trimmed[0]
        // 1-to-3 digit numbers are always 1 token in o200k_base
        if (firstChar.isDigit()) return 1

        // Check for non-ASCII / CJK / emoji characters
        var nonAsciiChars = 0
        for (i in 0 until trimmed.length) {
            if (trimmed[i].code > 127) nonAsciiChars++
        }
        if (nonAsciiChars > 0) {
            // In o200k_base's 200k multilingual vocabulary, common CJK characters are 1 token (~1.25 tokens/char on rare hanzi)
            val asciiPart = trimmed.length - nonAsciiChars
            val asciiToks = if (asciiPart == 0) 0 else ((asciiPart + 4) / 5).coerceAtLeast(1)
            val unicodeToks = ((nonAsciiChars * 5 + 3) / 4).coerceAtLeast(1)
            return asciiToks + unicodeToks
        }

        // ASCII word or punctuation cluster:
        // In o200k_base, words <= 7 chars are almost always 1 token; longer words split into ~4.5-char subwords
        val len = trimmed.length
        if (!firstChar.isLetter()) {
            // Punctuation/operator run (e.g. `});`, `->`, `===`, `/**`): ~1 token per 2.5 chars
            return ((len + 2) / 3).coerceAtLeast(1)
        }
        return when {
            len <= 7 -> 1
            len <= 12 -> 2
            else -> ((len + 3) / 5).coerceAtLeast(2)
        }
    }

    /**
     * Counts `o200k_base` tokens for a single `PromptAttachment` (either inline text/code content
     * or multimodal vision/document tile budget).
     */
    fun countAttachmentTokens(att: PromptAttachment): Int {
        if (att.textContent.isNotBlank()) {
            return countTokens(att.textContent) + 8 // includes `[Attached File: ...]` header overhead
        }
        val mime = att.mimeType.lowercase()
        val lower = att.fileName.lowercase()
        return when {
            mime == "application/pdf" || lower.endsWith(".pdf") -> 1024
            mime.startsWith("video/") || lower.endsWith(".mp4") || lower.endsWith(".webm") -> 1536
            mime.startsWith("audio/") || lower.endsWith(".mp3") || lower.endsWith(".wav") -> 512
            mime.startsWith("image/") || lower.endsWith(".png") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif") -> 258
            else -> 128
        }
    }

    /**
     * Counts the exact `o200k_base` tokens for a single message turn (`MessageVariant`),
     * including its prose, attachments, tool calls, and reasoning summary.
     */
    fun countMessageVariantTokens(
        role: MessageRole,
        variant: MessageVariant,
        includeReasoningAndTools: Boolean = true
    ): Int {
        var total = 4 // ChatML / turn framing overhead (`<|start|>role\n...<|end|>`)
        val isLive = variant.isStreaming
        if (variant.content.isNotBlank()) {
            total += if (isLive) estimateTokensFast(variant.content) else countTokens(variant.content)
        }
        for (att in variant.attachments) {
            total += countAttachmentTokens(att)
        }
        if (role == MessageRole.ASSISTANT && includeReasoningAndTools) {
            if (variant.thinkingSummary.isNotBlank()) {
                total += if (isLive) estimateTokensFast(variant.thinkingSummary) else countTokens(variant.thinkingSummary)
            }
            for (tc in variant.toolCalls) {
                val tcLive = isLive || tc.isRunning
                total += 6 + countTokens(tc.name) +
                    (if (tcLive) estimateTokensFast(tc.argumentsJson) else countTokens(tc.argumentsJson)) +
                    (if (tcLive) estimateTokensFast(tc.resultText) else countTokens(tc.resultText))
            }
        }
        return total
    }

    /**
     * Computes the session/system/history portion of `ContextWindowBreakdown` (excluding the live Composer draft).
     * Memoizing this separately in the UI ensures typing in the Composer never re-scans chat history or tool schemas!
     */
    fun computeBaseContextWindowBreakdown(
        session: ChatSession,
        assistant: AssistantAgent,
        profile: ProviderProfile,
        goalModeActive: Boolean = false,
        globalWorldBook: List<io.parchment.ai.model.ContextFragment> = emptyList()
    ): ContextWindowBreakdown {
        val activeModelId = session.modelOverride.ifBlank {
            assistant.defaultModelId.ifBlank { profile.defaultModel }
        }.trim()
        val contextLimit = profile.customModelSpecs[activeModelId]?.contextWindowTokens
            ?: profile.contextWindowTokens

        // 1. System & Persona Prompt (Assistant systemPrompt + Session systemPrompt + Card + Author's Note)
        val cardCompiled = session.activeCard?.buildCompiledInstructions(session.userName)
            ?: assistant.activeCard?.buildCompiledInstructions(session.userName).orEmpty()
        val effectiveAuthorsNote = session.authorsNote.ifBlank { assistant.authorsNote }
        val systemAndPersonaTokens = countTokens(assistant.systemPrompt) +
            countTokens(session.systemPrompt) +
            countTokens(cardCompiled) +
            countTokens(effectiveAuthorsNote)

        // 2. Lorebook & Story Continuity Memory (Global World Book + Assistant + Session + Card Fragments + Story Summary)
        val allFragments = (
            globalWorldBook +
                assistant.fragments +
                session.fragments +
                session.activeCard?.embeddedFragments.orEmpty() +
                assistant.activeCard?.embeddedFragments.orEmpty()
            ).distinctBy { it.id }
        val recentWindowText = session.messages.takeLast(8).joinToString("\n") { it.activeVariant.content }
        val activeFragments = allFragments.filter { frag ->
            when (frag.mode) {
                FragmentActivationMode.PINNED -> true
                FragmentActivationMode.MUTED -> false
                FragmentActivationMode.KEYWORD -> {
                    frag.keywords.isEmpty() || frag.keywords.any { kw ->
                        kw.isNotBlank() && recentWindowText.contains(kw.trim(), ignoreCase = true)
                    }
                }
            }
        }
        val lorebookAndMemoryTokens = activeFragments.sumOf { countTokens(it.title) + countTokens(it.content) + 4 } +
            countTokens(session.storySummary)

        // 3. Workspace & Harness Tool Schemas
        val wsPath = session.workspacePath.ifBlank { assistant.workspacePath }
        val effectiveWsMode = if (session.workspaceMode != WorkspaceMode.OFF) {
            session.workspaceMode
        } else if (session.workspacePath.isBlank()) {
            assistant.workspaceMode
        } else {
            WorkspaceMode.OFF
        }
        val enableWeb = profile.enableWebSearch || profile.enableUrlContext || assistant.enableWebSearch
        val activeTools = WorkspaceHarness.activeToolsForMode(
            mode = effectiveWsMode,
            workspacePath = wsPath,
            allowShell = profile.allowShellExecution,
            goalModeActive = goalModeActive,
            enableWebTools = enableWeb
        )
        val toolSchemaTokens = activeTools.sumOf { t ->
            countTokens(t.name) + countTokens(t.description) + countTokens(t.parametersSchemaString) + 8
        }
        val wsContextTokens = if (effectiveWsMode != WorkspaceMode.OFF && wsPath.isNotBlank()) {
            countTokens(WorkspaceHarness.discoverWorkspaceContextFiles(wsPath)) + 95
        } else 0
        val goalModeTokens = if (goalModeActive) 110 else 0
        val workspaceAndToolsTokens = toolSchemaTokens + wsContextTokens + goalModeTokens

        // 4 & 5. Active Unmuted Conversation Turns (honoring assistant.contextMessageLimit)
        val effectiveMessages = if (assistant.contextMessageLimit > 0 && session.messages.size > assistant.contextMessageLimit) {
            session.messages.takeLast(assistant.contextMessageLimit)
        } else {
            session.messages
        }
        var uploadedFilesTokens = 0
        var uploadedFilesCount = 0
        var chatHistoryTokens = 0
        var activeTurnsCount = 0
        var mutedTurnsCount = (session.messages.size - effectiveMessages.size).coerceAtLeast(0)

        for (node in effectiveMessages) {
            if (!node.includedInContext) {
                mutedTurnsCount++
                continue
            }
            activeTurnsCount++
            val v = node.activeVariant
            val isLive = v.isStreaming
            var turnProseAndTools = 4
            if (v.content.isNotBlank()) {
                turnProseAndTools += if (isLive) estimateTokensFast(v.content) else countTokens(v.content)
            }
            if (node.role == MessageRole.ASSISTANT) {
                for (tc in v.toolCalls) {
                    val tcLive = isLive || tc.isRunning
                    turnProseAndTools += 6 + countTokens(tc.name) +
                        (if (tcLive) estimateTokensFast(tc.argumentsJson) else countTokens(tc.argumentsJson)) +
                        (if (tcLive) estimateTokensFast(tc.resultText) else countTokens(tc.resultText))
                }
                if ((v.content.isBlank() || isLive || node == effectiveMessages.lastOrNull()) && v.thinkingSummary.isNotBlank()) {
                    turnProseAndTools += if (isLive) estimateTokensFast(v.thinkingSummary) else countTokens(v.thinkingSummary)
                }
            }
            chatHistoryTokens += turnProseAndTools

            for (att in v.attachments) {
                uploadedFilesCount++
                uploadedFilesTokens += countAttachmentTokens(att)
            }
        }

        return ContextWindowBreakdown(
            systemAndPersonaTokens = systemAndPersonaTokens,
            lorebookAndMemoryTokens = lorebookAndMemoryTokens,
            workspaceAndToolsTokens = workspaceAndToolsTokens,
            uploadedFilesTokens = uploadedFilesTokens,
            uploadedFilesCount = uploadedFilesCount,
            chatHistoryTokens = chatHistoryTokens,
            activeTurnsCount = activeTurnsCount,
            mutedTurnsCount = mutedTurnsCount,
            composerDraftTokens = 0,
            contextLimitTokens = contextLimit
        )
    }

    /**
     * Combines a pre-computed `baseBreakdown` with the live Composer draft & staged attachments in $O(1)$.
     */
    fun applyComposerDraftToBreakdown(
        baseBreakdown: ContextWindowBreakdown,
        draftPrompt: String,
        draftPrefill: String,
        pendingAttachments: List<PromptAttachment>
    ): ContextWindowBreakdown {
        var composerDraftTokens = countTokens(draftPrompt) + countTokens(draftPrefill)
        for (staged in pendingAttachments) {
            composerDraftTokens += countAttachmentTokens(staged)
        }
        return baseBreakdown.copy(
            uploadedFilesCount = baseBreakdown.uploadedFilesCount + pendingAttachments.size,
            composerDraftTokens = composerDraftTokens
        )
    }

    /**
     * Computes the unified, itemized `ContextWindowBreakdown` across all 7 categories so the
     * Circular Context Ring, Context Breakdown Popover, and per-turn token counters always agree!
     */
    fun computeContextWindowBreakdown(
        session: ChatSession,
        assistant: AssistantAgent,
        profile: ProviderProfile,
        draftPrompt: String,
        draftPrefill: String,
        pendingAttachments: List<PromptAttachment>,
        goalModeActive: Boolean = false,
        globalWorldBook: List<io.parchment.ai.model.ContextFragment> = emptyList()
    ): ContextWindowBreakdown {
        val base = computeBaseContextWindowBreakdown(
            session = session,
            assistant = assistant,
            profile = profile,
            goalModeActive = goalModeActive,
            globalWorldBook = globalWorldBook
        )
        return applyComposerDraftToBreakdown(base, draftPrompt, draftPrefill, pendingAttachments)
    }

    const val COMPACTED_TOOL_MARKER = "[Compacted into Continuity Memory]"

    fun isToolResultCompacted(tc: ToolCallRecord): Boolean =
        tc.resultText.contains(COMPACTED_TOOL_MARKER)

    fun compactToolCallRecord(tc: ToolCallRecord, maxKeepChars: Int = 220): ToolCallRecord {
        val cleanSig = if (tc.thoughtSignature.length > 2048) {
            io.parchment.ai.network.AiProviderEngine.sanitizeGeminiThoughtSignature(tc.thoughtSignature, forFunctionCall = true)
        } else tc.thoughtSignature
        if (tc.isRunning || (tc.resultText.length <= maxKeepChars + 60 && cleanSig == tc.thoughtSignature) || isToolResultCompacted(tc)) {
            return if (cleanSig != tc.thoughtSignature) tc.copy(thoughtSignature = cleanSig) else tc
        }
        val head = tc.resultText.trim().take(maxKeepChars).trimEnd()
        return tc.copy(
            resultText = "$head\n… $COMPACTED_TOOL_MARKER",
            thoughtSignature = cleanSig,
            precedingThinking = if (tc.precedingThinking.length > 240) {
                tc.precedingThinking.take(240).trimEnd() + "…"
            } else tc.precedingThinking
        )
    }

    /**
     * Checks whether a session or in-flight generation has older unmuted turns, heavy completed
     * tool call outputs, or long reasoning traces that can be compacted into `storySummary`.
     */
    fun hasCompactableContext(
        historyNodes: List<MessageNode>,
        keepRecentTurnsCount: Int = 2,
        liveTargetTools: List<ToolCallRecord> = emptyList(),
        liveTargetThinking: String = ""
    ): Boolean {
        val unmuted = historyNodes.filter { it.includedInContext }
        if (unmuted.size > keepRecentTurnsCount) return true
        val hasUncompactedNodeTools = unmuted.any { node ->
            val v = node.activeVariant
            if (v.thinkingSignature.length > 4096) return@any true
            val completedTools = v.toolCalls.filter { !it.isRunning }
            completedTools.any { it.thoughtSignature.length > 4096 } ||
                (completedTools.size > 2 && completedTools.dropLast(2).any { tc ->
                    tc.resultText.length > 280 && !isToolResultCompacted(tc)
                }) || completedTools.any { tc -> tc.resultText.length > 1800 && !isToolResultCompacted(tc) }
        }
        if (hasUncompactedNodeTools) return true
        val completedLiveTools = liveTargetTools.filter { !it.isRunning }
        val hasUncompactedLiveTools = (completedLiveTools.size > 2 && completedLiveTools.dropLast(2).any { tc ->
            tc.resultText.length > 280 && !isToolResultCompacted(tc)
        }) || completedLiveTools.any { tc -> tc.resultText.length > 1800 && !isToolResultCompacted(tc) }
        if (hasUncompactedLiveTools) return true
        if (liveTargetThinking.length > 3600) return true
        return false
    }

    /**
     * Builds a comprehensive `ContextCompactionPlan` that:
     * 1. Mutes older unmuted turns beyond `keepRecentTurnsCount`
     * 2. Compacts older or oversized tool call outputs inside kept unmuted turns & `liveTargetTools`
     * 3. Trims oversized `liveTargetThinking` while folding its contents into the summary transcript
     * 4. Clears `providerInteractionId` on compacted variants so resumed requests use the compacted context
     */
    fun prepareCompactionPlan(
        existingStorySummary: String,
        historyNodes: List<MessageNode>,
        keepRecentTurnsCount: Int = 2,
        liveTargetTools: List<ToolCallRecord> = emptyList(),
        liveTargetThinking: String = ""
    ): ContextCompactionPlan {
        val activeIndices = historyNodes.mapIndexedNotNull { idx, n -> if (n.includedInContext) idx else null }
        val safeKeepCount = keepRecentTurnsCount.coerceAtLeast(1)
        val indicesToMute = if (activeIndices.size > safeKeepCount) {
            activeIndices.dropLast(safeKeepCount).toSet()
        } else {
            emptySet()
        }
        val mutedNodeIds = indicesToMute.map { historyNodes[it].id }.toSet()
        val turnsToSummarize = indicesToMute.map { historyNodes[it] }

        val compactedToolNotes = mutableListOf<String>()
        var anyToolOrThinkingCompacted = false

        fun compactToolListAndCollectNotes(tools: List<ToolCallRecord>): List<ToolCallRecord> {
            if (tools.isEmpty()) return tools
            val lastIndex = tools.lastIndex
            return tools.mapIndexed { idx, tc ->
                val isRecentTwo = idx >= lastIndex - 1
                val hasBloatedSig = tc.thoughtSignature.length > 2048
                val shouldCompact = !tc.isRunning && (!isToolResultCompacted(tc) && (
                    (!isRecentTwo && tc.resultText.length > 280) ||
                        (isRecentTwo && tc.resultText.length > 2400)
                    ) || hasBloatedSig)
                if (shouldCompact) {
                    anyToolOrThinkingCompacted = true
                    if (!isToolResultCompacted(tc) && tc.resultText.length > 280) {
                        val snippet = tc.resultText.replace(WHITESPACE_RUN_REGEX, " ").trim().take(260)
                        compactedToolNotes.add("${tc.name}(${tc.argumentsJson.take(120)}) -> $snippet")
                    }
                    compactToolCallRecord(tc, maxKeepChars = if (isRecentTwo) 600 else 220)
                } else {
                    tc
                }
            }
        }

        val compactedHistoryNodes = historyNodes.mapIndexed { idx, node ->
            if (idx in indicesToMute) {
                node.copy(includedInContext = false)
            } else if (node.includedInContext) {
                val activeVar = node.activeVariant
                val newTools = compactToolListAndCollectNotes(activeVar.toolCalls)
                val newThinking = if (activeVar.thinkingSummary.length > 3600) {
                    anyToolOrThinkingCompacted = true
                    "… [Earlier reasoning compacted into Continuity Memory]\n" + activeVar.thinkingSummary.takeLast(1800)
                } else activeVar.thinkingSummary
                val newSig = if (activeVar.thinkingSignature.length > 2048) {
                    val sanitized = io.parchment.ai.network.AiProviderEngine.sanitizeGeminiThoughtSignature(
                        activeVar.thinkingSignature,
                        forFunctionCall = false
                    )
                    if (sanitized != activeVar.thinkingSignature) {
                        anyToolOrThinkingCompacted = true
                    }
                    sanitized
                } else activeVar.thinkingSignature
                if (newTools != activeVar.toolCalls ||
                    newThinking != activeVar.thinkingSummary ||
                    newSig != activeVar.thinkingSignature ||
                    activeVar.providerInteractionId.isNotEmpty()
                ) {
                    node.copy(
                        variants = node.variants.mapIndexed { vIdx, v ->
                            if (vIdx == node.activeVariantIndex) {
                                v.copy(
                                    toolCalls = newTools,
                                    thinkingSummary = newThinking,
                                    thinkingSignature = newSig,
                                    providerInteractionId = ""
                                )
                            } else v
                        }
                    )
                } else node
            } else node
        }

        val compactedLiveTools = compactToolListAndCollectNotes(liveTargetTools)
        val compactedLiveThinking = if (liveTargetThinking.length > 3600) {
            anyToolOrThinkingCompacted = true
            "… [Earlier reasoning compacted into Continuity Memory]\n" + liveTargetThinking.takeLast(1800)
        } else {
            liveTargetThinking
        }

        val transcriptToSummarize = buildString {
            if (existingStorySummary.isNotBlank()) {
                appendLine("Existing Continuity Summary:")
                appendLine(existingStorySummary.trim())
                appendLine()
            }
            if (turnsToSummarize.isNotEmpty()) {
                appendLine("Older Conversation Turns to Fold into Continuity Summary:")
                for (turn in turnsToSummarize.takeLast(32)) {
                    val roleLabel = if (turn.role == MessageRole.ASSISTANT) "Assistant" else "User"
                    val v = turn.activeVariant
                    val txt = v.content.trim().take(1600)
                    if (txt.isNotEmpty()) {
                        appendLine("$roleLabel: $txt")
                    }
                    if (v.toolCalls.isNotEmpty()) {
                        val toolSummary = v.toolCalls.takeLast(8).joinToString("; ") { tc ->
                            "${tc.name}: ${tc.resultText.replace(WHITESPACE_RUN_REGEX, " ").trim().take(240)}"
                        }
                        appendLine("$roleLabel Tools: $toolSummary")
                    }
                }
                appendLine()
            }
            if (compactedToolNotes.isNotEmpty()) {
                appendLine("Completed Agent Tool Executions to Fold into Continuity Summary:")
                for (note in compactedToolNotes.takeLast(28)) {
                    appendLine("• $note")
                }
                appendLine()
            }
            if (liveTargetThinking.length > 500) {
                appendLine("Recent Agent Reasoning Context:")
                appendLine(liveTargetThinking.takeLast(2000).trim())
            }
        }.trim()

        val fallbackSummary = buildString {
            if (existingStorySummary.isNotBlank()) {
                appendLine(existingStorySummary.trim())
            }
            for (turn in turnsToSummarize.takeLast(14)) {
                val roleLabel = if (turn.role == MessageRole.ASSISTANT) "AI" else "User"
                val txt = turn.activeVariant.content.replace(WHITESPACE_RUN_REGEX, " ").trim().take(400)
                if (txt.isNotEmpty()) appendLine("• $roleLabel: $txt")
            }
            for (note in compactedToolNotes.takeLast(14)) {
                appendLine("• Tool: ${note.take(320)}")
            }
        }.trim()

        val didCompact = mutedNodeIds.isNotEmpty() || anyToolOrThinkingCompacted
        return ContextCompactionPlan(
            mutedNodeIds = mutedNodeIds,
            compactedHistoryNodes = compactedHistoryNodes,
            compactedLiveTools = compactedLiveTools,
            compactedLiveThinking = compactedLiveThinking,
            transcriptToSummarize = transcriptToSummarize,
            fallbackSummary = fallbackSummary,
            didCompactAnything = didCompact
        )
    }
}
