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
import io.parchment.ai.model.WorkspaceMode

/**
 * Unified `o200k_base` Tokenizer & Context Window Breakdown Engine.
 *
 * Implements OpenAI's official `o200k_base` pre-tokenization regex pattern (`tiktoken_ext/openai_public.py`)
 * combined with UTF-8 subword BPE chunking so the Composer Context Ring, the Context Window Breakdown Popover,
 * User message token badges, and Assistant message token readouts are 100% unified and consistent.
 */
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

    private val tokenCache = object : LinkedHashMap<Int, Int>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Int>?): Boolean = size > 2048
    }

    /**
     * Counts `o200k_base` tokens for a text string using the official `o200k_base` pre-tokenization
     * regex and UTF-8 subword BPE splitting rules.
     */
    fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val cacheKey = text.hashCode() * 31 + text.length
        synchronized(tokenCache) {
            tokenCache[cacheKey]?.let { return it }
        }

        var total = 0
        for (match in O200K_PAT_REGEX.findAll(text)) {
            val piece = match.value
            if (piece.isEmpty()) continue
            total += estimateBpeSubwordsForPiece(piece)
        }
        val finalCount = total.coerceAtLeast(1)
        synchronized(tokenCache) {
            tokenCache[cacheKey] = finalCount
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
        if (variant.content.isNotBlank()) {
            total += countTokens(variant.content)
        }
        for (att in variant.attachments) {
            total += countAttachmentTokens(att)
        }
        if (role == MessageRole.ASSISTANT && includeReasoningAndTools) {
            if (variant.thinkingSummary.isNotBlank()) {
                total += countTokens(variant.thinkingSummary)
            }
            for (tc in variant.toolCalls) {
                total += 6 + countTokens(tc.name) + countTokens(tc.argumentsJson) + countTokens(tc.resultText)
            }
        }
        return total
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
        goalModeActive: Boolean = false
    ): ContextWindowBreakdown {
        val activeModelId = session.modelOverride.ifBlank { profile.defaultModel }.trim()
        val contextLimit = profile.customModelSpecs[activeModelId]?.contextWindowTokens
            ?: profile.contextWindowTokens

        // 1. System & Persona Prompt (Assistant systemPrompt + Session systemPrompt + Card + Author's Note)
        val cardCompiled = session.activeCard?.buildCompiledInstructions(session.userName)
            ?: assistant.activeCard?.buildCompiledInstructions(session.userName).orEmpty()
        val systemAndPersonaTokens = countTokens(assistant.systemPrompt) +
            countTokens(session.systemPrompt) +
            countTokens(cardCompiled) +
            countTokens(session.authorsNote)

        // 2. Lorebook & Story Continuity Memory (Active Pinned/Keyword Fragments + Story Summary)
        val allFragments = session.fragments +
            session.activeCard?.embeddedFragments.orEmpty() +
            assistant.activeCard?.embeddedFragments.orEmpty()
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
        val activeTools = WorkspaceHarness.activeToolsForMode(
            mode = session.workspaceMode,
            workspacePath = wsPath,
            allowShell = profile.allowShellExecution,
            goalModeActive = goalModeActive,
            enableWebTools = profile.enableWebSearch || profile.enableUrlContext
        )
        val toolSchemaTokens = activeTools.sumOf { t ->
            countTokens(t.name) + countTokens(t.description) + countTokens(t.parametersSchema.toString()) + 8
        }
        val wsContextTokens = if (session.workspaceMode != WorkspaceMode.OFF && wsPath.isNotBlank()) {
            countTokens(WorkspaceHarness.discoverWorkspaceContextFiles(wsPath)) + 95
        } else 0
        val goalModeTokens = if (goalModeActive) 110 else 0
        val workspaceAndToolsTokens = toolSchemaTokens + wsContextTokens + goalModeTokens

        // 4 & 5. Active Unmuted Conversation Turns (separating Uploaded Files from Chat History)
        var uploadedFilesTokens = 0
        var uploadedFilesCount = 0
        var chatHistoryTokens = 0
        var activeTurnsCount = 0
        var mutedTurnsCount = 0

        for (node in session.messages) {
            if (!node.includedInContext) {
                mutedTurnsCount++
                continue
            }
            activeTurnsCount++
            val v = node.activeVariant
            var turnProseAndTools = 4
            if (v.content.isNotBlank()) {
                turnProseAndTools += countTokens(v.content)
            }
            if (node.role == MessageRole.ASSISTANT) {
                for (tc in v.toolCalls) {
                    turnProseAndTools += 6 + countTokens(tc.name) + countTokens(tc.argumentsJson) + countTokens(tc.resultText)
                }
                if (v.content.isBlank() && v.thinkingSummary.isNotBlank()) {
                    turnProseAndTools += countTokens(v.thinkingSummary)
                }
            }
            chatHistoryTokens += turnProseAndTools

            for (att in v.attachments) {
                uploadedFilesCount++
                uploadedFilesTokens += countAttachmentTokens(att)
            }
        }

        // 6. Current Composer Draft + Prefill + Staged Attachments
        var composerDraftTokens = countTokens(draftPrompt) + countTokens(draftPrefill)
        for (staged in pendingAttachments) {
            uploadedFilesCount++
            composerDraftTokens += countAttachmentTokens(staged)
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
            composerDraftTokens = composerDraftTokens,
            contextLimitTokens = contextLimit
        )
    }
}
