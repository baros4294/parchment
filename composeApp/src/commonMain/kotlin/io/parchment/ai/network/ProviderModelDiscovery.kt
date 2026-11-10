package io.parchment.ai.network

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// Model listing and discovered input/output capabilities.

/**
 * Dynamically fetches the available model IDs from the active provider or custom proxy endpoint.
 * Supports Google `/v1beta/models`, OpenAI `/v1/models`, `/api/v3/models`, and Anthropic `/v1/models` formats.
 */
suspend fun AiProviderEngine.fetchAvailableModels(profile: ProviderProfile): Result<List<String>> = runCatching {
    val base = profile.baseUrl.trim().trimEnd('#').trimEnd('/')
    val hasVersionSuffix = Regex("/(?:api/)?(?:paas/)?v\\d+(?:beta\\d*|alpha\\d*)?$", RegexOption.IGNORE_CASE)
        .containsMatchIn(base)
    val candidateUrls = when {
        base.endsWith("/models", ignoreCase = true) -> listOf(base)
        hasVersionSuffix -> listOf(
            "$base/models",
            "${base.replace(Regex("/(?:api/)?(?:paas/)?v\\d+(?:beta\\d*|alpha\\d*)?$", RegexOption.IGNORE_CASE), "")}/v1/models"
        ).distinct()
        profile.protocol == ProviderProtocol.GEMINI_INTERACTIONS -> listOf(
            "$base/v1beta/models?pageSize=200",
            "$base/v1/models",
            "$base/models"
        )
        profile.protocol == ProviderProtocol.ANTHROPIC_MESSAGES -> listOf(
            "$base/v1/models?limit=100",
            "$base/v1/models",
            "$base/models"
        )
        else -> listOf(
            "$base/v1/models",
            "$base/api/v3/models",
            "$base/models"
        )
    }

    var lastError = ""
    for (rawUrl in candidateUrls) {
        val url = buildUrlWithAuthQuery(profile, rawUrl)
        val respResult = runCatching {
            httpClient.get(url) {
                applyAuthAndCustomHeaders(profile)
                if (profile.protocol == ProviderProtocol.ANTHROPIC_MESSAGES) {
                    header("anthropic-version", "2023-06-01")
                }
            }
        }
        val resp = respResult.getOrNull()
        if (resp == null) {
            val err = respResult.exceptionOrNull()
            lastError = err?.message ?: err?.let { it::class.simpleName } ?: "Connection failed"
            continue
        }

        val bodyText = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            lastError = "HTTP ${resp.status.value}: ${bodyText.take(240)}"
            continue
        }

        val parsed = parseModelsListJson(bodyText)
        if (parsed.isNotEmpty()) {
            return@runCatching parsed
        }
    }

    if (lastError.isNotBlank()) {
        error(lastError)
    } else {
        error("Endpoint returned an empty model list.")
    }
}

internal fun AiProviderEngine.recordDiscoveredModality(modelId: String, modalities: List<String>) {
    val clean = modelId.trim().removePrefix("models/").lowercase()
    if (clean.isEmpty() || modalities.isEmpty()) return
    val normalized = modalities.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    if (normalized.isNotEmpty()) {
        discoveredModelModalities[clean] = normalized
        val shortKey = clean.substringAfterLast("/")
        if (shortKey.isNotEmpty()) {
            discoveredModelModalities[shortKey] = normalized
        }
        val canonical = ModelsDevClient.normalizeCanonicalModelSlug(clean)
        if (canonical.isNotEmpty()) {
            discoveredModelModalities[canonical] = normalized
        }
    }
}

/** Added only when a provider or a user's model settings state live, two-way support. */
internal const val REALTIME_MODALITY = "realtime"

internal fun AiProviderEngine.recordDiscoveredVendor(modelId: String, vendor: String) {
    val clean = modelId.trim().removePrefix("models/").lowercase()
    val cleanVendor = vendor.trim()
    if (clean.isEmpty() || cleanVendor.isEmpty()) return
    discoveredModelVendors[clean] = cleanVendor
    val shortKey = clean.substringAfterLast("/")
    if (shortKey.isNotEmpty()) {
        discoveredModelVendors[shortKey] = cleanVendor
    }
}

fun AiProviderEngine.getDiscoveredVendorForModel(modelId: String): String? {
    val clean = modelId.trim().removePrefix("models/").lowercase()
    if (clean.isEmpty()) return null
    return discoveredModelVendors[clean]
        ?: discoveredModelVendors[clean.substringAfterLast("/")]
        ?: ModelsDevClient.getCachedVendorSlug(modelId)
}

/** An explicit endpoint path states what the profile generates; a model's name never does. */
private fun modalitiesFromEndpointOverride(endpointOverride: String): List<String> {
    val path = endpointOverride.trim().lowercase()
    return when {
        path.contains("contents/generations/tasks") || path.contains("videos/generations") || path.contains("video/generations") -> listOf("video")
        path.contains("images/generations") || path.contains("images/edits") -> listOf("image")
        path.contains("audio/speech") -> listOf("audio")
        else -> emptyList()
    }
}

internal fun AiProviderEngine.resolveKnownOutputModalities(profile: ProviderProfile?, modelId: String): List<String> {
    val clean = modelId.trim().removePrefix("models/").lowercase()
    if (clean.isEmpty()) return emptyList()
    val shortKey = clean.substringAfterLast("/")
    val canonical = ModelsDevClient.normalizeCanonicalModelSlug(clean)
    if (profile != null) {
        profile.customModelSpecs[modelId.trim()]?.outputModalities?.takeIf { it.isNotEmpty() }?.let { return it }
        profile.customModelSpecs[clean]?.outputModalities?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    discoveredModelModalities[clean]?.takeIf { it.isNotEmpty() }?.let { return it }
    discoveredModelModalities[shortKey]?.takeIf { it.isNotEmpty() }?.let { return it }
    discoveredModelModalities[canonical]?.takeIf { it.isNotEmpty() }?.let { return it }

    return ModelsDevClient.getCachedOutputModalities(modelId)
        .ifEmpty { modalitiesFromEndpointOverride(profile?.endpointPathOverride.orEmpty()) }
}

fun AiProviderEngine.resolveModelModalityProfile(profile: ProviderProfile?, modelId: String): ModelModalityProfile {
    val outMods = resolveKnownOutputModalities(profile, modelId)
    val inMods = ModelsDevClient.getCachedInputModalities(modelId).ifEmpty { listOf("text") }
    val isLive = outMods.contains(REALTIME_MODALITY)
    val isDedicatedVid = outMods.contains("video") && !outMods.contains("text")
    val isDedicatedImg = outMods.contains("image") && !outMods.contains("text")
    val isInterleavedImg = outMods.contains("image") && outMods.contains("text")
    val isDedicatedAud = (outMods.contains("audio") || outMods.contains("speech")) && !outMods.contains("text")
    return ModelModalityProfile(
        outputModalities = outMods.ifEmpty { listOf("text") },
        inputModalities = inMods,
        isDedicatedImage = isDedicatedImg,
        isInterleavedImage = isInterleavedImg,
        isDedicatedVideo = isDedicatedVid,
        isDedicatedAudioTts = isDedicatedAud,
        isLiveRealtimeAudio = isLive
    )
}

internal suspend fun AiProviderEngine.resolveModelModalityProfileAsync(
    profile: ProviderProfile?,
    modelId: String
): ModelModalityProfile {
    val fastProfile = resolveModelModalityProfile(profile, modelId)
    if (fastProfile.isDedicatedImage ||
        fastProfile.isDedicatedVideo ||
        fastProfile.isDedicatedAudioTts ||
        fastProfile.isInterleavedImage ||
        ModelsDevClient.getCachedOutputModalities(modelId).isNotEmpty()
    ) {
        return fastProfile
    }
    val networkMods = ModelsDevClient.resolveOutputModalities(modelId)
    if (networkMods.isNotEmpty()) {
        recordDiscoveredModality(modelId, networkMods)
    }
    return resolveModelModalityProfile(profile, modelId)
}

internal fun AiProviderEngine.detectMediaEndpointRedirectFromError(err: Throwable): String? {
    val msg = (err.message ?: err.toString()).lowercase()
    if (msg.contains("contents/generations/tasks") ||
        msg.contains("videos/generations") ||
        msg.contains("video/generations") ||
        msg.contains("v1/videos") ||
        msg.contains("video generation") ||
        msg.contains("video model")
    ) {
        return "video"
    }
    if (msg.contains("images/generations") ||
        msg.contains("images/edits") ||
        msg.contains("v1/images") ||
        msg.contains("only supports image") ||
        msg.contains("image generation") ||
        (msg.contains("not a chat model") && msg.contains("image"))
    ) {
        return "image"
    }
    if (msg.contains("audio/speech") ||
        msg.contains("v1/audio") ||
        msg.contains("speech generation") ||
        msg.contains("tts model")
    ) {
        return "audio"
    }
    return null
}

internal fun AiProviderEngine.parseModelsListJson(bodyText: String): List<String> {
    val rootEl = runCatching { json.parseToJsonElement(bodyText) }.getOrNull() ?: return emptyList()
    val results = LinkedHashSet<String>()

    fun extractModalitiesFromModelObject(item: JsonObject): List<String> {
        val outList = mutableListOf<String>()
        (item["output_modalities"] as? JsonArray)?.forEach {
            it.jsonPrimitive.contentOrNull?.trim()?.lowercase()?.takeIf { s -> s.isNotEmpty() }?.let(outList::add)
        }
        val modObj = item["modalities"] as? JsonObject
        (modObj?.get("output") as? JsonArray)?.forEach {
            it.jsonPrimitive.contentOrNull?.trim()?.lowercase()?.takeIf { s -> s.isNotEmpty() }?.let(outList::add)
        }
        val archObj = item["architecture"] as? JsonObject
        (archObj?.get("output_modalities") as? JsonArray)?.forEach {
            it.jsonPrimitive.contentOrNull?.trim()?.lowercase()?.takeIf { s -> s.isNotEmpty() }?.let(outList::add)
        }
        val archMod = archObj?.get("modality")?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
        if (archMod.contains("->")) {
            val rhs = archMod.substringAfter("->")
            rhs.split("+", ",", "/").map { it.trim() }.filter { it.isNotEmpty() }.forEach(outList::add)
        }
        val modelType = (item["type"]?.jsonPrimitive?.contentOrNull
            ?: item["mode"]?.jsonPrimitive?.contentOrNull
            ?: item["task_type"]?.jsonPrimitive?.contentOrNull
            ?: item["task"]?.jsonPrimitive?.contentOrNull)?.trim()?.lowercase().orEmpty()
        val tags = (item["tags"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.lowercase() }.orEmpty()
        when {
            modelType.contains("video") || tags.any { it.contains("video") } -> outList.add("video")
            modelType.contains("image") || tags.any { it.contains("image") } -> outList.add("image")
            modelType.contains("audio_speech") || modelType == "tts" || modelType == "speech" ||
                modelType == "audio" || tags.any { it.contains("audio") || it.contains("tts") } -> outList.add("audio")
            modelType == "chat" || modelType == "text" || modelType == "language" ||
                modelType == "responses" || modelType == "completion" -> outList.add("text")
        }
        val genMethods = ((item["supportedGenerationMethods"] as? JsonArray)
            ?: (item["supported_generation_methods"] as? JsonArray))
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }.orEmpty()
        if (genMethods.any { it.contains("bidi") }) {
            outList.add("text")
            outList.add("audio")
            outList.add(REALTIME_MODALITY)
        } else if (genMethods.any { it.contains("predict") || it.contains("generateimages") } &&
            genMethods.none { it.contains("generatecontent") }
        ) {
            outList.add("image")
        } else if (genMethods.any { it.contains("generatevideo") }) {
            outList.add("video")
        }
        return outList.distinct()
    }

    fun extractFromArray(arr: JsonArray) {
        for (item in arr) {
            when (item) {
                is JsonPrimitive -> {
                    val s = item.contentOrNull?.trim().orEmpty()
                    if (s.isNotEmpty()) results.add(s.removePrefix("models/"))
                }
                is JsonObject -> {
                    val idCandidate = item["id"]?.jsonPrimitive?.contentOrNull
                        ?: item["name"]?.jsonPrimitive?.contentOrNull
                        ?: item["model"]?.jsonPrimitive?.contentOrNull
                    if (!idCandidate.isNullOrBlank()) {
                        val cleanId = idCandidate.trim().removePrefix("models/")
                        results.add(cleanId)
                        val discovered = extractModalitiesFromModelObject(item)
                        if (discovered.isNotEmpty()) {
                            recordDiscoveredModality(cleanId, discovered)
                        }
                        val infoObj = item["info"] as? JsonObject
                        val vendor = item["owned_by"]?.jsonPrimitive?.contentOrNull
                            ?: item["provider"]?.jsonPrimitive?.contentOrNull
                            ?: item["developer"]?.jsonPrimitive?.contentOrNull
                            ?: infoObj?.get("developer")?.jsonPrimitive?.contentOrNull
                        if (!vendor.isNullOrBlank()) {
                            recordDiscoveredVendor(cleanId, vendor)
                        }
                    }
                }
                else -> {}
            }
        }
    }

    when (rootEl) {
        is JsonArray -> extractFromArray(rootEl)
        is JsonObject -> {
            (rootEl["models"] as? JsonArray)?.let { extractFromArray(it) }
            (rootEl["data"] as? JsonArray)?.let { extractFromArray(it) }
        }
        else -> {}
    }

    return results.toList()
}
