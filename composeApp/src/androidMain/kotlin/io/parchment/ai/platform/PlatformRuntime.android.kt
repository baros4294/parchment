package io.parchment.ai.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.parchment.ai.LongDecodeForegroundService
import io.parchment.ai.OtaInstallReceiver
import java.io.File
import java.io.FileInputStream

actual object PlatformRuntime {
    actual fun createRunId(): String = java.util.UUID.randomUUID().toString()
    actual val backgroundExecutionNotice: kotlinx.coroutines.flow.StateFlow<String> = LongDecodeForegroundService.executionNotice
    actual val platformName: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    actual val isAndroid: Boolean = true
    actual val isLowMemoryDevice: Boolean by lazy {
        val manager = AndroidPlatformHost.appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        manager?.isLowRamDevice == true || (info.totalMem in 1 until LOW_MEMORY_BYTES)
    }

    @Volatile
    private var stateFlushCallback: (() -> Unit)? = null
    @Volatile
    private var backHandlerCallback: (() -> Boolean)? = null
    @Volatile
    private var lastReportedDecodeSec: Int = -1

    actual fun currentTimeMillis(): Long = System.currentTimeMillis()
    actual fun monotonicTimeMillis(): Long = android.os.SystemClock.uptimeMillis()

    actual fun registerStateFlushCallback(callback: () -> Unit) {
        stateFlushCallback = callback
    }

    actual fun flushPendingStateNow() {
        runCatching { stateFlushCallback?.invoke() }
    }

    actual fun registerBackHandler(handler: (() -> Boolean)?) {
        backHandlerCallback = handler
    }

    actual fun handleSystemBackPressed(): Boolean {
        return runCatching { backHandlerCallback?.invoke() == true }.getOrDefault(false)
    }

    actual fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        val ctx = AndroidPlatformHost.appContext ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
        } else true
    }.getOrDefault(true)

    actual fun requestUnrestrictedBackgroundBattery() {
        val ctx = AndroidPlatformHost.appContext ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            }
        }
    }

    actual fun synchronizeBackgroundExecution(owners: Int, runnable: Int, status: String) {
        val ctx = AndroidPlatformHost.appContext ?: return
        if (owners == 0) {
            lastReportedDecodeSec = -1
            ctx.stopService(Intent(ctx, LongDecodeForegroundService::class.java))
            return
        }
        ensureNotificationPermission(ctx)
        runCatching {
            val intent = Intent(ctx, LongDecodeForegroundService::class.java).apply {
                putExtra(LongDecodeForegroundService.EXTRA_ACTION, LongDecodeForegroundService.ACTION_SYNC)
                putExtra(LongDecodeForegroundService.EXTRA_OWNERS, owners)
                putExtra(LongDecodeForegroundService.EXTRA_RUNNABLE, runnable)
                putExtra(LongDecodeForegroundService.EXTRA_STATUS, status)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }.onFailure { LongDecodeForegroundService.reportFailure(it) }
    }

    actual fun notifyLongDecodeProgress(modelName: String, outputTokens: Int, elapsedSeconds: Int) {
        if (elapsedSeconds == lastReportedDecodeSec) return
        lastReportedDecodeSec = elapsedSeconds
        val ctx = AndroidPlatformHost.appContext ?: return
        runCatching {
            val intent = Intent(ctx, LongDecodeForegroundService::class.java).apply {
                putExtra(LongDecodeForegroundService.EXTRA_ACTION, LongDecodeForegroundService.ACTION_UPDATE)
                putExtra(LongDecodeForegroundService.EXTRA_STATUS, "$modelName • +$outputTokens tok • ${elapsedSeconds}s")
            }
            ctx.startService(intent)
        }.onFailure { LongDecodeForegroundService.reportFailure(it) }
    }

    private fun ensureNotificationPermission(ctx: Context) {
        LongDecodeForegroundService.refreshNotificationNotice(ctx)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        AndroidPlatformHost.mainActivityRef?.get()?.requestNotificationPermission()
    }

    actual fun createOtaStagedDownload(assetName: String): OtaStagedDownload {
        val ctx = AndroidPlatformHost.appContext ?: error("Android application context is not initialized.")
        // Android verifies the APK signature against the installed app before updating it.
        return OtaStagedFile(File(File(ctx.cacheDir, "ota"), "update.apk"), requireDigest = false, installer = ::installOtaApk)
    }

    private fun installOtaApk(apk: File): Result<String> = runCatching {
        // Save everything pending before the package installer starts.
        flushPendingStateNow()

        val ctx = AndroidPlatformHost.appContext ?: error("Android application context is not initialized.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(permIntent)
            error("Please allow 'Install unknown apps' for Parchment in the Android settings screen that just opened, then tap Update again.")
        }

        val packageInstaller = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        session.use { activeSession ->
            activeSession.openWrite("parchment_ota.apk", 0L, apk.length()).use { out ->
                FileInputStream(apk).use { input -> input.copyTo(out, 64 * 1024) }
                activeSession.fsync(out)
            }

            val callbackIntent = Intent(ctx, OtaInstallReceiver::class.java).apply {
                setPackage(ctx.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(ctx, sessionId, callbackIntent, flags)
            activeSession.commit(pendingIntent.intentSender)
        }

        val committedKb = apk.length() / 1024
        apk.delete() // The installer session now holds its own copy.
        "Android PackageInstaller session committed ($committedKb KB). Confirm the system prompt if shown."
    }
}
