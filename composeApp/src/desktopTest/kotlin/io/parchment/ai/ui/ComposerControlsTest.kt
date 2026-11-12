package io.parchment.ai.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ComposerControlsTest {
    private inline fun ImageComposeScene.use(block: (ImageComposeScene) -> Unit) { try { block(this) } finally { closeIgnoringRectList() } }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }

    private fun settle(scene: ImageComposeScene, frame: Long) {
        Snapshot.sendApplyNotifications()
        scene.render(frame).close()
    }

    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    private fun open(scene: ImageComposeScene, chip: String) {
        val node = nodes(scene).first { text(it) == chip || text(it).startsWith(chip) }
        val clickable = generateSequence(node) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
        assertTrue(requireNotNull(clickable.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
        settle(scene, 2_000_000_000L)
    }

    private fun slider(scene: ImageComposeScene) = nodes(scene).single { it.config.contains(SemanticsActions.SetProgress) }
    private fun setSlider(scene: ImageComposeScene, target: Float) {
        assertTrue(requireNotNull(slider(scene).config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(target))
        settle(scene, 3_000_000_000L)
    }

    private fun scene(content: @androidx.compose.runtime.Composable () -> Unit) =
        ImageComposeScene(900, 700, Density(1f)) {
            CompositionLocalProvider(LocalParchmentPalette provides buildPaletteFromConfig(io.parchment.ai.model.ThemeConfig())) { content() }
        }

    @Test fun theThinkingSliderSendsExactStopsAndShowsCustomValues() {
        var value by mutableStateOf("none")
        scene { ThinkingControl("🧠 Thinking", value) { value = it } }.use { scene ->
            settle(scene, 1_000_000_000L)
            open(scene, "Thinking")
            setSlider(scene, 3f)
            assertEquals("high", value)
            assertEquals("High", slider(scene).config.getOrNull(SemanticsProperties.StateDescription))
            setSlider(scene, 5f)
            assertEquals("max", value)
            setSlider(scene, 99f)
            assertEquals("max", value, "Values stay on the slider")
            value = "budget-4096"
            settle(scene, 4_000_000_000L)
            assertEquals("budget-4096", slider(scene).config.getOrNull(SemanticsProperties.StateDescription), "An unusual saved value is kept and shown")
        }
    }

    @Test fun theDurationSliderCoversEverySecond() {
        var seconds by mutableStateOf(5)
        scene { DurationControl(seconds) { seconds = it } }.use { scene ->
            settle(scene, 1_000_000_000L)
            open(scene, "5s")
            setSlider(scene, 0f)
            assertEquals(4, seconds)
            setSlider(scene, 11f)
            assertEquals(15, seconds)
        }
    }

    @Test fun aspectTilesPickTheirShape() {
        var ratio by mutableStateOf("1:1")
        scene { AspectControl("🖼", ratio, IMAGE_ASPECTS) { ratio = it } }.use { scene ->
            settle(scene, 1_000_000_000L)
            open(scene, "1:1")
            open(scene, "21:9")
            assertEquals("21:9", ratio)
        }
    }

    @Test fun onlyRealRatiosAreDrawn() {
        assertEquals(21f to 9f, parseAspect("21:9"))
        assertEquals(1024f to 1536f, parseAspect("1024x1536"))
        listOf("", "auto", "1:0", "-1:2", "a:b", "1:2:3", "Infinity:1").forEach { assertNull(parseAspect(it), it) }
    }

    @Test fun positionsMapToTheNearestStop() {
        assertEquals(0, sliderIndexAt(-50f, 100f, 10f, 6))
        assertEquals(5, sliderIndexAt(500f, 100f, 10f, 6))
        assertEquals(2, sliderIndexAt(42f, 100f, 10f, 6))
        assertEquals(0, sliderIndexAt(50f, 0f, 0f, 1))
    }
}
