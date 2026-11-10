package io.parchment.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Full-Spec Hierarchical W3C SVG 1.1 / 2.0 Scene-Graph Parser & Compose Canvas Renderer.
 *
 * Supports:
 * - `<svg viewBox="minX minY width height" width="..." height="...">`
 * - `<defs>`, `<style>` CSS class/tag rules, `<linearGradient>`, `<radialGradient>`, `<stop>`
 * - Recursive `<g>` groups with inherited `fill`, `stroke`, `stroke-width`, `opacity`, `fill-opacity`,
 *   `stroke-opacity`, `stroke-linecap`, `stroke-linejoin`, `stroke-dasharray`, and `fill-rule`
 * - Full 2D Affine Transform Matrices (`translate`, `scale`, `rotate(deg [, cx, cy])`, `skewX`, `skewY`, `matrix`)
 * - All SVG geometric primitives: `<path>`, `<rect>`, `<circle>`, `<ellipse>`, `<line>`, `<polyline>`, `<polygon>`
 * - Full W3C SVG/CSS color specification (`#RGB`, `#RRGGBB`, `#AARRGGBB`, `rgb()`, `rgba()`, `hsl()`,
 *   `url(#gradId)`, `currentColor`, `none`, `transparent`, and 140+ named colors)
 */
internal data class SvgSceneDocument(
    val minX: Float,
    val minY: Float,
    val width: Float,
    val height: Float,
    val backgroundColor: Color?,
    val gradients: Map<String, SvgGradientDef>,
    val drawItems: List<SvgRenderItem>
)

internal sealed class SvgGradientDef {
    abstract val stops: List<Pair<Float, Color>>

    data class Linear(
        val id: String,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val isObjectBoundingBox: Boolean,
        override val stops: List<Pair<Float, Color>>
    ) : SvgGradientDef()

    data class Radial(
        val id: String,
        val cx: Float,
        val cy: Float,
        val r: Float,
        val isObjectBoundingBox: Boolean,
        override val stops: List<Pair<Float, Color>>
    ) : SvgGradientDef()
}

internal sealed class SvgPaintSpec {
    data object None : SvgPaintSpec()
    data class Solid(val color: Color) : SvgPaintSpec()
    data class GradientRef(val id: String) : SvgPaintSpec()
}

internal data class SvgInheritedStyle(
    val fill: SvgPaintSpec = SvgPaintSpec.Solid(Color.Black),
    val stroke: SvgPaintSpec = SvgPaintSpec.None,
    val strokeWidth: Float = 1f,
    val strokeCap: StrokeCap = StrokeCap.Butt,
    val strokeJoin: StrokeJoin = StrokeJoin.Miter,
    val strokeDashArray: FloatArray? = null,
    val fillOpacity: Float = 1f,
    val strokeOpacity: Float = 1f,
    val opacity: Float = 1f,
    val fillRule: PathFillType = PathFillType.NonZero
)

/**
 * 2D Affine Transform Matrix `[a, b, c, d, tx, ty]`:
 * `x' = a*x + c*y + tx`
 * `y' = b*x + d*y + ty`
 */
internal data class Affine2D(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f
) {
    fun preConcat(other: Affine2D): Affine2D = Affine2D(
        a = a * other.a + c * other.b,
        b = b * other.a + d * other.b,
        c = a * other.c + c * other.d,
        d = b * other.c + d * other.d,
        tx = a * other.tx + c * other.ty + tx,
        ty = b * other.tx + d * other.ty + ty
    )

    fun mapX(x: Float, y: Float): Float = a * x + c * y + tx
    fun mapY(x: Float, y: Float): Float = b * x + d * y + ty

    fun avgScale(): Float {
        val sx = sqrt(a * a + b * b)
        val sy = sqrt(c * c + d * d)
        return ((sx + sy) * 0.5f).coerceAtLeast(0.001f)
    }

    fun toComposeMatrix(viewMinX: Float, viewMinY: Float, viewScale: Float, offsetX: Float, offsetY: Float): Matrix {
        // Total transform: first apply SVG local affine, then shift by (-viewMinX, -viewMinY), then scale by viewScale and offset
        val totalA = a * viewScale
        val totalB = b * viewScale
        val totalC = c * viewScale
        val totalD = d * viewScale
        val totalTx = (tx - viewMinX) * viewScale + offsetX
        val totalTy = (ty - viewMinY) * viewScale + offsetY
        return Matrix().apply {
            values[Matrix.ScaleX] = totalA
            values[Matrix.SkewY] = totalB
            values[Matrix.SkewX] = totalC
            values[Matrix.ScaleY] = totalD
            values[Matrix.TranslateX] = totalTx
            values[Matrix.TranslateY] = totalTy
        }
    }
}

internal data class SvgRenderItem(
    val path: Path,
    val style: SvgInheritedStyle,
    val transform: Affine2D
)

@Composable
internal fun InlineSvgArtifactPreview(rawSvg: String) {
    val p = LocalParchmentPalette.current
    val scene = remember(rawSvg) {
        runCatching { parseSvgSceneDocument(rawSvg, p.primaryInk) }.getOrNull()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp, max = 440.dp)
            .background(p.cardBg)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (scene == null || scene.drawItems.isEmpty()) {
            BasicText(
                text = "SVG source ready (tap Save to export .svg or Code to inspect markup).",
                style = p.uiLabelStyle
            )
        } else {
            val aspect = (scene.width / scene.height.coerceAtLeast(1f)).coerceIn(0.4f, 2.6f)
            val canvasBg = scene.backgroundColor ?: Color.Transparent
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (canvasBg != Color.Transparent) Modifier.background(canvasBg) else Modifier)
            ) {
                val scaleX = size.width / scene.width.coerceAtLeast(1f)
                val scaleY = size.height / scene.height.coerceAtLeast(1f)
                val s = min(scaleX, scaleY)
                val drawW = scene.width * s
                val drawH = scene.height * s
                val padX = (size.width - drawW) * 0.5f
                val padY = (size.height - drawH) * 0.5f

                for (item in scene.drawItems) {
                    val totalAlpha = item.style.opacity.coerceIn(0f, 1f)
                    if (totalAlpha <= 0.001f) continue

                    val transformedPath = Path().apply {
                        fillType = item.style.fillRule
                        addPath(item.path)
                        transform(item.transform.toComposeMatrix(scene.minX, scene.minY, s, padX, padY))
                    }
                    val bounds = transformedPath.getBounds()

                    // 1. Draw Fill
                    val fillAlpha = (totalAlpha * item.style.fillOpacity).coerceIn(0f, 1f)
                    if (fillAlpha > 0.001f) {
                        when (val fillSpec = item.style.fill) {
                            is SvgPaintSpec.Solid -> {
                                val col = fillSpec.color.copy(alpha = (fillSpec.color.alpha * fillAlpha).coerceIn(0f, 1f))
                                if (col.alpha > 0.001f) {
                                    drawPath(path = transformedPath, color = col)
                                }
                            }
                            is SvgPaintSpec.GradientRef -> {
                                val grad = scene.gradients[fillSpec.id]
                                val brush = grad?.toComposeBrush(bounds, item.transform, scene.minX, scene.minY, s, padX, padY, fillAlpha)
                                if (brush != null) {
                                    drawPath(path = transformedPath, brush = brush)
                                }
                            }
                            is SvgPaintSpec.None -> {}
                        }
                    }

                    // 2. Draw Stroke
                    val strokeAlpha = (totalAlpha * item.style.strokeOpacity).coerceIn(0f, 1f)
                    val effStrokeWidth = item.style.strokeWidth * item.transform.avgScale() * s
                    if (strokeAlpha > 0.001f && effStrokeWidth > 0.05f && item.style.stroke !is SvgPaintSpec.None) {
                        val dashEffect = item.style.strokeDashArray?.let { rawDashes ->
                            val scaledDashes = FloatArray(if (rawDashes.size % 2 == 1) rawDashes.size * 2 else rawDashes.size) { idx ->
                                (rawDashes[idx % rawDashes.size] * item.transform.avgScale() * s).coerceAtLeast(1f)
                            }
                            if (scaledDashes.size >= 2) PathEffect.dashPathEffect(scaledDashes, 0f) else null
                        }
                        val strokeDrawStyle = Stroke(
                            width = effStrokeWidth,
                            cap = item.style.strokeCap,
                            join = item.style.strokeJoin,
                            pathEffect = dashEffect
                        )
                        when (val strokeSpec = item.style.stroke) {
                            is SvgPaintSpec.Solid -> {
                                val col = strokeSpec.color.copy(alpha = (strokeSpec.color.alpha * strokeAlpha).coerceIn(0f, 1f))
                                if (col.alpha > 0.001f) {
                                    drawPath(path = transformedPath, color = col, style = strokeDrawStyle)
                                }
                            }
                            is SvgPaintSpec.GradientRef -> {
                                val grad = scene.gradients[strokeSpec.id]
                                val brush = grad?.toComposeBrush(bounds, item.transform, scene.minX, scene.minY, s, padX, padY, strokeAlpha)
                                if (brush != null) {
                                    drawPath(path = transformedPath, brush = brush, style = strokeDrawStyle)
                                }
                            }
                            is SvgPaintSpec.None -> {}
                        }
                    }
                }
            }
        }
    }
}

private fun SvgGradientDef.toComposeBrush(
    bounds: androidx.compose.ui.geometry.Rect,
    transform: Affine2D,
    viewMinX: Float,
    viewMinY: Float,
    viewScale: Float,
    offsetX: Float,
    offsetY: Float,
    alphaMultiplier: Float
): Brush? {
    if (stops.isEmpty()) return null
    val colorStops = stops.map { (frac, col) ->
        frac.coerceIn(0f, 1f) to col.copy(alpha = (col.alpha * alphaMultiplier).coerceIn(0f, 1f))
    }.toTypedArray()

    return when (this) {
        is SvgGradientDef.Linear -> {
            val startOffset = if (isObjectBoundingBox) {
                Offset(
                    bounds.left + x1 * bounds.width,
                    bounds.top + y1 * bounds.height
                )
            } else {
                val gx1 = (transform.mapX(x1, y1) - viewMinX) * viewScale + offsetX
                val gy1 = (transform.mapY(x1, y1) - viewMinY) * viewScale + offsetY
                Offset(gx1, gy1)
            }
            val endOffset = if (isObjectBoundingBox) {
                Offset(
                    bounds.left + x2 * bounds.width,
                    bounds.top + y2 * bounds.height
                )
            } else {
                val gx2 = (transform.mapX(x2, y2) - viewMinX) * viewScale + offsetX
                val gy2 = (transform.mapY(x2, y2) - viewMinY) * viewScale + offsetY
                Offset(gx2, gy2)
            }
            val safeEnd = if ((endOffset - startOffset).getDistanceSquared() < 0.01f) {
                startOffset + Offset(1f, 1f)
            } else endOffset
            if (colorStops.size == 1) {
                Brush.linearGradient(listOf(colorStops[0].second, colorStops[0].second))
            } else {
                Brush.linearGradient(colorStops = colorStops, start = startOffset, end = safeEnd)
            }
        }
        is SvgGradientDef.Radial -> {
            val centerOffset = if (isObjectBoundingBox) {
                Offset(
                    bounds.left + cx * bounds.width,
                    bounds.top + cy * bounds.height
                )
            } else {
                val gcx = (transform.mapX(cx, cy) - viewMinX) * viewScale + offsetX
                val gcy = (transform.mapY(cx, cy) - viewMinY) * viewScale + offsetY
                Offset(gcx, gcy)
            }
            val radiusPx = if (isObjectBoundingBox) {
                (r * max(bounds.width, bounds.height)).coerceAtLeast(1f)
            } else {
                (r * transform.avgScale() * viewScale).coerceAtLeast(1f)
            }
            if (colorStops.size == 1) {
                Brush.radialGradient(listOf(colorStops[0].second, colorStops[0].second))
            } else {
                Brush.radialGradient(colorStops = colorStops, center = centerOffset, radius = radiusPx)
            }
        }
    }
}

private const val MAX_SVG_CHARS = 512 * 1024
private const val MAX_SVG_TAGS = 20_000
private const val MAX_SVG_DRAW_ITEMS = 6_000

// Possessive runs and lookbehinds keep these linear: a failed match cannot be retried from inside a name.
private val SVG_XML_TAG_REGEX = Regex("""<(/?)([a-zA-Z0-9:_-]++)([^<>]*?)(/?)>""", RegexOption.DOT_MATCHES_ALL)
private val SVG_ATTR_PAIR_REGEX = Regex("""(?<![a-zA-Z0-9:_-])([a-zA-Z0-9:_-]++)\s*+=\s*+(?:"([^"]*+)"|'([^']*+)')""")
private val SVG_WHITESPACE_REGEX = Regex("\\s+")
private val SVG_WS_COMMA_REGEX = Regex("[\\s,]+")
private val SVG_WS_COMMA_SLASH_REGEX = Regex("[\\s,/]+")
private val SVG_TRANSFORM_FN_REGEX = Regex("""(?<![a-zA-Z])([a-zA-Z]++)\s*+\(([^)]*+)\)""")
private val SVG_NUMBER_TOKEN_REGEX = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

private fun stripSvgComments(raw: String): String {
    val out = StringBuilder(raw.length)
    var cursor = 0
    while (true) {
        val open = raw.indexOf("<!--", cursor)
        if (open == -1) return out.append(raw, cursor, raw.length).toString()
        out.append(raw, cursor, open)
        val close = raw.indexOf("-->", open + 4)
        if (close == -1) return out.toString()
        cursor = close + 3
    }
}

private fun svgStyleBodies(xml: String): List<String> {
    val bodies = mutableListOf<String>()
    var cursor = 0
    while (true) {
        val open = xml.indexOf("<style", cursor, ignoreCase = true)
        if (open == -1) return bodies
        val afterName = open + 6
        if (afterName < xml.length && xml[afterName].isLetterOrDigit()) {
            cursor = afterName
            continue
        }
        val tagEnd = xml.indexOf('>', afterName)
        if (tagEnd == -1) return bodies
        val close = xml.indexOf("</style>", tagEnd + 1, ignoreCase = true)
        if (close == -1) return bodies
        bodies += xml.substring(tagEnd + 1, close)
        cursor = close + 8
    }
}

private inline fun forEachCssRule(css: String, action: (selectors: String, declarations: String) -> Unit) {
    var cursor = 0
    while (true) {
        val open = css.indexOf('{', cursor)
        if (open == -1) return
        val close = css.indexOf('}', open + 1)
        if (close == -1) return
        action(css.substring(cursor, open), css.substring(open + 1, close))
        cursor = close + 1
    }
}

internal fun parseSvgSceneDocument(rawSvg: String, defaultCurrentColor: Color = Color.Black): SvgSceneDocument? {
    if (rawSvg.length > MAX_SVG_CHARS) return null
    val cleanXml = stripSvgComments(rawSvg).trim()
    val svgStart = cleanXml.indexOf("<svg", ignoreCase = true)
    if (svgStart == -1) return null

    // 1. Parse CSS <style> rules (.class or tag -> style map)
    val cssRules = mutableMapOf<String, Map<String, String>>()
    svgStyleBodies(cleanXml).forEach { cssBody ->
        forEachCssRule(cssBody) { selectorText, declarations ->
            val selectors = selectorText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val decls = parseInlineCssDeclarations(declarations)
            for (sel in selectors) {
                cssRules[sel] = (cssRules[sel] ?: emptyMap()) + decls
            }
        }
    }

    // 2. Tokenize XML tags in document order
    val tagRegex = SVG_XML_TAG_REGEX
    var minX = 0f
    var minY = 0f
    var vbW = 0f
    var vbH = 0f
    var svgBgColor: Color? = null

    val gradients = mutableMapOf<String, SvgGradientDef>()
    val drawItems = mutableListOf<SvgRenderItem>()

    val styleStack = ArrayDeque<SvgInheritedStyle>()
    val transformStack = ArrayDeque<Affine2D>()
    val tagStack = ArrayDeque<String>()

    styleStack.addLast(SvgInheritedStyle(fill = SvgPaintSpec.Solid(Color.Black)))
    transformStack.addLast(Affine2D())

    var insideDefsDepth = 0
    var activeGradientTag: String? = null
    var activeGradientAttrs: Map<String, String> = emptyMap()
    val activeGradientStops = mutableListOf<Pair<Float, Color>>()

    var tagCount = 0
    for (match in tagRegex.findAll(cleanXml, startIndex = svgStart)) {
        if (++tagCount > MAX_SVG_TAGS) return null
        val isClosing = match.groupValues[1] == "/"
        val rawTagName = match.groupValues[2].substringAfter(':').lowercase()
        val attrBody = match.groupValues[3]
        val isSelfClosing = match.groupValues[4] == "/"

        if (isClosing) {
            if (rawTagName == "defs" || rawTagName == "clippath" || rawTagName == "mask" || rawTagName == "symbol" || rawTagName == "marker") {
                insideDefsDepth = (insideDefsDepth - 1).coerceAtLeast(0)
            } else if ((rawTagName == "lineargradient" || rawTagName == "radialgradient") && activeGradientTag == rawTagName) {
                val gradId = activeGradientAttrs["id"].orEmpty()
                if (gradId.isNotEmpty() && activeGradientStops.isNotEmpty()) {
                    gradients[gradId] = buildGradientDef(rawTagName, gradId, activeGradientAttrs, activeGradientStops.toList())
                }
                activeGradientTag = null
                activeGradientStops.clear()
            } else if (tagStack.isNotEmpty() && tagStack.last() == rawTagName) {
                tagStack.removeLast()
                if (styleStack.size > 1) styleStack.removeLast()
                if (transformStack.size > 1) transformStack.removeLast()
            }
            continue
        }

        val attrs = parseXmlAttributes(attrBody, cssRules, rawTagName)

        if (rawTagName == "defs" || rawTagName == "clippath" || rawTagName == "mask" || rawTagName == "symbol" || rawTagName == "marker") {
            if (!isSelfClosing) insideDefsDepth++
            continue
        }

        if (rawTagName == "lineargradient" || rawTagName == "radialgradient") {
            if (!isSelfClosing) {
                activeGradientTag = rawTagName
                activeGradientAttrs = attrs
                activeGradientStops.clear()
            }
            continue
        }

        if (rawTagName == "stop" && activeGradientTag != null) {
            val rawOffset = attrs["offset"]?.trim().orEmpty()
            val frac = when {
                rawOffset.endsWith("%") -> (rawOffset.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f
                else -> rawOffset.toFloatOrNull() ?: 0f
            }.coerceIn(0f, 1f)
            val stopColorStr = attrs["stop-color"] ?: "#000000"
            val stopOpacity = attrs["stop-opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
            val baseCol = parseSvgColorString(stopColorStr, defaultCurrentColor) ?: Color.Black
            activeGradientStops.add(frac to baseCol.copy(alpha = (baseCol.alpha * stopOpacity).coerceIn(0f, 1f)))
            continue
        }

        if (rawTagName == "svg") {
            val vb = attrs["viewbox"] ?: attrs["viewBox"]
            if (!vb.isNullOrBlank()) {
                val parts = vb.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                if (parts.size >= 4) {
                    minX = parts[0]
                    minY = parts[1]
                    vbW = parts[2]
                    vbH = parts[3]
                }
            }
            if (vbW <= 0f) vbW = parseSvgLength(attrs["width"], 800f).takeIf { it > 0f } ?: 800f
            if (vbH <= 0f) vbH = parseSvgLength(attrs["height"], 600f).takeIf { it > 0f } ?: 600f
            attrs["background"]?.let { svgBgColor = parseSvgColorString(it, defaultCurrentColor) }
            attrs["background-color"]?.let { svgBgColor = parseSvgColorString(it, defaultCurrentColor) }
        }

        if (insideDefsDepth > 0) continue

        val parentStyle = styleStack.last()
        val parentTransform = transformStack.last()
        val currentStyle = resolveChildSvgStyle(parentStyle, attrs, defaultCurrentColor)
        val localTransform = attrs["transform"]?.let { parseSvgTransform(it) } ?: Affine2D()
        val currentTransform = parentTransform.preConcat(localTransform)

        val shapePath: Path? = when (rawTagName) {
            "path" -> {
                val d = attrs["d"]?.trim().orEmpty()
                if (d.isNotEmpty()) {
                    runCatching {
                        PathParser().parsePathString(normalizeSvgPathArcFlags(d)).toPath()
                    }.getOrNull()
                } else null
            }
            "rect" -> {
                val x = parseSvgLength(attrs["x"], 0f)
                val y = parseSvgLength(attrs["y"], 0f)
                val w = parseSvgLength(attrs["width"], 0f, vbW)
                val h = parseSvgLength(attrs["height"], 0f, vbH)
                val rx = parseSvgLength(attrs["rx"], 0f)
                val ry = parseSvgLength(attrs["ry"], rx).let { if (it == 0f) rx else it }
                if (w > 0f && h > 0f) {
                    buildRectOrRoundRectPath(x, y, w, h, rx, ry)
                } else null
            }
            "circle" -> {
                val cx = parseSvgLength(attrs["cx"], 0f)
                val cy = parseSvgLength(attrs["cy"], 0f)
                val r = parseSvgLength(attrs["r"], 0f)
                if (r > 0f) buildEllipsePath(cx, cy, r, r) else null
            }
            "ellipse" -> {
                val cx = parseSvgLength(attrs["cx"], 0f)
                val cy = parseSvgLength(attrs["cy"], 0f)
                val rx = parseSvgLength(attrs["rx"], 0f)
                val ry = parseSvgLength(attrs["ry"], 0f)
                if (rx > 0f && ry > 0f) buildEllipsePath(cx, cy, rx, ry) else null
            }
            "line" -> {
                val x1 = parseSvgLength(attrs["x1"], 0f)
                val y1 = parseSvgLength(attrs["y1"], 0f)
                val x2 = parseSvgLength(attrs["x2"], 0f)
                val y2 = parseSvgLength(attrs["y2"], 0f)
                Path().apply {
                    moveTo(x1, y1)
                    lineTo(x2, y2)
                }
            }
            "polyline", "polygon" -> {
                val pts = attrs["points"]?.trim().orEmpty()
                if (pts.isNotEmpty()) {
                    buildPolyPath(pts, close = rawTagName == "polygon")
                } else null
            }
            else -> null
        }

        if (shapePath != null) {
            val effectiveStyle = if (rawTagName == "line") {
                currentStyle.copy(fill = SvgPaintSpec.None)
            } else {
                currentStyle
            }
            drawItems.add(SvgRenderItem(shapePath, effectiveStyle, currentTransform))
            if (drawItems.size > MAX_SVG_DRAW_ITEMS) return null
        }

        if (!isSelfClosing && (rawTagName == "svg" || rawTagName == "g" || rawTagName == "a")) {
            tagStack.addLast(rawTagName)
            styleStack.addLast(currentStyle)
            transformStack.addLast(currentTransform)
        }
    }

    if (vbW <= 0f) vbW = 800f
    if (vbH <= 0f) vbH = 600f

    return SvgSceneDocument(
        minX = minX,
        minY = minY,
        width = vbW,
        height = vbH,
        backgroundColor = svgBgColor,
        gradients = gradients,
        drawItems = drawItems
    )
}

private fun buildGradientDef(
    tagName: String,
    id: String,
    attrs: Map<String, String>,
    stops: List<Pair<Float, Color>>
): SvgGradientDef {
    val units = attrs["gradientunits"] ?: attrs["gradientUnits"] ?: "objectBoundingBox"
    val isObb = !units.equals("userSpaceOnUse", ignoreCase = true)
    return if (tagName == "radialgradient") {
        val cx = parseSvgGradCoord(attrs["cx"], if (isObb) 0.5f else 0f, isObb)
        val cy = parseSvgGradCoord(attrs["cy"], if (isObb) 0.5f else 0f, isObb)
        val r = parseSvgGradCoord(attrs["r"], if (isObb) 0.5f else 100f, isObb)
        SvgGradientDef.Radial(id, cx, cy, r, isObb, stops)
    } else {
        val x1 = parseSvgGradCoord(attrs["x1"], 0f, isObb)
        val y1 = parseSvgGradCoord(attrs["y1"], 0f, isObb)
        val x2 = parseSvgGradCoord(attrs["x2"], if (isObb) 1f else 100f, isObb)
        val y2 = parseSvgGradCoord(attrs["y2"], 0f, isObb)
        SvgGradientDef.Linear(id, x1, y1, x2, y2, isObb, stops)
    }
}

private fun parseSvgGradCoord(raw: String?, defaultVal: Float, isObb: Boolean): Float {
    if (raw.isNullOrBlank()) return defaultVal
    val t = raw.trim()
    return if (t.endsWith("%")) {
        val pct = (t.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f
        pct
    } else {
        t.toFloatOrNull() ?: defaultVal
    }
}

private fun parseXmlAttributes(
    attrBody: String,
    cssRules: Map<String, Map<String, String>>,
    tagName: String
): Map<String, String> {
    val map = mutableMapOf<String, String>()
    cssRules[tagName]?.let { map.putAll(it) }

    val attrRegex = SVG_ATTR_PAIR_REGEX
    for (m in attrRegex.findAll(attrBody)) {
        val key = m.groupValues[1].lowercase()
        val value = m.groupValues[2].ifEmpty { m.groupValues[3] }.trim()
        map[key] = value
    }

    map["class"]?.split(SVG_WHITESPACE_REGEX)?.forEach { cls ->
        if (cls.isNotEmpty()) {
            cssRules[".$cls"]?.let { map.putAll(it) }
            cssRules["$tagName.$cls"]?.let { map.putAll(it) }
        }
    }
    map["id"]?.takeIf { it.isNotEmpty() }?.let { idVal ->
        cssRules["#$idVal"]?.let { map.putAll(it) }
    }
    map["style"]?.let { inlineCss ->
        map.putAll(parseInlineCssDeclarations(inlineCss))
    }
    return map
}

private fun parseInlineCssDeclarations(css: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    for (part in css.split(';')) {
        val colon = part.indexOf(':')
        if (colon > 0) {
            val k = part.substring(0, colon).trim().lowercase()
            val v = part.substring(colon + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) {
                out[k] = v
            }
        }
    }
    return out
}

private fun resolveChildSvgStyle(
    parent: SvgInheritedStyle,
    attrs: Map<String, String>,
    currentColor: Color
): SvgInheritedStyle {
    val fill = attrs["fill"]?.let { parseSvgPaintSpec(it, currentColor) } ?: parent.fill
    val stroke = attrs["stroke"]?.let { parseSvgPaintSpec(it, currentColor) } ?: parent.stroke
    val strokeWidth = attrs["stroke-width"]?.let { parseSvgLength(it, parent.strokeWidth) } ?: parent.strokeWidth
    val strokeCap = when (attrs["stroke-linecap"]?.lowercase()) {
        "round" -> StrokeCap.Round
        "square" -> StrokeCap.Square
        "butt" -> StrokeCap.Butt
        else -> parent.strokeCap
    }
    val strokeJoin = when (attrs["stroke-linejoin"]?.lowercase()) {
        "round" -> StrokeJoin.Round
        "bevel" -> StrokeJoin.Bevel
        "miter" -> StrokeJoin.Miter
        else -> parent.strokeJoin
    }
    val dashArray = if (attrs.containsKey("stroke-dasharray")) {
        val raw = attrs["stroke-dasharray"].orEmpty().trim()
        if (raw.equals("none", ignoreCase = true) || raw.isEmpty()) null
        else raw.split(SVG_WS_COMMA_REGEX).mapNotNull { parseSvgLength(it, 0f).takeIf { v -> v > 0f } }.toFloatArray().takeIf { it.isNotEmpty() }
    } else {
        parent.strokeDashArray
    }
    val fillOpacity = attrs["fill-opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: parent.fillOpacity
    val strokeOpacity = attrs["stroke-opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: parent.strokeOpacity
    val elemOpacity = attrs["opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
    val fillRule = when (attrs["fill-rule"]?.lowercase()) {
        "evenodd" -> PathFillType.EvenOdd
        "nonzero" -> PathFillType.NonZero
        else -> parent.fillRule
    }
    return SvgInheritedStyle(
        fill = fill,
        stroke = stroke,
        strokeWidth = strokeWidth,
        strokeCap = strokeCap,
        strokeJoin = strokeJoin,
        strokeDashArray = dashArray,
        fillOpacity = fillOpacity,
        strokeOpacity = strokeOpacity,
        opacity = (parent.opacity * elemOpacity).coerceIn(0f, 1f),
        fillRule = fillRule
    )
}

private fun parseSvgPaintSpec(raw: String, currentColor: Color): SvgPaintSpec {
    val t = raw.trim()
    if (t.isEmpty() || t.equals("none", ignoreCase = true) || t.equals("transparent", ignoreCase = true)) {
        return SvgPaintSpec.None
    }
    if (t.startsWith("url(", ignoreCase = true)) {
        val id = t.substringAfter('(').substringBefore(')').trim().removeSurrounding("\"").removeSurrounding("'").removePrefix("#")
        return if (id.isNotEmpty()) SvgPaintSpec.GradientRef(id) else SvgPaintSpec.None
    }
    val col = parseSvgColorString(t, currentColor) ?: return SvgPaintSpec.None
    return if (col.alpha <= 0.001f) SvgPaintSpec.None else SvgPaintSpec.Solid(col)
}

internal fun parseSvgColorString(raw: String, currentColor: Color = Color.Black): Color? {
    val s = raw.trim().lowercase()
    if (s.isEmpty() || s == "none" || s == "transparent") return Color.Transparent
    if (s == "currentcolor" || s.startsWith("var(")) return currentColor

    if (s.startsWith("#")) {
        val hex = s.removePrefix("#")
        return runCatching {
            when (hex.length) {
                3 -> {
                    val r = hex[0].digitToInt(16) * 17
                    val g = hex[1].digitToInt(16) * 17
                    val b = hex[2].digitToInt(16) * 17
                    Color(r, g, b, 255)
                }
                4 -> {
                    val r = hex[0].digitToInt(16) * 17
                    val g = hex[1].digitToInt(16) * 17
                    val b = hex[2].digitToInt(16) * 17
                    val a = hex[3].digitToInt(16) * 17
                    Color(r, g, b, a)
                }
                6 -> {
                    val rgb = hex.toLong(16)
                    Color(0xFF000000L or rgb)
                }
                8 -> {
                    val rgba = hex.toLong(16)
                    val r = ((rgba shr 24) and 0xFF).toInt()
                    val g = ((rgba shr 16) and 0xFF).toInt()
                    val b = ((rgba shr 8) and 0xFF).toInt()
                    val a = (rgba and 0xFF).toInt()
                    Color(r, g, b, a)
                }
                else -> null
            }
        }.getOrNull()
    }

    if (s.startsWith("rgb")) {
        val inner = s.substringAfter('(').substringBefore(')')
        val parts = inner.split(SVG_WS_COMMA_SLASH_REGEX).filter { it.isNotEmpty() }
        if (parts.size >= 3) {
            fun parseChan(p: String): Int {
                return if (p.endsWith("%")) {
                    (((p.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f) * 255f).toInt().coerceIn(0, 255)
                } else {
                    (p.toFloatOrNull()?.toInt() ?: 0).coerceIn(0, 255)
                }
            }
            val r = parseChan(parts[0])
            val g = parseChan(parts[1])
            val b = parseChan(parts[2])
            val a = if (parts.size >= 4) {
                val ap = parts[3]
                val af = if (ap.endsWith("%")) (ap.removeSuffix("%").toFloatOrNull() ?: 100f) / 100f else (ap.toFloatOrNull() ?: 1f)
                (af.coerceIn(0f, 1f) * 255f).toInt()
            } else 255
            return Color(r, g, b, a)
        }
    }

    if (s.startsWith("hsl")) {
        val inner = s.substringAfter('(').substringBefore(')')
        val parts = inner.split(SVG_WS_COMMA_SLASH_REGEX).filter { it.isNotEmpty() }
        if (parts.size >= 3) {
            val h = ((parts[0].removeSuffix("deg").toFloatOrNull() ?: 0f) % 360f + 360f) % 360f
            val sat = ((parts[1].removeSuffix("%").toFloatOrNull() ?: 0f) / 100f).coerceIn(0f, 1f)
            val l = ((parts[2].removeSuffix("%").toFloatOrNull() ?: 0f) / 100f).coerceIn(0f, 1f)
            val a = if (parts.size >= 4) {
                val ap = parts[3]
                if (ap.endsWith("%")) ((ap.removeSuffix("%").toFloatOrNull() ?: 100f) / 100f).coerceIn(0f, 1f)
                else (ap.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
            } else 1f
            return Color.hsl(h, sat, l, a)
        }
    }

    return W3C_NAMED_SVG_COLORS[s]
}

private fun parseSvgLength(raw: String?, defaultVal: Float, containerSpan: Float = 100f): Float {
    if (raw.isNullOrBlank()) return defaultVal
    val s = raw.trim().lowercase()
    return when {
        s.endsWith("%") -> ((s.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f) * containerSpan
        s.endsWith("px") -> s.removeSuffix("px").toFloatOrNull() ?: defaultVal
        s.endsWith("pt") -> (s.removeSuffix("pt").toFloatOrNull() ?: defaultVal) * 1.3333f
        s.endsWith("em") || s.endsWith("rem") -> (s.removeSuffix("rem").removeSuffix("em").toFloatOrNull() ?: 1f) * 16f
        else -> s.toFloatOrNull() ?: defaultVal
    }
}

internal fun parseSvgTransform(raw: String): Affine2D {
    var result = Affine2D()
    val fnRegex = SVG_TRANSFORM_FN_REGEX
    for (m in fnRegex.findAll(raw)) {
        val fn = m.groupValues[1].lowercase()
        val args = m.groupValues[2].trim().split(SVG_WS_COMMA_REGEX).mapNotNull { it.toFloatOrNull() }
        if (args.isEmpty()) continue
        val step = when (fn) {
            "translate" -> {
                val tx = args[0]
                val ty = args.getOrElse(1) { 0f }
                Affine2D(tx = tx, ty = ty)
            }
            "scale" -> {
                val sx = args[0]
                val sy = args.getOrElse(1) { sx }
                Affine2D(a = sx, d = sy)
            }
            "rotate" -> {
                val rad = (args[0] * PI / 180.0).toFloat()
                val cosA = cos(rad)
                val sinA = sin(rad)
                val rot = Affine2D(a = cosA, b = sinA, c = -sinA, d = cosA)
                if (args.size >= 3) {
                    val cx = args[1]
                    val cy = args[2]
                    Affine2D(tx = cx, ty = cy).preConcat(rot).preConcat(Affine2D(tx = -cx, ty = -cy))
                } else {
                    rot
                }
            }
            "skewx" -> {
                val rad = (args[0] * PI / 180.0).toFloat()
                Affine2D(c = tan(rad))
            }
            "skewy" -> {
                val rad = (args[0] * PI / 180.0).toFloat()
                Affine2D(b = tan(rad))
            }
            "matrix" -> {
                if (args.size >= 6) {
                    Affine2D(a = args[0], b = args[1], c = args[2], d = args[3], tx = args[4], ty = args[5])
                } else Affine2D()
            }
            else -> Affine2D()
        }
        result = result.preConcat(step)
    }
    return result
}

private fun buildRectOrRoundRectPath(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float): Path {
    if (rx <= 0f || ry <= 0f) {
        return Path().apply {
            moveTo(x, y)
            lineTo(x + w, y)
            lineTo(x + w, y + h)
            lineTo(x, y + h)
            close()
        }
    }
    val crx = min(rx, w * 0.5f)
    val cry = min(ry, h * 0.5f)
    val k = 0.55228475f
    val ox = crx * k
    val oy = cry * k
    return Path().apply {
        moveTo(x + crx, y)
        lineTo(x + w - crx, y)
        cubicTo(x + w - crx + ox, y, x + w, y + cry - oy, x + w, y + cry)
        lineTo(x + w, y + h - cry)
        cubicTo(x + w, y + h - cry + oy, x + w - crx + ox, y + h, x + w - crx, y + h)
        lineTo(x + crx, y + h)
        cubicTo(x + crx - ox, y + h, x, y + h - cry + oy, x, y + h - cry)
        lineTo(x, y + cry)
        cubicTo(x, y + cry - oy, x + crx - ox, y, x + crx, y)
        close()
    }
}

private fun buildEllipsePath(cx: Float, cy: Float, rx: Float, ry: Float): Path {
    val k = 0.55228475f
    val ox = rx * k
    val oy = ry * k
    return Path().apply {
        moveTo(cx, cy - ry)
        cubicTo(cx + ox, cy - ry, cx + rx, cy - oy, cx + rx, cy)
        cubicTo(cx + rx, cy + oy, cx + ox, cy + ry, cx, cy + ry)
        cubicTo(cx - ox, cy + ry, cx - rx, cy + oy, cx - rx, cy)
        cubicTo(cx - rx, cy - oy, cx - ox, cy - ry, cx, cy - ry)
        close()
    }
}

private fun buildPolyPath(pointsStr: String, close: Boolean): Path? {
    val nums = SVG_NUMBER_TOKEN_REGEX
        .findAll(pointsStr)
        .mapNotNull { it.value.toFloatOrNull() }
        .toList()
    if (nums.size < 4) return null
    return Path().apply {
        moveTo(nums[0], nums[1])
        var idx = 2
        while (idx + 1 < nums.size) {
            lineTo(nums[idx], nums[idx + 1])
            idx += 2
        }
        if (close) close()
    }
}

private val W3C_NAMED_SVG_COLORS: Map<String, Color> = mapOf(
    "aliceblue" to Color(0xFFF0F8FF),
    "antiquewhite" to Color(0xFFFAEBD7),
    "aqua" to Color(0xFF00FFFF),
    "aquamarine" to Color(0xFF7FFFD4),
    "azure" to Color(0xFFF0FFFF),
    "beige" to Color(0xFFF5F5DC),
    "bisque" to Color(0xFFFFE4C4),
    "black" to Color(0xFF000000),
    "blanchedalmond" to Color(0xFFFFEBCD),
    "blue" to Color(0xFF0000FF),
    "blueviolet" to Color(0xFF8A2BE2),
    "brown" to Color(0xFFA52A2A),
    "burlywood" to Color(0xFFDEB887),
    "cadetblue" to Color(0xFF5F9EA0),
    "chartreuse" to Color(0xFF7FFF00),
    "chocolate" to Color(0xFFD2691E),
    "coral" to Color(0xFFFF7F50),
    "cornflowerblue" to Color(0xFF6495ED),
    "cornsilk" to Color(0xFFFFF8DC),
    "crimson" to Color(0xFFDC143C),
    "cyan" to Color(0xFF00FFFF),
    "darkblue" to Color(0xFF00008B),
    "darkcyan" to Color(0xFF008B8B),
    "darkgoldenrod" to Color(0xFFB8860B),
    "darkgray" to Color(0xFFA9A9A9),
    "darkgreen" to Color(0xFF006400),
    "darkgrey" to Color(0xFFA9A9A9),
    "darkkhaki" to Color(0xFFBDB76B),
    "darkmagenta" to Color(0xFF8B008B),
    "darkolivegreen" to Color(0xFF556B2F),
    "darkorange" to Color(0xFFFF8C00),
    "darkorchid" to Color(0xFF9932CC),
    "darkred" to Color(0xFF8B0000),
    "darksalmon" to Color(0xFFE9967A),
    "darkseagreen" to Color(0xFF8FBC8F),
    "darkslateblue" to Color(0xFF483D8B),
    "darkslategray" to Color(0xFF2F4F4F),
    "darkslategrey" to Color(0xFF2F4F4F),
    "darkturquoise" to Color(0xFF00CED1),
    "darkviolet" to Color(0xFF9400D3),
    "deeppink" to Color(0xFFFF1493),
    "deepskyblue" to Color(0xFF00BFFF),
    "dimgray" to Color(0xFF696969),
    "dimgrey" to Color(0xFF696969),
    "dodgerblue" to Color(0xFF1E90FF),
    "firebrick" to Color(0xFFB22222),
    "floralwhite" to Color(0xFFFFFAF0),
    "forestgreen" to Color(0xFF228B22),
    "fuchsia" to Color(0xFFFF00FF),
    "gainsboro" to Color(0xFFDCDCDC),
    "ghostwhite" to Color(0xFFF8F8FF),
    "gold" to Color(0xFFFFD700),
    "goldenrod" to Color(0xFFDAA520),
    "gray" to Color(0xFF808080),
    "green" to Color(0xFF008000),
    "greenyellow" to Color(0xFFADFF2F),
    "grey" to Color(0xFF808080),
    "honeydew" to Color(0xFFF0FFF0),
    "hotpink" to Color(0xFFFF69B4),
    "indianred" to Color(0xFFCD5C5C),
    "indigo" to Color(0xFF4B0082),
    "ivory" to Color(0xFFFFFFF0),
    "khaki" to Color(0xFFF0E68C),
    "lavender" to Color(0xFFE6E6FA),
    "lavenderblush" to Color(0xFFFFF0F5),
    "lawngreen" to Color(0xFF7CFC00),
    "lemonchiffon" to Color(0xFFFFFACD),
    "lightblue" to Color(0xFFADD8E6),
    "lightcoral" to Color(0xFFF08080),
    "lightcyan" to Color(0xFFE0FFFF),
    "lightgoldenrodyellow" to Color(0xFFFAFAD2),
    "lightgray" to Color(0xFFD3D3D3),
    "lightgreen" to Color(0xFF90EE90),
    "lightgrey" to Color(0xFFD3D3D3),
    "lightpink" to Color(0xFFFFB6C1),
    "lightsalmon" to Color(0xFFFFA07A),
    "lightseagreen" to Color(0xFF20B2AA),
    "lightskyblue" to Color(0xFF87CEFA),
    "lightslategray" to Color(0xFF778899),
    "lightslategrey" to Color(0xFF778899),
    "lightsteelblue" to Color(0xFFB0C4DE),
    "lightyellow" to Color(0xFFFFFFE0),
    "lime" to Color(0xFF00FF00),
    "limegreen" to Color(0xFF32CD32),
    "linen" to Color(0xFFFAF0E6),
    "magenta" to Color(0xFFFF00FF),
    "maroon" to Color(0xFF800000),
    "mediumaquamarine" to Color(0xFF66CDAA),
    "mediumblue" to Color(0xFF0000CD),
    "mediumorchid" to Color(0xFFBA55D3),
    "mediumpurple" to Color(0xFF9370DB),
    "mediumseagreen" to Color(0xFF3CB371),
    "mediumslateblue" to Color(0xFF7B68EE),
    "mediumspringgreen" to Color(0xFF00FA9A),
    "mediumturquoise" to Color(0xFF48D1CC),
    "mediumvioletred" to Color(0xFFC71585),
    "midnightblue" to Color(0xFF191970),
    "mintcream" to Color(0xFFF5FFFA),
    "mistyrose" to Color(0xFFFFE4E1),
    "moccasin" to Color(0xFFFFE4B5),
    "navajowhite" to Color(0xFFFFDEAD),
    "navy" to Color(0xFF000080),
    "oldlace" to Color(0xFFFDF5E6),
    "olive" to Color(0xFF808000),
    "olivedrab" to Color(0xFF6B8E23),
    "orange" to Color(0xFFFFA500),
    "orangered" to Color(0xFFFF4500),
    "orchid" to Color(0xFFDA70D6),
    "palegoldenrod" to Color(0xFFEEE8AA),
    "palegreen" to Color(0xFF98FB98),
    "paleturquoise" to Color(0xFFAFEEEE),
    "palevioletred" to Color(0xFFDB7093),
    "papayawhip" to Color(0xFFFFEFD5),
    "peachpuff" to Color(0xFFFFDAB9),
    "peru" to Color(0xFFCD853F),
    "pink" to Color(0xFFFFC0CB),
    "plum" to Color(0xFFDDA0DD),
    "powderblue" to Color(0xFFB0E0E6),
    "purple" to Color(0xFF800080),
    "rebeccapurple" to Color(0xFF663399),
    "red" to Color(0xFFFF0000),
    "rosybrown" to Color(0xFFBC8F8F),
    "royalblue" to Color(0xFF4169E1),
    "saddlebrown" to Color(0xFF8B4513),
    "salmon" to Color(0xFFFA8072),
    "sandybrown" to Color(0xFFF4A460),
    "seagreen" to Color(0xFF2E8B57),
    "seashell" to Color(0xFFFFF5EE),
    "sienna" to Color(0xFFA0522D),
    "silver" to Color(0xFFC0C0C0),
    "skyblue" to Color(0xFF87CEEB),
    "slateblue" to Color(0xFF6A5ACD),
    "slategray" to Color(0xFF708090),
    "slategrey" to Color(0xFF708090),
    "snow" to Color(0xFFFFFAFA),
    "springgreen" to Color(0xFF00FF7F),
    "steelblue" to Color(0xFF4682B4),
    "tan" to Color(0xFFD2B48C),
    "teal" to Color(0xFF008080),
    "thistle" to Color(0xFFD8BFD8),
    "tomato" to Color(0xFFFF6347),
    "turquoise" to Color(0xFF40E0D0),
    "violet" to Color(0xFFEE82EE),
    "wheat" to Color(0xFFF5DEB3),
    "white" to Color(0xFFFFFFFF),
    "whitesmoke" to Color(0xFFF5F5F5),
    "yellow" to Color(0xFFFFFF00),
    "yellowgreen" to Color(0xFF9ACD32)
)
