package io.parchment.ai.platform

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File

/** Thread-safe bounded image cache keyed by logical path or resolved absolute file path. */
internal class BoundedImageCache(
    private val limitsProvider: () -> Pair<Int, Long>
) {
    private val entries = LinkedHashMap<String, ImageBitmap>()

    fun get(key: String): ImageBitmap? = synchronized(entries) { entries[key] }

    fun remove(key: String) {
        synchronized(entries) { entries.remove(key) }
    }

    fun getOrLoad(
        filePath: String,
        resolveFile: (String) -> File?,
        decode: (File) -> ImageBitmap?
    ): ImageBitmap? {
        if (filePath.isBlank()) return null
        get(filePath)?.let { return it }
        return runCatching {
            val file = resolveFile(filePath) ?: return null
            get(file.absolutePath)?.let { return it }
            val bitmap = decode(file) ?: return null
            put(filePath, bitmap)
            bitmap
        }.getOrNull()
    }

    private fun put(key: String, bitmap: ImageBitmap) = synchronized(entries) {
        entries[key] = bitmap
        val (maxImages, maxBytes) = limitsProvider()
        while (entries.size > maxImages || totalPixelBytes() > maxBytes) {
            val eldest = entries.keys.firstOrNull()?.takeIf { it != key } ?: break
            entries.remove(eldest)
        }
    }

    private fun totalPixelBytes(): Long =
        entries.values.sumOf { it.width.toLong() * it.height * 4L }
}
