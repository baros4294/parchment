package io.parchment.ai.platform

import java.io.File

/** Finds, saves and relocates media inside app storage the same way on every platform. */
internal class LocalMediaStore(
    private val storageRoot: () -> File,
    private val chatFolder: (String) -> File,
    private val forgetCachedImage: (String) -> Unit
) {
    private val unsafeNameChars = Regex("[^a-zA-Z0-9._-]")

    fun resolve(filePath: String): File? {
        val trimmed = filePath.trim()
        if (trimmed.isEmpty()) return null
        val direct = File(trimmed)
        if (direct.isAbsolute && direct.exists() && direct.isFile) return direct
        val root = storageRoot()
        val relCandidate = File(root, trimmed.replace('\\', '/'))
        if (relCandidate.exists() && relCandidate.isFile) return relCandidate
        // Cross-OS / portable path fallback: extract `chats/<id>/media/<name>` or `media/<name>` suffix
        val norm = trimmed.replace('\\', '/')
        val chatsIdx = norm.indexOf("chats/")
        if (chatsIdx >= 0) {
            val fromChats = File(root, norm.substring(chatsIdx))
            if (fromChats.exists() && fromChats.isFile) return fromChats
        }
        val mediaIdx = norm.lastIndexOf("/media/")
        if (mediaIdx >= 0) {
            val fileNameOnly = norm.substring(mediaIdx + 7)
            val legacyMedia = File(File(root, "media"), fileNameOnly)
            if (legacyMedia.exists() && legacyMedia.isFile) return legacyMedia
        }
        return null
    }

    fun read(filePath: String): ByteArray? = runCatching {
        resolve(filePath)?.takeIf { it.exists() && it.isFile }?.readBytes()
    }.getOrNull()

    fun sizeOf(filePath: String): Long = runCatching {
        resolve(filePath)?.takeIf { it.isFile }?.length() ?: -1L
    }.getOrDefault(-1L)

    fun save(fileName: String, bytes: ByteArray, sessionId: String): String {
        val mediaDir = if (sessionId.isNotBlank()) File(chatFolder(sessionId), "media") else File(storageRoot(), "media")
        if (!mediaDir.exists()) mediaDir.mkdirs()
        val outFile = File(mediaDir, fileName.replace(unsafeNameChars, "_"))
        outFile.writeBytes(bytes)
        forgetCachedImage(outFile.absolutePath)
        forgetCachedImage(fileName)
        return outFile.absolutePath
    }

    /** Moves a shared legacy file into its chat's folder; files elsewhere are copied and left alone. */
    fun relocateToChatFolder(filePath: String, sessionId: String): String {
        if (filePath.isBlank() || sessionId.isBlank()) return filePath
        val resolved = resolve(filePath) ?: return filePath
        val safeSessionId = sessionId.trim().replace(unsafeNameChars, "_").ifBlank { "session" }
        val targetMediaDir = File(chatFolder(safeSessionId), "media")
        val resolvedNorm = resolved.absolutePath.replace('\\', '/')
        val targetDirNorm = targetMediaDir.absolutePath.replace('\\', '/')
        if (resolvedNorm.startsWith("$targetDirNorm/")) return resolved.absolutePath
        return runCatching {
            if (!targetMediaDir.exists()) targetMediaDir.mkdirs()
            val targetFile = File(targetMediaDir, resolved.name)
            if (!targetFile.exists() || targetFile.length() != resolved.length()) {
                resolved.copyTo(targetFile, overwrite = true)
            }
            val legacyRootMediaDir = File(storageRoot(), "media").canonicalFile
            if (resolved.canonicalFile.parentFile == legacyRootMediaDir) {
                runCatching { resolved.delete() }
            }
            targetFile.absolutePath
        }.getOrDefault(resolved.absolutePath)
    }
}
