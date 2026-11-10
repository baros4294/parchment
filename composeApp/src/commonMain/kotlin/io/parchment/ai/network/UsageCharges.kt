package io.parchment.ai.network

import io.parchment.ai.model.*
import kotlinx.serialization.json.*

/** Fee counters are cumulative just like token counters; repeated SSE events replace them. */
internal fun parseProviderCharges(dialect: UsageDialect, usage: JsonObject?, response: JsonObject? = null): ProviderChargeUsage {
    val units = linkedMapOf<String, Long>()
    val observed = linkedSetOf<String>()
    var invalid = false
    fun count(source: JsonObject?, field: String, key: String) {
        val raw = source?.get(field) ?: return
        if (raw is JsonNull) return
        val value = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (value == null || value < 0) invalid = true else units[key] = value
    }
    val rawCost = if (dialect == UsageDialect.OPENAI_CHAT || dialect == UsageDialect.OPENAI_RESPONSES)
        usage?.get("cost")?.takeUnless { it is JsonNull } else null
    val cost = (rawCost as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        ?.takeIf { it.isFinite() && it >= 0 }
    if (rawCost != null && cost == null) invalid = true
    if ((usage?.get("is_byok") as? JsonPrimitive)?.booleanOrNull == true) observed += "external provider billing"
    val rawServer = usage?.get("server_tool_use")?.takeUnless { it is JsonNull }
    val server = rawServer as? JsonObject
    if (rawServer != null && server == null) invalid = true
    count(server, "web_search_requests", "web_search")
    // Retain unknown server charges as unavailable rather than interpreting them as free.
    server?.forEach { (key, value) ->
        if (key != "web_search_requests" && value != JsonPrimitive(0) && value !is JsonNull) observed += "other server tools"
    }
    if (dialect == UsageDialect.OPENAI_CHAT || dialect == UsageDialect.OPENAI_RESPONSES) {
        val input = usage?.get(if (dialect == UsageDialect.OPENAI_CHAT) "prompt_tokens_details" else "input_tokens_details") as? JsonObject
        val output = usage?.get(if (dialect == UsageDialect.OPENAI_CHAT) "completion_tokens_details" else "output_tokens_details") as? JsonObject
        count(input, "audio_tokens", "input_audio")
        count(input, "video_tokens", "input_video")
        count(output, "audio_tokens", "output_audio")
        count(output, "image_tokens", "output_image")
    }
    if (dialect == UsageDialect.GEMINI_CONTENT) {
        fun modalities(field: String, prefix: String) {
            (usage?.get(field) as? JsonArray)?.forEach { element ->
                val item = element as? JsonObject ?: run { invalid = true; return@forEach }
                val modality = (item["modality"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
                if (modality == "text") return@forEach
                val key = "${prefix}_$modality"
                val previous = units[key]
                count(item, "tokenCount", key)
                if (previous != null) invalid = true // duplicate bins are not a documented sum
            }
        }
        modalities("promptTokensDetails", "input")
        modalities("cacheTokensDetails", "cached_input")
        modalities("candidatesTokensDetails", "output")
    }
    fun item(element: JsonElement) {
        val obj = element as? JsonObject ?: return
        when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "web_search_call", "google_search_result", "web_search_result", "web_search_tool_result" -> observed += "web_search"
            "image_generation_call", "image" -> observed += "output_image"
            "code_interpreter_call" -> observed += "code execution"
            // Gemini code execution is billed through its ordinary token usage.
            "code_execution_result" -> if (dialect != UsageDialect.GEMINI_CONTENT && dialect != UsageDialect.GEMINI_INTERACTIONS) observed += "code execution"
            "server_tool_use" -> {
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                observed += if (name == "web_search") "web_search" else "other server tools"
            }
        }
    }
    listOf("output", "outputs", "steps", "content").forEach { field ->
        (response?.get(field) as? JsonArray)?.forEach(::item)
    }
    listOf("step", "delta", "content_block", "item").forEach { field -> response?.get(field)?.let(::item) }
    (response?.get("candidates") as? JsonArray)?.forEach { candidate ->
        val grounding = (candidate as? JsonObject)?.get("groundingMetadata") as? JsonObject
        if ((grounding?.get("webSearchQueries") as? JsonArray)?.isNotEmpty() == true ||
            (grounding?.get("groundingChunks") as? JsonArray)?.isNotEmpty() == true) observed += "web_search"
    }
    return ProviderChargeUsage(cost, units, observed, invalid)
}

internal fun requestChargePricing(profile: ProviderProfile, model: String): ChargePricing =
    profile.customModelSpecs[model]?.chargePricing?.takeIf { it.perUnitUsd.isNotEmpty() }
        ?: ModelsDevClient.getCachedSpec(model)?.chargePricing ?: ChargePricing()

internal data class RequestCostEstimate(val usd: Double, val unavailable: Set<String>, val reported: Boolean = false)

/** Reported totals cover tokens and fees; never add a second estimate on top of them. */
internal fun estimateRequestCost(record: ApiUsageRecord): RequestCostEstimate {
    val charges = record.usage.charges
    charges.reportedTotalUsd?.takeIf { it.isFinite() && it >= 0 && !charges.invalid }?.let {
        return RequestCostEstimate(it, charges.observedComponents.filter { component -> component == "external provider billing" }.toSet(), reported = true)
    }
    val missing = linkedSetOf<String>()
    if (charges.invalid) missing += "invalid fee usage"
    val usage = record.usage
    var part = usageRecordMetrics(record)
    val pricedUnits = charges.units.toMutableMap()
    fun label(key: String) = when (key) {
        "input_audio" -> "audio input"
        "input_image_count" -> "image input"
        "cached_input_audio" -> "cached audio input"
        "output_audio" -> "audio output"
        "output_image" -> "image output"
        "web_search" -> "native web search"
        else -> key.replace('_', ' ')
    }
    val audioIn = charges.units["input_audio"] ?: 0
    val audioCached = charges.units["cached_input_audio"]
    val audioOut = charges.units["output_audio"] ?: 0
    val imageOut = charges.units["output_image"] ?: 0
    if (charges.units.values.any { it < 0 } || audioIn > part.inputTokens || audioCached != null && audioCached > audioIn ||
        audioOut > part.outputTokens || imageOut > part.outputTokens - audioOut.coerceAtMost(part.outputTokens)) {
        return RequestCostEstimate(0.0, setOf("invalid media usage"))
    }
    val unsupportedInput = charges.units.any { (key, count) -> count > 0 &&
        (key.startsWith("input_") && key != "input_audio" || key.startsWith("cached_input_") && key != "cached_input_audio") }
    val unsupportedOutput = charges.units.any { (key, count) -> count > 0 && key.startsWith("output_") && key !in setOf("output_audio", "output_image") }
    val missingInputSplit = charges.observedComponents.any { it in setOf("input_audio", "input_video") && charges.units[it] == null }
    if (unsupportedInput || missingInputSplit) {
        missing += "input media usage/prices"
        part = part.copy(inputTokens = 0, cachedInputTokens = 0, cacheCreationInputTokens = 0, cacheCreationCostUnits = 0.0)
        pricedUnits.keys.removeAll { it.startsWith("input_") || it.startsWith("cached_input_") }
    }
    if (audioIn > 0 && !unsupportedInput && !missingInputSplit) {
        if (part.cacheCreationInputTokens > 0 || part.cachedInputTokens > 0 && audioCached == null ||
            audioCached != null && (audioCached > part.cachedInputTokens ||
                audioIn - audioCached > part.inputTokens - part.cachedInputTokens)) {
            // Unknown cache overlap cannot be billed twice or assigned the ordinary input rate.
            missing += "audio input/cache split"
            part = part.copy(inputTokens = 0, cachedInputTokens = 0, cacheCreationInputTokens = 0, cacheCreationCostUnits = 0.0)
            pricedUnits.remove("input_audio")
            pricedUnits.remove("cached_input_audio")
        } else {
            part = part.copy(inputTokens = part.inputTokens - audioIn, cachedInputTokens = part.cachedInputTokens - (audioCached ?: 0))
            pricedUnits["input_audio"] = audioIn - (audioCached ?: 0)
        }
    }
    part = part.copy(outputTokens = part.outputTokens - audioOut - imageOut)
    if (unsupportedOutput) {
        missing += "output media usage/prices"
        part = part.copy(outputTokens = 0)
        pricedUnits.keys.removeAll { it.startsWith("output_") }
    }
    val priceUsageKnown = usage.isComplete && (part.inputTokens == 0L ||
        usage.cachedInputTokens != null && usage.cacheCreationInputTokens != null)
    val ttlKnown = part.cacheCreationInputTokens == 0L || usage.shortCacheCreationInputTokens != null &&
        usage.longCacheCreationInputTokens != null &&
        saturatingTokenSum(usage.shortCacheCreationInputTokens, usage.longCacheCreationInputTokens) == part.cacheCreationInputTokens
    val tokenCost = if (priceUsageKnown && ttlKnown) recordPricing(record).estimate(part,
        usage.shortCacheCreationInputTokens, usage.longCacheCreationInputTokens) else null
    if (tokenCost == null) missing += "token usage/prices"
    if ("request" in record.chargePricing.perUnitUsd) {
        if (usage.isFinal && usage.isComplete) pricedUnits["request"] = 1 else missing += "request fee"
    }
    var total = tokenCost ?: 0.0
    for ((key, count) in pricedUnits) {
        if (count == 0L) continue
        val rate = record.chargePricing.perUnitUsd[key]
        val cost = rate?.takeIf { it.isFinite() && it >= 0 }?.times(count.toDouble())?.takeIf { it.isFinite() }
        if (cost == null) missing += label(key) else total += cost
    }
    for (key in charges.observedComponents) {
        if (charges.units[key] == null || key == "output_image" && record.chargePricing.perUnitUsd[key] == null) missing += label(key)
    }
    if (!total.isFinite()) return RequestCostEstimate(0.0, missing + "cost overflow")
    return RequestCostEstimate(total, missing)
}

/**
 * What a spend limit counts. Many servers leave out the cache split, which makes the displayed
 * cost unavailable; here that input counts at its lowest possible price, so the limit still
 * holds and never stops a run on an overestimate.
 */
internal fun spendTowardLimit(records: Collection<ApiUsageRecord>): Double {
    val total = records.sumOf { record ->
        val usage = record.usage
        val splitMissing = usage.isComplete && (usage.cachedInputTokens == null || usage.cacheCreationInputTokens == null)
        val estimate = estimateRequestCost(record)
        if (!splitMissing || estimate.reported) estimate.usd else estimateRequestCost(record.copy(usage = usage.copy(
            cachedInputTokens = usage.cachedInputTokens ?: usage.inputTokens,
            cacheCreationInputTokens = usage.cacheCreationInputTokens ?: 0L))).usd
    }
    return total.takeIf { it.isFinite() && it >= 0 } ?: 0.0
}
