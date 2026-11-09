package io.parchment.ai.network

import io.parchment.ai.model.ApiUsageRecord
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderTokenUsage
import io.parchment.ai.model.TokenMetrics
import io.parchment.ai.model.TokenPricing
import io.parchment.ai.model.ContextPricingTier
import io.parchment.ai.model.legacyTokenPricing
import io.parchment.ai.model.saturatingTokenSum
import io.parchment.ai.platform.PlatformBridge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random

internal enum class UsageDialect(val wireName: String) {
    OPENAI_RESPONSES("openai_responses"),
    OPENAI_CHAT("openai_chat"),
    ANTHROPIC("anthropic_messages"),
    GEMINI_INTERACTIONS("gemini_interactions"),
    GEMINI_CONTENT("gemini_generate_content")
}

internal typealias UsageReporter = suspend (String?, String, UsageDialect, ProviderTokenUsage) -> Unit
internal typealias UsageRequestStarter = suspend (UsageDialect) -> String

/** Only usage is normalized here; request payloads and opaque reasoning remain untouched. */
internal fun parseProviderUsage(dialect: UsageDialect, obj: JsonObject?, isFinal: Boolean = false, response: JsonObject? = null): ProviderTokenUsage {
    var invalid = false
    fun count(source: JsonObject?, name: String): Long? {
        val raw = source?.get(name) ?: return null
        val primitive = raw as? JsonPrimitive
        val value = primitive?.longOrNull
        if (value == null || value < 0L || primitive.isString) invalid = true
        return value?.takeIf { it >= 0L }
    }
    fun sum(vararg values: Long): Long {
        var total = 0L
        for (value in values) {
            if (value > Long.MAX_VALUE - total) { invalid = true; return Long.MAX_VALUE }
            total += value
        }
        return total
    }
    val combined = dialect == UsageDialect.OPENAI_RESPONSES || dialect == UsageDialect.OPENAI_CHAT || dialect == UsageDialect.ANTHROPIC
    if (obj == null) return ProviderTokenUsage(outputIncludesReasoning = combined, isFinal = isFinal,
        charges = parseProviderCharges(dialect, null, response))
    val input: Long?
    val cached: Long?
    var created: Long? = null
    var short: Long? = null
    var long: Long? = null
    val output: Long?
    val thinking: Long?
    var uncachedInput: Long? = null
    when (dialect) {
        UsageDialect.OPENAI_RESPONSES, UsageDialect.OPENAI_CHAT -> {
            val responses = dialect == UsageDialect.OPENAI_RESPONSES
            val inputDetails = obj[if (responses) "input_tokens_details" else "prompt_tokens_details"] as? JsonObject
            val outputDetails = obj[if (responses) "output_tokens_details" else "completion_tokens_details"] as? JsonObject
            input = count(obj, if (responses) "input_tokens" else "prompt_tokens")
            cached = count(inputDetails, "cached_tokens")
            thinking = count(outputDetails, "reasoning_tokens")
            output = count(obj, if (responses) "output_tokens" else "completion_tokens")
            if (responses) {
                created = count(inputDetails, "cache_write_tokens")
                val ttl = inputDetails?.get("cache_write_tokens_details") as? JsonObject
                short = count(ttl, "ttl_30m_tokens")
                long = count(ttl, "ttl_12h_tokens")
            } else {
                // The documented Chat Completions contract has no cache-creation charge field.
                created = 0L
            }
        }
        UsageDialect.ANTHROPIC -> {
            val uncached = count(obj, "input_tokens")
            uncachedInput = uncached
            cached = count(obj, "cache_read_input_tokens")
            created = count(obj, "cache_creation_input_tokens")
            val ttl = obj["cache_creation"] as? JsonObject
            short = count(ttl, "ephemeral_5m_input_tokens")
            long = count(ttl, "ephemeral_1h_input_tokens")
            if (created == null && short != null && long != null) created = sum(short, long)
            input = uncached?.let { sum(it, cached ?: 0L, created ?: 0L) }
            output = count(obj, "output_tokens")
            thinking = null // Anthropic includes thinking in output_tokens without a separate count.
        }
        UsageDialect.GEMINI_INTERACTIONS -> {
            input = count(obj, "total_input_tokens")
            cached = count(obj, "total_cached_tokens")
            output = count(obj, "total_output_tokens")
            thinking = count(obj, "total_thought_tokens")
            created = 0L // This driver does not issue explicit cached-content creation requests.
        }
        UsageDialect.GEMINI_CONTENT -> {
            input = count(obj, "promptTokenCount")
            cached = count(obj, "cachedContentTokenCount")
            output = count(obj, "candidatesTokenCount")
            thinking = count(obj, "thoughtsTokenCount")
            created = 0L
        }
    }
    if (created == null && short != null && long != null) created = sum(short, long)
    if (created != null && short != null && long != null && sum(short, long) != created) invalid = true
    if (input != null && (cached ?: 0L) > input) invalid = true
    if (input != null && (created ?: 0L) > input - (cached ?: 0L).coerceAtMost(input)) invalid = true
    if (combined && thinking != null && output != null && thinking > output) invalid = true
    return ProviderTokenUsage(input, cached, created, short, long, thinking, output, combined, invalid, uncachedInput, isFinal,
        parseProviderCharges(dialect, obj, response))
}

/** Effective write-token units at the short-TTL rate, with each token counted exactly once. */
internal fun cacheCreationCostUnits(record: ApiUsageRecord): Double {
    val usage = record.usage
    val total = (usage.cacheCreationInputTokens ?: 0L).coerceAtLeast(0L)
    val short = (usage.shortCacheCreationInputTokens ?: 0L).coerceIn(0L, total)
    val long = (usage.longCacheCreationInputTokens ?: 0L).coerceIn(0L, total - short)
    val longMultiplier = when (record.wireDialect) {
        UsageDialect.OPENAI_RESPONSES.wireName -> 2.5 / 1.25
        UsageDialect.ANTHROPIC.wireName -> 2.0 / 1.25
        else -> 1.0
    }
    return (total - long).toDouble() + long.toDouble() * longMultiplier
}

internal fun usageRecordMetrics(record: ApiUsageRecord): TokenMetrics = TokenMetrics(
    inputTokens = record.usage.inputTokens ?: 0L,
    cachedInputTokens = record.usage.cachedInputTokens ?: 0L,
    cacheCreationInputTokens = record.usage.cacheCreationInputTokens ?: 0L,
    cacheCreationCostUnits = cacheCreationCostUnits(record),
    outputTokens = record.usage.nonReasoningOutputTokens,
    reasoningTokens = record.usage.separateReasoningTokens,
    outputIncludesUnreportedReasoning = record.usage.outputIncludesReasoning && record.usage.reasoningTokens == null
)

internal fun requestPricing(profile: ProviderProfile, model: String): Pair<TokenPricing, List<ContextPricingTier>> {
    val explicit = profile.customModelSpecs[model]
    val catalog = ModelsDevClient.getCachedSpec(model)
    val providerOverride = profile.knownPricing != null && (profile.pricingModelId.isBlank() || profile.pricingModelId == model)
    val base = profile.tokenPricing(model)
    val pricing = if (explicit?.knownPricing != null || providerOverride) base else {
        val known = catalog?.knownPricing ?: catalog?.let { legacyTokenPricing(it.inputPricePerM, it.outputPricePerM,
            it.cacheReadPricePerM, it.cacheWritePricePerM, it.reasoningPricePerM) } ?: TokenPricing()
        base.withMissingFrom(known)
    }
    val tiers = when {
        explicit?.knownPricing != null || explicit?.contextPricingTiers?.isNotEmpty() == true -> explicit.contextPricingTiers.orEmpty()
        providerOverride -> emptyList()
        else -> catalog?.contextPricingTiers.orEmpty()
    }
    return pricing to tiers
}

internal fun recordPricing(record: ApiUsageRecord): TokenPricing {
    val input = record.usage.inputTokens ?: return record.pricing
    val tier = record.contextPricingTiers.filter { it.inputTokensAbove >= 0 && input > it.inputTokensAbove }
        .maxByOrNull { it.inputTokensAbove }
    return tier?.pricing?.withMissingFrom(record.pricing) ?: record.pricing
}

internal class ProviderUsageLedger(
    private val profile: ProviderProfile,
    private val model: String,
    initialRecords: List<ApiUsageRecord> = emptyList(),
    initialMetrics: TokenMetrics = TokenMetrics(),
    private val ownerSessionId: String = "",
    private val ownerRunId: String = "",
    private val purpose: String = "chat"
) {
    private val records = LinkedHashMap<String, ApiUsageRecord>().apply {
        initialRecords.filter { it.requestId.isNotBlank() }.forEach { put(it.requestId, it) }
    }
    private val legacy = initialMetrics.takeIf { records.isEmpty() && it.totalTokens > 0L } ?: TokenMetrics()
    private val prefix = "usage-${PlatformBridge.currentTimeMillis()}-${Random.nextLong().toULong().toString(16)}"
    private var sequence = 0L

    fun begin(dialect: UsageDialect, observedComponents: Set<String> = emptySet()): ApiUsageRecord {
        val initial = parseProviderUsage(dialect, null)
        return begin(dialect.wireName, initial.copy(charges = initial.charges.copy(observedComponents = observedComponents)))
    }

    fun beginMedia(wireDialect: String, component: String): ApiUsageRecord = begin(wireDialect,
        ProviderTokenUsage(charges = io.parchment.ai.model.ProviderChargeUsage(observedComponents = setOf(component))))

    private fun begin(wireDialect: String, usage: ProviderTokenUsage): ApiUsageRecord {
        val (pricing, tiers) = requestPricing(profile, model)
        val record = ApiUsageRecord("$prefix-${++sequence}", wireDialect = wireDialect, profileId = profile.id,
            model = model, pricing = pricing, usage = usage,
            ownerSessionId = ownerSessionId, ownerRunId = ownerRunId, purpose = purpose, contextPricingTiers = tiers,
            chargePricing = requestChargePricing(profile, model))
        records[record.requestId] = record
        return record
    }

    fun update(requestId: String?, responseId: String, dialect: UsageDialect, usage: ProviderTokenUsage): ApiUsageRecord? {
        val previous = if (requestId != null) records[requestId] else records.values.lastOrNull {
            responseId.isNotBlank() && it.responseId == responseId && it.profileId == profile.id &&
                it.model == model && it.wireDialect == dialect.wireName
        }
        if (previous == null || previous.wireDialect != dialect.wireName) return null
        val updated = previous.copy(responseId = responseId.ifBlank { previous.responseId }, usage = previous.usage.updatedBy(usage))
        if (updated == previous) return null
        records[updated.requestId] = updated
        return updated
    }

    fun snapshots(): List<ApiUsageRecord> = records.values.toList()
    fun observeChargeComponent(component: String): ApiUsageRecord? {
        val record = records.values.lastOrNull() ?: return null
        if (component in record.usage.charges.observedComponents) return null
        val updated = record.copy(usage = record.usage.copy(charges = record.usage.charges.copy(
            observedComponents = record.usage.charges.observedComponents + component)))
        records[record.requestId] = updated
        return updated
    }
    val hasReportedInput: Boolean get() = legacy.inputTokens > 0L || records.values.any { it.usage.inputTokens != null }
    val hasReportedOutput: Boolean get() = legacy.outputTokens > 0L || records.values.any { it.usage.outputTokens != null }
    val hasReportedReasoning: Boolean get() = legacy.reasoningTokens > 0L || records.values.any { it.usage.reasoningTokens != null }

    fun metrics(): TokenMetrics = aggregateApiUsageRecords(records.values, legacy)
}


internal fun aggregateApiUsageRecords(inputRecords: Collection<ApiUsageRecord>, legacy: TokenMetrics = TokenMetrics()): TokenMetrics {
    val records = inputRecords.filter { it.requestId.isNotBlank() }.associateBy { it.requestId }
    var input = legacy.inputTokens
    var cached = legacy.cachedInputTokens
    var created = legacy.cacheCreationInputTokens
    var writeUnits = legacy.cacheCreationCostUnits
    var output = legacy.outputTokens
    var reasoning = legacy.reasoningTokens
    var cost = legacy.estimatedCostUsd
    var costAvailable = legacy.totalTokens == 0L || legacy.costIsAvailable || legacy.estimatedCostUsd > 0.0
    var complete = legacy.totalTokens == 0L && records.isNotEmpty()
    var unreportedReasoning = legacy.outputIncludesUnreportedReasoning
    val unpriced = legacy.unpricedCostComponents.toMutableSet()
    var reportedCosts = legacy.reportedCostRequests
    for (record in records.values) {
        val part = usageRecordMetrics(record)
        if (part.inputTokens > Long.MAX_VALUE - input.coerceAtLeast(0L) ||
            part.cachedInputTokens > Long.MAX_VALUE - cached.coerceAtLeast(0L) ||
            part.outputTokens > Long.MAX_VALUE - output.coerceAtLeast(0L) ||
            part.reasoningTokens > Long.MAX_VALUE - reasoning.coerceAtLeast(0L)) {
            complete = false
            costAvailable = false
        }
        input = saturatingTokenSum(input, part.inputTokens)
        cached = saturatingTokenSum(cached, part.cachedInputTokens)
        created = saturatingTokenSum(created, part.cacheCreationInputTokens)
        output = saturatingTokenSum(output, part.outputTokens)
        reasoning = saturatingTokenSum(reasoning, part.reasoningTokens)
        writeUnits += part.cacheCreationCostUnits
        complete = complete && record.usage.isComplete && record.usage.isFinal
        unreportedReasoning = unreportedReasoning || part.outputIncludesUnreportedReasoning
        val estimate = estimateRequestCost(record)
        unpriced += estimate.unavailable
        if (estimate.reported) reportedCosts++
        costAvailable = costAvailable && estimate.unavailable.isEmpty()
        cost += estimate.usd
    }
    if (!cost.isFinite()) costAvailable = false
    return TokenMetrics(inputTokens = input, cachedInputTokens = cached, outputTokens = output, reasoningTokens = reasoning,
        cacheCreationInputTokens = created, cacheCreationCostUnits = writeUnits,
        reportedRequests = records.size, usageIsComplete = complete,
        costIsAvailable = costAvailable && (records.isNotEmpty() || legacy.totalTokens > 0L),
        estimatedCostUsd = cost.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
        outputIncludesUnreportedReasoning = unreportedReasoning,
        unpricedCostComponents = unpriced, reportedCostRequests = reportedCosts)
}
