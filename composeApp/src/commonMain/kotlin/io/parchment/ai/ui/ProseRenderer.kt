package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.launch

internal sealed class ProseBlock {
    data class Heading(val level: Int, val text: String) : ProseBlock()
    data class SceneBreak(val raw: String) : ProseBlock()
    data class BlockQuote(val text: String) : ProseBlock()
    data class CodeFence(val language: String, val code: String) : ProseBlock()
    data class MathBlock(val rawLatex: String) : ProseBlock()
    data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : ProseBlock()
    data class Paragraph(val text: String) : ProseBlock()
}

/**
 * Incremental prefix-cached Markdown block parser for 120Hz VSync letter-by-letter streaming.
 * Keeps completed paragraphs/code-fences/tables cached in `stableBlocks` and only parses the active
 * trailing <600-character tail on each frame, turning $O(N)$ per-frame parsing into $O(1)$.
 */
internal class IncrementalProseBlockCache {
    private var lastEnableMath: Boolean = true
    private var stablePrefixText: String = ""
    private var stableBlocks: List<ProseBlock> = emptyList()
    private var lastFullText: String = ""
    private var lastFullBlocks: List<ProseBlock> = emptyList()

    fun parseIncremental(raw: String, enableMath: Boolean = true): List<ProseBlock> {
        if (raw.isBlank()) return emptyList()
        if (raw == lastFullText && enableMath == lastEnableMath) {
            return lastFullBlocks
        }
        if (enableMath != lastEnableMath || stablePrefixText.isEmpty() || !raw.startsWith(stablePrefixText)) {
            lastEnableMath = enableMath
            val full = parseProseBlocks(raw, enableMath)
            lastFullText = raw
            lastFullBlocks = full
            // Try to seed a stable prefix if raw is already long
            val splitAt = raw.lastIndexOf("\n\n")
            if (splitAt >= 256) {
                val candPrefix = raw.substring(0, splitAt + 2)
                val candTail = raw.substring(splitAt + 2)
                val headB = parseProseBlocks(candPrefix, enableMath)
                val tailB = parseProseBlocks(candTail, enableMath)
                if (headB + tailB == full) {
                    stablePrefixText = candPrefix
                    stableBlocks = headB
                } else {
                    stablePrefixText = ""
                    stableBlocks = emptyList()
                }
            } else {
                stablePrefixText = ""
                stableBlocks = emptyList()
            }
            return full
        }

        val tail = raw.substring(stablePrefixText.length)
        val tailBlocks = parseProseBlocks(tail, enableMath)
        val combined = if (stableBlocks.isEmpty()) tailBlocks else stableBlocks + tailBlocks

        // Advance stablePrefixText once the active tail grows past 512 chars and has a paragraph break `\n\n`
        if (tail.length > 512) {
            val relSplit = tail.lastIndexOf("\n\n")
            if (relSplit >= 180) {
                val candHead = tail.substring(0, relSplit + 2)
                val candRem = tail.substring(relSplit + 2)
                val headB = parseProseBlocks(candHead, enableMath)
                val remB = parseProseBlocks(candRem, enableMath)
                if (headB + remB == tailBlocks) {
                    stablePrefixText += candHead
                    stableBlocks = stableBlocks + headB
                }
            }
        }

        lastFullText = raw
        lastFullBlocks = combined
        return combined
    }
}

private val MD_CODE_FENCE_OPEN_REGEX = Regex("```[a-zA-Z0-9_-]*\\n?")
private val MD_HEADING_PREFIX_REGEX = Regex("(?m)^#{1,6}\\s+")
private val MD_INLINE_LINK_REGEX = Regex("\\[([^\\]]+)\\]\\([^)]+\\)")
private val MD_BOLD_AST_REGEX = Regex("\\*\\*([^*]+)\\*\\*")
private val MD_BOLD_UND_REGEX = Regex("__([^_]+)__")
private val MD_STRIKE_REGEX = Regex("~~([^~]+)~~")
private val MD_ITALIC_AST_REGEX = Regex("(?<!\\*)\\*([^*\n]+)\\*(?!\\*)")
private val MD_INLINE_CODE_REGEX = Regex("`([^`]+)`")

/**
 * Strips Markdown syntax (`**bold**`, `*italic*`, `# Heading`, `` `code` ``) to clean plain text
 * for the 1-tap `📋 Copy (Text)` button.
 */
fun stripMarkdownToPlainText(raw: String): String {
    if (raw.isEmpty()) return ""
    return raw
        .replace(MD_CODE_FENCE_OPEN_REGEX, "")
        .replace("```", "")
        .replace(MD_HEADING_PREFIX_REGEX, "")
        .replace(MD_INLINE_LINK_REGEX, "$1")
        .replace(MD_BOLD_AST_REGEX, "$1")
        .replace(MD_BOLD_UND_REGEX, "$1")
        .replace(MD_STRIKE_REGEX, "$1")
        .replace(MD_ITALIC_AST_REGEX, "$1")
        .replace(MD_INLINE_CODE_REGEX, "$1")
        .trim()
}

@Composable
fun LiteraryProseRenderer(
    text: String,
    modifier: Modifier = Modifier,
    compactReasoningMode: Boolean = false,
    enableMarkdown: Boolean = true,
    enableMath: Boolean = true,
    enableInlineDollarMath: Boolean = true,
    autoCollapseCodeBlocks: Boolean = false,
    highlightedLineSubstring: String = ""
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val baseTextStyle = if (compactReasoningMode) {
        p.uiBodyStyle.copy(
            fontFamily = p.proseFontFamily,
            fontSize = (p.config.proseFontSizeSp * 0.86f).coerceAtLeast(13f).sp,
            lineHeight = (p.config.proseFontSizeSp * 1.36f).coerceAtLeast(19f).sp,
            color = p.narrationInk
        )
    } else {
        p.proseStyle
    }

    if (!enableMarkdown) {
        SelectionContainer {
            BasicText(
                text = text,
                style = baseTextStyle,
                modifier = modifier.fillMaxWidth()
            )
        }
        return
    }

    val blockCache = remember { IncrementalProseBlockCache() }
    val blocks = remember(text, enableMath) { blockCache.parseIncremental(text, enableMath) }

    SelectionContainer {
        Column(modifier = modifier.fillMaxWidth()) {
            blocks.forEachIndexed { index, block ->
                key(index, block) {
                when (block) {
                    is ProseBlock.SceneBreak -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = if (compactReasoningMode) 4.dp else 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                text = "—   ",
                                style = baseTextStyle.copy(
                                    color = p.accentSecondary,
                                    fontSize = (p.config.proseFontSizeSp * 0.88f).sp
                                )
                            )
                            KissVectorIcon(
                                icon = KissIcon.SPARKLES,
                                tint = p.accentSecondary,
                                size = if (compactReasoningMode) 11.dp else 13.dp
                            )
                            BasicText(
                                text = "   —",
                                style = baseTextStyle.copy(
                                    color = p.accentSecondary,
                                    fontSize = (p.config.proseFontSizeSp * 0.88f).sp
                                )
                            )
                        }
                    }

                    is ProseBlock.Heading -> {
                        val scale = if (compactReasoningMode) {
                            when (block.level) {
                                1 -> 1.12f
                                2 -> 1.06f
                                else -> 1.02f
                            }
                        } else {
                            when (block.level) {
                                1 -> 1.34f
                                2 -> 1.20f
                                else -> 1.08f
                            }
                        }
                        val styledHeading = remember(block.text, p, enableMath, enableInlineDollarMath) {
                            styleLiteraryParagraph(
                                raw = block.text,
                                palette = p,
                                indentChars = 0,
                                enableMath = enableMath,
                                enableInlineDollarMath = enableInlineDollarMath
                            )
                        }
                        val isHighlightedHeading = highlightedLineSubstring.isNotBlank() &&
                            (block.text.contains(highlightedLineSubstring, ignoreCase = true) ||
                                stripMarkdownToPlainText(block.text).contains(highlightedLineSubstring, ignoreCase = true))
                        BasicText(
                            text = styledHeading,
                            style = baseTextStyle.copy(
                                fontSize = (baseTextStyle.fontSize.value * scale).sp,
                                fontWeight = FontWeight.SemiBold,
                                color = p.primaryInk
                            ),
                            modifier = Modifier
                                .then(
                                    if (isHighlightedHeading) {
                                        Modifier
                                            .fillMaxWidth()
                                            .background(p.accent.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                                            .border(1.dp, p.accent.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    } else Modifier
                                )
                                .padding(
                                    top = if (compactReasoningMode) 3.dp else 6.dp,
                                    bottom = if (compactReasoningMode) 2.dp else 4.dp
                                )
                        )
                    }

                    is ProseBlock.BlockQuote -> {
                        val styledQuote = remember(block.text, p, enableMath, enableInlineDollarMath) {
                            styleLiteraryParagraph(
                                raw = block.text,
                                palette = p,
                                indentChars = 0,
                                enableMath = enableMath,
                                enableInlineDollarMath = enableInlineDollarMath
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .background(p.elevatedBg.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .border(0.85.dp, p.border.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            BasicText(
                                text = styledQuote,
                                style = baseTextStyle.copy(
                                    fontStyle = FontStyle.Italic,
                                    color = p.narrationInk
                                )
                            )
                        }
                    }

                    is ProseBlock.CodeFence -> {
                        var isCollapsed by remember(index, autoCollapseCodeBlocks) {
                            mutableStateOf(autoCollapseCodeBlocks)
                        }
                        val isSvgArtifact = remember(block.language, block.code) {
                            block.language.equals("svg", ignoreCase = true) ||
                                (block.code.contains("<svg", ignoreCase = true) && block.code.contains("</svg>", ignoreCase = true))
                        }
                        var showSvgPreview by remember(index, isSvgArtifact) { mutableStateOf(isSvgArtifact) }
                        var copiedCode by remember(index) { mutableStateOf(false) }
                        var savedCode by remember(index) { mutableStateOf(false) }
                        val shape = RoundedCornerShape(10.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(shape)
                                .background(p.elevatedBg)
                                .border(0.65.dp, p.border.copy(alpha = 0.52f), shape)
                        ) {
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(p.sheetBg.copy(alpha = 0.85f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    KissVectorIcon(
                                        icon = if (isSvgArtifact) KissIcon.IMAGE else KissIcon.CODE,
                                        tint = p.accent,
                                        size = 12.dp
                                    )
                                    BasicText(
                                        text = "${block.language.ifBlank { "code" }} (${block.code.lines().size} lines)",
                                        style = p.uiLabelStyle.copy(fontFamily = p.codeFontFamily)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (isSvgArtifact) {
                                        SepiaButton(
                                            text = if (showSvgPreview) "‹/›" else "👁",
                                            compact = true,
                                            style = if (showSvgPreview) SepiaButtonStyle.SECONDARY else SepiaButtonStyle.GHOST,
                                            onClick = {
                                                showSvgPreview = !showSvgPreview
                                                isCollapsed = false
                                            }
                                        )
                                    }
                                    SepiaButton(
                                        text = if (isCollapsed) "▸" else "▾",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = { isCollapsed = !isCollapsed }
                                    )
                                    SepiaButton(
                                        text = if (savedCode) "✓" else "📥",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = {
                                            scope.launch {
                                                val ext = inferCodeFenceExtension(block.language, isSvgArtifact)
                                                val saved = PlatformBridge.saveFileForExport(
                                                    "artifact-${index + 1}.$ext",
                                                    block.code.encodeToByteArray()
                                                )
                                                if (saved != null) savedCode = true
                                            }
                                        }
                                    )
                                    SepiaButton(
                                        text = if (copiedCode) "✓" else "📋",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = {
                                            clipboard.setText(AnnotatedString(block.code))
                                            copiedCode = true
                                        }
                                    )
                                }
                            }
                            AnimatedVisibility(visible = !isCollapsed) {
                                Column {
                                    SepiaDivider()
                                    val isDiffLang = block.language.equals("diff", ignoreCase = true) ||
                                        block.language.equals("patch", ignoreCase = true)
                                    if (isSvgArtifact && showSvgPreview) {
                                        InlineSvgArtifactPreview(rawSvg = block.code)
                                    } else if (isDiffLang) {
                                        SepiaRawDiffLinesView(
                                            rawDiffText = block.code,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState())
                                                .padding(11.dp)
                                        ) {
                                            BasicText(
                                                text = block.code,
                                                style = p.monoStyle
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    is ProseBlock.MathBlock -> {
                        if (enableMath) {
                            LatexMathRenderer.DisplayLatexBlock(rawLatex = block.rawLatex)
                        } else {
                            BasicText(text = block.rawLatex, style = p.monoStyle)
                        }
                    }

                    is ProseBlock.TableBlock -> {
                        val win = LocalParchmentWindowSize.current
                        var copiedTable by remember(index) { mutableStateOf(false) }
                        val colCount = maxOf(
                            block.headers.size,
                            block.rows.maxOfOrNull { it.size } ?: 0
                        ).coerceAtLeast(1)
                        val needsHorizontalScroll = colCount >= 4 || win.isCompact
                        val shape = RoundedCornerShape(8.dp)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp)
                                .clip(shape)
                                .background(p.cardBg)
                                .border(0.85.dp, p.border, shape)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(p.sheetBg.copy(alpha = 0.7f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    KissVectorIcon(
                                        icon = KissIcon.LAYOUT_GRID,
                                        tint = p.accent,
                                        size = 11.5.dp
                                    )
                                    BasicText(
                                        text = "Table ($colCount cols × ${block.rows.size} rows)",
                                        style = p.uiLabelStyle.copy(fontSize = 10.5.sp)
                                    )
                                }
                                SepiaButton(
                                    text = if (copiedTable) "✓" else "📋",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = {
                                        val tsv = buildString {
                                            if (block.headers.isNotEmpty()) {
                                                appendLine(block.headers.joinToString("\t"))
                                            }
                                            block.rows.forEach { r ->
                                                appendLine(r.joinToString("\t"))
                                            }
                                        }.trim()
                                        clipboard.setText(AnnotatedString(tsv))
                                        copiedTable = true
                                    }
                                )
                            }
                            SepiaDivider()

                            val tableBodyMod = if (needsHorizontalScroll) {
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            } else {
                                Modifier.fillMaxWidth()
                            }
                            Column(modifier = tableBodyMod) {
                                if (block.headers.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(p.sheetBg)
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        for (cIdx in 0 until colCount) {
                                            val h = block.headers.getOrElse(cIdx) { "" }
                                            Box(
                                                modifier = if (needsHorizontalScroll) Modifier.width(145.dp) else Modifier.weight(1f)
                                            ) {
                                                BasicText(
                                                    text = styleLiteraryParagraph(h, p, 0, enableMath, enableInlineDollarMath),
                                                    style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold)
                                                )
                                            }
                                        }
                                    }
                                    SepiaDivider()
                                }
                                block.rows.forEachIndexed { rIdx, rowCells ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (rIdx % 2 == 1) p.elevatedBg.copy(alpha = 0.35f) else p.cardBg)
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        for (cIdx in 0 until colCount) {
                                            val cellText = rowCells.getOrElse(cIdx) { "" }
                                            Box(
                                                modifier = if (needsHorizontalScroll) Modifier.width(145.dp) else Modifier.weight(1f)
                                            ) {
                                                BasicText(
                                                    text = styleLiteraryParagraph(cellText, p, 0, enableMath, enableInlineDollarMath),
                                                    style = p.uiBodyStyle
                                                )
                                            }
                                        }
                                    }
                                    if (rIdx < block.rows.lastIndex) {
                                        SepiaDivider()
                                    }
                                }
                            }
                        }
                    }

                    is ProseBlock.Paragraph -> {
                        val styled = remember(block.text, p, compactReasoningMode, enableMath, enableInlineDollarMath) {
                            styleLiteraryParagraph(
                                raw = block.text,
                                palette = p,
                                indentChars = if (compactReasoningMode) 0 else p.config.firstLineIndentChars,
                                enableMath = enableMath,
                                enableInlineDollarMath = enableInlineDollarMath
                            )
                        }
                        val isHighlightedPara = highlightedLineSubstring.isNotBlank() &&
                            (block.text.contains(highlightedLineSubstring, ignoreCase = true) ||
                                stripMarkdownToPlainText(block.text).contains(highlightedLineSubstring, ignoreCase = true) ||
                                (block.text.length >= 24 && highlightedLineSubstring.contains(stripMarkdownToPlainText(block.text), ignoreCase = true)))
                        BasicText(
                            text = styled,
                            style = baseTextStyle,
                            modifier = if (isHighlightedPara) {
                                Modifier
                                    .fillMaxWidth()
                                    .background(p.accent.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                                    .border(1.dp, p.accent.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            } else Modifier
                        )
                    }
                }

                if (index < blocks.lastIndex) {
                    Spacer(Modifier.height(if (compactReasoningMode) 10.dp else p.paragraphSpacing.coerceAtLeast(16.dp)))
                }
                }
            }
        }
    }
}

internal fun parseProseBlocks(raw: String, enableMath: Boolean = true): List<ProseBlock> {
    if (raw.isBlank()) return emptyList()
    val lines = raw.lines()
    val blocks = mutableListOf<ProseBlock>()
    var i = 0
    val currentPara = StringBuilder()

    fun flushPara() {
        val text = currentPara.toString().trim()
        if (text.isNotEmpty()) {
            blocks.add(ProseBlock.Paragraph(text))
        }
        currentPara.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            trimmed.startsWith("```") -> {
                flushPara()
                val lang = trimmed.removePrefix("```").trim()
                i++
                val codeBuf = StringBuilder()
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (codeBuf.isNotEmpty()) codeBuf.append('\n')
                    codeBuf.append(lines[i])
                    i++
                }
                if (enableMath && (lang.equals("math", ignoreCase = true) || lang.equals("latex", ignoreCase = true) || lang.equals("tex", ignoreCase = true))) {
                    blocks.add(ProseBlock.MathBlock(codeBuf.toString()))
                } else {
                    blocks.add(ProseBlock.CodeFence(lang, codeBuf.toString()))
                }
            }

            enableMath && trimmed.startsWith("$$") -> {
                flushPara()
                if (trimmed.length > 4 && trimmed.endsWith("$$")) {
                    blocks.add(ProseBlock.MathBlock(trimmed.removeSurrounding("$$")))
                } else {
                    val mathBuf = StringBuilder()
                    val firstLineAfter = trimmed.removePrefix("$$").trim()
                    if (firstLineAfter.isNotEmpty()) mathBuf.append(firstLineAfter)
                    i++
                    while (i < lines.size && !lines[i].trim().endsWith("$$")) {
                        if (mathBuf.isNotEmpty()) mathBuf.append('\n')
                        mathBuf.append(lines[i])
                        i++
                    }
                    if (i < lines.size) {
                        val closingLine = lines[i].trim().removeSuffix("$$").trim()
                        if (closingLine.isNotEmpty()) {
                            if (mathBuf.isNotEmpty()) mathBuf.append('\n')
                            mathBuf.append(closingLine)
                        }
                    }
                    blocks.add(ProseBlock.MathBlock(mathBuf.toString()))
                }
            }

            enableMath && trimmed.startsWith("\\[") -> {
                flushPara()
                if (trimmed.endsWith("\\]")) {
                    blocks.add(ProseBlock.MathBlock(trimmed.removePrefix("\\[").removeSuffix("\\]")))
                } else {
                    val mathBuf = StringBuilder()
                    val firstLineAfter = trimmed.removePrefix("\\[").trim()
                    if (firstLineAfter.isNotEmpty()) mathBuf.append(firstLineAfter)
                    i++
                    while (i < lines.size && !lines[i].trim().endsWith("\\]")) {
                        if (mathBuf.isNotEmpty()) mathBuf.append('\n')
                        mathBuf.append(lines[i])
                        i++
                    }
                    if (i < lines.size) {
                        val closingLine = lines[i].trim().removeSuffix("\\]").trim()
                        if (closingLine.isNotEmpty()) {
                            if (mathBuf.isNotEmpty()) mathBuf.append('\n')
                            mathBuf.append(closingLine)
                        }
                    }
                    blocks.add(ProseBlock.MathBlock(mathBuf.toString()))
                }
            }

            trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2 -> {
                flushPara()
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().let { it.startsWith("|") && it.contains("|") }) {
                    tableLines.add(lines[i].trim())
                    i++
                }
                val parsedRows = tableLines.map { rowStr ->
                    rowStr.trim('|').split('|').map { it.trim() }
                }
                if (parsedRows.size >= 2 && parsedRows[1].all { cell -> cell.all { c -> c == '-' || c == ':' || c == ' ' } }) {
                    blocks.add(ProseBlock.TableBlock(headers = parsedRows.first(), rows = parsedRows.drop(2)))
                } else {
                    blocks.add(ProseBlock.TableBlock(headers = parsedRows.firstOrNull().orEmpty(), rows = parsedRows.drop(1)))
                }
                continue
            }

            trimmed == "---" || trimmed == "***" || trimmed == "* * *" || trimmed == "___" -> {
                flushPara()
                blocks.add(ProseBlock.SceneBreak(trimmed))
            }

            trimmed.startsWith("#") -> {
                flushPara()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
                val headingText = trimmed.drop(level).trim()
                if (headingText.isNotEmpty()) {
                    blocks.add(ProseBlock.Heading(level, headingText))
                }
            }

            trimmed.startsWith(">") -> {
                flushPara()
                val quoteBuf = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    if (quoteBuf.isNotEmpty()) quoteBuf.append('\n')
                    quoteBuf.append(lines[i].trim().removePrefix(">").trimStart())
                    i++
                }
                blocks.add(ProseBlock.BlockQuote(quoteBuf.toString()))
                continue
            }

            trimmed.isEmpty() -> {
                flushPara()
            }

            else -> {
                // Treat each non-empty newline in AI output as its own distinct paragraph block
                // (or group consecutive bullet items nicely with 8.dp spacing) so single-\n and double-\n
                // paragraphs never look like a single wrapped wall of text on mobile or desktop!
                flushPara()
                val leadingSpaces = line.takeWhile { it == ' ' || it == '\t' }
                val afterSpaces = line.drop(leadingSpaces.length)
                val normalizedLine = when {
                    afterSpaces.startsWith("* ") -> "$leadingSpaces• ${afterSpaces.removePrefix("* ")}"
                    afterSpaces.startsWith("- ") -> "$leadingSpaces• ${afterSpaces.removePrefix("- ")}"
                    else -> line
                }
                blocks.add(ProseBlock.Paragraph(normalizedLine.trim()))
            }
        }
        i++
    }
    flushPara()
    return blocks
}

private fun styleLiteraryParagraph(
    raw: String,
    palette: ParchmentPalette,
    indentChars: Int,
    enableMath: Boolean = true,
    enableInlineDollarMath: Boolean = true
): AnnotatedString = buildAnnotatedString {
    val indentPrefix = if (indentChars > 0) " ".repeat(indentChars * 2) else ""
    val text = indentPrefix + raw

    var i = 0
    val n = text.length
    while (i < n) {
        val ch = text[i]
        when {
            // Inline Display Math: $$...$$
            enableMath && i + 1 < n && ch == '$' && text[i + 1] == '$' -> {
                val endIdx = text.indexOf("$$", startIndex = i + 2)
                if (endIdx != -1) {
                    val innerLatex = text.substring(i + 2, endIdx)
                    append(LatexMathRenderer.formatInlineLatexToAnnotatedString(innerLatex, palette))
                    i = endIdx + 2
                } else {
                    append(ch)
                    i++
                }
            }

            // Inline LaTeX Math: \( ... \)
            enableMath && i + 1 < n && ch == '\\' && text[i + 1] == '(' -> {
                val endIdx = text.indexOf("\\)", startIndex = i + 2)
                if (endIdx != -1) {
                    val innerLatex = text.substring(i + 2, endIdx)
                    append(LatexMathRenderer.formatInlineLatexToAnnotatedString(innerLatex, palette))
                    i = endIdx + 2
                } else {
                    append(ch)
                    i++
                }
            }

            // Inline LaTeX Math: $...$ (avoid matching currency like "$10 and $20")
            enableMath && enableInlineDollarMath && ch == '$' && (i + 1 < n && !text[i + 1].isWhitespace()) -> {
                val endIdx = text.indexOf('$', startIndex = i + 1)
                if (endIdx != -1 && endIdx > i + 1 && !text[endIdx - 1].isWhitespace() && !text.substring(i + 1, endIdx).contains('\n')) {
                    val innerLatex = text.substring(i + 1, endIdx)
                    val looksLikeCurrencyOnly = innerLatex.all { it.isDigit() || it == '.' || it == ',' }
                    if (!looksLikeCurrencyOnly) {
                        append(LatexMathRenderer.formatInlineLatexToAnnotatedString(innerLatex, palette))
                        i = endIdx + 1
                    } else {
                        append(ch)
                        i++
                    }
                } else {
                    append(ch)
                    i++
                }
            }

            // Bold + Italic: ***...***
            i + 2 < n && ch == '*' && text[i + 1] == '*' && text[i + 2] == '*' -> {
                val endIdx = text.indexOf("***", startIndex = i + 3)
                if (endIdx != -1) {
                    val inner = text.substring(i + 3, endIdx)
                    pushStyle(
                        SpanStyle(
                            color = palette.primaryInk,
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic
                        )
                    )
                    append(styleLiteraryParagraph(inner, palette, 0, enableMath, enableInlineDollarMath))
                    pop()
                    i = endIdx + 3
                } else {
                    append(ch)
                    i++
                }
            }

            // Bold: **...**
            i + 1 < n && ch == '*' && text[i + 1] == '*' -> {
                val endIdx = text.indexOf("**", startIndex = i + 2)
                if (endIdx != -1) {
                    val inner = text.substring(i + 2, endIdx)
                    pushStyle(
                        SpanStyle(
                            color = palette.primaryInk,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    append(styleLiteraryParagraph(inner, palette, 0, enableMath, enableInlineDollarMath))
                    pop()
                    i = endIdx + 2
                } else {
                    append(ch)
                    i++
                }
            }

            // Bold with underscores: __...__
            i + 1 < n && ch == '_' && text[i + 1] == '_' -> {
                val endIdx = text.indexOf("__", startIndex = i + 2)
                if (endIdx != -1) {
                    val inner = text.substring(i + 2, endIdx)
                    pushStyle(SpanStyle(color = palette.primaryInk, fontWeight = FontWeight.SemiBold))
                    append(styleLiteraryParagraph(inner, palette, 0, enableMath, enableInlineDollarMath))
                    pop()
                    i = endIdx + 2
                } else {
                    append(ch)
                    i++
                }
            }

            // Strikethrough: ~~...~~
            i + 1 < n && ch == '~' && text[i + 1] == '~' -> {
                val endIdx = text.indexOf("~~", startIndex = i + 2)
                if (endIdx != -1) {
                    val inner = text.substring(i + 2, endIdx)
                    pushStyle(SpanStyle(color = palette.mutedInk, textDecoration = TextDecoration.LineThrough))
                    append(styleLiteraryParagraph(inner, palette, 0, enableMath, enableInlineDollarMath))
                    pop()
                    i = endIdx + 2
                } else {
                    append(ch)
                    i++
                }
            }

            // Markdown Link: [label](url) with balanced-parenthesis URL support & 1-tap browser launch
            ch == '[' -> {
                val closeBracket = text.indexOf(']', startIndex = i + 1)
                val openParen = if (closeBracket != -1 && closeBracket + 1 < n && text[closeBracket + 1] == '(') closeBracket + 1 else -1
                val closeParen = if (openParen != -1) findClosingMarkdownLinkParen(text, openParen) else -1
                if (closeBracket != -1 && openParen != -1 && closeParen != -1 && !text.substring(i + 1, closeBracket).contains('\n')) {
                    val linkLabel = text.substring(i + 1, closeBracket)
                    val linkUrl = text.substring(openParen + 1, closeParen).trim()
                    val linkStyle = SpanStyle(
                        color = palette.accent,
                        fontWeight = FontWeight.Medium,
                        textDecoration = TextDecoration.Underline
                    )
                    pushLink(
                        LinkAnnotation.Url(
                            url = linkUrl,
                            styles = TextLinkStyles(style = linkStyle),
                            linkInteractionListener = {
                                if (linkUrl.isNotBlank()) {
                                    PlatformBridge.openOrPlayMedia(linkUrl)
                                }
                            }
                        )
                    )
                    pushStyle(linkStyle)
                    append(linkLabel)
                    pop()
                    pop()
                    i = closeParen + 1
                } else {
                    append(ch)
                    i++
                }
            }

            // Italic Narration / Action: *...* (only when not followed by whitespace)
            ch == '*' && i + 1 < n && !text[i + 1].isWhitespace() -> {
                val endIdx = text.indexOf('*', startIndex = i + 1)
                if (endIdx != -1 && endIdx > i + 1 && !text[endIdx - 1].isWhitespace() && !text.substring(i + 1, endIdx).contains('\n')) {
                    val inner = text.substring(i + 1, endIdx)
                    pushStyle(
                        SpanStyle(
                            color = palette.narrationInk,
                            fontStyle = FontStyle.Italic
                        )
                    )
                    append(styleLiteraryParagraph(inner, palette, 0, enableMath, enableInlineDollarMath))
                    pop()
                    i = endIdx + 1
                } else {
                    append(ch)
                    i++
                }
            }

            // Spoken Dialogue: "..." or “...” (recursively parses inner *italic*, **bold**, and `code` spans!)
            (ch == '"' || ch == '“') && palette.config.highlightDialogueAndNarration -> {
                val closeChar = if (ch == '“') '”' else '"'
                val endIdx = text.indexOf(closeChar, startIndex = i + 1)
                if (endIdx != -1) {
                    val innerDialogue = text.substring(i + 1, endIdx)
                    pushStyle(
                        SpanStyle(
                            color = palette.dialogueInk,
                            fontWeight = FontWeight.Normal
                        )
                    )
                    append(ch)
                    append(
                        styleLiteraryParagraph(
                            raw = innerDialogue,
                            palette = palette.copy(config = palette.config.copy(highlightDialogueAndNarration = false)),
                            indentChars = 0,
                            enableMath = enableMath,
                            enableInlineDollarMath = enableInlineDollarMath
                        )
                    )
                    append(closeChar)
                    pop()
                    i = endIdx + 1
                } else {
                    append(ch)
                    i++
                }
            }

            // Double-Backtick Inline Code: ``...`` (allows literal single backticks inside inline code)
            i + 1 < n && ch == '`' && text[i + 1] == '`' -> {
                val endIdx = text.indexOf("``", startIndex = i + 2)
                if (endIdx != -1) {
                    val inner = text.substring(i + 2, endIdx).trim()
                    pushStyle(
                        SpanStyle(
                            fontFamily = palette.codeFontFamily,
                            fontSize = (palette.config.proseFontSizeSp * 0.90f).coerceAtLeast(11f).sp,
                            background = palette.accent.copy(alpha = 0.12f),
                            color = palette.accent,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    append(inner)
                    pop()
                    i = endIdx + 2
                } else {
                    append(ch)
                    i++
                }
            }

            // Single-Backtick Inline Code: `...`
            ch == '`' -> {
                val endIdx = text.indexOf('`', startIndex = i + 1)
                if (endIdx != -1) {
                    val inner = text.substring(i + 1, endIdx)
                    pushStyle(
                        SpanStyle(
                            fontFamily = palette.codeFontFamily,
                            fontSize = (palette.config.proseFontSizeSp * 0.90f).coerceAtLeast(11f).sp,
                            background = palette.accent.copy(alpha = 0.12f),
                            color = palette.accent,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    append(inner)
                    pop()
                    i = endIdx + 1
                } else {
                    append(ch)
                    i++
                }
            }

            else -> {
                append(ch)
                i++
            }
        }
    }
}

/**
 * Finds the closing `)` for a Markdown link `[label](url)`, properly balancing nested parentheses
 * inside the URL (such as Wikipedia URLs `https://en.wikipedia.org/wiki/Kotlin_(programming_language)`).
 */
internal fun findClosingMarkdownLinkParen(text: String, openParenIdx: Int): Int {
    var depth = 0
    var idx = openParenIdx + 1
    while (idx < text.length) {
        val c = text[idx]
        if (c == '\n' || c == '\r') return -1
        if (c == '(') {
            depth++
        } else if (c == ')') {
            if (depth == 0) return idx
            depth--
        }
        idx++
    }
    return -1
}

private fun inferCodeFenceExtension(language: String, isSvg: Boolean): String {
    if (isSvg) return "svg"
    return when (language.trim().lowercase()) {
        "kotlin", "kt" -> "kt"
        "python", "py" -> "py"
        "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"
        "html", "htm" -> "html"
        "css" -> "css"
        "json" -> "json"
        "markdown", "md" -> "md"
        "mermaid", "mmd" -> "mmd"
        "sh", "bash", "zsh" -> "sh"
        "powershell", "ps1" -> "ps1"
        "sql" -> "sql"
        "yaml", "yml" -> "yml"
        "xml" -> "xml"
        "rust", "rs" -> "rs"
        "go", "golang" -> "go"
        "java" -> "java"
        "c" -> "c"
        "cpp", "c++", "cxx" -> "cpp"
        else -> "txt"
    }
}

