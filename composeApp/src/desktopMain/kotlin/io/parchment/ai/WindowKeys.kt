package io.parchment.ai

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.parchment.ai.platform.PlatformPickers
import io.parchment.ai.ui.AppShortcutDispatcher

/**
 * The window's `onKeyEvent`: it runs only for keys nothing focused consumed. Pasted files become attachments, and
 * the app's shortcuts run here too, because with nothing focused the app's own root handler never sees the key.
 */
internal fun handleUnconsumedWindowKey(event: KeyEvent, shortcuts: AppShortcutDispatcher): Boolean {
    val isPasteShortcut = event.type == KeyEventType.KeyDown &&
        !event.isAltPressed &&
        (((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.V) ||
            (event.isShiftPressed && event.key == Key.Insert))
    if (isPasteShortcut) {
        val pastedFiles = PlatformPickers.readClipboardFiles()
        if (pastedFiles.isNotEmpty()) {
            PlatformPickers.filesDroppedCallback?.invoke(pastedFiles)
            return true
        }
        return false
    }
    return shortcuts.dispatch(event)
}
