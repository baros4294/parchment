package io.parchment.ai.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilenameFilter
import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JFileChooser

actual object PlatformBridge {
    actual val platformName: String =
        "${System.getProperty("os.name") ?: "Desktop"} (${System.getProperty("os.arch") ?: "universal"})"
    actual val isAndroid: Boolean = false

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
    private val activeShellProcesses: MutableSet<Process> = java.util.Collections.synchronizedSet(HashSet())

    init {
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread {
                flushPendingStateNow()
            })
        }
    }

    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    private fun resolveStorageDir(): File {
        // 0. Isolate Gradle/JUnit test workers so unit tests NEVER touch the user's real %LOCALAPPDATA%\ParchmentAI folder!
        if (!System.getProperty("org.gradle.test.worker").isNullOrBlank()) {
            val testDir = File(System.getProperty("java.io.tmpdir"), "parchment-test-data")
            if (!testDir.exists()) testDir.mkdirs()
            return testDir
        }
        // 1. Check if portable 'data' directory exists alongside app launcher
        val portableDir = File(System.getProperty("user.dir"), "parchment-portable-data")
        if (portableDir.exists() && portableDir.isDirectory && portableDir.canWrite()) {
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
        return baseDir
    }

    actual fun getAppStorageDirectoryPath(): String = resolveStorageDir().absolutePath

    private fun chatsDir(): File = File(resolveStorageDir(), "chats").also {
        if (!it.exists()) it.mkdirs()
    }

    actual fun getChatsStorageDirectoryPath(): String = chatsDir().absolutePath

    private fun settingsFile(): File = File(resolveStorageDir(), "parchment-settings.json")
    private fun legacyStateFile(): File = File(resolveStorageDir(), "parchment-state.json")

    private fun sanitizeSessionFileName(sessionId: String): String =
        sessionId.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "session" }

    private fun isBlankDefaultStateJson(json: String): Boolean {
        val hasNoApiKeys = Regex("\"apiKey\":\"[^\"]+\"").find(json) == null
        val hasNoAssistants = json.contains("\"assistants\":[]") || (
            !Regex("\"id\":\"asst-(?!default\")").containsMatchIn(json) &&
                !Regex("\"systemPrompt\":\"[^\"]+\"").containsMatchIn(json)
            )
        val hasNoMessages = !json.contains("\"role\":\"USER\"") && !json.contains("\"role\":\"ASSISTANT\"")
        val hasNoSavedCards = !json.contains("\"savedCards\":[{")
        val hasNoPresets = !json.contains("\"systemPromptPresets\":[{")
        val hasNoGlobalWorldBook = !json.contains("\"globalWorldBook\":[{")
        val isInitialSessionId = json.contains("\"activeSessionId\":\"sess-initial\"") ||
            json.contains("\"activeSessionId\":\"\"")
        return hasNoApiKeys && hasNoAssistants && hasNoMessages &&
            hasNoSavedCards && hasNoPresets && hasNoGlobalWorldBook && isInitialSessionId
    }

    actual fun loadAppStateJson(): String? {
        val dir = resolveStorageDir()
        val f = settingsFile()
        val tmp = File(dir, "parchment-settings.json.tmp")
        val prev = File(dir, "parchment-settings.prev.json")
        val userBak = File(dir, "parchment-settings.user-backup.json")
        val legacy = legacyStateFile()
        val legacyTmp = File(dir, "parchment-state.json.tmp")
        val legacyPrev = File(dir, "parchment-state.prev.json")
        val legacyBak = File(dir, "parchment-state.user-backup.json")

        // Retry up to 6 times (50ms apart) in case the previous process during an OTA restart is still releasing its file handle
        repeat(6) { attempt ->
            val candidates = listOf(f, tmp, prev, userBak, legacy, legacyTmp, legacyPrev, legacyBak)
            for (cand in candidates) {
                val content = runCatching {
                    if (cand.exists() && cand.isFile && cand.length() > 10L) {
                        cand.readText(Charsets.UTF_8)
                    } else null
                }.getOrNull()
                if (!content.isNullOrBlank()) {
                    val activeBak = if (userBak.exists() && userBak.length() > 50L) userBak else legacyBak
                    if (cand != activeBak && isBlankDefaultStateJson(content) && activeBak.exists() && activeBak.length() > 50L) {
                        val bakContent = runCatching { activeBak.readText(Charsets.UTF_8) }.getOrNull()
                        if (!bakContent.isNullOrBlank() && !isBlankDefaultStateJson(bakContent)) {
                            return bakContent
                        }
                    }
                    return content
                }
            }
            if (attempt < 5 && candidates.any { it.exists() }) {
                runCatching { Thread.sleep(50L) }
            }
        }
        return null
    }

    @Synchronized
    actual fun saveAppStateJson(json: String) {
        runCatching {
            val dir = resolveStorageDir()
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "parchment-settings.json.tmp")
            val prev = File(dir, "parchment-settings.prev.json")
            val userBak = File(dir, "parchment-settings.user-backup.json")
            val target = settingsFile()

            val incomingIsBlankDefault = isBlankDefaultStateJson(json)

            // Guard: Never allow a blank default state to overwrite an existing populated user settings file on disk!
            if (incomingIsBlankDefault && userBak.exists() && userBak.length() > 50L) {
                return
            }

            tmp.writeText(json, Charsets.UTF_8)
            if (target.exists() && target.length() > 50L) {
                runCatching { target.copyTo(prev, overwrite = true) }
            }
            if (!tmp.renameTo(target)) {
                target.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
            // Whenever the state contains actual user customizations, persist a protected user backup!
            if (!incomingIsBlankDefault && json.length > 50) {
                runCatching { target.copyTo(userBak, overwrite = true) }
            }
        }
    }

    actual fun loadAllChatSessionJsons(): List<String> = runCatching {
        val dir = chatsDir()
        val files = dir.listFiles()?.toList().orEmpty()
        // Recover any interrupted .tmp files if their primary .json is missing
        files.filter { it.name.endsWith(".json.tmp") }.forEach { tmpFile ->
            val targetName = tmpFile.name.removeSuffix(".tmp")
            val targetFile = File(dir, targetName)
            if ((!targetFile.exists() || targetFile.length() <= 5L) && tmpFile.length() > 10L) {
                runCatching { tmpFile.copyTo(targetFile, overwrite = true) }
            }
        }
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) && it.length() > 5L }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { f -> runCatching { f.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() } }
            .orEmpty()
    }.getOrDefault(emptyList())

    @Synchronized
    actual fun saveChatSessionJson(sessionId: String, json: String) {
        if (sessionId.isBlank() || json.isBlank()) return
        runCatching {
            val dir = chatsDir()
            val safeId = sanitizeSessionFileName(sessionId)
            val target = File(dir, "$safeId.json")
            val tmp = File(dir, "$safeId.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    @Synchronized
    actual fun deleteChatSessionFile(sessionId: String) {
        if (sessionId.isBlank()) return
        runCatching {
            val dir = chatsDir()
            val safeId = sanitizeSessionFileName(sessionId)
            File(dir, "$safeId.json").delete()
            File(dir, "$safeId.json.tmp").delete()
        }
    }

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

    actual suspend fun pickFileForImport(allowedExtensions: List<String>): PickedBinaryFile? =
        pickFilesForImport(allowedExtensions).firstOrNull()

    actual suspend fun pickFilesForImport(allowedExtensions: List<String>): List<PickedBinaryFile> =
        withContext(Dispatchers.IO) {
            runCatching {
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
                val selectedFiles = dialog.files?.toList().orEmpty().ifEmpty {
                    val dir = dialog.directory
                    val file = dialog.file
                    if (dir != null && file != null) listOf(File(dir, file)) else emptyList()
                }
                val baseTs = currentTimeMillis()
                selectedFiles.mapIndexedNotNull { idx, target ->
                    if (target.exists() && target.isFile) {
                        normalizePickedBinaryFile(
                            rawFileName = target.name,
                            bytes = target.readBytes(),
                            fallbackTimestamp = baseTs + idx
                        )
                    } else null
                }
            }.getOrDefault(emptyList())
        }

    actual suspend fun saveFileForExport(defaultFileName: String, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dialog = FileDialog(null as Frame?, "Export from Parchment", FileDialog.SAVE)
                dialog.file = defaultFileName
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    val out = File(dir, file)
                    out.writeBytes(bytes)
                    out.absolutePath
                } else null
            }.getOrNull()
        }

    actual suspend fun exportMultipleFilesToDirectory(files: List<Pair<String, ByteArray>>): String? =
        withContext(Dispatchers.IO) {
            if (files.isEmpty()) return@withContext null
            runCatching {
                val chooser = JFileChooser()
                chooser.dialogTitle = "Select Destination Folder to Export ${files.size} Chat File(s)"
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                val res = chooser.showSaveDialog(null)
                if (res == JFileChooser.APPROVE_OPTION && chooser.selectedFile != null) {
                    val outDir = chooser.selectedFile
                    if (!outDir.exists()) outDir.mkdirs()
                    var written = 0
                    for ((fileName, bytes) in files) {
                        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "chat-${written + 1}.json" }
                        File(outDir, safeName).writeBytes(bytes)
                        written++
                    }
                    "$written file(s) in ${outDir.absolutePath}"
                } else null
            }.getOrNull()
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
                "jetbrainsmono nerd", "jetbrainsmono", "nerd" -> {
                    val reg = readResBytes("fonts/JetBrainsMonoNerdFont-Regular.ttf") ?: return null
                    val semi = readResBytes("fonts/JetBrainsMonoNerdFont-SemiBold.ttf") ?: reg
                    val bold = readResBytes("fonts/JetBrainsMonoNerdFont-Bold.ttf") ?: reg
                    val ital = readResBytes("fonts/JetBrainsMonoNerdFont-Italic.ttf") ?: reg
                    FontFamily(
                        Font(identity = "JBMNerd-Regular", data = reg, weight = FontWeight.Normal, style = FontStyle.Normal),
                        Font(identity = "JBMNerd-Medium", data = reg, weight = FontWeight.Medium, style = FontStyle.Normal),
                        Font(identity = "JBMNerd-SemiBold", data = semi, weight = FontWeight.SemiBold, style = FontStyle.Normal),
                        Font(identity = "JBMNerd-Bold", data = bold, weight = FontWeight.Bold, style = FontStyle.Normal),
                        Font(identity = "JBMNerd-Italic", data = ital, weight = FontWeight.Normal, style = FontStyle.Italic)
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
    private fun resolveSandboxedPath(workspaceRoot: String, relativePath: String): Path {
        val root = File(workspaceRoot).canonicalFile.toPath().normalize()
        val cleanedRel = relativePath.trim().removePrefix("/").removePrefix("\\")
        val resolved = if (cleanedRel.isEmpty() || cleanedRel == ".") {
            root
        } else {
            root.resolve(cleanedRel).normalize()
        }
        require(resolved.startsWith(root)) {
            "Access denied: Path '$relativePath' escapes the mounted workspace root '$workspaceRoot'."
        }
        return resolved
    }

    private fun isIgnoredWorkspaceDir(name: String): Boolean {
        val low = name.lowercase()
        return low == ".git" || low == "build" || low == ".gradle" ||
            low == "node_modules" || low == ".idea" || low == ".kotlin"
    }

    actual fun workspaceList(workspaceRoot: String, relativePath: String, recursive: Boolean): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath)
        val rootPath = File(workspaceRoot).canonicalFile.toPath().normalize()
        val dirFile = target.toFile()
        require(dirFile.exists()) { "Directory does not exist: $relativePath" }
        require(dirFile.isDirectory) { "Path is not a directory: $relativePath" }

        val entries = if (recursive) {
            dirFile.walkTopDown()
                .onEnter { d -> d == dirFile || !isIgnoredWorkspaceDir(d.name) }
                .maxDepth(24)
                .filter { it != dirFile && !isIgnoredWorkspaceDir(it.name) }
                .take(400)
                .map { f ->
                    val rel = rootPath.relativize(f.canonicalFile.toPath()).toString().replace('\\', '/')
                    if (f.isDirectory) "$rel/" else "$rel (${f.length()} bytes)"
                }
                .toList()
        } else {
            (dirFile.listFiles()?.toList() ?: emptyList())
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .take(300)
                .map { f ->
                    val rel = rootPath.relativize(f.canonicalFile.toPath()).toString().replace('\\', '/')
                    if (f.isDirectory) "$rel/" else "$rel (${f.length()} bytes)"
                }
        }

        if (entries.isEmpty()) "(Empty directory)" else entries.joinToString("\n")
    }

    actual fun workspaceGlob(workspaceRoot: String, pattern: String, maxResults: Int): Result<String> = runCatching {
        val rootFile = File(workspaceRoot).canonicalFile
        val rootPath = rootFile.toPath().normalize()
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
        val matches = rootFile.walkTopDown()
            .onEnter { d -> d == rootFile || !isIgnoredWorkspaceDir(d.name) }
            .maxDepth(24)
            .filter { f -> f != rootFile && !isIgnoredWorkspaceDir(f.name) }
            .mapNotNull { f ->
                val rel = rootPath.relativize(f.canonicalFile.toPath()).toString().replace('\\', '/')
                val matchTarget = if (!cleanPat.contains('/')) f.name else rel
                if (globRegex.matches(matchTarget) || rel.contains(cleanPat.replace("*", ""), ignoreCase = true)) {
                    if (f.isDirectory) "$rel/" else "$rel (${f.length()} bytes)"
                } else null
            }
            .take(maxResults.coerceIn(1, 500))
            .toList()
        if (matches.isEmpty()) "No files matched glob '$pattern'." else matches.joinToString("\n")
    }

    actual fun workspaceRead(workspaceRoot: String, relativePath: String, startLine: Int, maxLines: Int): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        require(target.exists() && target.isFile) { "File not found: $relativePath" }
        val lines = target.readLines(Charsets.UTF_8)
        val startIdx = (startLine - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val endIdx = (startIdx + maxLines.coerceIn(1, 2000)).coerceAtMost(lines.size)
        val sliced = lines.subList(startIdx, endIdx)
        buildString {
            appendLine("File: $relativePath (Lines ${startIdx + 1}..$endIdx of ${lines.size})")
            sliced.forEachIndexed { idx, line ->
                appendLine("${startIdx + idx + 1}: $line")
            }
        }.trimEnd()
    }

    actual fun workspaceWrite(workspaceRoot: String, relativePath: String, content: String): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
        "Successfully wrote ${content.length} characters (${content.lines().size} lines) to '$relativePath'."
    }

    actual fun workspaceEdit(
        workspaceRoot: String,
        relativePath: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean
    ): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        require(target.exists() && target.isFile) { "File not found: $relativePath" }
        val existingRaw = target.readText(Charsets.UTF_8)
        require(oldText.isNotEmpty()) { "old_text must not be empty." }

        // Normalize CRLF/LF for reliable matching
        val usesCrlf = existingRaw.contains("\r\n")
        val existing = existingRaw.replace("\r\n", "\n")
        val normOld = oldText.replace("\r\n", "\n")
        val normNew = newText.replace("\r\n", "\n")

        if (!existing.contains(normOld)) {
            error("old_text was not found in '$relativePath' (${existing.lines().size} lines). Use fs_read to inspect the exact lines before editing.")
        }

        val updatedLf = if (replaceAll) {
            existing.replace(normOld, normNew)
        } else {
            val firstIdx = existing.indexOf(normOld)
            val secondIdx = existing.indexOf(normOld, startIndex = firstIdx + normOld.length)
            if (secondIdx != -1) {
                val matchLines = mutableListOf<Int>()
                var searchIdx = 0
                while (true) {
                    val hit = existing.indexOf(normOld, startIndex = searchIdx)
                    if (hit == -1) break
                    val lineNum = existing.substring(0, hit).count { it == '\n' } + 1
                    matchLines.add(lineNum)
                    searchIdx = hit + normOld.length.coerceAtLeast(1)
                }
                error("old_text matched ${matchLines.size} locations in '$relativePath' (at lines ${matchLines.joinToString(", ")}). Include more surrounding context or set replace_all=true.")
            }
            existing.replaceFirst(normOld, normNew)
        }

        val finalContent = if (usesCrlf) updatedLf.replace("\n", "\r\n") else updatedLf
        target.writeText(finalContent, Charsets.UTF_8)
        val removedLines = normOld.lines().size
        val addedLines = normNew.lines().size
        "Edited '$relativePath' (+$addedLines / -$removedLines lines)."
    }

    actual fun workspaceMultiEdit(
        workspaceRoot: String,
        relativePath: String,
        edits: List<Triple<String, String, Boolean>>
    ): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        require(target.exists() && target.isFile) { "File not found: $relativePath" }
        require(edits.isNotEmpty()) { "edits list must not be empty." }
        val existingRaw = target.readText(Charsets.UTF_8)
        val usesCrlf = existingRaw.contains("\r\n")
        var currentLf = existingRaw.replace("\r\n", "\n")
        var totalAdded = 0
        var totalRemoved = 0

        edits.forEachIndexed { idx, (oldText, newText, replaceAll) ->
            require(oldText.isNotEmpty()) { "Edit #${idx + 1}: old_text must not be empty." }
            val normOld = oldText.replace("\r\n", "\n")
            val normNew = newText.replace("\r\n", "\n")
            if (!currentLf.contains(normOld)) {
                error("Edit #${idx + 1} failed (rolled back all edits): old_text was not found in '$relativePath'.")
            }
            currentLf = if (replaceAll) {
                currentLf.replace(normOld, normNew)
            } else {
                val firstIdx = currentLf.indexOf(normOld)
                val secondIdx = currentLf.indexOf(normOld, startIndex = firstIdx + normOld.length)
                if (secondIdx != -1) {
                    error("Edit #${idx + 1} failed (rolled back all edits): old_text matched multiple locations in '$relativePath'. Include more surrounding lines or set replace_all=true.")
                }
                currentLf.replaceFirst(normOld, normNew)
            }
            totalRemoved += normOld.lines().size
            totalAdded += normNew.lines().size
        }

        val finalContent = if (usesCrlf) currentLf.replace("\n", "\r\n") else currentLf
        target.writeText(finalContent, Charsets.UTF_8)
        "Applied ${edits.size} edit(s) atomically to '$relativePath' (+$totalAdded / -$totalRemoved lines)."
    }

    actual fun workspaceMove(workspaceRoot: String, sourceRelativePath: String, destRelativePath: String): Result<String> = runCatching {
        val srcFile = resolveSandboxedPath(workspaceRoot, sourceRelativePath).toFile()
        val dstFile = resolveSandboxedPath(workspaceRoot, destRelativePath).toFile()
        require(srcFile.exists()) { "Source path does not exist: $sourceRelativePath" }
        dstFile.parentFile?.mkdirs()
        java.nio.file.Files.move(
            srcFile.toPath(),
            dstFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        "Moved '$sourceRelativePath' -> '$destRelativePath'."
    }

    actual fun workspaceDelete(workspaceRoot: String, relativePath: String): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        val rootCanon = File(workspaceRoot).canonicalFile
        require(target.canonicalFile != rootCanon) { "Cannot delete workspace root directory." }
        require(target.exists()) { "Path does not exist: $relativePath" }
        val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
        require(deleted) { "Failed to delete '$relativePath'." }
        "Deleted '$relativePath'."
    }
    actual fun workspaceShellExec(
        workspaceRoot: String,
        command: String,
        timeoutSeconds: Int,
        onOutputDelta: ((String) -> Unit)?
    ): Result<String> = runCatching {
        val rootDir = File(workspaceRoot).canonicalFile
        require(rootDir.exists() && rootDir.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        val pb = if (isWin) {
            val utf16LeBytes = command.toByteArray(Charsets.UTF_16LE)
            val encodedCommand = java.util.Base64.getEncoder().encodeToString(utf16LeBytes)
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedCommand)
        } else {
            ProcessBuilder("sh", "-c", command)
        }
        pb.directory(rootDir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        activeShellProcesses.add(proc)
        // Close stdin immediately so child CLI processes checking piped stdin never hang waiting for EOF
        runCatching { proc.outputStream.close() }

        val outputBuf = StringBuilder()
        var lastEmitMs = 0L
        val readerThread = Thread {
            runCatching {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val chunk = CharArray(1024)
                    while (true) {
                        val readCount = reader.read(chunk)
                        if (readCount < 0) break
                        var snap: String? = null
                        synchronized(outputBuf) {
                            if (outputBuf.length < 64_000) {
                                val allowed = (64_000 - outputBuf.length).coerceAtMost(readCount)
                                outputBuf.append(chunk, 0, allowed)
                            }
                            val now = System.currentTimeMillis()
                            if (onOutputDelta != null && now - lastEmitMs >= 100L) {
                                lastEmitMs = now
                                snap = outputBuf.toString()
                            }
                        }
                        val deltaSnap = snap
                        if (deltaSnap != null) {
                            runCatching { onOutputDelta?.invoke(deltaSnap) }
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
                if (!activeShellProcesses.contains(proc)) {
                    // Killed by user pressing Stop
                    runCatching { proc.descendants().forEach { it.destroyForcibly() } }
                    proc.destroyForcibly()
                    error("Shell command terminated by user.")
                }
                if (proc.waitFor(100L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
            }
            if (!finished) {
                runCatching { proc.descendants().forEach { it.destroyForcibly() } }
                proc.destroyForcibly()
                runCatching { proc.inputStream.close() }
                runCatching { readerThread.join(1500L) }
                val partial = synchronized(outputBuf) { outputBuf.toString().trim() }
                val partialSuffix = if (partial.isNotEmpty()) "\nPartial output before timeout:\n${partial.take(4000)}" else ""
                error("Command timed out after $timeoutSeconds seconds.$partialSuffix")
            }
            runCatching { readerThread.join(2000L) }
            val output = synchronized(outputBuf) { outputBuf.toString().trim() }
            if (onOutputDelta != null && output.isNotEmpty()) {
                runCatching { onOutputDelta.invoke(output) }
            }
            "Exit Code: ${proc.exitValue()}\n${output.take(8000)}"
        } finally {
            activeShellProcesses.remove(proc)
        }
    }

    actual fun killActiveShellProcesses() {
        val snapshot = synchronized(activeShellProcesses) {
            val list = activeShellProcesses.toList()
            activeShellProcesses.clear()
            list
        }
        for (proc in snapshot) {
            runCatching { proc.descendants().forEach { it.destroyForcibly() } }
            runCatching { proc.destroyForcibly() }
            runCatching { proc.inputStream.close() }
        }
    }

    actual fun workspaceSearch(workspaceRoot: String, query: String, fileGlob: String): Result<String> = runCatching {
        val rootFile = File(workspaceRoot).canonicalFile
        val rootPath = rootFile.toPath().normalize()
        require(rootFile.exists() && rootFile.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        require(query.isNotBlank()) { "Search query must not be blank." }

        val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
        val matches = mutableListOf<String>()

        rootFile.walkTopDown()
            .onEnter { d -> d == rootFile || !isIgnoredWorkspaceDir(d.name) }
            .maxDepth(24)
            .filter { f ->
                f.isFile &&
                    !isIgnoredWorkspaceDir(f.name) &&
                    f.length() < 2_000_000L &&
                    (fileGlob.isBlank() || f.name.contains(fileGlob.removePrefix("*"), ignoreCase = true))
            }
            .forEach { file ->
                if (matches.size >= 150) return@forEach
                runCatching {
                    val rel = rootPath.relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
                    file.useLines(Charsets.UTF_8) { seq ->
                        seq.forEachIndexed { idx, line ->
                            if (matches.size < 150) {
                                val hit = regex?.containsMatchIn(line) ?: line.contains(query, ignoreCase = true)
                                if (hit) {
                                    matches.add("$rel:${idx + 1}: ${line.trim().take(200)}")
                                }
                            }
                        }
                    }
                }
            }

        if (matches.isEmpty()) "No matches found for '$query'." else matches.joinToString("\n")
    }

    actual fun fetchUrlText(url: String, maxChars: Int): Result<String> = runCatching {
        val trimmed = url.trim()
        require(trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            "URL must start with http:// or https://"
        }
        val conn = (URI(trimmed).toURL().openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; ParchmentAI/2.0)")
            setRequestProperty("Accept", "text/markdown, text/html, application/xhtml+xml, application/json, text/plain;q=0.9, */*;q=0.8")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                error("HTTP $code from $trimmed: ${body.take(400)}")
            }
            val contentType = conn.contentType.orEmpty().lowercase()
            val cleaned = io.parchment.ai.harness.WorkspaceHarness.convertHtmlToReadableText(body, contentType)
            cleaned.take(maxChars.coerceIn(500, 100_000))
        } finally {
            conn.disconnect()
        }
    }
    @Volatile
    private var keepAwakeProcess: Process? = null

    private fun setWindowsKeepAwake(active: Boolean) {
        runCatching {
            val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
            if (!isWin) return@runCatching
            if (!active) {
                keepAwakeProcess?.destroyForcibly()
                keepAwakeProcess = null
                return@runCatching
            }
            if (keepAwakeProcess?.isAlive == true) return@runCatching
            val parentPid = runCatching { ProcessHandle.current().pid() }.getOrDefault(0L)
            // Holds Win32 SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_AWAYMODE_REQUIRED = 0x80000041)
            // while the active generation/goal is running so Windows never suspends CPU/network during multi-day runs.
            val psCmd = buildString {
                append("\$code = '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint esFlags);'; ")
                append("\$ste = Add-Type -MemberDefinition \$code -Name 'Win32Ste' -Namespace 'Parchment' -PassThru; ")
                append("try { ")
                append("while (\$true) { ")
                append("[void]\$ste::SetThreadExecutionState(0x80000041); ")
                if (parentPid > 0L) {
                    append("if (-not (Get-Process -Id $parentPid -ErrorAction SilentlyContinue)) { break }; ")
                }
                append("Start-Sleep -Seconds 30 ")
                append("} ")
                append("} finally { [void]\$ste::SetThreadExecutionState(0x80000000) }")
            }
            keepAwakeProcess = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle", "Hidden",
                "-Command", psCmd
            ).start()
        }
    }

    actual fun notifyLongDecodeStart(modelName: String) {
        windowStatusCallback?.invoke("Parchment — Decoding ($modelName)…")
        setWindowsKeepAwake(true)
    }

    actual fun notifyLongDecodeProgress(modelName: String, outputTokens: Int, elapsedSeconds: Int) {
        windowStatusCallback?.invoke("Parchment — $modelName (+$outputTokens tok • ${elapsedSeconds}s)")
    }

    actual fun notifyLongDecodeComplete() {
        windowStatusCallback?.invoke("Parchment — Literary & Bleeding-Edge AI Studio")
        setWindowsKeepAwake(false)
    }

    actual fun installOtaUpdate(fileName: String, bytes: ByteArray): Result<String> = runCatching {
        // Flush any pending in-memory state to disk BEFORE starting the OTA installer!
        flushPendingStateNow()

        val tempDir = File(System.getProperty("java.io.tmpdir"), "parchment-ota")
        if (!tempDir.exists()) tempDir.mkdirs()
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "Parchment-Windows-Setup.exe" }
        val installerFile = File(tempDir, safeName)
        installerFile.writeBytes(bytes)

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

        "Downloaded ${bytes.size / 1024} KB. Applying silent Windows update & restarting Parchment…"
    }

    actual fun saveGeneratedMediaFile(fileName: String, bytes: ByteArray): String {
        val mediaDir = File(resolveStorageDir(), "media")
        if (!mediaDir.exists()) mediaDir.mkdirs()
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(mediaDir, safeName)
        outFile.writeBytes(bytes)
        synchronized(imageCache) { imageCache.remove(outFile.absolutePath) }
        return outFile.absolutePath
    }

    actual fun readBinaryFileBytes(filePath: String): ByteArray? = runCatching {
        if (filePath.isBlank()) return null
        val f = File(filePath)
        if (f.exists() && f.isFile) f.readBytes() else null
    }.getOrNull()

    actual fun loadImageBitmapFromFile(filePath: String): ImageBitmap? {
        if (filePath.isBlank()) return null
        synchronized(imageCache) { imageCache[filePath]?.let { return it } }
        return runCatching {
            val f = File(filePath)
            if (!f.exists() || !f.isFile) return null
            val bmp = Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap()
            synchronized(imageCache) {
                if (imageCache.size >= 16) {
                    imageCache.keys.firstOrNull()?.let { imageCache.remove(it) }
                }
                imageCache[filePath] = bmp
            }
            bmp
        }.getOrNull()
    }

    actual fun openOrPlayMedia(localFilePathOrUrl: String) {
        runCatching {
            val f = File(localFilePathOrUrl)
            if (f.exists()) {
                Desktop.getDesktop().open(f)
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
                if (f != null && f.exists() && f.isFile && f.length() <= 150_000_000L) {
                    runCatching {
                        normalizePickedBinaryFile(
                            rawFileName = f.name,
                            bytes = f.readBytes(),
                            fallbackTimestamp = currentTimeMillis()
                        )
                    }.getOrNull()
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
                (contents.getTransferData(rawImageFlavor) as? InputStream)?.use { it.readBytes() }
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

    actual fun isSpeakingAloud(): Boolean = activeTtsProcess?.isAlive == true

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
