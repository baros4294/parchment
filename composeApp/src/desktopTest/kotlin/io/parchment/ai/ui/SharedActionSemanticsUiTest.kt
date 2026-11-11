package io.parchment.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.parchment.ai.model.ThemeConfig
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class SharedActionSemanticsUiTest {
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun text(node: SemanticsNode) =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
    private fun description(node: SemanticsNode) =
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
    private fun click(node: SemanticsNode) =
        assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())

    private fun withScene(
        width: Int = 320, height: Int = 320, fontScale: Float = 1f,
        content: @Composable () -> Unit, check: (ImageComposeScene) -> Unit
    ) {
        val scene = ImageComposeScene(width, height, density = Density(1f, fontScale), coroutineContext = Dispatchers.Unconfined)
        try {
            scene.setContent {
                CompositionLocalProvider(LocalParchmentWindowSize provides ParchmentWindowSize(width.dp, height.dp)) {
                    Column(Modifier.fillMaxSize().background(LocalParchmentPalette.current.canvasBg)) { content() }
                }
            }
            scene.render(0L).close()
            check(scene)
        } finally { scene.close() }
    }

    /** Publishes state written outside composition before rendering, so one frame observes it. */
    private fun settle(scene: ImageComposeScene, frameNanos: Long) {
        Snapshot.sendApplyNotifications()
        scene.render(frameNanos).close()
    }

    private fun save(scene: ImageComposeScene, name: String) {
        val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "TODO.md").isFile }
        val file = File(repo, ".parchment-local/verification/actions/$name.png")
        file.parentFile.mkdirs()
        scene.render(64_000_000L).use { image -> file.writeBytes(requireNotNull(image.encodeToData()).bytes) }
    }

    private fun tap(scene: ImageComposeScene, x: Float, y: Float) {
        val position = Offset(x, y)
        scene.sendPointerEvent(PointerEventType.Move, position, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, position, type = PointerType.Mouse,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        scene.render(16_000_000L).close()
        scene.sendPointerEvent(PointerEventType.Release, position, type = PointerType.Mouse,
            buttons = PointerButtons(isPrimaryPressed = false), button = PointerButton.Primary)
        scene.render(32_000_000L).close()
    }

    @Test fun iconOnlyActionsAreNamedAndChipsExposeSelection() {
        var removals = 0
        val chipSelected = mutableStateOf(false)
        withScene(content = {
            SepiaButton("×", { removals++ }, compact = true, contentDescription = "Remove attachment")
            SepiaButton("⚙", {}, compact = true)
            SepiaButton("Save draft", {})
            SepiaChip("🎯 Goal Mode", chipSelected.value, { chipSelected.value = !chipSelected.value })
            SepiaChip("📎", false, {}, contentDescription = "Attach files")
        }) { scene ->
            val named = nodes(scene).single { description(it) == "Remove attachment" }
            assertEquals(Role.Button, named.config[SemanticsProperties.Role])
            click(named)
            assertEquals(1, removals)
            assertEquals(Role.Button, nodes(scene).single { description(it) == "Settings" }.config[SemanticsProperties.Role])
            assertEquals("", description(nodes(scene).single { text(it) == "Save draft" }), "Visible text must stay the accessible name")

            fun chip() = nodes(scene).single { text(it).contains("Goal Mode") }
            assertEquals(false, chip().config[SemanticsProperties.Selected])
            click(chip())
            settle(scene, 16_000_000L)
            assertEquals(true, chip().config[SemanticsProperties.Selected])
            assertEquals(false, nodes(scene).single { description(it) == "Attach files" }.config[SemanticsProperties.Selected])
        }
    }

    @Test fun keyboardFocusShowsARingWithoutMovingLayout() {
        withScene(width = 120, height = 120, content = {
            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) { SepiaButton("Focus me", {}) }
        }) { scene ->
            fun button() = nodes(scene).single { text(it) == "Focus me" }
            val before = button().boundsInRoot
            val unfocused = requireNotNull(scene.render(16_000_000L).use { it.encodeToData() }).bytes
            assertEquals(false, button().config[SemanticsProperties.Focused])
            assertTrue(button().config[SemanticsActions.RequestFocus].action!!.invoke())
            settle(scene, 32_000_000L)
            assertEquals(true, button().config[SemanticsProperties.Focused])
            assertEquals(before, button().boundsInRoot, "Focus must not move or resize the control")
            val focused = requireNotNull(scene.render(48_000_000L).use { it.encodeToData() }).bytes
            assertFalse(unfocused.contentEquals(focused), "Keyboard focus must be visible")
        }
    }

    @Test fun sheetTextStaysReadableAndOnlyOutsideTapsDismiss() {
        var dismissed = 0
        var cancels = 0
        withScene(content = {
            Box(Modifier.fillMaxSize().dismissOnOutsideTap { dismissed++ }, contentAlignment = Alignment.Center) {
                Column(Modifier.size(160.dp).background(Color.White).blockTapThrough()) {
                    BasicText("Delete Chat?")
                    BasicText("This cannot be undone.")
                    SepiaButton("Cancel", { cancels++ })
                }
            }
        }) { scene ->
            val all = nodes(scene)
            val title = all.single { text(it) == "Delete Chat?" }
            val body = all.single { text(it) == "This cannot be undone." }
            listOf(title, body).forEach {
                assertFalse(it.config.contains(SemanticsProperties.Disabled), "Sheet text must not read as disabled")
                assertFalse(it.config.contains(SemanticsActions.OnClick), "Sheet text must not be a tap target")
            }
            assertEquals(1, all.count { it.config.contains(SemanticsActions.OnClick) }, "Only the sheet's action is a reader target")

            tap(scene, 160f, 150f)
            assertEquals(0, dismissed, "A tap inside the sheet must not dismiss it")
            tap(scene, 20f, 20f)
            assertEquals(1, dismissed, "A tap outside the sheet dismisses it")
            click(all.single { text(it) == "Cancel" })
            assertEquals(1, cancels)
            assertEquals(1, dismissed)
        }
    }

    @Test fun confirmationActionsStayVisibleWithLongTextAtLargeFont() {
        val longTitle = "A very long chat title ".repeat(60)
        var confirms = 0
        listOf(320 to 568, 568 to 320).forEach { (width, height) ->
            withScene(width, height, fontScale = 1.8f, content = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SepiaConfirmationCard(
                        title = "Delete Chat?",
                        body = "Are you sure you want to delete '$longTitle'? This cannot be undone.",
                        options = { SepiaToggleRow("Ask for confirmation before deleting", true, {}) }
                    ) {
                        SepiaButton("Cancel", {}, compact = true)
                        SepiaButton("🗑 Delete", { confirms++ }, compact = true, style = SepiaButtonStyle.DANGER)
                    }
                }
            }) { scene ->
                val cancel = nodes(scene).single { text(it) == "Cancel" }
                val delete = nodes(scene).single { text(it) == "Delete" }
                listOf(cancel, delete).forEach {
                    assertTrue(it.boundsInRoot.top >= 0f && it.boundsInRoot.bottom <= height.toFloat(),
                        "Action ${text(it)} is off screen at ${width}x$height: ${it.boundsInRoot}")
                }
                assertTrue(nodes(scene).any { it.config.contains(SemanticsActions.ScrollBy) }, "Long message must scroll")
                click(delete)
                save(scene, "confirm-dialog-${width}x$height")
            }
        }
        assertEquals(2, confirms)
    }

    @Test fun everyThemeKeepsTextAndFilledActionsReadable() {
        val washedOut = ThemeConfig(name = "Custom", mutedInkHex = "#EDE3CC", dangerHex = "#F4ECD8")
        (ThemeConfig.PRESETS + washedOut).forEach { config ->
            val palette = buildPaletteFromConfig(config)
            val surfaces = listOf(palette.canvasBg, palette.sheetBg, palette.cardBg, palette.elevatedBg)
            mapOf(
                "primary" to palette.primaryInk, "narration" to palette.narrationInk, "muted" to palette.mutedInk,
                "danger" to palette.danger, "success" to palette.success, "accent" to palette.accent
            ).forEach { (name, ink) ->
                surfaces.forEach { surface ->
                    assertTrue(contrastRatio(ink, surface) >= 4.5f, "${config.name}: $name on $surface = ${contrastRatio(ink, surface)}")
                }
            }
            listOf(palette.accent, palette.accentSecondary, palette.danger).forEach { fill ->
                assertTrue(contrastRatio(onFillInk(fill), fill) >= 4.5f, "${config.name}: button text on $fill")
            }
        }
        // A color that already passes is left exactly as the theme chose it.
        val untouched = Color(0xFF261C14)
        assertEquals(untouched, readableInk(untouched, listOf(Color(0xFFFAF6EB))))
    }

    @Test fun fieldLabelNamesItsInputOnceWithoutReplacingTheValue() {
        withScene(content = { SepiaTextField("0.7", {}, label = "Temperature") }) { scene ->
            val input = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(text(input).contains("Temperature"), "The input must carry its label")
            assertEquals("0.7", input.config[SemanticsProperties.EditableText].text)
            assertEquals("", description(input), "A description would replace the value for readers")
            nodes(scene).filter { text(it) == "Temperature" && !it.config.contains(SemanticsActions.SetText) }.forEach {
                assertTrue(it.config.contains(SemanticsProperties.HideFromAccessibility), "The visible label must not be read twice")
            }
        }
    }

    @Test fun accentSwatchHasALabelledRadioTargetAndSelection() {
        var picks = 0
        val selected = mutableStateOf(false)
        withScene(content = {
            SepiaAccentSwatch(Color(0xFF3B5998), "Blue accent", selected.value, { picks++; selected.value = true })
        }) { scene ->
            fun swatch() = nodes(scene).single { description(it) == "Blue accent" }
            assertEquals(Role.RadioButton, swatch().config[SemanticsProperties.Role])
            assertTrue(swatch().size.width >= 48 && swatch().size.height >= 48, "Swatch target is too small")
            assertEquals(false, swatch().config[SemanticsProperties.Selected])
            click(swatch())
            settle(scene, 16_000_000L)
            assertEquals(1, picks)
            assertEquals(true, swatch().config[SemanticsProperties.Selected])
        }
    }
}
