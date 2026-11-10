package io.parchment.ai.ui

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.parchment.ai.model.*
import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the real app with sample chats, so design changes can be reviewed at phone and desktop sizes. */
@OptIn(ExperimentalComposeUiApi::class)
class UiGalleryTest {
    private val output = File(
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "PARCHMENT_TODO.md").isFile },
        ".parchment-local/verification/gallery"
    ).apply { mkdirs() }

    private data class Viewport(val name: String, val widthDp: Int, val heightDp: Int, val density: Float)
    private val phone = Viewport("phone", 390, 844, 2f)
    private val desktop = Viewport("desktop", 1360, 860, 1f)

    private fun message(id: String, role: MessageRole, variant: MessageVariant) = MessageNode(id, role, listOf(variant))

    private fun sampleMessages(protocol: ProviderProtocol) = listOf(
        message("u1", MessageRole.USER, MessageVariant("vu1", "Why does the export fail on chats with long titles? Please check the writer and fix it.")),
        message("a1", MessageRole.ASSISTANT, MessageVariant(
            id = "va1",
            content = "The export built each file name directly from the chat title, so a long title went past the 255 character limit and the write failed.\n\n" +
                "I changed it to shorten the name and add a short hash, so two similar titles stay distinct:\n\n" +
                "```kotlin\nfun exportName(title: String, id: String): String =\n    title.take(80).trim() + \"-\" + id.takeLast(6) + \".json\"\n```\n\n" +
                "- Titles over 80 characters are shortened\n- Existing exports keep their names\n\nThe export test now covers a 400 character title.",
            thinkingSummary = "The failure only happens on long titles, so the path length is the likely cause. I should read the writer first.",
            thinkingDurationMs = 4200L,
            toolCalls = listOf(
                ToolCallRecord("t1", "fs_read", """{"path":"src/ChatArchiveWriter.kt"}""", "fun write(title: String) { ... }", durationMs = 120L, startsToolStep = true),
                ToolCallRecord("t2", "fs_edit", """{"path":"src/ChatArchiveWriter.kt"}""", "Edited 1 file", durationMs = 80L, startsToolStep = true)
            ),
            providerProtocol = protocol,
            modelUsed = "gemini-3-pro",
            createdAt = PlatformBridge.currentTimeMillis() - 300_000L
        )),
        message("u2", MessageRole.USER, MessageVariant("vu2", "Great. Does that also cover the Android export?"))
    )

    private fun seed(theme: ThemeConfig) {
        val base = createDefaultAppState()
        val profile = base.profiles.first()
        val assistant = base.assistants.first()
        fun chat(id: String, title: String, updated: Long, messages: List<MessageNode>) = ChatSession(
            id = id, title = title, createdAt = updated, updatedAt = updated, profileId = profile.id,
            assistantId = assistant.id, messages = messages
        )
        val now = PlatformBridge.currentTimeMillis()
        val chats = listOf(
            chat("gallery-1", "Fix chat export on long titles", now, sampleMessages(profile.protocol)),
            chat("gallery-2", "Trip notes for Kyoto", now - 3_600_000L, sampleMessages(profile.protocol).take(1)),
            chat("gallery-3", "The lighthouse keeper, chapter four", now - 90_000_000L, sampleMessages(profile.protocol).take(1)),
            chat("gallery-4", "Compare two storage designs", now - 400_000_000L, sampleMessages(profile.protocol).take(1))
        )
        chats.forEach { PlatformBridge.saveChatSessionJson(it.id, compactStateJson.encodeToString(it)) }
        val state = base.copy(
            sessions = emptyList(), activeSessionId = "gallery-1", openTabSessionIds = listOf("gallery-1", "gallery-2"),
            profiles = base.profiles.map { it.copy(apiKey = "sample", defaultModel = "gemini-3-pro") }, theme = theme,
            autoCheckOtaOnStartup = false
        )
        PlatformBridge.saveAppStateJson(compactStateJson.encodeToString(state))
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }

    private fun label(node: SemanticsNode) = (
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        ).joinToString(" ")

    private var frame = 0L
    private fun settle(scene: ImageComposeScene, frames: Int = 40) = repeat(frames) {
        Snapshot.sendApplyNotifications()
        frame += 16_000_000L
        scene.render(frame).close()
        Thread.sleep(12)
    }

    private fun capture(viewport: Viewport, theme: ThemeConfig, name: String, steps: List<String> = emptyList()) {
        seed(theme)
        val scene = ImageComposeScene(
            (viewport.widthDp * viewport.density).toInt(), (viewport.heightDp * viewport.density).toInt(),
            density = Density(viewport.density), coroutineContext = Dispatchers.Unconfined
        )
        try {
            val process = parchmentProcessState
            process.snapshot.value = loadFastInitialAppState().first.copy(theme = theme)
            scene.setContent { ParchmentApp() }
            settle(scene, 130)
            for (step in steps) {
                val clickable = nodes(scene).filter { it.config.contains(SemanticsActions.OnClick) }
                val target = clickable.firstOrNull { label(it).equals(step, ignoreCase = true) }
                    ?: clickable.firstOrNull { label(it).contains(step, ignoreCase = true) }
                if (target != null) target.config[SemanticsActions.OnClick].action?.invoke()
                else File(output, "${viewport.name}-$name-missing.txt").writeText(step)
                settle(scene)
            }
            File(output, "${viewport.name}-$name.txt").writeText(
                nodes(scene).filter { it.config.contains(SemanticsActions.OnClick) }.joinToString("\n", transform = ::label)
            )
            frame += 16_000_000L
            scene.render(frame).use { File(output, "${viewport.name}-$name.png").writeBytes(requireNotNull(it.encodeToData()).bytes) }
        } finally { scene.close() }
    }

    @Test fun renderGallery() {
        val night = ThemeConfig.PRESETS[1]
        try {
            for (viewport in listOf(phone, desktop)) {
                val closeDrawer = if (viewport == phone) listOf("Close sidebar") else emptyList()
                capture(viewport, ThemeConfig(), "chat", closeDrawer)
                capture(viewport, night, "chat-dark", closeDrawer)
                capture(viewport, ThemeConfig(), "sidebar")
                capture(viewport, ThemeConfig(), "settings", listOf("Settings"))
                capture(viewport, ThemeConfig(), "thought", closeDrawer + listOf("Thought"))
                capture(viewport, ThemeConfig(), "models", closeDrawer + listOf("gemini-3-pro"))
                capture(viewport, ThemeConfig(), "notes", closeDrawer + listOf("Chat notes"))
            }
            for (viewport in listOf(phone, desktop)) {
                capture(viewport, ThemeConfig(), "empty", listOf("New chat") + if (viewport == phone) listOf("Close sidebar") else emptyList())
            }
        } finally {
            (1..4).forEach { PlatformBridge.deleteChatSessionFile("gallery-$it") }
            PlatformBridge.saveAppStateJson(compactStateJson.encodeToString(createDefaultAppState()))
        }
        assertTrue(File(output, "phone-chat.png").isFile)
    }
}
