package io.parchment.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pure Compose Foundation Lucide-Inspired KISS Vector Icon Pack (24x24 coordinate grid).
 * Zero OS emoji dependencies — renders crisp, theme-tinted SVG vector strokes at any DPI.
 */
enum class KissIcon {
    MENU,
    SIDEBAR_CLOSE,
    SETTINGS,
    SEARCH,
    PLUS,
    CLOSE,
    TRASH,
    COPY,
    MARKDOWN,
    EDIT,
    SPARKLES,
    REFRESH,
    ARROW_RIGHT,
    ARROW_LEFT,
    ARROW_UP,
    ARROW_DOWN,
    CHEVRONS_UP,
    CHEVRONS_DOWN,
    CHEVRON_DOWN,
    CHEVRON_UP,
    CHEVRON_RIGHT,
    MAP_TREE,
    GIT_BRANCH,
    BRAIN,
    PAPERCLIP,
    GLOBE,
    IMAGE,
    VIDEO,
    CODE,
    LINK,
    ZAP,
    CLOCK,
    STOP,
    SEND_ENTER,
    EYE,
    EYE_OFF,
    HEART_PULSE,
    PIN,
    FOLDER,
    BOOK_OPEN,
    CLOUD,
    DOWNLOAD,
    UPLOAD,
    MONITOR,
    SERVER_NETWORK,
    BOT,
    MESSAGE_CIRCLE,
    FLASK,
    COMPASS,
    CHECK,
    MORE_HORIZONTAL,
    VOLUME_ON,
    VOLUME_OFF,
    PLAY,
    WARNING,
    CIRCLE_DOT,
    CIRCLE_EMPTY,
    MINUS
}

data class ParsedLabelWithIcons(
    val leadingIcon: KissIcon?,
    val cleanText: String,
    val trailingIcon: KissIcon?
)

/**
 * Automatically strips any legacy emoji/symbol tokens from a label and resolves crisp vector `KissIcon`s.
 */
fun parseKissLabelIcons(raw: String): ParsedLabelWithIcons {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ParsedLabelWithIcons(null, "", null)

    // Exact single-icon matches (for icon-only buttons)
    when (trimmed) {
        "☰", "▤" -> return ParsedLabelWithIcons(KissIcon.MENU, "", null)
        "◂" -> return ParsedLabelWithIcons(KissIcon.SIDEBAR_CLOSE, "", null)
        "+" -> return ParsedLabelWithIcons(KissIcon.PLUS, "", null)
        "—", "-" -> return ParsedLabelWithIcons(KissIcon.MINUS, "", null)
        "×", "✕" -> return ParsedLabelWithIcons(KissIcon.CLOSE, "", null)
        "🗑" -> return ParsedLabelWithIcons(KissIcon.TRASH, "", null)
        "📋" -> return ParsedLabelWithIcons(KissIcon.COPY, "", null)
        "✎", "✏️" -> return ParsedLabelWithIcons(KissIcon.EDIT, "", null)
        "✦" -> return ParsedLabelWithIcons(KissIcon.SPARKLES, "", null)
        "⚙" -> return ParsedLabelWithIcons(KissIcon.SETTINGS, "", null)
        "📎" -> return ParsedLabelWithIcons(KissIcon.PAPERCLIP, "", null)
        "🧠" -> return ParsedLabelWithIcons(KissIcon.BRAIN, "", null)
        "📌" -> return ParsedLabelWithIcons(KissIcon.PIN, "", null)
        "🗺" -> return ParsedLabelWithIcons(KissIcon.MAP_TREE, "", null)
        "⤒" -> return ParsedLabelWithIcons(KissIcon.CHEVRONS_UP, "", null)
        "⤓" -> return ParsedLabelWithIcons(KissIcon.CHEVRONS_DOWN, "", null)
        "↑" -> return ParsedLabelWithIcons(KissIcon.ARROW_UP, "", null)
        "↓" -> return ParsedLabelWithIcons(KissIcon.ARROW_DOWN, "", null)
        "←" -> return ParsedLabelWithIcons(KissIcon.ARROW_LEFT, "", null)
        "→" -> return ParsedLabelWithIcons(KissIcon.ARROW_RIGHT, "", null)
        "💬" -> return ParsedLabelWithIcons(KissIcon.MESSAGE_CIRCLE, "", null)
        "🧪" -> return ParsedLabelWithIcons(KissIcon.FLASK, "", null)
        "🤖" -> return ParsedLabelWithIcons(KissIcon.BOT, "", null)
        "📚", "📖" -> return ParsedLabelWithIcons(KissIcon.BOOK_OPEN, "", null)
        "⚡" -> return ParsedLabelWithIcons(KissIcon.ZAP, "", null)
        "🎭", "🧭" -> return ParsedLabelWithIcons(KissIcon.COMPASS, "", null)
        "🖥" -> return ParsedLabelWithIcons(KissIcon.MONITOR, "", null)
        "🖧" -> return ParsedLabelWithIcons(KissIcon.SERVER_NETWORK, "", null)
        "♡" -> return ParsedLabelWithIcons(KissIcon.HEART_PULSE, "", null)
        "📂" -> return ParsedLabelWithIcons(KissIcon.FOLDER, "", null)
        "☁" -> return ParsedLabelWithIcons(KissIcon.CLOUD, "", null)
        "○" -> return ParsedLabelWithIcons(KissIcon.CIRCLE_EMPTY, "", null)
        "⋯" -> return ParsedLabelWithIcons(KissIcon.MORE_HORIZONTAL, "", null)
    }

    var working = trimmed
    var leading: KissIcon? = null
    var trailing: KissIcon? = null

    val prefixMappings = listOf(
        "☰ " to KissIcon.MENU,
        "◂ " to KissIcon.SIDEBAR_CLOSE,
        "⚙ " to KissIcon.SETTINGS,
        "🔍 " to KissIcon.SEARCH,
        "+ " to KissIcon.PLUS,
        "✕ " to KissIcon.CLOSE,
        "🗑 " to KissIcon.TRASH,
        "📋 MD" to KissIcon.MARKDOWN,
        "📋 " to KissIcon.COPY,
        "✎ " to KissIcon.EDIT,
        "✏️ " to KissIcon.EDIT,
        "✍ " to KissIcon.EDIT,
        "✦ " to KissIcon.SPARKLES,
        "↻ " to KissIcon.REFRESH,
        "🔄 " to KissIcon.REFRESH,
        "→ " to KissIcon.ARROW_RIGHT,
        "← " to KissIcon.ARROW_LEFT,
        "▾ " to KissIcon.CHEVRON_DOWN,
        "▸ " to KissIcon.CHEVRON_RIGHT,
        "▲ " to KissIcon.CHEVRON_UP,
        "🗺 " to KissIcon.MAP_TREE,
        "⑂ " to KissIcon.GIT_BRANCH,
        "🧠 " to KissIcon.BRAIN,
        "📎 " to KissIcon.PAPERCLIP,
        "🌐 " to KissIcon.GLOBE,
        "🖼 " to KissIcon.IMAGE,
        "🎬 " to KissIcon.VIDEO,
        "💻 " to KissIcon.CODE,
        "🔗 " to KissIcon.LINK,
        "⛓ " to KissIcon.LINK,
        "⚡ " to KissIcon.ZAP,
        "⏳ " to KissIcon.CLOCK,
        "■ " to KissIcon.STOP,
        "👁 " to KissIcon.EYE,
        "🙈 " to KissIcon.EYE_OFF,
        "♡ " to KissIcon.HEART_PULSE,
        "📌 " to KissIcon.PIN,
        "📂 " to KissIcon.FOLDER,
        "📖 " to KissIcon.BOOK_OPEN,
        "▤ " to KissIcon.MENU,
        "☁ " to KissIcon.CLOUD,
        "📥 " to KissIcon.DOWNLOAD,
        "📤 " to KissIcon.UPLOAD,
        "⬇ " to KissIcon.DOWNLOAD,
        "🖥 " to KissIcon.MONITOR,
        "🖧 " to KissIcon.SERVER_NETWORK,
        "🤖 " to KissIcon.BOT,
        "💬 " to KissIcon.MESSAGE_CIRCLE,
        "🧪 " to KissIcon.FLASK,
        "🧭 " to KissIcon.COMPASS,
        "✓ " to KissIcon.CHECK,
        "⋯ " to KissIcon.MORE_HORIZONTAL,
        "🔊 " to KissIcon.VOLUME_ON,
        "🔇 " to KissIcon.VOLUME_OFF,
        "▶ " to KissIcon.PLAY,
        "⚠ " to KissIcon.WARNING
    )

    for ((prefix, icon) in prefixMappings) {
        if (working.startsWith(prefix)) {
            leading = icon
            working = if (prefix == "📋 MD") "MD" else working.removePrefix(prefix).trimStart()
            break
        }
    }

    val suffixMappings = listOf(
        " ↵" to KissIcon.SEND_ENTER,
        " →" to KissIcon.ARROW_RIGHT,
        " ▲" to KissIcon.CHEVRON_UP,
        " ▼" to KissIcon.CHEVRON_DOWN,
        " ▾" to KissIcon.CHEVRON_DOWN,
        " •" to KissIcon.CIRCLE_DOT
    )

    for ((suffix, icon) in suffixMappings) {
        if (working.endsWith(suffix)) {
            trailing = icon
            working = working.removeSuffix(suffix).trimEnd()
            break
        }
    }

    return ParsedLabelWithIcons(leading, working, trailing)
}

@Composable
fun KissVectorIcon(
    icon: KissIcon,
    tint: Color,
    size: Dp = 14.dp,
    strokeWidthDp: Dp = 1.75.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val stroke = Stroke(
            width = strokeWidthDp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        drawKissIconPath(icon, tint, s, stroke)
    }
}

private fun DrawScope.drawKissIconPath(
    icon: KissIcon,
    color: Color,
    s: Float,
    stroke: Stroke
) {
    fun pt(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(color, pt(x1, y1), pt(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
    fun path(builder: Path.() -> Unit) {
        val p = Path().apply(builder)
        drawPath(p, color, style = stroke)
    }

    when (icon) {
        KissIcon.MENU -> {
            line(4f, 6f, 20f, 6f)
            line(4f, 12f, 20f, 12f)
            line(4f, 18f, 20f, 18f)
        }
        KissIcon.SIDEBAR_CLOSE -> {
            drawRoundRect(color, pt(3f, 3f), Size(18f * s, 18f * s), CornerRadius(2.5f * s), stroke)
            line(9f, 3f, 9f, 21f)
            path {
                moveTo(15f * s, 9f * s)
                lineTo(12f * s, 12f * s)
                lineTo(15f * s, 15f * s)
            }
        }
        KissIcon.SETTINGS -> {
            drawCircle(color, radius = 3.2f * s, center = pt(12f, 12f), style = stroke)
            line(12f, 2.5f, 12f, 5.2f)
            line(12f, 18.8f, 12f, 21.5f)
            line(2.5f, 12f, 5.2f, 12f)
            line(18.8f, 12f, 21.5f, 12f)
            line(5.3f, 5.3f, 7.2f, 7.2f)
            line(16.8f, 16.8f, 18.7f, 18.7f)
            line(18.7f, 5.3f, 16.8f, 7.2f)
            line(7.2f, 16.8f, 5.3f, 18.7f)
        }
        KissIcon.SEARCH -> {
            drawCircle(color, radius = 7f * s, center = pt(11f, 11f), style = stroke)
            line(16.2f, 16.2f, 21f, 21f)
        }
        KissIcon.PLUS -> {
            line(12f, 5f, 12f, 19f)
            line(5f, 12f, 19f, 12f)
        }
        KissIcon.MINUS -> {
            line(5f, 12f, 19f, 12f)
        }
        KissIcon.CLOSE -> {
            line(6f, 6f, 18f, 18f)
            line(18f, 6f, 6f, 18f)
        }
        KissIcon.TRASH -> {
            line(3f, 6f, 21f, 6f)
            line(8f, 6f, 9f, 3.5f)
            line(9f, 3.5f, 15f, 3.5f)
            line(15f, 3.5f, 16f, 6f)
            drawRoundRect(color, pt(5.5f, 6f), Size(13f * s, 14.5f * s), CornerRadius(2f * s), stroke)
            line(10f, 10.5f, 10f, 16.5f)
            line(14f, 10.5f, 14f, 16.5f)
        }
        KissIcon.COPY -> {
            drawRoundRect(color, pt(9f, 9f), Size(12f * s, 12f * s), CornerRadius(2f * s), stroke)
            path {
                moveTo(5f * s, 15f * s)
                lineTo(4f * s, 15f * s)
                lineTo(4f * s, 4f * s)
                lineTo(15f * s, 4f * s)
                lineTo(15f * s, 5f * s)
            }
        }
        KissIcon.MARKDOWN -> {
            drawRoundRect(color, pt(2.5f, 5f), Size(19f * s, 14f * s), CornerRadius(2f * s), stroke)
            path {
                moveTo(6f * s, 15f * s)
                lineTo(6f * s, 9f * s)
                lineTo(9f * s, 12.5f * s)
                lineTo(12f * s, 9f * s)
                lineTo(12f * s, 15f * s)
            }
            line(16.5f, 9f, 16.5f, 15f)
            path {
                moveTo(14.5f * s, 13f * s)
                lineTo(16.5f * s, 15f * s)
                lineTo(18.5f * s, 13f * s)
            }
        }
        KissIcon.EDIT -> {
            path {
                moveTo(16.5f * s, 3.5f * s)
                lineTo(20.5f * s, 7.5f * s)
                lineTo(8f * s, 20f * s)
                lineTo(3.5f * s, 20.5f * s)
                lineTo(4f * s, 16f * s)
                close()
            }
        }
        KissIcon.SPARKLES -> {
            path {
                moveTo(12f * s, 2.5f * s)
                lineTo(14.3f * s, 9.7f * s)
                lineTo(21.5f * s, 12f * s)
                lineTo(14.3f * s, 14.3f * s)
                lineTo(12f * s, 21.5f * s)
                lineTo(9.7f * s, 14.3f * s)
                lineTo(2.5f * s, 12f * s)
                lineTo(9.7f * s, 9.7f * s)
                close()
            }
        }
        KissIcon.REFRESH -> {
            drawArc(
                color = color,
                startAngle = -35f,
                sweepAngle = 290f,
                useCenter = false,
                topLeft = pt(4f, 4f),
                size = Size(16f * s, 16f * s),
                style = stroke
            )
            path {
                moveTo(16f * s, 3.5f * s)
                lineTo(20f * s, 7.5f * s)
                lineTo(15.5f * s, 8.2f * s)
            }
        }
        KissIcon.ARROW_RIGHT -> {
            line(4f, 12f, 20f, 12f)
            path {
                moveTo(14f * s, 6f * s)
                lineTo(20f * s, 12f * s)
                lineTo(14f * s, 18f * s)
            }
        }
        KissIcon.ARROW_LEFT -> {
            line(20f, 12f, 4f, 12f)
            path {
                moveTo(10f * s, 6f * s)
                lineTo(4f * s, 12f * s)
                lineTo(10f * s, 18f * s)
            }
        }
        KissIcon.ARROW_UP -> {
            line(12f, 19f, 12f, 5f)
            path {
                moveTo(6f * s, 11f * s)
                lineTo(12f * s, 5f * s)
                lineTo(18f * s, 11f * s)
            }
        }
        KissIcon.ARROW_DOWN -> {
            line(12f, 5f, 12f, 19f)
            path {
                moveTo(6f * s, 13f * s)
                lineTo(12f * s, 19f * s)
                lineTo(18f * s, 13f * s)
            }
        }
        KissIcon.CHEVRONS_UP -> {
            path {
                moveTo(6f * s, 12f * s)
                lineTo(12f * s, 6f * s)
                lineTo(18f * s, 12f * s)
            }
            path {
                moveTo(6f * s, 18f * s)
                lineTo(12f * s, 12f * s)
                lineTo(18f * s, 18f * s)
            }
        }
        KissIcon.CHEVRONS_DOWN -> {
            path {
                moveTo(6f * s, 6f * s)
                lineTo(12f * s, 12f * s)
                lineTo(18f * s, 6f * s)
            }
            path {
                moveTo(6f * s, 12f * s)
                lineTo(12f * s, 18f * s)
                lineTo(18f * s, 12f * s)
            }
        }
        KissIcon.CHEVRON_DOWN -> {
            path {
                moveTo(6f * s, 9f * s)
                lineTo(12f * s, 15f * s)
                lineTo(18f * s, 9f * s)
            }
        }
        KissIcon.CHEVRON_UP -> {
            path {
                moveTo(6f * s, 15f * s)
                lineTo(12f * s, 9f * s)
                lineTo(18f * s, 15f * s)
            }
        }
        KissIcon.CHEVRON_RIGHT -> {
            path {
                moveTo(9f * s, 6f * s)
                lineTo(15f * s, 12f * s)
                lineTo(9f * s, 18f * s)
            }
        }
        KissIcon.MAP_TREE -> {
            drawCircle(color, radius = 2.3f * s, center = pt(6f, 6f), style = stroke)
            drawCircle(color, radius = 2.3f * s, center = pt(18f, 10f), style = stroke)
            drawCircle(color, radius = 2.3f * s, center = pt(18f, 18f), style = stroke)
            line(6f, 8.3f, 6f, 18f)
            line(6f, 10f, 15.7f, 10f)
            line(6f, 18f, 15.7f, 18f)
        }
        KissIcon.GIT_BRANCH -> {
            drawCircle(color, radius = 2.4f * s, center = pt(6f, 6f), style = stroke)
            drawCircle(color, radius = 2.4f * s, center = pt(18f, 6f), style = stroke)
            drawCircle(color, radius = 2.4f * s, center = pt(6f, 18f), style = stroke)
            line(6f, 8.4f, 6f, 15.6f)
            path {
                moveTo(18f * s, 8.4f * s)
                lineTo(18f * s, 12f * s)
                lineTo(6f * s, 12f * s)
            }
        }
        KissIcon.BRAIN -> {
            drawRoundRect(color, pt(4f, 4.5f), Size(7.2f * s, 15f * s), CornerRadius(3.5f * s), stroke)
            drawRoundRect(color, pt(12.8f, 4.5f), Size(7.2f * s, 15f * s), CornerRadius(3.5f * s), stroke)
            line(7.5f, 9f, 10.5f, 9f)
            line(13.5f, 9f, 16.5f, 9f)
            line(7.5f, 14f, 10.5f, 14f)
            line(13.5f, 14f, 16.5f, 14f)
        }
        KissIcon.PAPERCLIP -> {
            path {
                moveTo(21.4f * s, 11.1f * s)
                lineTo(12.2f * s, 20.3f * s)
                lineTo(6.5f * s, 20.3f * s)
                lineTo(3.7f * s, 17.5f * s)
                lineTo(3.7f * s, 11.8f * s)
                lineTo(12.9f * s, 2.6f * s)
                lineTo(16.7f * s, 2.6f * s)
                lineTo(18.6f * s, 4.5f * s)
                lineTo(18.6f * s, 8.3f * s)
                lineTo(9.4f * s, 17.5f * s)
                lineTo(7.5f * s, 15.6f * s)
                lineTo(15.8f * s, 7.3f * s)
            }
        }
        KissIcon.GLOBE -> {
            drawCircle(color, radius = 9f * s, center = pt(12f, 12f), style = stroke)
            line(3f, 12f, 21f, 12f)
            drawOval(color, pt(8f, 3f), Size(8f * s, 18f * s), style = stroke)
        }
        KissIcon.IMAGE -> {
            drawRoundRect(color, pt(3f, 3f), Size(18f * s, 18f * s), CornerRadius(2.5f * s), stroke)
            drawCircle(color, radius = 1.8f * s, center = pt(8.5f, 8.5f), style = stroke)
            path {
                moveTo(21f * s, 15f * s)
                lineTo(16f * s, 10f * s)
                lineTo(5f * s, 21f * s)
            }
        }
        KissIcon.VIDEO -> {
            drawRoundRect(color, pt(2.5f, 6f), Size(13f * s, 12f * s), CornerRadius(2f * s), stroke)
            path {
                moveTo(15.5f * s, 10f * s)
                lineTo(21.5f * s, 6.5f * s)
                lineTo(21.5f * s, 17.5f * s)
                lineTo(15.5f * s, 14f * s)
                close()
            }
        }
        KissIcon.CODE -> {
            path {
                moveTo(8f * s, 7f * s)
                lineTo(3f * s, 12f * s)
                lineTo(8f * s, 17f * s)
            }
            path {
                moveTo(16f * s, 7f * s)
                lineTo(21f * s, 12f * s)
                lineTo(16f * s, 17f * s)
            }
            line(13.5f, 5f, 10.5f, 19f)
        }
        KissIcon.LINK -> {
            drawRoundRect(color, pt(3f, 8.5f), Size(8.5f * s, 7f * s), CornerRadius(3.5f * s), stroke)
            drawRoundRect(color, pt(12.5f, 8.5f), Size(8.5f * s, 7f * s), CornerRadius(3.5f * s), stroke)
            line(8.5f, 12f, 15.5f, 12f)
        }
        KissIcon.ZAP -> {
            path {
                moveTo(13f * s, 2f * s)
                lineTo(4f * s, 14f * s)
                lineTo(12f * s, 14f * s)
                lineTo(11f * s, 22f * s)
                lineTo(20f * s, 10f * s)
                lineTo(12f * s, 10f * s)
                close()
            }
        }
        KissIcon.CLOCK -> {
            drawCircle(color, radius = 9f * s, center = pt(12f, 12f), style = stroke)
            path {
                moveTo(12f * s, 7f * s)
                lineTo(12f * s, 12f * s)
                lineTo(15.5f * s, 14f * s)
            }
        }
        KissIcon.STOP -> {
            drawRoundRect(color, pt(5.5f, 5.5f), Size(13f * s, 13f * s), CornerRadius(2f * s))
        }
        KissIcon.SEND_ENTER -> {
            path {
                moveTo(19f * s, 5f * s)
                lineTo(19f * s, 14f * s)
                lineTo(5f * s, 14f * s)
            }
            path {
                moveTo(9f * s, 10f * s)
                lineTo(5f * s, 14f * s)
                lineTo(9f * s, 18f * s)
            }
        }
        KissIcon.EYE -> {
            drawOval(color, pt(2.5f, 6.5f), Size(19f * s, 11f * s), style = stroke)
            drawCircle(color, radius = 2.8f * s, center = pt(12f, 12f), style = stroke)
        }
        KissIcon.EYE_OFF -> {
            drawOval(color, pt(2.5f, 6.5f), Size(19f * s, 11f * s), style = stroke)
            line(4f, 4f, 20f, 20f)
        }
        KissIcon.HEART_PULSE -> {
            path {
                moveTo(12f * s, 20.5f * s)
                lineTo(4.5f * s, 13f * s)
                lineTo(4.5f * s, 7f * s)
                lineTo(8.5f * s, 4.5f * s)
                lineTo(12f * s, 8f * s)
                lineTo(15.5f * s, 4.5f * s)
                lineTo(19.5f * s, 7f * s)
                lineTo(19.5f * s, 13f * s)
                close()
            }
        }
        KissIcon.PIN -> {
            path {
                moveTo(8f * s, 4f * s)
                lineTo(16f * s, 4f * s)
                lineTo(15f * s, 11f * s)
                lineTo(18f * s, 14f * s)
                lineTo(6f * s, 14f * s)
                lineTo(9f * s, 11f * s)
                close()
            }
            line(12f, 14f, 12f, 21f)
        }
        KissIcon.FOLDER -> {
            path {
                moveTo(3f * s, 6f * s)
                lineTo(9.5f * s, 6f * s)
                lineTo(11.5f * s, 8.5f * s)
                lineTo(21f * s, 8.5f * s)
                lineTo(21f * s, 19f * s)
                lineTo(3f * s, 19f * s)
                close()
            }
        }
        KissIcon.BOOK_OPEN -> {
            path {
                moveTo(12f * s, 6f * s)
                lineTo(12f * s, 19.5f * s)
                lineTo(3f * s, 18f * s)
                lineTo(3f * s, 4.5f * s)
                close()
            }
            path {
                moveTo(12f * s, 6f * s)
                lineTo(12f * s, 19.5f * s)
                lineTo(21f * s, 18f * s)
                lineTo(21f * s, 4.5f * s)
                close()
            }
        }
        KissIcon.CLOUD -> {
            drawRoundRect(color, pt(4f, 9f), Size(16f * s, 10f * s), CornerRadius(5f * s), stroke)
            drawCircle(color, radius = 4.5f * s, center = pt(10.5f, 9.5f), style = stroke)
        }
        KissIcon.DOWNLOAD -> {
            line(12f, 4f, 12f, 15.5f)
            path {
                moveTo(7.5f * s, 11.5f * s)
                lineTo(12f * s, 16f * s)
                lineTo(16.5f * s, 11.5f * s)
            }
            line(4f, 20f, 20f, 20f)
        }
        KissIcon.UPLOAD -> {
            line(12f, 16f, 12f, 4.5f)
            path {
                moveTo(7.5f * s, 9f * s)
                lineTo(12f * s, 4.5f * s)
                lineTo(16.5f * s, 9f * s)
            }
            line(4f, 20f, 20f, 20f)
        }
        KissIcon.MONITOR -> {
            drawRoundRect(color, pt(2.5f, 3.5f), Size(19f * s, 13f * s), CornerRadius(2f * s), stroke)
            line(8f, 20.5f, 16f, 20.5f)
            line(12f, 16.5f, 12f, 20.5f)
        }
        KissIcon.SERVER_NETWORK -> {
            drawCircle(color, radius = 3f * s, center = pt(12f, 5.5f), style = stroke)
            drawCircle(color, radius = 3f * s, center = pt(6f, 17.5f), style = stroke)
            drawCircle(color, radius = 3f * s, center = pt(18f, 17.5f), style = stroke)
            line(10.5f, 8.2f, 7.2f, 14.8f)
            line(13.5f, 8.2f, 16.8f, 14.8f)
            line(9f, 17.5f, 15f, 17.5f)
        }
        KissIcon.BOT -> {
            drawRoundRect(color, pt(4f, 7f), Size(16f * s, 13f * s), CornerRadius(3f * s), stroke)
            line(12f, 3f, 12f, 7f)
            drawCircle(color, radius = 1.4f * s, center = pt(9f, 13f))
            drawCircle(color, radius = 1.4f * s, center = pt(15f, 13f))
            line(9f, 16.5f, 15f, 16.5f)
        }
        KissIcon.MESSAGE_CIRCLE -> {
            drawRoundRect(color, pt(3f, 4f), Size(18f * s, 13.5f * s), CornerRadius(4f * s), stroke)
            path {
                moveTo(8f * s, 17.5f * s)
                lineTo(5f * s, 21f * s)
                lineTo(12f * s, 17.5f * s)
            }
        }
        KissIcon.FLASK -> {
            line(9f, 3f, 15f, 3f)
            path {
                moveTo(10f * s, 3f * s)
                lineTo(10f * s, 9f * s)
                lineTo(4.5f * s, 19f * s)
                lineTo(19.5f * s, 19f * s)
                lineTo(14f * s, 9f * s)
                lineTo(14f * s, 3f * s)
            }
            line(7f, 14.5f, 17f, 14.5f)
        }
        KissIcon.COMPASS -> {
            drawCircle(color, radius = 9f * s, center = pt(12f, 12f), style = stroke)
            path {
                moveTo(15.5f * s, 8.5f * s)
                lineTo(13.5f * s, 13.5f * s)
                lineTo(8.5f * s, 15.5f * s)
                lineTo(10.5f * s, 10.5f * s)
                close()
            }
        }
        KissIcon.CHECK -> {
            path {
                moveTo(4.5f * s, 12.5f * s)
                lineTo(9.5f * s, 17.5f * s)
                lineTo(19.5f * s, 6.5f * s)
            }
        }
        KissIcon.MORE_HORIZONTAL -> {
            drawCircle(color, radius = 1.5f * s, center = pt(6f, 12f))
            drawCircle(color, radius = 1.5f * s, center = pt(12f, 12f))
            drawCircle(color, radius = 1.5f * s, center = pt(18f, 12f))
        }
        KissIcon.VOLUME_ON -> {
            path {
                moveTo(11f * s, 5f * s)
                lineTo(6f * s, 9f * s)
                lineTo(2.5f * s, 9f * s)
                lineTo(2.5f * s, 15f * s)
                lineTo(6f * s, 15f * s)
                lineTo(11f * s, 19f * s)
                close()
            }
            line(15.5f, 9f, 15.5f, 15f)
            line(19f, 7f, 19f, 17f)
        }
        KissIcon.VOLUME_OFF -> {
            path {
                moveTo(11f * s, 5f * s)
                lineTo(6f * s, 9f * s)
                lineTo(2.5f * s, 9f * s)
                lineTo(2.5f * s, 15f * s)
                lineTo(6f * s, 15f * s)
                lineTo(11f * s, 19f * s)
                close()
            }
            line(16f, 9.5f, 21f, 14.5f)
            line(21f, 9.5f, 16f, 14.5f)
        }
        KissIcon.PLAY -> {
            path {
                moveTo(6.5f * s, 4.5f * s)
                lineTo(19.5f * s, 12f * s)
                lineTo(6.5f * s, 19.5f * s)
                close()
            }
        }
        KissIcon.WARNING -> {
            path {
                moveTo(12f * s, 3.5f * s)
                lineTo(21.5f * s, 19.5f * s)
                lineTo(2.5f * s, 19.5f * s)
                close()
            }
            line(12f, 9.5f, 12f, 14f)
            drawCircle(color, radius = 1.1f * s, center = pt(12f, 17f))
        }
        KissIcon.CIRCLE_DOT -> {
            drawCircle(color, radius = 4.2f * s, center = pt(12f, 12f))
        }
        KissIcon.CIRCLE_EMPTY -> {
            drawCircle(color, radius = 7f * s, center = pt(12f, 12f), style = stroke)
        }
    }
}
