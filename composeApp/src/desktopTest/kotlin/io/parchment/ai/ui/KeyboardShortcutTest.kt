package io.parchment.ai.ui

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.parchment.ai.handleUnconsumedWindowKey
import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shortcuts must work with nothing focused: at launch and after a click on empty space (2026-11-12 regression). */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class KeyboardShortcutTest {
    /** Keeps the app's coroutines on the rendering thread, as they are in a real window. */
    private class RenderThreadDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (true) (tasks.poll() ?: break).run() }
    }
    private val renderThread = RenderThreadDispatcher()
    private var frame = 0L

    private fun settle(scene: ImageComposeScene, frames: Int = 40) = repeat(frames) {
        renderThread.drain()
        Snapshot.sendApplyNotifications()
        frame += 16_000_000L
        scene.render(frame).close()
        Thread.sleep(5)
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }

    private fun anythingFocused(scene: ImageComposeScene) =
        nodes(scene).any { it.config.getOrNull(SemanticsProperties.Focused) == true }

    private fun paletteOpen(scene: ImageComposeScene) =
        nodes(scene).any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == Strings.typeACommandOrA } }

    /** What the desktop window does with a key: the focused element first, then the window's own fallback. */
    private fun pressInWindow(scene: ImageComposeScene, shortcuts: AppShortcutDispatcher, key: Key, ctrl: Boolean) {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            val event = KeyEvent(key, type, isCtrlPressed = ctrl)
            scene.sendKeyEvent(event) || handleUnconsumedWindowKey(event, shortcuts)
        }
        settle(scene)
    }

    @Test fun ctrlKOpensThePaletteWithNothingFocused() {
        PlatformBridge.saveAppStateJson(compactStateJson.encodeToString(createDefaultAppState().copy(autoCheckOtaOnStartup = false)))
        parchmentProcessState.snapshot.value = loadFastInitialAppState().first
        val shortcuts = AppShortcutDispatcher()
        val scene = ImageComposeScene(1360, 860, density = Density(1f), coroutineContext = renderThread)
        try {
            scene.setContent { ParchmentApp(shortcutDispatcher = shortcuts) }
            settle(scene, 120)
            assertFalse(anythingFocused(scene), "The test needs a window where nothing has focus, as at launch")
            assertFalse(paletteOpen(scene))

            pressInWindow(scene, shortcuts, Key.K, ctrl = true)
            assertTrue(paletteOpen(scene), "Ctrl+K must open the command palette when nothing has focus")

            pressInWindow(scene, shortcuts, Key.Escape, ctrl = false)
            assertFalse(paletteOpen(scene), "Escape must still close the palette")
        } finally {
            scene.closeIgnoringRectList()
            PlatformBridge.saveAppStateJson(compactStateJson.encodeToString(createDefaultAppState()))
        }
    }
}
