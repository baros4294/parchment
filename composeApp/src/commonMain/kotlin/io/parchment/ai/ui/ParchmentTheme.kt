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
    val proseStyle: TextStyle,
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

    val proseFont = when (config.proseFontFamily.lowercase()) {
        "sans", "sans-serif" -> FontFamily.SansSerif
        "mono", "monospace" -> FontFamily.Monospace
        else -> FontFamily.Serif
    }

    val fontSize = config.proseFontSizeSp.coerceIn(12f, 32f)
    val lineHeight = (fontSize * config.proseLineHeightMultiplier.coerceIn(1.1f, 2.5f))

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
        proseStyle = TextStyle(
            fontFamily = proseFont,
            fontSize = fontSize.sp,
            lineHeight = lineHeight.sp,
            color = primaryInk,
            fontWeight = FontWeight.Normal
        ),
        uiBodyStyle = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            color = primaryInk
        ),
        uiLabelStyle = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            color = mutedInk,
            fontWeight = FontWeight.Medium
        ),
        uiHeaderStyle = TextStyle(
            fontFamily = FontFamily.Serif,
            fontSize = 17.sp,
            lineHeight = 23.sp,
            color = primaryInk,
            fontWeight = FontWeight.SemiBold
        ),
        monoStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
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
