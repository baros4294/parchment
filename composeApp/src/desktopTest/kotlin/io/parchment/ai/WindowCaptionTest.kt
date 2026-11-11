package io.parchment.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowCaptionTest {
    @Test fun idleCaptionNamesTheChatAndARunShowsItsProgress() {
        assertEquals("Kyoto trip plan — Parchment", windowCaption("Parchment", " Kyoto trip plan "))
        assertEquals("Settings — Parchment", windowCaption("Parchment", "Settings"))
        assertEquals("Parchment", windowCaption("Parchment", ""))
        val running = "Parchment — model (+120 tok • 4s)"
        assertEquals(running, windowCaption(running, "Kyoto trip plan"))
    }
}
