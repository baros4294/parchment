package io.parchment.ai.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser

actual object PlatformBridge {
    actual val platformName: String =
        "${System.getProperty("os.name") ?: "Desktop"} (${System.getProperty("os.arch") ?: "universal"})"
    actual val isAndroid: Boolean = false

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
        val existing = target.readText(Charsets.UTF_8)
        require(oldText.isNotEmpty()) { "old_text must not be empty." }
        require(existing.contains(oldText)) { "old_text was not found in '$relativePath'." }

        val updated = if (replaceAll) {
            existing.replace(oldText, newText)
        } else {
            val firstIdx = existing.indexOf(oldText)
            val secondIdx = existing.indexOf(oldText, startIndex = firstIdx + oldText.length)
            require(secondIdx == -1) {
                "old_text matched multiple locations in '$relativePath'. Include more context or set replace_all=true."
            }
            existing.replaceFirst(oldText, newText)
        }
        target.writeText(updated, Charsets.UTF_8)
        "Successfully edited '$relativePath'."
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
}
