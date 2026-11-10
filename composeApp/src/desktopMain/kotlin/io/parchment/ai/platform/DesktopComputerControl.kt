package io.parchment.ai.platform

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_SCREENSHOT_WIDTH = 1280

private val NAMED_KEYS = mapOf(
    "enter" to KeyEvent.VK_ENTER, "return" to KeyEvent.VK_ENTER, "tab" to KeyEvent.VK_TAB, "esc" to KeyEvent.VK_ESCAPE, "escape" to KeyEvent.VK_ESCAPE,
    "backspace" to KeyEvent.VK_BACK_SPACE, "delete" to KeyEvent.VK_DELETE, "space" to KeyEvent.VK_SPACE, "up" to KeyEvent.VK_UP, "down" to KeyEvent.VK_DOWN,
    "left" to KeyEvent.VK_LEFT, "right" to KeyEvent.VK_RIGHT, "home" to KeyEvent.VK_HOME, "end" to KeyEvent.VK_END, "pageup" to KeyEvent.VK_PAGE_UP,
    "pagedown" to KeyEvent.VK_PAGE_DOWN, "ctrl" to KeyEvent.VK_CONTROL, "control" to KeyEvent.VK_CONTROL, "shift" to KeyEvent.VK_SHIFT, "alt" to KeyEvent.VK_ALT,
    "win" to KeyEvent.VK_WINDOWS, "windows" to KeyEvent.VK_WINDOWS, "meta" to KeyEvent.VK_META
) + (1..12).associate { "f$it" to KeyEvent.VK_F1 + it - 1 }

/** Turns text like `ctrl+shift+a` into key codes, or null if any part is unknown. */
internal fun parseKeyCombination(keys: String): List<Int>? = keys.lowercase().split('+').map { it.trim() }.map { key ->
    NAMED_KEYS[key] ?: key.singleOrNull()?.let { KeyEvent.getExtendedKeyCodeForChar(it.code) }?.takeIf { it != KeyEvent.VK_UNDEFINED } ?: return null
}

/** Maps a point in the screenshot to the screen, refusing anything outside it. */
internal fun mapScreenshotPoint(x: Int, y: Int, scale: Double, screenWidth: Int, screenHeight: Int): Pair<Int, Int>? {
    if (scale <= 0.0) return null
    val screenX = (x / scale).roundToInt()
    val screenY = (y / scale).roundToInt()
    return (screenX to screenY).takeIf { screenX in 0 until screenWidth && screenY in 0 until screenHeight }
}

/** The primary display only. Input goes through the operating system like a person's would. */
internal object DesktopComputerControl : ComputerControl {
    private val robot by lazy { Robot().apply { autoDelay = 40 } }
    @Volatile private var scale = 0.0

    override val isAvailable: Boolean get() = !GraphicsEnvironment.isHeadless()

    override fun screenshot(): Result<ComputerScreenshot> = runCatching {
        val size = Toolkit.getDefaultToolkit().screenSize
        val capture = robot.createScreenCapture(Rectangle(size))
        val factor = min(1.0, MAX_SCREENSHOT_WIDTH.toDouble() / size.width)
        val width = (size.width * factor).roundToInt()
        val height = (size.height * factor).roundToInt()
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(capture, 0, 0, width, height, null)
            dispose()
        }
        val bytes = ByteArrayOutputStream().also { check(ImageIO.write(scaled, "jpg", it)) { "JPEG encoding is unavailable." } }.toByteArray()
        scale = factor
        ComputerScreenshot(Base64.getEncoder().encodeToString(bytes), width, height)
    }

    private fun pressCombination(codes: List<Int>) {
        val pressed = ArrayDeque<Int>()
        try {
            codes.forEach { robot.keyPress(it); pressed.addLast(it) }
        } finally {
            while (pressed.isNotEmpty()) robot.keyRelease(pressed.removeLast())
        }
    }

    private fun click(mask: Int, times: Int) = repeat(times) {
        robot.mousePress(mask)
        robot.mouseRelease(mask)
    }

    /** Pasting is the only way to enter every language's characters reliably. */
    private fun paste(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previous = runCatching { clipboard.getContents(null) }.getOrNull()
        clipboard.setContents(StringSelection(text), null)
        pressCombination(listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_V))
        Thread.sleep(150)
        previous?.let { contents -> runCatching { clipboard.setContents(contents, null) } }
    }

    override fun act(action: ComputerAction): Result<String> = runCatching {
        if (action.x != null && action.y != null) {
            val size = Toolkit.getDefaultToolkit().screenSize
            check(scale > 0.0) { "Take a screenshot first." }
            val (x, y) = mapScreenshotPoint(action.x, action.y, scale, size.width, size.height) ?: error("That point is outside the screen.")
            robot.mouseMove(x, y)
        } else {
            check(action.action !in setOf("click", "double_click", "right_click", "move")) { "That action needs a point." }
        }
        when (action.action) {
            "click" -> click(InputEvent.BUTTON1_DOWN_MASK, 1)
            "double_click" -> click(InputEvent.BUTTON1_DOWN_MASK, 2)
            "right_click" -> click(InputEvent.BUTTON3_DOWN_MASK, 1)
            "move" -> Unit
            "scroll" -> robot.mouseWheel(action.amount)
            "type" -> paste(action.text)
            "key" -> pressCombination(parseKeyCombination(action.keys) ?: error("Unknown keys: ${action.keys}"))
            else -> error("Unknown action.")
        }
        "Done: ${action.action}."
    }
}
