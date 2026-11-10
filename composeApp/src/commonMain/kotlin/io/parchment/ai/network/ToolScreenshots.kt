package io.parchment.ai.network

import io.parchment.ai.model.ToolCallRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// Each loop resends its earlier steps, so only the newest screenshot stays in them. Otherwise a
// long run would pay to resend every screen on every step.
private const val SCREENSHOT_MIME = "image/jpeg"
private const val SCREENSHOT_LABEL = "Screenshot from the computer_use tool."

internal val ToolCallRecord.hasScreenshot: Boolean get() = screenshotJpegBase64.isNotEmpty()

private fun JsonObject.isScreenshotEntry(): Boolean =
    ((this["content"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.content } == SCREENSHOT_LABEL

/** For the formats where a screenshot is its own message: Responses, Chat and Interactions. */
internal fun MutableList<JsonObject>.dropOlderScreenshotEntries() {
    removeAll { it.isScreenshotEntry() }
}

/** Anthropic: older tool results keep their text and lose the image. */
internal fun MutableList<JsonObject>.dropOlderAnthropicScreenshots() {
    replaceAll { message ->
        val blocks = message["content"] as? JsonArray ?: return@replaceAll message
        if (blocks.none { (it as? JsonObject)?.get("content") is JsonArray }) return@replaceAll message
        JsonObject(message + ("content" to JsonArray(blocks.map { block ->
            val result = block as? JsonObject
            val text = ((result?.get("content") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("text")
            if (result == null || text == null) block else JsonObject(result + ("content" to text))
        })))
    }
}

/** Gemini: only a tool-response turn can hold a screenshot, so a user's own images are untouched. */
internal fun MutableList<JsonObject>.dropOlderGeminiScreenshots() {
    replaceAll { turn ->
        val parts = turn["parts"] as? JsonArray ?: return@replaceAll turn
        if (parts.none { it is JsonObject && "functionResponse" in it }) return@replaceAll turn
        JsonObject(turn + ("parts" to JsonArray(parts.filterNot { it is JsonObject && "inline_data" in it })))
    }
}

internal fun responsesScreenshotMessage(record: ToolCallRecord): JsonObject = buildJsonObject {
    put("role", "user")
    putJsonArray("content") {
        addJsonObject { put("type", "input_text"); put("text", SCREENSHOT_LABEL) }
        addJsonObject { put("type", "input_image"); put("image_url", "data:$SCREENSHOT_MIME;base64,${record.screenshotJpegBase64}") }
    }
}

internal fun chatScreenshotMessage(record: ToolCallRecord): JsonObject = buildJsonObject {
    put("role", "user")
    putJsonArray("content") {
        addJsonObject { put("type", "text"); put("text", SCREENSHOT_LABEL) }
        addJsonObject {
            put("type", "image_url")
            putJsonObject("image_url") { put("url", "data:$SCREENSHOT_MIME;base64,${record.screenshotJpegBase64}") }
        }
    }
}

/** Anthropic carries the image inside the tool result itself. */
internal fun anthropicToolResultContent(record: ToolCallRecord): JsonElement {
    if (!record.hasScreenshot) return JsonPrimitive(record.resultText)
    return buildJsonArray {
        addJsonObject { put("type", "text"); put("text", record.resultText) }
        addJsonObject {
            put("type", "image")
            putJsonObject("source") {
                put("type", "base64")
                put("media_type", SCREENSHOT_MIME)
                put("data", record.screenshotJpegBase64)
            }
        }
    }
}

internal fun geminiScreenshotPart(record: ToolCallRecord): JsonObject = buildJsonObject {
    putJsonObject("inline_data") {
        put("mime_type", SCREENSHOT_MIME)
        put("data", record.screenshotJpegBase64)
    }
}

internal fun interactionsScreenshotStep(record: ToolCallRecord): JsonObject = buildJsonObject {
    put("type", "user_input")
    put("content", JsonArray(listOf(
        buildJsonObject { put("type", "text"); put("text", SCREENSHOT_LABEL) },
        buildJsonObject { put("type", "image"); put("mime_type", SCREENSHOT_MIME); put("data", record.screenshotJpegBase64) }
    )))
}
