package io.parchment.ai.harness

import io.parchment.ai.platform.ComputerAction
import io.parchment.ai.platform.ComputerControl
import io.parchment.ai.platform.ComputerScreenshot
import io.parchment.ai.platform.PlatformBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class ComputerOutcome(val text: String, val isError: Boolean = false, val screenshot: ComputerScreenshot? = null)

/** Runs one approved action. Each action is followed by a fresh look at the screen. */
internal object ComputerUse {
    const val TOOL_NAME = "computer_use"
    private const val MAX_TEXT_CHARS = 4_000
    private const val MAX_KEY_CHARS = 64
    private const val SETTLE_MS = 400L
    private val POINTER_ACTIONS = setOf("click", "double_click", "right_click", "move")
    private val ALL_ACTIONS = POINTER_ACTIONS + setOf("screenshot", "type", "key", "scroll")
    private val parser = Json { ignoreUnknownKeys = true }

    /** Replaced in tests, so they never move the real pointer. */
    var control: ComputerControl = PlatformBridge.computerControl

    internal fun parseAction(argumentsJson: String): Result<ComputerAction> = runCatching {
        val args = parser.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject ?: error("Tool arguments must be a JSON object.")
        fun text(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun number(key: String) = (args[key] as? JsonPrimitive)?.intOrNull
        val action = text("action").trim().lowercase()
        require(action in ALL_ACTIONS) { "Unknown action '$action'. Use one of: ${ALL_ACTIONS.sorted().joinToString()}." }
        val x = number("x")
        val y = number("y")
        if (action in POINTER_ACTIONS) require(x != null && y != null) { "$action needs x and y." }
        if (action == "type") require(text("text").isNotEmpty() && text("text").length <= MAX_TEXT_CHARS) { "type needs text of up to $MAX_TEXT_CHARS characters." }
        if (action == "key") require(text("keys").isNotBlank() && text("keys").length <= MAX_KEY_CHARS) { "key needs keys, for example ctrl+c." }
        ComputerAction(action, x, y, text("text"), text("keys").trim(), (number("amount") ?: 3).coerceIn(-20, 20))
    }

    fun perform(argumentsJson: String, isCancelled: () -> Boolean): ComputerOutcome {
        val action = parseAction(argumentsJson).getOrElse { return ComputerOutcome("Error: ${it.message}", isError = true) }
        if (!control.isAvailable) return ComputerOutcome("Error: Computer control is not available. On Android, turn on Parchment in Accessibility settings.", isError = true)
        var done = "Screenshot taken."
        if (action.action != "screenshot") {
            done = control.act(action).getOrElse { return ComputerOutcome("Error: ${it.message}", isError = true) }
            Thread.sleep(SETTLE_MS)
        }
        if (isCancelled()) return ComputerOutcome("$done The run stopped before the next screenshot.")
        val screenshot = control.screenshot().getOrElse { return ComputerOutcome("$done Error: The screen could not be captured: ${it.message}", isError = true) }
        return ComputerOutcome("$done Screen is ${screenshot.width}x${screenshot.height}; use those coordinates.", screenshot = screenshot)
    }
}
