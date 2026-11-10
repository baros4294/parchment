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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
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
        "bigcup" to "⋃", "bigcap" to "⋂", "bigoplus" to "⨁", "bigotimes" to "⨂",
        "bigsqcup" to "⨆", "bigvee" to "⋁", "bigwedge" to "⋀",
        "partial" to "∂", "nabla" to "∇", "infty" to "∞",
        "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "wp" to "℘",
        "aleph" to "ℵ", "beth" to "ℶ",
        // Binary Operators & Relations
        "times" to "×", "div" to "÷", "cdot" to "⋅", "pm" to "±", "mp" to "∓",
        "circ" to "∘", "bullet" to "•", "star" to "⋆", "oplus" to "⊕", "otimes" to "⊗",
        "odot" to "⊙", "ominus" to "⊖", "oslash" to "⊘", "ast" to "∗",
        "dagger" to "†", "ddagger" to "‡", "amalg" to "⨿",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "cong" to "≅",
        "propto" to "∝", "ll" to "≪", "gg" to "≫", "perp" to "⊥", "parallel" to "∥",
        "mid" to "∣", "nmid" to "∤", "asymp" to "≍", "doteq" to "≐",
        "prec" to "≺", "succ" to "≻", "preceq" to "≼", "succeq" to "≽",
        // Set Theory & Logic
        "forall" to "∀", "exists" to "∃", "nexists" to "∄",
        "in" to "∈", "notin" to "∉", "ni" to "∋",
        "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇",
        "sqsubset" to "⊏", "sqsupset" to "⊐", "sqsubseteq" to "⊑", "sqsupseteq" to "⊒",
        "cup" to "∪", "cap" to "∩", "setminus" to "∖", "emptyset" to "∅", "varnothing" to "∅",
        "land" to "∧", "wedge" to "∧", "lor" to "∨", "vee" to "∨", "lnot" to "¬", "neg" to "¬",
        "top" to "⊤", "bot" to "⊥", "vdash" to "⊢", "dashv" to "⊣", "models" to "⊨",
        "therefore" to "∴", "because" to "∵",
        // Arrows
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←", "leftrightarrow" to "↔",
        "longrightarrow" to "⟶", "longleftarrow" to "⟵", "longleftrightarrow" to "⟷",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔",
        "Longrightarrow" to "⟹", "Longleftarrow" to "⟸", "Longleftrightarrow" to "⟺",
        "iff" to "⟺", "implies" to "⟹", "impliedby" to "⟸", "mapsto" to "↦", "longmapsto" to "⟼",
        "uparrow" to "↑", "downarrow" to "↓", "updownarrow" to "↕",
        "Uparrow" to "⇑", "Downarrow" to "⇓", "nearrow" to "↗", "searrow" to "↘",
        "nwarrow" to "↖", "swarrow" to "↙", "hookrightarrow" to "↪", "hookleftarrow" to "↩",
        // Delimiters & Misc
        "langle" to "⟨", "rangle" to "⟩", "lceil" to "⌈", "rceil" to "⌉",
        "lfloor" to "⌊", "rfloor" to "⌋", "vert" to "|", "Vert" to "‖", "| " to "‖",
        "backslash" to "\\", "colon" to ":",
        "dots" to "…", "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
        "prime" to "′", "degree" to "°", "triangle" to "△", "triangledown" to "▽",
        "triangleleft" to "◁", "triangleright" to "▷", "angle" to "∠",
        "checkmark" to "✓", "square" to "□", "blacksquare" to "■", "diamond" to "⋄",
        "clubsuit" to "♣", "diamondsuit" to "♢", "heartsuit" to "♡", "spadesuit" to "♠",
        "quad" to "  ", "qquad" to "    ", "," to " ", ";" to " ", ":" to " ", "!" to "", " " to " "
    )

    private val BLACKBOARD_BOLD: Map<Char, String> = mapOf(
        'A' to "𝔸", 'B' to "𝔹", 'C' to "ℂ", 'D' to "𝔻", 'E' to "𝔼", 'F' to "𝔽", 'G' to "𝔾",
        'H' to "ℍ", 'I' to "𝕀", 'J' to "𝕁", 'K' to "𝕂", 'L' to "𝕃", 'M' to "𝕄", 'N' to "ℕ",
        'O' to "𝕆", 'P' to "ℙ", 'Q' to "ℚ", 'R' to "ℝ", 'S' to "𝕊", 'T' to "𝕋", 'U' to "𝕌",
        'V' to "𝕍", 'W' to "𝕎", 'X' to "𝕏", 'Y' to "𝕐", 'Z' to "ℤ",
        'k' to "𝕜", '1' to "𝟙"
    )

    private val CALLIGRAPHIC_SCRIPT: Map<Char, String> = mapOf(
        'A' to "𝒜", 'B' to "ℬ", 'C' to "𝒞", 'D' to "𝒟", 'E' to "ℰ", 'F' to "ℱ", 'G' to "𝒢",
        'H' to "ℋ", 'I' to "ℐ", 'J' to "𝒥", 'K' to "𝒦", 'L' to "ℒ", 'M' to "ℳ", 'N' to "𝒩",
        'O' to "𝒪", 'P' to "𝒫", 'Q' to "𝒬", 'R' to "ℛ", 'S' to "𝒮", 'T' to "𝒯", 'U' to "𝒰",
        'V' to "𝒱", 'W' to "𝒲", 'X' to "𝒳", 'Y' to "𝒴", 'Z' to "𝒵",
        'a' to "𝒶", 'b' to "𝒷", 'c' to "𝒸", 'd' to "𝒹", 'e' to "ℯ", 'f' to "𝒻", 'g' to "ℊ",
        'h' to "𝒽", 'i' to "𝒾", 'j' to "𝒿", 'k' to "𝓀", 'l' to "𝓁", 'm' to "𝓂", 'n' to "𝓃",
        'o' to "ℴ", 'p' to "𝓅", 'q' to "𝓆", 'r' to "𝓇", 's' to "𝓈", 't' to "𝓉", 'u' to "𝓊",
        'v' to "𝓋", 'w' to "𝓌", 'x' to "𝓍", 'y' to "𝓎", 'z' to "𝓏"
    )

    private val FRAKTUR_MAP: Map<Char, String> = mapOf(
        'A' to "𝔄", 'B' to "𝔅", 'C' to "ℭ", 'D' to "𝔇", 'E' to "𝔈", 'F' to "𝔉", 'G' to "𝔊",
        'H' to "ℌ", 'I' to "ℑ", 'J' to "𝔍", 'K' to "𝔎", 'L' to "𝔏", 'M' to "𝔐", 'N' to "𝔑",
        'O' to "𝔒", 'P' to "𝔓", 'Q' to "𝔔", 'R' to "ℜ", 'S' to "𝔖", 'T' to "𝔗", 'U' to "𝔘",
        'V' to "𝔙", 'W' to "𝔚", 'X' to "𝔛", 'Y' to "𝔜", 'Z' to "ℨ",
        'a' to "𝔞", 'b' to "𝔟", 'c' to "𝔠", 'd' to "𝔡", 'e' to "𝔢", 'f' to "𝔣", 'g' to "𝔤",
        'h' to "𝔥", 'i' to "𝔦", 'j' to "𝔧", 'k' to "𝔨", 'l' to "𝔩", 'm' to "𝔪", 'n' to "𝔫",
        'o' to "𝔬", 'p' to "𝔭", 'q' to "𝔮", 'r' to "𝔯", 's' to "𝔰", 't' to "𝔱", 'u' to "𝔲",
        'v' to "𝔳", 'w' to "𝔴", 'x' to "𝔵", 'y' to "𝔶", 'z' to "𝔷"
    )

    sealed class DisplayMathNode {
        data class TextRun(val annotated: AnnotatedString) : DisplayMathNode()
        data class StackedFraction(val numerator: AnnotatedString, val denominator: AnnotatedString) : DisplayMathNode()
        data class MatrixNode(val leftDelim: String, val rightDelim: String, val rows: List<List<AnnotatedString>>) : DisplayMathNode()
    }

    private val DISPLAY_ALIGN_ENV_REGEX = Regex("""^\s*\\begin\{(align\*?|aligned|equation\*?|gather\*?|gathered|split)\}([\s\S]*?)\\end\{\1\}\s*$""")
    private val DISPLAY_TAG_REGEX = Regex("""\\tag\{([^}]*)\}""")

    /**
     * Splits top-level `\\` line breaks outside of `\begin{...}...\end{...}` blocks so multi-line
     * equations in `$$...$$` or `\begin{align}...\end{align}` render as stacked rows.
     */
    private fun splitTopLevelDisplayLines(latex: String): List<String> {
        val unwrapped = DISPLAY_ALIGN_ENV_REGEX.matchEntire(latex)?.groupValues?.get(2)?.trim() ?: latex
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var envDepth = 0
        var braceDepth = 0
        var i = 0
        val n = unwrapped.length
        while (i < n) {
            when {
                unwrapped.startsWith("\\begin{", i) -> {
                    envDepth++
                    sb.append("\\begin{")
                    i += 7
                }
                unwrapped.startsWith("\\end{", i) -> {
                    envDepth = (envDepth - 1).coerceAtLeast(0)
                    sb.append("\\end{")
                    i += 5
                }
                unwrapped[i] == '{' -> {
                    braceDepth++
                    sb.append('{')
                    i++
                }
                unwrapped[i] == '}' -> {
                    braceDepth = (braceDepth - 1).coerceAtLeast(0)
                    sb.append('}')
                    i++
                }
                envDepth == 0 && braceDepth == 0 && i + 1 < n && unwrapped[i] == '\\' && unwrapped[i + 1] == '\\' -> {
                    val lineStr = sb.toString().replace("&", " ").replace(DISPLAY_TAG_REGEX, "  ($1)").trim()
                    if (lineStr.isNotEmpty()) lines.add(lineStr)
                    sb.clear()
                    i += 2
                    // Skip optional `\\[4pt]` row spacing argument
                    while (i < n && unwrapped[i] == ' ') i++
                    if (i < n && unwrapped[i] == '[') {
                        val closeBracket = unwrapped.indexOf(']', i + 1)
                        if (closeBracket != -1) i = closeBracket + 1
                    }
                }
                else -> {
                    if (envDepth == 0 && unwrapped[i] == '&') {
                        sb.append(' ')
                    } else {
                        sb.append(unwrapped[i])
                    }
                    i++
                }
            }
        }
        val tail = sb.toString().replace(DISPLAY_TAG_REGEX, "  ($1)").trim()
        if (tail.isNotEmpty()) lines.add(tail)
        return if (lines.isEmpty()) listOf(unwrapped) else lines
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

        val displayRows = remember(cleaned, p) {
            splitTopLevelDisplayLines(cleaned).map { lineLatex ->
                parseDisplayMathNodes(lineLatex, p)
            }
        }

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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                displayRows.forEach { nodes ->
                    Row(
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
                }
            }

            var copiedTex by remember(cleaned) { mutableStateOf(false) }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                SepiaButton(
                    text = if (copiedTex) "✓" else "📋",
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
        scaleFactor: Float,
        forceUprightLetters: Boolean = false
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
                        "mathbb", "Bbb" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                val mapped = g.first.map { c -> BLACKBOARD_BOLD[c] ?: c.toString() }.joinToString("")
                                builder.append(mapped)
                                i = g.second
                            } else i = endCmd
                        }
                        "mathcal", "mathscr", "cal" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                val mapped = g.first.map { c -> CALLIGRAPHIC_SCRIPT[c] ?: c.toString() }.joinToString("")
                                builder.append(mapped)
                                i = g.second
                            } else i = endCmd
                        }
                        "mathfrak", "frak" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                val mapped = g.first.map { c -> FRAKTUR_MAP[c] ?: c.toString() }.joinToString("")
                                builder.append(mapped)
                                i = g.second
                            } else i = endCmd
                        }
                        "mathbf", "boldsymbol", "bm", "textbf" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                                appendLatexSegment(builder, g.first, palette, scaleFactor)
                                builder.pop()
                                i = g.second
                            } else i = endCmd
                        }
                        "mathrm", "text", "textrm", "textnormal", "operatorname", "mathsf", "textsf" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Normal))
                                appendLatexSegment(builder, g.first, palette, scaleFactor, forceUprightLetters = true)
                                builder.pop()
                                i = g.second
                            } else i = endCmd
                        }
                        "mathtt", "texttt" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                builder.pushStyle(SpanStyle(fontFamily = palette.codeFontFamily, fontStyle = FontStyle.Normal))
                                appendLatexSegment(builder, g.first, palette, scaleFactor * 0.92f, forceUprightLetters = true)
                                builder.pop()
                                i = g.second
                            } else i = endCmd
                        }
                        "mathit", "textit", "emph" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                                appendLatexSegment(builder, g.first, palette, scaleFactor)
                                builder.pop()
                                i = g.second
                            } else i = endCmd
                        }
                        "underline" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                builder.pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                                appendLatexSegment(builder, g.first, palette, scaleFactor)
                                builder.pop()
                                i = g.second
                            } else i = endCmd
                        }
                        "cancel", "bcancel", "xcancel", "sout" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = palette.mutedInk))
                                appendLatexSegment(builder, g.first, palette, scaleFactor)
                                builder.pop()
                                i = g.second
                            } else i = endCmd
                        }
                        "xrightarrow", "xleftarrow" -> {
                            val arrowSym = if (cmd == "xrightarrow") "→" else "←"
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                builder.append(" $arrowSym(")
                                appendLatexSegment(builder, g.first, palette, scaleFactor * 0.85f)
                                builder.append(") ")
                                i = g.second
                            } else {
                                builder.append(arrowSym)
                                i = endCmd
                            }
                        }
                        "overset", "stackrel", "uderset", "underset" -> {
                            val g1 = extractBracedGroup(src, endCmd)
                            val g2 = if (g1 != null) extractBracedGroup(src, g1.second) else null
                            if (g1 != null && g2 != null) {
                                appendLatexSegment(builder, g2.first, palette, scaleFactor)
                                builder.pushStyle(
                                    SpanStyle(
                                        baselineShift = if (cmd.startsWith("u")) BaselineShift.Subscript else BaselineShift.Superscript,
                                        fontSize = (baseSizeSp * 0.70f).sp
                                    )
                                )
                                appendLatexSegment(builder, g1.first, palette, scaleFactor * 0.70f)
                                builder.pop()
                                i = g2.second
                            } else i = endCmd
                        }
                        "overbrace", "underbrace" -> {
                            val g = extractBracedGroup(src, endCmd)
                            if (g != null) {
                                appendLatexSegment(builder, g.first, palette, scaleFactor)
                                i = g.second
                            } else i = endCmd
                        }
                        "color", "textcolor" -> {
                            val g1 = extractBracedGroup(src, endCmd)
                            val g2 = if (cmd == "textcolor" && g1 != null) extractBracedGroup(src, g1.second) else null
                            if (g2 != null) {
                                appendLatexSegment(builder, g2.first, palette, scaleFactor)
                                i = g2.second
                            } else if (g1 != null) {
                                i = g1.second
                            } else i = endCmd
                        }
                        "hat", "widehat", "bar", "vec", "overrightarrow", "dot", "ddot", "tilde", "widetilde", "overline" -> {
                            val g = extractBracedGroup(src, endCmd)
                            val combining = when (cmd) {
                                "hat", "widehat" -> "\u0302"
                                "bar", "overline" -> "\u0304"
                                "vec", "overrightarrow" -> "\u20D7"
                                "dot" -> "\u0307"
                                "ddot" -> "\u0308"
                                "tilde", "widetilde" -> "\u0303"
                                else -> ""
                            }
                            if (g != null) {
                                appendLatexSegment(builder, g.first, palette, scaleFactor)
                                builder.append(combining)
                                i = g.second
                            } else i = endCmd
                        }
                        "left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr",
                        "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "noindent", "hspace", "vspace" -> {
                            val g = if (cmd == "hspace" || cmd == "vspace") extractBracedGroup(src, endCmd) else null
                            i = g?.second ?: endCmd
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
                    if (forceUprightLetters) {
                        builder.append(ch)
                    } else {
                        // Single mathematical variables in TeX are italicized by convention
                        builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        builder.append(ch)
                        builder.pop()
                    }
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
