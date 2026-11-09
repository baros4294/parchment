package io.parchment.ai.ui

import io.parchment.ai.model.ApiUsageRecord
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.TokenMetrics
import io.parchment.ai.network.aggregateApiUsageRecords

internal data class SessionSpendSummary(val metrics: TokenMetrics, val legacyEstimateUsd: Double, val legacyTurns: Int) {
    val estimateUsd: Double get() = (metrics.estimatedCostUsd + legacyEstimateUsd).takeIf { it.isFinite() && it >= 0 } ?: 0.0
    val partial: Boolean get() = legacyTurns > 0 || !metrics.usageIsComplete || !metrics.costIsAvailable
    val label: String get() = when {
        metrics.reportedRequests == 0 && legacyTurns == 0 -> ""
        estimateUsd == 0.0 && partial && !metrics.costIsAvailable -> "cost unavailable"
        else -> (if (legacyTurns == 0 && metrics.reportedCostRequests == metrics.reportedRequests && metrics.costIsAvailable) "" else "~") +
            formatUsdCost(estimateUsd) +
            (if (legacyTurns == 0 && metrics.reportedCostRequests == metrics.reportedRequests && metrics.costIsAvailable) " · reported" else "") +
            if (partial) " · partial" else ""
    }
}

internal fun sessionSpendSummary(session: ChatSession, profile: ProviderProfile): SessionSpendSummary {
    val requests = linkedMapOf<String, ApiUsageRecord>()
    fun add(record: ApiUsageRecord) {
        if (record.requestId.isBlank() || record.ownerSessionId.isNotBlank() && record.ownerSessionId != session.id) return
        val existing = requests[record.requestId]
        if (existing == null || !existing.usage.isFinal || record.usage.isFinal) requests[record.requestId] = record
    }
    var legacyCost = 0.0
    var legacyTurns = 0
    session.messages.forEach { node ->
        node.variants.forEach { variant ->
            variant.apiUsageRecords.forEach(::add)
            if (node.role == MessageRole.ASSISTANT && variant.apiUsageRecords.isEmpty() &&
                (variant.legacyUsageOwnerSessionId.isBlank() || variant.legacyUsageOwnerSessionId == session.id) &&
                (variant.tokenMetrics.totalTokens > 0L || variant.tokenMetrics.estimatedCostUsd > 0.0)) {
                legacyTurns++
                val estimate = variant.tokenMetrics.estimatedCostUsd.takeIf { it.isFinite() && it > 0 }
                    ?: profile.calculateTurnCostUsd(variant.tokenMetrics)
                if (estimate.isFinite() && estimate >= 0) legacyCost += estimate
            }
        }
    }
    session.utilityUsageRecords.forEach(::add)
    return SessionSpendSummary(aggregateApiUsageRecords(requests.values), legacyCost, legacyTurns)
}

/** Preserve copied evidence while keeping its original billing owner through future forks. */
internal fun ChatSession.withSpendProvenance(): ChatSession {
    fun owned(record: ApiUsageRecord) = if (record.ownerSessionId.isBlank()) record.copy(ownerSessionId = id) else record
    return copy(
        utilityUsageRecords = utilityUsageRecords.map(::owned),
        messages = messages.map { node -> node.copy(variants = node.variants.map { variant ->
            variant.copy(apiUsageRecords = variant.apiUsageRecords.map(::owned),
                legacyUsageOwnerSessionId = variant.legacyUsageOwnerSessionId.ifBlank { id })
        }) }
    )
}
