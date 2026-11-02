package io.parchment.ai.protocol

import io.parchment.ai.model.CharacterOrScenarioCard
import io.parchment.ai.model.ContextFragment
import io.parchment.ai.model.FragmentActivationMode
import io.parchment.ai.model.SystemPromptPreset
import io.parchment.ai.model.ThemeConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Pure-Kotlin Protocol for importing and exporting:
 * 1. PNG images with embedded instructions (tEXt / iTXt chunks: `chara`, `ccv3`, `parchment_card`, `parchment_prompt`)
 * 2. JSON Character / Scenario / Co-Writing Cards (.json)
 * 3. JSON System Prompt Presets (.prompt.json / .json)
 * 4. JSON Custom UI Themes (.theme.json)
 */
@OptIn(ExperimentalEncodingApi::class)
object CardAndPromptCodec {

    val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    sealed class ImportResult {
        data class CardImported(val card: CharacterOrScenarioCard, val sourceType: String) : ImportResult()
        data class SystemPromptImported(val preset: SystemPromptPreset, val sourceType: String) : ImportResult()
        data class ThemeImported(val theme: ThemeConfig) : ImportResult()
    }

    /**
     * Universal importer that inspects binary bytes (.png or .json) and extracts either
     * a Character/Scenario Card, a SystemPromptPreset, or a ThemeConfig.
     */
    fun importFromBytes(fileName: String, bytes: ByteArray): Result<ImportResult> = runCatching {
        if (isPng(bytes)) {
            val chunks = extractPngTextChunks(bytes)
            // Check for embedded system prompt first if explicitly tagged
            chunks["parchment_prompt"]?.let { payload ->
                val jsonText = decodeBase64OrRawJson(payload)
                val preset = parseSystemPromptJson(jsonText)
                return@runCatching ImportResult.SystemPromptImported(preset, "PNG (parchment_prompt chunk)")
            }

            // Check ccv3, chara, or parchment_card chunks
            val rawChunk = chunks["ccv3"]
                ?: chunks["chara"]
                ?: chunks["parchment_card"]
                ?: chunks.entries.firstOrNull {
                    it.key.equals("ccv3", ignoreCase = true) ||
                        it.key.equals("chara", ignoreCase = true) ||
                        it.key.contains("prompt", ignoreCase = true) ||
                        it.key.contains("card", ignoreCase = true)
                }?.value
                ?: error("PNG file does not contain an embedded instruction chunk (checked tEXt/iTXt for chara, ccv3, parchment_card, parchment_prompt).")

            val jsonText = decodeBase64OrRawJson(rawChunk)
            val root = prettyJson.parseToJsonElement(jsonText).jsonObject
            if (root.containsKey("systemPrompt") && !root.containsKey("firstMessage") && !root.containsKey("first_mes") && !root.containsKey("data")) {
                return@runCatching ImportResult.SystemPromptImported(
                    parseSystemPromptJson(jsonText),
                    "PNG Embedded System Prompt"
                )
            }
            val card = parseCardJson(jsonText)
            ImportResult.CardImported(card, "PNG Embedded Metadata")
        } else {
            val text = bytes.decodeToString().trim().removePrefix("\uFEFF")
            val root = prettyJson.parseToJsonElement(text).jsonObject
            when {
                root.containsKey("canvasBgHex") && root.containsKey("primaryInkHex") -> {
                    val theme = prettyJson.decodeFromString<ThemeConfig>(text)
                    ImportResult.ThemeImported(theme)
                }
                root.containsKey("spec") || root.containsKey("data") || root.containsKey("first_mes") || root.containsKey("firstMessage") || root.containsKey("personality") || root.containsKey("scenario") -> {
                    val card = parseCardJson(text)
                    ImportResult.CardImported(card, "JSON Card")
                }
                else -> {
                    val preset = parseSystemPromptJson(text)
                    ImportResult.SystemPromptImported(preset, "JSON System Prompt")
                }
            }
        }
    }

    fun parseCardJson(jsonText: String): CharacterOrScenarioCard {
        val root = prettyJson.parseToJsonElement(jsonText).jsonObject
        val spec = root["spec"]?.jsonPrimitive?.content ?: "parchment-v1"
        val dataObj = root["data"]?.let { it as? JsonObject } ?: root

        fun JsonObject.str(vararg keys: String): String {
            for (k in keys) {
                val el = this[k]
                if (el is JsonPrimitive && el.content.isNotBlank()) {
                    return el.content
                }
            }
            return ""
        }

        fun JsonObject.strList(vararg keys: String): List<String> {
            for (k in keys) {
                val el = this[k]
                if (el is JsonArray) {
                    return el.mapNotNull { (it as? JsonPrimitive)?.content }.filter { it.isNotBlank() }
                }
            }
            return emptyList()
        }

        // Extract character_book lore entries (V2 / V3 card format) into Composable Fragments
        val parsedFragments = mutableListOf<ContextFragment>()
        val charBookObj = (dataObj["character_book"] as? JsonObject) ?: (root["character_book"] as? JsonObject)
        val entriesArr = (charBookObj?.get("entries") as? JsonArray) ?: (charBookObj?.get("entries") as? JsonObject)?.values?.let { JsonArray(it.toList()) }
        if (entriesArr != null) {
            entriesArr.forEachIndexed { idx, entryEl ->
                val entry = entryEl as? JsonObject ?: return@forEachIndexed
                val content = entry.str("content", "entry")
                if (content.isBlank()) return@forEachIndexed
                val keys = entry.strList("keys", "key", "keywords")
                val title = entry.str("name", "comment", "title").ifBlank {
                    keys.firstOrNull() ?: "Lore Fragment #${idx + 1}"
                }
                val enabled = entry["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                val constant = entry["constant"]?.jsonPrimitive?.booleanOrNull ?: false
                val mode = when {
                    !enabled -> FragmentActivationMode.MUTED
                    constant || keys.isEmpty() -> FragmentActivationMode.PINNED
                    else -> FragmentActivationMode.KEYWORD
                }
                parsedFragments.add(
                    ContextFragment(
                        id = "cb-frag-$idx-${kotlin.random.Random.nextInt(1000, 9999)}",
                        title = title,
                        content = content,
                        keywords = keys,
                        mode = mode
                    )
                )
            }
        }

        return CharacterOrScenarioCard(
            name = dataObj.str("name", "char_name", "title").ifBlank { "Imported Card" },
            description = dataObj.str("description", "char_persona"),
            personality = dataObj.str("personality"),
            scenario = dataObj.str("scenario", "world_scenario"),
            firstMessage = dataObj.str("first_mes", "firstMessage", "greeting"),
            alternateGreetings = dataObj.strList("alternate_greetings", "alternateGreetings"),
            exampleDialogue = dataObj.str("mes_example", "exampleDialogue", "example_dialogue"),
            systemPrompt = dataObj.str("system_prompt", "systemPrompt"),
            postHistoryInstructions = dataObj.str("post_history_instructions", "postHistoryInstructions", "jailbreak"),
            creatorNotes = dataObj.str("creator_notes", "creatorNotes", "comment"),
            tags = dataObj.strList("tags"),
            embeddedFragments = parsedFragments,
            sourceFormat = spec
        )
    }

    fun parseSystemPromptJson(jsonText: String): SystemPromptPreset {
        val root = prettyJson.parseToJsonElement(jsonText).jsonObject
        fun JsonObject.str(vararg keys: String): String {
            for (k in keys) {
                val el = this[k]
                if (el is JsonPrimitive && el.content.isNotBlank()) return el.content
            }
            return ""
        }
        return SystemPromptPreset(
            id = root.str("id").ifBlank { "prompt-${kotlin.random.Random.nextInt(10000, 99999)}" },
            name = root.str("name", "title").ifBlank { "Imported System Prompt" },
            description = root.str("description", "notes"),
            systemPrompt = root.str("systemPrompt", "system_prompt", "prompt", "content", "instructions"),
            authorsNoteDefault = root.str("authorsNoteDefault", "authors_note", "post_history_instructions"),
            suggestedTemperature = root["suggestedTemperature"]?.jsonPrimitive?.doubleOrNull
                ?: root["temperature"]?.jsonPrimitive?.doubleOrNull ?: 0.85,
            suggestedTopP = root["suggestedTopP"]?.jsonPrimitive?.doubleOrNull
                ?: root["top_p"]?.jsonPrimitive?.doubleOrNull ?: 0.95,
            suggestedThinkingEffort = root.str("suggestedThinkingEffort", "thinking_effort").ifBlank { "high" }
        )
    }

    /**
     * Exports a CharacterOrScenarioCard to clean V2/V3 + Parchment compatible JSON string.
     */
    fun exportCardToJson(card: CharacterOrScenarioCard): String {
        val jsonObj = buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put("name", card.name)
            put("description", card.description)
            put("personality", card.personality)
            put("scenario", card.scenario)
            put("first_mes", card.firstMessage)
            put("mes_example", card.exampleDialogue)
            put("system_prompt", card.systemPrompt)
            put("post_history_instructions", card.postHistoryInstructions)
            putJsonObject("data") {
                put("name", card.name)
                put("description", card.description)
                put("personality", card.personality)
                put("scenario", card.scenario)
                put("first_mes", card.firstMessage)
                putJsonArray("alternate_greetings") {
                    card.alternateGreetings.forEach { add(JsonPrimitive(it)) }
                }
                put("mes_example", card.exampleDialogue)
                put("system_prompt", card.systemPrompt)
                put("post_history_instructions", card.postHistoryInstructions)
                put("creator_notes", card.creatorNotes)
                putJsonArray("tags") {
                    card.tags.forEach { add(JsonPrimitive(it)) }
                }
                if (card.embeddedFragments.isNotEmpty()) {
                    putJsonObject("character_book") {
                        putJsonArray("entries") {
                            card.embeddedFragments.forEachIndexed { idx, frag ->
                                add(buildJsonObject {
                                    put("id", idx)
                                    put("name", frag.title)
                                    put("comment", frag.title)
                                    put("content", frag.content)
                                    put("enabled", frag.mode != FragmentActivationMode.MUTED)
                                    put("constant", frag.mode == FragmentActivationMode.PINNED)
                                    putJsonArray("keys") {
                                        frag.keywords.forEach { add(JsonPrimitive(it)) }
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
        return prettyJson.encodeToString(JsonObject.serializer(), jsonObj)
    }

    /**
     * Exports a CharacterOrScenarioCard baked inside a valid PNG file (`tEXt` chunks `chara` and `ccv3`).
     * If `basePngBytes` is null, generates a clean 32x32 Warm Sepia Parchment PNG automatically.
     */
    fun exportCardToPngBytes(card: CharacterOrScenarioCard, basePngBytes: ByteArray? = null): ByteArray {
        val cardJson = exportCardToJson(card)
        val base64Payload = Base64.encode(cardJson.encodeToByteArray())
        val targetPng = if (basePngBytes != null && isPng(basePngBytes)) {
            basePngBytes
        } else {
            generateMinimalSepiaPng(width = 64, height = 64)
        }
        return injectTextChunksIntoPng(
            pngBytes = targetPng,
            chunks = mapOf(
                "chara" to base64Payload,
                "ccv3" to base64Payload
            )
        )
    }

    /**
     * Exports a SystemPromptPreset either to JSON or embedded inside a PNG (`parchment_prompt` chunk).
     */
    fun exportSystemPromptToJson(preset: SystemPromptPreset): String =
        prettyJson.encodeToString(SystemPromptPreset.serializer(), preset)

    fun exportSystemPromptToPngBytes(preset: SystemPromptPreset, basePngBytes: ByteArray? = null): ByteArray {
        val json = exportSystemPromptToJson(preset)
        val base64Payload = Base64.encode(json.encodeToByteArray())
        val targetPng = if (basePngBytes != null && isPng(basePngBytes)) {
            basePngBytes
        } else {
            generateMinimalSepiaPng(width = 64, height = 64)
        }
        return injectTextChunksIntoPng(
            pngBytes = targetPng,
            chunks = mapOf("parchment_prompt" to base64Payload)
        )
    }

    fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        for (i in 0 until 8) {
            if (bytes[i] != PNG_SIGNATURE[i]) return false
        }
        return true
    }

    private fun decodeBase64OrRawJson(payload: String): String {
        val trimmed = payload.trim()
        if (trimmed.startsWith("{")) return trimmed
        return runCatching {
            Base64.decode(trimmed).decodeToString()
        }.getOrElse {
            // Try URL-safe or padded Base64
            val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
            Base64.decode(padded).decodeToString()
        }
    }

    /**
     * Reads all `tEXt` and uncompressed `iTXt` chunks from a PNG byte array.
     */
    fun extractPngTextChunks(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var offset = 8 // Skip PNG signature
        while (offset + 8 <= bytes.size) {
            val length = readIntBigEndian(bytes, offset)
            if (length < 0 || offset + 12 + length > bytes.size) break
            val type = bytes.decodeToString(offset + 4, offset + 8)
            val dataStart = offset + 8
            val dataEnd = dataStart + length

            if (type == "tEXt") {
                val nulIndex = (dataStart until dataEnd).firstOrNull { bytes[it] == 0.toByte() }
                if (nulIndex != null) {
                    val keyword = bytes.decodeToString(dataStart, nulIndex)
                    val text = bytes.decodeToString(nulIndex + 1, dataEnd)
                    result[keyword] = text
                }
            } else if (type == "iTXt") {
                val nul1 = (dataStart until dataEnd).firstOrNull { bytes[it] == 0.toByte() }
                if (nul1 != null && nul1 + 3 <= dataEnd) {
                    val keyword = bytes.decodeToString(dataStart, nul1)
                    val compressionFlag = bytes[nul1 + 1].toInt()
                    // Skip compressionFlag (1), compressionMethod (1), langTag (nul), translatedKeyword (nul)
                    var cursor = nul1 + 3
                    while (cursor < dataEnd && bytes[cursor] != 0.toByte()) cursor++
                    cursor++ // skip nul after langTag
                    while (cursor < dataEnd && bytes[cursor] != 0.toByte()) cursor++
                    cursor++ // skip nul after translatedKeyword
                    if (compressionFlag == 0 && cursor <= dataEnd) {
                        val text = bytes.decodeToString(cursor, dataEnd)
                        result[keyword] = text
                    }
                }
            } else if (type == "IEND") {
                break
            }
            offset = dataEnd + 4 // Skip 4-byte CRC
        }
        return result
    }

    /**
     * Injects or replaces `tEXt` chunks right before the `IEND` chunk in a PNG image.
     */
    fun injectTextChunksIntoPng(pngBytes: ByteArray, chunks: Map<String, String>): ByteArray {
        val output = ArrayList<Byte>(pngBytes.size + chunks.values.sumOf { it.length + 32 })
        for (b in PNG_SIGNATURE) output.add(b)

        var offset = 8
        val keysToReplace = chunks.keys.map { it.lowercase() }.toSet()

        while (offset + 8 <= pngBytes.size) {
            val length = readIntBigEndian(pngBytes, offset)
            if (length < 0 || offset + 12 + length > pngBytes.size) break
            val type = pngBytes.decodeToString(offset + 4, offset + 8)
            val dataStart = offset + 8
            val dataEnd = dataStart + length

            var skipExisting = false
            if (type == "tEXt" || type == "iTXt") {
                val nulIndex = (dataStart until dataEnd).firstOrNull { pngBytes[it] == 0.toByte() }
                if (nulIndex != null) {
                    val key = pngBytes.decodeToString(dataStart, nulIndex).lowercase()
                    if (key in keysToReplace) {
                        skipExisting = true
                    }
                }
            }

            if (type == "IEND") {
                // Write our new tEXt chunks right before IEND
                for ((key, value) in chunks) {
                    val keyBytes = key.encodeToByteArray()
                    val valBytes = value.encodeToByteArray()
                    val chunkData = ByteArray(keyBytes.size + 1 + valBytes.size)
                    keyBytes.copyInto(chunkData, 0)
                    chunkData[keyBytes.size] = 0
                    valBytes.copyInto(chunkData, keyBytes.size + 1)
                    appendPngChunk(output, "tEXt", chunkData)
                }
            }

            if (!skipExisting) {
                for (i in offset until (dataEnd + 4)) {
                    output.add(pngBytes[i])
                }
            }

            if (type == "IEND") break
            offset = dataEnd + 4
        }

        return output.toByteArray()
    }

    /**
     * Generates a valid, uncompressed 24-bit RGB PNG filled with warm Sepia parchment (#F4ECD8)
     * and an antique brass border (#B57C2E) in pure Kotlin with zero platform image dependencies.
     */
    fun generateMinimalSepiaPng(width: Int = 64, height: Int = 64): ByteArray {
        val out = ArrayList<Byte>(8192)
        for (b in PNG_SIGNATURE) out.add(b)

        // 1. IHDR chunk (13 bytes)
        val ihdr = ByteArray(13)
        writeIntBigEndian(ihdr, 0, width)
        writeIntBigEndian(ihdr, 4, height)
        ihdr[8] = 8  // Bit depth: 8
        ihdr[9] = 2  // Color type: 2 (Truecolor RGB)
        ihdr[10] = 0 // Compression: 0
        ihdr[11] = 0 // Filter: 0
        ihdr[12] = 0 // Interlace: 0
        appendPngChunk(out, "IHDR", ihdr)

        // 2. Raw scanlines (each row starts with filter byte 0, followed by width * 3 RGB bytes)
        val rowLen = 1 + width * 3
        val rawImage = ByteArray(height * rowLen)
        for (y in 0 until height) {
            val rowStart = y * rowLen
            rawImage[rowStart] = 0 // Filter type None
            for (x in 0 until width) {
                val px = rowStart + 1 + x * 3
                val isBorder = x < 3 || x >= width - 3 || y < 3 || y >= height - 3
                if (isBorder) {
                    // Antique Brass (#B57C2E)
                    rawImage[px] = 0xB5.toByte()
                    rawImage[px + 1] = 0x7C.toByte()
                    rawImage[px + 2] = 0x2E.toByte()
                } else {
                    // Warm Parchment (#F4ECD8)
                    rawImage[px] = 0xF4.toByte()
                    rawImage[px + 1] = 0xEC.toByte()
                    rawImage[px + 2] = 0xD8.toByte()
                }
            }
        }

        // Wrap rawImage in a valid uncompressed zlib stream (CMF=0x78, FLG=0x01, deflate store blocks, Adler32)
        val zlibData = wrapUncompressedZlib(rawImage)
        appendPngChunk(out, "IDAT", zlibData)

        // 3. IEND chunk
        appendPngChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun wrapUncompressedZlib(raw: ByteArray): ByteArray {
        val maxBlock = 65535
        val numBlocks = (raw.size + maxBlock - 1) / maxBlock
        val out = ByteArray(2 + numBlocks * 5 + raw.size + 4)
        var pos = 0
        out[pos++] = 0x78
        out[pos++] = 0x01

        var remaining = raw.size
        var srcOffset = 0
        while (remaining > 0) {
            val blockLen = minOf(remaining, maxBlock)
            val isFinal = (remaining - blockLen) == 0
            out[pos++] = if (isFinal) 0x01 else 0x00
            out[pos++] = (blockLen and 0xFF).toByte()
            out[pos++] = ((blockLen ushr 8) and 0xFF).toByte()
            val nlen = blockLen.inv() and 0xFFFF
            out[pos++] = (nlen and 0xFF).toByte()
            out[pos++] = ((nlen ushr 8) and 0xFF).toByte()
            raw.copyInto(out, pos, srcOffset, srcOffset + blockLen)
            pos += blockLen
            srcOffset += blockLen
            remaining -= blockLen
        }

        val adler = computeAdler32(raw)
        writeIntBigEndian(out, pos, adler)
        return out
    }

    private fun appendPngChunk(out: MutableList<Byte>, type: String, data: ByteArray) {
        val lenBytes = ByteArray(4)
        writeIntBigEndian(lenBytes, 0, data.size)
        for (b in lenBytes) out.add(b)

        val typeBytes = type.encodeToByteArray()
        for (b in typeBytes) out.add(b)
        for (b in data) out.add(b)

        val crc = computeCrc32(typeBytes, data)
        val crcBytes = ByteArray(4)
        writeIntBigEndian(crcBytes, 0, crc)
        for (b in crcBytes) out.add(b)
    }

    private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun writeIntBigEndian(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    private val crcTable = IntArray(256).apply {
        for (n in 0 until 256) {
            var c = n
            for (k in 0 until 8) {
                c = if ((c and 1) != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
            }
            this[n] = c
        }
    }

    private fun computeCrc32(typeBytes: ByteArray, dataBytes: ByteArray): Int {
        var c = -1
        for (b in typeBytes) {
            c = crcTable[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        }
        for (b in dataBytes) {
            c = crcTable[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        }
        return c xor -1
    }

    private fun computeAdler32(data: ByteArray): Int {
        var a = 1
        var b = 0
        val mod = 65521
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % mod
            b = (b + a) % mod
        }
        return (b shl 16) or a
    }
}
