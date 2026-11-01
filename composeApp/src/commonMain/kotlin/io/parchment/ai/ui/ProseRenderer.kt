package io.parchment.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed class ProseBlock {
    data class Heading(val level: Int, val text: String) : ProseBlock()
    data class SceneBreak(val raw: String) : ProseBlock()
    data class BlockQuote(val text: String) : ProseBlock()
    data class CodeFence(val language: String, val code: String) : ProseBlock()
    data class Paragraph(val text: String) : ProseBlock()
}

@Composable
fun LiteraryProseRenderer(
    text: String,
    modifier: Modifier = Modifier
) {
    val p = LocalParchmentPalette.current
    val clipboard = LocalClipboardManager.current

    val blocks = remember(text) { parseProseBlocks(text) }

    SelectionContainer {
        Column(modifier = modifier.fillMaxWidth()) {
            blocks.forEachIndexed { index, block ->
                when (block) {
                    is ProseBlock.SceneBreak -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                text = "—  ✦  —",
                                style = p.proseStyle.copy(
                                    color = p.accentSecondary,
                                    fontSize = (p.config.proseFontSizeSp * 0.9f).sp
                                )
                            )
                        }
                    }

                    is ProseBlock.Heading -> {
                        val scale = when (block.level) {
                            1 -> 1.38f
                            2 -> 1.22f
                            else -> 1.1f
                        }
                        BasicText(
                            text = block.text,
                            style = p.proseStyle.copy(
                                fontSize = (p.config.proseFontSizeSp * scale).sp,
                                fontWeight = FontWeight.SemiBold,
                                color = p.primaryInk
                            ),
                            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                        )
                    }

                    is ProseBlock.BlockQuote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(p.elevatedBg.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .border(1.dp, p.border.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            BasicText(
                                text = styleLiteraryParagraph(block.text, p, indentChars = 0),
                                style = p.proseStyle.copy(
                                    fontStyle = FontStyle.Italic,
                                    color = p.narrationInk
                                )
                            )
                        }
                    }

                    is ProseBlock.CodeFence -> {
                        val shape = RoundedCornerShape(6.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(shape)
                                .background(p.elevatedBg)
                                .border(1.dp, p.border, shape)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(p.sheetBg)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText(
                                    text = block.language.ifBlank { "code" },
                                    style = p.uiLabelStyle.copy(fontFamily = FontFamily.Monospace)
                                )
                                SepiaButton(
                                    text = "Copy",
                                    compact = true,
                                    style = SepiaButtonStyle.OUTLINE,
                                    onClick = { clipboard.setText(AnnotatedString(block.code)) }
                                )
                            }
                            SepiaDivider()
                            BasicText(
                                text = block.code,
                                style = p.monoStyle,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    is ProseBlock.Paragraph -> {
                        val styled = remember(block.text, p) {
                            styleLiteraryParagraph(
                                raw = block.text,
                                palette = p,
                                indentChars = p.config.firstLineIndentChars
                            )
                        }
                        BasicText(
                            text = styled,
                            style = p.proseStyle
                        )
                    }
                }

                if (index < blocks.lastIndex) {
                    Spacer(Modifier.height(p.paragraphSpacing))
                }
            }
        }
    }
}

private fun parseProseBlocks(raw: String): List<ProseBlock> {
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
                blocks.add(ProseBlock.CodeFence(lang, codeBuf.toString()))
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
                if (currentPara.isNotEmpty()) currentPara.append('\n')
                currentPara.append(line)
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
    indentChars: Int
): AnnotatedString = buildAnnotatedString {
    val indentPrefix = if (indentChars > 0) " ".repeat(indentChars * 2) else ""
    val text = indentPrefix + raw

    if (!palette.config.highlightDialogueAndNarration) {
        append(text)
        return@buildAnnotatedString
    }

    var i = 0
    val n = text.length
    while (i < n) {
        val ch = text[i]
        when {
            // Spoken Dialogue: "..." or “...”
            ch == '"' || ch == '“' -> {
                val closeChar = if (ch == '“') '”' else '"'
                val endIdx = text.indexOf(closeChar, startIndex = i + 1)
                if (endIdx != -1) {
                    val segment = text.substring(i, endIdx + 1)
                    pushStyle(
                        SpanStyle(
                            color = palette.dialogueInk,
                            fontWeight = FontWeight.Normal
                        )
                    )
                    append(segment)
                    pop()
                    i = endIdx + 1
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
                    append(inner)
                    pop()
                    i = endIdx + 2
                } else {
                    append(ch)
                    i++
                }
            }

            // Italic Narration / Action: *...*
            ch == '*' -> {
                val endIdx = text.indexOf('*', startIndex = i + 1)
                if (endIdx != -1 && endIdx > i + 1) {
                    val inner = text.substring(i + 1, endIdx)
                    pushStyle(
                        SpanStyle(
                            color = palette.narrationInk,
                            fontStyle = FontStyle.Italic
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

            // Inline Code: `...`
            ch == '`' -> {
                val endIdx = text.indexOf('`', startIndex = i + 1)
                if (endIdx != -1) {
                    val inner = text.substring(i + 1, endIdx)
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = palette.elevatedBg,
                            color = palette.accent
                        )
                    )
                    append(" $inner ")
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
