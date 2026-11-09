package io.parchment.ai.network

import io.parchment.ai.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class UsageChargesTest {
    private val profile = ProviderProfile("fees", "Fees", ProviderProtocol.OPENAI_RESPONSES, defaultModel = "model",
        inputPricePerM = 1.0, outputPricePerM = 2.0, cacheReadPricePerM = 0.1)
    private fun record(charges: ProviderChargeUsage = ProviderChargeUsage(), fees: Map<String, Double> = emptyMap()) =
        ApiUsageRecord("request", wireDialect = "openai_chat", profileId = "fees", model = "model", pricing = profile.tokenPricing(),
            usage = ProviderTokenUsage(inputTokens = 100, cachedInputTokens = 0, cacheCreationInputTokens = 0,
                outputTokens = 80, reasoningTokens = 0, isFinal = true, charges = charges), chargePricing = ChargePricing(fees))
    private fun parse(dialect: UsageDialect, usage: String, response: String? = null) = parseProviderUsage(dialect,
        Json.parseToJsonElement(usage).jsonObject, isFinal = true, response = response?.let { Json.parseToJsonElement(it).jsonObject })

    @Test fun reportedTotalIncludesFeesAndDoesNotReceiveAnAdditionalTokenOrToolEstimate() {
        val charges = parse(UsageDialect.OPENAI_CHAT, """{"cost":0.123,"server_tool_use":{"web_search_requests":2}}""").charges
        val metrics = aggregateApiUsageRecords(listOf(record(charges, mapOf("web_search" to 1.0))))
        assertEquals(0.123, metrics.estimatedCostUsd)
        assertEquals(1, metrics.reportedCostRequests)
        assertTrue(metrics.costIsAvailable)
        assertTrue(metrics.unpricedCostComponents.isEmpty())
        val free = metricsFor(record(charges.copy(reportedTotalUsd = 0.0)))
        assertEquals(0.0, free.estimatedCostUsd)
        assertTrue(free.costIsAvailable)
        assertTrue(metricsFor(record(charges).copy(usage = ProviderTokenUsage(charges = charges))).costIsAvailable)
    }

    private fun metricsFor(record: ApiUsageRecord) = aggregateApiUsageRecords(listOf(record))

    @Test fun cumulativeSearchCountersCoalesceAndUseTheCapturedMetadataRate() {
        val configured = profile.copy(customModelSpecs = mapOf("model" to ModelPricingSpec("model",
            chargePricing = ChargePricing(mapOf("web_search" to 0.01)))))
        val ledger = ProviderUsageLedger(configured, "model")
        val request = ledger.begin(UsageDialect.ANTHROPIC)
        val usage = parse(UsageDialect.ANTHROPIC, """{"input_tokens":100,"cache_read_input_tokens":0,
            "cache_creation_input_tokens":0,"output_tokens":80,"server_tool_use":{"web_search_requests":2}}""")
        ledger.update(request.requestId, "response", UsageDialect.ANTHROPIC, usage)
        assertNull(ledger.update(request.requestId, "response", UsageDialect.ANTHROPIC, usage))
        ledger.update(request.requestId, "response", UsageDialect.ANTHROPIC, parseProviderUsage(UsageDialect.ANTHROPIC, null, true))
        assertEquals(0.02026, ledger.metrics().estimatedCostUsd, 1e-12)
        assertEquals(2L, ledger.snapshots().single().usage.charges.units["web_search"])
        assertTrue(ledger.metrics().costIsAvailable)
    }

    @Test fun audioTokensReplaceTheirTextRateAndAreNeverChargedTwice() {
        val usage = parse(UsageDialect.OPENAI_CHAT, """{"prompt_tokens":100,"completion_tokens":80,
            "prompt_tokens_details":{"cached_tokens":0,"audio_tokens":40},
            "completion_tokens_details":{"reasoning_tokens":0,"audio_tokens":20}}""")
        val metrics = metricsFor(record(fees = mapOf("input_audio" to 0.00001, "output_audio" to 0.00002)).copy(usage = usage))
        assertEquals(0.00098, metrics.estimatedCostUsd, 1e-12)
        assertTrue(metrics.costIsAvailable)
        assertEquals(100L, metrics.inputTokens)
        assertEquals(80L, metrics.outputTokens)
    }

    @Test fun geminiAudioCacheBinsAreSubtractedFromTheCorrectInputComponents() {
        val usage = parse(UsageDialect.GEMINI_CONTENT, """{"promptTokenCount":100,"cachedContentTokenCount":30,
            "candidatesTokenCount":80,"thoughtsTokenCount":0,
            "promptTokensDetails":[{"modality":"TEXT","tokenCount":60},{"modality":"AUDIO","tokenCount":40}],
            "cacheTokensDetails":[{"modality":"TEXT","tokenCount":20},{"modality":"AUDIO","tokenCount":10}],
            "candidatesTokensDetails":[{"modality":"AUDIO","tokenCount":20}]}""")
        val metrics = metricsFor(record(fees = mapOf("input_audio" to 0.00001, "cached_input_audio" to 0.000001,
            "output_audio" to 0.00002)).copy(usage = usage))
        assertEquals(0.000872, metrics.estimatedCostUsd, 1e-12)
        assertTrue(metrics.costIsAvailable)
    }

    @Test fun anUnknownAudioCacheSplitRetainsKnownOutputCostsAndLabelsTheMissingInput() {
        val item = record(ProviderChargeUsage(units = mapOf("input_audio" to 40, "output_audio" to 20)),
            mapOf("input_audio" to 0.00001, "output_audio" to 0.00002))
        val metrics = metricsFor(item.copy(usage = item.usage.copy(cachedInputTokens = 10)))
        assertEquals(0.00052, metrics.estimatedCostUsd, 1e-12)
        assertFalse(metrics.costIsAvailable)
        assertTrue("audio input/cache split" in metrics.unpricedCostComponents)
        val missingAudioCounts = metricsFor(record(ProviderChargeUsage(observedComponents = setOf("input_audio"))))
        assertFalse(missingAudioCounts.costIsAvailable)
        assertEquals(0.00016, missingAudioCounts.estimatedCostUsd, 1e-12)
    }

    @Test fun inconsistentCacheAudioBinsCannotBeAddedToAlreadyPricedCachedTokens() {
        val cachedWithoutAudio = record(ProviderChargeUsage(units = mapOf("cached_input_audio" to 10)),
            mapOf("cached_input_audio" to 0.00001))
        assertFalse(metricsFor(cachedWithoutAudio).costIsAvailable)
        val impossible = record(ProviderChargeUsage(units = mapOf("input_audio" to 40, "cached_input_audio" to 0)),
            mapOf("input_audio" to 0.00001))
        val metrics = metricsFor(impossible.copy(usage = impossible.usage.copy(cachedInputTokens = 90)))
        assertFalse(metrics.costIsAvailable)
        assertEquals(0.00016, metrics.estimatedCostUsd, 1e-12)
    }

    @Test fun unrelatedCostFieldsAndTokenPricedGeminiCodeDoNotInventAdditionalCharges() {
        val gemini = parse(UsageDialect.GEMINI_CONTENT, """{"cost":5,"promptTokenCount":100,
            "cachedContentTokenCount":0,"candidatesTokenCount":80,"thoughtsTokenCount":0}""",
            """{"outputs":[{"type":"code_execution_result"}]}""")
        assertNull(gemini.charges.reportedTotalUsd)
        assertTrue(metricsFor(record().copy(usage = gemini)).costIsAvailable)
        assertEquals(0.00026, metricsFor(record().copy(usage = gemini)).estimatedCostUsd, 1e-12)
    }

    @Test fun observedNativeToolsAndMediaMakeAnOtherwiseCompleteTokenEstimatePartial() {
        val usage = parse(UsageDialect.OPENAI_RESPONSES, """{"input_tokens":100,"output_tokens":80,
            "input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}""",
            """{"output":[{"type":"web_search_call"},{"type":"image_generation_call"},{"type":"code_interpreter_call"}]}""")
        val metrics = metricsFor(record().copy(usage = usage))
        assertEquals(0.00026, metrics.estimatedCostUsd, 1e-12)
        assertTrue(metrics.usageIsComplete)
        assertFalse(metrics.costIsAvailable)
        assertEquals(setOf("native web search", "image output", "code execution"), metrics.unpricedCostComponents)
    }

    @Test fun mediaCountersRequireTheirOwnRatesAndInvalidBinsCannotBecomeFree() {
        val item = record(ProviderChargeUsage(units = mapOf("output_image" to 20)))
        val metrics = metricsFor(item)
        assertEquals(0.00022, metrics.estimatedCostUsd, 1e-12)
        assertFalse(metrics.costIsAvailable)
        assertTrue("image output" in metrics.unpricedCostComponents)
        assertFalse(metricsFor(item.copy(usage = item.usage.copy(charges = item.usage.charges.copy(
            units = mapOf("output_image" to Long.MAX_VALUE))))).costIsAvailable)
        assertFalse(metricsFor(record(ProviderChargeUsage(units = mapOf("output_audio" to -1)))).costIsAvailable)
    }

    @Test fun malformedOrNegativeFeeFieldsAreRetainedAsUnavailable() {
        for (raw in listOf("-1", "{}", "true", "\"0.1\"")) {
            val charges = parse(UsageDialect.OPENAI_CHAT, """{"cost":$raw,"server_tool_use":{"web_search_requests":$raw}}""").charges
            assertTrue(charges.invalid)
            assertFalse(metricsFor(record(charges)).costIsAvailable)
        }
        assertTrue(parse(UsageDialect.OPENAI_CHAT, """{"server_tool_use":[]}""").charges.invalid)
        val byok = parse(UsageDialect.OPENAI_CHAT, """{"cost":0,"is_byok":true}""").charges
        assertFalse(metricsFor(record(byok)).costIsAvailable)
        assertTrue("external provider billing" in metricsFor(record(byok)).unpricedCostComponents)
    }

    @Test fun catalogPricesKeepPerTokenAndPerSearchUnitsDistinctFromPerImagePrices() {
        val catalog = linkedMapOf<String, ModelPricingSpec>()
        ModelsDevClient.parseOpenRouterModelsIntoCatalog("""{"data":[{"id":"vendor/model","pricing":{
            "prompt":"0.000001","completion":"0.000002","audio":"0.00001","audio_output":"0.00002",
            "input_audio_cache":"0","web_search":"0.01","request":"0.001","image_output":"0.04"}}]}""", catalog)
        val fees = ModelsDevClient.lookupSpecInCatalog(catalog, "vendor/model")!!.chargePricing
        assertEquals(0.00001, fees.perUnitUsd["input_audio"])
        assertEquals(0.0, fees.perUnitUsd["cached_input_audio"])
        assertEquals(0.01, fees.perUnitUsd["web_search"])
        assertNull(fees.perUnitUsd["output_image"])
        assertEquals(0.00126, metricsFor(record(fees = fees.perUnitUsd)).estimatedCostUsd, 1e-12)
        val merged = ModelsDevClient.mergeModelSpecs(ModelPricingSpec("model", chargePricing = ChargePricing(mapOf("web_search" to 0.0))),
            ModelPricingSpec("model", chargePricing = fees))
        assertEquals(0.0, merged.chargePricing.perUnitUsd["web_search"])
    }

    @Test fun unknownDirectMediaAttemptsPersistWithoutInventedTokenCountsOrFreePrices() {
        val ledger = ProviderUsageLedger(profile, "model")
        ledger.beginMedia("openai_audio_speech", "output_audio")
        ledger.beginMedia("image_generation", "output_image")
        val records = ledger.snapshots()
        val raw = Json.encodeToString(ApiUsageRecord.serializer(), records.last())
        assertEquals(records.last(), Json.decodeFromString(ApiUsageRecord.serializer(), raw))
        assertEquals(2, ledger.metrics().reportedRequests)
        assertFalse(ledger.metrics().costIsAvailable)
        assertTrue(records.all { it.usage.inputTokens == null })
        assertTrue("image output" in ledger.metrics().unpricedCostComponents)
    }
}
