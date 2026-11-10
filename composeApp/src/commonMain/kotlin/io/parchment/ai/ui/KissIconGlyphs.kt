package io.parchment.ai.ui

import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The interface icon family, drawn on one 24-unit grid: live area 3 to 21, circles of radius 9,
 * corners of radius 2.5, arrowheads 6 units deep. Shapes are outlines unless a dot needs a fill.
 */
internal class KissGlyph(val stroke: Path, val fill: Path?)

private fun n(value: Double): String = ((value * 100).roundToInt() / 100.0).toString()

private fun circle(cx: Double, cy: Double, r: Double): String =
    "M${n(cx - r)} ${n(cy)}a${n(r)} ${n(r)} 0 1 0 ${n(r * 2)} 0a${n(r)} ${n(r)} 0 1 0 ${n(-r * 2)} 0Z"

private fun box(x: Double, y: Double, w: Double, h: Double, r: Double = 2.5): String =
    "M${n(x + r)} ${n(y)}h${n(w - r * 2)}a${n(r)} ${n(r)} 0 0 1 ${n(r)} ${n(r)}v${n(h - r * 2)}" +
        "a${n(r)} ${n(r)} 0 0 1 ${n(-r)} ${n(r)}h${n(-(w - r * 2))}a${n(r)} ${n(r)} 0 0 1 ${n(-r)} ${n(-r)}" +
        "v${n(-(h - r * 2))}a${n(r)} ${n(r)} 0 0 1 ${n(r)} ${n(-r)}Z"

private fun gear(): String = buildString {
    fun point(degrees: Double, radius: Double): String {
        val angle = degrees * PI / 180.0
        return "${n(12 + radius * cos(angle))} ${n(12 + radius * sin(angle))}"
    }
    for (tooth in 0 until 8) {
        val center = tooth * 45.0
        append(if (tooth == 0) "M" else "L").append(point(center - 12, 9.6))
        append("L").append(point(center + 12, 9.6))
        append("L").append(point(center + 20, 7.1))
        append("L").append(point(center + 25, 7.1))
    }
    append("Z").append(circle(12.0, 12.0, 3.0))
}

private const val SPEAKER = "M11 5L6.5 9H3v6h3.5l4.5 4Z"
private const val MAGNIFIER = "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14ZM16 16l5 5"

private fun outline(icon: KissIcon): String = when (icon) {
    KissIcon.MENU -> "M4 6h16M4 12h16M4 18h16"
    KissIcon.SIDEBAR_CLOSE -> box(3.0, 3.0, 18.0, 18.0) + "M9 3v18M16 9l-3 3 3 3"
    KissIcon.SETTINGS -> gear()
    KissIcon.SEARCH -> MAGNIFIER
    KissIcon.PLUS -> "M12 5v14M5 12h14"
    KissIcon.MINUS -> "M5 12h14"
    KissIcon.CLOSE -> "M6 6l12 12M18 6L6 18"
    KissIcon.TRASH -> "M3.5 6h17M8.5 6V4.5A1.5 1.5 0 0 1 10 3h4a1.5 1.5 0 0 1 1.5 1.5V6M18.5 6l-.8 13a2 2 0 0 1-2 2H8.3a2 2 0 0 1-2-2L5.5 6M10 11v5.5M14 11v5.5"
    KissIcon.COPY -> box(9.0, 9.0, 12.0, 12.0) + "M5.5 15A2.5 2.5 0 0 1 3 12.5v-7A2.5 2.5 0 0 1 5.5 3h7A2.5 2.5 0 0 1 15 5.5"
    KissIcon.MARKDOWN -> box(2.5, 5.0, 19.0, 14.0) + "M6.5 15V9l3 3.5 3-3.5v6M17 9v6M15 13l2 2 2-2"
    KissIcon.EDIT -> "M16.5 3.6a2.1 2.1 0 0 1 3 0l.9.9a2.1 2.1 0 0 1 0 3L8 20l-4.500.5L4 16ZM14.500 5.500l4 4"
    KissIcon.SPARKLES -> "M12 3c.6 4.9 3.1 7.4 9 9-5.9 1.6-8.4 4.1-9 9-.6-4.9-3.1-7.4-9-9 5.9-1.6 8.4-4.1 9-9Z"
    KissIcon.REFRESH -> "M20.5 12a8.5 8.5 0 1 1-2.6-6.1L20.5 8M20.5 3.5V8H16"
    KissIcon.REWIND -> "M3.5 12a8.5 8.5 0 1 0 2.6-6.1L3.5 8M3.5 3.5V8H8"
    KissIcon.ARROW_RIGHT -> "M4 12h16M14 6l6 6-6 6"
    KissIcon.ARROW_LEFT -> "M20 12H4M10 6l-6 6 6 6"
    KissIcon.ARROW_UP -> "M12 20V4M6 10l6-6 6 6"
    KissIcon.ARROW_DOWN -> "M12 4v16M6 14l6 6 6-6"
    KissIcon.CHEVRONS_UP -> "M6 12l6-6 6 6M6 18l6-6 6 6"
    KissIcon.CHEVRONS_DOWN -> "M6 6l6 6 6-6M6 12l6 6 6-6"
    KissIcon.CHEVRON_DOWN -> "M6 9l6 6 6-6"
    KissIcon.CHEVRON_UP -> "M6 15l6-6 6 6"
    KissIcon.CHEVRON_RIGHT -> "M9 6l6 6-6 6"
    KissIcon.MAP_TREE -> circle(6.0, 5.5, 2.5) + circle(18.0, 11.0, 2.5) + circle(18.0, 19.0, 2.5) + "M6 8v8a3 3 0 0 0 3 3h6.5M6 8a3 3 0 0 0 3 3h6.5"
    KissIcon.GIT_BRANCH -> circle(6.0, 5.5, 2.5) + circle(6.0, 18.5, 2.5) + circle(18.0, 5.5, 2.5) + "M6 8v8M18 8a6 6 0 0 1-6 6H8.5"
    KissIcon.BRAIN -> "M12 5a3 3 0 0 0-5.700-1.300A3.500 3.500 0 0 0 4 10a3.500 3.500 0 0 0 .8 6 3.500 3.500 0 0 0 6.200 2.500c.6-.4 1-1 1-1.700ZM12 5a3 3 0 0 1 5.700-1.300A3.500 3.500 0 0 1 20 10a3.500 3.500 0 0 1-.8 6 3.500 3.500 0 0 1-6.200 2.500c-.6-.4-1-1-1-1.700Z"
    KissIcon.PAPERCLIP -> "M20.500 11.500l-8.600 8.600a5.500 5.500 0 0 1-7.800-7.800l8.900-8.900a3.700 3.700 0 0 1 5.200 5.200l-8.900 8.900a1.800 1.800 0 0 1-2.600-2.600l8.100-8.100"
    KissIcon.GLOBE -> circle(12.0, 12.0, 9.0) + "M3 12h18M12 3c2.600 2.600 4 5.600 4 9s-1.400 6.400-4 9c-2.600-2.600-4-5.600-4-9s1.400-6.400 4-9Z"
    KissIcon.TRANSLATE -> "M4 5.500h9M8.500 3.500v2M11 5.500c-.8 3.700-3.400 6.700-7 8.500M6 8.500c1.200 2.500 3.200 4.300 6 5.500M13.500 20.500l3.500-9 3.500 9M14.700 17.500h4.600"
    KissIcon.IMAGE -> box(3.0, 3.0, 18.0, 18.0) + circle(8.5, 8.5, 1.75) + "M21 15l-4.500-4.500L6 21"
    KissIcon.VIDEO -> box(2.5, 6.0, 13.0, 12.0) + "M15.500 10.500l6-3.500v10l-6-3.500"
    KissIcon.CODE -> "M8 7l-5 5 5 5M16 7l5 5-5 5M13.500 5l-3 14"
    KissIcon.LINK -> "M10 14a4.500 4.500 0 0 0 6.400 0l3.200-3.200a4.500 4.500 0 0 0-6.400-6.400l-1.200 1.200M14 10a4.500 4.500 0 0 0-6.400 0l-3.200 3.200a4.500 4.500 0 0 0 6.400 6.400l1.200-1.200"
    KissIcon.ZAP -> "M13 2.500L4.500 14H12l-1 7.500L19.500 10H12Z"
    KissIcon.CLOCK -> circle(12.0, 12.0, 9.0) + "M12 7v5l3.500 2"
    KissIcon.SEND_ENTER -> "M19.500 5v6a3 3 0 0 1-3 3H5M9 10l-4 4 4 4"
    KissIcon.EYE -> "M2.500 12S6 5.500 12 5.500 21.500 12 21.500 12 18 18.500 12 18.500 2.500 12 2.500 12Z" + circle(12.0, 12.0, 3.0)
    KissIcon.EYE_OFF -> "M4 4l16 16M9.600 5.900A9 9 0 0 1 12 5.500c6 0 9.500 6.500 9.500 6.500a17 17 0 0 1-2.700 3.500M6.200 7.600C3.700 9.300 2.500 12 2.500 12S6 18.500 12 18.500c1.900 0 3.600-.6 5-1.500M10 10a3 3 0 0 0 4.100 4.100"
    KissIcon.HEART_PULSE -> "M12 20.500S3.500 15.600 3.500 9.400A4.900 4.900 0 0 1 12 6.200a4.900 4.900 0 0 1 8.500 3.200c0 6.200-8.500 11.100-8.500 11.100Z"
    KissIcon.PIN -> "M9 3.500h6M10 3.500v6.700L6.500 14v1.500h11V14L14 10.200V3.500M12 15.500V21"
    KissIcon.FOLDER -> "M3 7.500A2.500 2.500 0 0 1 5.500 5h3.400a2 2 0 0 1 1.600.8l1 1.400a2 2 0 0 0 1.600.8h5.400a2.500 2.500 0 0 1 2.500 2.500v6a2.500 2.500 0 0 1-2.500 2.500h-13A2.500 2.500 0 0 1 3 16.500Z"
    KissIcon.BOOK_OPEN -> "M12 7c-1.800-1.600-4.500-2.500-9-2.500v14c4.500 0 7.200.9 9 2.500 1.800-1.600 4.500-2.500 9-2.500v-14c-4.500 0-7.200.9-9 2.500ZM12 7v14"
    KissIcon.CLOUD -> "M7 19a4.500 4.500 0 0 1-.7-8.900A6 6 0 0 1 17.800 9.500 4.800 4.800 0 0 1 17.500 19Z"
    KissIcon.DOWNLOAD -> "M12 4v12M7 11.500l5 5 5-5M4 20.500h16"
    KissIcon.UPLOAD -> "M12 16.500V4.500M7 9.500l5-5 5 5M4 20.500h16"
    KissIcon.MONITOR -> box(2.5, 3.5, 19.0, 13.5) + "M8 21h8M12 17v4"
    KissIcon.SERVER_NETWORK -> circle(12.0, 5.5, 2.75) + circle(5.5, 18.0, 2.75) + circle(18.5, 18.0, 2.75) + "M10.600 7.900l-3.800 7.700M13.400 7.900l3.800 7.700M8.250 18h7.500"
    KissIcon.BOT -> box(4.0, 8.0, 16.0, 12.0, 3.0) + "M12 3.500V8M8.500 13v2M15.500 13v2M2 13.500v2M22 13.500v2"
    KissIcon.MESSAGE_CIRCLE -> "M6.500 4h11A3.500 3.500 0 0 1 21 7.500V14a3.500 3.500 0 0 1-3.500 3.500H12l-5 3.500v-3.500h-.5A3.500 3.500 0 0 1 3 14V7.500A3.500 3.500 0 0 1 6.500 4Z"
    KissIcon.FLASK -> "M9 3h6M10 3v6.200L4.600 18.100A2 2 0 0 0 6.300 21h11.400a2 2 0 0 0 1.700-2.900L14 9.200V3M7.300 15h9.400"
    KissIcon.COMPASS -> circle(12.0, 12.0, 9.0) + "M15.800 8.200l-2.200 5.400-5.400 2.200 2.200-5.400Z"
    KissIcon.CHECK -> "M4.500 12.500l5 5 10-11"
    KissIcon.VOLUME_ON -> SPEAKER + "M15 9.500a3.500 3.500 0 0 1 0 5M17.800 6.700a7.500 7.500 0 0 1 0 10.600"
    KissIcon.VOLUME_OFF -> SPEAKER + "M16 9.500l5 5M21 9.500l-5 5"
    KissIcon.PLAY -> "M7 4.500L19 12 7 19.500Z"
    KissIcon.WARNING -> "M12 3.800L21.600 20H2.400ZM12 10v4.500"
    KissIcon.CIRCLE_EMPTY -> circle(12.0, 12.0, 7.5)
    KissIcon.TARGET -> circle(12.0, 12.0, 9.0) + circle(12.0, 12.0, 5.0)
    KissIcon.FILE_TEXT -> "M14 3H7.500A2.500 2.500 0 0 0 5 5.500v13A2.500 2.500 0 0 0 7.500 21h9a2.500 2.500 0 0 0 2.500-2.500V8ZM14 3v5h5M9 13h6M9 17h4.500"
    KissIcon.AUDIO -> "M9 18V6l10.500-2v12" + circle(6.5, 18.0, 2.5) + circle(17.0, 16.0, 2.5)
    KissIcon.TABS -> box(3.5, 7.0, 13.5, 13.5) + "M7.500 3.500H18a2.500 2.500 0 0 1 2.500 2.500v10.500"
    KissIcon.MIC -> box(9.0, 3.0, 6.0, 11.0, 3.0) + "M5.500 11a6.500 6.500 0 0 0 13 0M12 17.500V21M8.500 21h7"
    KissIcon.TERMINAL -> box(3.0, 4.0, 18.0, 16.0) + "M7 9l3.500 3L7 15M12.500 15H17"
    KissIcon.SLIDERS -> "M4 7h9M17 7h3M4 17h3M11 17h9" + circle(15.0, 7.0, 2.0) + circle(9.0, 17.0, 2.0)
    KissIcon.SHIELD_CHECK -> "M12 3l7.500 2.800v5.700c0 4.700-3.100 7.900-7.500 9.500-4.400-1.600-7.500-4.800-7.500-9.500V5.800ZM8.800 12l2.300 2.300 4.200-4.600"
    KissIcon.CPU -> box(6.0, 6.0, 12.0, 12.0) + box(9.5, 9.5, 5.0, 5.0, 1.0) + "M9.500 2.500V6M14.500 2.500V6M9.500 18v3.500M14.500 18v3.500M2.500 9.500H6M2.500 14.500H6M18 9.500h3.500M18 14.500h3.500"
    KissIcon.USER -> circle(12.0, 8.0, 4.0) + "M4.500 21a7.500 7.500 0 0 1 15 0"
    KissIcon.LAYOUT_GRID -> box(3.5, 3.5, 7.0, 7.0, 1.5) + box(13.5, 3.5, 7.0, 7.0, 1.5) + box(3.5, 13.5, 7.0, 7.0, 1.5) + box(13.5, 13.5, 7.0, 7.0, 1.5)
    KissIcon.DIFF -> "M12 4v10M7 9h10M7 20h10"
    KissIcon.WRENCH -> "M14.500 6.500a1 1 0 0 0 0 1.400l1.600 1.600a1 1 0 0 0 1.400 0l3.300-3.300a6 6 0 0 1-7.900 7.900l-6.800 6.800a2.100 2.100 0 0 1-3-3l6.800-6.800a6 6 0 0 1 7.900-7.900Z"
    KissIcon.EXTERNAL_LINK -> "M14 4h6v6M20 4l-9.500 9.500M18 14v4a2.500 2.500 0 0 1-2.500 2.500h-9A2.500 2.500 0 0 1 4 18V8.500A2.500 2.500 0 0 1 6.500 6H10"
    KissIcon.AT_SIGN -> circle(12.0, 12.0, 3.75) + "M15.750 8.250v5a2.600 2.600 0 0 0 5.250 0V12a9 9 0 1 0-3.600 7.200"
    KissIcon.THEATER -> "M3.500 4.500h11v7a5.500 5.500 0 0 1-11 0ZM6.500 8.500H8M10 8.500h1.500M7 12.500c1 .9 3 .9 4 0M14.500 8.500h6v7a5 5 0 0 1-9 3"
    KissIcon.TAG -> "M3.500 5.500a2 2 0 0 1 2-2H12l8.500 8.500a2 2 0 0 1 0 2.800l-6.200 6.200a2 2 0 0 1-2.800 0L3.500 12Z"
    KissIcon.PACKAGE_BOX -> "M12 2.800l8.500 4.700v9L12 21.200l-8.500-4.700v-9ZM3.500 7.500L12 12l8.500-4.500M12 12v9.200M7.800 5.200l8.500 4.700"
    KissIcon.ZOOM_IN -> MAGNIFIER + "M11 8v6M8 11h6"
    KissIcon.ZOOM_OUT -> MAGNIFIER + "M8 11h6"
    KissIcon.PALETTE -> "M12 3a9 9 0 1 0 0 18c1.400 0 2.200-1 2.200-2.100 0-.6-.3-1.100-.6-1.500-.3-.4-.6-.9-.6-1.400 0-1.100.9-2 2-2h2.200a3.800 3.800 0 0 0 3.800-3.800c0-4-4-7.200-9-7.200Z"
    KissIcon.CROWN -> "M4 20h16M4.500 16L3 6l5 3.500L12 4l4 5.500L21 6l-1.500 10Z"
    KissIcon.FLAME -> "M12 21.500a7 7 0 0 0 7-7c0-3.200-2-5.800-3.500-7.300-.4 1.800-1.300 3-2.700 3.600.4-3.200-.8-6-3.300-8.300.2 3.100-1 4.900-2.500 6.700C5.700 11 5 12.600 5 14.500a7 7 0 0 0 7 7Z"
    KissIcon.DICE -> box(3.5, 3.5, 17.0, 17.0)
    KissIcon.CALCULATOR -> box(5.0, 2.5, 14.0, 19.0) + "M8.500 6.500h7"
    KissIcon.CORNER_DOWN_RIGHT -> "M5 4v7a3 3 0 0 0 3 3h11M15 10l4 4-4 4"
    KissIcon.STOP, KissIcon.MORE_HORIZONTAL, KissIcon.CIRCLE_DOT -> ""
}

private fun solid(icon: KissIcon): String = when (icon) {
    KissIcon.STOP -> box(6.0, 6.0, 12.0, 12.0, 2.0)
    KissIcon.MORE_HORIZONTAL -> circle(5.5, 12.0, 1.6) + circle(12.0, 12.0, 1.6) + circle(18.5, 12.0, 1.6)
    KissIcon.CIRCLE_DOT -> circle(12.0, 12.0, 4.5)
    KissIcon.WARNING -> circle(12.0, 17.2, 1.1)
    KissIcon.TARGET -> circle(12.0, 12.0, 1.75)
    KissIcon.TAG -> circle(8.0, 8.0, 1.3)
    KissIcon.PALETTE -> circle(7.5, 11.5, 1.2) + circle(9.5, 7.5, 1.2) + circle(14.5, 7.5, 1.2)
    KissIcon.DICE -> listOf(8.5 to 8.5, 15.5 to 8.5, 12.0 to 12.0, 8.5 to 15.5, 15.5 to 15.5).joinToString("") { (x, y) -> circle(x, y, 1.3) }
    KissIcon.CALCULATOR -> listOf(11.5, 15.0, 18.5).joinToString("") { y -> listOf(8.8, 12.0, 15.2).joinToString("") { x -> circle(x, y, 1.0) } }
    else -> ""
}

private val glyphCache = arrayOfNulls<KissGlyph>(KissIcon.entries.size)

/** Parsed once per icon and only drawn on the UI thread. */
internal fun kissGlyph(icon: KissIcon): KissGlyph = glyphCache[icon.ordinal] ?: run {
    val fill = solid(icon)
    KissGlyph(parseSvgPathNormalized(outline(icon)), if (fill.isEmpty()) null else parseSvgPathNormalized(fill))
        .also { glyphCache[icon.ordinal] = it }
}
