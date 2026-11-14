package io.parchment.ai.platform

import androidx.compose.ui.text.font.FontFamily
import io.parchment.ai.model.StorageFileCategory
import io.parchment.ai.model.StoredAppFileItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Shared JVM helpers for inspecting/cleaning app storage files and caching loaded custom fonts. */
internal object AppStorageInventory {
    fun wipeDirectory(dir: File, preserveRoot: Boolean): Boolean = runCatching {
        if (preserveRoot) {
            dir.listFiles()?.forEach { it.deleteRecursively() }
        } else if (dir.exists()) {
            dir.deleteRecursively()
        }
        true
    }.getOrDefault(false)

    fun listFiles(
        storageRoot: File,
        extraOtaDir: File? = null,
        extraOtaRelativePrefix: String = "temp/parchment-ota/",
        otaTempExtensions: Set<String> = setOf(".tmp")
    ): List<StoredAppFileItem> = runCatching {
        val rootDir = storageRoot.canonicalFile
        val rootPath = rootDir.toPath().normalize()
        val items = mutableListOf<StoredAppFileItem>()

        if (rootDir.exists() && rootDir.isDirectory) {
            rootDir.walkTopDown()
                .maxDepth(6)
                .filter { it.isFile }
                .forEach { file ->
                    val rel = runCatching { rootPath.relativize(file.canonicalFile.toPath()).toString().replace('\\', '/') }
                        .getOrDefault(file.name)
                    items.add(
                        StoredAppFileItem(
                            fullPath = file.absolutePath,
                            relativePath = rel,
                            fileName = file.name,
                            category = categorizeStorageFile(rel, file.name, otaTempExtensions),
                            sizeBytes = file.length(),
                            lastModifiedMs = file.lastModified()
                        )
                    )
                }
        }
        if (extraOtaDir != null && extraOtaDir.exists() && extraOtaDir.isDirectory) {
            extraOtaDir.listFiles()?.filter { it.isFile }?.forEach { file ->
                items.add(
                    StoredAppFileItem(
                        fullPath = file.absolutePath,
                        relativePath = "$extraOtaRelativePrefix${file.name}",
                        fileName = file.name,
                        category = StorageFileCategory.OTA_AND_TEMP,
                        sizeBytes = file.length(),
                        lastModifiedMs = file.lastModified()
                    )
                )
            }
        }
        items.sortedWith(
            compareBy<StoredAppFileItem> { it.category.ordinal }
                .thenByDescending { it.lastModifiedMs }
        )
    }.getOrDefault(emptyList())

    private fun categorizeStorageFile(
        relativePath: String,
        fileName: String,
        otaTempExtensions: Set<String>
    ): StorageFileCategory {
        val lowerRel = relativePath.lowercase()
        val lowerName = fileName.lowercase()
        return when {
            otaTempExtensions.any { lowerName.endsWith(it) } -> StorageFileCategory.OTA_AND_TEMP
            lowerRel.startsWith("chats/") && lowerName.endsWith(".json") -> StorageFileCategory.CHAT_SESSION
            (lowerRel.startsWith("media/") || lowerRel.contains("/media/")) && lowerName.startsWith("att-") ->
                StorageFileCategory.UPLOADED_MEDIA
            lowerRel.startsWith("media/") || lowerRel.contains("/media/") ->
                StorageFileCategory.GENERATED_MEDIA
            else -> StorageFileCategory.SETTINGS_AND_BACKUPS
        }
    }

    fun deleteFile(
        fullPath: String,
        storageRoot: File,
        extraAllowedDir: File? = null,
        beforeDelete: (String) -> Unit
    ): Boolean = runCatching {
        if (fullPath.isBlank()) return false
        val target = File(fullPath).canonicalFile
        val targetPath = target.toPath().normalize()
        val insideStorage = targetPath.startsWith(storageRoot.canonicalFile.toPath().normalize())
        val insideExtra = extraAllowedDir != null &&
            targetPath.startsWith(extraAllowedDir.canonicalFile.toPath().normalize())
        if (!insideStorage && !insideExtra) return false
        if (!target.exists() || !target.isFile) return false
        beforeDelete(target.absolutePath)
        target.delete()
    }.getOrDefault(false)

    fun loadCachedFontFamily(
        fontCache: ConcurrentHashMap<String, FontFamily>,
        fontPath: String,
        buildFamily: (File) -> FontFamily
    ): FontFamily? {
        val cleaned = fontPath.trim().removeSurrounding("\"")
        if (cleaned.isEmpty()) return null
        fontCache[cleaned]?.let { return it }

        return runCatching {
            val file = File(cleaned)
            if (!file.exists() || !file.isFile) return null
            val family = buildFamily(file)
            fontCache[cleaned] = family
            family
        }.getOrNull()
    }
}
