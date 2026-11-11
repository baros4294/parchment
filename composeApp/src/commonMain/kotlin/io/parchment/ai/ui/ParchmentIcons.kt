package io.parchment.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.getDiscoveredVendorForModel

/**
 * Parchment's own vector icons on a 24×24 grid, drawn with Compose Foundation and tinted by the theme,
 * so no OS emoji is needed.
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
    MINUS,
    TARGET,
    REWIND,
    FILE_TEXT,
    AUDIO,
    TABS,
    MIC,
    TERMINAL,
    SLIDERS,
    SHIELD_CHECK,
    CPU,
    USER,
    LAYOUT_GRID,
    TRANSLATE,
    DIFF,
    WRENCH,
    EXTERNAL_LINK,
    AT_SIGN,
    THEATER,
    TAG,
    PACKAGE_BOX,
    ZOOM_IN,
    ZOOM_OUT,
    PALETTE,
    CROWN,
    FLAME,
    DICE,
    CALCULATOR,
    CORNER_DOWN_RIGHT
}

fun resolveToolKissIcon(toolName: String): KissIcon = when (toolName.lowercase()) {
    "fs_read" -> KissIcon.FILE_TEXT
    "fs_edit", "fs_write", "fs_multi_edit" -> KissIcon.DIFF
    "fs_list", "fs_glob", "fs_move" -> KissIcon.FOLDER
    "fs_delete" -> KissIcon.TRASH
    "fs_search" -> KissIcon.SEARCH
    "web_fetch", "web_search" -> KissIcon.GLOBE
    "calc_eval" -> KissIcon.CALCULATOR
    "shell_exec" -> KissIcon.TERMINAL
    "todo_write" -> KissIcon.TARGET
    "goal_complete" -> KissIcon.CHECK
    "ask_user" -> KissIcon.USER
    "skill_load" -> KissIcon.BOOK_OPEN
    "agent_delegate" -> KissIcon.SPARKLES
    "computer_use" -> KissIcon.WRENCH
    "agent_memory" -> KissIcon.BOOK_OPEN
    "dice_roll" -> KissIcon.DICE
    else -> KissIcon.WRENCH
}
data class ParsedLabelWithIcons(
    val leadingIcon: KissIcon?,
    val cleanText: String,
    val trailingIcon: KissIcon?
)

/** Reader fallback for an icon-only label; callers should pass a more specific action name. */
fun ParsedLabelWithIcons.iconOnlyName(): String? =
    if (cleanText.isNotEmpty()) null
    else (leadingIcon ?: trailingIcon)?.name?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.titlecase() }

private val PREFIX_ICON_MAPPINGS = listOf(
    "☰ " to KissIcon.MENU,
    "◂ " to KissIcon.SIDEBAR_CLOSE,
    "⚙ " to KissIcon.SETTINGS,
    "⚙️ " to KissIcon.SETTINGS,
    "🔧 " to KissIcon.WRENCH,
    "🛠 " to KissIcon.WRENCH,
    "🛠️ " to KissIcon.WRENCH,
    "🐚 " to KissIcon.TERMINAL,
    "🔍 " to KissIcon.SEARCH,
    "+ " to KissIcon.PLUS,
    "× " to KissIcon.CLOSE,
    "✕ " to KissIcon.CLOSE,
    "— " to KissIcon.MINUS,
    "− " to KissIcon.MINUS,
    "- " to KissIcon.MINUS,
    "🗑 " to KissIcon.TRASH,
    "🗑️ " to KissIcon.TRASH,
    "📋 Markdown" to KissIcon.MARKDOWN,
    "📋 " to KissIcon.COPY,
    "✎ " to KissIcon.EDIT,
    "✏️ " to KissIcon.EDIT,
    "✍ " to KissIcon.EDIT,
    "✍️ " to KissIcon.EDIT,
    "✦ " to KissIcon.SPARKLES,
    "✨ " to KissIcon.SPARKLES,
    "↻ " to KissIcon.REFRESH,
    "🔄 " to KissIcon.REFRESH,
    "→ " to KissIcon.ARROW_RIGHT,
    "← " to KissIcon.ARROW_LEFT,
    "↗ " to KissIcon.EXTERNAL_LINK,
    "↳ " to KissIcon.CORNER_DOWN_RIGHT,
    "▾ " to KissIcon.CHEVRON_DOWN,
    "▼ " to KissIcon.CHEVRON_DOWN,
    "▸ " to KissIcon.CHEVRON_RIGHT,
    "▴ " to KissIcon.CHEVRON_UP,
    "▲ " to KissIcon.CHEVRON_UP,
    "🗺 " to KissIcon.MAP_TREE,
    "🗺️ " to KissIcon.MAP_TREE,
    "⑂ " to KissIcon.GIT_BRANCH,
    "🧠 " to KissIcon.BRAIN,
    "📎 " to KissIcon.PAPERCLIP,
    "🌐 " to KissIcon.GLOBE,
    "🖼 " to KissIcon.IMAGE,
    "🖼️ " to KissIcon.IMAGE,
    "🎬 " to KissIcon.VIDEO,
    "💻 " to KissIcon.CODE,
    "🔗 " to KissIcon.LINK,
    "⛓ " to KissIcon.LINK,
    "⚡ " to KissIcon.ZAP,
    "⏳ " to KissIcon.CLOCK,
    "■ " to KissIcon.STOP,
    "👁 " to KissIcon.EYE,
    "👁️ " to KissIcon.EYE,
    "🙈 " to KissIcon.EYE_OFF,
    "♡ " to KissIcon.HEART_PULSE,
    "📌 " to KissIcon.PIN,
    "📂 " to KissIcon.FOLDER,
    "📁 " to KissIcon.FOLDER,
    "📚 " to KissIcon.BOOK_OPEN,
    "📖 " to KissIcon.BOOK_OPEN,
    "☁ " to KissIcon.CLOUD,
    "☁️ " to KissIcon.CLOUD,
    "⬇ " to KissIcon.DOWNLOAD,
    "⬇️ " to KissIcon.DOWNLOAD,
    "📥 " to KissIcon.DOWNLOAD,
    "📤 " to KissIcon.UPLOAD,
    "🖥 " to KissIcon.MONITOR,
    "🖥️ " to KissIcon.MONITOR,
    "🖧 " to KissIcon.SERVER_NETWORK,
    "🤖 " to KissIcon.BOT,
    "💬 " to KissIcon.MESSAGE_CIRCLE,
    "🧪 " to KissIcon.FLASK,
    "🎭 " to KissIcon.THEATER,
    "🧭 " to KissIcon.COMPASS,
    "🏷 " to KissIcon.TAG,
    "🏷️ " to KissIcon.TAG,
    "📦 " to KissIcon.PACKAGE_BOX,
    "🎨 " to KissIcon.PALETTE,
    "👑 " to KissIcon.CROWN,
    "🔥 " to KissIcon.FLAME,
    "🛡 " to KissIcon.SHIELD_CHECK,
    "🛡️ " to KissIcon.SHIELD_CHECK,
    "🔒 " to KissIcon.SHIELD_CHECK,
    "🔑 " to KissIcon.SHIELD_CHECK,
    "🎲 " to KissIcon.DICE,
    "🧮 " to KissIcon.CALCULATOR,
    "✓ " to KissIcon.CHECK,
    "✔ " to KissIcon.CHECK,
    "⋯ " to KissIcon.MORE_HORIZONTAL,
    "🔊 " to KissIcon.VOLUME_ON,
    "🔇 " to KissIcon.VOLUME_OFF,
    "▶ " to KissIcon.PLAY,
    "⚠ " to KissIcon.WARNING,
    "⚠️ " to KissIcon.WARNING,
    "● " to KissIcon.CIRCLE_DOT,
    "○ " to KissIcon.CIRCLE_EMPTY,
    "🎯 " to KissIcon.TARGET,
    "↺ " to KissIcon.REWIND,
    "📄 " to KissIcon.FILE_TEXT,
    "📝 " to KissIcon.FILE_TEXT,
    "🎵 " to KissIcon.AUDIO,
    "🗂 " to KissIcon.TABS,
    "🗂️ " to KissIcon.TABS,
    "🎙 " to KissIcon.MIC,
    "🎙️ " to KissIcon.MIC,
    "⊞ " to KissIcon.LAYOUT_GRID,
    "👤 " to KissIcon.USER,
    "@ " to KissIcon.AT_SIGN
)

private val SUFFIX_ICON_MAPPINGS = listOf(
    " ↵" to KissIcon.SEND_ENTER,
    " →" to KissIcon.ARROW_RIGHT,
    " ←" to KissIcon.ARROW_LEFT,
    " ↗" to KissIcon.EXTERNAL_LINK,
    " ▲" to KissIcon.CHEVRON_UP,
    " ▴" to KissIcon.CHEVRON_UP,
    " ▼" to KissIcon.CHEVRON_DOWN,
    " ▾" to KissIcon.CHEVRON_DOWN,
    " ▸" to KissIcon.CHEVRON_RIGHT,
    " ▶" to KissIcon.CHEVRON_RIGHT,
    " 👁" to KissIcon.EYE,
    " •" to KissIcon.CIRCLE_DOT,
    " ●" to KissIcon.CIRCLE_DOT
)

private val EMPTY_PARSED_LABEL = ParsedLabelWithIcons(null, "", null)

private val parsedKissLabelCache = object : LinkedHashMap<String, ParsedLabelWithIcons>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedLabelWithIcons>?): Boolean = size > 512
}

/**
 * Strips emoji or symbol tokens from a label and maps them to `KissIcon`s.
 */
fun parseKissLabelIcons(raw: String): ParsedLabelWithIcons {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return EMPTY_PARSED_LABEL
    synchronized(parsedKissLabelCache) {
        parsedKissLabelCache[trimmed]?.let { return it }
    }

    // Exact single-icon matches (for icon-only buttons & avatar circles)
    val singleIcon: KissIcon? = when (trimmed) {
        "☰", "▤" -> KissIcon.MENU
        "◂" -> KissIcon.SIDEBAR_CLOSE
        "+" -> KissIcon.PLUS
        "—", "-", "−", "–" -> KissIcon.MINUS
        "×", "✕" -> KissIcon.CLOSE
        "🗑", "🗑️" -> KissIcon.TRASH
        "📋" -> KissIcon.COPY
        "✎", "✏️", "✍", "✍️" -> KissIcon.EDIT
        "✦", "✨" -> KissIcon.SPARKLES
        "⚙", "⚙️" -> KissIcon.SETTINGS
        "🔧", "🛠", "🛠️" -> KissIcon.WRENCH
        "🐚", "⌨", "⌨️" -> KissIcon.TERMINAL
        "📎" -> KissIcon.PAPERCLIP
        "🧠" -> KissIcon.BRAIN
        "📌" -> KissIcon.PIN
        "🗺", "🗺️" -> KissIcon.MAP_TREE
        "⤒" -> KissIcon.CHEVRONS_UP
        "⤓" -> KissIcon.CHEVRONS_DOWN
        "↑" -> KissIcon.ARROW_UP
        "↓" -> KissIcon.ARROW_DOWN
        "←" -> KissIcon.ARROW_LEFT
        "→" -> KissIcon.ARROW_RIGHT
        "↗" -> KissIcon.EXTERNAL_LINK
        "↳" -> KissIcon.CORNER_DOWN_RIGHT
        "@" -> KissIcon.AT_SIGN
        "💬" -> KissIcon.MESSAGE_CIRCLE
        "🧪" -> KissIcon.FLASK
        "🤖" -> KissIcon.BOT
        "📚", "📖", "📕", "📗", "📘", "📙" -> KissIcon.BOOK_OPEN
        "⚡" -> KissIcon.ZAP
        "🎭" -> KissIcon.THEATER
        "🧭" -> KissIcon.COMPASS
        "🏷", "🏷️" -> KissIcon.TAG
        "📦" -> KissIcon.PACKAGE_BOX
        "🎨" -> KissIcon.PALETTE
        "👑" -> KissIcon.CROWN
        "🔥" -> KissIcon.FLAME
        "🛡", "🛡️", "🔒", "🔑" -> KissIcon.SHIELD_CHECK
        "🎲" -> KissIcon.DICE
        "🧮" -> KissIcon.CALCULATOR
        "🔍+" -> KissIcon.ZOOM_IN
        "🔍−", "🔍-" -> KissIcon.ZOOM_OUT
        "🔍" -> KissIcon.SEARCH
        "🖥", "🖥️" -> KissIcon.MONITOR
        "🖧" -> KissIcon.SERVER_NETWORK
        "♡", "♥", "❤️" -> KissIcon.HEART_PULSE
        "📂", "📁" -> KissIcon.FOLDER
        "☁", "☁️" -> KissIcon.CLOUD
        "○" -> KissIcon.CIRCLE_EMPTY
        "⋯" -> KissIcon.MORE_HORIZONTAL
        "🎯" -> KissIcon.TARGET
        "↺" -> KissIcon.REWIND
        "⟳", "↻", "🔄" -> KissIcon.REFRESH
        "📄", "📝" -> KissIcon.FILE_TEXT
        "🎵" -> KissIcon.AUDIO
        "🎙", "🎙️" -> KissIcon.MIC
        "🗂", "🗂️" -> KissIcon.TABS
        "⊞" -> KissIcon.LAYOUT_GRID
        "👤" -> KissIcon.USER
        "🔊" -> KissIcon.VOLUME_ON
        "🔇" -> KissIcon.VOLUME_OFF
        "✓", "✔" -> KissIcon.CHECK
        "💾", "📥", "⬇", "⬇️" -> KissIcon.DOWNLOAD
        "📤" -> KissIcon.UPLOAD
        "👁", "👁️" -> KissIcon.EYE
        "🙈" -> KissIcon.EYE_OFF
        "🖼", "🖼️" -> KissIcon.IMAGE
        "🎬" -> KissIcon.VIDEO
        "🔗", "⛓" -> KissIcon.LINK
        "⏳" -> KissIcon.CLOCK
        "■" -> KissIcon.STOP
        "⚠", "⚠️" -> KissIcon.WARNING
        "‹/›", "</>", "💻" -> KissIcon.CODE
        "🌐" -> KissIcon.TRANSLATE
        "⑂" -> KissIcon.GIT_BRANCH
        "▴", "▲" -> KissIcon.CHEVRON_UP
        "▾", "▼" -> KissIcon.CHEVRON_DOWN
        "▸", "▶" -> KissIcon.CHEVRON_RIGHT
        else -> null
    }
    if (singleIcon != null) {
        val result = ParsedLabelWithIcons(singleIcon, "", null)
        synchronized(parsedKissLabelCache) { parsedKissLabelCache[trimmed] = result }
        return result
    }
    var working = trimmed
    var leading: KissIcon? = null
    var trailing: KissIcon? = null

    for ((prefix, icon) in PREFIX_ICON_MAPPINGS) {
        if (working.startsWith(prefix)) {
            leading = icon
            working = if (prefix == "📋 Markdown") "MD" else working.removePrefix(prefix).trimStart()
            break
        }
    }

    for ((suffix, icon) in SUFFIX_ICON_MAPPINGS) {
        if (working.endsWith(suffix)) {
            trailing = icon
            working = working.removeSuffix(suffix).trimEnd()
            break
        }
    }

    val result = ParsedLabelWithIcons(leading, working, trailing)
    synchronized(parsedKissLabelCache) { parsedKissLabelCache[trimmed] = result }
    return result
}

@Composable
fun KissVectorIcon(
    icon: KissIcon,
    tint: Color,
    size: Dp = 14.dp,
    strokeWidthDp: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier
) {
    // Icons sit beside text, so they grow with the system text size too (capped; see textGrowth).
    Canvas(modifier = modifier.size(size * textGrowth())) {
        val unit = this.size.minDimension / 24f
        // Weight follows size within limits, so small and large icons read as one family.
        val strokePx = if (strokeWidthDp != Dp.Unspecified) strokeWidthDp.toPx()
            else (unit * 1.85f).coerceIn(1.15.dp.toPx(), 2.dp.toPx())
        val glyph = kissGlyph(icon)
        withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
            drawPath(glyph.stroke, tint, style = Stroke(strokePx / unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
            glyph.fill?.let { drawPath(it, tint) }
        }
    }
}

// AI lab logos from lobehub/lobe-icons, drawn from their 24×24 SVG paths. The common labs are built in;
// any other lab's logo is fetched from raw.githubusercontent.com/lobehub/lobe-icons when first needed.
private val LOBE_ICONS_HTTP_CLIENT
    get() = AiProviderEngine.httpClient
private val DYNAMIC_LOBE_SVG_CACHE = mutableStateMapOf<String, List<String>>()
private val MISSING_LOBE_SVG_SLUGS = mutableSetOf<String>()
private val LOADING_LOBE_SVG_SLUGS = mutableSetOf<String>()
private val PARSED_LAB_SVG_PATH_CACHE = mutableMapOf<String, List<Path>>()

internal val BUILTIN_LAB_SVG_PATHS: Map<String, List<String>> = mapOf(
    "gemini" to listOf(
        "M20.616 10.835a14.147 14.147 0 01-4.45-3.001 14.111 14.111 0 01-3.678-6.452.503.503 0 00-.975 0 14.134 14.134 0 01-3.679 6.452 14.155 14.155 0 01-4.45 3.001c-.65.28-1.318.505-2.002.678a.502.502 0 000 .975c.684.172 1.35.397 2.002.677a14.147 14.147 0 014.45 3.001 14.112 14.112 0 013.679 6.453.502.502 0 00.975 0c.172-.685.397-1.351.677-2.003a14.145 14.145 0 013.001-4.45 14.113 14.113 0 016.453-3.678.503.503 0 000-.975 13.245 13.245 0 01-2.003-.678z"
    ),
    "openai" to listOf(
        "M9.205 8.658v-2.26c0-.19.072-.333.238-.428l4.543-2.616c.619-.357 1.356-.523 2.117-.523 2.854 0 4.662 2.212 4.662 4.566 0 .167 0 .357-.024.547l-4.71-2.759a.797.797 0 00-.856 0l-5.97 3.473zm10.609 8.8V12.06c0-.333-.143-.57-.429-.737l-5.97-3.473 1.95-1.118a.433.433 0 01.476 0l4.543 2.617c1.309.76 2.189 2.378 2.189 3.948 0 1.808-1.07 3.473-2.76 4.163zM7.802 12.703l-1.95-1.142c-.167-.095-.239-.238-.239-.428V5.899c0-2.545 1.95-4.472 4.591-4.472 1 0 1.927.333 2.712.928L8.23 5.067c-.285.166-.428.404-.428.737v6.898zM12 15.128l-2.795-1.57v-3.33L12 8.658l2.795 1.57v3.33L12 15.128zm1.796 7.23c-1 0-1.927-.332-2.712-.927l4.686-2.712c.285-.166.428-.404.428-.737v-6.898l1.974 1.142c.167.095.238.238.238.428v5.233c0 2.545-1.974 4.472-4.614 4.472zm-5.637-5.303l-4.544-2.617c-1.308-.761-2.188-2.378-2.188-3.948A4.482 4.482 0 014.21 6.327v5.423c0 .333.143.571.428.738l5.947 3.449-1.95 1.118a.432.432 0 01-.476 0zm-.262 3.9c-2.688 0-4.662-2.021-4.662-4.519 0-.19.024-.38.047-.57l4.686 2.71c.286.167.571.167.856 0l5.97-3.448v2.26c0 .19-.07.333-.237.428l-4.543 2.616c-.619.357-1.356.523-2.117.523zm5.899 2.83a5.947 5.947 0 005.827-4.756C22.287 18.339 24 15.84 24 13.296c0-1.665-.713-3.282-1.998-4.448.119-.5.19-.999.19-1.498 0-3.401-2.759-5.947-5.946-5.947-.642 0-1.26.095-1.88.31A5.962 5.962 0 0010.205 0a5.947 5.947 0 00-5.827 4.757C1.713 5.447 0 7.945 0 10.49c0 1.666.713 3.283 1.998 4.448-.119.5-.19 1-.19 1.499 0 3.401 2.759 5.946 5.946 5.946.642 0 1.26-.095 1.88-.309a5.96 5.96 0 004.162 1.713z"
    ),
    "claude" to listOf(
        "M4.709 15.955l4.72-2.647.08-.23-.08-.128H9.2l-.79-.048-2.698-.073-2.339-.097-2.266-.122-.571-.121L0 11.784l.055-.352.48-.321.686.06 1.52.103 2.278.158 1.652.097 2.449.255h.389l.055-.157-.134-.098-.103-.097-2.358-1.596-2.552-1.688-1.336-.972-.724-.491-.364-.462-.158-1.008.656-.722.881.06.225.061.893.686 1.908 1.476 2.491 1.833.365.304.145-.103.019-.073-.164-.274-1.355-2.446-1.446-2.49-.644-1.032-.17-.619a2.97 2.97 0 01-.104-.729L6.283.134 6.696 0l.996.134.42.364.62 1.414 1.002 2.229 1.555 3.03.456.898.243.832.091.255h.158V9.01l.128-1.706.237-2.095.23-2.695.08-.76.376-.91.747-.492.584.28.48.685-.067.444-.286 1.851-.559 2.903-.364 1.942h.212l.243-.242.985-1.306 1.652-2.064.73-.82.85-.904.547-.431h1.033l.76 1.129-.34 1.166-1.064 1.347-.881 1.142-1.264 1.7-.79 1.36.073.11.188-.02 2.856-.606 1.543-.28 1.841-.315.833.388.091.395-.328.807-1.969.486-2.309.462-3.439.813-.042.03.049.061 1.549.146.662.036h1.622l3.02.225.79.522.474.638-.079.485-1.215.62-1.64-.389-3.829-.91-1.312-.329h-.182v.11l1.093 1.068 2.006 1.81 2.509 2.33.127.578-.322.455-.34-.049-2.205-1.657-.851-.747-1.926-1.62h-.128v.17l.444.649 2.345 3.521.122 1.08-.17.353-.608.213-.668-.122-1.374-1.925-1.415-2.167-1.143-1.943-.14.08-.674 7.254-.316.37-.729.28-.607-.461-.322-.747.322-1.476.389-1.924.315-1.53.286-1.9.17-.632-.012-.042-.14.018-1.434 1.967-2.18 2.945-1.726 1.845-.414.164-.717-.37.067-.662.401-.589 2.388-3.036 1.44-1.882.93-1.086-.006-.158h-.055L4.132 18.56l-1.13.146-.487-.456.061-.746.231-.243 1.908-1.312-.006.006z"
    ),
    "anthropic" to listOf(
        "M13.827 3.52h3.603L24 20h-3.603l-6.57-16.48zm-7.258 0h3.767L16.906 20h-3.674l-1.343-3.461H5.017l-1.344 3.46H0L6.57 3.522zm4.132 9.959L8.453 7.687 6.205 13.48H10.7z"
    ),
    "deepseek" to listOf(
        "M23.748 4.482c-.254-.124-.364.113-.512.234-.051.039-.094.09-.137.136-.372.397-.806.657-1.373.626-.829-.046-1.537.214-2.163.848-.133-.782-.575-1.248-1.247-1.548-.352-.156-.708-.311-.955-.65-.172-.241-.219-.51-.305-.774-.055-.16-.11-.323-.293-.35-.2-.031-.278.136-.356.276-.313.572-.434 1.202-.422 1.84.027 1.436.633 2.58 1.838 3.393.137.093.172.187.129.323-.082.28-.18.552-.266.833-.055.179-.137.217-.329.14a5.526 5.526 0 01-1.736-1.18c-.857-.828-1.631-1.742-2.597-2.458a11.365 11.365 0 00-.689-.471c-.985-.957.13-1.743.388-1.836.27-.098.093-.432-.779-.428-.872.004-1.67.295-2.687.684a3.055 3.055 0 01-.465.137 9.597 9.597 0 00-2.883-.102c-1.885.21-3.39 1.102-4.497 2.623C.082 8.606-.231 10.684.152 12.85c.403 2.284 1.569 4.175 3.36 5.653 1.858 1.533 3.997 2.284 6.438 2.14 1.482-.085 3.133-.284 4.994-1.86.47.234.962.327 1.78.397.63.059 1.236-.03 1.705-.128.735-.156.684-.837.419-.961-2.155-1.004-1.682-.595-2.113-.926 1.096-1.296 2.746-2.642 3.392-7.003.05-.347.007-.565 0-.845-.004-.17.035-.237.23-.256a4.173 4.173 0 001.545-.475c1.396-.763 1.96-2.015 2.093-3.517.02-.23-.004-.467-.247-.588zM11.581 18c-2.089-1.642-3.102-2.183-3.52-2.16-.392.024-.321.471-.235.763.09.288.207.486.371.739.114.167.192.416-.113.603-.673.416-1.842-.14-1.897-.167-1.361-.802-2.5-1.86-3.301-3.307-.774-1.393-1.224-2.887-1.298-4.482-.02-.386.093-.522.477-.592a4.696 4.696 0 011.529-.039c2.132.312 3.946 1.265 5.468 2.774.868.86 1.525 1.887 2.202 2.891.72 1.066 1.494 2.082 2.48 2.914.348.292.625.514.891.677-.802.09-2.14.11-3.054-.614z"
    ),
    "grok" to listOf(
        "M9.27 15.29l7.978-5.897c.391-.29.95-.177 1.137.272.98 2.369.542 5.215-1.41 7.169-1.951 1.954-4.667 2.382-7.149 1.406l-2.711 1.257c3.889 2.661 8.611 2.003 11.562-.953 2.341-2.344 3.066-5.539 2.388-8.42l.006.007c-.983-4.232.242-5.924 2.75-9.383.06-.082.12-.164.179-.248l-3.301 3.305v-.01L9.267 15.292M7.623 16.723c-2.792-2.67-2.31-6.801.071-9.184 1.761-1.763 4.647-2.483 7.166-1.425l2.705-1.25a7.808 7.808 0 00-1.829-1A8.975 8.975 0 005.984 5.83c-2.533 2.536-3.33 6.436-1.962 9.764 1.022 2.487-.653 4.246-2.34 6.022-.599.63-1.199 1.259-1.682 1.925l7.62-6.815"
    ),
    "qwen" to listOf(
        "M19.713 2.825h-5.907c0-.251-.066-.503-.196-.728L12.819.728A1.46 1.46 0 0 0 11.559 0h-1.58c-.52 0-1 .275-1.26.728l-2.42 4.19a1.45 1.45 0 0 0 0 1.453l.791 1.37c.26.448.74.727 1.26.727h11.36c.52 0 1-.275 1.26-.728l.79-1.37a1.45 1.45 0 0 0 0-1.451l-.79-1.37a1.46 1.46 0 0 0-1.26-.728zM.195 9.905l2.951 5.115a1.46 1.46 0 0 0-.531.531l-.791 1.37a1.45 1.45 0 0 0 0 1.452l.79 1.37c.26.448.74.727 1.26.727h4.84c.52 0 1-.275 1.26-.728l.79-1.37a1.45 1.45 0 0 0 0-1.451L5.086 7.083a1.46 1.46 0 0 0-1.26-.728h-1.58c-.52 0-1 .276-1.26.728l-.79 1.37a1.45 1.45 0 0 0 0 1.452m15.888 13.367 2.951-5.115c.217.125.469.196.728.196h1.582c.52 0 1-.275 1.26-.728l.79-1.37a1.45 1.45 0 0 0 0-1.451l-2.42-4.191a1.46 1.46 0 0 0-1.259-.728h-1.582c-.52 0-1 .276-1.259.728l-5.678 9.838a1.45 1.45 0 0 0 0 1.452l.79 1.37c.26.448.74.727 1.26.727h1.582c.52 0 1-.275 1.26-.728z"
    ),
    "mistral" to listOf(
        "M3.428 3.4h3.429v3.428h3.429v3.429h-.002 3.431V6.828h3.427V3.4h3.43v13.714H24v3.429H13.714v-3.428h-3.428v-3.429h-3.43v3.428h3.43v3.429H0v-3.429h3.428V3.4zm10.286 13.715h3.428v-3.429h-3.427v3.429z"
    ),
    "meta" to listOf(
        "M6.897 4c1.915 0 3.516.932 5.43 3.376l.282-.373c.19-.246.383-.484.58-.71l.313-.35C14.588 4.788 15.792 4 17.225 4c1.273 0 2.469.557 3.491 1.516l.218.213c1.73 1.765 2.917 4.71 3.053 8.026l.011.392.002.25c0 1.501-.28 2.759-.818 3.7l-.14.23-.108.153c-.301.42-.664.758-1.086 1.009l-.265.142-.087.04a3.493 3.493 0 01-.302.118 4.117 4.117 0 01-1.33.208c-.524 0-.996-.067-1.438-.215-.614-.204-1.163-.56-1.726-1.116l-.227-.235c-.753-.812-1.534-1.976-2.493-3.586l-1.43-2.41-.544-.895-1.766 3.13-.343.592C7.597 19.156 6.227 20 4.356 20c-1.21 0-2.205-.42-2.936-1.182l-.168-.184c-.484-.573-.837-1.311-1.043-2.189l-.067-.32a8.69 8.69 0 01-.136-1.288L0 14.468c.002-.745.06-1.49.174-2.23l.1-.573c.298-1.53.828-2.958 1.536-4.157l.209-.34c1.177-1.83 2.789-3.053 4.615-3.16L6.897 4zm-.033 2.615l-.201.01c-.83.083-1.606.673-2.252 1.577l-.138.199-.01.018c-.67 1.017-1.185 2.378-1.456 3.845l-.004.022a12.591 12.591 0 00-.207 2.254l.002.188c.004.18.017.36.04.54l.043.291c.092.503.257.908.486 1.208l.117.137c.303.323.698.492 1.17.492 1.1 0 1.796-.676 3.696-3.641l2.175-3.4.454-.701-.139-.198C9.11 7.3 8.084 6.616 6.864 6.616zm10.196-.552l-.176.007c-.635.048-1.223.359-1.82.933l-.196.198c-.439.462-.887 1.064-1.367 1.807l.266.398c.18.274.362.56.55.858l.293.475 1.396 2.335.695 1.114c.583.926 1.03 1.6 1.408 2.082l.213.262c.282.326.529.54.777.673l.102.05c.227.1.457.138.718.138.176.002.35-.023.518-.073.338-.104.61-.32.813-.637l.095-.163.077-.162c.194-.459.29-1.06.29-1.785l-.006-.449c-.08-2.871-.938-5.372-2.2-6.798l-.176-.189c-.67-.683-1.444-1.074-2.27-1.074z"
    ),
    "bytedance" to listOf(
        "M14.944 18.587l-1.704-.445V10.01l1.824-.462c1-.254 1.84-.461 1.88-.453.032 0 .056 2.235.056 4.972v4.973l-.176-.008c-.104 0-.952-.207-1.88-.446z",
        "M7 16.542c0-2.736.024-4.98.064-4.98.032-.008.872.2 1.88.454l1.816.461-.016 4.05-.024 4.049-1.632.422c-.896.23-1.736.445-1.856.469L7 21.523v-4.98z",
        "M19.24 12.477c0-9.03.008-9.515.144-9.475.072.024.784.207 1.576.406.792.207 1.576.405 1.744.445l.296.08-.016 8.56-.024 8.568-1.624.414c-.888.23-1.728.437-1.856.47l-.24.055v-9.523z",
        "M1 12.509c0-4.678.024-8.505.064-8.505.032 0 .872.207 1.872.454l1.824.461v7.582c0 4.16-.016 7.574-.032 7.574-.024 0-.872.215-1.88.47L1 21.013v-8.505z"
    ),
    "doubao" to listOf(
        "M5.31 15.756c.172-3.75 1.883-5.999 2.549-6.739-3.26 2.058-5.425 5.658-6.358 8.308v1.12C1.501 21.513 4.226 24 7.59 24a6.59 6.59 0 002.2-.375c.353-.12.7-.248 1.039-.378.913-.899 1.65-1.91 2.243-2.992-4.877 2.431-7.974.072-7.763-4.5l.002.001z",
        "M22.57 10.283c-1.212-.901-4.109-2.404-7.397-2.8.295 3.792.093 8.766-2.1 12.773a12.782 12.782 0 01-2.244 2.992c3.764-1.448 6.746-3.457 8.596-5.219 2.82-2.683 3.353-5.178 3.361-6.66a2.737 2.737 0 00-.216-1.084v-.002zM14.303 1.867C12.955.7 11.248 0 9.39 0 7.532 0 5.883.677 4.545 1.807 2.791 3.29 1.627 5.557 1.5 8.125v9.201c.932-2.65 3.097-6.25 6.357-8.307.5-.318 1.025-.595 1.569-.829 1.883-.801 3.878-.932 5.746-.706-.222-2.83-.718-5.002-.87-5.617h.001z"
    ),
    "zhipu" to listOf(
        "M9.671 5.365a1.697 1.697 0 011.099 2.132l-.071.172-.016.04-.018.054c-.07.16-.104.32-.104.498-.035.71.47 1.279 1.186 1.314h.366c1.309.053 2.338 1.173 2.286 2.523-.052 1.332-1.152 2.38-2.478 2.327h-.174c-.715.018-1.274.64-1.239 1.368 0 .124.018.23.053.337.209.373.54.658.96.8.75.23 1.517-.125 1.9-.782l.018-.035c.402-.64 1.17-.96 1.92-.711.854.284 1.378 1.226 1.099 2.167a1.661 1.661 0 01-2.077 1.102 1.711 1.711 0 01-.907-.711l-.017-.035c-.2-.323-.463-.58-.851-.711l-.056-.018a1.646 1.646 0 00-1.954.746 1.66 1.66 0 01-1.065.764 1.677 1.677 0 01-1.989-1.279c-.209-.906.332-1.83 1.257-2.043a1.51 1.51 0 01.296-.035h.018c.68-.071 1.151-.622 1.116-1.333a1.307 1.307 0 00-.227-.693 2.515 2.515 0 01-.366-1.403 2.39 2.39 0 01.366-1.208c.14-.195.21-.444.227-.693.018-.71-.506-1.261-1.186-1.332l-.07-.018a1.43 1.43 0 01-.299-.07l-.05-.019a1.7 1.7 0 01-1.047-2.114 1.68 1.68 0 012.094-1.101zm10.031-1.475c.925 0 1.675.764 1.675 1.706s-.75 1.705-1.675 1.705-1.674-.763-1.674-1.705c0-.942.75-1.706 1.674-1.706z"
    ),
    "kimi" to listOf(
        "M21.846 0a1.923 1.923 0 110 3.846H20.15a.226.226 0 01-.227-.226V1.923C19.923.861 20.784 0 21.846 0z",
        "M11.065 11.199l7.257-7.2c.137-.136.06-.41-.116-.41H14.3a.164.164 0 00-.117.051l-7.82 7.756c-.122.12-.302.013-.302-.179V3.82c0-.127-.083-.23-.185-.23H3.186c-.103 0-.186.103-.186.23V19.77c0 .128.083.23.186.23h2.69c.103 0 .186-.102.186-.23v-3.25c0-.069.025-.135.069-.178l2.424-2.406a.158.158 0 01.205-.023l6.484 4.772a7.677 7.677 0 003.453 1.283c.108.012.2-.095.2-.23v-3.06c0-.117-.07-.212-.164-.227a5.028 5.028 0 01-2.027-.807l-5.613-4.064c-.117-.078-.132-.279-.028-.381z"
    ),
    "minimax" to listOf(
        "M16.278 2c1.156 0 2.093.927 2.093 2.07v12.501a.74.74 0 00.744.709.74.74 0 00.743-.709V9.099a2.06 2.06 0 012.071-2.049A2.06 2.06 0 0124 9.1v6.561a.649.649 0 01-.652.645.649.649 0 01-.653-.645V9.1a.762.762 0 00-.766-.758.762.762 0 00-.766.758v7.472a2.037 2.037 0 01-2.048 2.026 2.037 2.037 0 01-2.048-2.026v-12.5a.785.785 0 00-.788-.753.785.785 0 00-.789.752l-.001 15.904A2.037 2.037 0 0113.441 22a2.037 2.037 0 01-2.048-2.026V18.04c0-.356.292-.645.652-.645.36 0 .652.289.652.645v1.934c0 .263.142.506.372.638.23.131.514.131.744 0a.734.734 0 00.372-.638V4.07c0-1.143.937-2.07 2.093-2.07zm-5.674 0c1.156 0 2.093.927 2.093 2.07v11.523a.648.648 0 01-.652.645.648.648 0 01-.652-.645V4.07a.785.785 0 00-.789-.78.785.785 0 00-.789.78v14.013a2.06 2.06 0 01-2.07 2.048 2.06 2.06 0 01-2.071-2.048V9.1a.762.762 0 00-.766-.758.762.762 0 00-.766.758v3.8a2.06 2.06 0 01-2.071 2.049A2.06 2.06 0 010 12.9v-1.378c0-.357.292-.646.652-.646.36 0 .653.29.653.646V12.9c0 .418.343.757.766.757s.766-.339.766-.757V9.099a2.06 2.06 0 012.07-2.048 2.06 2.06 0 012.071 2.048v8.984c0 .419.343.758.767.758.423 0 .766-.339.766-.758V4.07c0-1.143.937-2.07 2.093-2.07z"
    ),
    "perplexity" to listOf(
        "M19.785 0v7.272H22.5V17.62h-2.935V24l-7.037-6.194v6.145h-1.091v-6.152L4.392 24v-6.465H1.5V7.188h2.884V0l7.053 6.494V.19h1.09v6.49L19.786 0zm-7.257 9.044v7.319l5.946 5.234V14.44l-5.946-5.397zm-1.099-.08l-5.946 5.398v7.235l5.946-5.234V8.965zm8.136 7.58h1.844V8.349H13.46l6.105 5.54v2.655zm-8.982-8.28H2.59v8.195h1.8v-2.576l6.192-5.62zM5.475 2.476v4.71h5.115l-5.115-4.71zm13.219 0l-5.115 4.71h5.115v-4.71z"
    ),
    "cohere" to listOf(
        "M8.128 14.099c.592 0 1.77-.033 3.398-.703 1.897-.781 5.672-2.2 8.395-3.656 1.905-1.018 2.74-2.366 2.74-4.18A4.56 4.56 0 0018.1 1H7.549A6.55 6.55 0 001 7.55c0 3.617 2.745 6.549 7.128 6.549z",
        "M9.912 18.61a4.387 4.387 0 012.705-4.052l3.323-1.38c3.361-1.394 7.06 1.076 7.06 4.715a5.104 5.104 0 01-5.105 5.104l-3.597-.001a4.386 4.386 0 01-4.386-4.387z",
        "M4.776 14.962A3.775 3.775 0 001 18.738v.489a3.776 3.776 0 007.551 0v-.49a3.775 3.775 0 00-3.775-3.775z"
    ),
    "openrouter" to listOf(
        "M18.654 3.87a5.087 5.087 0 110 10.174L23.7 19.09c.64.641.187 1.737-.72 1.737H8.48a8.479 8.479 0 010-16.958h10.175zM8.479 7.26a5.087 5.087 0 100 10.176 5.087 5.087 0 000-10.175z"
    ),
    "google" to listOf(
        "M23 12.245c0-.905-.075-1.565-.236-2.25h-10.54v4.083h6.186c-.124 1.014-.797 2.542-2.294 3.569l-.021.136 3.332 2.53.23.022C21.779 18.417 23 15.593 23 12.245z",
        "M12.225 23c3.03 0 5.574-.978 7.433-2.665l-3.542-2.688c-.948.648-2.22 1.1-3.891 1.1a6.745 6.745 0 01-6.386-4.572l-.132.011-3.465 2.628-.045.124C4.043 20.531 7.835 23 12.225 23z",
        "M5.84 14.175A6.65 6.65 0 015.463 12c0-.758.138-1.491.361-2.175l-.006-.147-3.508-2.67-.115.054A10.831 10.831 0 001 12c0 1.772.436 3.447 1.197 4.938l3.642-2.763z",
        "M12.225 5.253c2.108 0 3.529.892 4.34 1.638l3.167-3.031C17.787 2.088 15.255 1 12.225 1 7.834 1 4.043 3.469 2.197 7.062l3.63 2.763a6.77 6.77 0 016.398-4.572z"
    )
)

private val OPENAI_REASONING_SLUG_REGEX = Regex("""(^|[/:_-])o[134]([/:_-]|$)""")
private val NON_ALNUM_SLUG_REGEX = Regex("[^a-z0-9]")

private fun matchKnownLabSlug(raw: String): String? {
    val s = raw.trim().lowercase()
    if (s.isEmpty()) return null
    return when {
        s.contains("gemini") || s.contains("gemma") || s.contains("learnlm") ||
            s.contains("deep-research") || s.contains("nano-banana") || s.contains("palm") -> "gemini"
        s.contains("claude") -> "claude"
        s.contains("anthropic") -> "anthropic"
        s.contains("doubao") || s.contains("seedream") || s.contains("seedance") ||
            s.contains("seed-1") || s.contains("seed-2") || s.contains("seed-audio") ||
            s.contains("bytedance-seed") || s.contains("dreamina") || s.contains("dola-seed") ||
            s.contains("ui-tars") -> "doubao"
        s.contains("bytedance") || s.contains("volcengine") || s.contains("byteplus") ||
            s == "ark" || s.contains("bytedance_ark") -> "bytedance"
        s.contains("deepseek") -> "deepseek"
        s.contains("grok") || s.contains("xai") || s.contains("x.ai") || s.contains("x-ai") || s.contains("aurora") -> "grok"
        s.contains("qwen") || s.contains("qwq") || s.contains("tongyi") || s.contains("alibaba") ||
            s.contains("dashscope") || s.contains("wan-") || s.contains("wan2") || s.contains("wan3") ||
            s.contains("wanx") || s.contains("happyhorse") -> "qwen"
        s.contains("mistral") || s.contains("mixtral") || s.contains("codestral") || s.contains("pixtral") || s.contains("ministral") -> "mistral"
        s.contains("llama") || s.contains("meta") -> "meta"
        s.contains("glm") || s.contains("zhipu") || s.contains("chatglm") || s.contains("cogview") || s.contains("cogvideo") || s.contains("zai") -> "zhipu"
        s.contains("kimi") || s.contains("moonshot") -> "kimi"
        s.contains("minimax") || s.contains("abab") || s.contains("hailuo") -> "minimax"
        s.contains("perplexity") || s.contains("sonar") -> "perplexity"
        s.contains("cohere") || s.contains("command-r") || s.contains("aya") -> "cohere"
        s.contains("google") || s.contains("imagen") || s.contains("veo-") || s.contains("/veo") ||
            s.startsWith("veo") || s.contains("veo2") || s.contains("veo3") -> "google"
        s.contains("flux") || s.contains("black-forest-labs") -> "flux"
        s.contains("kling") || s.contains("kwaivgi") || s.contains("kuaishou") -> "kling"
        s.contains("runway") || s.contains("gen-3") || s.contains("gen-4") || s.contains("aleph-") -> "runway"
        s.contains("luma") || s.contains("ray-2") || s.contains("ray2") -> "luma"
        s.contains("recraft") -> "recraft"
        s.contains("ideogram") -> "ideogram"
        s.contains("midjourney") -> "midjourney"
        s.contains("stable-diffusion") || s.contains("sdxl") || s.contains("sd3") || s.contains("stability") -> "stability"
        s.contains("elevenlabs") -> "elevenlabs"
        s.contains("gpt") || s.contains("openai") || s.contains("chatgpt") || s.contains("codex") ||
            s.contains("dall-e") || s.contains("sora") || s.contains("whisper") ||
            s.startsWith("tts-") || s.contains("/tts-") ||
            OPENAI_REASONING_SLUG_REGEX.containsMatchIn(s) -> "openai"
        s.contains("openrouter") -> "openrouter"
        else -> null
    }
}

/**
 * Maps any model ID (e.g. `gemini-4-argon`, `claude-sonnet-4`, `gpt-5`, `deepseek-r1`, `qwen/qwen3-235b`,
 * `seedream-5-0-pro`, `seedance-2.5`, `deep-research-preview`) or provider name/URL to its canonical
 * `@lobehub/icons-static-svg` icon slug.
 *
 * Strictly evaluates [modelOrProvider] first before consulting [fallbackProtocolName] so that non-OpenAI
 * models (`seedream`, `seedance`, `deep-research`, `deepseek`, `qwen`, etc.) on an OpenAI-compatible
 * proxy always display their authentic lab icon rather than the proxy protocol's icon.
 */
fun resolveLabSlugForModelOrProvider(modelOrProvider: String, fallbackProtocolName: String = ""): String {
    // 1. Match the model ID or provider string directly first (without polluting with fallbackProtocolName)
    matchKnownLabSlug(modelOrProvider)?.let { return it }

    // 2. Consult live /models vendor discovery and the Multi-Source Model Registry
    val discoveredVendor = io.parchment.ai.network.AiProviderEngine.getDiscoveredVendorForModel(modelOrProvider)
    if (!discoveredVendor.isNullOrBlank()) {
        matchKnownLabSlug(discoveredVendor)?.let { return it }
    }

    // 3. If modelOrProvider has an explicit `vendor/model` prefix, try the vendor prefix
    if (modelOrProvider.contains('/')) {
        val vendorPrefix = modelOrProvider.substringBefore('/').lowercase().replace(NON_ALNUM_SLUG_REGEX, "")
        if (vendorPrefix.isNotEmpty()) {
            matchKnownLabSlug(vendorPrefix)?.let { return it }
            return vendorPrefix
        }
    }

    // 4. Only fall back to fallbackProtocolName when modelOrProvider itself did not match any known lab
    matchKnownLabSlug(fallbackProtocolName)?.let { return it }

    val fallbackPrefix = modelOrProvider.lowercase().substringBefore('/').replace(NON_ALNUM_SLUG_REGEX, "")
    return fallbackPrefix.ifEmpty { "gemini" }
}

private val SVG_PATH_D_REGEX = Regex("""<path[^>]*\bd=["']([^"']+)["']""")

/**
 * Normalizes SVGO-minified SVG `<path d="..."/>` strings so that single-bit arc flags (`large-arc-flag`
 * and `sweep-flag` at positions 3 and 4 of every 7-argument `A`/`a` tuple, such as `01-4.45` or `000`)
 * and adjacent decimals (`.502.502`) are always separated by spaces before passing to Compose `PathParser`.
 */
internal fun normalizeSvgPathArcFlags(d: String): String {
    val sb = StringBuilder(d.length + 64)
    var i = 0
    val n = d.length
    var currentCmd = ' '
    var argIndexInCmd = 0

    fun skipSeparators() {
        while (i < n && (d[i].isWhitespace() || d[i] == ',')) i++
    }

    fun readNumberToken(): String {
        val start = i
        if (i < n && (d[i] == '+' || d[i] == '-')) i++
        var sawDot = false
        while (i < n) {
            val c = d[i]
            if (c.isDigit()) {
                i++
            } else if (c == '.' && !sawDot) {
                sawDot = true
                i++
            } else if (c == 'e' || c == 'E') {
                i++
                if (i < n && (d[i] == '+' || d[i] == '-')) i++
            } else {
                break
            }
        }
        return d.substring(start, i)
    }

    while (true) {
        skipSeparators()
        if (i >= n) break
        val ch = d[i]
        if (ch.isLetter()) {
            currentCmd = ch
            argIndexInCmd = 0
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(ch)
            i++
            continue
        }
        val isArcCmd = currentCmd == 'A' || currentCmd == 'a'
        val tuplePos = if (isArcCmd) (argIndexInCmd % 7) else -1
        if (isArcCmd && (tuplePos == 3 || tuplePos == 4) && (ch == '0' || ch == '1')) {
            sb.append(' ').append(ch)
            i++
            argIndexInCmd++
        } else {
            val numTok = readNumberToken()
            if (numTok.isEmpty()) {
                i++
            } else {
                sb.append(' ').append(numTok)
                argIndexInCmd++
            }
        }
    }
    return sb.toString()
}

internal fun parseSvgPathNormalized(dStr: String): Path {
    val normalized = normalizeSvgPathArcFlags(dStr)
    return PathParser().parsePathString(normalized).toPath().apply {
        fillType = PathFillType.EvenOdd
    }
}

/**
 * Renders the official AI Lab / Model SVG icon (`viewBox="0 0 24 24"`) for any model ID or provider.
 * Uses the embedded `@lobehub/icons-static-svg` paths for instant 0ms rendering and automatically
 * fetches + caches `.svg` paths from `lobehub/lobe-icons` online for any novel lab slug.
 */
@Composable
fun ModelLabSvgIcon(
    modelOrProvider: String,
    tint: Color,
    size: Dp = 15.dp,
    fallbackProtocolName: String = "",
    modifier: Modifier = Modifier
) {
    val slug = remember(modelOrProvider, fallbackProtocolName) {
        resolveLabSlugForModelOrProvider(modelOrProvider, fallbackProtocolName)
    }
    val rawPaths = BUILTIN_LAB_SVG_PATHS[slug] ?: DYNAMIC_LOBE_SVG_CACHE[slug]

    LaunchedEffect(slug) {
        if (rawPaths == null && slug !in MISSING_LOBE_SVG_SLUGS && LOADING_LOBE_SVG_SLUGS.add(slug)) {
            try {
                runCatching {
                    val url = "https://raw.githubusercontent.com/lobehub/lobe-icons/master/packages/static-svg/icons/$slug.svg"
                    val resp = LOBE_ICONS_HTTP_CLIENT.get(url)
                    if (resp.status.isSuccess()) {
                        val svgText = resp.bodyAsText()
                        val extracted = SVG_PATH_D_REGEX.findAll(svgText).map { it.groupValues[1] }.toList()
                        if (extracted.isNotEmpty()) DYNAMIC_LOBE_SVG_CACHE[slug] = extracted else MISSING_LOBE_SVG_SLUGS += slug
                    } else if (resp.status.value == 404) {
                        MISSING_LOBE_SVG_SLUGS += slug
                    }
                }
            } finally {
                LOADING_LOBE_SVG_SLUGS.remove(slug)
            }
        }
    }

    val parsedVectorPaths = remember(slug, rawPaths) {
        val cached = PARSED_LAB_SVG_PATH_CACHE[slug]
        if (cached != null) {
            cached
        } else {
            val parsed = rawPaths?.mapNotNull { dStr ->
                runCatching { parseSvgPathNormalized(dStr) }.getOrNull()
            }.orEmpty()
            if (parsed.isNotEmpty()) {
                PARSED_LAB_SVG_PATH_CACHE[slug] = parsed
            }
            parsed
        }
    }

    if (parsedVectorPaths.isNotEmpty()) {
        Canvas(modifier = modifier.size(size * textGrowth())) {
            val scaleX = this.size.width / 24f
            val scaleY = this.size.height / 24f
            withTransform({ scale(scaleX, scaleY, Offset.Zero) }) {
                for (srcPath in parsedVectorPaths) {
                    drawPath(path = srcPath, color = tint)
                }
            }
        }
    } else {
        KissVectorIcon(icon = KissIcon.CPU, tint = tint, size = size, modifier = modifier)
    }
}
