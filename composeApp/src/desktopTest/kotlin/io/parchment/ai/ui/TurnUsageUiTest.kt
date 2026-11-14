package io.parchment.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.model.TokenMetrics
import io.parchment.ai.model.ViewMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalComposeUiApi::class)
class TurnUsageUiTest {

    private fun flatten(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::flatten)

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun unmergedNodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }

    private fun popupNodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.drop(1).flatMap { flatten(it.rootSemanticsNode) }

    private fun text(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    private fun contentDescription(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")

    private fun stateDescription(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()

    private fun click(node: SemanticsNode) {
        val action = requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action)
        assertTrue(action.invoke())
    }

    private fun settle(scene: ImageComposeScene, frameTimeNanos: Long) {
        Snapshot.sendApplyNotifications()
        scene.render(frameTimeNanos).close()
    }

    private fun waitForSemanticsOwnerCount(
        scene: ImageComposeScene,
        expectedCount: Int,
        startNanos: Long
    ): Long {
        var nanos = startNanos
        repeat(20) {
            if (scene.semanticsOwners.size == expectedCount) return nanos
            runBlocking { delay(50L) }
            nanos += 50_000_000L
            settle(scene, nanos)
        }
        return nanos
    }

    @Test
    fun unavailableCostTurnUsesWarningBadgeAndMovesBreakdownToHoverAndPopup() {
        val node = MessageNode(
            id = "turn-unavailable",
            role = MessageRole.ASSISTANT,
            variants = listOf(
                MessageVariant(
                    id = "var-1",
                    content = "Hello world from assistant.",
                    tokenMetrics = TokenMetrics(
                        reportedRequests = 1,
                        inputTokens = 0,
                        outputTokens = 0,
                        costIsAvailable = false,
                        usageIsComplete = false,
                        unpricedCostComponents = setOf("token usage/prices")
                    )
                )
            )
        )
        val preferences = PreferencesConfig(
            showTokenAndContextStats = true,
            showTokensForEntireTurn = true
        )
        val palette = buildPaletteFromConfig(ThemeConfig())
        val scene = ImageComposeScene(
            width = 900,
            height = 600,
            density = Density(1f, 1f),
            coroutineContext = Dispatchers.Unconfined
        )
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalParchmentPalette provides palette,
                    LocalParchmentWindowSize provides ParchmentWindowSize(900.dp, 600.dp)
                ) {
                    Box(Modifier.fillMaxSize().background(palette.canvasBg).padding(Space.s16)) {
                        MessageTurnItem(
                            node = node,
                            nodeIndex = 0,
                            isLatestTurn = true,
                            viewMode = ViewMode.EDITORIAL_TURNS,
                            preferences = preferences,
                            userName = "Reader",
                            characterName = "Assistant",
                            providerName = "Test Provider",
                            isActivelyThinkingThisNode = false,
                            onSelectVariant = {},
                            onSaveEditedText = { _, _, _ -> },
                            onToggleContextMute = {},
                            onDeleteNode = {},
                            onForkHere = {},
                            onRegenerate = {},
                            onContinue = {}
                        )
                    }
                }
            }
            settle(scene, 0L)

            // Hover the turn card so the desktop footer fades in cleanly.
            scene.sendPointerEvent(PointerEventType.Enter, position = Offset(80f, 40f), timeMillis = 10L)
            scene.sendPointerEvent(PointerEventType.Move, position = Offset(80f, 40f), timeMillis = 10L)
            settle(scene, 10_000_000L)
            settle(scene, 500_000_000L)

            val visibleTextsBefore = nodes(scene).map(::text).filter { it.isNotBlank() }
            assertTrue(
                visibleTextsBefore.any { it.contains("tokens") },
                "Expected token estimate in footer, got $visibleTextsBefore"
            )
            listOf(
                "cost unavailable",
                "Cost unavailable",
                "0 input • 0 output",
                "1 request • partial",
                "Unpriced: token usage/prices"
            ).forEach { forbidden ->
                assertFalse(
                    visibleTextsBefore.any { it.contains(forbidden) },
                    "Inline footer should not show '$forbidden'; visible=$visibleTextsBefore"
                )
            }

            val warningBadge = nodes(scene).firstOrNull { candidate ->
                candidate.config.contains(SemanticsActions.OnClick) &&
                    contentDescription(candidate).contains("Cost unavailable")
            }
            assertNotNull(
                warningBadge,
                "Expected clickable warning badge with 'Cost unavailable' description; nodes=${
                    nodes(scene).map { contentDescription(it) }.filter { it.isNotBlank() }
                }"
            )

            // Hover the warning badge beyond HoverHint delay (450 ms) to open the hover popup.
            val badgeCenter = warningBadge.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, position = badgeCenter, timeMillis = 600L)
            settle(scene, 600_000_000L)
            val afterHoverNanos = waitForSemanticsOwnerCount(scene, 2, 600_000_000L)
            assertEquals(2, scene.semanticsOwners.size, "Hovering warning badge should open HoverHint popup")

            // Moving off the badge closes HoverHint.
            scene.sendPointerEvent(PointerEventType.Move, position = Offset(4f, 4f), timeMillis = 1250L)
            settle(scene, afterHoverNanos + 50_000_000L)
            val afterLeaveNanos = waitForSemanticsOwnerCount(scene, 1, afterHoverNanos + 50_000_000L)
            assertEquals(1, scene.semanticsOwners.size, "Moving away from badge should dismiss HoverHint")

            // Clicking/tapping opens the focusable breakdown popup with every detail line.
            click(warningBadge)
            settle(scene, afterLeaveNanos + 50_000_000L)
            assertTrue(scene.semanticsOwners.size >= 2, "Clicking badge should open details popup")
            val popupTexts = popupNodes(scene).map(::text).filter { it.isNotBlank() }
            listOf(
                "0 input • 0 output",
                "1 request • partial",
                "Cost unavailable",
                "Unpriced: token usage/prices"
            ).forEach { expected ->
                assertTrue(
                    popupTexts.any { it.contains(expected) },
                    "Expected '$expected' inside breakdown popup, got $popupTexts"
                )
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun knownCostWholeTurnStaysSingleLineAndOpensBreakdownPopup() {
        val node = MessageNode(
            id = "turn-known",
            role = MessageRole.ASSISTANT,
            variants = listOf(
                MessageVariant(
                    id = "var-2",
                    content = "Detailed reply text.",
                    tokenMetrics = TokenMetrics(
                        inputTokens = 1200,
                        outputTokens = 340,
                        cachedInputTokens = 500,
                        reportedRequests = 1,
                        reportedCostRequests = 1,
                        costIsAvailable = true,
                        usageIsComplete = true,
                        estimatedCostUsd = 0.0042
                    )
                )
            )
        )
        val preferences = PreferencesConfig(
            showTokenAndContextStats = true,
            showTokensForEntireTurn = true
        )
        val palette = buildPaletteFromConfig(ThemeConfig())
        val scene = ImageComposeScene(
            width = 900,
            height = 600,
            density = Density(1f, 1f),
            coroutineContext = Dispatchers.Unconfined
        )
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalParchmentPalette provides palette,
                    LocalParchmentWindowSize provides ParchmentWindowSize(900.dp, 600.dp)
                ) {
                    Box(Modifier.fillMaxSize().background(palette.canvasBg).padding(Space.s16)) {
                        MessageTurnItem(
                            node = node,
                            nodeIndex = 0,
                            isLatestTurn = true,
                            viewMode = ViewMode.EDITORIAL_TURNS,
                            preferences = preferences,
                            userName = "Reader",
                            characterName = "Assistant",
                            providerName = "Test Provider",
                            isActivelyThinkingThisNode = false,
                            onSelectVariant = {},
                            onSaveEditedText = { _, _, _ -> },
                            onToggleContextMute = {},
                            onDeleteNode = {},
                            onForkHere = {},
                            onRegenerate = {},
                            onContinue = {}
                        )
                    }
                }
            }
            settle(scene, 0L)
            scene.sendPointerEvent(PointerEventType.Enter, position = Offset(80f, 40f), timeMillis = 10L)
            scene.sendPointerEvent(PointerEventType.Move, position = Offset(80f, 40f), timeMillis = 10L)
            settle(scene, 10_000_000L)
            settle(scene, 500_000_000L)

            val allNodes = nodes(scene)
            val inlineNodes = unmergedNodes(scene)
            val inlineTexts = inlineNodes.map(::text).filter { it.isNotBlank() }
            val costNode = inlineNodes.firstOrNull { text(it).contains("$0.0042") }
            val inputNode = inlineNodes.firstOrNull { text(it) == "1.2k" }
            val outputNode = inlineNodes.firstOrNull { text(it) == "340" }
            val reusedNode = inlineNodes.firstOrNull { text(it) == "500" }
            assertNotNull(costNode, "Expected inline cost '$0.0042' in $inlineTexts")
            assertNotNull(inputNode, "Expected inline input count '1.2k' in $inlineTexts")
            assertNotNull(outputNode, "Expected inline output count '340' in $inlineTexts")
            assertNotNull(reusedNode, "Expected inline reused count '500' in $inlineTexts")

            val rowCentersY = listOf(costNode, inputNode, outputNode, reusedNode).map { it.boundsInRoot.center.y }
            val minY = rowCentersY.min()
            val maxY = rowCentersY.max()
            assertTrue(
                abs(maxY - minY) <= 4f,
                "Expected compact inline stats on one horizontal row, got Y centers=$rowCentersY"
            )

            val clickablePill = allNodes.firstOrNull { candidate ->
                candidate.config.contains(SemanticsActions.OnClick) &&
                    (contentDescription(candidate).contains("$0.0042") || text(candidate).contains("$0.0042"))
            }
            assertNotNull(clickablePill, "Expected clickable usage pill containing cost")
            click(clickablePill)
            settle(scene, 600_000_000L)

            assertTrue(scene.semanticsOwners.size >= 2, "Clicking usage pill should open breakdown popup")
            val popupTexts = popupNodes(scene).map(::text).filter { it.isNotBlank() }
            listOf(
                "1.2k input • 340 output",
                "500 reused • 0 saved for reuse",
                "1 request • reported cost"
            ).forEach { expected ->
                assertTrue(
                    popupTexts.any { it.contains(expected) },
                    "Expected '$expected' inside breakdown popup, got $popupTexts"
                )
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun contextGaugeRingUsesWarningIconAndHoverHintWhenCostUnavailable() {
        val palette = buildPaletteFromConfig(ThemeConfig())
        val scene = ImageComposeScene(
            width = 900,
            height = 300,
            density = Density(1f, 1f),
            coroutineContext = Dispatchers.Unconfined
        )
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalParchmentPalette provides palette,
                    LocalParchmentWindowSize provides ParchmentWindowSize(900.dp, 300.dp)
                ) {
                    Box(Modifier.fillMaxSize().background(palette.canvasBg).padding(Space.s24)) {
                        SepiaContextGaugeRing(
                            usedTokens = 120,
                            contextLimitTokens = 1_000_000,
                            sessionCostUsd = 0.0,
                            sessionCostLabel = "cost unavailable"
                        )
                    }
                }
            }
            settle(scene, 0L)

            val allNodes = nodes(scene)
            val texts = allNodes.map(::text).filter { it.isNotBlank() }
            assertTrue(texts.any { it.contains("120/1M (0%)") }, "Expected context usage label, got $texts")
            assertFalse(
                texts.any { it.contains("cost unavailable", ignoreCase = true) },
                "Gauge should show warning icon instead of inline 'cost unavailable' text; got $texts"
            )

            val gaugeNode = allNodes.firstOrNull { stateDescription(it).contains("Cost unavailable") }
            assertNotNull(
                gaugeNode,
                "Expected gauge semantics stateDescription to mention 'Cost unavailable', got ${
                    allNodes.map(::stateDescription).filter { it.isNotBlank() }
                }"
            )

            scene.sendPointerEvent(PointerEventType.Enter, position = gaugeNode.boundsInRoot.center, timeMillis = 10L)
            scene.sendPointerEvent(PointerEventType.Move, position = gaugeNode.boundsInRoot.center, timeMillis = 10L)
            settle(scene, 10_000_000L)
            val afterHoverNanos = waitForSemanticsOwnerCount(scene, 2, 10_000_000L)
            assertEquals(2, scene.semanticsOwners.size, "Hovering gauge with unavailable cost should open HoverHint")

            scene.sendPointerEvent(PointerEventType.Move, position = Offset(2f, 2f), timeMillis = 650L)
            settle(scene, afterHoverNanos + 50_000_000L)
            waitForSemanticsOwnerCount(scene, 1, afterHoverNanos + 50_000_000L)
            assertEquals(1, scene.semanticsOwners.size, "Moving off gauge should dismiss HoverHint")
        } finally {
            scene.close()
        }
    }
}
