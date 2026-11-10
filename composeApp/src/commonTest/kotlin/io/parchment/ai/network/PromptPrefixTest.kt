package io.parchment.ai.network

import io.parchment.ai.model.*
import kotlin.test.*

class PromptPrefixTest {
    private fun message(text: String) = MessageNode(text, MessageRole.USER, listOf(MessageVariant(text, content = text)))

    private fun chat(vararg messages: String) = ChatSession(
        "c", "t", 1L, 1L, "p", messages = messages.map(::message),
        fragments = listOf(
            ContextFragment("1", "Dragons", "Dragons breathe frost.", keywords = listOf("dragon"), mode = FragmentActivationMode.KEYWORD),
            ContextFragment("2", "Setting", "It always rains.", mode = FragmentActivationMode.PINNED)
        )
    )

    @Test fun aKeywordMatchLeavesTheSystemPromptUnchanged() {
        val quiet = AiProviderEngine.buildCompiledSystemPrompt(chat("hello"))
        val matched = AiProviderEngine.buildCompiledSystemPrompt(chat("a dragon appears"))
        assertEquals(quiet, matched)
        assertTrue("It always rains." in matched, "Pinned knowledge stays at the top")
    }

    @Test fun aKeywordMatchStillReachesTheModelNearTheEnd() {
        val session = chat("a dragon appears")
        val turns = AiProviderEngine.injectAuthorsNoteIntoHistory(session.messages, session)
        assertTrue("Dragons breathe frost." in turns.last().second)
        val quiet = chat("hello")
        assertFalse("Dragons breathe frost." in AiProviderEngine.injectAuthorsNoteIntoHistory(quiet.messages, quiet).last().second)
    }
}
