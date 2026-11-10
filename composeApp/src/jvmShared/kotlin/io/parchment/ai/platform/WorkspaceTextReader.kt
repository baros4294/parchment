package io.parchment.ai.platform

import java.io.File

/** Reads line ranges of workspace files without holding more than a bounded part of any line. */
internal object WorkspaceTextReader {
    private const val MAX_LINE_CHARS = 4000

    fun read(target: File, relativePath: String, startLine: Int, maxLines: Int, maxTotalChars: Int): String {
        val reqStart = (startLine - 1).coerceAtLeast(0)
        val reqMax = maxLines.coerceIn(1, 10_000)
        val sliced = ArrayList<String>(reqMax.coerceAtMost(512))
        var totalLines = 0
        var accumulatedChars = 0
        forEachBoundedLine(target, MAX_LINE_CHARS) { line, truncated ->
            if (totalLines >= reqStart && sliced.size < reqMax && accumulatedChars < maxTotalChars) {
                val safeLine = if (truncated) "$line … [line truncated]" else line
                sliced.add(safeLine)
                accumulatedChars += safeLine.length
            }
            totalLines++
        }
        if (totalLines == 0) return "File: $relativePath (Lines 1..0 of 0)"
        val actualStart = reqStart.coerceAtMost(totalLines - 1)
        val actualEnd = (actualStart + sliced.size).coerceAtMost(totalLines)
        return buildString(accumulatedChars + 128) {
            appendLine("File: $relativePath (Lines ${actualStart + 1}..$actualEnd of $totalLines)")
            sliced.forEachIndexed { index, line -> appendLine("${actualStart + index + 1}: $line") }
        }.trimEnd()
    }

    /** Splits on LF, CR and CRLF like `useLines`, but discards characters past [maxLineChars]. */
    fun forEachBoundedLine(file: File, maxLineChars: Int, action: (line: String, truncated: Boolean) -> Unit) {
        val line = StringBuilder()
        var truncated = false
        var lineOpen = false
        var skipLineFeed = false

        fun finishLine() {
            action(line.toString(), truncated)
            line.setLength(0)
            truncated = false
            lineOpen = false
        }

        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(16 * 1024)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    val character = buffer[index]
                    if (skipLineFeed) {
                        skipLineFeed = false
                        if (character == '\n') continue
                    }
                    when (character) {
                        '\n' -> finishLine()
                        '\r' -> {
                            finishLine()
                            skipLineFeed = true
                        }
                        else -> {
                            lineOpen = true
                            if (line.length < maxLineChars) line.append(character) else truncated = true
                        }
                    }
                }
            }
        }
        if (lineOpen) finishLine()
    }
}
