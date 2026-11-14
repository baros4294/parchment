package io.parchment.ai.platform

actual object PlatformWorkspace {
    private val workspaceScanner = WorkspaceTreeScanner(
        WorkspaceScanConfig(
            missingDirMessage = { "Directory not found: $it" },
            notDirMessage = { "Directory not found: $it" },
            emptyDirectoryMessage = "(Empty workspace directory)",
            useTreeCache = false,
            canonicalizeRelativize = true,
            allowEmptyPlainGlobFallback = true,
            requirePositiveSearchFileBytes = false,
            maxSearchFileBytes = 1_499_999L,
            maxSearchHits = 100,
            maxSearchLineChars = 180,
            parallelSearch = false,
            filterSearchByRelPath = false
        )
    )

    actual fun workspaceList(workspaceRoot: String, relativePath: String, recursive: Boolean): Result<String> =
        workspaceScanner.list(workspaceRoot, relativePath, recursive)

    actual fun workspaceGlob(workspaceRoot: String, pattern: String, maxResults: Int): Result<String> =
        workspaceScanner.glob(workspaceRoot, pattern, maxResults)

    actual fun workspaceRead(workspaceRoot: String, relativePath: String, startLine: Int, maxLines: Int): Result<String> = runCatching {
        val target = WorkspacePathGuard.resolve(workspaceRoot, relativePath).toFile()
        require(target.exists() && target.isFile) { "File not found: $relativePath" }
        WorkspaceTextReader.read(target, relativePath, startLine, maxLines, maxTotalChars = 200_000)
    }

    actual fun workspaceWrite(workspaceRoot: String, relativePath: String, content: String): Result<String> = runCatching {
        WorkspaceFileEditor.write(workspaceRoot, relativePath, content)
    }

    actual fun workspaceAppend(workspaceRoot: String, relativePath: String, content: String): Result<String> = runCatching {
        WorkspaceFileEditor.append(workspaceRoot, relativePath, content)
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
        WorkspaceFileEditor.move(workspaceRoot, sourceRelativePath, destRelativePath)
    }

    actual fun workspaceDelete(workspaceRoot: String, relativePath: String): Result<String> = runCatching {
        WorkspaceFileEditor.delete(workspaceRoot, relativePath)
    }

    actual fun workspaceShellExec(
        workspaceRoot: String,
        command: String,
        timeoutSeconds: Int,
        onOutputDelta: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): Result<String> {
        val root = runCatching { WorkspacePathGuard.root(workspaceRoot).toFile() }.getOrElse { return Result.failure(it) }
        if (!root.isDirectory) return Result.failure(IllegalArgumentException("Workspace root does not exist: $workspaceRoot"))
        return AndroidOwnedShell.execute(root, command, timeoutSeconds, onOutputDelta, isCancelled)
    }

    actual fun workspaceSearch(workspaceRoot: String, query: String, fileGlob: String): Result<String> =
        workspaceScanner.search(workspaceRoot, query, fileGlob)

    actual fun fetchUrlText(url: String, maxChars: Int, isCancelled: (() -> Boolean)?): Result<String> =
        PublicWebTransport.fetch(url, maxChars, isCancelled)

    actual fun fetchPublicBytes(url: String, maxBytes: Int, isCancelled: (() -> Boolean)?): Result<ByteArray> =
        PublicWebTransport.fetchBytes(url, maxBytes, isCancelled)

    actual val computerControl: ComputerControl get() = AndroidComputerControl
    actual val supportsLocalMcpServers: Boolean = false
    actual fun startMcpProcess(command: String): McpProcess? = null
}
