package io.parchment.ai.network

import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.protocol.isCompleteGeminiSignatureProtobuf
import io.parchment.ai.protocol.isGeminiServerSideToolSignature

// Opaque reasoning signatures and cross-provider reasoning handoff.

// ============================================================================================
// 5. Media and audio: images, video and speech, routed to the right endpoint.
//    Supports:
//    - Universal Image Generation (`POST /v1/images/generations` & `/api/v3/images/generations` with 3-stage payload self-healing)
//    - Universal Video Generation (`POST /contents/generations/tasks`, `/videos/generations`, `/video/generations`, `/videos` + sync/async polling)
//    - Universal Audio Speech / TTS (`POST /v1/audio/speech` & `/audio/speech` with fallback to `/chat/completions`)
//    - Falls back to `/v1/chat/completions` when a proxy offers media generation only through chat.
// ============================================================================================
/**
 * Cross-provider reasoning continuity.
 *
 * Signatures are provider-private receipts and are dropped on replay (see `isForeignProtocol`).
 * The *narrative* of prior reasoning is not a receipt, so it is carried forward as plain text
 * for every historical assistant turn minted by a different protocol — giving the new model
 * the reasoning context without ever handing it an unverifiable blob.
 *
 * Skipped entirely when server-side chaining is active: the provider already holds those steps,
 * so re-injecting them would duplicate context.
 */
internal fun AiProviderEngine.applyCrossProviderReasoningHandoff(
    nodes: List<MessageNode>,
    currentProtocol: ProviderProtocol,
    chainingActive: Boolean
): List<MessageNode> {
    if (chainingActive) return nodes
    var changed = false
    val out = nodes.map { node ->
        if (node.role != MessageRole.ASSISTANT || !node.includedInContext) return@map node
        val variant = node.activeVariant
        val summary = variant.thinkingSummary.trim()
        if (summary.isEmpty()) return@map node
        val bornProtocol = variant.providerProtocol
        if (bornProtocol == currentProtocol) return@map node
        if (variant.content.contains(REASONING_HANDOFF_MARKER)) return@map node
        val handoff = buildString {
            append(REASONING_HANDOFF_MARKER)
            append('\n')
            append(summary.take(MAX_REASONING_HANDOFF_CHARS))
            append("\n\n")
            append(variant.content)
        }
        val newVariants = node.variants.toMutableList()
        val idx = node.activeVariantIndex.coerceIn(0, (newVariants.size - 1).coerceAtLeast(0))
        if (idx !in newVariants.indices) return@map node
        newVariants[idx] = variant.copy(content = handoff)
        changed = true
        node.copy(variants = newVariants)
    }
    return if (changed) out else nodes
}

/** A server-side chain is reusable only by the protocol and model that created it. */
internal fun AiProviderEngine.reusableProviderChainId(
    previous: MessageVariant,
    protocol: ProviderProtocol,
    model: String
): String {
    fun normalized(name: String) = name.trim().removePrefix("models/")
    val sameModel = previous.modelUsed.isBlank() || normalized(previous.modelUsed).equals(normalized(model), ignoreCase = true)
    return if (previous.providerProtocol == protocol && sameModel) previous.providerInteractionId else ""
}

internal fun AiProviderEngine.isValidOpenAiEncryptedReasoning(rawSig: String, savedProtocol: ProviderProtocol? = null): Boolean {
    if (savedProtocol != null && savedProtocol != ProviderProtocol.OPENAI_RESPONSES) return false
    val trimmed = rawSig.trim()
    if (trimmed.isEmpty() || trimmed.contains(' ')) return false
    // Tokens end in normal base64 padding; padding mid-string means two tokens were glued together.
    if (trimmed.indexOf('=') in 0 until trimmed.trimEnd('=').length) return false
    if (isCompleteGeminiSignatureProtobuf(trimmed) || isGeminiServerSideToolSignature(trimmed)) return false
    return true
}

internal fun AiProviderEngine.isValidAnthropicSignature(rawSig: String, savedProtocol: ProviderProtocol?): Boolean {
    if (savedProtocol != null && savedProtocol != ProviderProtocol.ANTHROPIC_MESSAGES) return false
    val trimmed = rawSig.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("gAAAAAB")) return false
    val hasMidPadding = trimmed.indexOf('=') in 0 until trimmed.trimEnd('=').length
    if (hasMidPadding) return false
    return !isCompleteGeminiSignatureProtobuf(trimmed) && !isGeminiServerSideToolSignature(trimmed)
}

/**
 * Appends an incoming signature chunk to `builder` while preventing distinct complete Base64
 * signatures (e.g. ending in `=` padding or starting a new Gemini `0x12 ...` protobuf) from
 * being concatenated into an invalid Franken-signature.
 */
internal fun AiProviderEngine.appendSignatureChunkSafe(builder: StringBuilder, incoming: String): String {
    if (incoming.isEmpty()) return ""
    val cur = builder.toString()
    if (cur == incoming || cur.endsWith(incoming)) return ""
    if (cur.isEmpty()) {
        builder.append(incoming)
        return incoming
    }
    val curEndsWithPadding = cur.endsWith('=')
    val bothAreCompleteGeminiSigs = isCompleteGeminiSignatureProtobuf(cur) && isCompleteGeminiSignatureProtobuf(incoming)
    if (curEndsWithPadding || bothAreCompleteGeminiSigs) {
        if (isGeminiServerSideToolSignature(incoming) && !isGeminiServerSideToolSignature(cur)) {
            return ""
        }
        builder.setLength(0)
        builder.append(incoming)
        return incoming
    }
    builder.append(incoming)
    return incoming
}
