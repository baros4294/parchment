package io.parchment.ai.runtime

import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Some phones cut an app's network soon after it leaves the screen, even with a foreground
 * service. Coming back is the moment to find out whether a connection survived that.
 */
internal object AppForeground {
    private val resumedAtState = MutableStateFlow(0L)
    val resumedAt: StateFlow<Long> = resumedAtState.asStateFlow()

    @Volatile var leftAt: Long = 0L
        private set

    fun markLeft() { leftAt = PlatformBridge.monotonicTimeMillis() }
    fun markResumed() { resumedAtState.value = PlatformBridge.monotonicTimeMillis() }
}
