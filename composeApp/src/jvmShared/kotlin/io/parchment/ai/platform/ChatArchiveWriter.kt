package io.parchment.ai.platform

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reads the whole stream, or returns null as soon as it proves longer than [maxBytes]. */
internal fun readAtMost(input: InputStream, maxBytes: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) return out.toByteArray()
        total += read
        if (total > maxBytes) return null
        out.write(buffer, 0, read)
    }
}

/** Writes a chat bundle straight to its destination, so media files are never held in memory. */
internal class ChatArchiveWriter(output: OutputStream, private val resolveMedia: (String) -> File?) {
    private val buffered = BufferedOutputStream(output, 64 * 1024)
    private val zip = ZipOutputStream(buffered)
    private val seenNames = HashSet<String>()
    private val copyBuffer = ByteArray(8192)

    fun addBytes(rawPath: String, data: ByteArray) {
        val name = acceptName(rawPath) ?: return
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    fun addFile(rawPath: String, localPath: String) {
        val name = acceptName(rawPath) ?: return
        val file = resolveMedia(localPath) ?: return
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(copyBuffer)
                if (read <= 0) break
                zip.write(copyBuffer, 0, read)
            }
        }
        zip.closeEntry()
    }

    /** Completes the archive without closing the destination, which the caller owns and may sync. */
    fun finish() {
        zip.finish()
        buffered.flush()
    }

    private fun acceptName(rawPath: String): String? {
        val name = rawPath.replace('\\', '/').trimStart('/')
        return name.takeIf { it.isNotEmpty() && it.split('/').none { part -> part == ".." } && seenNames.add(it) }
    }

    companion object {
        fun toByteArray(
            entries: List<Pair<String, ByteArray>>,
            fileEntries: List<Pair<String, String>>,
            resolveMedia: (String) -> File?
        ): ByteArray {
            val bytes = ByteArrayOutputStream(maxOf(4096, entries.sumOf { it.second.size }))
            val writer = ChatArchiveWriter(bytes, resolveMedia)
            entries.forEach { (path, data) -> writer.addBytes(path, data) }
            fileEntries.forEach { (path, localPath) -> writer.addFile(path, localPath) }
            writer.finish()
            return bytes.toByteArray()
        }

        /** Consumes [chats] one at a time, so a large export never holds every chat at once. */
        fun writeBundle(output: OutputStream, chats: Sequence<ChatBundleEntry>, resolveMedia: (String) -> File?) {
            val writer = ChatArchiveWriter(output, resolveMedia)
            chats.forEach { chat ->
                writer.addBytes(chat.jsonPath, chat.json)
                chat.mediaFiles.forEach { (path, localPath) -> writer.addFile(path, localPath) }
            }
            writer.finish()
        }
    }
}
