package io.parchment.ai.model

import kotlin.test.*

class TurnTextTest {
    private fun tool(id: String, text: String = "") = ToolCallRecord(id, "fs_read", "{}", precedingText = text)
    private val steps = listOf(tool("a", "One. "), tool("parallel"), tool("b", "Two. "))

    @Test fun textIsPlacedBeforeTheToolItPreceded() {
        val layout = turnTextLayout("One. Two. Final.", steps)
        assertEquals(listOf("One. ", "", "Two. "), layout.beforeTool)
        assertEquals("Final.", layout.finalText)
        assertEquals("One.\n\nTwo.\n\nFinal.", layout.joinedText)
    }

    @Test fun visibleTextThatTrailsTheStreamIsCutToFit() {
        assertEquals(listOf("One. ", "", "Tw"), turnTextLayout("One. Tw", steps).beforeTool)
        assertEquals("", turnTextLayout("One. Tw", steps).finalText)
        assertEquals(listOf("On", "", ""), turnTextLayout("On", steps).beforeTool)
    }

    @Test fun editedOrOlderTextKeepsTheWholeReplyTogether() {
        val edited = turnTextLayout("Rewritten by me.", steps)
        assertFalse(edited.hasSteps)
        assertEquals("Rewritten by me.", edited.finalText)
        val old = turnTextLayout("All text.", listOf(tool("a")))
        assertFalse(old.hasSteps)
        assertEquals("All text.", old.finalText)
        assertFalse(turnTextLayout("Zzz", steps).hasSteps, "Shorter text that differs is not a stream prefix")
    }

    @Test fun onlyTheFirstToolOfAStepClaimsItsText() {
        val claimed = mutableListOf<ToolCallRecord>()
        claimed += tool("a", unclaimedStepText("One. ", claimed))
        claimed += tool("parallel", unclaimedStepText("One. ", claimed))
        claimed += tool("b", unclaimedStepText("One. Two. ", claimed))
        assertEquals(listOf("One. ", "", "Two. "), claimed.map { it.precedingText })
        assertEquals("", unclaimedStepText("short", listOf(tool("a", "Much longer than the buffer"))))
    }
}
