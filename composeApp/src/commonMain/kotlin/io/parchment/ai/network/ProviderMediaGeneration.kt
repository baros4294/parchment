package io.parchment.ai.network

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.MediaKind
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.network.AiProviderEngine.ResolvedBinaryAttachment
import io.parchment.ai.platform.PlatformBridge
import kotlin.io.encoding.Base64
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// Generated images, video and speech, and binary attachments sent to providers.

internal suspend fun AiProviderEngine.executeOpenAiAudioSpeechEndpoint(
    profile: ProviderProfile,
    model: String,
    session: ChatSession,
    historyNodes: List<MessageNode>,
    circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
    onStatus: suspend (String) -> Unit,
    onFirstToken: () -> Unit,
    onMedia: suspend (GeneratedMediaItem) -> Unit,
    onContent: suspend (String) -> Unit,
    onRawWire: suspend (String, String) -> Unit,
    onRequestStarted: suspend () -> Unit
) {
    val latestUserPrompt = historyNodes.lastOrNull { it.role == MessageRole.USER }?.activeVariant?.content.orEmpty()
        .ifBlank { historyNodes.lastOrNull()?.activeVariant?.content.orEmpty() }
    require(latestUserPrompt.isNotBlank()) { "Prompt cannot be empty." }

    val targetUrl = buildUrlWithAuthQuery(
        profile,
        resolveSmartEndpointUrl(profile, "/v1/audio/speech", "/audio/speech")
    )
    val voice = profile.audioVoice.ifBlank { "alloy" }
    val speed = profile.audioSpeed.coerceIn(0.25, 4.0)

    val payloadCandidates = listOf(
        buildJsonObject {
            put("model", model.trim())
            put("input", latestUserPrompt)
            put("voice", voice)
            if (speed != 1.0) put("speed", speed)
            put("response_format", "mp3")
        },
        buildJsonObject {
            put("model", model.trim())
            put("input", latestUserPrompt)
            put("voice", "alloy")
        },
        buildJsonObject {
            put("model", model.trim())
            put("input", latestUserPrompt)
        }
    )

    var lastStatus = 0
    var lastErrorBody = ""
    for ((idx, candidateObj) in payloadCandidates.withIndex()) {
        val finalPayload = mergeRawJsonExtraBody(candidateObj, profile.rawJsonExtraBody)
        val reqStr = json.encodeToString(JsonObject.serializer(), finalPayload)
        onStatus("Synthesizing speech ($model • $voice)…")
        onRawWire(reqStr, "Dispatching to $targetUrl ...")

        ensureAgentPolicyCurrent(session, profile)
        onRequestStarted()
        val resp = httpClient.post(targetUrl) {
            applyAuthAndCustomHeaders(profile)
            contentType(ContentType.Application.Json)
            setBody(reqStr)
        }
        lastStatus = resp.status.value
        val contentTypeHeader = resp.headers["Content-Type"].orEmpty().lowercase()
        if (resp.status.isSuccess() && (contentTypeHeader.contains("audio") || contentTypeHeader.contains("octet-stream"))) {
            onFirstToken()
            val audioBytes = resp.readRawBytes()
            val ts = PlatformBridge.currentTimeMillis()
            val localPath = if (audioBytes.isNotEmpty()) {
                PlatformBridge.saveGeneratedMediaFile("speech-$ts.mp3", audioBytes, sessionId = session.id)
            } else ""
            onRawWire(reqStr, "Received ${audioBytes.size} bytes ($contentTypeHeader)")
            onMedia(
                GeneratedMediaItem(
                    id = "aud-$ts",
                    kind = MediaKind.AUDIO,
                    mimeType = "audio/mpeg",
                    localFilePath = localPath,
                    revisedPrompt = latestUserPrompt
                )
            )
            onContent("Generated speech audio (`$model` • voice: `$voice`).")
            return
        }

        val bodyText = resp.bodyAsText()
        lastErrorBody = bodyText
        onRawWire(reqStr, bodyText)
        if (resp.status.isSuccess()) {
            onFirstToken()
            val root = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
            val audioUrl = root?.get("url")?.jsonPrimitive?.contentOrNull
                ?: root?.get("audio_url")?.jsonPrimitive?.contentOrNull
                ?: (root?.get("data") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
            val b64Audio = root?.get("b64_json")?.jsonPrimitive?.contentOrNull
                ?: root?.get("audio")?.jsonPrimitive?.contentOrNull
            val audioBytes = when {
                !b64Audio.isNullOrBlank() -> runCatching { Base64.decode(b64Audio) }.getOrElse { ByteArray(0) }
                !audioUrl.isNullOrBlank() -> runCatching { downloadMediaBytes(audioUrl, MAX_DOWNLOADED_AUDIO_BYTES) }.getOrElse { ByteArray(0) }
                else -> ByteArray(0)
            }
            val ts = PlatformBridge.currentTimeMillis()
            val localPath = if (audioBytes.isNotEmpty()) {
                PlatformBridge.saveGeneratedMediaFile("speech-$ts.mp3", audioBytes, sessionId = session.id)
            } else ""
            onMedia(
                GeneratedMediaItem(
                    id = "aud-$ts",
                    kind = MediaKind.AUDIO,
                    mimeType = "audio/mpeg",
                    localFilePath = localPath,
                    remoteUrl = audioUrl.orEmpty(),
                    revisedPrompt = latestUserPrompt
                )
            )
            onContent("Generated speech audio (`$model` • voice: `$voice`).")
            return
        }
        if (lastStatus != 400 && lastStatus != 422 || idx == payloadCandidates.lastIndex) {
            break
        }
    }
    error("Audio Speech HTTP $lastStatus: $lastErrorBody")
}

internal suspend fun AiProviderEngine.executeByteDanceArkGeneration(
    profile: ProviderProfile,
    model: String,
    session: ChatSession,
    historyNodes: List<MessageNode>,
    circuitBreaker: WorkspaceHarness.LoopCircuitBreaker,
    forceVideoMode: Boolean = false,
    onStatus: suspend (String, String) -> Unit,
    onFirstToken: () -> Unit,
    onThinking: suspend (String) -> Unit,
    onMedia: suspend (GeneratedMediaItem) -> Unit,
    onContent: suspend (String) -> Unit,
    onRawWire: suspend (String, String) -> Unit,
    onRequestStarted: UsageRequestStarter,
    onUsage: UsageReporter,
    onMediaRequestStarted: suspend (String, String) -> Unit
) {
    val latestUserPrompt = historyNodes.lastOrNull { it.role == MessageRole.USER }?.activeVariant?.content.orEmpty()
        .ifBlank { historyNodes.lastOrNull()?.activeVariant?.content.orEmpty() }
    require(latestUserPrompt.isNotBlank()) { "Prompt cannot be empty." }

    val baseClean = profile.baseUrl.trim().trimEnd('#').trimEnd('/')
    val rootBase = baseClean
        .replace(Regex("/(?:api/)?(?:paas/)?v\\d+(?:beta\\d*|alpha\\d*)?$", RegexOption.IGNORE_CASE), "")
        .trimEnd('/')
    val hasVersionSuffix = Regex("/(?:api/)?(?:paas/)?v\\d+(?:beta\\d*|alpha\\d*)?$", RegexOption.IGNORE_CASE)
        .containsMatchIn(baseClean)

    val knownModalities = resolveKnownOutputModalities(profile, model)
    val isVideoTaskMode = forceVideoMode ||
        knownModalities.contains("video") ||
        profile.endpointPathOverride.contains("contents/generations/tasks", ignoreCase = true) ||
        profile.endpointPathOverride.contains("videos/generations", ignoreCase = true) ||
        profile.endpointPathOverride.contains("video/generations", ignoreCase = true)

    if (isVideoTaskMode) {
        val videoRes = when (profile.mediaResolution) {
            "480p", "720p", "1080p", "4K" -> profile.mediaResolution
            "2K" -> "1080p"
            else -> "720p"
        }

        val latestUserNode = historyNodes.lastOrNull { it.role == MessageRole.USER } ?: historyNodes.lastOrNull()
        val refImages = resolveBinaryAttachments(latestUserNode?.activeVariant)
            .filter { it.mimeType.lowercase().startsWith("image/") }

        val arkPayloadObj = buildJsonObject {
            put("model", model)
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", latestUserPrompt)
                })
                for ((imgIdx, img) in refImages.withIndex()) {
                    val imgRole = when {
                        refImages.size == 1 -> "first_frame"
                        refImages.size == 2 && imgIdx == 0 -> "first_frame"
                        refImages.size == 2 && imgIdx == 1 -> "last_frame"
                        else -> "reference_image"
                    }
                    add(buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:${img.mimeType};base64,${img.base64Data}")
                        }
                        put("role", imgRole)
                    })
                }
            }
            if (profile.mediaAspectRatio.isNotBlank()) {
                put("ratio", profile.mediaAspectRatio)
            }
            put("duration", profile.videoDurationSeconds.coerceIn(4, 15))
            put("resolution", videoRes)
            put("generate_audio", profile.videoGenerateAudio)
            put("watermark", false)
        }

        val openAiVideoPayloadObj = buildJsonObject {
            put("model", model)
            put("prompt", latestUserPrompt)
            if (profile.mediaAspectRatio.isNotBlank()) {
                put("aspect_ratio", profile.mediaAspectRatio)
            }
            put("duration", profile.videoDurationSeconds.coerceIn(4, 15))
            put("resolution", videoRes)
            if (refImages.isNotEmpty()) {
                val firstImg = refImages.first()
                put("image", "data:${firstImg.mimeType};base64,${firstImg.base64Data}")
            }
        }

        val minimalVideoPayloadObj = buildJsonObject {
            put("model", model)
            put("prompt", latestUserPrompt)
        }

        val isByteDanceFamily = model.contains("seedance", ignoreCase = true) ||
            model.contains("doubao", ignoreCase = true) ||
            baseClean.contains("volces.com", ignoreCase = true) ||
            baseClean.contains("bytepluses.com", ignoreCase = true) ||
            baseClean.endsWith("/api/v3", ignoreCase = true)

        val candidateTaskUrls = when {
            profile.protocol.isCustomWire || profile.baseUrl.trim().endsWith("#") || profile.endpointPathOverride.isNotBlank() ->
                listOf(resolveSmartEndpointUrl(profile, "/v1/videos/generations", "/videos/generations"))
            isByteDanceFamily -> buildList {
                if (hasVersionSuffix) {
                    add("$baseClean/contents/generations/tasks")
                    add("$baseClean/videos/generations")
                    add("$baseClean/video/generations")
                    add("$baseClean/videos")
                }
                add("$rootBase/api/v3/contents/generations/tasks")
                add("$rootBase/v1/contents/generations/tasks")
                add("$rootBase/v1/videos/generations")
                add("$rootBase/v1/video/generations")
                add("$rootBase/v1/videos")
            }.distinct()
            else -> buildList {
                if (hasVersionSuffix) {
                    add("$baseClean/videos/generations")
                    add("$baseClean/video/generations")
                    add("$baseClean/videos")
                    add("$baseClean/contents/generations/tasks")
                }
                add("$rootBase/v1/videos/generations")
                add("$rootBase/v1/video/generations")
                add("$rootBase/v1/videos")
                add("$rootBase/v1/contents/generations/tasks")
                add("$rootBase/api/v3/contents/generations/tasks")
            }.distinct()
        }

        var activeTaskUrl = candidateTaskUrls.first()
        var createBody = ""
        var createStatus = 0
        var reqStr = ""

        outerUrlLoop@ for (candUrl in candidateTaskUrls) {
            activeTaskUrl = candUrl
            val finalCreateUrl = buildUrlWithAuthQuery(profile, candUrl)
            val isTasksEndpoint = candUrl.contains("contents/generations/tasks", ignoreCase = true)
            val payloadSequence = if (isTasksEndpoint) {
                listOf(arkPayloadObj, openAiVideoPayloadObj, minimalVideoPayloadObj)
            } else {
                listOf(openAiVideoPayloadObj, minimalVideoPayloadObj, arkPayloadObj)
            }

            for ((pIdx, candPayload) in payloadSequence.withIndex()) {
                val merged = mergeRawJsonExtraBody(candPayload, profile.rawJsonExtraBody)
                reqStr = json.encodeToString(JsonObject.serializer(), merged)
                onStatus("Submitting video generation task ($model)…", "")
                onRawWire(reqStr, "Dispatching to $finalCreateUrl ...")

                ensureAgentPolicyCurrent(session, profile)
                onMediaRequestStarted("video_generation", "output_video")
                val createResp = httpClient.post(finalCreateUrl) {
                    applyAuthAndCustomHeaders(profile)
                    contentType(ContentType.Application.Json)
                    setBody(reqStr)
                }
                createBody = createResp.bodyAsText()
                createStatus = createResp.status.value
                onRawWire(reqStr, createBody)
                if (createResp.status.isSuccess()) break@outerUrlLoop
                if (createStatus == 404 || createStatus == 405 || createStatus == 501) {
                    break // Try next URL in candidateTaskUrls
                }
                if ((createStatus == 400 || createStatus == 422) && pIdx < payloadSequence.lastIndex) {
                    continue // Self-heal payload schema on the same URL
                }
                break@outerUrlLoop
            }
        }

        if (createStatus == 404 || createStatus == 405 || createStatus == 501) {
            onStatus("Routing video model via /v1/chat/completions…", "")
            val chatAccumulator = StringBuilder()
            executeOpenAiChatCompletionsLoop(
                profile = profile,
                model = model,
                session = session,
                historyNodes = historyNodes,
                continuePrefix = "",
                circuitBreaker = circuitBreaker,
                onStatus = { st -> onStatus(st, "") },
                onGoalProgress = {},
                onFirstToken = onFirstToken,
                onThinking = onThinking,
                onContent = { delta ->
                    chatAccumulator.append(delta)
                    onContent(delta)
                },
                onMedia = onMedia,
                onToolCall = {},
                onRawWire = onRawWire,
                onRequestStarted = onRequestStarted,
                onUsage = onUsage
            )
            extractMarkdownVideosFromText(chatAccumulator.toString(), profile.mediaAspectRatio, onMedia, sessionId = session.id)
            return
        }

        if (createStatus !in 200..299) {
            error("Video Generation HTTP $createStatus: $createBody")
        }

        val createObj = json.parseToJsonElement(createBody).jsonObject

        fun extractDirectVideoUrl(obj: JsonObject): String? {
            val contentSub = (obj["content"] as? JsonObject)
                ?: ((obj["data"] as? JsonObject)?.get("content") as? JsonObject)
            val dataObj = obj["data"] as? JsonObject
            val dataArr = obj["data"] as? JsonArray
            val firstDataItem = dataArr?.firstOrNull() as? JsonObject
            val videoObj = obj["video"] as? JsonObject
            val generationsArr = obj["generations"] as? JsonArray
            val firstGenItem = generationsArr?.firstOrNull() as? JsonObject
            return contentSub?.get("video_url")?.jsonPrimitive?.contentOrNull
                ?: obj["video_url"]?.jsonPrimitive?.contentOrNull
                ?: obj["result_url"]?.jsonPrimitive?.contentOrNull
                ?: obj["url"]?.jsonPrimitive?.contentOrNull
                ?: (obj["output"] as? JsonPrimitive)?.contentOrNull
                ?: videoObj?.get("url")?.jsonPrimitive?.contentOrNull
                ?: dataObj?.get("video_url")?.jsonPrimitive?.contentOrNull
                ?: dataObj?.get("url")?.jsonPrimitive?.contentOrNull
                ?: firstDataItem?.get("url")?.jsonPrimitive?.contentOrNull
                ?: firstDataItem?.get("video_url")?.jsonPrimitive?.contentOrNull
                ?: firstGenItem?.get("url")?.jsonPrimitive?.contentOrNull
        }

        val immediateVideoUrl = extractDirectVideoUrl(createObj)
        val taskId = createObj["id"]?.jsonPrimitive?.contentOrNull
            ?: createObj["task_id"]?.jsonPrimitive?.contentOrNull
            ?: createObj["generation_id"]?.jsonPrimitive?.contentOrNull
            ?: (createObj["data"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            ?: (createObj["data"] as? JsonObject)?.get("task_id")?.jsonPrimitive?.contentOrNull
            ?: immediateVideoUrl?.let { "sync-${PlatformBridge.currentTimeMillis()}" }
            ?: error("Video generation response did not contain a task 'id' or 'url': $createBody")

        var latestTaskObj = createObj
        if (immediateVideoUrl == null) {
            var status = createObj["status"]?.jsonPrimitive?.contentOrNull
                ?: (createObj["data"] as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull
                ?: "queued"
            var pollCount = 0
            var failedPolls = 0

            while (status.equals("queued", ignoreCase = true) ||
                status.equals("pending", ignoreCase = true) ||
                status.equals("submitted", ignoreCase = true) ||
                status.equals("running", ignoreCase = true) ||
                status.equals("in_progress", ignoreCase = true) ||
                status.equals("processing", ignoreCase = true) ||
                status.equals("starting", ignoreCase = true)
            ) {
                pollCount++
                onStatus("Video Generating ($status • poll #$pollCount)…", taskId)
                delay(3000L)
                val pollUrl = buildUrlWithAuthQuery(profile, "${activeTaskUrl.trimEnd('/')}/$taskId")
                latestTaskObj = try {
                    val pollResp = httpClient.get(pollUrl) {
                        applyAuthAndCustomHeaders(profile)
                    }
                    val pollBody = pollResp.bodyAsText()
                    onRawWire(reqStr, pollBody)
                    check(pollResp.status.isSuccess()) { "HTTP ${pollResp.status.value}: $pollBody" }
                    json.parseToJsonElement(pollBody).jsonObject
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (++failedPolls >= MAX_VIDEO_POLL_FAILURES) {
                        throw AiProviderEngine.AcceptedJobException(
                            "Video task $taskId was accepted, but its status could not be read, so it was not submitted again. ${e.message.orEmpty()}", e
                        )
                    }
                    onStatus("Video status check failed — trying again ($failedPolls/$MAX_VIDEO_POLL_FAILURES)…", taskId)
                    continue
                }
                failedPolls = 0
                status = latestTaskObj["status"]?.jsonPrimitive?.contentOrNull
                    ?: (latestTaskObj["data"] as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull
                    ?: "succeeded"
            }

            if (status.equals("failed", ignoreCase = true) ||
                status.equals("error", ignoreCase = true) ||
                status.equals("expired", ignoreCase = true) ||
                status.equals("cancelled", ignoreCase = true) ||
                status.equals("canceled", ignoreCase = true)
            ) {
                val errMsg = (latestTaskObj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: latestTaskObj.toString()
                throw AiProviderEngine.AcceptedJobException("Video Task $status: $errMsg")
            }
        }

        val videoUrl = immediateVideoUrl ?: extractDirectVideoUrl(latestTaskObj)
            ?: throw AiProviderEngine.AcceptedJobException("Video task completed but no video URL was found in response: $latestTaskObj")

        onFirstToken()
        onStatus("Downloading generated MP4 video…", taskId)
        val videoBytes = runCatching {
            downloadMediaBytes(videoUrl, MAX_DOWNLOADED_VIDEO_BYTES)
        }.getOrElse { ByteArray(0) }

        val localPath = if (videoBytes.isNotEmpty()) {
            PlatformBridge.saveGeneratedMediaFile("video-$taskId.mp4", videoBytes, sessionId = session.id)
        } else ""

        val mediaItem = GeneratedMediaItem(
            id = "vid-$taskId",
            kind = MediaKind.VIDEO,
            mimeType = "video/mp4",
            localFilePath = localPath,
            remoteUrl = videoUrl,
            aspectRatio = profile.mediaAspectRatio
        )
        onMedia(mediaItem)
        onContent("Generated video (`$model` • ${profile.mediaAspectRatio} • ${profile.videoDurationSeconds}s).")
    } else {
        // Universal Image Generation Engine (`POST /v1/images/generations` & `/api/v3/images/generations`)
        val latestUserNode = historyNodes.lastOrNull { it.role == MessageRole.USER } ?: historyNodes.lastOrNull()
        val refImages = resolveBinaryAttachments(latestUserNode?.activeVariant)
            .filter { it.mimeType.lowercase().startsWith("image/") }

        val isHighResSeedreamFamily = model.contains("seedream", ignoreCase = true) ||
            model.contains("doubao", ignoreCase = true) ||
            model.contains("dreamina", ignoreCase = true) ||
            profile.mediaResolution in listOf("2K", "4K")
        val primarySize = if (isHighResSeedreamFamily) {
            resolveSeedreamImageSize(profile.mediaAspectRatio, profile.mediaResolution)
        } else {
            resolveOpenAiImageSize(profile.mediaAspectRatio, profile.mediaResolution)
        }
        val secondarySize = if (isHighResSeedreamFamily) {
            resolveOpenAiImageSize(profile.mediaAspectRatio, profile.mediaResolution)
        } else {
            resolveSeedreamImageSize(profile.mediaAspectRatio, profile.mediaResolution)
        }

        val candidatePayloads = listOf(
            // Candidate 1: Primary size + b64_json + optional reference image
            buildJsonObject {
                put("model", model)
                put("prompt", latestUserPrompt)
                put("size", primarySize)
                put("response_format", "b64_json")
                if (isHighResSeedreamFamily) put("watermark", false)
                if (refImages.isNotEmpty()) {
                    val firstImg = refImages.first()
                    put("image", "data:${firstImg.mimeType};base64,${firstImg.base64Data}")
                }
            },
            // Candidate 2: Alternate resolution tier without watermark/response_format restrictions
            buildJsonObject {
                put("model", model)
                put("prompt", latestUserPrompt)
                put("size", secondarySize)
                if (refImages.isNotEmpty()) {
                    val firstImg = refImages.first()
                    put("image", "data:${firstImg.mimeType};base64,${firstImg.base64Data}")
                }
            },
            // Candidate 3: Minimal universal payload (model + prompt only)
            buildJsonObject {
                put("model", model)
                put("prompt", latestUserPrompt)
            }
        )

        val candidateImgUrls = when {
            profile.protocol.isCustomWire || profile.baseUrl.trim().endsWith("#") || profile.endpointPathOverride.isNotBlank() ->
                listOf(resolveSmartEndpointUrl(profile, "/v1/images/generations", "/images/generations"))
            hasVersionSuffix -> listOf(
                "$baseClean/images/generations",
                "$rootBase/v1/images/generations",
                "$rootBase/api/v3/images/generations"
            ).distinct()
            else -> listOf(
                "$rootBase/v1/images/generations",
                "$rootBase/api/v3/images/generations",
                "$baseClean/images/generations"
            ).distinct()
        }

        var bodyText = ""
        var lastStatus = 0
        var reqStr = ""
        outerImgLoop@ for (candidateUrl in candidateImgUrls) {
            val finalUrl = buildUrlWithAuthQuery(profile, candidateUrl)
            for ((pIdx, candPayload) in candidatePayloads.withIndex()) {
                val finalPayload = mergeRawJsonExtraBody(candPayload, profile.rawJsonExtraBody)
                reqStr = json.encodeToString(JsonObject.serializer(), finalPayload)
                onStatus("Generating image ($model • ${ profile.mediaAspectRatio })…", "")
                onRawWire(reqStr, "Dispatching to $finalUrl ...")

                ensureAgentPolicyCurrent(session, profile)
                onMediaRequestStarted("image_generation", "output_image")
                val resp = httpClient.post(finalUrl) {
                    applyAuthAndCustomHeaders(profile)
                    contentType(ContentType.Application.Json)
                    setBody(reqStr)
                }
                bodyText = resp.bodyAsText()
                lastStatus = resp.status.value
                onRawWire(reqStr, bodyText)
                if (resp.status.isSuccess()) break@outerImgLoop
                if (lastStatus == 404 || lastStatus == 405 || lastStatus == 501) {
                    break // Try next URL
                }
                if ((lastStatus == 400 || lastStatus == 422) && pIdx < candidatePayloads.lastIndex) {
                    val lowerErr = bodyText.lowercase()
                    if (lowerErr.contains("video") || lowerErr.contains("contents/generations/tasks")) {
                        break@outerImgLoop
                    }
                    continue // Self-heal payload parameters
                }
                break@outerImgLoop
            }
        }

        if (lastStatus == 404 || lastStatus == 405 || lastStatus == 501) {
            onStatus("Routing image model via /v1/chat/completions…", "")
            executeOpenAiChatCompletionsLoop(
                profile = profile,
                model = model,
                session = session,
                historyNodes = historyNodes,
                continuePrefix = "",
                circuitBreaker = circuitBreaker,
                onStatus = { st -> onStatus(st, "") },
                onGoalProgress = {},
                onFirstToken = onFirstToken,
                onThinking = onThinking,
                onContent = onContent,
                onMedia = onMedia,
                onToolCall = {},
                onRawWire = onRawWire,
                onRequestStarted = onRequestStarted,
                onUsage = onUsage
            )
            return
        }

        if (lastStatus !in 200..299) {
            val lowerBody = bodyText.lowercase()
            if (!forceVideoMode && (lowerBody.contains("video") || lowerBody.contains("tasks") || lowerBody.contains("content"))) {
                recordDiscoveredModality(model, listOf("video"))
                executeByteDanceArkGeneration(
                    profile = profile,
                    model = model,
                    session = session,
                    historyNodes = historyNodes,
                    circuitBreaker = circuitBreaker,
                    forceVideoMode = true,
                    onStatus = onStatus,
                    onFirstToken = onFirstToken,
                    onThinking = onThinking,
                    onMedia = onMedia,
                    onContent = onContent,
                    onRawWire = onRawWire,
                    onRequestStarted = onRequestStarted,
                    onUsage = onUsage,
                    onMediaRequestStarted = onMediaRequestStarted
                )
                return
            }
            error("Image Generation HTTP $lastStatus: $bodyText")
        }

        onFirstToken()
        val root = json.parseToJsonElement(bodyText).jsonObject
        val dataArr = root["data"] as? JsonArray
            ?: root["images"] as? JsonArray
            ?: JsonArray(emptyList())
        if (dataArr.isNotEmpty()) {
            for (itemEl in dataArr) {
                val itemObj = itemEl as? JsonObject ?: continue
                val b64 = itemObj["b64_json"]?.jsonPrimitive?.contentOrNull
                    ?: itemObj["base64"]?.jsonPrimitive?.contentOrNull ?: ""
                val remoteUrl = itemObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                val revised = itemObj["revised_prompt"]?.jsonPrimitive?.contentOrNull ?: ""
                saveAndEmitImage(b64, remoteUrl, "image/png", profile.mediaAspectRatio, revised, onMedia, sessionId = session.id)
            }
        } else {
            val directUrl = root["url"]?.jsonPrimitive?.contentOrNull ?: ""
            val directB64 = root["b64_json"]?.jsonPrimitive?.contentOrNull ?: ""
            if (directUrl.isNotBlank() || directB64.isNotBlank()) {
                saveAndEmitImage(directB64, directUrl, "image/png", profile.mediaAspectRatio, "", onMedia, sessionId = session.id)
            }
        }
    }
}

/**
 * Resolves multimodal binary attachments (images, videos, audio, and PDFs) with filename, MIME type, and base64 payload,
 * reading lazily from `localFilePath` on disk when `base64Data` is empty so multi-megabyte base64 strings never bloat RAM or JSON state.
 */
/** Downloads generated media without letting one response exhaust memory. */
internal suspend fun AiProviderEngine.downloadMediaBytes(url: String, maxBytes: Int): ByteArray =
    httpClient.prepareGet(url).execute { response ->
        check(response.status.isSuccess()) { "Media download failed: HTTP ${response.status.value}" }
        val channel = response.bodyAsChannel()
        var bytes = ByteArray(64 * 1024)
        var size = 0
        while (!channel.isClosedForRead) {
            if (size == bytes.size) bytes = bytes.copyOf(minOf(bytes.size * 2L, maxBytes + 1L).toInt())
            val read = channel.readAvailable(bytes, size, bytes.size - size)
            if (read <= 0) break
            size += read
            check(size <= maxBytes) { "Media download exceeded the $maxBytes byte limit." }
        }
        bytes.copyOf(size)
    }

private const val MAX_VIDEO_POLL_FAILURES = 5

internal val ResolvedBinaryAttachment.isDocument: Boolean
    get() {
        val m = mimeType.lowercase()
        return m == "application/pdf" || m.contains("document") || m.contains("msword") || m.contains("officedocument")
    }

/** Stands in for a file this wire cannot accept, so one attachment cannot fail every later request. */
internal fun ResolvedBinaryAttachment.unsupportedAttachmentNote(): String =
    "[Attachment not sent: ${fileName.ifBlank { "file" }} ($mimeType) is not supported by this API format.]"

internal fun AiProviderEngine.resolveBinaryAttachments(nodeVar: MessageVariant?): List<ResolvedBinaryAttachment> {
    if (nodeVar == null || nodeVar.attachments.isEmpty()) return emptyList()
    return nodeVar.attachments.mapNotNull { att ->
        val m = att.mimeType.lowercase()
        val isBinaryMedia = m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/") ||
            m == "application/pdf" || m.contains("document") || m.contains("msword") || m.contains("officedocument")
        if (!isBinaryMedia && att.base64Data.isBlank()) return@mapNotNull null
        val b64 = if (att.base64Data.isNotBlank()) {
            att.base64Data
        } else if (att.localFilePath.isNotBlank()) {
            val fileBytes = PlatformBridge.localFileSizeBytes(att.localFilePath)
            check(fileBytes <= MAX_INLINE_ATTACHMENT_BYTES) { oversizedAttachmentMessage(att.fileName, fileBytes) }
            PlatformBridge.readBinaryFileBytes(att.localFilePath)?.let { Base64.encode(it) } ?: ""
        } else ""
        if (b64.isNotBlank()) {
            ResolvedBinaryAttachment(
                fileName = att.fileName,
                mimeType = att.mimeType.ifBlank { "image/png" },
                base64Data = b64
            )
        } else null
    }
}

private suspend fun AiProviderEngine.extractMarkdownVideosFromText(
    text: String,
    aspectRatio: String,
    onMedia: suspend (GeneratedMediaItem) -> Unit,
    sessionId: String = ""
) {
    val urlRegex = Regex("""https?://[^\s)"'>]+\.mp4(?:\?[^\s)"'>]*)?""", RegexOption.IGNORE_CASE)
    for (match in urlRegex.findAll(text)) {
        val videoUrl = match.value.trim()
        val now = PlatformBridge.currentTimeMillis()
        val videoBytes = runCatching { downloadMediaBytes(videoUrl, MAX_DOWNLOADED_VIDEO_BYTES) }.getOrElse { ByteArray(0) }
        val localPath = if (videoBytes.isNotEmpty()) {
            PlatformBridge.saveGeneratedMediaFile("video-$now.mp4", videoBytes, sessionId = sessionId)
        } else ""
        onMedia(
            GeneratedMediaItem(
                id = "vid-$now",
                kind = MediaKind.VIDEO,
                mimeType = "video/mp4",
                localFilePath = localPath,
                remoteUrl = videoUrl,
                aspectRatio = aspectRatio
            )
        )
    }
}

internal suspend fun AiProviderEngine.saveAndEmitImage(
    base64Data: String,
    remoteUrl: String,
    mimeType: String,
    aspectRatio: String,
    revisedPrompt: String,
    onMedia: suspend (GeneratedMediaItem) -> Unit,
    sessionId: String = ""
) {
    val now = PlatformBridge.currentTimeMillis()
    val ext = when {
        mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
        mimeType.contains("webp") -> "webp"
        else -> "png"
    }

    val imageBytes: ByteArray = when {
        base64Data.isNotBlank() -> {
            val cleanB64 = base64Data.substringAfter("base64,").replace("\n", "").replace("\r", "").trim()
            runCatching { Base64.decode(cleanB64) }.getOrElse { ByteArray(0) }
        }
        remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://") -> {
            runCatching { downloadMediaBytes(remoteUrl, MAX_DOWNLOADED_IMAGE_BYTES) }.getOrElse { ByteArray(0) }
        }
        else -> ByteArray(0)
    }

    if (imageBytes.isEmpty() && remoteUrl.isBlank()) return

    val localPath = if (imageBytes.isNotEmpty()) {
        PlatformBridge.saveGeneratedMediaFile("img-$now-${kotlin.random.Random.nextInt(100, 999)}.$ext", imageBytes, sessionId = sessionId)
    } else ""

    onMedia(
        GeneratedMediaItem(
            id = "media-$now",
            kind = MediaKind.IMAGE,
            mimeType = mimeType,
            localFilePath = localPath,
            remoteUrl = remoteUrl,
            revisedPrompt = revisedPrompt,
            aspectRatio = aspectRatio
        )
    )
}

internal suspend fun AiProviderEngine.extractMarkdownImagesFromText(
    text: String,
    aspectRatio: String,
    onMedia: suspend (GeneratedMediaItem) -> Unit,
    sessionId: String = ""
) {
    if (!text.contains("![")) return
    val regex = Regex("""!\[[^\]]*\]\(([^)]+)\)""")
    for (match in regex.findAll(text)) {
        val target = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (target.startsWith("data:image/")) {
            val mime = target.substringAfter("data:").substringBefore(";").ifBlank { "image/png" }
            val b64 = target.substringAfter("base64,")
            saveAndEmitImage(b64, "", mime, aspectRatio, "", onMedia, sessionId = sessionId)
        } else if (target.startsWith("http://") || target.startsWith("https://")) {
            saveAndEmitImage("", target, "image/png", aspectRatio, "", onMedia, sessionId = sessionId)
        }
    }
}

internal fun AiProviderEngine.resolveOpenAiImageSize(aspectRatio: String, resolution: String): String {
    if (resolution.contains("x")) return resolution
    return when (aspectRatio) {
        "16:9", "3:2", "4:3" -> "1536x1024"
        "9:16", "2:3", "3:4" -> "1024x1536"
        "1:1" -> "1024x1024"
        else -> "auto"
    }
}

private fun AiProviderEngine.resolveSeedreamImageSize(aspectRatio: String, resolution: String): String {
    if (resolution.contains("x")) return resolution
    val is2K = resolution == "2K" || resolution == "4K"
    return when (aspectRatio) {
        "16:9" -> if (is2K) "2560x1440" else "1280x720"
        "9:16" -> if (is2K) "1440x2560" else "720x1280"
        "4:3" -> if (is2K) "2304x1728" else "1152x864"
        "3:4" -> if (is2K) "1728x2304" else "864x1152"
        else -> if (is2K) "2048x2048" else "1024x1024"
    }
}
