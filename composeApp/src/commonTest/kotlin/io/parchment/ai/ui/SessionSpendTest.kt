package io.parchment.ai.ui

import io.parchment.ai.model.*
import kotlin.test.*

class SessionSpendTest {
    private val profile = ProviderProfile("profile", "Profile", ProviderProtocol.OPENAI_RESPONSES, inputPricePerM = 1.0, outputPricePerM = 2.0)
    private fun record(id: String, owner: String = "chat") = ApiUsageRecord(id, wireDialect = "openai_responses", profileId = profile.id, model = "model",
        pricing = TokenPricing(inputPerM = 1.0, outputPerM = 2.0), ownerSessionId = owner,
        usage = ProviderTokenUsage(inputTokens = 10, outputTokens = 20, reasoningTokens = 0, cachedInputTokens = 0,
            cacheCreationInputTokens = 0, shortCacheCreationInputTokens = 0, longCacheCreationInputTokens = 0, isFinal = true))
    private fun chat(messages: List<MessageNode>, utility: List<ApiUsageRecord> = emptyList()) =
        ChatSession("chat", "Chat", 1L, 1L, profile.id, messages = messages, utilityUsageRecords = utility)

    @Test fun everyReplyVersionAndUtilityRequestIsCountedWithoutChargingTheUserAgain() {
        val first = record("first")
        val second = record("second")
        val utility = record("utility").copy(purpose = "title")
        val user = MessageNode("user", MessageRole.USER, listOf(MessageVariant("user-v", "Large prompt ".repeat(1000))))
        val assistant = MessageNode("assistant", MessageRole.ASSISTANT, listOf(
            MessageVariant("first-v", "First reply", apiUsageRecords = listOf(first)),
            MessageVariant("second-v", "Regenerated reply", apiUsageRecords = listOf(second))), activeVariantIndex = 1)
        val session = chat(listOf(user, assistant), listOf(utility, first))
        val spend = sessionSpendSummary(session, profile)
        assertEquals(3, spend.metrics.reportedRequests)
        assertEquals(30L, spend.metrics.inputTokens)
        assertEquals(60L, spend.metrics.outputTokens)
        assertEquals(0.00015, spend.estimateUsd, 1e-12)
        assertFalse(spend.partial)
        assertEquals(spend, sessionSpendSummary(session.copy(messages = listOf(user, assistant.copy(activeVariantIndex = 0))), profile))
    }

    @Test fun forkedHistoryRetainsEvidenceAndOriginalBillingOwnership() {
        val main = record("main", owner = "")
        val utility = record("utility", owner = "").copy(purpose = "summary")
        val legacy = MessageVariant("legacy", "Old reply", tokenMetrics = TokenMetrics(outputTokens = 100, estimatedCostUsd = 0.5))
        val original = chat(listOf(MessageNode("reply", MessageRole.ASSISTANT,
            listOf(MessageVariant("main-v", "Reply", apiUsageRecords = listOf(main)), legacy))), listOf(utility))
        val fork = original.withSpendProvenance().copy(id = "fork")
        assertEquals("chat", fork.messages.first().variants.first().apiUsageRecords.first().ownerSessionId)
        assertEquals("chat", fork.utilityUsageRecords.first().ownerSessionId)
        assertEquals(0.0, sessionSpendSummary(fork, profile).estimateUsd)
        val newRequest = record("new", owner = "fork")
        val withNewCharge = fork.copy(utilityUsageRecords = fork.utilityUsageRecords + newRequest)
        assertEquals(0.00005, sessionSpendSummary(withNewCharge, profile).estimateUsd, 1e-12)
        assertEquals(1, sessionSpendSummary(withNewCharge, profile).metrics.reportedRequests)
        assertEquals(0.5001, sessionSpendSummary(original, profile).estimateUsd, 1e-12)
    }

    @Test fun aLaterPartialCopyDoesNotEraseACompletedRequest() {
        val completed = record("same")
        val partial = completed.copy(usage = completed.usage.copy(outputTokens = 5, isFinal = false))
        val session = chat(listOf(MessageNode("reply", MessageRole.ASSISTANT, listOf(
            MessageVariant("complete", "Complete", apiUsageRecords = listOf(completed)),
            MessageVariant("partial", "Partial", apiUsageRecords = listOf(partial))))))
        val spend = sessionSpendSummary(session, profile)
        assertEquals(1, spend.metrics.reportedRequests)
        assertEquals(20L, spend.metrics.outputTokens)
        assertFalse(spend.partial)
    }

    @Test fun unknownRequestsAndLegacyEstimatesStayExplicitlyPartial() {
        val unknown = record("unknown").copy(usage = ProviderTokenUsage())
        val legacy = MessageVariant("legacy", "Old", tokenMetrics = TokenMetrics(outputTokens = 50))
        val session = chat(listOf(MessageNode("reply", MessageRole.ASSISTANT, listOf(legacy))), listOf(record("known"), unknown))
        val spend = sessionSpendSummary(session, profile)
        assertTrue(spend.partial)
        assertTrue(spend.label.contains("partial"))
        val unavailable = sessionSpendSummary(chat(emptyList(), listOf(unknown)), profile)
        assertEquals("cost unavailable", unavailable.label)
        assertEquals("", sessionSpendSummary(chat(emptyList()), profile).label)
    }

    @Test fun aMissingCacheSplitStaysUnavailableButStillCountsTowardTheLimitAtItsLowestPrice() {
        val noSplit = record("no-split").copy(pricing = TokenPricing(inputPerM = 1.0, outputPerM = 2.0, cacheReadPerM = 0.1),
            usage = ProviderTokenUsage(inputTokens = 1_000_000, outputTokens = 0, isFinal = true))
        val spend = sessionSpendSummary(chat(emptyList(), listOf(noSplit)), profile)
        assertEquals("cost unavailable", spend.label)
        assertEquals(0.1, spend.towardLimitUsd, 1e-12)
        val known = sessionSpendSummary(chat(emptyList(), listOf(record("known"))), profile)
        assertEquals(known.estimateUsd, known.towardLimitUsd, 1e-12)
    }

    @Test fun reportedFreeCostsRemainVisibleWhenTokenUsageIsMissing() {
        val reported = record("free").copy(usage = ProviderTokenUsage(charges = ProviderChargeUsage(reportedTotalUsd = 0.0)))
        val spend = sessionSpendSummary(chat(emptyList(), listOf(reported)), profile)
        assertTrue(spend.label.contains("reported"))
        assertFalse(spend.label.contains("unavailable"))
        assertTrue(spend.partial)
    }
}
