package io.parchment.ai.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import io.parchment.ai.network.MAX_INLINE_ATTACHMENT_BYTES
import io.parchment.ai.network.OtaStagedDownload
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilenameFilter
import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JFileChooser

private val DESKTOP_EXECUTABLE_EXTENSIONS = setOf(
    "exe", "com", "bat", "cmd", "msi", "msp", "scr", "pif", "cpl", "lnk", "url", "reg", "inf", "scf", "msc",
    "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta", "jar", "application", "appref-ms", "gadget"
)

/** Attachments can come from imported archives, so opening one must never launch it. */
internal fun isDesktopExecutableName(name: String): Boolean =
    name.trimEnd('.', ' ').substringAfterLast('.', "").lowercase() in DESKTOP_EXECUTABLE_EXTENSIONS

actual object PlatformBridge {
    actual fun createRunId(): String = java.util.UUID.randomUUID().toString()
    private val backgroundNoticeState = kotlinx.coroutines.flow.MutableStateFlow("")
    actual val backgroundExecutionNotice: kotlinx.coroutines.flow.StateFlow<String> = backgroundNoticeState
    actual val platformName: String =
        "${System.getProperty("os.name") ?: "Desktop"} (${System.getProperty("os.arch") ?: "universal"})"
    actual val isAndroid: Boolean = false
    actual val isLowMemoryDevice: Boolean by lazy { WindowsMemory.totalPhysicalBytes() in 1 until LOW_MEMORY_BYTES }

    private val fontCache = ConcurrentHashMap<String, FontFamily>()
    private val imageCache = LinkedHashMap<String, ImageBitmap>()

    @Volatile
    var windowStatusCallback: ((String) -> Unit)? = null
    @Volatile
    var dragHoverCallback: ((Boolean) -> Unit)? = null
    @Volatile
    var filesDroppedCallback: ((List<PickedBinaryFile>) -> Unit)? = null
    @Volatile
    private var stateFlushCallback: (() -> Unit)? = null
    @Volatile
    private var backHandlerCallback: (() -> Boolean)? = null
    @Volatile
    private var cachedStorageDir: File? = null

    init {
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    flushPendingStateNow()
                } finally {
                    if (isWindows) windowsPowerLease.close()
                }
            })
        }
    }

    actual fun currentTimeMillis(): Long = System.currentTimeMillis()
    actual fun monotonicTimeMillis(): Long = System.nanoTime() / 1_000_000L

    private fun resolveStorageDir(): File {
        cachedStorageDir?.let { if (it.exists()) return it }
        // 0. Isolate Gradle/JUnit test workers so unit tests NEVER touch the user's real %LOCALAPPDATA%\ParchmentAI folder!
        if (!System.getProperty("org.gradle.test.worker").isNullOrBlank()) {
            val testDir = java.nio.file.Files.createTempDirectory("parchment-test-data-").toFile()
            val ownedPath = testDir.canonicalFile.toPath()
            Runtime.getRuntime().addShutdownHook(Thread({
                val temporaryRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile.toPath()
                if (testDir.canonicalFile.toPath() == ownedPath && ownedPath.startsWith(temporaryRoot) &&
                    testDir.name.startsWith("parchment-test-data-")) {
                    runCatching { AtomicFileStorage.deleteTree(ownedPath) }
                }
            }, "parchment-test-storage-cleanup"))
            cachedStorageDir = testDir
            return testDir
        }
        // 1. Check if portable 'data' directory exists alongside app launcher
        val portableDir = File(System.getProperty("user.dir"), "parchment-portable-data")
        if (portableDir.exists() && portableDir.isDirectory && portableDir.canWrite()) {
            cachedStorageDir = portableDir
            return portableDir
        }
        // 2. Standard isolated per-user app data directory
        val localAppData = System.getenv("LOCALAPPDATA")
        val baseDir = if (!localAppData.isNullOrBlank()) {
            File(localAppData, "ParchmentAI")
        } else {
            File(System.getProperty("user.home"), ".parchment-ai")
        }
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        cachedStorageDir = baseDir
        return baseDir
    }

    actual fun getAppStorageDirectoryPath(): String = resolveStorageDir().absolutePath

    actual fun isReadableLocalFile(filePath: String): Boolean =
        File(filePath).let { it.isFile && it.canRead() && it.length() > 0L }

    private fun chatsDir(): File = File(resolveStorageDir(), "chats").also {
        if (!it.exists()) it.mkdirs()
    }

    actual fun getChatsStorageDirectoryPath(): String = chatsDir().absolutePath


    private fun chatSessionFolder(sessionId: String): File =
        snapshotStorage.chatDirectory(sessionId)

    private val snapshotStorage = SnapshotFileStorage(::resolveStorageDir)

    actual fun loadAppStateJson(): String? = snapshotStorage.loadSettings()
    actual fun saveAppStateJson(json: String) = snapshotStorage.saveSettings(json)
    actual fun loadChatSessionJsonById(sessionId: String): String? = snapshotStorage.loadChat(sessionId)
    actual fun loadAllChatSessionJsons(): List<String> = snapshotStorage.loadAllChats()
    actual fun chatSessionJsons(): Sequence<String> = snapshotStorage.chats()
    actual fun saveChatSessionJson(sessionId: String, json: String) = snapshotStorage.saveChat(sessionId, json)
    actual fun deleteChatSessionFile(sessionId: String) = snapshotStorage.deleteChat(sessionId)

    actual fun registerStateFlushCallback(callback: () -> Unit) {
        stateFlushCallback = callback
    }

    actual fun flushPendingStateNow() {
        runCatching { stateFlushCallback?.invoke() }
    }

    actual fun registerBackHandler(handler: (() -> Boolean)?) {
        backHandlerCallback = handler
    }

    actual fun handleSystemBackPressed(): Boolean {
        return runCatching { backHandlerCallback?.invoke() == true }.getOrDefault(false)
    }

    actual fun isIgnoringBatteryOptimizations(): Boolean = true

    actual fun requestUnrestrictedBackgroundBattery() {}

    actual fun wipeAllLocalData(): Boolean = runCatching {
        val dir = resolveStorageDir()
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        true
    }.getOrDefault(false)

    actual fun listAppStorageFiles(): List< io.parchment.ai.model.StoredAppFileItem> = runCatching {
        val rootDir = resolveStorageDir().canonicalFile
        val rootPath = rootDir.toPath().normalize()
        val otaTempDir = File(System.getProperty("java.io.tmpdir"), "parchment-ota")
        val items = mutableListOf<io.parchment.ai.model.StoredAppFileItem>()

        if (rootDir.exists() && rootDir.isDirectory) {
            rootDir.walkTopDown()
                .maxDepth(6)
                .filter { it.isFile }
                .forEach { f ->
                    val rel = runCatching { rootPath.relativize(f.canonicalFile.toPath()).toString().replace('\\', '/') }
                        .getOrDefault(f.name)
                    val lowerRel = rel.lowercase()
                    val lowerName = f.name.lowercase()
                    val cat = when {
                        lowerName.endsWith(".tmp") -> io.parchment.ai.model.StorageFileCategory.OTA_AND_TEMP
                        lowerRel.startsWith("chats/") && lowerName.endsWith(".json") -> io.parchment.ai.model.StorageFileCategory.CHAT_SESSION
                        (lowerRel.startsWith("media/") || lowerRel.contains("/media/")) && lowerName.startsWith("att-") ->
                            io.parchment.ai.model.StorageFileCategory.UPLOADED_MEDIA
                        lowerRel.startsWith("media/") || lowerRel.contains("/media/") ->
                            io.parchment.ai.model.StorageFileCategory.GENERATED_MEDIA
                        else -> io.parchment.ai.model.StorageFileCategory.SETTINGS_AND_BACKUPS
                    }
                    items.add(
                        io.parchment.ai.model.StoredAppFileItem(
                            fullPath = f.absolutePath,
                            relativePath = rel,
                            fileName = f.name,
                            category = cat,
                            sizeBytes = f.length(),
                            lastModifiedMs = f.lastModified()
                        )
                    )
                }
        }
        if (otaTempDir.exists() && otaTempDir.isDirectory) {
            otaTempDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                items.add(
                    io.parchment.ai.model.StoredAppFileItem(
                        fullPath = f.absolutePath,
                        relativePath = "temp/parchment-ota/${f.name}",
                        fileName = f.name,
                        category = io.parchment.ai.model.StorageFileCategory.OTA_AND_TEMP,
                        sizeBytes = f.length(),
                        lastModifiedMs = f.lastModified()
                    )
                )
            }
        }
        items.sortedWith(
            compareBy<io.parchment.ai.model.StoredAppFileItem> { it.category.ordinal }
                .thenByDescending { it.lastModifiedMs }
        )
    }.getOrDefault(emptyList())

    actual fun deleteAppStorageFile(fullPath: String): Boolean = runCatching {
        if (fullPath.isBlank()) return false
        val target = File(fullPath).canonicalFile
        val storageRoot = resolveStorageDir().canonicalFile
        val otaTempDir = File(System.getProperty("java.io.tmpdir"), "parchment-ota").canonicalFile
        val insideStorage = target.toPath().normalize().startsWith(storageRoot.toPath().normalize())
        val insideOta = target.toPath().normalize().startsWith(otaTempDir.toPath().normalize())
        if (!insideStorage && !insideOta) return false
        if (!target.exists() || !target.isFile) return false
        synchronized(imageCache) { imageCache.remove(target.absolutePath) }
        target.delete()
    }.getOrDefault(false)

    actual suspend fun pickFileForImport(allowedExtensions: List<String>): PickedBinaryFile? =
        withContext(Dispatchers.IO) {
            runCatching {
                chooseImportFiles(allowedExtensions).firstOrNull { it.isFile }?.let {
                    pickedFileFromDisk(it, maxPickedFileBytes(allowedExtensions), currentTimeMillis())
                }
            }.getOrNull()
        }

    private fun chooseImportFiles(allowedExtensions: List<String>): List<File> {
        val allowAll = allowedExtensions.isEmpty() || allowedExtensions.contains("*")
        val title = if (allowAll) "Attach Image, Video, or File(s)" else "Import File(s) into Parchment"
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isMultipleMode = true
        if (!allowAll && allowedExtensions.isNotEmpty()) {
            dialog.file = allowedExtensions.joinToString(";") { "*.$it" }
            dialog.filenameFilter = FilenameFilter { _, name ->
                allowedExtensions.any { ext -> name.endsWith(".$ext", ignoreCase = true) }
            }
        }
        dialog.isVisible = true
        return dialog.files?.toList().orEmpty().ifEmpty {
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) listOf(File(dir, file)) else emptyList()
        }
    }

    actual suspend fun <T> pickAndImportChatFiles(decode: (chatJsons: List<String>) -> T): List<ChatImportOutcome<T>> =
        withContext(Dispatchers.IO) {
            chooseImportFiles(listOf("zip", "json")).filter { it.isFile }.map { file ->
                ChatImportOutcome(file.name, runCatching {
                    ChatArchiveExtractor.importChatFile(file.name, FileInputStream(file), resolveStorageDir(), decode = decode)
                })
            }
        }

    /** Refuses an oversized file from its length alone, so none of it is read. */
    internal fun pickedFileFromDisk(target: File, maxBytes: Long, fallbackTimestamp: Long): PickedBinaryFile =
        if (target.length() > maxBytes) {
            PickedBinaryFile(target.name, ByteArray(0), oversized = true)
        } else {
            normalizePickedBinaryFile(rawFileName = target.name, bytes = target.readBytes(), fallbackTimestamp = fallbackTimestamp)
        }

    actual suspend fun saveFileForExport(defaultFileName: String, bytes: ByteArray): String? =
        runCatching { exportStreamed(defaultFileName) { it.write(bytes) } }.getOrNull()

    actual suspend fun exportChatBundle(defaultFileName: String, chats: Sequence<ChatBundleEntry>): String? =
        exportStreamed(defaultFileName) { ChatArchiveWriter.writeBundle(it, chats, ::resolveLocalMediaFile) }

    actual suspend fun exportLocalFile(defaultFileName: String, filePath: String): String? {
        val source = resolveLocalMediaFile(filePath) ?: return null
        return exportStreamed(defaultFileName) { output -> FileInputStream(source).use { it.copyTo(output, 64 * 1024) } }
    }

    private suspend fun exportStreamed(defaultFileName: String, write: (OutputStream) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val dialog = FileDialog(null as Frame?, "Export from Parchment", FileDialog.SAVE)
            dialog.file = defaultFileName
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir == null || file == null) return@withContext null
            File(dir, file).also { AtomicFileStorage.writeStream(it, write = write) }.absolutePath
        }

    actual suspend fun pickWorkspaceDirectory(currentPath: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val chooser = JFileChooser()
                chooser.dialogTitle = "Select Local Workspace Folder for Agent Harness"
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                if (currentPath.isNotBlank()) {
                    val cur = File(currentPath)
                    if (cur.exists()) chooser.currentDirectory = cur
                }
                val res = chooser.showOpenDialog(null)
                if (res == JFileChooser.APPROVE_OPTION && chooser.selectedFile != null) {
                    chooser.selectedFile.absolutePath
                } else null
            }.getOrNull()
        }

    actual suspend fun pickFontFile(dialogTitle: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dialog = FileDialog(null as Frame?, dialogTitle, FileDialog.LOAD)
                dialog.file = "*.ttf;*.otf;*.ttc"
                dialog.filenameFilter = FilenameFilter { _, name ->
                    name.endsWith(".ttf", ignoreCase = true) ||
                        name.endsWith(".otf", ignoreCase = true) ||
                        name.endsWith(".ttc", ignoreCase = true)
                }
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    val selected = File(dir, file)
                    if (selected.exists() && selected.isFile) {
                        selected.absolutePath
                    } else null
                } else null
            }.getOrNull()
        }

    actual fun loadCustomFontFamily(fontPath: String): FontFamily? {
        val cleaned = fontPath.trim().removeSurrounding("\"")
        if (cleaned.isEmpty()) return null
        fontCache[cleaned]?.let { return it }

        return runCatching {
            val file = File(cleaned)
            if (!file.exists() || !file.isFile) return null
            val family = FontFamily(
                Font(file = file, weight = FontWeight.Normal, style = FontStyle.Normal),
                Font(file = file, weight = FontWeight.Medium, style = FontStyle.Normal),
                Font(file = file, weight = FontWeight.SemiBold, style = FontStyle.Normal),
                Font(file = file, weight = FontWeight.Bold, style = FontStyle.Normal),
                Font(file = file, weight = FontWeight.Normal, style = FontStyle.Italic),
                Font(file = file, weight = FontWeight.Bold, style = FontStyle.Italic)
            )
            fontCache[cleaned] = family
            family
        }.getOrNull()
    }

    actual fun copyImageToClipboard(bytes: ByteArray): Boolean = runCatching {
        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return false
        val transferable = object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors(): Array<java.awt.datatransfer.DataFlavor> =
                arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)

            override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor): Boolean =
                flavor == java.awt.datatransfer.DataFlavor.imageFlavor

            override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any = image
        }
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
        true
    }.getOrDefault(false)

    actual fun loadBundledFontFamily(bundleName: String): FontFamily? {
        val key = "bundled:$bundleName"
        fontCache[key]?.let { return it }

        fun readResBytes(resPath: String): ByteArray? =
            Thread.currentThread().contextClassLoader?.getResourceAsStream(resPath)?.use { it.readBytes() }
                ?: PlatformBridge::class.java.classLoader?.getResourceAsStream(resPath)?.use { it.readBytes() }

        return runCatching {
            val family = when (bundleName.lowercase()) {
                "inter" -> {
                    val reg = readResBytes("fonts/Inter-Regular.ttf") ?: return null
                    val ital = readResBytes("fonts/Inter-Italic.ttf") ?: reg
                    FontFamily(
                        Font(identity = "Inter-Regular", data = reg, weight = FontWeight.Normal, style = FontStyle.Normal),
                        Font(identity = "Inter-Medium", data = reg, weight = FontWeight.Medium, style = FontStyle.Normal),
                        Font(identity = "Inter-SemiBold", data = reg, weight = FontWeight.SemiBold, style = FontStyle.Normal),
                        Font(identity = "Inter-Bold", data = reg, weight = FontWeight.Bold, style = FontStyle.Normal),
                        Font(identity = "Inter-Italic", data = ital, weight = FontWeight.Normal, style = FontStyle.Italic),
                        Font(identity = "Inter-BoldItalic", data = ital, weight = FontWeight.Bold, style = FontStyle.Italic)
                    )
                }
                "jetbrains mono", "jetbrainsmono nerd", "jetbrainsmono", "nerd" -> {
                    val reg = readResBytes("fonts/JetBrainsMono-Regular.ttf") ?: return null
                    val semi = readResBytes("fonts/JetBrainsMono-SemiBold.ttf") ?: reg
                    val bold = readResBytes("fonts/JetBrainsMono-Bold.ttf") ?: reg
                    val ital = readResBytes("fonts/JetBrainsMono-Italic.ttf") ?: reg
                    FontFamily(
                        Font(identity = "JBM-Regular", data = reg, weight = FontWeight.Normal, style = FontStyle.Normal),
                        Font(identity = "JBM-Medium", data = reg, weight = FontWeight.Medium, style = FontStyle.Normal),
                        Font(identity = "JBM-SemiBold", data = semi, weight = FontWeight.SemiBold, style = FontStyle.Normal),
                        Font(identity = "JBM-Bold", data = bold, weight = FontWeight.Bold, style = FontStyle.Normal),
                        Font(identity = "JBM-Italic", data = ital, weight = FontWeight.Normal, style = FontStyle.Italic)
                    )
                }
                "latex", "katex", "math" -> {
                    val katexReg = readResBytes("fonts/KaTeX_Main-Regular.ttf") ?: return null
                    val katexBold = readResBytes("fonts/KaTeX_Main-Bold.ttf") ?: katexReg
                    val katexItal = readResBytes("fonts/KaTeX_Math-Italic.ttf") ?: katexReg
                    val notoMath = readResBytes("fonts/NotoSansMath-Regular.ttf") ?: katexReg
                    FontFamily(
                        Font(identity = "KaTeX-Regular", data = katexReg, weight = FontWeight.Normal, style = FontStyle.Normal),
                        Font(identity = "KaTeX-Bold", data = katexBold, weight = FontWeight.Bold, style = FontStyle.Normal),
                        Font(identity = "KaTeX-SemiBold", data = katexBold, weight = FontWeight.SemiBold, style = FontStyle.Normal),
                        Font(identity = "KaTeX-MathItalic", data = katexItal, weight = FontWeight.Normal, style = FontStyle.Italic),
                        Font(identity = "NotoSansMath-Regular", data = notoMath, weight = FontWeight.Medium, style = FontStyle.Normal)
                    )
                }
                else -> return null
            }
            fontCache[key] = family
            family
        }.getOrNull()
    }

    // ========================================================================================
    // Sandboxed Local File System Agent Harness Operations
    // ========================================================================================
    private data class CachedTreeEntry(
        val relPath: String,
        val name: String,
        val file: File,
        val isDirectory: Boolean,
        val length: Long
    )

    private data class CachedWorkspaceTree(
        val rootCanonicalPath: String,
        val timestampMs: Long,
        val entries: List<CachedTreeEntry>
    )

    @Volatile
    private var cachedWorkspaceTree: CachedWorkspaceTree? = null
    private const val WORKSPACE_TREE_CACHE_TTL_MS = 10_000L

    private fun invalidateWorkspaceTreeCache() {
        cachedWorkspaceTree = null
    }

    private fun getOrBuildWorkspaceTree(rootFile: File): List<CachedTreeEntry> {
        val canonPath = rootFile.canonicalPath
        val now = System.currentTimeMillis()
        val existing = cachedWorkspaceTree
        if (existing != null && existing.rootCanonicalPath == canonPath && now - existing.timestampMs <= WORKSPACE_TREE_CACHE_TTL_MS) {
            return existing.entries
        }
        val rootPath = rootFile.toPath().normalize()
        val built = rootFile.walkTopDown()
            .onEnter { d -> (d == rootFile || !WorkspacePathGuard.isIgnoredDirectory(d.name)) && WorkspacePathGuard.canTraverse(rootPath, d.toPath()) }
            .maxDepth(24)
            .filter { it != rootFile && !WorkspacePathGuard.isIgnoredDirectory(it.name) && WorkspacePathGuard.canTraverse(rootPath, it.toPath()) }
            .map { f ->
                val rel = rootPath.relativize(f.toPath()).toString().replace('\\', '/')
                val isDir = f.isDirectory
                CachedTreeEntry(
                    relPath = rel,
                    name = f.name,
                    file = f,
                    isDirectory = isDir,
                    length = if (isDir) 0L else f.length()
                )
            }
            .toList()
        cachedWorkspaceTree = CachedWorkspaceTree(canonPath, now, built)
        return built
    }

    actual fun workspaceList(workspaceRoot: String, relativePath: String, recursive: Boolean): Result<String> = runCatching {
        val target = WorkspacePathGuard.resolve(workspaceRoot, relativePath)
        val rootFile = WorkspacePathGuard.root(workspaceRoot).toFile()
        val rootPath = rootFile.toPath().normalize()
        val dirFile = target.toFile()
        require(dirFile.exists()) { "Directory does not exist: $relativePath" }
        require(dirFile.isDirectory) { "Path is not a directory: $relativePath" }

        val entries = if (recursive && relativePath.trim().isEmpty()) {
            getOrBuildWorkspaceTree(rootFile)
                .take(400)
                .map { e ->
                    if (e.isDirectory) "${e.relPath}/" else "${e.relPath} (${e.length} bytes)"
                }
        } else if (recursive) {
            dirFile.walkTopDown()
                .onEnter { d -> (d == dirFile || !WorkspacePathGuard.isIgnoredDirectory(d.name)) && WorkspacePathGuard.canTraverse(rootPath, d.toPath()) }
                .maxDepth(24)
                .filter { it != dirFile && !WorkspacePathGuard.isIgnoredDirectory(it.name) && WorkspacePathGuard.canTraverse(rootPath, it.toPath()) }
                .take(400)
                .map { f ->
                    val rel = rootPath.relativize(f.toPath()).toString().replace('\\', '/')
                    if (f.isDirectory) "$rel/" else "$rel (${f.length()} bytes)"
                }
                .toList()
        } else {
            (dirFile.listFiles()?.toList() ?: emptyList()).filter { WorkspacePathGuard.canTraverse(rootPath, it.toPath()) }
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .take(300)
                .map { f ->
                    val rel = rootPath.relativize(f.toPath()).toString().replace('\\', '/')
                    if (f.isDirectory) "$rel/" else "$rel (${f.length()} bytes)"
                }
        }

        if (entries.isEmpty()) "(Empty directory)" else entries.joinToString("\n")
    }

    actual fun workspaceGlob(workspaceRoot: String, pattern: String, maxResults: Int): Result<String> = runCatching {
        val rootFile = WorkspacePathGuard.root(workspaceRoot).toFile()
        require(rootFile.exists() && rootFile.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        val cleanPat = pattern.trim().replace('\\', '/').removePrefix("./")
        require(cleanPat.isNotEmpty()) { "Glob pattern must not be empty." }
        val regexStr = buildString {
            append("^")
            var i = 0
            while (i < cleanPat.length) {
                val c = cleanPat[i]
                if (c == '*' && i + 1 < cleanPat.length && cleanPat[i + 1] == '*') {
                    if (i + 2 < cleanPat.length && cleanPat[i + 2] == '/') {
                        append("(?:.*/)?")
                        i += 3
                        continue
                    } else {
                        append(".*")
                        i += 2
                        continue
                    }
                } else if (c == '*') {
                    append("[^/]*")
                } else if (c == '?') {
                    append("[^/]")
                } else if (".+()[]{}|^$\\".contains(c)) {
                    append('\\').append(c)
                } else {
                    append(c)
                }
                i++
            }
            append("$")
        }
        val globRegex = Regex(regexStr, RegexOption.IGNORE_CASE)
        val hasSlash = cleanPat.contains('/')
        val plainSub = cleanPat.replace("*", "")
        val matches = getOrBuildWorkspaceTree(rootFile)
            .asSequence()
            .mapNotNull { e ->
                val matchTarget = if (!hasSlash) e.name else e.relPath
                if (globRegex.matches(matchTarget) || (plainSub.isNotEmpty() && e.relPath.contains(plainSub, ignoreCase = true))) {
                    if (e.isDirectory) "${e.relPath}/" else "${e.relPath} (${e.length} bytes)"
                } else null
            }
            .take(maxResults.coerceIn(1, 500))
            .toList()
        if (matches.isEmpty()) "No files matched glob '$pattern'." else matches.joinToString("\n")
    }

    actual fun workspaceRead(workspaceRoot: String, relativePath: String, startLine: Int, maxLines: Int): Result<String> = runCatching {
        val target = WorkspacePathGuard.resolve(workspaceRoot, relativePath).toFile()
        require(target.exists() && target.isFile) { "File not found: $relativePath" }
        WorkspaceTextReader.read(target, relativePath, startLine, maxLines, maxTotalChars = 250_000)
    }

    actual fun workspaceWrite(workspaceRoot: String, relativePath: String, content: String): Result<String> = runCatching {
        WorkspaceFileEditor.write(workspaceRoot, relativePath, content).also { invalidateWorkspaceTreeCache() }
    }

    actual fun workspaceAppend(workspaceRoot: String, relativePath: String, content: String): Result<String> = runCatching {
        WorkspaceFileEditor.append(workspaceRoot, relativePath, content).also { invalidateWorkspaceTreeCache() }
    }

    actual fun workspaceEdit(
        workspaceRoot: String,
        relativePath: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean
    ): Result<String> = runCatching {
        WorkspaceFileEditor.edit(workspaceRoot, relativePath, oldText, newText, replaceAll)
    }

    actual fun workspaceMultiEdit(
        workspaceRoot: String,
        relativePath: String,
        edits: List<Triple<String, String, Boolean>>
    ): Result<String> = runCatching {
        WorkspaceFileEditor.multiEdit(workspaceRoot, relativePath, edits)
    }

    actual fun workspaceMove(workspaceRoot: String, sourceRelativePath: String, destRelativePath: String): Result<String> = runCatching {
        WorkspaceFileEditor.move(workspaceRoot, sourceRelativePath, destRelativePath).also { invalidateWorkspaceTreeCache() }
    }

    actual fun workspaceDelete(workspaceRoot: String, relativePath: String): Result<String> = runCatching {
        WorkspaceFileEditor.delete(workspaceRoot, relativePath).also { invalidateWorkspaceTreeCache() }
    }
    actual fun workspaceShellExec(
        workspaceRoot: String,
        command: String,
        timeoutSeconds: Int,
        onOutputDelta: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): Result<String> = runCatching {
        val rootDir = WorkspacePathGuard.root(workspaceRoot).toFile()
        require(rootDir.exists() && rootDir.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        val pb = if (isWin) {
            val psCommandWithNoProgress = "\$ProgressPreference = 'SilentlyContinue'; $command"
            val utf16LeBytes = psCommandWithNoProgress.toByteArray(Charsets.UTF_16LE)
            val encodedCommand = java.util.Base64.getEncoder().encodeToString(utf16LeBytes)
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedCommand)
        } else {
            ProcessBuilder("sh", "-c", command)
        }
        pb.directory(rootDir)
        pb.redirectErrorStream(true)
        require(isCancelled?.invoke() != true) { "The owning run was cancelled before shell startup." }
        val proc = pb.start()
        // Close stdin immediately so child CLI processes checking piped stdin never hang waiting for EOF
        runCatching { proc.outputStream.close() }

        val capture = BoundedTextCapture()
        var lastEmitMs = 0L
        val readerThread = Thread {
            runCatching {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val chunk = CharArray(1024)
                    while (true) {
                        val readCount = reader.read(chunk)
                        if (readCount < 0) break
                        capture.append(chunk, readCount)
                        val now = System.currentTimeMillis()
                        if (onOutputDelta != null && now - lastEmitMs >= 100L) {
                            lastEmitMs = now
                            runCatching { onOutputDelta.invoke(capture.snapshot()) }
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "parchment-shell-exec-reader"
            start()
        }

        try {
            val maxWaitMs = timeoutSeconds.coerceIn(1, 300) * 1000L
            val startMs = System.currentTimeMillis()
            var finished = false
            while (System.currentTimeMillis() - startMs < maxWaitMs) {
                if (isCancelled?.invoke() == true) {
                    // Killed by user pressing Stop
                    runCatching { proc.descendants().use { children -> children.forEach { it.destroyForcibly() } } }
                    proc.destroyForcibly()
                    error("Shell command terminated because its owning run stopped or permission was revoked.")
                }
                if (proc.waitFor(100L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
            }
            if (!finished) {
                runCatching { proc.descendants().use { children -> children.forEach { it.destroyForcibly() } } }
                proc.destroyForcibly()
                runCatching { proc.inputStream.close() }
                runCatching { readerThread.join(1500L) }
                val partial = capture.snapshot(4000).trim()
                val partialSuffix = if (partial.isNotEmpty()) "\nPartial output before timeout:\n$partial" else ""
                error("Command timed out after $timeoutSeconds seconds.$partialSuffix")
            }
            runCatching { readerThread.join(2000L) }
            val output = capture.snapshot().trim()
            if (onOutputDelta != null && output.isNotEmpty()) {
                runCatching { onOutputDelta.invoke(output) }
            }
            "Exit Code: ${proc.exitValue()}\n${capture.snapshot(8000).trim()}"
        } finally {
            if (proc.isAlive) {
                runCatching { proc.descendants().use { children -> children.forEach { it.destroyForcibly() } } }
                runCatching { proc.destroyForcibly() }
            }
            runCatching { proc.inputStream.close() }
            runCatching { proc.errorStream.close() }
            runCatching { readerThread.join(1500L) }
            invalidateWorkspaceTreeCache()
        }
    }

    actual fun workspaceSearch(workspaceRoot: String, query: String, fileGlob: String): Result<String> = runCatching {
        val rootFile = WorkspacePathGuard.root(workspaceRoot).toFile()
        val rootPath = rootFile.toPath()
        require(rootFile.exists() && rootFile.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        require(query.isNotBlank()) { "Search query must not be blank." }

        val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
        val cleanFilter = fileGlob.removePrefix("*").trim()
        val candidateEntries = getOrBuildWorkspaceTree(rootFile).filter { e ->
            !e.isDirectory &&
                e.length in 1L..2_000_000L &&
                (cleanFilter.isEmpty() || e.name.contains(cleanFilter, ignoreCase = true) || e.relPath.contains(cleanFilter, ignoreCase = true))
        }

        val hitCount = java.util.concurrent.atomic.AtomicInteger(0)
        val perFileMatches = candidateEntries.parallelStream()
            .map { entry ->
                if (hitCount.get() >= 150) return@map null
                val fileHits = mutableListOf<String>()
                runCatching {
                    require(WorkspacePathGuard.canTraverse(rootPath, entry.file.toPath())) { "Workspace path changed during search." }
                    entry.file.useLines(Charsets.UTF_8) { seq ->
                        seq.forEachIndexed { idx, line ->
                            if (hitCount.get() < 150) {
                                val hit = regex?.containsMatchIn(line) ?: line.contains(query, ignoreCase = true)
                                if (hit) {
                                    val countNow = hitCount.incrementAndGet()
                                    if (countNow <= 150) {
                                        fileHits.add("${entry.relPath}:${idx + 1}: ${line.trim().take(200)}")
                                    }
                                }
                            }
                        }
                    }
                }
                if (fileHits.isEmpty()) null else fileHits
            }
            .filter { it != null }
            .collect(java.util.stream.Collectors.toList())

        val matches = perFileMatches.filterNotNull().flatten()
        if (matches.isEmpty()) "No matches found for '$query'." else matches.joinToString("\n")
    }

    actual fun fetchUrlText(url: String, maxChars: Int, isCancelled: (() -> Boolean)?): Result<String> =
        PublicWebTransport.fetch(url, maxChars, isCancelled)

    actual fun fetchPublicBytes(url: String, maxBytes: Int, isCancelled: (() -> Boolean)?): Result<ByteArray> =
        PublicWebTransport.fetchBytes(url, maxBytes, isCancelled)

    actual val computerControl: ComputerControl get() = DesktopComputerControl
    actual val supportsLocalMcpServers: Boolean = true
    actual fun startMcpProcess(command: String): McpProcess? = DesktopMcpProcess(command)

    private val windowsPowerLease by lazy { WindowsPowerLease() }
    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Volatile
    private var lastReportedDecodeSec: Int = -1

    private fun reportDecodeWindowStatus(text: String) {
        val unavailable = isWindows && windowsPowerLease.snapshot.status == WindowsPowerLeaseStatus.UNAVAILABLE
        backgroundNoticeState.value = if (unavailable) "Keep-awake unavailable: ${windowsPowerLease.snapshot.error}" else ""
        windowStatusCallback?.invoke(text + if (unavailable) " — Keep-awake unavailable" else "")
    }

    actual fun synchronizeBackgroundExecution(owners: Int, runnable: Int, status: String) {
        if (isWindows) windowsPowerLease.reconcile(runnable)
        if (owners == 0) lastReportedDecodeSec = -1
        reportDecodeWindowStatus(status)
    }

    actual fun notifyLongDecodeProgress(modelName: String, outputTokens: Int, elapsedSeconds: Int) {
        if (elapsedSeconds == lastReportedDecodeSec) return
        lastReportedDecodeSec = elapsedSeconds
        if (isWindows) windowsPowerLease.refresh()
        reportDecodeWindowStatus("Parchment — " + modelName + " (+" + outputTokens + " tok • " + elapsedSeconds + "s)")
    }

    actual fun createOtaStagedDownload(assetName: String): OtaStagedDownload {
        val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "Parchment-Windows-Setup.exe" }
        // The installer runs silently and is not signature-checked by the system, so a published checksum is required.
        return OtaStagedFile(File(File(resolveStorageDir(), "ota"), safeName), requireDigest = true, installer = ::installOtaExecutable)
    }

    private fun installOtaExecutable(installerFile: File): Result<String> = runCatching {
        // Flush any pending in-memory state to disk BEFORE starting the OTA installer!
        flushPendingStateNow()

        val tempDir = checkNotNull(installerFile.parentFile) { "The update has no folder." }

        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        val installedExe = File(localAppData, "Programs\\Parchment\\Parchment.exe")
        val fallbackExe = File(System.getProperty("user.dir"), "Parchment.exe")
        val relaunchTarget = if (installedExe.exists()) installedExe.absolutePath else fallbackExe.absolutePath

        val currentPid = runCatching { ProcessHandle.current().pid() }.getOrDefault(0L)

        // Write a tiny updater script that waits for the current process PID to exit completely,
        // runs the silent Inno Setup update, and automatically relaunches Parchment.exe
        val psScriptFile = File(tempDir, "apply-ota.ps1")
        psScriptFile.writeText(
            """
            if ($currentPid -gt 0) {
                Wait-Process -Id $currentPid -Timeout 15 -ErrorAction SilentlyContinue
            }
            Start-Sleep -Milliseconds 600
            Start-Process -FilePath "${installerFile.absolutePath}" -ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/CLOSEAPPLICATIONS' -Wait
            if (Test-Path "$relaunchTarget") {
                Start-Process -FilePath "$relaunchTarget"
            } elseif (Test-Path "${installedExe.absolutePath}") {
                Start-Process -FilePath "${installedExe.absolutePath}"
            }
            Remove-Item -Force "${installerFile.absolutePath}" -ErrorAction SilentlyContinue
            """.trimIndent(),
            Charsets.UTF_8
        )

        ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden",
            "-File", psScriptFile.absolutePath
        ).start()

        // Exit current instance after 400ms so the silent installer can replace files cleanly
        Thread {
            Thread.sleep(400L)
            kotlin.system.exitProcess(0)
        }.apply { isDaemon = true }.start()

        "Downloaded ${installerFile.length() / 1024} KB. Applying silent Windows update & restarting Parchment…"
    }

    private val mediaStore by lazy {
        LocalMediaStore(::resolveStorageDir, ::chatSessionFolder) { key -> synchronized(imageCache) { imageCache.remove(key) } }
    }

    private fun resolveLocalMediaFile(filePath: String): File? = mediaStore.resolve(filePath)

    actual fun saveGeneratedMediaFile(fileName: String, bytes: ByteArray, sessionId: String): String =
        mediaStore.save(fileName, bytes, sessionId)

    actual fun relocateLegacyMediaToChatFolder(filePath: String, sessionId: String): String =
        mediaStore.relocateToChatFolder(filePath, sessionId)

    actual fun createZipArchive(
        entries: List<Pair<String, ByteArray>>,
        fileEntries: List<Pair<String, String>>
    ): ByteArray = ChatArchiveWriter.toByteArray(entries, fileEntries, ::resolveLocalMediaFile)

    actual fun <T> extractZipArchiveToChatStorage(zipBytes: ByteArray, decode: (chatJsons: List<String>) -> T): T =
        ChatArchiveExtractor.extract(zipBytes, resolveStorageDir(), decode = decode)

    actual fun localFileSizeBytes(filePath: String): Long = mediaStore.sizeOf(filePath)

    actual fun readBinaryFileBytes(filePath: String): ByteArray? = mediaStore.read(filePath)

    actual fun cachedImageBitmap(filePath: String): ImageBitmap? = synchronized(imageCache) { imageCache[filePath] }

    actual fun loadImageBitmapFromFile(filePath: String): ImageBitmap? {
        if (filePath.isBlank()) return null
        synchronized(imageCache) { imageCache[filePath]?.let { return it } }
        return runCatching {
            val f = resolveLocalMediaFile(filePath) ?: return null
            synchronized(imageCache) { imageCache[f.absolutePath]?.let { return it } }
            val bmp = DesktopImageDecoder.decode(f) ?: return null
            rememberImage(filePath, bmp)
            bmp
        }.getOrNull()
    }

    private fun rememberImage(key: String, bitmap: ImageBitmap) = synchronized(imageCache) {
        imageCache[key] = bitmap
        val (maxImages, maxBytes) = if (isLowMemoryDevice) 3 to 48L * 1024L * 1024L else 6 to 128L * 1024L * 1024L
        while (imageCache.size > maxImages || imageCache.values.sumOf { it.width.toLong() * it.height * 4L } > maxBytes) {
            val eldest = imageCache.keys.firstOrNull()?.takeIf { it != key } ?: break
            imageCache.remove(eldest)
        }
    }

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

    fun extractTransferableFiles(contents: Transferable): List<PickedBinaryFile> = runCatching {
        // 1. Check for copied/dropped files from Explorer (images, GIFs, videos, PDFs, audio, code, etc.)
        if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val fileList = contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
            val picked = fileList?.mapNotNull { item ->
                val f = item as? File
                if (f != null && f.exists() && f.isFile) {
                    runCatching { pickedFileFromDisk(f, MAX_INLINE_ATTACHMENT_BYTES, currentTimeMillis()) }.getOrNull()
                } else null
            }.orEmpty()
            if (picked.isNotEmpty()) return@runCatching picked
        }

        // 2. Check for raw binary image InputStream flavors (preserves animated GIFs & WebP from browsers)
        val rawImageFlavor = contents.transferDataFlavors.firstOrNull { flavor ->
            val mime = "${flavor.primaryType}/${flavor.subType}".lowercase()
            (mime == "image/gif" || mime == "image/webp" || mime == "image/png" || mime == "image/jpeg") &&
                InputStream::class.java.isAssignableFrom(flavor.representationClass)
        }
        if (rawImageFlavor != null) {
            val mime = "${rawImageFlavor.primaryType}/${rawImageFlavor.subType}".lowercase()
            val streamBytes = runCatching {
                (contents.getTransferData(rawImageFlavor) as? InputStream)?.use { readAtMost(it, MAX_INLINE_ATTACHMENT_BYTES) }
            }.getOrNull()
            if (streamBytes != null && streamBytes.isNotEmpty()) {
                return@runCatching listOf(
                    normalizePickedBinaryFile(
                        rawFileName = "",
                        bytes = streamBytes,
                        explicitMimeType = mime,
                        fallbackTimestamp = currentTimeMillis()
                    )
                )
            }
        }

        // 3. Check for raw clipboard/dragged AWT Image (Snipping Tool Win+Shift+S, PrintScreen, Browser Copy Image)
        if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val rawAwtImage = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
            if (rawAwtImage != null) {
                val buffered = if (rawAwtImage is BufferedImage) {
                    rawAwtImage
                } else {
                    // Use ImageIcon's MediaTracker so asynchronous ToolkitImage dimensions (>0) finish loading
                    val loaded = ImageIcon(rawAwtImage).image
                    val w = loaded.getWidth(null).coerceAtLeast(1)
                    val h = loaded.getHeight(null).coerceAtLeast(1)
                    val b = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                    val g = b.createGraphics()
                    g.drawImage(loaded, 0, 0, null)
                    g.dispose()
                    b
                }
                val baos = ByteArrayOutputStream()
                ImageIO.write(buffered, "png", baos)
                val bytes = baos.toByteArray()
                if (bytes.isNotEmpty()) {
                    return@runCatching listOf(
                        normalizePickedBinaryFile(
                            rawFileName = "clipboard-${currentTimeMillis()}.png",
                            bytes = bytes,
                            explicitMimeType = "image/png",
                            fallbackTimestamp = currentTimeMillis()
                        )
                    )
                }
            }
        }

        emptyList()
    }.getOrDefault(emptyList())

    actual fun readClipboardFiles(): List<PickedBinaryFile> = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val contents = clipboard.getContents(null) ?: return@runCatching emptyList()
        extractTransferableFiles(contents)
    }.getOrDefault(emptyList())

    actual fun registerFileDropCallbacks(
        onDragHover: (Boolean) -> Unit,
        onFilesDropped: (List<PickedBinaryFile>) -> Unit
    ) {
        dragHoverCallback = onDragHover
        filesDroppedCallback = onFilesDropped
    }

    @Volatile
    private var activeTtsProcess: Process? = null
    @Volatile
    private var activeSttProcess: Process? = null

    actual fun speakTextAloud(text: String, onFinished: () -> Unit) {
        stopSpeakingAloud()
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        Thread {
            runCatching {
                val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
                if (isWin) {
                    val tempTextFile = File(System.getProperty("java.io.tmpdir"), "parchment-tts-${currentTimeMillis()}.txt")
                    tempTextFile.writeText(cleaned.take(6000), Charsets.UTF_8)
                    val ps = """
                        Add-Type -AssemblyName System.Speech
                        ${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
                        ${'$'}synth.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::NotSet)
                        ${'$'}txt = Get-Content -LiteralPath '${tempTextFile.absolutePath.replace("'", "''")}' -Raw -Encoding UTF8
                        ${'$'}synth.Speak(${'$'}txt)
                        Remove-Item -LiteralPath '${tempTextFile.absolutePath.replace("'", "''")}' -Force -ErrorAction SilentlyContinue
                    """.trimIndent()
                    val proc = ProcessBuilder(
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-WindowStyle", "Hidden",
                        "-Command", ps
                    ).start()
                    activeTtsProcess = proc
                    runCatching { proc.outputStream.close() }
                    runCatching { proc.inputStream.bufferedReader(Charsets.UTF_8).readText() }
                    proc.waitFor()
                    tempTextFile.delete()
                }
            }
            activeTtsProcess = null
            onFinished()
        }.apply { isDaemon = true }.start()
    }

    actual fun stopSpeakingAloud() {
        runCatching {
            activeTtsProcess?.destroyForcibly()
            activeTtsProcess = null
        }
    }


    actual fun startVoiceInput(onRecognizedText: (String) -> Unit, onError: (String) -> Unit) {
        stopVoiceInput()
        Thread {
            runCatching {
                val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
                if (!isWin) {
                    onError("Native voice recognition is supported on Windows and Android.")
                    return@Thread
                }
                val ps = """
                    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                    Add-Type -AssemblyName System.Speech
                    ${'$'}rec = New-Object System.Speech.Recognition.SpeechRecognitionEngine
                    ${'$'}rec.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
                    ${'$'}rec.SetInputToDefaultAudioDevice()
                    ${'$'}res = ${'$'}rec.Recognize([TimeSpan]::FromSeconds(12))
                    if (${'$'}res -ne ${'$'}null -and ${'$'}res.Text) { Write-Output ${'$'}res.Text }
                """.trimIndent()
                val proc = ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-WindowStyle", "Hidden",
                    "-Command", ps
                ).start()
                activeSttProcess = proc
                runCatching { proc.outputStream.close() }
                val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
                proc.waitFor(18L, java.util.concurrent.TimeUnit.SECONDS)
                activeSttProcess = null
                if (output.isNotEmpty()) {
                    onRecognizedText(output)
                } else {
                    onError("No speech detected from microphone.")
                }
            }.onFailure { err ->
                activeSttProcess = null
                onError("Microphone voice input error: ${err.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    actual fun stopVoiceInput() {
        runCatching {
            activeSttProcess?.destroyForcibly()
            activeSttProcess = null
        }
    }
}
