package io.parchment.ai.ui

import androidx.compose.ui.input.key.KeyEvent

/**
 * Hands a key event to the app's shortcuts (Ctrl+K, Ctrl+N, Ctrl+, , Ctrl+B and Escape) from outside the composition.
 *
 * Compose delivers key events to a modifier only while something inside it has focus, so the root handler misses
 * every shortcut at launch or after a click on empty space. A desktop window passes its unconsumed key events here,
 * and [ParchmentApp] keeps [handler] pointed at the same function its root uses, so the list lives in one place.
 */
class AppShortcutDispatcher {
    internal var handler: (KeyEvent) -> Boolean = { false }

    /** Runs the matching shortcut and returns true if one ran. */
    fun dispatch(event: KeyEvent): Boolean = handler(event)
}
