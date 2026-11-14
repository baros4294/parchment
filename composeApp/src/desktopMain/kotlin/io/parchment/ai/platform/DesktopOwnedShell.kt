package io.parchment.ai.platform

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Desktop workspace shell command runner with streaming output capture and descendant cleanup. */
internal object DesktopOwnedShell {
    private const val MAX_TIMEOUT_SECONDS = 300
    private const val POLL_INTERVAL_MS = 100L
    private const val DELTA_EMIT_INTERVAL_MS = 100L
    private const val READER_JOIN_TIMEOUT_MS = 1500L
    private const val READER_JOIN_DONE_MS = 2000L
    private const val TIMEOUT_CAPTURE_CHARS = 4000
    private const val EXIT_CAPTURE_CHARS = 8000

    fun execute(
        workspaceRoot: String,
        command: String,
        timeoutSeconds: Int,
        onOutputDelta: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): Result<String> = runCatching {
        val rootDir = WorkspacePathGuard.root(workspaceRoot).toFile()
        require(rootDir.exists() && rootDir.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        require(isCancelled?.invoke() != true) { "The owning run was cancelled before shell startup." }

        val proc = buildProcess(rootDir, command).start()
        // Close stdin immediately so child CLI processes checking piped stdin never hang waiting for EOF.
        runCatching { proc.outputStream.close() }

        val capture = BoundedTextCapture()
        val readerThread = startReaderThread(proc, capture, onOutputDelta)

        try {
            val finished = awaitProcessExit(proc, timeoutSeconds, isCancelled)
            if (!finished) {
                terminateProcessTree(proc)
                runCatching { proc.inputStream.close() }
                runCatching { readerThread.join(READER_JOIN_TIMEOUT_MS) }
                val partial = capture.snapshot(TIMEOUT_CAPTURE_CHARS).trim()
                val partialSuffix = if (partial.isNotEmpty()) "\nPartial output before timeout:\n$partial" else ""
                error("Command timed out after $timeoutSeconds seconds.$partialSuffix")
            }
            runCatching { readerThread.join(READER_JOIN_DONE_MS) }
            val output = capture.snapshot().trim()
            if (onOutputDelta != null && output.isNotEmpty()) {
                runCatching { onOutputDelta.invoke(output) }
            }
            "Exit Code: ${proc.exitValue()}\n${capture.snapshot(EXIT_CAPTURE_CHARS).trim()}"
        } finally {
            if (proc.isAlive) {
                terminateProcessTree(proc)
            }
            runCatching { proc.inputStream.close() }
            runCatching { proc.errorStream.close() }
            runCatching { readerThread.join(READER_JOIN_TIMEOUT_MS) }
        }
    }

    private fun buildProcess(rootDir: File, command: String): ProcessBuilder {
        val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        val builder = if (isWin) {
            val psCommandWithNoProgress = "\$ProgressPreference = 'SilentlyContinue'; $command"
            val encodedCommand = Base64.getEncoder().encodeToString(psCommandWithNoProgress.toByteArray(Charsets.UTF_16LE))
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedCommand)
        } else {
            ProcessBuilder("sh", "-c", command)
        }
        return builder.directory(rootDir).redirectErrorStream(true)
    }

    private fun startReaderThread(
        proc: Process,
        capture: BoundedTextCapture,
        onOutputDelta: ((String) -> Unit)?
    ): Thread = Thread {
        runCatching {
            proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val chunk = CharArray(1024)
                var lastEmitMs = 0L
                while (true) {
                    val readCount = reader.read(chunk)
                    if (readCount < 0) break
                    capture.append(chunk, readCount)
                    val now = System.currentTimeMillis()
                    if (onOutputDelta != null && now - lastEmitMs >= DELTA_EMIT_INTERVAL_MS) {
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

    private fun awaitProcessExit(
        proc: Process,
        timeoutSeconds: Int,
        isCancelled: (() -> Boolean)?
    ): Boolean {
        val maxWaitMs = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS) * 1000L
        val startMs = System.currentTimeMillis()
        while (System.currentTimeMillis() - startMs < maxWaitMs) {
            if (isCancelled?.invoke() == true) {
                terminateProcessTree(proc)
                error("Shell command terminated because its owning run stopped or permission was revoked.")
            }
            if (proc.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                return true
            }
        }
        return false
    }

    private fun terminateProcessTree(proc: Process) {
        runCatching { proc.descendants().use { children -> children.forEach { it.destroyForcibly() } } }
        runCatching { proc.destroyForcibly() }
    }
}
