package io.parchment.ai.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.parchment.ai.model.ModelPricingSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fetches and caches model pricing ($ per 1M tokens for input, output, cache_read, cache_write, reasoning)
 * and context window / output token limits from https://models.dev/api.json.
 */
object ModelsDevClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 45_000L
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = 45_000L
        }
    }

    private var cachedCatalog: Map<String, ModelPricingSpec>? = null


    fun getCachedOutputModalities(modelId: String): List<String> {
        val catalog = cachedCatalog ?: return emptyList()
        val cleanTarget = modelId.trim().removePrefix("models/").lowercase()
        if (cleanTarget.isEmpty()) return emptyList()
        catalog[cleanTarget]?.let { return it.outputModalities }
        val withoutSlash = cleanTarget.substringAfter("/")
        catalog[withoutSlash]?.let { return it.outputModalities }
        return catalog.entries.firstOrNull { (k, _) ->
            k == withoutSlash || withoutSlash.startsWith(k) || k.startsWith(withoutSlash)
        }?.value?.outputModalities.orEmpty()
    }
    suspend fun fetchModelSpec(modelId: String, forceRefresh: Boolean = false): Result<ModelPricingSpec> = runCatching {
        val cleanTarget = modelId.trim().removePrefix("models/").lowercase()
        require(cleanTarget.isNotEmpty()) { "Enter or select a Model ID first." }

        val catalog = if (!forceRefresh && cachedCatalog != null) {
            cachedCatalog!!
        } else {
            val loaded = downloadAndParseModelsDevCatalog()
            cachedCatalog = loaded
            loaded
        }

        // 1. Exact match on model ID
        catalog[cleanTarget]?.let { return@runCatching it }

        // 2. Strip provider prefix if user entered "google/gemini-..." or "anthropic/claude-..."
        val withoutProviderSlash = cleanTarget.substringAfter("/")
        catalog[withoutProviderSlash]?.let { return@runCatching it }

        // 3. Prefix / substring match (e.g. matching 'claude-opus-4-6-2026...' or 'gemini-2.5-pro-preview')
        val bestMatch = catalog.entries.firstOrNull { (k, _) ->
            k == withoutProviderSlash ||
                withoutProviderSlash.startsWith(k) ||
                k.startsWith(withoutProviderSlash)
        }?.value ?: error("Model '$modelId' was not found in models.dev catalog. You can input its pricing and context window manually.")

        bestMatch.copy(modelId = modelId.trim())
    }

    private suspend fun downloadAndParseModelsDevCatalog(): Map<String, ModelPricingSpec> {
        val resp = httpClient.get("https://models.dev/api.json") {
            header("Accept", "application/json")
            header("User-Agent", "Parchment-AI-Studio")
        }
        if (!resp.status.isSuccess()) {
            error("models.dev HTTP ${resp.status.value}")
        }

        val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val map = LinkedHashMap<String, ModelPricingSpec>(2048)

        // Prioritize primary providers first so canonical specs win over third-party resellers
        val priorityOrder = listOf("google", "openai", "anthropic", "bytedance", "xai", "deepseek", "mistral")
        val allProviderKeys = (priorityOrder.filter { root.containsKey(it) } + root.keys.filterNot { it in priorityOrder })

        for (providerKey in allProviderKeys) {
            val providerObj = root[providerKey] as? JsonObject ?: continue
            val providerName = providerObj["name"]?.jsonPrimitive?.contentOrNull ?: providerKey
            val modelsObj = providerObj["models"] as? JsonObject ?: continue

            for ((modelKey, modelEl) in modelsObj) {
                val mObj = modelEl as? JsonObject ?: continue
                val id = (mObj["id"]?.jsonPrimitive?.contentOrNull ?: modelKey).trim()
                val name = mObj["name"]?.jsonPrimitive?.contentOrNull ?: id
                val costObj = mObj["cost"] as? JsonObject
                val limitObj = mObj["limit"] as? JsonObject

                val inputCost = costObj?.get("input")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val outputCost = costObj?.get("output")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val cacheReadCost = costObj?.get("cache_read")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val cacheWriteCost = costObj?.get("cache_write")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val reasoningCost = costObj?.get("reasoning")?.jsonPrimitive?.doubleOrNull
                    ?.takeIf { it > 0.0 } ?: outputCost

                val ctxLimit = limitObj?.get("context")?.jsonPrimitive?.intOrNull ?: 128000
                val outLimit = limitObj?.get("output")?.jsonPrimitive?.intOrNull ?: 32768
                val modalitiesObj = mObj["modalities"] as? JsonObject
                val outModalities = (modalitiesObj?.get("output") as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.lowercase() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()

                val spec = ModelPricingSpec(
                    modelId = id,
                    displayName = name,
                    providerName = providerName,
                    inputPricePerM = inputCost,
                    outputPricePerM = outputCost,
                    cacheReadPricePerM = cacheReadCost,
                    cacheWritePricePerM = cacheWriteCost,
                    reasoningPricePerM = reasoningCost,
                    contextWindowTokens = if (ctxLimit > 0) ctxLimit else 128000,
                    maxOutputTokens = if (outLimit > 0) outLimit else 32768,
                    outputModalities = outModalities
                )

                val normKey = id.removePrefix("models/").lowercase()
                if (!map.containsKey(normKey)) {
                    map[normKey] = spec
                }
                val shortKey = normKey.substringAfter("/")
                if (!map.containsKey(shortKey)) {
                    map[shortKey] = spec
                }
                val rawNorm = modelKey.removePrefix("models/").lowercase()
                if (!map.containsKey(rawNorm)) {
                    map[rawNorm] = spec
                }
            }
        }

        return map
    }
}
