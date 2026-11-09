package io.parchment.ai.network

import io.parchment.ai.model.ModelPricingSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelCatalogIdentityTest {
    @Test
    fun unknownNamesAndVariantsDoNotBorrowPrefixMetadata() {
        val known = ModelPricingSpec("custom", providerName = "Qwen", outputModalities = listOf("audio"))
        val catalog = mapOf("custom" to known)
        assertEquals(known, ModelsDevClient.lookupSpecInCatalog(catalog, "custom"))
        assertNull(ModelsDevClient.lookupSpecInCatalog(catalog, "Custom Proxy Name"))
        assertNull(ModelsDevClient.lookupSpecInCatalog(catalog, "custom-visual"))
    }

    @Test
    fun explicitAndCanonicalAliasesStillResolve() {
        val known = ModelPricingSpec("arbitrary-2-1", providerName = "Fixture")
        val spaced = ModelPricingSpec("explicit name", providerName = "Fixture")
        val catalog = mapOf("arbitrary-2-1" to known, "explicit name" to spaced)
        assertEquals(known, ModelsDevClient.lookupSpecInCatalog(catalog, "wrapper/arbitrary-2.1"))
        assertEquals(spaced, ModelsDevClient.lookupSpecInCatalog(catalog, "explicit name"))
    }

    @Test
    fun shorthandRequiresAUniqueModelAtADelimiterBoundary() {
        val first = ModelPricingSpec("arbitrary-root-fast", providerName = "First")
        val second = ModelPricingSpec("arbitrary-root-large", providerName = "Second")
        assertEquals(first, ModelsDevClient.lookupSpecInCatalog(mapOf(first.modelId to first), "arbitrary-root"))
        assertNull(ModelsDevClient.lookupSpecInCatalog(mapOf(first.modelId to first, second.modelId to second), "arbitrary-root"))
        assertNull(ModelsDevClient.lookupSpecInCatalog(mapOf("arbitrary-rooted" to first), "arbitrary-root"))
    }
}
