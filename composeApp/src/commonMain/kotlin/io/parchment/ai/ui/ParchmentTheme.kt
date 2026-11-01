package io.parchment.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.platform.PlatformBridge

@Immutable
data class ParchmentPalette(
    val config: ThemeConfig,
    val canvasBg: Color,
    val sheetBg: Color,
    val cardBg: Color,
    val elevatedBg: Color,
    val border: Color,
    val primaryInk: Color,
    val dialogueInk: Color,
    val narrationInk: Color,
    val mutedInk: Color,
    val accent: Color,
    val accentSecondary: Color,
    val danger: Color,
    val proseFontFamily: FontFamily,
    val codeFontFamily: FontFamily,
    val mathFontFamily: FontFamily,
    val proseStyle: TextStyle,
    val mathStyle: TextStyle,
    val uiBodyStyle: TextStyle,
    val uiLabelStyle: TextStyle,
    val uiHeaderStyle: TextStyle,
    val monoStyle: TextStyle,
    val paragraphSpacing: Dp,
    val maxReadingWidth: Dp
)

val LocalParchmentPalette = staticCompositionLocalOf {
    buildPaletteFromConfig(ThemeConfig())
}

fun parseHexColor(hex: String, fallback: Color = Color(0xFFF4ECD8)): Color {
    val cleaned = hex.trim().removePrefix("#")
    return runCatching {
        when (cleaned.length) {
            6 -> {
                val rgb = cleaned.toLong(16)
                Color(0xFF000000L or rgb)
            }
            8 -> {
                val argb = cleaned.toLong(16)
                Color(argb)
            }
            else -> fallback
        }
    }.getOrDefault(fallback)
}

fun buildPaletteFromConfig(config: ThemeConfig): ParchmentPalette {
    val canvasBg = parseHexColor(config.canvasBgHex, Color(0xFFF4ECD8))
    val sheetBg = parseHexColor(config.sheetBgHex, Color(0xFFEBE0C5))
    val cardBg = parseHexColor(config.cardBgHex, Color(0xFFFAF6EB))
    val elevatedBg = parseHexColor(config.elevatedBgHex, Color(0xFFEFE5CC))
    val border = parseHexColor(config.borderHex, Color(0xFFD5C4A1))
    val primaryInk = parseHexColor(config.primaryInkHex, Color(0xFF261C14))
    val dialogueInk = parseHexColor(config.dialogueInkHex, Color(0xFF4A2511))
    val narrationInk = parseHexColor(config.narrationInkHex, Color(0xFF5C4938))
    val mutedInk = parseHexColor(config.mutedInkHex, Color(0xFF826F5C))
    val accent = parseHexColor(config.accentHex, Color(0xFF9E4730))
    val accentSecondary = parseHexColor(config.accentSecondaryHex, Color(0xFFB57C2E))
    val danger = parseHexColor(config.dangerHex, Color(0xFFA62B2B))

    val customProseLoaded = if (config.customProseFontPath.isNotBlank()) {
        PlatformBridge.loadCustomFontFamily(config.customProseFontPath)
    } else null

    val bundledInter = PlatformBridge.loadBundledFontFamily("Inter")
    val bundledJbmNerd = PlatformBridge.loadBundledFontFamily("JetBrainsMono Nerd")
    val bundledLatex = PlatformBridge.loadBundledFontFamily("LaTeX")

    val proseFont = customProseLoaded ?: when (config.proseFontFamily.lowercase()) {
        "inter" -> bundledInter ?: FontFamily.SansSerif
        "jetbrainsmono nerd", "jetbrainsmono", "nerd" -> bundledJbmNerd ?: FontFamily.Monospace
        "latex", "katex", "math" -> bundledLatex ?: FontFamily.Serif
        "sans", "sans-serif" -> bundledInter ?: FontFamily.SansSerif
        "mono", "monospace" -> bundledJbmNerd ?: FontFamily.Monospace
        else -> FontFamily.Serif
    }

    val customCodeLoaded = if (config.customCodeFontPath.isNotBlank()) {
        PlatformBridge.loadCustomFontFamily(config.customCodeFontPath)
    } else null

    val codeFont = customCodeLoaded ?: when (config.codeFontFamily.lowercase()) {
        "jetbrainsmono nerd", "jetbrainsmono", "nerd" -> bundledJbmNerd ?: FontFamily.Monospace
        "inter", "sans", "sans-serif" -> bundledInter ?: FontFamily.SansSerif
        "serif" -> FontFamily.Serif
        "latex", "katex" -> bundledLatex ?: FontFamily.Serif
        else -> bundledJbmNerd ?: FontFamily.Monospace
    }

    val mathFont = bundledLatex ?: FontFamily.Serif

    val fontSize = config.proseFontSizeSp.coerceIn(12f, 32f)
    val lineHeight = (fontSize * config.proseLineHeightMultiplier.coerceIn(1.1f, 2.5f))
    val codeSize = config.codeFontSizeSp.coerceIn(10f, 28f)
    val codeLineHeight = (codeSize * 1.42f)
    val uiFont = customProseLoaded ?: bundledInter ?: FontFamily.SansSerif

    return ParchmentPalette(
        config = config,
        canvasBg = canvasBg,
        sheetBg = sheetBg,
        cardBg = cardBg,
        elevatedBg = elevatedBg,
        border = border,
        primaryInk = primaryInk,
        dialogueInk = dialogueInk,
        narrationInk = narrationInk,
        mutedInk = mutedInk,
        accent = accent,
        accentSecondary = accentSecondary,
        danger = danger,
        proseFontFamily = proseFont,
        codeFontFamily = codeFont,
        mathFontFamily = mathFont,
        proseStyle = TextStyle(
            fontFamily = proseFont,
            fontSize = fontSize.sp,
            lineHeight = lineHeight.sp,
            color = primaryInk,
            fontWeight = FontWeight.Normal
        ),
        mathStyle = TextStyle(
            fontFamily = mathFont,
            fontSize = fontSize.sp,
            lineHeight = lineHeight.sp,
            color = primaryInk,
            fontWeight = FontWeight.Normal
        ),
        uiBodyStyle = TextStyle(
            fontFamily = uiFont,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            color = primaryInk
        ),
        uiLabelStyle = TextStyle(
            fontFamily = uiFont,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            color = mutedInk,
            fontWeight = FontWeight.Medium
        ),
        uiHeaderStyle = TextStyle(
            fontFamily = proseFont,
            fontSize = 17.sp,
            lineHeight = 23.sp,
            color = primaryInk,
            fontWeight = FontWeight.SemiBold
        ),
        monoStyle = TextStyle(
            fontFamily = codeFont,
            fontSize = codeSize.sp,
            lineHeight = codeLineHeight.sp,
            color = primaryInk
        ),
        paragraphSpacing = config.paragraphSpacingDp.coerceIn(2f, 36f).dp,
        maxReadingWidth = config.maxReadingWidthDp.coerceIn(420f, 1800f).dp
    )
}

@Composable
fun ProvideParchmentTheme(
    config: ThemeConfig,
    content: @Composable () -> Unit
) {
    val palette = buildPaletteFromConfig(config)
    CompositionLocalProvider(LocalParchmentPalette provides palette) {
        content()
    }
}
