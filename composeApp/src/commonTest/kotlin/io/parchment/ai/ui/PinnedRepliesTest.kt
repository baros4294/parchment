package io.parchment.ai.ui

import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinnedRepliesTest {
    private fun node(id: String, text: String, pinned: Boolean = false, role: MessageRole = MessageRole.ASSISTANT) =
        MessageNode(id = id, role = role, variants = listOf(MessageVariant(id = "v-$id", content = text)), pinned = pinned)

    private fun chat(id: String, updatedAt: Long, vararg messages: MessageNode) =
        ChatSession(id = id, title = "Chat $id", createdAt = 1L, updatedAt = updatedAt, profileId = "profile", messages = messages.toList())

    @Test
    fun aStoredChatStillListsItsPinsWithoutBeingLoaded() {
        val full = chat(
            "a", 5L,
            node("q1", "question", role = MessageRole.USER),
            node("r1", "**Pinned** answer with `code` " + "and more ".repeat(60), pinned = true),
            node("q2", "another question", role = MessageRole.USER),
            node("r2", "last answer")
        )
        val stub = dehydrateChatSessionToCompactStub(full)

        assertTrue(stub.isCompactStub)
        assertEquals(pinnedReplies(listOf(full)), pinnedReplies(listOf(stub)))
        assertEquals("r1", pinnedReplies(listOf(stub)).single().nodeId)
        assertTrue(pinnedReplies(listOf(stub)).single().preview.startsWith("Pinned answer with code"))
        assertEquals(full.messages.size, stub.messages.size)
        assertEquals(stub.messages.size, stub.messages.map { it.id }.toSet().size)
    }

    @Test
    fun unpinnedMessagesSaveExactlyAsBefore() {
        val plain = compactStateJson.encodeToString(MessageNode.serializer(), node("n", "text"))
        assertFalse("pinned" in plain)

        val pinned = compactStateJson.encodeToString(MessageNode.serializer(), node("n", "text", pinned = true))
        assertTrue("\"pinned\":true" in pinned)
        assertTrue(compactStateJson.decodeFromString(MessageNode.serializer(), pinned).pinned)
    }

    @Test
    fun pinsListNewestChatFirstInMessageOrderAndToggle() {
        val older = chat("old", 1L, node("o1", "old pin", pinned = true))
        val newer = chat("new", 9L, node("n1", "first", pinned = true), node("n2", "unpinned"), node("n3", "third", pinned = true))

        assertEquals(listOf("n1", "n3", "o1"), pinnedReplies(listOf(older, newer)).map { it.nodeId })
        assertEquals(listOf("o1"), pinnedReplies(listOf(older, newer), onlySessionId = "old").map { it.nodeId })

        val toggled = newer.togglePinned("n2").togglePinned("n1")
        assertEquals(listOf("n2", "n3"), pinnedReplies(listOf(toggled)).map { it.nodeId })
    }
}
