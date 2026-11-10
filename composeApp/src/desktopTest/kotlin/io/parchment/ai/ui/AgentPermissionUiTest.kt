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
import androidx.compose.runtime.snapshots.Snapshot
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
    /** Publishes state written outside composition before rendering, so one frame observes it. */
    private fun settle(scene: ImageComposeScene, frameNanos: Long) {
        Snapshot.sendApplyNotifications()
        scene.render(frameNanos).close()
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
    private fun save(scene: ImageComposeScene, name: String, frameTimeNanos: Long = 1_000_000_000L) {
        val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "PARCHMENT_TODO.md").isFile }
        val file = File(repo, ".parchment-local/verification/permissions/$name.png")
        file.parentFile.mkdirs()
        scene.render(frameTimeNanos).use { image -> file.writeBytes(requireNotNull(image.encodeToData()).bytes) }
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
                settle(scene, 0L)
                val radios = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
                assertEquals(ToolApprovalMode.entries.size + WorkspaceMode.entries.size + ShellAccess.entries.size, radios.size)
                click(radios.single { text(it).contains("Read and edit files") })
                assertEquals(WorkspaceMode.READ_WRITE, overrides.value.workspaceMode)
                assertNull(overrides.value.shellAccess)
                settle(scene, 0L)
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
            settle(scene, 0L)
            val name = WorkspaceHarness.allTools().first().name
            val first = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch && text(it).startsWith(name) }
            assertEquals(ToggleableState.On, first.config[SemanticsProperties.ToggleableState])
            click(first)
            assertEquals(false, overrides.value.toolOverrides?.get(name))
            assertNull(overrides.value.toolsEnabled)
            assertNull(overrides.value.workspaceMode)
            assertNull(overrides.value.shellAccess)
            settle(scene, 0L)
            val search = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(requireNotNull(search.config[SemanticsActions.SetText].action).invoke(AnnotatedString("web_fetch")))
            settle(scene, 0L)
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
            settle(scene, 0L)
            val actions = nodes(scene).filter { it.config.contains(SemanticsActions.OnClick) }
            assertEquals(4, actions.count { text(it) in listOf("Allow once", "Allow this exact action for this run", "Deny", "Stop run") })
            save(scene, "approval-large-font")
            assertTextFits(scene, "approval")
            val scroll = nodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }
            assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 2_000f))
            repeat(12) { settle(scene, 1_100_000_000L + it * 100_000_000L) }
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
        val labels = listOf("Temperature (", "Top-P (", "Max output tokens (", "Legacy thinking budget (",
            "History message limit (", "Prose font size (", "Code font size (", "Max reading width (")
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
                    settle(scene, 0L)
                    if (page == "assistant") {
                        click(nodes(scene).single { text(it).startsWith(assistant.name) && it.config.contains(SemanticsActions.OnClick) })
                        settle(scene, 0L)
                    }
                    val first = if (page == "fonts") "Prose font size (" else "Temperature ("
                    val second = if (page == "fonts") "Code font size (" else "Top-P ("
                    val fieldNodes = nodes(scene).filter { node ->
                        !node.config.contains(SemanticsActions.SetText) && labels.any { text(node).startsWith(it) }
                    }
                    val firstNode = fieldNodes.single { text(it).startsWith(first) }
                    val secondNode = fieldNodes.single { text(it).startsWith(second) }
                    assertTrue(secondNode.positionInRoot.y > firstNode.positionInRoot.y, "$page fields are still side by side")
                    assertTextFits(scene, "$page at $width / $scale") { label ->
                        labels.any { label.startsWith(it) } || label in presetLabels || label in listOf("Omitted", "Unlimited", "Adaptive")
                    }
                    assertSingleLineInputsFitVertically(scene, "$page at $width / $scale")
                    save(scene, "settings-$page-$width")
                    if (page == "fonts") {
                        val radios = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
                        val (choices, swatches) = radios.partition { text(it).isNotEmpty() }
                        assertEquals(presetLabels.toSet(), choices.map(::text).toSet())
                        choices.forEach { assertTrue(it.size.height >= 48, "Theme choice target is too short") }
                        assertEquals(8, swatches.size)
                        swatches.forEach {
                            assertTrue(it.size.width >= 48 && it.size.height >= 48, "Accent swatch target is too small")
                            assertTrue(it.config[SemanticsProperties.ContentDescription].single().endsWith(" accent"))
                        }
                        click(choices.single { text(it) == presetLabels.last() })
                        assertEquals(ThemeConfig.PRESETS.last(), selectedTheme.value)
                        settle(scene, 1_100_000_000L)
                        assertEquals(presetLabels.last(), text(nodes(scene).single {
                            it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton && text(it).isNotEmpty() &&
                                it.config.getOrNull(SemanticsProperties.Selected) == true
                        }))
                    }
                } finally { scene.close() }
            }
        }
    }

    @Test fun providerChoicesWrapAndPreserveExplicitSettings() {
        val labels = ProviderProtocol.entries.map { it.displayName } + CUSTOM_DIALECTS.map(::customDialectLabel) +
            AuthHeaderMode.entries.map { it.label }
        listOf(320 to 1.8f, 740 to 1f).forEach { (width, scale) ->
            val profile = mutableStateOf(ProviderProfile("fixture", "Fixture", ProviderProtocol.CUSTOM,
                customDialect = CUSTOM_DIALECTS.first(), baseUrl = "https://example.invalid/exact-endpoint"))
            val scene = ImageComposeScene(width, 3_800, density = Density(1f, scale), coroutineContext = Dispatchers.Unconfined)
            try {
                scene.setContent {
                    CompositionLocalProvider(LocalParchmentWindowSize provides ParchmentWindowSize(width.dp, 720.dp)) {
                        Column(Modifier.fillMaxSize().background(LocalParchmentPalette.current.canvasBg)
                            .verticalScroll(rememberScrollState()).padding(12.dp)) {
                            ProviderProtocolSubpage(profile.value, { profile.value = it(profile.value) }, {}, {})
                        }
                    }
                }
                settle(scene, 0L)
                fun radios() = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
                assertEquals(labels.toSet(), radios().map(::text).toSet())
                radios().forEach { assertTrue(it.size.height >= 48, "Provider choice target is too short") }
                assertEquals(3, radios().count { it.config.getOrNull(SemanticsProperties.Selected) == true })
                click(radios().single { text(it) == customDialectLabel(CUSTOM_DIALECTS.last()) })
                assertEquals(CUSTOM_DIALECTS.last(), profile.value.customDialect)
                settle(scene, 0L)
                click(radios().single { text(it) == AuthHeaderMode.QUERY_PARAM.label })
                assertEquals(AuthHeaderMode.QUERY_PARAM, profile.value.authHeaderMode)
                assertEquals("https://example.invalid/exact-endpoint", profile.value.baseUrl)
                settle(scene, 0L)
                assertTextFits(scene, "Provider choices at $width / $scale") { it in labels }
                save(scene, "provider-choices-$width")
                click(radios().single { text(it) == ProviderProtocol.OPENAI_RESPONSES.displayName })
                assertEquals(ProviderProtocol.OPENAI_RESPONSES, profile.value.protocol)
                assertEquals(ProviderProtocol.OPENAI_RESPONSES.defaultBaseUrl, profile.value.baseUrl)
                assertEquals(CUSTOM_DIALECTS.last(), profile.value.customDialect)
                assertEquals(AuthHeaderMode.QUERY_PARAM, profile.value.authHeaderMode)
            } finally { scene.close() }
        }
    }

    @Test fun sharedSettingsSwitchesHaveOneLabelledTargetAndExposeState() {
        listOf(320 to 1.8f, 740 to 1f).forEach { (width, scale) ->
            val rowState = mutableStateOf(false)
            val standaloneState = mutableStateOf(false)
            var rowChanges = 0
            var standaloneChanges = 0
            val title = "Auto-fetch models when the saved provider model list is empty"
            val label = "Enable provider fixture"
            val scene = ImageComposeScene(width, 720, density = Density(1f, scale), coroutineContext = Dispatchers.Unconfined)
            try {
                scene.setContent {
                    CompositionLocalProvider(LocalParchmentWindowSize provides ParchmentWindowSize(width.dp, 720.dp)) {
                        Column(Modifier.fillMaxSize().background(LocalParchmentPalette.current.canvasBg).padding(12.dp)) {
                            SepiaToggleRow(title, rowState.value, { rowChanges++; rowState.value = it },
                                subtitle = "A setting with a wrapping description at enlarged font sizes.")
                            SepiaToggleSwitch(standaloneState.value, { standaloneChanges++; standaloneState.value = it }, label)
                        }
                    }
                }
                settle(scene, 0L)
                fun switches() = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
                fun row() = switches().single { text(it).startsWith(title) }
                fun standalone() = switches().single { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                assertEquals(2, switches().size)
                assertEquals(2, nodes(scene).count { it.config.contains(SemanticsActions.OnClick) }, "Nested click targets remain")
                switches().forEach {
                    assertTrue(it.size.width >= 48 && it.size.height >= 48, "Switch target is too small")
                    assertEquals(ToggleableState.Off, it.config[SemanticsProperties.ToggleableState])
                }
                click(row())
                assertEquals(1, rowChanges)
                assertTrue(rowState.value)
                assertEquals(0, standaloneChanges)
                settle(scene, 0L)
                assertEquals(ToggleableState.On, row().config[SemanticsProperties.ToggleableState])
                click(standalone())
                assertEquals(1, standaloneChanges)
                assertTrue(standaloneState.value)
                settle(scene, 0L)
                click(row())
                assertEquals(2, rowChanges)
                assertFalse(rowState.value)
                settle(scene, 0L)
                assertEquals(ToggleableState.Off, row().config[SemanticsProperties.ToggleableState])
                assertEquals(ToggleableState.On, standalone().config[SemanticsProperties.ToggleableState])
                assertTextFits(scene, "Shared switches at $width / $scale")
                settle(scene, 1_000_000_000L)
                save(scene, "settings-switches-$width", frameTimeNanos = 2_000_000_000L)
            } finally { scene.close() }
        }
    }
}
