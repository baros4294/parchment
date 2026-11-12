package io.parchment.ai.ui

import androidx.compose.ui.ImageComposeScene

private fun IllegalArgumentException.isRectListBookkeeping() = message?.contains("RectList") == true

/**
 * Compose 1.12 sometimes fails inside its own layout bookkeeping ("LayoutNode … not found in RectList") while a
 * render lays out the tree (about half of the gallery runs, 2026-11-11). Runs [attempt] again once when that one
 * error escapes; [attempt] must build its own scene and state. Anything else, or a second failure, still fails the
 * test. TODO.md tracks it for the real app.
 */
internal fun retryOnceOnRectList(attempt: () -> Unit) {
    try {
        attempt()
    } catch (e: IllegalArgumentException) {
        if (!e.isRectListBookkeeping()) throw e
        attempt()
    }
}

/**
 * Closes a test scene. The same Compose 1.12 error also escapes from `close()` itself, while disposing the
 * composition (`RectManager.remove` from `LayoutNode.detach`; CI 2026-11-12, on both attempts of a retried scene).
 * By then every check has run and the scene is being thrown away, so a retry cannot help and only that error, only
 * here, is ignored. A failure in any render or assertion still fails the test.
 */
internal fun ImageComposeScene.closeIgnoringRectList() {
    try {
        close()
    } catch (e: IllegalArgumentException) {
        if (!e.isRectListBookkeeping()) throw e
    }
}
