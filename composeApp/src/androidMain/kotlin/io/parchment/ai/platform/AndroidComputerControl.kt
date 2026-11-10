package io.parchment.ai.platform

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import io.parchment.ai.ParchmentAccessibilityService
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_SCREENSHOT_WIDTH = 1080
private const val SCROLL_STEP_PX = 120f

/** Works only after the user turns on Parchment in Android's Accessibility settings. */
internal object AndroidComputerControl : ComputerControl {
    @Volatile private var scale = 0.0
    @Volatile private var screenWidth = 0
    @Volatile private var screenHeight = 0

    private val service: ParchmentAccessibilityService
        get() = checkNotNull(ParchmentAccessibilityService.instance) { "Turn on Parchment in Accessibility settings first." }

    override val isAvailable: Boolean get() = ParchmentAccessibilityService.instance != null

    override fun screenshot(): Result<ComputerScreenshot> = runCatching {
        val full = checkNotNull(service.capture()) { "This Android version or screen does not allow screenshots." }
        val factor = min(1.0, MAX_SCREENSHOT_WIDTH.toDouble() / full.width)
        val width = (full.width * factor).roundToInt()
        val height = (full.height * factor).roundToInt()
        val scaled = Bitmap.createScaledBitmap(full, width, height, true)
        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
        screenWidth = full.width
        screenHeight = full.height
        scale = factor
        if (scaled !== full) scaled.recycle()
        full.recycle()
        ComputerScreenshot(Base64.encodeToString(bytes, Base64.NO_WRAP), width, height)
    }

    private fun point(action: ComputerAction): Pair<Float, Float> {
        check(scale > 0.0) { "Take a screenshot first." }
        val x = (checkNotNull(action.x) { "That action needs a point." } / scale).toFloat()
        val y = (checkNotNull(action.y) { "That action needs a point." } / scale).toFloat()
        check(x >= 0f && y >= 0f && x < screenWidth && y < screenHeight) { "That point is outside the screen." }
        return x to y
    }

    private fun Boolean.orFail(what: String) = check(this) { "$what did not work." }

    override fun act(action: ComputerAction): Result<String> = runCatching {
        val target = service
        when (action.action) {
            "click" -> point(action).let { (x, y) -> target.press(x, y, 60).orFail("The tap") }
            "double_click" -> point(action).let { (x, y) ->
                target.press(x, y, 60).orFail("The tap")
                target.press(x, y, 60).orFail("The tap")
            }
            "right_click" -> point(action).let { (x, y) -> target.press(x, y, 700).orFail("The long press") }
            "scroll" -> {
                check(scale > 0.0) { "Take a screenshot first." }
                val x = action.x?.let { (it / scale).toFloat() } ?: (screenWidth / 2f)
                val y = action.y?.let { (it / scale).toFloat() } ?: (screenHeight / 2f)
                target.swipe(x, y, x, (y - action.amount * SCROLL_STEP_PX).coerceIn(1f, screenHeight - 1f)).orFail("The swipe")
            }
            "type" -> target.enterText(action.text).orFail("Typing (tap a text field first)")
            "key" -> {
                val global = when (action.keys.lowercase()) {
                    "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    else -> error("On Android use back, home, recents or notifications.")
                }
                target.performGlobalAction(global).orFail("That key")
            }
            "move" -> error("A phone has no pointer to move.")
            else -> error("Unknown action.")
        }
        "Done: ${action.action}."
    }
}
