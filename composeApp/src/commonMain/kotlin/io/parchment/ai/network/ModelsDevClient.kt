package io.parchment.ai.network

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.parchment.ai.model.ModelPricingSpec
import io.parchment.ai.model.TokenPricing
import io.parchment.ai.model.ContextPricingTier
import io.parchment.ai.model.ChargePricing
import io.parchment.ai.model.legacyTokenPricing
import io.parchment.ai.platform.PlatformRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Multi-Source Unified Model Registry Client.
 *
 * Fetches and merges live model pricing, context window limits, creator/vendor metadata,
 * and input/output modalities (`text`, `image`, `video`, `audio`) from multiple complementary
 * zero-auth public registries:
 * 1. OpenRouter Multimodal API (`https://openrouter.ai/api/v1/models?output_modalities=all`)
 * 2. OpenRouter Video API (`https://openrouter.ai/api/v1/videos/models`)
 * 3. Vercel AI Gateway Catalog (`https://ai-gateway.vercel.sh/v1/models`)
 * 4. AIMLAPI Multimodal & Video Catalog (`https://api.aimlapi.com/models`)
 * 5. LiteLLM Official Registry (`https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json`)
 * 6. Fallback `models.dev` (`https://models.dev/api.json`)
 */
object ModelsDevClient {
    private const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models?output_modalities=all"
    private const val OPENROUTER_VIDEOS_URL = "https://openrouter.ai/api/v1/videos/models"
    private const val VERCEL_MODELS_URL = "https://ai-gateway.vercel.sh/v1/models"
    private const val AIMLAPI_MODELS_URL = "https://api.aimlapi.com/models"
    private const val LITELLM_PRICES_URL = "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"
    private const val MODELS_DEV_API_URL = "https://models.dev/api.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val fetchMutex = Mutex()

    @Volatile
    private var cachedCatalog: Map<String, ModelPricingSpec> = emptyMap()

    @Volatile
    private var lastFetchEpochMs: Long = 0L

    private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
    private val CONTEXT_TIER_PATTERN = Regex("context_over_([0-9]+)([km]?)")
    private val LITELLM_CONTEXT_TIER_PATTERN = Regex("(input_cost_per_token|output_cost_per_token|cache_read_input_token_cost|cache_creation_input_token_cost|cache_creation_input_token_cost_above_(?:1|12)hr)_above_([0-9]+)([km]?)_tokens")

    private val TRAILING_SNAPSHOT_REGEX = Regex(
        "-(?:20\\d{6}|\\d{6}|20\\d{2}-\\d{2}-\\d{2}|00\\d|latest|preview)$"
    )
    private val VERSION_DOT_BETWEEN_DIGITS_REGEX = Regex("(?<=\\d)\\.(?=\\d)")
    private val VERSION_V_PREFIX_REGEX = Regex("(?<=-)v(?=\\d)")
    private val WRAPPER_PREFIX_REGEX = Regex("^(?:doubao-|dola-|dreamina-)")

    private val AGGREGATOR_PROVIDER_NAMES = setOf(
        "openrouter", "vercel", "aimlapi", "litellm", "fal_ai", "fal-ai",
        "together_ai", "fireworks_ai", "deepinfra", "replicate", "bedrock", "azure", "vertex_ai"
    )

    /**
     * Normalizes a model identifier into a canonical vendor-stripped, version-normalized slug
     * so `bytedance-seed/seedream-5-0-flash`, `bytedance/seedream-5.0-flash`, and
     * `volcengine/doubao-seedream-5-0-260128` all converge to `seedream-5-0-flash`.
     */
    internal fun normalizeCanonicalModelSlug(raw: String): String {
        var s = raw.trim().lowercase().removePrefix("models/")
        if (s.contains('/')) {
            s = s.substringAfterLast('/')
        }
        if (s.contains(':')) {
            s = s.substringBefore(':')
        }
        s = WRAPPER_PREFIX_REGEX.replace(s, "")
        s = TRAILING_SNAPSHOT_REGEX.replace(s, "")
        s = VERSION_V_PREFIX_REGEX.replace(s, "")
        s = VERSION_DOT_BETWEEN_DIGITS_REGEX.replace(s, "-")
        return s.trim('-', '_', '.', ' ')
    }

    /**
     * Merges two [ModelPricingSpec] entries for the same model across registries while
     * strictly preserving non-text output modalities (`image`, `video`, `audio`) so a
     * generic `["text"]` fallback never overwrites a media modality.
     */
    internal fun mergeModelSpecs(
        existing: ModelPricingSpec,
        incoming: ModelPricingSpec
    ): ModelPricingSpec {
        val mergedInputs = (existing.inputModalities + incoming.inputModalities)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

        val existingNonTextOutputs = existing.outputModalities.filter { it != "text" }
        val incomingNonTextOutputs = incoming.outputModalities.filter { it != "text" }

        val mergedOutputs = when {
            existingNonTextOutputs.isNotEmpty() && incomingNonTextOutputs.isEmpty() ->
                existing.outputModalities
            incomingNonTextOutputs.isNotEmpty() && existingNonTextOutputs.isEmpty() ->
                incoming.outputModalities
            existingNonTextOutputs.isNotEmpty() && incomingNonTextOutputs.isNotEmpty() -> {
                val combined = (existing.outputModalities + incoming.outputModalities).distinct()
                // Only keep "text" alongside media if at least one source explicitly reported interleaved text+media
                val hasInterleavedText = (existing.outputModalities.contains("text") && existingNonTextOutputs.isNotEmpty()) ||
                    (incoming.outputModalities.contains("text") && incomingNonTextOutputs.isNotEmpty())
                if (hasInterleavedText) combined else combined.filter { it != "text" }
            }
            else -> (existing.outputModalities + incoming.outputModalities).distinct()
        }

        val isPureMedia = mergedOutputs.any { it == "image" || it == "video" || it == "audio" } &&
            !mergedOutputs.contains("text")

        val bestContextWindow = when {
            isPureMedia -> maxOf(
                if (existing.contextWindowTokens == 128000) 0 else existing.contextWindowTokens,
                if (incoming.contextWindowTokens == 128000) 0 else incoming.contextWindowTokens
            )
            existing.contextWindowTokens > 0 && existing.contextWindowTokens != 128000 ->
                existing.contextWindowTokens
            incoming.contextWindowTokens > 0 && incoming.contextWindowTokens != 128000 ->
                incoming.contextWindowTokens
            else -> maxOf(existing.contextWindowTokens, incoming.contextWindowTokens)
        }

        val bestMaxOutput = when {
            (existing.maxOutputTokens ?: 0) > 0 -> existing.maxOutputTokens
            (incoming.maxOutputTokens ?: 0) > 0 -> incoming.maxOutputTokens
            else -> existing.maxOutputTokens ?: incoming.maxOutputTokens
        }

        val bestProviderName = when {
            existing.providerName.isNotBlank() &&
                existing.providerName.lowercase() !in AGGREGATOR_PROVIDER_NAMES -> existing.providerName
            incoming.providerName.isNotBlank() &&
                incoming.providerName.lowercase() !in AGGREGATOR_PROVIDER_NAMES -> incoming.providerName
            existing.providerName.isNotBlank() -> existing.providerName
            else -> incoming.providerName
        }

        val mergedPricing = if (existing.knownPricing != null || incoming.knownPricing != null) {
            (existing.knownPricing ?: legacyTokenPricing(existing.inputPricePerM, existing.outputPricePerM, existing.cacheReadPricePerM,
                existing.cacheWritePricePerM, existing.reasoningPricePerM)).withMissingFrom(incoming.knownPricing
                ?: legacyTokenPricing(incoming.inputPricePerM, incoming.outputPricePerM, incoming.cacheReadPricePerM,
                    incoming.cacheWritePricePerM, incoming.reasoningPricePerM))
        } else null
        return existing.copy(
            displayName = existing.displayName.ifBlank { incoming.displayName },
            providerName = bestProviderName,
            inputPricePerM = mergedPricing?.inputPerM ?: if (existing.inputPricePerM > 0.0) existing.inputPricePerM else incoming.inputPricePerM,
            outputPricePerM = mergedPricing?.outputPerM ?: if (existing.outputPricePerM > 0.0) existing.outputPricePerM else incoming.outputPricePerM,
            cacheReadPricePerM = mergedPricing?.cacheReadPerM ?: if (existing.cacheReadPricePerM > 0.0) existing.cacheReadPricePerM else incoming.cacheReadPricePerM,
            cacheWritePricePerM = mergedPricing?.cacheWritePerM ?: if (existing.cacheWritePricePerM > 0.0) existing.cacheWritePricePerM else incoming.cacheWritePricePerM,
            reasoningPricePerM = mergedPricing?.reasoningPerM ?: if (existing.reasoningPricePerM > 0.0) existing.reasoningPricePerM else incoming.reasoningPricePerM,
            knownPricing = mergedPricing,
            contextPricingTiers = (existing.contextPricingTiers + incoming.contextPricingTiers).distinctBy { it.inputTokensAbove },
            chargePricing = existing.chargePricing.withMissingFrom(incoming.chargePricing),
            contextWindowTokens = bestContextWindow,
            maxOutputTokens = bestMaxOutput,
            outputModalities = mergedOutputs,
            inputModalities = mergedInputs
        )
    }

    internal fun indexModelSpec(
        catalog: MutableMap<String, ModelPricingSpec>,
        rawId: String,
        spec: ModelPricingSpec,
        aliases: List<String> = emptyList()
    ) {
        val cleanId = rawId.trim().lowercase().removePrefix("models/")
        if (cleanId.isEmpty()) return
        val shortKey = cleanId.substringAfterLast('/')
        val canonicalKey = normalizeCanonicalModelSlug(cleanId)
        val dotVariantKey = canonicalKey.replace(Regex("(?<=\\d)-(?=\\d)"), ".")

        val keys = buildList {
            add(cleanId)
            if (shortKey.isNotEmpty()) add(shortKey)
            if (canonicalKey.isNotEmpty()) add(canonicalKey)
            if (dotVariantKey.isNotEmpty()) add(dotVariantKey)
            for (alias in aliases) {
                val cleanAlias = alias.trim().lowercase().removePrefix("models/")
                if (cleanAlias.isNotEmpty()) {
                    add(cleanAlias)
                    add(cleanAlias.substringAfterLast('/'))
                    val normAlias = normalizeCanonicalModelSlug(cleanAlias)
                    if (normAlias.isNotEmpty()) add(normAlias)
                }
            }
        }.distinct()

        for (key in keys) {
            val prev = catalog[key]
            catalog[key] = if (prev == null) spec else mergeModelSpecs(prev, spec)
        }
    }

    internal fun lookupSpecInCatalog(
        catalog: Map<String, ModelPricingSpec>,
        modelId: String
    ): ModelPricingSpec? {
        if (catalog.isEmpty()) return null
        val cleanTarget = modelId.trim().lowercase().removePrefix("models/")
        if (cleanTarget.isEmpty()) return null
        val withoutSlash = cleanTarget.substringAfterLast('/')
        val canonical = normalizeCanonicalModelSlug(cleanTarget)
        val dotCanonical = canonical.replace(Regex("(?<=\\d)-(?=\\d)"), ".")

        catalog[cleanTarget]?.let { return it }
        catalog[withoutSlash]?.let { return it }
        catalog[canonical]?.let { return it }
        catalog[dotCanonical]?.let { return it }

        // A shortened identifier may resolve only to one model at a delimiter boundary.
        // Never let an unknown longer name inherit metadata from a shorter catalog key.
        val candidates = catalog.entries.filter { (key, _) ->
            listOf(withoutSlash, canonical).any { target ->
                target.length >= 6 && key.startsWith(target) &&
                    key.getOrNull(target.length) in listOf('-', '_', '.')
            }
        }.map { it.value }.distinctBy { normalizeCanonicalModelSlug(it.modelId) }
        return candidates.singleOrNull()
    }

    /**
     * Returns the cached `outputModalities` (`text`, `image`, `video`, `audio`) for [modelId]
     * if the registry has already been fetched during this session, or an empty list otherwise.
     */
    fun getCachedOutputModalities(modelId: String): List<String> {
        return lookupSpecInCatalog(cachedCatalog, modelId)?.outputModalities.orEmpty()
    }

    internal fun getCachedSpec(modelId: String): ModelPricingSpec? = lookupSpecInCatalog(cachedCatalog, modelId)

    /**
     * Returns the cached `inputModalities` for [modelId] if already loaded.
     */
    fun getCachedInputModalities(modelId: String): List<String> {
        return lookupSpecInCatalog(cachedCatalog, modelId)?.inputModalities.orEmpty()
    }

    /**
     * Returns the cached vendor/creator name for [modelId] if known in the registry.
     */
    fun getCachedVendorSlug(modelId: String): String? {
        val spec = lookupSpecInCatalog(cachedCatalog, modelId) ?: return null
        return spec.providerName.takeIf { it.isNotBlank() }
    }

    /**
     * Ensures the registry catalog is loaded (warming it with a bounded timeout if empty)
     * and returns the output modalities for [modelId].
     */
    suspend fun resolveOutputModalities(modelId: String): List<String> {
        getCachedOutputModalities(modelId).takeIf { it.isNotEmpty() }?.let { return it }
        return withTimeoutOrNull(6_500L) {
            fetchModelSpec(modelId).getOrNull()?.outputModalities
        }.orEmpty()
    }

    suspend fun fetchModelSpec(modelId: String): Result<ModelPricingSpec> = withContext(Dispatchers.Default) {
        runCatching {
            val cleanTarget = modelId.trim().lowercase().removePrefix("models/")
            require(cleanTarget.isNotBlank()) { "Model ID is empty." }

            val now = PlatformRuntime.currentTimeMillis()
            val catalog = if (cachedCatalog.isNotEmpty() && (now - lastFetchEpochMs) < CACHE_TTL_MS) {
                cachedCatalog
            } else {
                fetchEntireCatalog()
            }

            val matched = lookupSpecInCatalog(catalog, cleanTarget)
                ?: error("Model '$modelId' was not found in the unified model registry.")

            matched.copy(modelId = modelId.trim())
        }
    }

    private suspend fun fetchEntireCatalog(): Map<String, ModelPricingSpec> = fetchMutex.withLock {
        val now = PlatformRuntime.currentTimeMillis()
        if (cachedCatalog.isNotEmpty() && (now - lastFetchEpochMs) < CACHE_TTL_MS) {
            return@withLock cachedCatalog
        }

        val httpClient = AiProviderEngine.httpClient
        val catalog = mutableMapOf<String, ModelPricingSpec>()

        supervisorScope {
            val orAllDeferred = async {
                withTimeoutOrNull(15_000L) {
                    runCatching {
                        val resp = httpClient.get(OPENROUTER_MODELS_URL)
                        if (resp.status.isSuccess()) resp.bodyAsText() else null
                    }.getOrNull()
                }
            }
            val orVideosDeferred = async {
                withTimeoutOrNull(12_000L) {
                    runCatching {
                        val resp = httpClient.get(OPENROUTER_VIDEOS_URL)
                        if (resp.status.isSuccess()) resp.bodyAsText() else null
                    }.getOrNull()
                }
            }
            val vercelDeferred = async {
                withTimeoutOrNull(12_000L) {
                    runCatching {
                        val resp = httpClient.get(VERCEL_MODELS_URL)
                        if (resp.status.isSuccess()) resp.bodyAsText() else null
                    }.getOrNull()
                }
            }
            val aimlDeferred = async {
                withTimeoutOrNull(15_000L) {
                    runCatching {
                        val resp = httpClient.get(AIMLAPI_MODELS_URL)
                        if (resp.status.isSuccess()) resp.bodyAsText() else null
                    }.getOrNull()
                }
            }
            val liteLlmDeferred = async {
                withTimeoutOrNull(18_000L) {
                    runCatching {
                        val resp = httpClient.get(LITELLM_PRICES_URL)
                        if (resp.status.isSuccess()) resp.bodyAsText() else null
                    }.getOrNull()
                }
            }

            // Parse in order of schema richness & multimodal accuracy
            orAllDeferred.await()?.let { raw ->
                runCatching { parseOpenRouterModelsIntoCatalog(raw, catalog, forceVideoOutput = false) }
            }
            orVideosDeferred.await()?.let { raw ->
                runCatching { parseOpenRouterModelsIntoCatalog(raw, catalog, forceVideoOutput = true) }
            }
            vercelDeferred.await()?.let { raw ->
                runCatching { parseVercelModelsIntoCatalog(raw, catalog) }
            }
            aimlDeferred.await()?.let { raw ->
                runCatching { parseAimlApiModelsIntoCatalog(raw, catalog) }
            }
            liteLlmDeferred.await()?.let { raw ->
                runCatching { parseLiteLlmModelsIntoCatalog(raw, catalog) }
            }

            // Fallback to models.dev if primary sources failed or to fill niche gaps
            if (catalog.isEmpty()) {
                withTimeoutOrNull(15_000L) {
                    runCatching {
                        val resp = httpClient.get(MODELS_DEV_API_URL)
                        if (resp.status.isSuccess()) {
                            parseModelsDevIntoCatalog(resp.bodyAsText(), catalog)
                        }
                    }
                }
            }
        }

        if (catalog.isNotEmpty()) {
            cachedCatalog = catalog
            lastFetchEpochMs = PlatformRuntime.currentTimeMillis()
        }
        catalog
    }

    internal fun parseOpenRouterModelsIntoCatalog(
        rawJson: String,
        catalog: MutableMap<String, ModelPricingSpec>,
        forceVideoOutput: Boolean = false
    ) {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return
        val dataArr = root["data"] as? JsonArray ?: return
        for (item in dataArr) {
            val obj = item as? JsonObject ?: continue
            val rawId = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (rawId.isEmpty()) continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: rawId
            val vendorFromId = rawId.substringBefore('/', "").trim()

            val archObj = obj["architecture"] as? JsonObject
            val modalityStr = archObj?.get("modality")?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val inMods = parseStringList(archObj?.get("input_modalities") ?: obj["input_modalities"]).ifEmpty {
                if (modalityStr.contains("->")) {
                    modalityStr.substringBefore("->").split('+').map { it.trim() }.filter { it.isNotEmpty() }
                } else emptyList()
            }
            val outMods = if (forceVideoOutput) {
                listOf("video")
            } else {
                parseStringList(archObj?.get("output_modalities") ?: obj["output_modalities"]).ifEmpty {
                    when {
                        modalityStr.endsWith("->video") -> listOf("video")
                        modalityStr.endsWith("->image") -> listOf("image")
                        modalityStr.endsWith("->audio") || modalityStr.endsWith("->speech") -> listOf("audio")
                        modalityStr.contains("->") ->
                            modalityStr.substringAfter("->").split('+').map { it.trim() }.filter { it.isNotEmpty() }
                        else -> emptyList()
                    }
                }
            }

            val contextLen = obj["context_length"]?.jsonPrimitive?.intOrNull ?: 0
            val topProv = obj["top_provider"] as? JsonObject
            val maxCompletion = topProv?.get("max_completion_tokens")?.jsonPrimitive?.intOrNull

            val pricingObj = obj["pricing"] as? JsonObject
            val inputPricePerM = parsePerTokenToPerMillion(pricingObj?.get("prompt"))
            val outputPricePerM = parsePerTokenToPerMillion(pricingObj?.get("completion"))
            val cacheReadPerM = parsePerTokenToPerMillion(pricingObj?.get("input_cache_read"))
            val cacheWritePerM = parsePerTokenToPerMillion(pricingObj?.get("input_cache_write"))
            val reasoningPerM = parsePerTokenToPerMillion(pricingObj?.get("internal_reasoning"))

            val spec = ModelPricingSpec(
                modelId = rawId,
                displayName = name,
                providerName = vendorFromId,
                inputPricePerM = inputPricePerM,
                outputPricePerM = outputPricePerM,
                cacheReadPricePerM = cacheReadPerM,
                cacheWritePricePerM = cacheWritePerM,
                reasoningPricePerM = reasoningPerM,
                knownPricing = pricing(pricingObj, "prompt", "completion", "input_cache_read", "input_cache_write", "internal_reasoning", 1_000_000.0),
                chargePricing = ChargePricing(buildMap {
                    readRate(pricingObj?.get("web_search"))?.let { put("web_search", it) }
                    readRate(pricingObj?.get("request"))?.let { put("request", it) }
                    readRate(pricingObj?.get("audio"))?.let { put("input_audio", it) }
                    readRate(pricingObj?.get("audio_output"))?.let { put("output_audio", it) }
                    readRate(pricingObj?.get("input_audio_cache"))?.let { put("cached_input_audio", it) }
                    readRate(pricingObj?.get("image"))?.let { put("input_image_count", it) }
                }),
                contextWindowTokens = contextLen,
                maxOutputTokens = maxCompletion,
                outputModalities = outMods,
                inputModalities = inMods
            )
            indexModelSpec(catalog, rawId, spec)
        }
    }

    internal fun parseVercelModelsIntoCatalog(
        rawJson: String,
        catalog: MutableMap<String, ModelPricingSpec>
    ) {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return
        val dataArr = root["data"] as? JsonArray ?: return
        for (item in dataArr) {
            val obj = item as? JsonObject ?: continue
            val rawId = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (rawId.isEmpty()) continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: rawId
            val ownedBy = obj["owned_by"]?.jsonPrimitive?.contentOrNull
                ?: rawId.substringBefore('/', "")
            val typeStr = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()

            val modalitiesObj = obj["modalities"] as? JsonObject
            val inMods = parseStringList(modalitiesObj?.get("input"))
            val outMods = parseStringList(modalitiesObj?.get("output")).ifEmpty {
                when (typeStr) {
                    "image" -> listOf("image")
                    "video" -> listOf("video")
                    "audio", "speech", "tts" -> listOf("audio")
                    "language", "chat" -> listOf("text")
                    else -> emptyList()
                }
            }

            val contextWin = obj["context_window"]?.jsonPrimitive?.intOrNull ?: 0
            val maxTokens = obj["max_tokens"]?.jsonPrimitive?.intOrNull
            val pricingObj = obj["pricing"] as? JsonObject
            val inputPricePerM = parsePerTokenToPerMillion(pricingObj?.get("input"))
            val outputPricePerM = parsePerTokenToPerMillion(pricingObj?.get("output"))
            val cacheReadPerM = parsePerTokenToPerMillion(pricingObj?.get("input_cache_read"))
            val cacheWritePerM = parsePerTokenToPerMillion(pricingObj?.get("input_cache_write"))

            val spec = ModelPricingSpec(
                modelId = rawId,
                displayName = name,
                providerName = ownedBy,
                inputPricePerM = inputPricePerM,
                outputPricePerM = outputPricePerM,
                cacheReadPricePerM = cacheReadPerM,
                cacheWritePricePerM = cacheWritePerM,
                knownPricing = pricing(pricingObj, "input", "output", "input_cache_read", "input_cache_write", multiplier = 1_000_000.0),
                contextWindowTokens = contextWin,
                maxOutputTokens = maxTokens,
                outputModalities = outMods,
                inputModalities = inMods
            )
            indexModelSpec(catalog, rawId, spec)
        }
    }

    internal fun parseAimlApiModelsIntoCatalog(
        rawJson: String,
        catalog: MutableMap<String, ModelPricingSpec>
    ) {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return
        val dataArr = root["data"] as? JsonArray ?: return
        for (item in dataArr) {
            val obj = item as? JsonObject ?: continue
            val rawId = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (rawId.isEmpty()) continue
            val typeStr = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val tags = parseStringList(obj["tags"])
            val aliases = parseStringList(obj["aliases"])
            val infoObj = obj["info"] as? JsonObject
            val name = infoObj?.get("name")?.jsonPrimitive?.contentOrNull ?: rawId
            val developer = infoObj?.get("developer")?.jsonPrimitive?.contentOrNull
                ?: rawId.substringBefore('/', "")
            val contextLen = infoObj?.get("contextLength")?.jsonPrimitive?.intOrNull ?: 0
            val maxTokens = infoObj?.get("maxTokens")?.jsonPrimitive?.intOrNull

            val outMods = when {
                typeStr.contains("video") || tags.any { it.contains("video") } -> listOf("video")
                typeStr.contains("image") || tags.any { it.contains("image") } -> listOf("image")
                typeStr.contains("tts") || typeStr.contains("speech") ||
                    typeStr.contains("audio") || typeStr.contains("music") -> listOf("audio")
                typeStr.contains("chat") || typeStr.contains("completion") ||
                    typeStr.contains("response") || tags.any { it.contains("chat") } -> listOf("text")
                else -> emptyList()
            }

            val spec = ModelPricingSpec(
                modelId = rawId,
                displayName = name,
                providerName = developer,
                contextWindowTokens = contextLen,
                maxOutputTokens = maxTokens,
                outputModalities = outMods
            )
            indexModelSpec(catalog, rawId, spec, aliases = aliases)
        }
    }

    internal fun parseLiteLlmModelsIntoCatalog(
        rawJson: String,
        catalog: MutableMap<String, ModelPricingSpec>
    ) {
        val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return
        for ((modelKey, element) in root) {
            if (modelKey == "sample_spec") continue
            val obj = element as? JsonObject ?: continue
            val mode = obj["mode"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val provider = obj["litellm_provider"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val supportsVision = obj["supports_vision"]?.jsonPrimitive?.booleanOrNull == true
            val supportsAudioIn = obj["supports_audio_input"]?.jsonPrimitive?.booleanOrNull == true
            val supportsAudioOut = obj["supports_audio_output"]?.jsonPrimitive?.booleanOrNull == true
            val supportsPdf = obj["supports_pdf_input"]?.jsonPrimitive?.booleanOrNull == true

            val inMods = buildList {
                add("text")
                if (supportsVision || mode == "image_edit") add("image")
                if (supportsAudioIn || mode == "audio_transcription") add("audio")
                if (supportsPdf) add("pdf")
            }

            val outMods = when (mode) {
                "image_generation", "image_edit" -> listOf("image")
                "video_generation" -> listOf("video")
                "audio_speech" -> listOf("audio")
                "chat", "completion", "responses" ->
                    if (supportsAudioOut) listOf("text", "audio") else listOf("text")
                else -> emptyList()
            }

            val maxInput = obj["max_input_tokens"]?.jsonPrimitive?.intOrNull
                ?: obj["max_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val maxOutput = obj["max_output_tokens"]?.jsonPrimitive?.intOrNull
                ?: obj["max_tokens"]?.jsonPrimitive?.intOrNull

            val inputCostPerM = parsePerTokenToPerMillion(obj["input_cost_per_token"])
            val outputCostPerM = parsePerTokenToPerMillion(obj["output_cost_per_token"])
            val cacheReadPerM = parsePerTokenToPerMillion(obj["cache_read_input_token_cost"])
            val cacheWritePerM = parsePerTokenToPerMillion(obj["cache_creation_input_token_cost"])

            val spec = ModelPricingSpec(
                modelId = modelKey,
                displayName = modelKey.substringAfterLast('/'),
                providerName = provider,
                inputPricePerM = inputCostPerM,
                outputPricePerM = outputCostPerM,
                cacheReadPricePerM = cacheReadPerM,
                cacheWritePricePerM = cacheWritePerM,
                knownPricing = pricing(obj, "input_cost_per_token", "output_cost_per_token", "cache_read_input_token_cost", "cache_creation_input_token_cost", multiplier = 1_000_000.0),
                contextPricingTiers = liteLlmContextTiers(obj),
                contextWindowTokens = maxInput,
                maxOutputTokens = maxOutput,
                outputModalities = outMods,
                inputModalities = inMods
            )
            indexModelSpec(catalog, modelKey, spec)
        }
    }

    internal fun parseModelsDevIntoCatalog(
        rawJson: String,
        catalog: MutableMap<String, ModelPricingSpec>
    ) {
        val rootElement = json.parseToJsonElement(rawJson)
        val rootObj = rootElement as? JsonObject ?: return

        for ((providerKey, providerVal) in rootObj) {
            val providerObj = providerVal as? JsonObject ?: continue
            val providerName = providerObj["name"]?.jsonPrimitive?.contentOrNull ?: providerKey
            val modelsObj = providerObj["models"] as? JsonObject ?: continue

            for ((modelKey, modelVal) in modelsObj) {
                val mObj = modelVal as? JsonObject ?: continue
                val id = mObj["id"]?.jsonPrimitive?.contentOrNull ?: modelKey
                val name = mObj["name"]?.jsonPrimitive?.contentOrNull ?: id

                val costObj = mObj["cost"] as? JsonObject
                val inputCost = readRate(costObj?.get("input")) ?: 0.0
                val outputCost = readRate(costObj?.get("output")) ?: 0.0
                val cacheReadCost = readRate(costObj?.get("cache_read")) ?: 0.0
                val cacheWriteCost = readRate(costObj?.get("cache_write")) ?: 0.0
                val reasoningCost = readRate(costObj?.get("reasoning")) ?: 0.0

                val limitObj = mObj["limit"] as? JsonObject
                val contextWindow = limitObj?.get("context")?.jsonPrimitive?.intOrNull ?: 0
                val maxOutput = limitObj?.get("output")?.jsonPrimitive?.intOrNull

                val modalitiesObj = mObj["modalities"] as? JsonObject
                val inModalities = parseStringList(modalitiesObj?.get("input"))
                val outModalities = parseStringList(modalitiesObj?.get("output"))

                val spec = ModelPricingSpec(
                    modelId = id,
                    displayName = name,
                    providerName = providerName,
                    inputPricePerM = inputCost,
                    outputPricePerM = outputCost,
                    cacheReadPricePerM = cacheReadCost,
                    cacheWritePricePerM = cacheWriteCost,
                    reasoningPricePerM = reasoningCost,
                    knownPricing = pricing(costObj, "input", "output", "cache_read", "cache_write"),
                    chargePricing = ChargePricing(buildMap {
                        readRate(costObj?.get("input_audio"), 0.000001)?.let { put("input_audio", it) }
                        readRate(costObj?.get("output_audio"), 0.000001)?.let { put("output_audio", it) }
                    }),
                    contextPricingTiers = contextTiers(costObj),
                    contextWindowTokens = contextWindow,
                    maxOutputTokens = maxOutput,
                    outputModalities = outModalities,
                    inputModalities = inModalities
                )
                indexModelSpec(catalog, id, spec, aliases = listOf(modelKey))
            }
        }
    }

    private fun parseStringList(element: JsonElement?): List<String> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun readRate(element: JsonElement?, multiplier: Double = 1.0): Double? {
        val primitive = element as? kotlinx.serialization.json.JsonPrimitive ?: return null
        val raw = primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull() ?: return null
        return (raw * multiplier).takeIf { raw >= 0 && it.isFinite() && it >= 0 }
    }

    private fun parsePerTokenToPerMillion(element: JsonElement?): Double = readRate(element, 1_000_000.0) ?: 0.0

    private fun pricing(source: JsonObject?, input: String, output: String, cached: String, write: String,
        reasoning: String = "reasoning", multiplier: Double = 1.0): TokenPricing? = TokenPricing(
        readRate(source?.get(input), multiplier), readRate(source?.get(output), multiplier),
        readRate(source?.get(cached), multiplier), readRate(source?.get(write), multiplier),
        readRate(source?.get(reasoning), multiplier),
        readRate(source?.get("cache_creation_input_token_cost_above_12hr") ?: source?.get("cache_creation_input_token_cost_above_1hr"), multiplier)
    ).takeIf { it.hasAnyRate }

    private fun contextTiers(cost: JsonObject?): List<ContextPricingTier> = cost?.mapNotNull { (key, value) ->
        val match = CONTEXT_TIER_PATTERN.matchEntire(key) ?: return@mapNotNull null
        val scalar = when (match.groupValues[2]) { "k" -> 1000L; "m" -> 1_000_000L; else -> 1L }
        val count = match.groupValues[1].toLongOrNull()?.takeIf { it <= Long.MAX_VALUE / scalar } ?: return@mapNotNull null
        val rates = pricing(value as? JsonObject, "input", "output", "cache_read", "cache_write") ?: return@mapNotNull null
        ContextPricingTier(count * scalar, rates)
    }.orEmpty()

    private fun liteLlmContextTiers(source: JsonObject): List<ContextPricingTier> {
        val groups = mutableMapOf<Long, MutableMap<String, JsonElement>>()
        source.forEach { (key, value) ->
            val match = LITELLM_CONTEXT_TIER_PATTERN.matchEntire(key) ?: return@forEach
            val scalar = when (match.groupValues[3]) { "k" -> 1000L; "m" -> 1_000_000L; else -> 1L }
            val count = match.groupValues[2].toLongOrNull()?.takeIf { it <= Long.MAX_VALUE / scalar } ?: return@forEach
            groups.getOrPut(count * scalar) { mutableMapOf() }[match.groupValues[1]] = value
        }
        return groups.mapNotNull { (threshold, values) ->
            pricing(JsonObject(values), "input_cost_per_token", "output_cost_per_token", "cache_read_input_token_cost",
                "cache_creation_input_token_cost", multiplier = 1_000_000.0)?.let { ContextPricingTier(threshold, it) }
        }
    }
}
