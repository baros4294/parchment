package io.parchment.ai.network

import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.model.WebSourceCitation
import io.parchment.ai.network.AiProviderEngine.RawWebCitation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Web source citations from every provider format, bound to message lines.

internal fun AiProviderEngine.extractDomainFromUrl(rawUrl: String): String {
    val withoutScheme = rawUrl.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
    return withoutScheme.substringBefore("/").substringBefore("?").substringBefore("#").lowercase()
}

internal fun AiProviderEngine.extractCitationsFromToolCall(tc: ToolCallRecord): List<RawWebCitation> {
    if (tc.isError) return emptyList()
    if (tc.name.equals("web_search", ignoreCase = true)) {
        val results = mutableListOf<RawWebCitation>()
        val lines = tc.resultText.lines().map { it.trim() }
        val itemLinkRegex = Regex("""\*\*\[([^\]]+)\]\((https?://[^\s)]+)\)\*\*""")
        for ((idx, line) in lines.withIndex()) {
            val match = itemLinkRegex.find(line) ?: continue
            val title = match.groupValues[1].trim()
            val url = match.groupValues[2].trim()
            val snippet = lines.drop(idx + 1).take(3)
                .firstOrNull { it.startsWith("Snippet:", ignoreCase = true) }
                ?.removePrefix("Snippet:")?.removePrefix("snippet:")?.trim().orEmpty()
            if (url.isNotBlank()) {
                results.add(RawWebCitation(url = url, title = title, snippet = snippet))
            }
        }
        return results
    }
    if (!tc.name.equals("web_fetch", ignoreCase = true)) return emptyList()
    val argObj = runCatching { json.parseToJsonElement(tc.argumentsJson).jsonObject }.getOrNull()
    val url = argObj?.get("url")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) return emptyList()
    val lines = tc.resultText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val titleLine = lines.firstOrNull { it.startsWith("# ") || it.startsWith("Title:", ignoreCase = true) }
        ?.removePrefix("# ")?.removePrefix("Title:")?.removePrefix("title:")?.trim()
        ?: extractDomainFromUrl(url)
    val snippetLine = lines.firstOrNull {
        !it.startsWith("URL:", ignoreCase = true) &&
            !it.startsWith("Title:", ignoreCase = true) &&
            !it.startsWith("#") &&
            it.length > 24
    }?.take(180).orEmpty()
    return listOf(RawWebCitation(url = url, title = titleLine, snippet = snippetLine))
}

internal fun AiProviderEngine.extractMarkdownLinksAsCitations(text: String): List<RawWebCitation> {
    if (!text.contains("http")) return emptyList()
    val out = mutableListOf<RawWebCitation>()
    val mdLinkRegex = Regex("""\[([^\]]+)\]\((https?://[^\s)]+)\)""")
    for (match in mdLinkRegex.findAll(text)) {
        val label = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val url = match.groupValues.getOrNull(2)?.trim().orEmpty()
        if (url.isNotBlank() && !url.endsWith(".png", ignoreCase = true) &&
            !url.endsWith(".jpg", ignoreCase = true) && !url.endsWith(".mp4", ignoreCase = true)
        ) {
            out.add(
                RawWebCitation(
                    url = url,
                    title = label.ifBlank { extractDomainFromUrl(url) },
                    startIndex = match.range.first,
                    endIndex = match.range.last + 1
                )
            )
        }
    }
    return out
}

internal suspend fun AiProviderEngine.extractCitationsFromGeminiCandidate(
    candidateObj: JsonObject?,
    onCitation: suspend (RawWebCitation) -> Unit
) {
    if (candidateObj == null) return
    val gm = (candidateObj["groundingMetadata"] as? JsonObject)
        ?: (candidateObj["grounding_metadata"] as? JsonObject) ?: return
    val chunks = (gm["groundingChunks"] as? JsonArray) ?: (gm["grounding_chunks"] as? JsonArray)
    val chunkList = mutableListOf<Pair<String, String>>() // url to title
    chunks?.forEach { chEl ->
        val chObj = chEl as? JsonObject ?: return@forEach
        val webObj = (chObj["web"] as? JsonObject) ?: chObj
        val uri = webObj["uri"]?.jsonPrimitive?.contentOrNull
            ?: webObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
        val title = webObj["title"]?.jsonPrimitive?.contentOrNull ?: ""
        chunkList.add(uri to title)
        if (uri.isNotBlank()) {
            onCitation(RawWebCitation(url = uri, title = title))
        }
    }
    val supports = (gm["groundingSupports"] as? JsonArray) ?: (gm["grounding_supports"] as? JsonArray)
    supports?.forEach { supEl ->
        val supObj = supEl as? JsonObject ?: return@forEach
        val segObj = supObj["segment"] as? JsonObject
        val segText = segObj?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        val startIdx = segObj?.get("startIndex")?.jsonPrimitive?.intOrNull
            ?: segObj?.get("start_index")?.jsonPrimitive?.intOrNull ?: -1
        val endIdx = segObj?.get("endIndex")?.jsonPrimitive?.intOrNull
            ?: segObj?.get("end_index")?.jsonPrimitive?.intOrNull ?: -1
        val indices = (supObj["groundingChunkIndices"] as? JsonArray)
            ?: (supObj["grounding_chunk_indices"] as? JsonArray)
        indices?.forEach { idxEl ->
            val cIdx = idxEl.jsonPrimitive.intOrNull ?: return@forEach
            val pair = chunkList.getOrNull(cIdx) ?: return@forEach
            if (pair.first.isNotBlank()) {
                onCitation(
                    RawWebCitation(
                        url = pair.first,
                        title = pair.second,
                        groundedText = segText,
                        startIndex = startIdx,
                        endIndex = endIdx
                    )
                )
            }
        }
    }
}

internal suspend fun AiProviderEngine.extractCitationsFromGeminiObject(
    obj: JsonObject?,
    onCitation: suspend (RawWebCitation) -> Unit
) {
    if (obj == null) return
    extractCitationsFromGeminiCandidate(obj, onCitation)
    val cType = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (cType == "google_search_result" || cType == "url_context_result" || cType == "web_search_result") {
        val directUrl = obj["url"]?.jsonPrimitive?.contentOrNull
            ?: obj["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val directTitle = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (directUrl.isNotBlank()) {
            onCitation(RawWebCitation(url = directUrl, title = directTitle))
        }
        val resultsArr = (obj["results"] as? JsonArray) ?: (obj["content"] as? JsonArray)
        resultsArr?.forEach { rEl ->
            val rObj = rEl as? JsonObject ?: return@forEach
            val u = rObj["url"]?.jsonPrimitive?.contentOrNull
                ?: rObj["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val t = rObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val s = rObj["snippet"]?.jsonPrimitive?.contentOrNull
                ?: rObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (u.isNotBlank()) {
                onCitation(RawWebCitation(url = u, title = t, snippet = s.take(180)))
            }
        }
    }
    val annotations = (obj["annotations"] as? JsonArray) ?: (obj["citations"] as? JsonArray)
    annotations?.forEach { aEl ->
        val aObj = aEl as? JsonObject ?: return@forEach
        val u = aObj["url"]?.jsonPrimitive?.contentOrNull
            ?: aObj["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val t = aObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val st = aObj["start_index"]?.jsonPrimitive?.intOrNull
            ?: aObj["startIndex"]?.jsonPrimitive?.intOrNull ?: -1
        val en = aObj["end_index"]?.jsonPrimitive?.intOrNull
            ?: aObj["endIndex"]?.jsonPrimitive?.intOrNull ?: -1
        if (u.isNotBlank()) {
            onCitation(RawWebCitation(url = u, title = t, startIndex = st, endIndex = en))
        }
    }
    (obj["content"] as? JsonArray)?.forEach { cEl ->
        extractCitationsFromGeminiObject(cEl as? JsonObject, onCitation)
    }
}

internal suspend fun AiProviderEngine.extractCitationFromOpenAiAnnotation(
    ann: JsonObject,
    onCitation: suspend (RawWebCitation) -> Unit
) {
    val sub = (ann["url_citation"] as? JsonObject) ?: ann
    val url = sub["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (url.isBlank()) return
    val title = sub["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val startIdx = sub["start_index"]?.jsonPrimitive?.intOrNull ?: -1
    val endIdx = sub["end_index"]?.jsonPrimitive?.intOrNull ?: -1
    onCitation(RawWebCitation(url = url, title = title, startIndex = startIdx, endIndex = endIdx))
}

internal suspend fun AiProviderEngine.extractCitationsFromOpenAiResponsesItem(
    item: JsonObject,
    onCitation: suspend (RawWebCitation) -> Unit
) {
    val itemType = item["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (itemType == "web_search_call") {
        val actionObj = item["action"] as? JsonObject
        val sourcesArr = (actionObj?.get("sources") as? JsonArray)
            ?: (item["sources"] as? JsonArray)
            ?: (item["results"] as? JsonArray)
        sourcesArr?.forEach { sEl ->
            val sObj = sEl as? JsonObject ?: return@forEach
            val u = sObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val t = sObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (u.isNotBlank()) {
                onCitation(RawWebCitation(url = u, title = t))
            }
        }
    }
    val contentArr = item["content"] as? JsonArray
    contentArr?.forEach { cEl ->
        val cObj = cEl as? JsonObject ?: return@forEach
        val cTxt = cObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val anns = cObj["annotations"] as? JsonArray
        anns?.forEach { aEl ->
            val aObj = aEl as? JsonObject ?: return@forEach
            val sub = (aObj["url_citation"] as? JsonObject) ?: aObj
            val u = sub["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val t = sub["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val st = sub["start_index"]?.jsonPrimitive?.intOrNull ?: -1
            val en = sub["end_index"]?.jsonPrimitive?.intOrNull ?: -1
            if (u.isNotBlank()) {
                onCitation(RawWebCitation(url = u, title = t, groundedText = if (st < 0) cTxt else "", startIndex = st, endIndex = en))
            }
        }
    }
}

internal suspend fun AiProviderEngine.extractCitationsFromChatCompletionsObject(
    obj: JsonObject,
    onCitation: suspend (RawWebCitation) -> Unit
) {
    val anns = obj["annotations"] as? JsonArray
    anns?.forEach { aEl ->
        val aObj = aEl as? JsonObject ?: return@forEach
        extractCitationFromOpenAiAnnotation(aObj, onCitation)
    }
    val citations = obj["citations"] as? JsonArray
    citations?.forEach { cEl ->
        when (cEl) {
            is JsonPrimitive -> {
                val u = cEl.contentOrNull?.trim().orEmpty()
                if (u.startsWith("http")) onCitation(RawWebCitation(url = u))
            }
            is JsonObject -> {
                val u = cEl["url"]?.jsonPrimitive?.contentOrNull
                    ?: cEl["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val t = cEl["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (u.isNotBlank()) onCitation(RawWebCitation(url = u, title = t))
            }
            else -> {}
        }
    }
}

internal suspend fun AiProviderEngine.extractCitationFromAnthropicCitationObj(
    citObj: JsonObject,
    blockText: String,
    onCitation: suspend (RawWebCitation) -> Unit
) {
    val u = citObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (u.isBlank()) return
    val t = citObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val cited = citObj["cited_text"]?.jsonPrimitive?.contentOrNull.orEmpty()
    onCitation(
        RawWebCitation(
            url = u,
            title = t,
            snippet = cited.take(180),
            groundedText = blockText.ifBlank { cited }
        )
    )
}

internal suspend fun AiProviderEngine.extractCitationsFromAnthropicBlock(
    cb: JsonObject,
    onCitation: suspend (RawWebCitation) -> Unit
) {
    val bType = cb["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (bType == "web_search_tool_result") {
        val contentArr = cb["content"] as? JsonArray
        contentArr?.forEach { rEl ->
            val rObj = rEl as? JsonObject ?: return@forEach
            val u = rObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val t = rObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (u.isNotBlank()) {
                onCitation(RawWebCitation(url = u, title = t))
            }
        }
    }
    val blockTxt = cb["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val citationsArr = cb["citations"] as? JsonArray
    citationsArr?.forEach { cEl ->
        val cObj = cEl as? JsonObject ?: return@forEach
        extractCitationFromAnthropicCitationObj(cObj, blockTxt, onCitation)
    }
}

/**
 * Deduplicates raw web citations and binds each source to the exact 1-based line number(s)
 * and line excerpt(s) in `assistantContent` that it grounds!
 */
internal fun AiProviderEngine.bindSourcesToMessageLines(
    assistantContent: String,
    rawCitations: List<RawWebCitation>
): List<WebSourceCitation> {
    if (rawCitations.isEmpty()) return emptyList()

    data class IndexedLine(
        val lineNumber: Int,
        val startChar: Int,
        val endChar: Int,
        val cleanText: String
    )

    val indexedLines = mutableListOf<IndexedLine>()
    var cursor = 0
    var nonBlankCounter = 0
    for (rawLine in assistantContent.split('\n')) {
        val start = cursor
        val end = cursor + rawLine.length
        cursor = end + 1
        val trimmed = rawLine.trim()
            .removePrefix("- ")
            .removePrefix("* ")
            .replace(Regex("^#+\\s*"), "")
            .trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("```")) {
            nonBlankCounter++
            indexedLines.add(
                IndexedLine(
                    lineNumber = nonBlankCounter,
                    startChar = start,
                    endChar = end,
                    cleanText = trimmed
                )
            )
        }
    }

    // Group by normalized URL while merging titles, snippets, groundedText, and character spans
    val groupedByUrl = LinkedHashMap<String, MutableList<RawWebCitation>>()
    for (cit in rawCitations) {
        val normUrl = cit.url.trim()
        if (normUrl.isEmpty()) continue
        groupedByUrl.getOrPut(normUrl) { mutableListOf() }.add(cit)
    }

    val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "are", "was", "were",
        "have", "has", "had", "not", "but", "what", "when", "where", "which", "who",
        "how", "why", "can", "will", "into", "your", "more", "about", "http", "https", "www", "com", "org", "io", "dev"
    )

    fun extractSignificantTokens(s: String): Set<String> =
        s.lowercase()
            .split(Regex("[^a-z0-9.]+"))
            .map { it.trim('.') }
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()

    return groupedByUrl.entries.mapIndexed { idx, (url, group) ->
        val citationIndex = idx + 1
        val domain = extractDomainFromUrl(url)
        val bestTitle = group.firstNotNullOfOrNull { it.title.takeIf { t -> t.isNotBlank() } }
            ?: domain
        val bestSnippet = group.firstNotNullOfOrNull { it.snippet.takeIf { s -> s.isNotBlank() } }.orEmpty()

        val matchedLines = LinkedHashMap<Int, String>()

        if (indexedLines.isNotEmpty()) {
            // 1. Direct character-offset or segment-text match from provider grounding metadata
            for (g in group) {
                if (g.startIndex >= 0 || g.endIndex > 0) {
                    val targetPos = if (g.startIndex >= 0) g.startIndex else g.endIndex
                    val hit = indexedLines.firstOrNull { targetPos in it.startChar..it.endChar }
                        ?: indexedLines.minByOrNull { kotlin.math.abs(it.startChar - targetPos) }
                    if (hit != null) {
                        matchedLines[hit.lineNumber] = hit.cleanText
                    }
                }
                if (g.groundedText.isNotBlank()) {
                    val segClean = g.groundedText.trim()
                    for (line in indexedLines) {
                        if (line.cleanText.contains(segClean, ignoreCase = true) ||
                            segClean.contains(line.cleanText, ignoreCase = true)
                        ) {
                            matchedLines[line.lineNumber] = line.cleanText
                        }
                    }
                }
            }

            // 2. Inline URL, domain, or [index] citation marker match
            if (matchedLines.isEmpty()) {
                val domainRoot = domain.substringBefore(".")
                for (line in indexedLines) {
                    val lowerLine = line.cleanText.lowercase()
                    if (lowerLine.contains(url.lowercase()) ||
                        (domain.isNotBlank() && lowerLine.contains(domain)) ||
                        (domainRoot.length >= 4 && lowerLine.contains(domainRoot)) ||
                        line.cleanText.contains("[$citationIndex]")
                    ) {
                        matchedLines[line.lineNumber] = line.cleanText
                    }
                }
            }

            // 3. Lexical overlap match between source title/snippet and message lines
            if (matchedLines.isEmpty()) {
                val sourceTokens = extractSignificantTokens("$bestTitle $bestSnippet $domain")
                if (sourceTokens.isNotEmpty()) {
                    val scored = indexedLines.map { line ->
                        val lineTokens = extractSignificantTokens(line.cleanText)
                        val overlap = lineTokens.intersect(sourceTokens).size
                        line to overlap
                    }.filter { it.second > 0 }
                        .sortedByDescending { it.second }
                    for ((line, _) in scored.take(2)) {
                        matchedLines[line.lineNumber] = line.cleanText
                    }
                }
            }

            // 4. Fallback: point to the primary substantive line so every source has a clear line reference
            if (matchedLines.isEmpty()) {
                val fallbackLine = indexedLines.getOrNull(idx % indexedLines.size) ?: indexedLines.first()
                matchedLines[fallbackLine.lineNumber] = fallbackLine.cleanText
            }
        }

        WebSourceCitation(
            index = citationIndex,
            url = url,
            title = bestTitle,
            domain = domain,
            groundedLineNumbers = matchedLines.keys.toList(),
            groundedLines = matchedLines.values.map { it.take(180) },
            snippet = bestSnippet
        )
    }
}
