package io.parchment.ai.protocol

import kotlin.io.encoding.Base64

private val BASE64_SEGMENT_REGEX = Regex("[A-Za-z0-9+/]+={0,2}")

private val GEMINI_SIG_MAGIC = byteArrayOf(0x01, 0x69, 0x14, 0x7d, 0x13)

private val GEMINI_SERVER_TOOL_TAG = byteArrayOf(0x08, 0x02, 0x12)

private fun indexOfByteSubarray(haystack: ByteArray, needle: ByteArray, endExclusive: Int = haystack.size): Int {
    if (needle.isEmpty() || endExclusive < needle.size) return -1
    val limit = minOf(haystack.size, endExclusive) - needle.size
    for (i in 0..limit) {
        var match = true
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) {
                match = false
                break
            }
        }
        if (match) return i
    }
    return -1
}

internal fun isGeminiServerSideToolSignature(seg: String): Boolean {
    if (seg.length < 16 || seg.startsWith("gAAAAAB")) return false
    val decoded = runCatching { Base64.decode(seg.trim()) }.getOrNull() ?: return false
    val magicPos = indexOfByteSubarray(decoded, GEMINI_SIG_MAGIC)
    if (magicPos <= 0) return false
    return indexOfByteSubarray(decoded, GEMINI_SERVER_TOOL_TAG, endExclusive = magicPos) >= 0
}

internal fun isCompleteGeminiSignatureProtobuf(seg: String): Boolean {
    if (seg.length < 16 || seg.startsWith("gAAAAAB")) return false
    val decoded = runCatching { Base64.decode(seg.trim()) }.getOrNull() ?: return false
    return indexOfByteSubarray(decoded, GEMINI_SIG_MAGIC) >= 0
}

/**
 * Sanitizes a raw or historically concatenated Gemini `thoughtSignature` string:
 * - Splits any glued Base64 segments (`...=...==...==`) into individual Base64 strings.
 * - When `forFunctionCall == true`, filters out server-side `google_search` (`08 02`) signatures
 *   so `/v1beta/interactions` and `:streamGenerateContent` never fail with `HTTP 400`.
 */
internal fun sanitizeGeminiThoughtSignature(rawSig: String, forFunctionCall: Boolean = false): String {
    val trimmed = rawSig.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed == "context_engineering_is_the_way_to_go" || trimmed == "skip_thought_signature_validator") {
        return if (forFunctionCall) trimmed else ""
    }
    if (trimmed.startsWith("gAAAAAB")) return ""
    val hasMidPadding = trimmed.indexOf('=') in 0 until trimmed.trimEnd('=').length
    val isRealGeminiProtobuf = isCompleteGeminiSignatureProtobuf(trimmed) || isGeminiServerSideToolSignature(trimmed)
    if (!hasMidPadding && !isRealGeminiProtobuf) {
        return trimmed
    }
    val segments = BASE64_SEGMENT_REGEX.findAll(trimmed)
        .map { it.value }
        .filter { it.length >= 16 }
        .toList()
    if (segments.isEmpty()) return ""
    if (forFunctionCall) {
        val validNonServer = segments.firstOrNull { seg ->
            isCompleteGeminiSignatureProtobuf(seg) && !isGeminiServerSideToolSignature(seg)
        }
        if (validNonServer != null) return validNonServer
        val anyNonServer = segments.firstOrNull { seg ->
            !isGeminiServerSideToolSignature(seg) && runCatching { Base64.decode(seg) }.isSuccess
        }
        return anyNonServer.orEmpty()
    } else {
        val lastNonServer = segments.lastOrNull { seg ->
            isCompleteGeminiSignatureProtobuf(seg) && !isGeminiServerSideToolSignature(seg)
        }
        if (lastNonServer != null) return lastNonServer
        return segments.lastOrNull { runCatching { Base64.decode(it) }.isSuccess }.orEmpty()
    }
}
