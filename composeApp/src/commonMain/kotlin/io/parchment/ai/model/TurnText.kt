package io.parchment.ai.model

/**
 * A turn's text placed in the order it was written: what came before each tool, then the rest.
 * [beforeTool] runs parallel to the turn's tool list.
 */
data class TurnTextLayout(val beforeTool: List<String>, val finalText: String) {
    val hasSteps: Boolean get() = beforeTool.any { it.isNotEmpty() }

    /** The whole turn as one passage, for views that do not show the tool steps. */
    val joinedText: String get() = (beforeTool + finalText).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
}

/**
 * The turn's full text stays in `content`; each tool records the part written before it. If the
 * text no longer starts with those parts, because it was edited or replaced, the parts are ignored.
 * While streaming, the visible text can trail what the tools recorded, so parts are cut to fit.
 */
fun turnTextLayout(content: String, tools: List<ToolCallRecord>): TurnTextLayout {
    val unordered = TurnTextLayout(List(tools.size) { "" }, content)
    if (tools.none { it.precedingText.isNotEmpty() }) return unordered
    val written = tools.joinToString("") { it.precedingText }
    if (content.length >= written.length) {
        if (!content.startsWith(written)) return unordered
        return TurnTextLayout(tools.map { it.precedingText }, content.substring(written.length))
    }
    if (!written.startsWith(content)) return unordered
    var visible = content.length
    return TurnTextLayout(tools.map { tool ->
        tool.precedingText.take(visible).also { visible -= it.length }
    }, "")
}

/** The text written since the last tool, which belongs to the tool about to run. */
fun unclaimedStepText(content: CharSequence, tools: List<ToolCallRecord>): String {
    val claimed = tools.sumOf { it.precedingText.length }
    return if (content.length > claimed) content.substring(claimed) else ""
}
