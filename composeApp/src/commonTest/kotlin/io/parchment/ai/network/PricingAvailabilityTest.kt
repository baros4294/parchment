package io.parchment.ai.network

import io.parchment.ai.model.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class PricingAvailabilityTest {
    private fun usage(input: Long = 10, output: Long = 20) = ProviderTokenUsage(inputTokens = input, outputTokens = output,
        cachedInputTokens = 0, cacheCreationInputTokens = 0, shortCacheCreationInputTokens = 0, longCacheCreationInputTokens = 0,
        reasoningTokens = 0, isFinal = true)
    private fun profile() = ProviderProfile("profile", "Profile", ProviderProtocol.OPENAI_RESPONSES, defaultModel = "model")

    @Test fun explicitFreeRatesRemainDifferentFromMissingPrices() {
        val free = profile().copy(knownPricing = TokenPricing(inputPerM = 0.0, outputPerM = 0.0), pricingModelId = "model")
        val ledger = ProviderUsageLedger(free, "model")
        val first = ledger.begin(UsageDialect.OPENAI_RESPONSES)
        ledger.update(first.requestId, "response", UsageDialect.OPENAI_RESPONSES, usage())
        assertTrue(ledger.metrics().costIsAvailable)
        assertEquals(0.0, ledger.metrics().estimatedCostUsd)
        val unknown = ProviderUsageLedger(profile(), "model")
        val request = unknown.begin(UsageDialect.OPENAI_RESPONSES)
        unknown.update(request.requestId, "unknown", UsageDialect.OPENAI_RESPONSES, usage())
        assertFalse(unknown.metrics().costIsAvailable)
        val restored = Json.decodeFromString(ProviderProfile.serializer(), Json.encodeToString(ProviderProfile.serializer(), free))
        assertEquals(0.0, restored.tokenPricing().inputPerM)
        assertNull(free.tokenPricing("other-model").inputPerM)
    }

    @Test fun modelOverridesAndCapturedPricesDoNotChangeWithLaterSettings() {
        val original = profile().copy(inputPricePerM = 100.0, outputPricePerM = 100.0,
            customModelSpecs = mapOf("model" to ModelPricingSpec("model", knownPricing = TokenPricing(inputPerM = 1.0, outputPerM = 2.0))))
        val ledger = ProviderUsageLedger(original, "model")
        val request = ledger.begin(UsageDialect.OPENAI_RESPONSES)
        ledger.update(request.requestId, "response", UsageDialect.OPENAI_RESPONSES, usage())
        assertEquals(0.00005, ledger.metrics().estimatedCostUsd, 1e-12)
        val changed = original.copy(customModelSpecs = mapOf("model" to ModelPricingSpec("model", knownPricing = TokenPricing(inputPerM = 9.0, outputPerM = 9.0))))
        assertEquals(9.0, requestPricing(changed, "model").first.inputPerM)
        assertEquals(1.0, ledger.snapshots().single().pricing.inputPerM)
        assertEquals(0.00005, aggregateApiUsageRecords(ledger.snapshots()).estimatedCostUsd, 1e-12)
    }

    @Test fun perTokenUnitsAndTinyPositivePricesAreNotGuessedOrRoundedToFree() {
        val catalog = linkedMapOf<String, ModelPricingSpec>()
        ModelsDevClient.parseOpenRouterModelsIntoCatalog("""{"data":[{"id":"vendor/expensive","pricing":{"prompt":"0.1","completion":"0"}},{"id":"vendor/tiny","pricing":{"prompt":"0.000000000001"}}]}""", catalog)
        val expensive = ModelsDevClient.lookupSpecInCatalog(catalog, "vendor/expensive")!!
        assertEquals(100_000.0, expensive.knownPricing?.inputPerM)
        assertEquals(0.0, expensive.knownPricing?.outputPerM)
        assertNull(expensive.knownPricing?.cacheReadPerM)
        val tiny = ModelsDevClient.lookupSpecInCatalog(catalog, "vendor/tiny")!!
        assertEquals(0.000001, tiny.knownPricing!!.inputPerM!!, 1e-15)
        assertTrue(tiny.inputPricePerM > 0)
    }

    @Test fun modelRegistryPreservesZeroMissingAndContextTierBoundaries() {
        val catalog = linkedMapOf<String, ModelPricingSpec>()
        ModelsDevClient.parseModelsDevIntoCatalog("""{"vendor":{"models":{"model":{"id":"vendor/model","cost":{"input":1,"output":2,"cache_read":0,"context_over_100k":{"input":3,"output":4}}},"free":{"id":"vendor/free","cost":{"input":0,"output":0}},"unknown":{"id":"vendor/unknown"}}}}""", catalog)
        val spec = ModelsDevClient.lookupSpecInCatalog(catalog, "vendor/model")!!
        assertEquals(0.0, spec.knownPricing?.cacheReadPerM)
        assertNull(spec.knownPricing?.cacheWritePerM)
        assertEquals(100_000L, spec.contextPricingTiers.single().inputTokensAbove)
        val record = ApiUsageRecord("request", wireDialect = "openai_responses", profileId = "profile", model = spec.modelId,
            pricing = spec.knownPricing!!, contextPricingTiers = spec.contextPricingTiers, usage = usage(input = 100_000))
        assertEquals(1.0, recordPricing(record).inputPerM)
        assertEquals(3.0, recordPricing(record.copy(usage = usage(input = 100_001))).inputPerM)
        assertEquals(0.0, ModelsDevClient.lookupSpecInCatalog(catalog, "vendor/free")?.knownPricing?.inputPerM)
        assertNull(ModelsDevClient.lookupSpecInCatalog(catalog, "vendor/unknown")?.knownPricing)
    }

    @Test fun laterRegistryMergeCannotOverwriteAnExplicitFreePrice() {
        val existing = ModelPricingSpec("model", knownPricing = TokenPricing(inputPerM = 0.0, outputPerM = 0.0))
        val incoming = ModelPricingSpec("model", inputPricePerM = 2.0, outputPricePerM = 3.0,
            knownPricing = TokenPricing(inputPerM = 2.0, outputPerM = 3.0))
        val merged = ModelsDevClient.mergeModelSpecs(existing, incoming)
        assertEquals(0.0, merged.knownPricing?.inputPerM)
        assertEquals(0.0, merged.inputPricePerM)
        assertEquals(0.0, merged.outputPricePerM)
    }

    @Test fun liteLlmLongLifetimeAndContextTierRatesAreCapturedSeparately() {
        val catalog = linkedMapOf<String, ModelPricingSpec>()
        ModelsDevClient.parseLiteLlmModelsIntoCatalog("""{"vendor/model":{"input_cost_per_token":0.000001,"output_cost_per_token":0.000002,"cache_creation_input_token_cost":0.00000125,"cache_creation_input_token_cost_above_1hr":0.000002,"input_cost_per_token_above_200k_tokens":0.000003,"cache_creation_input_token_cost_above_1hr_above_200k_tokens":0.000006}}""", catalog)
        val spec = ModelsDevClient.lookupSpecInCatalog(catalog, "vendor/model")!!
        assertEquals(2.0, spec.knownPricing?.longCacheWritePerM)
        assertEquals(3.0, spec.contextPricingTiers.single().pricing.inputPerM)
        assertEquals(6.0, spec.contextPricingTiers.single().pricing.longCacheWritePerM)
    }

    @Test fun anExplicitLongWriteRateUsesTheReportedLifetimeCounts() {
        val record = ApiUsageRecord("write", wireDialect = "openai_responses", profileId = "profile", model = "model",
            pricing = TokenPricing(inputPerM = 0.0, outputPerM = 0.0, cacheWritePerM = 3.0, longCacheWritePerM = 10.0),
            usage = usage(input = 2, output = 0).copy(cacheCreationInputTokens = 2, shortCacheCreationInputTokens = 1, longCacheCreationInputTokens = 1))
        assertEquals(0.000013, aggregateApiUsageRecords(listOf(record)).estimatedCostUsd, 1e-12)
    }

    @Test fun editorKeepsBlankUnknownAndZeroFreeAndRejectsInvalidRates() {
        assertNull(parsePriceText(""))
        assertEquals(0.0, parsePriceText("0"))
        assertEquals(0.25, parsePriceText("0.25"))
        listOf("-1", "NaN", "Infinity", "not a price").forEach { assertNull(parsePriceText(it)) }
    }

    @Test fun vercelPricingKeepsFreeFieldsAndRejectsMalformedNumbers() {
        val catalog = linkedMapOf<String, ModelPricingSpec>()
        ModelsDevClient.parseVercelModelsIntoCatalog("""{"data":[{"id":"vendor/model","pricing":{"input":"0","output":"0.000002","input_cache_read":{},"input_cache_write":"-1"}}]}""", catalog)
        val spec = ModelsDevClient.lookupSpecInCatalog(catalog, "vendor/model")!!
        assertEquals(0.0, spec.knownPricing?.inputPerM)
        assertEquals(2.0, spec.knownPricing?.outputPerM)
        assertNull(spec.knownPricing?.cacheReadPerM)
        assertNull(spec.knownPricing?.cacheWritePerM)
    }
}
