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
import java.io.File
import java.io.FilenameFilter
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JFileChooser

actual object PlatformBridge {
    actual val platformName: String =
        "${System.getProperty("os.name") ?: "Desktop"} (${System.getProperty("os.arch") ?: "universal"})"
    actual val isAndroid: Boolean = false

    private val fontCache = ConcurrentHashMap<String, FontFamily>()
    private val imageCache = LinkedHashMap<String, ImageBitmap>()

    @Volatile
    var windowStatusCallback: ((String) -> Unit)? = null

    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    private fun resolveStorageDir(): File {
        // 1. Check if portable 'data' directory exists alongside app launcher
        val portableDir = File(System.getProperty("user.dir"), "parchment-portable-data")
        if (portableDir.exists() && portableDir.isDirectory && portableDir.canWrite()) {
            return portableDir
        }
        // 2. Standard isolated per-user app data directory (cleaned 100% by Windows uninstaller)
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

    private fun stateFile(): File = File(resolveStorageDir(), "parchment-state.json")

    actual fun loadAppStateJson(): String? = runCatching {
        val f = stateFile()
        if (f.exists() && f.isFile) f.readText(Charsets.UTF_8) else null
    }.getOrNull()

    actual fun saveAppStateJson(json: String) {
        runCatching {
            val dir = resolveStorageDir()
            if (!dir.exists()) dir.mkdirs()
            stateFile().writeText(json, Charsets.UTF_8)
        }
    }

    actual fun wipeAllLocalData(): Boolean = runCatching {
        val dir = resolveStorageDir()
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        true
    }.getOrDefault(false)

    actual suspend fun pickFileForImport(allowedExtensions: List<String>): PickedBinaryFile? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dialog = FileDialog(null as Frame?, "Import .PNG or .JSON into Parchment", FileDialog.LOAD)
                if (allowedExtensions.isNotEmpty()) {
                    dialog.file = allowedExtensions.joinToString(";") { "*.$it" }
                    dialog.filenameFilter = FilenameFilter { _, name ->
                        allowedExtensions.any { ext -> name.endsWith(".$ext", ignoreCase = true) }
                    }
                }
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    val target = File(dir, file)
                    if (target.exists() && target.isFile) {
                        PickedBinaryFile(
                            fileName = target.name,
                            bytes = target.readBytes()
                        )
                    } else null
                } else null
            }.getOrNull()
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

    actual fun workspaceList(workspaceRoot: String, relativePath: String, recursive: Boolean): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath)
        val rootPath = File(workspaceRoot).canonicalFile.toPath().normalize()
        val dirFile = target.toFile()
        require(dirFile.exists()) { "Directory does not exist: $relativePath" }
        require(dirFile.isDirectory) { "Path is not a directory: $relativePath" }

        val entries = if (recursive) {
            dirFile.walkTopDown()
                .maxDepth(5)
                .filter { it != dirFile && !it.name.startsWith(".git") && !it.name.equals("build", ignoreCase = true) }
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

        // Normalize CRLF/LF for reliable matching (OpenCode / Oh-My-Pi style)
        val usesCrlf = existingRaw.contains("\r\n")
        val existing = existingRaw.replace("\r\n", "\n")
        val normOld = oldText.replace("\r\n", "\n")
        val normNew = newText.replace("\r\n", "\n")

        require(existing.contains(normOld)) { "old_text was not found in '$relativePath'." }

        val updatedLf = if (replaceAll) {
            existing.replace(normOld, normNew)
        } else {
            val firstIdx = existing.indexOf(normOld)
            val secondIdx = existing.indexOf(normOld, startIndex = firstIdx + normOld.length)
            require(secondIdx == -1) {
                "old_text matched multiple locations in '$relativePath'. Include more surrounding context or set replace_all=true."
            }
            existing.replaceFirst(normOld, normNew)
        }

        val finalContent = if (usesCrlf) updatedLf.replace("\n", "\r\n") else updatedLf
        target.writeText(finalContent, Charsets.UTF_8)
        val removedLines = normOld.lines().size
        val addedLines = normNew.lines().size
        "Edited '$relativePath' (+$addedLines / -$removedLines lines)."
    }

    actual fun workspaceShellExec(workspaceRoot: String, command: String, timeoutSeconds: Int): Result<String> = runCatching {
        val rootDir = File(workspaceRoot).canonicalFile
        require(rootDir.exists() && rootDir.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        val pb = if (isWin) {
            ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command)
        } else {
            ProcessBuilder("sh", "-c", command)
        }
        pb.directory(rootDir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val finished = proc.waitFor(timeoutSeconds.coerceIn(1, 300).toLong(), java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            error("Command timed out after $timeoutSeconds seconds.")
        }
        val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        "Exit Code: ${proc.exitValue()}\n${output.take(8000)}"
    }

    actual fun workspaceSearch(workspaceRoot: String, query: String, fileGlob: String): Result<String> = runCatching {
        val rootFile = File(workspaceRoot).canonicalFile
        val rootPath = rootFile.toPath().normalize()
        require(rootFile.exists() && rootFile.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        require(query.isNotBlank()) { "Search query must not be blank." }

        val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
        val matches = mutableListOf<String>()

        rootFile.walkTopDown()
            .maxDepth(6)
            .filter { f ->
                f.isFile &&
                    !f.path.contains(".git") &&
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

    actual fun notifyLongDecodeStart(modelName: String) {
        windowStatusCallback?.invoke("Parchment — Decoding ($modelName)…")
    }

    actual fun notifyLongDecodeProgress(modelName: String, outputTokens: Int, elapsedSeconds: Int) {
        windowStatusCallback?.invoke("Parchment — $modelName (+$outputTokens tok • ${elapsedSeconds}s)")
    }

    actual fun notifyLongDecodeComplete() {
        windowStatusCallback?.invoke("Parchment — Literary & Bleeding-Edge AI Studio")
    }

    actual fun installOtaUpdate(fileName: String, bytes: ByteArray): Result<String> = runCatching {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "parchment-ota")
        if (!tempDir.exists()) tempDir.mkdirs()
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "Parchment-Windows-Setup.exe" }
        val installerFile = File(tempDir, safeName)
        installerFile.writeBytes(bytes)

        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        val installedExe = File(localAppData, "Programs\\Parchment\\Parchment.exe")
        val fallbackExe = File(System.getProperty("user.dir"), "Parchment.exe")
        val relaunchTarget = if (installedExe.exists()) installedExe.absolutePath else fallbackExe.absolutePath

        // Write a tiny updater script that waits for the current process to exit, runs the silent Inno Setup update,
        // and automatically relaunches Parchment.exe
        val psScriptFile = File(tempDir, "apply-ota.ps1")
        psScriptFile.writeText(
            """
            Start-Sleep -Milliseconds 900
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
}
