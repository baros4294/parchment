package io.parchment.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Native Compose Foundation LaTeX Math Parser & Typesetter powered by bundled
 * KaTeX (Computer Modern) + Noto Sans Math fonts.
 * Supports display blocks (`$$...$$`, `\[...\]`), inline math (`$...$`, `\(...\)`),
 * stacked fractions (`\frac{a}{b}`), matrices/cases (`\begin{pmatrix}...`),
 * superscripts/subscripts, Greek symbols, blackboard bold, and calculus operators.
 */
object LatexMathRenderer {

    private val LATEX_SYMBOLS: Map<String, String> = mapOf(
        // Lowercase Greek
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ϵ", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ",
        "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ",
        "phi" to "ϕ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        // Uppercase Greek
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
        "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        // Calculus & N-ary Operators
        "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮",
        "sum" to "∑", "prod" to "∏", "coprod" to "∐",
        "partial" to "∂", "nabla" to "∇", "infty" to "∞",
        "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "wp" to "℘",
        // Binary Operators & Relations
        "times" to "×", "div" to "÷", "cdot" to "⋅", "pm" to "±", "mp" to "∓",
        "circ" to "∘", "bullet" to "•", "star" to "⋆", "oplus" to "⊕", "otimes" to "⊗",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "cong" to "≅",
        "propto" to "∝", "ll" to "≪", "gg" to "≫", "perp" to "⊥", "parallel" to "∥",
        "mid" to "∣", "nmid" to "∤",
        // Set Theory & Logic
        "forall" to "∀", "exists" to "∃", "nexists" to "∄",
        "in" to "∈", "notin" to "∉", "ni" to "∋",
        "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇",
        "cup" to "∪", "cap" to "∩", "setminus" to "∖", "emptyset" to "∅", "varnothing" to "∅",
        "land" to "∧", "lor" to "∨", "lnot" to "¬", "neg" to "¬",
        "top" to "⊤", "bot" to "⊥", "vdash" to "⊢", "models" to "⊨",
        // Arrows
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔",
        "iff" to "⟺", "implies" to "⟹", "impliedby" to "⟸", "mapsto" to "↦",
        "uparrow" to "↑", "downarrow" to "↓", "nearrow" to "↗", "searrow" to "↘",
        // Delimiters & Misc
        "langle" to "⟨", "rangle" to "⟩", "lceil" to "⌈", "rceil" to "⌉",
        "lfloor" to "⌊", "rfloor" to "⌋",
        "dots" to "…", "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
        "prime" to "′", "degree" to "°", "triangle" to "△", "angle" to "∠",
        "quad" to "  ", "qquad" to "    ", "," to " ", ";" to " ", "!" to ""
    )

    private val BLACKBOARD_BOLD: Map<Char, String> = mapOf(
        'A' to "𝔸", 'B' to "𝔹", 'C' to "ℂ", 'D' to "𝔻", 'E' to "𝔼", 'F' to "𝔽", 'G' to "𝔾",
        'H' to "ℍ", 'I' to "𝕀", 'J' to "𝕁", 'K' to "𝕂", 'L' to "𝕃", 'M' to "𝕄", 'N' to "ℕ",
        'O' to "𝕆", 'P' to "ℙ", 'Q' to "ℚ", 'R' to "ℝ", 'S' to "𝕊", 'T' to "𝕋", 'U' to "𝕌",
        'V' to "𝕍", 'W' to "𝕎", 'X' to "𝕏", 'Y' to "𝕐", 'Z' to "ℤ"
    )

    sealed class DisplayMathNode {
        data class TextRun(val annotated: AnnotatedString) : DisplayMathNode()
        data class StackedFraction(val numerator: AnnotatedString, val denominator: AnnotatedString) : DisplayMathNode()
        data class MatrixNode(val leftDelim: String, val rightDelim: String, val rows: List<List<AnnotatedString>>) : DisplayMathNode()
    }

    @Composable
    fun DisplayLatexBlock(
        rawLatex: String,
        modifier: Modifier = Modifier
    ) {
        val p = LocalParchmentPalette.current
        @Suppress("DEPRECATION")
        val clipboard = LocalClipboardManager.current
        val cleaned = rawLatex.trim()
            .removeSurrounding("$$")
            .removePrefix("\\[").removeSuffix("\\]")
            .trim()

        val nodes = remember(cleaned, p) { parseDisplayMathNodes(cleaned, p) }

        val shape = RoundedCornerShape(6.dp)
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(shape)
                .background(p.cardBg)
                .border(1.dp, p.border, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                nodes.forEach { node ->
                    when (node) {
                        is DisplayMathNode.TextRun -> {
                            BasicText(
                                text = node.annotated,
                                style = p.mathStyle.copy(fontSize = (p.config.proseFontSizeSp * 1.12f).sp)
                            )
                        }
                        is DisplayMathNode.StackedFraction -> {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .width(IntrinsicSize.Max),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText(
                                    text = node.numerator,
                                    style = p.mathStyle.copy(fontSize = (p.config.proseFontSizeSp * 0.96f).sp),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.2.dp)
                                        .background(p.primaryInk)
                                )
                                BasicText(
                                    text = node.denominator,
                                    style = p.mathStyle.copy(fontSize = (p.config.proseFontSizeSp * 0.96f).sp),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                        is DisplayMathNode.MatrixNode -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                if (node.leftDelim.isNotEmpty()) {
                                    BasicText(
                                        text = node.leftDelim,
                                        style = p.mathStyle.copy(fontSize = (p.config.proseFontSizeSp * 1.45f).sp)
                                    )
                                }
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                ) {
                                    node.rows.forEach { rowCells ->
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            rowCells.forEach { cell ->
                                                BasicText(
                                                    text = cell,
                                                    style = p.mathStyle.copy(fontSize = p.config.proseFontSizeSp.sp)
                                                )
                                            }
                                        }
                                    }
                                }
                                if (node.rightDelim.isNotEmpty()) {
                                    BasicText(
                                        text = node.rightDelim,
                                        style = p.mathStyle.copy(fontSize = (p.config.proseFontSizeSp * 1.45f).sp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            var copiedTex by remember(cleaned) { mutableStateOf(false) }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                SepiaButton(
                    text = if (copiedTex) "✓ Copied TeX" else "📋 Copy TeX",
                    compact = true,
                    style = SepiaButtonStyle.GHOST,
                    onClick = {
                        clipboard.setText(AnnotatedString(cleaned))
                        copiedTex = true
                    }
                )
            }
        }
    }

    /**
     * Parses a display math expression into top-level runs, stacked `\frac{num}{den}` fractions,
     * and `\begin{pmatrix|bmatrix|matrix|cases}...\end{...}` matrices.
     */
    private fun parseDisplayMathNodes(latex: String, palette: ParchmentPalette): List<DisplayMathNode> {
        val nodes = mutableListOf<DisplayMathNode>()
        var i = 0
        val n = latex.length
        val currentRun = StringBuilder()

        fun flushRun() {
            if (currentRun.isNotEmpty()) {
                nodes.add(DisplayMathNode.TextRun(formatInlineLatexToAnnotatedString(currentRun.toString(), palette)))
                currentRun.clear()
            }
        }

        while (i < n) {
            when {
                latex.startsWith("\\frac", i) || latex.startsWith("\\dfrac", i) -> {
                    val cmdLen = if (latex.startsWith("\\dfrac", i)) 6 else 5
                    val numGroup = extractBracedGroup(latex, i + cmdLen)
                    if (numGroup != null) {
                        val denGroup = extractBracedGroup(latex, numGroup.second)
                        if (denGroup != null) {
                            flushRun()
                            nodes.add(
                                DisplayMathNode.StackedFraction(
                                    numerator = formatInlineLatexToAnnotatedString(numGroup.first, palette),
                                    denominator = formatInlineLatexToAnnotatedString(denGroup.first, palette)
                                )
                            )
                            i = denGroup.second
                            continue
                        }
                    }
                    currentRun.append(latex[i])
                    i++
                }

                latex.startsWith("\\begin{", i) -> {
                    val envEnd = latex.indexOf('}', i + 7)
                    if (envEnd != -1) {
                        val envName = latex.substring(i + 7, envEnd)
                        val closeTag = "\\end{$envName}"
                        val closeIdx = latex.indexOf(closeTag, envEnd + 1)
                        if (closeIdx != -1) {
                            flushRun()
                            val body = latex.substring(envEnd + 1, closeIdx).trim()
                            val (leftD, rightD) = when (envName) {
                                "pmatrix" -> "(" to ")"
                                "bmatrix" -> "[" to "]"
                                "Bmatrix" -> "{" to "}"
                                "vmatrix" -> "|" to "|"
                                "cases" -> "{" to ""
                                else -> "" to ""
                            }
                            val rawRows = body.split("\\\\").map { it.trim() }.filter { it.isNotEmpty() }
                            val parsedRows = rawRows.map { rowStr ->
                                rowStr.split("&").map { cellStr ->
                                    formatInlineLatexToAnnotatedString(cellStr.trim(), palette)
                                }
                            }
                            nodes.add(DisplayMathNode.MatrixNode(leftD, rightD, parsedRows))
                            i = closeIdx + closeTag.length
                            continue
                        }
                    }
                    currentRun.append(latex[i])
                    i++
                }

                else -> {
                    currentRun.append(latex[i])
                    i++
                }
            }
        }
        flushRun()
        return nodes
    }

    /**
     * Converts inline LaTeX math into a rich AnnotatedString using the bundled KaTeX / NotoSansMath font,
     * italic mathematical variables, upright numbers/operators, and real superscript/subscript spans.
     */
    fun formatInlineLatexToAnnotatedString(
        latex: String,
        palette: ParchmentPalette
    ): AnnotatedString = buildAnnotatedString {
        val baseMathSpan = SpanStyle(
            fontFamily = palette.mathFontFamily,
            color = palette.primaryInk
        )
        pushStyle(baseMathSpan)
        appendLatexSegment(this, latex.trim(), palette, scaleFactor = 1.0f)
        pop()
    }

    private fun appendLatexSegment(
        builder: AnnotatedString.Builder,
        src: String,
        palette: ParchmentPalette,
        scaleFactor: Float
    ) {
        var i = 0
        val n = src.length
        val baseSizeSp = palette.config.proseFontSizeSp * scaleFactor

        while (i < n) {
            val ch = src[i]
            when {
                ch == '\\' -> {
                    if (i + 1 < n && !src[i + 1].isLetter()) {
                        val symChar = src[i + 1].toString()
                        val mapped = LATEX_SYMBOLS[symChar] ?: symChar
                        builder.append(mapped)
                        i += 2
                        continue
                    }
                    var endCmd = i + 1
                    while (endCmd < n && src[endCmd].isLetter()) endCmd++
                    val cmd = src.substring(i + 1, endCmd)

                    when (cmd) {
                        "frac", "dfrac", "tfrac" -> {
                            val g1 = extractBracedGroup(src, endCmd)
                            val g2 = if (g1 != null) extractBracedGroup(src, g1.second) else null
                            if (g1 != null && g2 != null) {
                                builder.append("(")
                                appendLatexSegment(builder, g1.first, palette, scaleFactor * 0.92f)
                                builder.append(" ⁄ ")
                                appendLatexSegment(builder, g2.first, palette, scaleFactor * 0.92f)
                                builder.append(")")
                                i = g2.second
                            } else {
                                i = endCmd
                            }
                        }
                        "binom", "dbinom", "tbinom" -> {
                            val g1 = extractBracedGroup(src, endCmd)
                            val g2 = if (g1 != null) extractBracedGroup(src, g1.second) else null
                            if (g1 != null && g2 != null) {
                                builder.append("(")
                                appendLatexSegment(builder, g1.first, palette, scaleFactor * 0.92f)
                                builder.append(" choose ")
                                appendLatexSegment(builder, g2.first, palette, scaleFactor * 0.92f)
                                builder.append(")")
                                i = g2.second
                            } else {
                                i = endCmd
                            }
                        }
                        "boxed" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                builder.append("[")
                                appendLatexSegment(builder, g.first, palette, scaleFactor)
                                builder.append("]")
                                i = g.second
                            } else i = endCmd
                        }
                        "pmod" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                builder.append(" (mod ")
                                appendLatexSegment(builder, g.first, palette, scaleFactor)
                                builder.append(")")
                                i = g.second
                            } else i = endCmd
                        }
                        "sqrt" -> {
                            var cursor = endCmd
                            if (cursor < n && src[cursor] == '[') {
                                val closeBracket = src.indexOf(']', cursor + 1)
                                if (closeBracket != -1) {
                                    val rootDeg = src.substring(cursor + 1, closeBracket)
                                    builder.pushStyle(
                                        SpanStyle(
                                            baselineShift = BaselineShift.Superscript,
                                            fontSize = (baseSizeSp * 0.65f).sp
                                        )
                                    )
                                    appendLatexSegment(builder, rootDeg, palette, scaleFactor * 0.65f)
                                    builder.pop()
                                    cursor = closeBracket + 1
                                }
                            }
                            val radGroup = extractBracedGroup(src, cursor)
                            builder.append("√")
                            if (radGroup != null) {
                                builder.append("(")
                                appendLatexSegment(builder, radGroup.first, palette, scaleFactor)
                                builder.append(")")
                                i = radGroup.second
                            } else {
                                i = cursor
                            }
                        }
                        "mathbb" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                val mapped = g.first.map { c -> BLACKBOARD_BOLD[c] ?: c.toString() }.joinToString("")
                                builder.append(mapped)
                                i = g.second
                            } else i = endCmd
                        }
                        "mathbf", "boldsymbol", "bm" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                                appendLatexSegment(builder, g.first, palette, scaleFactor)
                                builder.pop()
                                i = g.second
                            } else i = endCmd
                        }
                        "mathrm", "text", "textrm", "operatorname" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Normal))
                                builder.append(g.first)
                                builder.pop()
                                i = g.second
                            } else i = endCmd
                        }
                        "mathit", "textit" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                                appendLatexSegment(builder, g.first, palette, scaleFactor)
                                builder.pop()
                                i = g.second
                            } else i = endCmd
                        }
                        "hat", "bar", "vec", "dot", "ddot", "tilde", "overline" -> {
                            val g = extractBracedGroup(src, endCmd)
                            val combining = when (cmd) {
                                "hat" -> "\u0302"
                                "bar", "overline" -> "\u0304"
                                "vec" -> "\u20D7"
                                "dot" -> "\u0307"
                                "ddot" -> "\u0308"
                                "tilde" -> "\u0303"
                                else -> ""
                            }
                            if (g != null) {
                                appendLatexSegment(builder, g.first, palette, scaleFactor)
                                builder.append(combining)
                                i = g.second
                            } else i = endCmd
                        }
                        "left", "right", "big", "Big", "bigg", "Bigg" -> {
                            i = endCmd
                        }
                        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
                        "sinh", "cosh", "tanh", "log", "ln", "exp", "lim", "sup", "inf",
                        "min", "max", "det", "dim", "ker", "deg", "gcd", "Pr", "arg" -> {
                            builder.pushStyle(SpanStyle(fontStyle = FontStyle.Normal))
                            builder.append(cmd)
                            builder.pop()
                            i = endCmd
                        }
                        else -> {
                            val sym = LATEX_SYMBOLS[cmd]
                            if (sym != null) {
                                builder.append(sym)
                            } else {
                                builder.append(cmd)
                            }
                            i = endCmd
                        }
                    }
                }

                ch == '^' || ch == '_' -> {
                    val isSuper = (ch == '^')
                    val shift = if (isSuper) BaselineShift.Superscript else BaselineShift.Subscript
                    val subScale = (scaleFactor * 0.72f).coerceAtLeast(0.55f)
                    val group = extractBracedGroup(src, i + 1)
                    builder.pushStyle(
                        SpanStyle(
                            baselineShift = shift,
                            fontSize = (palette.config.proseFontSizeSp * subScale).sp
                        )
                    )
                    if (group != null) {
                        appendLatexSegment(builder, group.first, palette, subScale)
                        i = group.second
                    } else if (i + 1 < n) {
                        if (src[i + 1] == '\\') {
                            var endCmd = i + 2
                            while (endCmd < n && src[endCmd].isLetter()) endCmd++
                            val subCmd = src.substring(i + 1, endCmd)
                            appendLatexSegment(builder, subCmd, palette, subScale)
                            i = endCmd
                        } else {
                            appendLatexSegment(builder, src[i + 1].toString(), palette, subScale)
                            i += 2
                        }
                    } else {
                        i++
                    }
                    builder.pop()
                }

                ch == '{' || ch == '}' -> {
                    i++
                }

                ch.isLetter() -> {
                    // Single mathematical variables in TeX are italicized by convention
                    builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    builder.append(ch)
                    builder.pop()
                    i++
                }

                ch == '-' -> {
                    builder.append('−') // Proper mathematical minus sign
                    i++
                }

                else -> {
                    builder.append(ch)
                    i++
                }
            }
        }
    }

    /**
     * Extracts `{ ... }` starting at `startIndex` (skipping leading whitespace), returning
     * `(innerContent, indexAfterClosingBrace)`.
     */
    private fun extractBracedGroup(s: String, startIndex: Int): Pair<String, Int>? {
        var idx = startIndex
        while (idx < s.length && s[idx].isWhitespace()) idx++
        if (idx >= s.length || s[idx] != '{') return null
        var depth = 0
        val startInner = idx + 1
        var cursor = idx
        while (cursor < s.length) {
            when (s[cursor]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return s.substring(startInner, cursor) to (cursor + 1)
                    }
                }
            }
            cursor++
        }
        return s.substring(startInner) to s.length
    }
}
