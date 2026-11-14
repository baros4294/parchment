package io.parchment.ai.platform

/** Devices with less physical memory than this count as low-memory (a nominal 4 GB device reports a little less). */
internal const val LOW_MEMORY_BYTES = 4L * 1024L * 1024L * 1024L

expect object PlatformRuntime {
    val platformName: String
    val isAndroid: Boolean
    /** Under 4 GB of RAM (or Android's low-RAM flag): caches shrink so the app stays responsive. */
    val isLowMemoryDevice: Boolean
    val backgroundExecutionNotice: kotlinx.coroutines.flow.StateFlow<String>

    fun currentTimeMillis(): Long
    fun monotonicTimeMillis(): Long
    fun createRunId(): String

    fun registerStateFlushCallback(callback: () -> Unit)
    fun flushPendingStateNow()
    fun registerBackHandler(handler: (() -> Boolean)?)
    fun handleSystemBackPressed(): Boolean
    fun isIgnoringBatteryOptimizations(): Boolean
    fun requestUnrestrictedBackgroundBattery()

    // Process-owned run snapshots include preparation, tools, compaction and approval waits.
    fun synchronizeBackgroundExecution(owners: Int, runnable: Int, status: String)
    fun notifyLongDecodeProgress(modelName: String, outputTokens: Int, elapsedSeconds: Int)

    // In-app update download and install.
    fun createOtaStagedDownload(assetName: String): OtaStagedDownload
}
