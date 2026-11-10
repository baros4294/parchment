package io.parchment.ai.ui

import androidx.compose.ui.graphics.Color
import io.parchment.ai.model.ThemeConfig
import kotlin.random.Random
import kotlin.test.*

/** Model text is untrusted: no input may overflow the stack or stall the frame that renders it. */
class RendererBoundsTest {
    private val palette = buildPaletteFromConfig(ThemeConfig())

    private fun bounded(name: String, block: () -> Unit) {
        val started = System.nanoTime()
        block()
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(millis < 4_000, "$name took ${millis}ms")
    }

    private fun renderAll(text: String) {
        parseProseBlocks(text)
        styleLiteraryParagraph(text, palette, 0)
        LatexMathRenderer.formatInlineLatexToAnnotatedString(text, palette)
        LatexMathRenderer.parseDisplayMathNodes(text, palette)
        parseSvgSceneDocument(text, Color.Black)
    }

    @Test fun deeplyNestedMathRendersAsTextInsteadOfOverflowing() {
        val depth = 20_000
        listOf(
            "\\boxed{".repeat(depth) + "x" + "}".repeat(depth),
            "\\boxed{".repeat(depth),
            "\\frac{".repeat(depth),
            "x^{".repeat(depth),
            "\\begin{a}".repeat(depth)
        ).forEach { latex -> bounded(latex.take(10)) { renderAll(latex) } }
        val nested = "\\boxed{".repeat(100) + "x" + "}".repeat(100)
        assertTrue("x" in LatexMathRenderer.formatInlineLatexToAnnotatedString(nested, palette).text)
    }

    @Test fun ordinaryMathStillFormats() {
        val text = LatexMathRenderer.formatInlineLatexToAnnotatedString("\\frac{a}{b} + \\sqrt{x}", palette).text
        assertTrue("⁄" in text && "√" in text, text)
    }

    @Test fun pathologicalInlineMarkdownStaysBounded() {
        listOf("*", "**", "_", "~~", "`", "$", "$$", "\"", "[", "](", "\\(").forEach { unit ->
            bounded("repeat $unit") { styleLiteraryParagraph(unit.repeat(200_000), palette, 0) }
            bounded("spaced $unit") { styleLiteraryParagraph("$unit ".repeat(5_000), palette, 0) }
        }
        val deep = (1..4_000).joinToString("") { if (it % 2 == 0) "**a " else "~~b " }
        val closed = deep + deep.reversed()
        bounded("nested emphasis") { assertTrue(styleLiteraryParagraph(closed, palette, 0).text.isNotEmpty()) }
        assertEquals("plain", styleLiteraryParagraph("**plain**", palette, 0).text)
    }

    @Test fun pathologicalBlocksStayBounded() {
        listOf("```\n", "$$\n", "\\[\n", "| a |\n", "> \n", "\\begin{align}\n", "# x\n", "\n\n").forEach { unit ->
            bounded("block $unit") { parseProseBlocks(unit.repeat(40_000)) }
        }
        bounded("one long line") { parseProseBlocks("a|".repeat(500_000)) }
    }

    @Test fun malformedSvgStaysBoundedAndLimitsItsWork() {
        listOf(
            "<svg>" + "<a".repeat(100_000),
            "<svg>" + "<!--".repeat(100_000),
            "<svg>" + "<style>".repeat(60_000),
            "<svg><style>" + "a".repeat(400_000) + "</style>",
            "<svg><rect " + "a".repeat(400_000) + "/>",
            "<svg><g transform=\"" + "a".repeat(400_000) + "\"/>",
            "<" + "a".repeat(400_000)
        ).forEach { svg -> bounded(svg.take(12)) { parseSvgSceneDocument(svg, Color.Black) } }
        val tooMany = "<svg viewBox=\"0 0 10 10\">" + "<rect width=\"1\" height=\"1\"/>".repeat(15_000) + "</svg>"
        bounded("many shapes") { assertNull(parseSvgSceneDocument(tooMany, Color.Black)) }
        val tooLarge = "<svg>" + " ".repeat(600_000) + "</svg>"
        assertNull(parseSvgSceneDocument(tooLarge, Color.Black))
        val nestedGroups = "<svg viewBox=\"0 0 10 10\">" + "<g>".repeat(15_000) + "<rect width=\"1\" height=\"1\"/>" + "</g>".repeat(15_000) + "</svg>"
        bounded("nested groups") { parseSvgSceneDocument(nestedGroups, Color.Black) }
    }

    @Test fun svgStylesCommentsAndAttributesStillParse() {
        val svg = """<svg viewBox='0 0 10 10'><!-- <rect width="9" height="9"/> --><style>.a, circle { fill: red } rect { opacity: 0.5 }</style>
            <g transform="translate(1 1) scale(2)"><rect class="a" width="1" height="1"/><circle r="1"/></g></svg>"""
        val scene = assertNotNull(parseSvgSceneDocument(svg, Color.Black))
        assertEquals(2, scene.drawItems.size, "The commented rectangle must not draw")
        assertEquals(0.5f, scene.drawItems[0].style.opacity)
        assertEquals(SvgPaintSpec.Solid(Color.Red), scene.drawItems[1].style.fill)
    }

    @Test fun randomDelimiterSoupNeverThrowsOrStalls() {
        val parts = listOf("*", "**", "_", "`", "```\n", "$", "$$", "\\[", "\\]", "{", "}", "\\frac", "\\boxed", "^", "<svg", "<g>", "</g>",
            "<!--", "-->", "\"", "'", "|", "\n", "\n\n", "[", "](", ")", "\\begin{a}", "\\end{a}", "é", "😀", "\u0000", " ", "x")
        val random = Random(7)
        repeat(300) {
            val text = buildString { repeat(random.nextInt(1, 2_000)) { append(parts[random.nextInt(parts.size)]) } }
            bounded("seeded soup $it") { renderAll(text) }
        }
    }

    @Test fun streamingFencesMatchAFullParseAtEveryFrame() {
        val lines = (1..200).joinToString("\n") { if (it % 5 == 0) "" else "line $it é😀" }
        val intro = "Intro paragraph.\n\n" + "x".repeat(300) + "\n\n"
        listOf(
            "$intro```kotlin\n$lines\n```\n\nAfter the fence.",
            "Tight intro:\n```\n$lines\n```\nTight outro.",
            "```py\n\n\n$lines",
            "$intro```a\n$lines\n   ```  \n\n```b\n$lines\n",
            "$intro```a\nnot ``` a close\n$lines\n```",
            "$intro```math\n$lines\n```",
            "$intro```a\r\n" + lines.replace("\n", "\r\n") + "\r\n```\r\nDone",
            "$intro$$\n$lines\n```\n$lines\n$$\nAfter"
        ).forEach { text ->
            for (step in listOf(1, 7, 64)) {
                val cache = IncrementalProseBlockCache()
                for (end in 1..text.length step step) {
                    val partial = text.substring(0, end)
                    assertEquals(parseProseBlocks(partial), cache.parseIncremental(partial), "step $step at $end")
                }
                assertEquals(parseProseBlocks(text), cache.parseIncremental(text))
            }
        }
        val rewrites = IncrementalProseBlockCache()
        val open = "$intro```a\n$lines"
        rewrites.parseIncremental(open)
        rewrites.parseIncremental(open + "\nmore")
        val rewritten = "Different text\n\n```b\ncode"
        assertEquals(parseProseBlocks(rewritten), rewrites.parseIncremental(rewritten))
        assertEquals(parseProseBlocks(rewritten, enableMath = false), rewrites.parseIncremental(rewritten, enableMath = false))
    }

    @Test fun storedChatSearchMatchesVisibleTextOnly() {
        val chat = io.parchment.ai.model.ChatSession("chat", "Title", 1L, 1L, "p", messages = listOf(
            io.parchment.ai.model.MessageNode("m", io.parchment.ai.model.MessageRole.USER, listOf(
                io.parchment.ai.model.MessageVariant("old", content = "Discarded draft"),
                io.parchment.ai.model.MessageVariant("new", content = "He said \"héllo\"\nthen left")), activeVariantIndex = 1)))
        val raw = compactStateJson.encodeToString(io.parchment.ai.model.ChatSession.serializer(), chat)
        assertTrue(storedChatJsonContains(raw, "chat", "HÉLLO"))
        assertTrue(storedChatJsonContains(raw, "chat", "\"héllo\"\nthen"), "Quotes and newlines are escaped in storage")
        assertFalse(storedChatJsonContains(raw, "chat", "Discarded"), "A replaced version is not the visible text")
        assertFalse(storedChatJsonContains(raw, "chat", "Title"), "Titles are searched separately, without reading the disk")
        assertFalse(storedChatJsonContains(raw, "other", "hello"), "A file must belong to the chat it is read for")
        assertFalse(storedChatJsonContains("{broken héllo", "chat", "héllo"))
    }

    @Test fun onlyVeryLongRepliesRefreshLessOften() {
        assertEquals(0L, visibleTextCopyIntervalMs(31_999))
        assertTrue(visibleTextCopyIntervalMs(32_000) in 1..100)
        assertTrue(visibleTextCopyIntervalMs(128_000) in 1..100)
    }

    @Test fun theStreamingWordCountMatchesAFullCountAcrossAnySplit() {
        val random = Random(11)
        val alphabet = listOf("a", "é", "😀", " ", "\n", "\t", "\u00A0", "\u2003", "word", "  ")
        repeat(200) {
            val text = buildString { repeat(random.nextInt(0, 300)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            val counter = StreamWordCounter()
            val buffer = StringBuilder()
            var at = 0
            while (at < text.length) {
                val end = (at + random.nextInt(1, 9)).coerceAtMost(text.length)
                buffer.append(text, at, end)
                assertEquals(countWordsFast(buffer), counter.count(buffer))
                at = end
            }
            buffer.clear()
            buffer.append("replaced with fewer")
            counter.reset()
            assertEquals(3, counter.count(buffer))
        }
    }
}
