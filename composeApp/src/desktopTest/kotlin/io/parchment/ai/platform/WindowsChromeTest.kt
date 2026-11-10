package io.parchment.ai.platform

import com.sun.jna.Native
import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsChromeTest {
    @Test fun nativeCaptionCallsAreAcceptedByWindows() {
        val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        if (!onWindows || GraphicsEnvironment.isHeadless()) return
        val frame = Frame()
        try {
            frame.addNotify()
            val results = WindowsChrome.applyCaption(Native.getComponentID(frame), 0xF4ECD8, 0x261C14, 0xD5C4A1, dark = false)
            assertEquals(0, results["hideCaption"], "caption text must be hidden: $results")
            assertEquals(0, results["darkMode"], "caption buttons must follow the theme: $results")
        } finally { frame.dispose() }
    }
}
