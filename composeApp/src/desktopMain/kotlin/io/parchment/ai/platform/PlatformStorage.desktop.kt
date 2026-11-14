package io.parchment.ai.platform

import androidx.compose.ui.graphics.ImageBitmap
import io.parchment.ai.model.StoredAppFileItem
import java.awt.Desktop
import java.io.File
import java.net.URI

private val DESKTOP_EXECUTABLE_EXTENSIONS = setOf(
    "exe", "com", "bat", "cmd", "msi", "msp", "scr", "pif", "cpl", "lnk", "url", "reg", "inf", "scf", "msc",
    "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta", "jar", "application", "appref-ms", "gadget"
)

/** Attachments can come from imported archives, so opening one must never launch it. */
internal fun isDesktopExecutableName(name: String): Boolean =
    name.trimEnd('.', ' ').substringAfterLast('.', "").lowercase() in DESKTOP_EXECUTABLE_EXTENSIONS

actual object PlatformStorage {
    private val imageCache = BoundedImageCache {
        if (PlatformRuntime.isLowMemoryDevice) 3 to 48L * 1024L * 1024L else 6 to 128L * 1024L * 1024L
    }

    private val snapshotStorage = SnapshotFileStorage(DesktopPlatformHost::resolveStorageDir)

    private val mediaStore by lazy {
        LocalMediaStore(DesktopPlatformHost::resolveStorageDir, ::chatSessionFolder, imageCache::remove)
    }

    actual fun getAppStorageDirectoryPath(): String = DesktopPlatformHost.resolveStorageDir().absolutePath

    actual fun isReadableLocalFile(filePath: String): Boolean =
        File(filePath).let { it.isFile && it.canRead() && it.length() > 0L }

    private fun chatsDir(): File = File(DesktopPlatformHost.resolveStorageDir(), "chats").also {
        if (!it.exists()) it.mkdirs()
    }

    actual fun getChatsStorageDirectoryPath(): String = chatsDir().absolutePath

    private fun chatSessionFolder(sessionId: String): File =
        snapshotStorage.chatDirectory(sessionId)

    actual fun loadAppStateJson(): String? = snapshotStorage.loadSettings()
    actual fun saveAppStateJson(json: String) = snapshotStorage.saveSettings(json)
    actual fun loadChatSessionJsonById(sessionId: String): String? = snapshotStorage.loadChat(sessionId)
    actual fun loadAllChatSessionJsons(): List<String> = snapshotStorage.loadAllChats()
    actual fun chatSessionJsons(): Sequence<String> = snapshotStorage.chats()
    actual fun saveChatSessionJson(sessionId: String, json: String) = snapshotStorage.saveChat(sessionId, json)
    actual fun deleteChatSessionFile(sessionId: String) = snapshotStorage.deleteChat(sessionId)

    actual fun wipeAllLocalData(): Boolean =
        AppStorageInventory.wipeDirectory(DesktopPlatformHost.resolveStorageDir(), preserveRoot = false)

    actual fun listAppStorageFiles(): List<StoredAppFileItem> =
        AppStorageInventory.listFiles(DesktopPlatformHost.resolveStorageDir(), extraOtaDir = DesktopPlatformHost.otaTempDir())

    actual fun deleteAppStorageFile(fullPath: String): Boolean =
        AppStorageInventory.deleteFile(
            fullPath = fullPath,
            storageRoot = DesktopPlatformHost.resolveStorageDir(),
            extraAllowedDir = DesktopPlatformHost.otaTempDir(),
            beforeDelete = imageCache::remove
        )

    internal fun resolveLocalMediaFile(filePath: String): File? = mediaStore.resolve(filePath)

    actual fun saveGeneratedMediaFile(fileName: String, bytes: ByteArray, sessionId: String): String =
        mediaStore.save(fileName, bytes, sessionId)

    actual fun relocateLegacyMediaToChatFolder(filePath: String, sessionId: String): String =
        mediaStore.relocateToChatFolder(filePath, sessionId)

    actual fun createZipArchive(
        entries: List<Pair<String, ByteArray>>,
        fileEntries: List<Pair<String, String>>
    ): ByteArray = ChatArchiveWriter.toByteArray(entries, fileEntries, ::resolveLocalMediaFile)

    actual fun <T> extractZipArchiveToChatStorage(zipBytes: ByteArray, decode: (chatJsons: List<String>) -> T): T =
        ChatArchiveExtractor.extract(zipBytes, DesktopPlatformHost.resolveStorageDir(), decode = decode)

    actual fun localFileSizeBytes(filePath: String): Long = mediaStore.sizeOf(filePath)

    actual fun readBinaryFileBytes(filePath: String): ByteArray? = mediaStore.read(filePath)

    actual fun cachedImageBitmap(filePath: String): ImageBitmap? = imageCache.get(filePath)

    actual fun loadImageBitmapFromFile(filePath: String): ImageBitmap? =
        imageCache.getOrLoad(filePath, ::resolveLocalMediaFile, DesktopImageDecoder::decode)

    actual fun openOrPlayMedia(localFilePathOrUrl: String) {
        runCatching {
            val f = resolveLocalMediaFile(localFilePathOrUrl) ?: File(localFilePathOrUrl)
            if (f.exists()) {
                if (f.isDirectory || !isDesktopExecutableName(f.name)) Desktop.getDesktop().open(f)
            } else if (localFilePathOrUrl.startsWith("http://") || localFilePathOrUrl.startsWith("https://")) {
                Desktop.getDesktop().browse(URI(localFilePathOrUrl))
            }
        }
    }
}
