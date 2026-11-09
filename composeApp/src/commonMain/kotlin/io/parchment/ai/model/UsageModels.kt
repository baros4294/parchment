package io.parchment.ai.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Nullable counters distinguish unavailable usage from a provider-reported zero. */
@Immutable
@Serializable
data class ProviderTokenUsage(
    val inputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val cacheCreationInputTokens: Long? = null,
    val shortCacheCreationInputTokens: Long? = null,
    val longCacheCreationInputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val outputTokens: Long? = null,
    val outputIncludesReasoning: Boolean = false,
    val invalidCounts: Boolean = false,
    val uncachedInputTokens: Long? = null,
    val isFinal: Boolean = false,
    val charges: ProviderChargeUsage = ProviderChargeUsage()
) {
    val isComplete: Boolean get() = inputTokens != null && outputTokens != null && !invalidCounts &&
        listOfNotNull(inputTokens, outputTokens, cachedInputTokens, cacheCreationInputTokens,
            shortCacheCreationInputTokens, longCacheCreationInputTokens, reasoningTokens, uncachedInputTokens).all { it >= 0L } &&
        (cachedInputTokens ?: 0L) <= inputTokens &&
        (cacheCreationInputTokens ?: 0L) <= inputTokens - (cachedInputTokens ?: 0L) &&
        (uncachedInputTokens == null || cachedInputTokens != null && cacheCreationInputTokens != null) &&
        (!outputIncludesReasoning || (reasoningTokens ?: 0L) <= outputTokens)

    fun updatedBy(next: ProviderTokenUsage): ProviderTokenUsage = next.copy(
        inputTokens = next.inputTokens ?: inputTokens,
        cachedInputTokens = next.cachedInputTokens ?: cachedInputTokens,
        cacheCreationInputTokens = next.cacheCreationInputTokens ?: cacheCreationInputTokens,
        shortCacheCreationInputTokens = next.shortCacheCreationInputTokens ?: shortCacheCreationInputTokens,
        longCacheCreationInputTokens = next.longCacheCreationInputTokens ?: longCacheCreationInputTokens,
        reasoningTokens = next.reasoningTokens ?: reasoningTokens,
        outputTokens = next.outputTokens ?: outputTokens,
        invalidCounts = invalidCounts || next.invalidCounts,
        uncachedInputTokens = next.uncachedInputTokens ?: uncachedInputTokens,
        isFinal = isFinal || next.isFinal,
        charges = charges.updatedBy(next.charges)
    ).let { merged ->
        if (merged.uncachedInputTokens == null) merged else {
            val parts = listOf(merged.uncachedInputTokens, merged.cachedInputTokens ?: 0L, merged.cacheCreationInputTokens ?: 0L)
            var sum = 0L
            var overflow = false
            for (part in parts) {
                if (part > Long.MAX_VALUE - sum) { overflow = true; sum = Long.MAX_VALUE; break }
                sum += part
            }
            merged.copy(inputTokens = sum, invalidCounts = merged.invalidCounts || overflow)
        }
    }

    val separateReasoningTokens: Long get() = if (outputIncludesReasoning) {
        (reasoningTokens ?: 0L).coerceIn(0L, outputTokens ?: Long.MAX_VALUE)
    } else (reasoningTokens ?: 0L).coerceAtLeast(0L)

    val nonReasoningOutputTokens: Long get() =
        ((outputTokens ?: 0L) - if (outputIncludesReasoning) separateReasoningTokens else 0L).coerceAtLeast(0L)
}

@Immutable
@Serializable
data class TokenPricing(
    val inputPerM: Double? = null,
    val outputPerM: Double? = null,
    val cacheReadPerM: Double? = null,
    val cacheWritePerM: Double? = null,
    val reasoningPerM: Double? = null,
    val longCacheWritePerM: Double? = null
) {
    val hasAnyRate: Boolean get() = inputPerM != null || outputPerM != null || cacheReadPerM != null || cacheWritePerM != null || reasoningPerM != null || longCacheWritePerM != null
    fun withMissingFrom(other: TokenPricing): TokenPricing = TokenPricing(
        inputPerM ?: other.inputPerM, outputPerM ?: other.outputPerM, cacheReadPerM ?: other.cacheReadPerM,
        cacheWritePerM ?: other.cacheWritePerM, reasoningPerM ?: other.reasoningPerM,
        longCacheWritePerM ?: other.longCacheWritePerM
    )
    /** Token-only estimate; no missing rate is interpreted as a free service. */
    fun estimate(metrics: TokenMetrics, shortWriteTokens: Long? = null, longWriteTokens: Long? = null): Double? {
        val input = metrics.inputTokens.coerceAtLeast(0L)
        val cached = metrics.cachedInputTokens.coerceIn(0L, input)
        val created = metrics.cacheCreationInputTokens.coerceIn(0L, input - cached)
        val ordinary = input - cached - created
        fun cost(tokens: Double, rate: Double?): Double? {
            if (tokens == 0.0) return 0.0
            if (!tokens.isFinite() || tokens < 0.0 || rate == null || !rate.isFinite() || rate < 0.0) return null
            return (tokens / 1_000_000.0 * rate).takeIf { it.isFinite() }
        }
        val writeUnits = metrics.cacheCreationCostUnits.takeIf { it.isFinite() && it >= created.toDouble() }
            ?.coerceAtMost(created.toDouble() * 2.0) ?: created.toDouble()
        val shortWriteRate = cacheWritePerM ?: inputPerM?.times(1.25)
        val writeCost = if (shortWriteTokens != null && longWriteTokens != null &&
            shortWriteTokens >= 0 && longWriteTokens >= 0 && saturatingTokenSum(shortWriteTokens, longWriteTokens) == created) {
            val shortCost = cost(shortWriteTokens.toDouble(), shortWriteRate)
            val longCost = if (longCacheWritePerM != null) cost(longWriteTokens.toDouble(), longCacheWritePerM)
                else cost((writeUnits - shortWriteTokens).coerceAtLeast(0.0), shortWriteRate)
            if (shortCost != null && longCost != null) shortCost + longCost else null
        } else cost(writeUnits, shortWriteRate)
        val terms = listOf(
            cost(ordinary.toDouble(), inputPerM),
            cost(cached.toDouble(), cacheReadPerM ?: inputPerM),
            writeCost,
            cost(metrics.reasoningTokens.coerceAtLeast(0L).toDouble(), reasoningPerM ?: outputPerM),
            cost(metrics.outputTokens.coerceAtLeast(0L).toDouble(), outputPerM)
        )
        if (terms.any { it == null }) return null
        return terms.filterNotNull().sum().takeIf { it.isFinite() }
    }
}

@Immutable
@Serializable
data class ContextPricingTier(val inputTokensAbove: Long, val pricing: TokenPricing)

/** Cumulative quantities; prices are USD per declared unit, never inferred from magnitude. */
@Immutable
@Serializable
data class ProviderChargeUsage(
    val reportedTotalUsd: Double? = null,
    val units: Map<String, Long> = emptyMap(),
    val observedComponents: Set<String> = emptySet(),
    val invalid: Boolean = false
) {
    fun updatedBy(next: ProviderChargeUsage) = ProviderChargeUsage(
        next.reportedTotalUsd ?: reportedTotalUsd, units + next.units,
        observedComponents + next.observedComponents, invalid || next.invalid
    )
}

@Immutable
@Serializable
data class ChargePricing(val perUnitUsd: Map<String, Double> = emptyMap()) {
    fun withMissingFrom(other: ChargePricing) = ChargePricing(other.perUnitUsd + perUnitUsd)
}

@Immutable
@Serializable
data class ApiUsageRecord(
    val requestId: String,
    val responseId: String = "",
    val wireDialect: String,
    val profileId: String,
    val model: String,
    val pricing: TokenPricing = TokenPricing(),
    val usage: ProviderTokenUsage = ProviderTokenUsage(),
    val ownerSessionId: String = "",
    val ownerRunId: String = "",
    val purpose: String = "chat",
    val contextPricingTiers: List<ContextPricingTier> = emptyList(),
    val chargePricing: ChargePricing = ChargePricing()
)

internal fun legacyTokenPricing(input: Double, output: Double, cached: Double, write: Double, reasoning: Double): TokenPricing {
    fun rate(value: Double) = value.takeIf { it.isFinite() && it > 0.0 }
    return TokenPricing(rate(input), rate(output), rate(cached), rate(write), rate(reasoning))
}

internal fun parsePriceText(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

internal fun saturatingTokenSum(vararg counts: Long): Long = counts.fold(0L) { total, count ->
    val positive = count.coerceAtLeast(0L)
    if (positive > Long.MAX_VALUE - total) Long.MAX_VALUE else total + positive
}
