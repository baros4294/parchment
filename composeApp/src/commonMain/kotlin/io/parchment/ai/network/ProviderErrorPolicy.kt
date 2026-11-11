package io.parchment.ai.network

import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.parchment.ai.network.AiProviderEngine.StepRetriesExhaustedException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// How provider errors are summarized, retried or used to fall back.

/**
 * Unwraps raw JSON API error payloads (such as `{"error":{"code":"invalid_request","message":"..."}}`)
 * into clean, human-readable summaries without raw JSON braces or mid-word truncation.
 */
internal fun AiProviderEngine.extractCleanApiErrorSummary(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val braceIdx = trimmed.indexOf('{')
    val bracketIdx = trimmed.indexOf('[')
    val jsonStart = when {
        braceIdx >= 0 && bracketIdx >= 0 -> minOf(braceIdx, bracketIdx)
        braceIdx >= 0 -> braceIdx
        bracketIdx >= 0 -> bracketIdx
        else -> -1
    }
    if (jsonStart >= 0) {
        val prefix = trimmed.substring(0, jsonStart).trim().removeSuffix(":").trim()
        val jsonCandidate = trimmed.substring(jsonStart).trim()
        val parsedSummary = runCatching {
            val rootEl = json.parseToJsonElement(jsonCandidate)
            val rootObj = when (rootEl) {
                is JsonObject -> rootEl
                is JsonArray -> rootEl.firstOrNull() as? JsonObject
                else -> null
            }
            if (rootObj != null) {
                val errObj = (rootObj["error"] as? JsonObject) ?: rootObj
                val code = errObj["code"]?.jsonPrimitive?.contentOrNull
                    ?: errObj["status"]?.jsonPrimitive?.contentOrNull
                    ?: errObj["type"]?.jsonPrimitive?.contentOrNull
                val msg = errObj["message"]?.jsonPrimitive?.contentOrNull
                    ?: errObj["detail"]?.jsonPrimitive?.contentOrNull
                    ?: (rootObj["error"] as? JsonPrimitive)?.contentOrNull
                if (!msg.isNullOrBlank()) {
                    val cleanMsg = msg.replace(Regex("\\s+"), " ").trim()
                    if (prefix.isNotEmpty()) {
                        val codeTag = if (!code.isNullOrBlank() && !prefix.contains(code, ignoreCase = true)) {
                            " ($code)"
                        } else ""
                        "$prefix$codeTag: $cleanMsg"
                    } else {
                        val codeTag = if (!code.isNullOrBlank()) "[$code] " else ""
                        "$codeTag$cleanMsg"
                    }
                } else null
            } else null
        }.getOrNull()
        if (!parsedSummary.isNullOrBlank()) {
            return parsedSummary
        }
    }
    return trimmed.replace(Regex("\\s+"), " ").trim()
}

/**
 * Detects transient proxy/gateway/CDN timeouts (~5-minute 300s Cloudflare 524, Nginx 502/504,
 * socket read timeout, premature EOF, connection reset, 529 overloaded, or 429 rate limit) so
 * `streamGeneration` and multi-step workspace tool loops can automatically retry.
 */
internal fun AiProviderEngine.isHardAuthOrQuotaError(err: Throwable): Boolean {
    val msg = (err.message ?: err.toString()).lowercase()
    return msg.contains("http 401") ||
        msg.contains("http 403") ||
        msg.contains("invalid_api_key") ||
        msg.contains("invalid api key") ||
        msg.contains("authentication_error") ||
        msg.contains("permission_denied") ||
        msg.contains("billing_not_active") ||
        msg.contains("insufficient_quota")
}

internal fun AiProviderEngine.isTransientOrProxyTimeoutError(err: Throwable): Boolean {
    if (err is kotlinx.coroutines.CancellationException || err is AiProviderEngine.NoRetryException) return false
    // Fail fast on hard authentication, permission, or billing/quota deactivation errors
    if (isHardAuthOrQuotaError(err)) {
        return false
    }
    // Allow unsupported-endpoint errors (404/405/501) to trigger protocol auto-fallback immediately without waiting 60s
    if (isEndpointUnsupportedForAutoFallback(err)) {
        return false
    }
    val msg = (err.message ?: err.toString()).lowercase()
    // Fail fast on client-side bad request (HTTP 400) unless it is an explicit rate-limit / overload / timeout wrapped in 400
    if (msg.contains("http 400")) {
        if (msg.contains("invalid_request") || msg.contains("invalid_argument") || msg.contains("bad_request")) {
            return false
        }
        val isRateOrOverload400 = msg.contains("rate limit") ||
            msg.contains("rate_limit") ||
            msg.contains("ratelimit") ||
            msg.contains("too many requests") ||
            msg.contains("resource_exhausted") ||
            msg.contains("overload") ||
            msg.contains("timeout") ||
            msg.contains("busy") ||
            msg.contains("capacity") ||
            msg.contains("try again")
        if (!isRateOrOverload400) {
            return false
        }
    }
    // Treat all other network, socket, EOF, stream, rate-limit (429),
    // gateway (502/503/504/520-529), and upstream proxy interruptions as retryable.
    return true
}

/**
 * Detects when a proxy or server does not implement `/v1beta/interactions` or `/v1/responses`
 * so `streamGeneration` can automatically fall back to `:streamGenerateContent` or `/v1/chat/completions`.
 */
internal fun AiProviderEngine.isEndpointUnsupportedForAutoFallback(err: Throwable): Boolean {
    val msg = (err.message ?: err.toString()).lowercase()
    return msg.contains("http 404") ||
        msg.contains("http 405") ||
        msg.contains("http 501") ||
        msg.contains("not found") ||
        msg.contains("unknown endpoint") ||
        msg.contains("unrecognized request url") ||
        msg.contains("unsupported path")
}

/**
 * Determines whether a failure on the primary protocol (`/v1beta/interactions` or `/v1/responses`)
 * should fall back to the legacy endpoint (`:streamGenerateContent` or `/v1/chat/completions`).
 * - Never falls back on `CancellationException` or hard auth/quota errors (HTTP 401/403).
 * - Always falls back on unsupported endpoint errors (404/405/501).
 * - When `useLegacyFallbackEndpoint` is enabled, falls back on any remaining error after the
 *   primary protocol's self-healing and fast probe retry budget (`LEGACY_FALLBACK_PRIMARY_MAX_RETRIES`) are exhausted.
 */
internal fun AiProviderEngine.shouldFallbackToLegacyProtocol(
    err: Throwable,
    useLegacyFallbackEndpoint: Boolean
): Boolean {
    if (err is kotlinx.coroutines.CancellationException || err is AiProviderEngine.NoRetryException) return false
    val actual = if (err is StepRetriesExhaustedException && err.cause != null) err.cause!! else err
    if (isHardAuthOrQuotaError(actual)) return false
    if (isEndpointUnsupportedForAutoFallback(actual)) return true
    return useLegacyFallbackEndpoint
}

/**
 * Detects HTTP 400 / 413 context-window / token-limit overflow errors across Gemini, OpenAI, Anthropic, and proxies.
 */
internal fun AiProviderEngine.isContextLengthExceededError(err: Throwable): Boolean {
    val msg = (err.message ?: err.toString()).lowercase()
    return msg.contains("context_length_exceeded") ||
        msg.contains("maximum context length") ||
        msg.contains("prompt is too long") ||
        (msg.contains("input token count") && msg.contains("exceed")) ||
        msg.contains("too many tokens") ||
        msg.contains("exceeds the maximum number of tokens") ||
        msg.contains("request too large") ||
        msg.contains("http 413")
}
