package io.parchment.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.harness.ToolApprovalBroker
import io.parchment.ai.harness.ToolAccessScope
import io.parchment.ai.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class AgentPermissionUiTest {
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun text(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
    private fun click(node: SemanticsNode) = assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
    private fun assertTextFits(scene: ImageComposeScene, context: String, include: (String) -> Boolean = { true }) {
        nodes(scene).forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            results.forEach layoutLoop@ { layout ->
                if (!include(layout.layoutInput.text.text)) return@layoutLoop
                val message = "$context: ${layout.layoutInput.text}; size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}"
                // Paragraph constraint width can exceed naturally measured text width. Check the
                // drawn lines themselves, with one pixel for fractional font-metric rounding.
                assertFalse(layout.multiParagraph.didExceedMaxLines, message)
                repeat(layout.lineCount) { line ->
                    assertFalse(layout.isLineEllipsized(line), message)
                    assertTrue(layout.getLineRight(line) <= layout.size.width + 1f, message)
                    assertTrue(layout.getLineBottom(line) <= layout.size.height + 1f, message)
                }
            }
        }
    }
    private fun assertSingleLineInputsFitVertically(scene: ImageComposeScene, context: String) {
        var checked = 0
        nodes(scene).filter { it.config.contains(SemanticsActions.SetText) }.forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            results.filter { !it.layoutInput.softWrap }.forEach { layout ->
                checked++
                val message = "$context: input '${layout.layoutInput.text}' is clipped vertically"
                // These settings fields have 8dp padding on each vertical edge. Horizontal
                // overflow is intentional: single-line inputs scroll to the cursor.
                assertTrue(node.size.height + 1f >= layout.multiParagraph.height + 16f, message)
                assertTrue(layout.getLineBottom(0) <= layout.size.height + 1f, message)
            }
        }
        assertTrue(checked > 0, "$context: no single-line input layouts checked")
    }
    private fun save(scene: ImageComposeScene, name: String) {
        val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "PARCHMENT_TODO.md").isFile }
        val file = File(repo, ".parchment-local/verification/permissions/$name.png")
        file.parentFile.mkdirs()
        scene.render(1_000_000_000L).use { image -> file.writeBytes(requireNotNull(image.encodeToData()).bytes) }
    }

    @Test fun editorChoicesWrapAndRetainInheritanceAcrossNarrowAndLargeFonts() {
        val cases = listOf(Triple(380, 1f, "portrait"), Triple(740, 1f, "landscape"),
            Triple(320, 1.8f, "large-font"), Triple(380, 1f, "dark"))
        cases.forEach { (width, scale, name) ->
            val overrides = mutableStateOf(AgentPermissionOverrides())
            val defaults = AgentPermissionPolicy(workspacePath = "C:/Projects/a-long-workspace-name/another-long-folder", workspaceMode = WorkspaceMode.READ_ONLY)
            val palette = buildPaletteFromConfig(if (name == "dark") ThemeConfig(
                canvasBgHex = "181411", sheetBgHex = "211B16", cardBgHex = "29221C", elevatedBgHex = "352B22",
                primaryInkHex = "F4ECD8", narrationInkHex = "D5C4A1", mutedInkHex = "B9A88A", accentHex = "E6A887"
            ) else ThemeConfig())
            val scene = ImageComposeScene(width, 3_800, density = Density(1f, scale), coroutineContext = Dispatchers.Unconfined)
            try {
                scene.setContent {
                    CompositionLocalProvider(LocalParchmentPalette provides palette,
                        LocalParchmentWindowSize provides ParchmentWindowSize(width.dp, 720.dp)) {
                        Column(Modifier.fillMaxSize().background(palette.canvasBg).verticalScroll(rememberScrollState()).padding(12.dp)) {
                            AgentPermissionEditor(defaults, overrides.value, { overrides.value = it }, onReset = { overrides.value = AgentPermissionOverrides() })
                            SepiaButton("Allow this exact action for this run", onClick = {}, modifier = Modifier.heightIn(min = 48.dp), wrapText = true)
                        }
                    }
                }
                scene.render(0L).close()
                val radios = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
                assertEquals(ToolApprovalMode.entries.size + WorkspaceMode.entries.size + ShellAccess.entries.size, radios.size)
                click(radios.single { text(it).contains("Read and edit files") })
                assertEquals(WorkspaceMode.READ_WRITE, overrides.value.workspaceMode)
                assertNull(overrides.value.shellAccess)
                scene.render(0L).close()
                click(nodes(scene).single { text(it) == "Follow assistant defaults" && it.config.contains(SemanticsActions.OnClick) })
                assertEquals(AgentPermissionOverrides(), overrides.value)
                save(scene, name)
                assertTextFits(scene, name)
            } finally { scene.close() }
        }
    }

    @Test fun registryTogglesSavedChoicesWithoutGrantingAccessAndSearchesDescriptions() {
        val overrides = mutableStateOf(AgentPermissionOverrides())
        val defaults = AgentPermissionPolicy(toolsEnabled = false)
        val scene = ImageComposeScene(360, 900, coroutineContext = Dispatchers.Unconfined)
        try {
            scene.setContent {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    AgentToolRegistry(overrides.value.applyTo(defaults), overrides.value, { overrides.value = it })
                }
            }
            scene.render(0L).close()
            val name = WorkspaceHarness.allTools().first().name
            val first = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch && text(it).startsWith(name) }
            assertEquals(ToggleableState.On, first.config[SemanticsProperties.ToggleableState])
            click(first)
            assertEquals(false, overrides.value.toolOverrides?.get(name))
            assertNull(overrides.value.toolsEnabled)
            assertNull(overrides.value.workspaceMode)
            assertNull(overrides.value.shellAccess)
            scene.render(0L).close()
            val search = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(requireNotNull(search.config[SemanticsActions.SetText].action).invoke(AnnotatedString("web_fetch")))
            scene.render(0L).close()
            val switches = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
            assertEquals(1, switches.size)
            assertTrue(text(switches.single()).contains("web_fetch"))
            assertTrue(text(switches.single()).contains("Agent tools are off"))
            save(scene, "registry-disabled")
        } finally { scene.close() }
    }

    @Test fun owningChatApprovalActionsWrapAtLargeFont() = runBlocking {
        val broker = ToolApprovalBroker("run", "chat", {
            ToolAccessScope(AgentPermissionPolicy(approvalMode = ToolApprovalMode.ASK_EVERY_TOOL), goalModeActive = true)
        })
        val waiting = async { broker.authorize("call", "calc_eval", """{"expression":"2+2"}""") }
        broker.requests.first { it.isNotEmpty() }
        val scene = ImageComposeScene(320, 720, density = Density(1f, 1.8f), coroutineContext = Dispatchers.Unconfined)
        try {
            scene.setContent { ToolApprovalPanel(broker, "Owning chat with a long project title") }
            scene.render(0L).close()
            val actions = nodes(scene).filter { it.config.contains(SemanticsActions.OnClick) }
            assertEquals(4, actions.count { text(it) in listOf("Allow once", "Allow this exact action for this run", "Deny", "Stop run") })
            save(scene, "approval-large-font")
            assertTextFits(scene, "approval")
            val scroll = nodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }
            assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 2_000f))
            repeat(12) { scene.render(1_100_000_000L + it * 100_000_000L).close() }
            val stop = nodes(scene).single { text(it) == "Stop run" && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(stop.boundsInRoot.height >= 48f, "Stop action is not reachable by scrolling")
            click(stop)
            waiting.join()
            assertTrue(waiting.isCancelled)
            assertTrue(broker.requests.value.isEmpty())
        } finally { scene.close(); waiting.cancelAndJoin(); broker.close() }
    }

    @Test fun substantialSettingsFieldsStackAndTextFits() {
        val profile = ProviderProfile("fixture", "Fixture", ProviderProtocol.OPENAI_RESPONSES, defaultModel = "explicit-model")
        val assistant = AssistantAgent("assistant", "Fixture assistant")
        val session = ChatSession("chat", "Fixture", 1L, 1L, profile.id)
        val state = AppStateSnapshot(profiles = listOf(profile), activeProfileId = profile.id,
            assistants = listOf(assistant), activeAssistantId = assistant.id)
        val labels = listOf("Temperature (", "Top-P (", "Max Output Tokens (", "Legacy Thinking Budget (",
            "History Message Limit (", "Prose Font Size (", "Code Font Size (", "Max Reading Width (")
        val presetLabels = ThemeConfig.PRESETS.map { it.name.substringBefore(" (") }
        listOf(320 to 1.8f, 740 to 1f).forEach { (width, scale) ->
            listOf("provider", "assistant", "fonts").forEach { page ->
                val selectedTheme = mutableStateOf(ThemeConfig())
                val scene = ImageComposeScene(width, 3_800, density = Density(1f, scale), coroutineContext = Dispatchers.Unconfined)
                try {
                    scene.setContent {
                        CompositionLocalProvider(LocalParchmentWindowSize provides ParchmentWindowSize(width.dp, 720.dp)) {
                            Box(Modifier.fillMaxSize().background(LocalParchmentPalette.current.canvasBg)) {
                            when (page) {
                                "provider" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                                    ProviderProtocolSubpage(profile, {}, {}, {})
                                }
                                "assistant" -> AssistantsSubpage(state, { _, _ -> }, {})
                                else -> PreferencesSubpage(PreferencesConfig(), selectedTheme.value, session, "font", {}, { selectedTheme.value = it }, {}, {})
                            }
                            }
                        }
                    }
                    scene.render(0L).close()
                    if (page == "assistant") {
                        click(nodes(scene).single { text(it).startsWith(assistant.name) && it.config.contains(SemanticsActions.OnClick) })
                        scene.render(0L).close()
                    }
                    val first = if (page == "fonts") "Prose Font Size (" else "Temperature ("
                    val second = if (page == "fonts") "Code Font Size (" else "Top-P ("
                    val fieldNodes = nodes(scene).filter { node -> labels.any { text(node).startsWith(it) } }
                    val firstNode = fieldNodes.single { text(it).startsWith(first) }
                    val secondNode = fieldNodes.single { text(it).startsWith(second) }
                    assertTrue(secondNode.positionInRoot.y > firstNode.positionInRoot.y, "$page fields are still side by side")
                    assertTextFits(scene, "$page at $width / $scale") { label ->
                        labels.any { label.startsWith(it) } || label in presetLabels || label in listOf("Omitted", "Unlimited", "Adaptive")
                    }
                    assertSingleLineInputsFitVertically(scene, "$page at $width / $scale")
                    save(scene, "settings-$page-$width")
                    if (page == "fonts") {
                        val choices = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
                        assertEquals(presetLabels.toSet(), choices.map(::text).toSet())
                        choices.forEach { assertTrue(it.size.height >= 48, "Theme choice target is too short") }
                        click(choices.single { text(it) == presetLabels.last() })
                        assertEquals(ThemeConfig.PRESETS.last(), selectedTheme.value)
                        scene.render(1_100_000_000L).close()
                        assertEquals(presetLabels.last(), text(nodes(scene).single {
                            it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                                it.config.getOrNull(SemanticsProperties.Selected) == true
                        }))
                    }
                } finally { scene.close() }
            }
        }
    }
}
