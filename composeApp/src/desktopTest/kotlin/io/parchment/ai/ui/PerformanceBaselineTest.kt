package io.parchment.ai.ui

import com.sun.management.ThreadMXBean
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.protocol.O200kTokenizer
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/** Repeatable desktop numbers for the work the app repeats while streaming and starting up. */
class PerformanceBaselineTest {
    private val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val results = mutableListOf<String>()

    private fun measure(name: String, work: () -> Unit) {
        val thread = Thread.currentThread().threadId()
        val allocatedBefore = threads.getThreadAllocatedBytes(thread)
        val started = System.nanoTime()
        work()
        val millis = (System.nanoTime() - started) / 1_000_000
        val megabytes = (threads.getThreadAllocatedBytes(thread) - allocatedBefore) / (1024 * 1024)
        results += "$name ms=$millis alloc_mb=$megabytes"
    }

    private fun streamFrames(text: String, charsPerFrame: Int = 12) {
        val cache = IncrementalProseBlockCache()
        var shown = charsPerFrame
        while (shown < text.length) {
            cache.parseIncremental(text.substring(0, shown))
            shown += charsPerFrame
        }
        cache.parseIncremental(text)
    }

    private val proseReply = (1..300).joinToString("\n\n") { number ->
        "Paragraph $number has **bold**, *italic* and `code` so the reply looks like a normal answer from a model."
    }
    private val codeReply = "```kotlin\n" + (1..1500).joinToString("\n") { number ->
        if (number % 6 == 0) "" else "val item$number = compute($number) // a line of generated code"
    } + "\n```"

    @Test fun recordDesktopBaselines() {
        measure("stream_prose_${proseReply.length}_chars") { streamFrames(proseReply) }
        measure("stream_code_fence_${codeReply.length}_chars") { streamFrames(codeReply) }
        measure("frame_text_copies_only") {
            var shown = 12
            var kept = 0
            while (shown < codeReply.length) {
                kept += codeReply.substring(0, shown).length
                shown += 12
            }
            assertTrue(kept > 0)
        }
        measure("frame_text_copies_paced") {
            var shown = 12
            var kept = 0
            var clock = 0L
            var lastCopy = -1_000L
            while (shown < codeReply.length) {
                if (clock - lastCopy >= visibleTextCopyIntervalMs(shown)) {
                    kept += codeReply.substring(0, shown).length
                    lastCopy = clock
                }
                shown += 12
                clock += 16
            }
            assertTrue(kept > 0)
        }

        val deltas = (1..8000).map { "word$it " }
        measure("status_word_count_${deltas.size}_deltas") {
            val target = StringBuilder()
            val counter = StreamWordCounter()
            for (delta in deltas) {
                target.append(delta)
                counter.count(target)
            }
        }

        val nodes = (1..2000).map { number ->
            MessageNode("m$number", if (number % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                listOf(MessageVariant("v$number", content = "Message $number. ".repeat(80))))
        }
        val encoded = compactStateJson.encodeToString(ChatSession.serializer(), ChatSession("large", "Large", 1L, 1L, "p", messages = nodes))
        measure("decode_chat_${encoded.length}_chars") { compactStateJson.decodeFromString(ChatSession.serializer(), encoded) }
        measure("tokenize_20000_chars") { O200kTokenizer.countTokens("Tokenize this sentence once. ".repeat(690), cacheResult = false) }

        val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "TODO.md").isFile }
        val fonts = repo.resolve("composeApp/src").walkTopDown().filter { it.isFile && it.extension == "ttf" }.toList()
        measure("read_${fonts.size}_fonts_${fonts.sumOf { it.length() } / 1024}_kb") { fonts.forEach { it.readBytes() } }

        File(repo, ".parchment-local/verification/performance.txt").apply { parentFile.mkdirs() }.writeText(results.joinToString("\n", postfix = "\n"))
        println(results.joinToString("\n"))
        assertTrue(results.size == 8)
    }
}
