package io.parchment.ai.ui

import io.parchment.ai.model.*
import io.parchment.ai.network.aggregateApiUsageRecords
import kotlinx.serialization.json.Json
import io.parchment.ai.platform.PlatformBridge
import kotlin.test.*

class UtilityUsagePersistenceTest {
    private val usage = ProviderTokenUsage(inputTokens = 10, cachedInputTokens = 0, cacheCreationInputTokens = 0,
        shortCacheCreationInputTokens = 0, longCacheCreationInputTokens = 0, outputTokens = 20, reasoningTokens = 0, isFinal = true)
    private val record = ApiUsageRecord("request", wireDialect = "openai_responses", profileId = "profile", model = "model",
        usage = usage, ownerSessionId = "chat", ownerRunId = "run", purpose = "translation")
    private val chat = ChatSession("chat", "Chat", 1, 1, "profile")

    @Test fun cumulativeUpdatesReplaceOneRequestWithoutDoubleCounting() {
        val partial = record.copy(usage = usage.copy(outputTokens = 5, isFinal = false))
        val updated = chat.withUtilityUsage(partial).withUtilityUsage(record)
        assertEquals(listOf(record), updated.utilityUsageRecords)
        val total = aggregateApiUsageRecords(updated.utilityUsageRecords + record)
        assertEquals(1, total.reportedRequests)
        assertEquals(10L, total.inputTokens)
        assertEquals(20L, total.outputTokens)
    }

    @Test fun importsAndMetadataMergePreserveUtilityEvidenceWithoutPermissions() {
        val full = chat.withUtilityUsage(record)
        val exported = Json.encodeToString(ChatSession.serializer(), full)
        val imported = decodeChatSessionsFromRawJson(exported).single()
        assertEquals(listOf(record), imported.utilityUsageRecords)
        assertEquals(listOf(record), mergeSessionMetadata(full, full.copy(title = "Renamed")).utilityUsageRecords)
        assertFalse(AgentPermissionResolver.resolve(imported).toolsEnabled)
    }

    @Test fun aRecordCannotBeAttributedToAnotherChat() {
        assertFails { chat.withUtilityUsage(record.copy(ownerSessionId = "other-chat")) }
    }

    @Test fun diskHydrationAndMetadataEditsPreserveUtilityRequests() {
        val id = "test-utility-hydration-${PlatformBridge.currentTimeMillis()}"
        val owned = record.copy(ownerSessionId = id)
        val full = chat.copy(id = id, utilityUsageRecords = listOf(owned), messages = listOf(
            MessageNode("message", MessageRole.ASSISTANT, variants = listOf(MessageVariant("variant", "Full history ".repeat(40))))))
        try {
            saveSingleChatSessionToDisk(full)
            val stub = dehydrateChatSessionToCompactStub(full).copy(title = "Renamed")
            val hydrated = hydrateSessionFromDiskIfNeeded(stub)
            assertEquals(listOf(owned), hydrated.utilityUsageRecords)
            assertEquals(full.messages, hydrated.messages)
            assertEquals("Renamed", hydrated.title)
        } finally { PlatformBridge.deleteChatSessionFile(id) }
    }
}
