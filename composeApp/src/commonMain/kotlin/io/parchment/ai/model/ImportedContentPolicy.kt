package io.parchment.ai.model

private val UNSAFE_MEDIA_SEGMENT = Regex("[^a-zA-Z0-9._-]")

/** True only for links a browser should open: never a local path or another scheme. */
fun isWebLink(target: String): Boolean {
    val value = target.trim()
    return value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)
}

/**
 * Imported chats are untrusted, so a media reference may only name a file inside app storage.
 * Keeps the portable `chats/<id>/media/<name>` or `media/<name>` form, sanitized the same way
 * archive extraction names files. Anything else, including absolute and network paths, is dropped.
 */
fun portableImportedMediaPath(rawPath: String): String {
    val segments = rawPath.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty() || segments.any { it == "." || it == ".." }) return ""
    val chats = segments.lastIndexOf("chats")
    val portable = when {
        chats >= 0 && chats + 4 == segments.size && segments[chats + 2] == "media" ->
            listOf("chats", segments[chats + 1], "media", segments.last())
        segments.size >= 2 && segments[segments.size - 2] == "media" -> listOf("media", segments.last())
        else -> return ""
    }
    return portable.joinToString("/") { it.trim().replace(UNSAFE_MEDIA_SEGMENT, "_") }
}

internal fun ChatSession.withPortableImportedMedia(): ChatSession = copy(
    messages = messages.map { node ->
        node.copy(variants = node.variants.map { variant ->
            variant.copy(
                attachments = variant.attachments.map {
                    it.copy(localFilePath = portableImportedMediaPath(it.localFilePath))
                },
                mediaItems = variant.mediaItems.map {
                    it.copy(
                        localFilePath = portableImportedMediaPath(it.localFilePath),
                        remoteUrl = it.remoteUrl.takeIf(::isWebLink).orEmpty()
                    )
                }
            )
        })
    }
)

/** Windows contacts network paths as soon as they are read, so imported settings must not name one. */
internal fun ThemeConfig.withoutNetworkFontPaths(): ThemeConfig {
    fun local(path: String) = path.takeUnless { it.trim().replace('\\', '/').startsWith("//") }.orEmpty()
    return copy(customProseFontPath = local(customProseFontPath), customCodeFontPath = local(customCodeFontPath))
}
