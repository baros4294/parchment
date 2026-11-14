package io.parchment.ai.ui

import androidx.compose.foundation.layout.Arrangement
import io.parchment.ai.strings.Strings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.parchment.ai.model.ApiUsageRecord
import io.parchment.ai.model.ChatSession
import io.parchment.ai.network.aggregateApiUsageRecords

internal fun ChatSession.withUtilityUsage(record: ApiUsageRecord): ChatSession {
    require(record.ownerSessionId.isBlank() || record.ownerSessionId == id) { "This background usage belongs to a different chat." }
    val index = utilityUsageRecords.indexOfFirst { it.requestId == record.requestId }
    val records = if (index < 0) utilityUsageRecords + record
        else utilityUsageRecords.toMutableList().also { it[index] = record }
    return copy(utilityUsageRecords = records)
}

@Composable
internal fun UtilityUsageSummary(records: List<ApiUsageRecord>) {
    if (records.isEmpty()) return
    val palette = LocalParchmentPalette.current
    val metrics = remember(records) { aggregateApiUsageRecords(records) }
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s6)) {
        val cost = SessionSpendSummary(metrics, 0.0, 0).label
        BasicText(Strings.backgroundRequests(metrics.reportedRequests, cost), style = palette.uiBodyStyle)
        SepiaButton(if (expanded) Strings.hideBackgroundUsage else Strings.showBackgroundUsage, compact = true,
            style = SepiaButtonStyle.GHOST, onClick = { expanded = !expanded })
        if (expanded) Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.s6)) {
            records.groupBy { it.purpose }.forEach { (purpose, group) ->
                val part = aggregateApiUsageRecords(group)
                val label = when (purpose) {
                    "title" -> Strings.titles
                    "summary" -> Strings.summaries
                    "compaction" -> Strings.compaction
                    "translation" -> Strings.translation
                    "draft" -> Strings.replyDrafts
                    else -> Strings.otherBackgroundRequests
                }
                BasicText(Strings.requests(label, part.reportedRequests), style = palette.uiBodyStyle)
                BasicText(Strings.inputOutputReasoning(part.inputTokens, part.outputTokens, part.reasoningTokens), style = palette.uiLabelStyle)
                BasicText(Strings.cacheReadCacheWriteUsage(part.cachedInputTokens, part.cacheCreationInputTokens, if (part.usageIsComplete) "complete" else "partial"), style = palette.uiLabelStyle)
                BasicText(SessionSpendSummary(part, 0.0, 0).label, style = palette.uiLabelStyle)
                if (part.unpricedCostComponents.isNotEmpty()) BasicText(
                    Strings.unpriced(part.unpricedCostComponents.sorted().joinToString(", ")), style = palette.uiLabelStyle)
            }
        }
    }
}
