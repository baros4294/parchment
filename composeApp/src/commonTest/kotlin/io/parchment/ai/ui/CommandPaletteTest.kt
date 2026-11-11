package io.parchment.ai.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandPaletteTest {
    private val commands = listOf("New chat", "Open settings", "Settings: Providers", "Chat: Trip notes for Kyoto", "Toggle sidebar")
        .map { PaletteCommand(it) {} }

    private fun labels(query: String) = filterPaletteCommands(commands, query).map { it.label }

    @Test
    fun anEmptyQueryKeepsEveryCommandInOrder() {
        assertEquals(commands.map { it.label }, labels("  "))
    }

    @Test
    fun everyWordMustMatchInAnyOrder() {
        assertEquals(listOf("Chat: Trip notes for Kyoto"), labels("kyoto trip"))
    }

    @Test
    fun labelsStartingWithTheQueryComeFirst() {
        assertEquals(listOf("Settings: Providers", "Open settings"), labels("settings"))
    }

    @Test
    fun noMatchGivesNothing() {
        assertEquals(emptyList(), labels("zebra"))
    }
}
