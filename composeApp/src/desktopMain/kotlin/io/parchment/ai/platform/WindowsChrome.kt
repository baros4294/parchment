package io.parchment.ai.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Themes the real Windows caption, which keeps native animations, Snap Layouts and rounded corners.
 * The caption shows the app icon and the window title (the open chat), drawn in the theme's ink.
 */
internal object WindowsChrome {
    private const val DARK_MODE = 20
    private const val CORNERS = 33
    private const val BORDER_COLOR = 34
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36
    private const val ROUND_CORNERS = 2

    private interface Dwm : Library {
        fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: IntArray, size: Int): Int
    }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val dwm by lazy { Native.load("dwmapi", Dwm::class.java) }

    private fun colorRef(argb: Int): Int =
        ((argb and 0xFF) shl 16) or (argb and 0xFF00) or ((argb shr 16) and 0xFF)

    /** Returns each call's result code, 0 meaning success. Older Windows builds decline the color attributes. */
    fun applyCaption(handle: Long, captionArgb: Int, inkArgb: Int, borderArgb: Int, dark: Boolean): Map<String, Int> {
        if (!isWindows || handle == 0L) return emptyMap()
        return runCatching {
            val window = Pointer(handle)
            fun attribute(id: Int, value: Int) = dwm.DwmSetWindowAttribute(window, id, intArrayOf(value), Int.SIZE_BYTES)
            mapOf(
                "darkMode" to attribute(DARK_MODE, if (dark) 1 else 0),
                "corners" to attribute(CORNERS, ROUND_CORNERS),
                "caption" to attribute(CAPTION_COLOR, colorRef(captionArgb)),
                "text" to attribute(TEXT_COLOR, colorRef(inkArgb)),
                "border" to attribute(BORDER_COLOR, colorRef(borderArgb))
            )
        }.getOrDefault(emptyMap())
    }
}
