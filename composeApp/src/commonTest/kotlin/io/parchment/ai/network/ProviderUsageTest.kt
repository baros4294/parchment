package io.parchment.ai.network

import io.parchment.ai.model.ApiUsageRecord
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.TokenMetrics
import io.parchment.ai.model.TokenPricing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderUsageTest {
    private val profile = ProviderProfile(id = "fixture", name = "Fixture", protocol = ProviderProtocol.OPENAI_RESPONSES,
        inputPricePerM = 1.0, outputPricePerM = 2.0, cacheReadPricePerM = 0.1)

    private fun parse(dialect: UsageDialect, raw: String, final: Boolean = true) =
        parseProviderUsage(dialect, Json.parseToJsonElement(raw).jsonObject, isFinal = final)

    private fun responses(input: Long, output: Long = 10) = parse(UsageDialect.OPENAI_RESPONSES,
        """{"input_tokens":$input,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},"output_tokens":$output,"output_tokens_details":{"reasoning_tokens":0}}""")

    @Test
    fun openAiWriteLifetimesMatchTheOfficialCostExample() {
        val usage = parse(UsageDialect.OPENAI_RESPONSES, """{
            "input_tokens":12000,
            "input_tokens_details":{"cached_tokens":4000,"cache_write_tokens":7000,
                "cache_write_tokens_details":{"ttl_30m_tokens":3000,"ttl_12h_tokens":4000}},
            "output_tokens":50,"output_tokens_details":{"reasoning_tokens":10}
        }""")
        val record = ApiUsageRecord("request", wireDialect = UsageDialect.OPENAI_RESPONSES.wireName,
            profileId = profile.id, model = "fixture-model", pricing = profile.tokenPricing(), usage = usage)
        val metrics = usageRecordMetrics(record)
        assertEquals(12000L, metrics.inputTokens)
        assertEquals(7000L, metrics.cacheCreationInputTokens)
        assertEquals(11000.0, metrics.cacheCreationCostUnits)
        assertEquals(40L, metrics.outputTokens)
        assertEquals(10L, metrics.reasoningTokens)
        assertEquals(0.01525, record.pricing.estimate(metrics)!!, 1e-12)
    }

    @Test
    fun anthropicTotalInputIncludesReadsAndCreationWithoutDoubleCountingBins() {
        val usage = parse(UsageDialect.ANTHROPIC, """{
            "input_tokens":2048,"cache_read_input_tokens":1800,"cache_creation_input_tokens":248,
            "cache_creation":{"ephemeral_5m_input_tokens":148,"ephemeral_1h_input_tokens":100},"output_tokens":503
        }""")
        val record = ApiUsageRecord("request", wireDialect = UsageDialect.ANTHROPIC.wireName,
            profileId = profile.id, model = "fixture-model", pricing = profile.tokenPricing(), usage = usage)
        assertEquals(4096L, usage.inputTokens)
        assertEquals(248L, usage.cacheCreationInputTokens)
        assertEquals(308.0, cacheCreationCostUnits(record))
        assertNull(usage.reasoningTokens)
        assertEquals(503L, usage.nonReasoningOutputTokens)
        assertEquals(0.003619, record.pricing.estimate(usageRecordMetrics(record))!!, 1e-12)
    }

    @Test
    fun cumulativeAnthropicUpdatesRetainTheOtherInputComponentsAndExplicitZero() {
        val initial = parse(UsageDialect.ANTHROPIC, """{"input_tokens":50,"cache_read_input_tokens":100,
            "cache_creation_input_tokens":20,"cache_creation":{"ephemeral_5m_input_tokens":20,"ephemeral_1h_input_tokens":0},"output_tokens":3}""", false)
        val delta = parse(UsageDialect.ANTHROPIC, """{"input_tokens":80,"output_tokens":10}""", false)
        val updated = initial.updatedBy(delta)
        assertEquals(200L, updated.inputTokens)
        assertEquals(100L, updated.cachedInputTokens)
        assertEquals(20L, updated.cacheCreationInputTokens)
        assertEquals(10L, updated.outputTokens)
        val zero = updated.updatedBy(parse(UsageDialect.ANTHROPIC, """{"output_tokens":0}""", false))
        assertEquals(0L, zero.outputTokens)
        val stopped = zero.updatedBy(parseProviderUsage(UsageDialect.ANTHROPIC, null, isFinal = true))
        assertTrue(stopped.isFinal)
        assertEquals(200L, stopped.inputTokens)
    }

    @Test
    fun geminiCachedInputIsAlreadyIncludedAndThoughtTokensAreSeparate() {
        val interaction = parse(UsageDialect.GEMINI_INTERACTIONS,
            """{"total_input_tokens":100,"total_cached_tokens":40,"total_output_tokens":30,"total_thought_tokens":20}""")
        val content = parse(UsageDialect.GEMINI_CONTENT,
            """{"promptTokenCount":100,"cachedContentTokenCount":40,"candidatesTokenCount":30,"thoughtsTokenCount":20}""")
        assertEquals(interaction, content)
        assertEquals(100L, interaction.inputTokens)
        assertEquals(30L, interaction.nonReasoningOutputTokens)
        assertEquals(20L, interaction.separateReasoningTokens)
    }

    @Test
    fun allRequestsAreAddedAndRepeatedEventsAreCoalescedEvenWithReusedServerIds() {
        val ledger = ProviderUsageLedger(profile, "fixture-model")
        for (input in listOf(1000L, 2000L, 3000L)) {
            val request = ledger.begin(UsageDialect.OPENAI_RESPONSES)
            val usage = responses(input)
            ledger.update(request.requestId, "reused-server-id", UsageDialect.OPENAI_RESPONSES, usage)
            assertNull(ledger.update(request.requestId, "reused-server-id", UsageDialect.OPENAI_RESPONSES, usage))
        }
        val metrics = ledger.metrics()
        assertEquals(6000L, metrics.inputTokens)
        assertEquals(30L, metrics.outputTokens)
        assertEquals(3, metrics.reportedRequests)
        assertTrue(metrics.usageIsComplete)
        assertTrue(metrics.costIsAvailable)
    }

    @Test
    fun pollingAndContinuationReuseRecordedUsageInsteadOfChargingItAgain() {
        val initial = ProviderUsageLedger(profile, "fixture-model")
        val request = initial.begin(UsageDialect.OPENAI_RESPONSES)
        initial.update(request.requestId, "response", UsageDialect.OPENAI_RESPONSES, responses(100, 10))
        val continued = ProviderUsageLedger(profile, "fixture-model", initial.snapshots(), initial.metrics())
        continued.update(null, "response", UsageDialect.OPENAI_RESPONSES, responses(150, 20))
        assertEquals(150L, continued.metrics().inputTokens)
        assertEquals(1, continued.metrics().reportedRequests)
        assertNull(continued.update(null, "foreign-response", UsageDialect.OPENAI_RESPONSES, responses(999)))
        val next = continued.begin(UsageDialect.OPENAI_RESPONSES)
        continued.update(next.requestId, "next", UsageDialect.OPENAI_RESPONSES, responses(200, 30))
        assertEquals(350L, continued.metrics().inputTokens)
        assertEquals(50L, continued.metrics().outputTokens)
    }

    @Test
    fun missingUsageAndReportedZeroHaveDifferentMeanings() {
        val ledger = ProviderUsageLedger(profile, "fixture-model")
        val request = ledger.begin(UsageDialect.OPENAI_RESPONSES)
        assertFalse(ledger.hasReportedInput)
        assertFalse(ledger.metrics().usageIsComplete)
        assertFalse(ledger.metrics().costIsAvailable)
        ledger.update(request.requestId, "response", UsageDialect.OPENAI_RESPONSES, responses(0, 0))
        val metrics = ledger.metrics()
        assertTrue(ledger.hasReportedInput)
        assertTrue(metrics.usageIsComplete)
        assertEquals(0L, metrics.totalTokens)
        assertEquals(0.0, metrics.estimatedCostUsd)
        val final = AiProviderEngine.computeTurnTokenMetrics(0, 0, 0, 0, 500, "visible text", "summary", emptyList(),
            5000, 100, 5000, 0, hasReportedInput = true, hasReportedOutput = true, hasReportedReasoning = true)
        assertEquals(0L, final.totalTokens)
        assertEquals(0.0, final.tokensPerSec)
    }

    @Test
    fun wideTotalsDoNotOverflowAtTheIntBoundary() {
        val ledger = ProviderUsageLedger(profile, "fixture-model")
        repeat(3) {
            val request = ledger.begin(UsageDialect.OPENAI_RESPONSES)
            ledger.update(request.requestId, "response-$it", UsageDialect.OPENAI_RESPONSES, responses(1_500_000_000))
        }
        assertEquals(4_500_000_000L, ledger.metrics().inputTokens)
    }

    @Test
    fun malformedCountsAndNonFinitePricingRemainUnavailable() {
        val bad = parse(UsageDialect.OPENAI_RESPONSES,
            """{"input_tokens":-1,"output_tokens":{},"input_tokens_details":{"cached_tokens":true}}""")
        assertNull(bad.inputTokens)
        assertNull(bad.outputTokens)
        assertTrue(bad.invalidCounts)
        assertFalse(bad.isComplete)
        assertNull(TokenPricing(inputPerM = Double.NaN).estimate(TokenMetrics(inputTokens = 10)))
        assertNull(TokenPricing(inputPerM = Double.MAX_VALUE).estimate(TokenMetrics(inputTokens = Long.MAX_VALUE)))
        assertNull(TokenPricing().estimate(TokenMetrics(inputTokens = 10)))
    }

    @Test
    fun aStartedResponseIsNotFinalJustBecauseItHasCumulativeCounters() {
        val ledger = ProviderUsageLedger(profile, "fixture-model")
        val request = ledger.begin(UsageDialect.ANTHROPIC)
        val start = parse(UsageDialect.ANTHROPIC, """{"input_tokens":50,"cache_read_input_tokens":0,
            "cache_creation_input_tokens":0,"output_tokens":3}""", false)
        ledger.update(request.requestId, "message", UsageDialect.ANTHROPIC, start)
        assertFalse(ledger.metrics().usageIsComplete)
        ledger.update(request.requestId, "message", UsageDialect.ANTHROPIC,
            parseProviderUsage(UsageDialect.ANTHROPIC, null, isFinal = true))
        assertTrue(ledger.metrics().usageIsComplete)
    }
}
