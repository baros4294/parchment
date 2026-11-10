package io.parchment.ai.platform

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** A local MCP server. Its stderr is discarded so a chatty server cannot fill the pipe and stall. */
internal class DesktopMcpProcess(command: String) : McpProcess {
    private val process: Process = run {
        val shell = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) listOf("cmd", "/c", command) else listOf("sh", "-c", command)
        ProcessBuilder(shell).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }
    private val input = process.inputStream.buffered()
    private val output = process.outputStream

    override fun writeLine(line: String) {
        output.write(line.toByteArray(Charsets.UTF_8))
        output.write('\n'.code)
        output.flush()
    }

    override fun readLine(): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = runCatching { input.read() }.getOrDefault(-1)
            if (byte < 0) return if (line.size() > 0) line.toString(Charsets.UTF_8) else null
            if (byte == '\n'.code) return line.toString(Charsets.UTF_8).trimEnd('\r')
            check(line.size() < MAX_LINE_BYTES) { "An MCP message was too large." }
            line.write(byte)
        }
    }

    override fun close() {
        runCatching { output.close() }
        val children = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        children.forEach { child -> if (child.isAlive) child.destroyForcibly() }
    }

    private companion object { const val MAX_LINE_BYTES = 8 * 1024 * 1024 }
}
