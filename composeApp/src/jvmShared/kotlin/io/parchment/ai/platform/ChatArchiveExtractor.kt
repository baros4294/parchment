package io.parchment.ai.platform

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/** A rejected chat bundle. The message is safe to show the user; nothing was imported. */
internal class ChatArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val MIB = 1024L * 1024L

internal data class ChatArchiveLimits(
    val maxEntries: Int = 20_000,
    val maxChatJsonBytes: Long = 64 * MIB,
    val maxTotalChatJsonBytes: Long = 256 * MIB,
    val maxMediaBytes: Long = 1024 * MIB,
    val maxTotalBytes: Long = 4096 * MIB,
    val minFreeBytes: Long = 64 * MIB,
    val maxTrailingBytes: Long = 64 * MIB
)

/**
 * Imports a chat bundle all-or-nothing: every entry is validated and staged, the chats are decoded,
 * and only then do new media files appear. Existing files are never replaced.
 */
internal object ChatArchiveExtractor {
    private const val STAGING_PREFIX = ".chat-import-"
    private const val DAMAGED = "The archive is damaged or incomplete."
    private const val END_RECORD_SEARCH_BYTES = 65_557
    private val UNSAFE_NAME_CHARS = Regex("[^a-zA-Z0-9._-]")
    private val WINDOWS_DEVICE_NAME = Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")

    private class StagedArchive(val chatJsons: List<String>, val media: Map<String, File>)

    fun <T> extract(
        zipBytes: ByteArray,
        storageRoot: File,
        limits: ChatArchiveLimits = ChatArchiveLimits(),
        decode: (List<String>) -> T
    ): T = extract(ByteArrayInputStream(zipBytes), storageRoot, limits, decode)

    /** Imports one chosen file from a stream: a bundle if it looks like a ZIP, otherwise chat JSON. */
    fun <T> importChatFile(
        fileName: String,
        source: InputStream,
        storageRoot: File,
        limits: ChatArchiveLimits = ChatArchiveLimits(),
        decode: (List<String>) -> T
    ): T = BufferedInputStream(source).use { input ->
        input.mark(2)
        val isZip = (input.read() == 0x50 && input.read() == 0x4B) || fileName.endsWith(".zip", ignoreCase = true)
        input.reset()
        if (isZip) extract(input, storageRoot, limits, decode) else decode(listOf(readChatJson(input, limits.maxTotalChatJsonBytes)))
    }

    private fun readChatJson(input: InputStream, maxBytes: Long): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) return out.toByteArray().decodeToString()
            total += read
            if (total > maxBytes) throw ChatArchiveException("This chat file is too large to import.")
            out.write(buffer, 0, read)
        }
    }

    @Synchronized
    fun <T> extract(
        input: InputStream,
        storageRoot: File,
        limits: ChatArchiveLimits = ChatArchiveLimits(),
        decode: (List<String>) -> T
    ): T {
        val root = storageRoot.canonicalFile
        // Imports are serialized here, so any staging directory left behind is from an interrupted run.
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(STAGING_PREFIX) }.forEach { it.deleteRecursively() }
        val staging = Files.createTempDirectory(root.toPath(), STAGING_PREFIX).toFile()
        try {
            val staged = try {
                stage(input, staging, limits)
            } catch (error: IOException) {
                throw ChatArchiveException(DAMAGED, error)
            } catch (error: IllegalArgumentException) {
                throw ChatArchiveException(DAMAGED, error) // Malformed entry names.
            }
            if (staged.chatJsons.isEmpty()) throw ChatArchiveException("The archive contains no chats.")
            val decoded = decode(staged.chatJsons)
            commit(staged.media, root)
            return decoded
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun stage(input: InputStream, staging: File, limits: ChatArchiveLimits): StagedArchive {
        val chatJsons = mutableListOf<String>()
        val media = LinkedHashMap<String, File>()
        val claimedTargets = HashSet<String>()
        val buffer = ByteArray(8192)
        var entries = 0
        var totalBytes = 0L
        var totalJsonBytes = 0L

        fun copyBounded(source: InputStream, sink: OutputStream?, entryLimit: Long, tooLarge: String): Long {
            var copied = 0L
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) return copied
                copied += read
                totalBytes += read
                if (copied > entryLimit) throw ChatArchiveException(tooLarge)
                if (totalBytes > limits.maxTotalBytes) throw ChatArchiveException("The archive is too large to import.")
                sink?.write(buffer, 0, read)
            }
        }

        val source = TailRetainingInputStream(input)
        ZipInputStream(source).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > limits.maxEntries) throw ChatArchiveException("The archive has too many files.")
                val path = entry.name.replace('\\', '/').trimStart('/')
                if (path.split('/').any { it == ".." }) throw ChatArchiveException("The archive contains an unsafe path.")
                val lowerPath = path.lowercase()
                when {
                    !entry.isDirectory && path.isNotEmpty() && lowerPath.endsWith(".json") -> {
                        val out = ByteArrayOutputStream()
                        totalJsonBytes += copyBounded(zip, out, limits.maxChatJsonBytes, "A chat in the archive is too large to import.")
                        if (totalJsonBytes > limits.maxTotalChatJsonBytes) throw ChatArchiveException("The archive is too large to import.")
                        val json = out.toByteArray().decodeToString()
                        if (json.isNotBlank()) chatJsons += json
                    }
                    !entry.isDirectory && (lowerPath.contains("/media/") || lowerPath.startsWith("media/")) -> {
                        val target = mediaTarget(path)
                        if (!claimedTargets.add(target.lowercase())) {
                            throw ChatArchiveException("The archive contains conflicting media files.")
                        }
                        if (staging.usableSpace < limits.minFreeBytes) throw ChatArchiveException("There is not enough free space to import this archive.")
                        val stagedFile = File(staging, media.size.toString())
                        FileOutputStream(stagedFile).use { copyBounded(zip, it, limits.maxMediaBytes, "A media file in the archive is too large to import.") }
                        media[target] = stagedFile
                    }
                    // Unrecognized entries are discarded, but still count against the decompression limit.
                    else -> copyBounded(zip, null, limits.maxTotalBytes, "The archive is too large to import.")
                }
                zip.closeEntry()
            }
            if (!source.endsWithCentralDirectory(limits.maxTrailingBytes)) throw ChatArchiveException(DAMAGED)
        }
        return StagedArchive(chatJsons, media)
    }

    /** Maps an entry to the storage-relative path that imported chats reference. */
    private fun mediaTarget(path: String): String {
        val relative = if (path.startsWith("chats/", ignoreCase = true)) path.substring(6) else path
        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw ChatArchiveException("The archive contains an unsafe path.")
        val name = safeSegment(parts.last())
        return if (parts.size >= 3 && parts[1].equals("media", ignoreCase = true)) {
            "chats/${safeSegment(parts[0])}/media/$name"
        } else {
            "media/$name"
        }
    }

    private fun safeSegment(raw: String): String {
        val segment = raw.trim().replace(UNSAFE_NAME_CHARS, "_")
        if (segment.isEmpty() || segment == "." || segment.endsWith(".") || WINDOWS_DEVICE_NAME.matches(segment)) {
            throw ChatArchiveException("The archive contains an unsafe path.")
        }
        return segment
    }

    private fun commit(media: Map<String, File>, root: File) {
        // Resolve every conflict first, so a rejected archive leaves storage untouched.
        val moves = media.entries.mapNotNull { (relative, staged) ->
            val target = File(root, relative).canonicalFile
            if (!target.toPath().startsWith(root.toPath())) throw ChatArchiveException("The archive contains an unsafe path.")
            when {
                !target.exists() -> target to staged
                target.isFile && sameContents(target, staged) -> null
                else -> throw ChatArchiveException("The archive would replace a different existing media file, so it was not imported.")
            }
        }
        val moved = mutableListOf<File>()
        try {
            moves.forEach { (target, staged) ->
                target.parentFile?.mkdirs()
                Files.move(staged.toPath(), target.toPath())
                moved += target
            }
        } catch (error: IOException) {
            moved.forEach { it.delete() }
            throw ChatArchiveException("The archive could not be saved, so it was not imported.", error)
        }
    }

    private fun sameContents(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false
        FileInputStream(first).buffered().use { a ->
            FileInputStream(second).buffered().use { b ->
                while (true) {
                    val value = a.read()
                    if (value != b.read()) return false
                    if (value < 0) return true
                }
            }
        }
    }

    /** ZipInputStream stops quietly at an entry boundary, so this keeps the final bytes to prove the archive ended. */
    private class TailRetainingInputStream(source: InputStream) : FilterInputStream(source) {
        private val tail = ByteArray(END_RECORD_SEARCH_BYTES)
        private var nextIndex = 0
        private var stored = 0

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) remember(byteArrayOf(value.toByte()), 0, 1)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) remember(b, off, count)
            return count
        }

        override fun skip(n: Long): Long {
            val scratch = ByteArray(n.coerceIn(1L, 8192L).toInt())
            return maxOf(read(scratch, 0, scratch.size), 0).toLong()
        }

        override fun markSupported(): Boolean = false

        private fun remember(bytes: ByteArray, offset: Int, count: Int) {
            val start = offset + maxOf(0, count - tail.size)
            val length = minOf(count, tail.size)
            val beforeWrap = minOf(length, tail.size - nextIndex)
            bytes.copyInto(tail, nextIndex, start, start + beforeWrap)
            bytes.copyInto(tail, 0, start + beforeWrap, start + length)
            nextIndex = (nextIndex + length) % tail.size
            stored = minOf(tail.size, stored + length)
        }

        /** Reads what remains (the central directory) and reports whether the archive ended properly. */
        fun endsWithCentralDirectory(maxRemainingBytes: Long): Boolean {
            val scratch = ByteArray(8192)
            var remaining = 0L
            while (true) {
                val count = read(scratch, 0, scratch.size)
                if (count <= 0) break
                remaining += count
                if (remaining > maxRemainingBytes) return false
            }
            return hasEndOfCentralDirectory(ByteArray(stored) { tail[(nextIndex - stored + it + tail.size) % tail.size] })
        }
    }

    private fun hasEndOfCentralDirectory(bytes: ByteArray): Boolean {
        val lowest = maxOf(0, bytes.size - END_RECORD_SEARCH_BYTES)
        for (index in bytes.size - 22 downTo lowest) {
            if (bytes[index] == 0x50.toByte() && bytes[index + 1] == 0x4B.toByte() &&
                bytes[index + 2] == 0x05.toByte() && bytes[index + 3] == 0x06.toByte()
            ) return true
        }
        return false
    }
}
