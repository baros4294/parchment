package io.parchment.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Android Foreground Service that keeps long-horizon AI agent turns alive while the screen is off,
 * the user switches apps, or the device is otherwise idle.
 *
 * Declared as `specialUse` rather than `dataSync`: Android 15+ caps `dataSync` at six hours of
 * runtime per 24-hour window and then throws [android.app.RemoteServiceException] unless
 * [onTimeout] calls `stopSelf()`. Other OS background and battery restrictions still apply.
 *
 * Absolute process-owned snapshots reconcile concurrent runs and service recreation. Approval-only
 * waits keep a visible notification while releasing CPU/Wi-Fi holds.
 */
class LongDecodeForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Live process owners, including runs waiting for people. */
    private var activeDecodeCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us after a process kill. The coroutine that was
        // doing the work is gone, so reacquiring locks would only produce a phantom notification and
        // hours of pointless battery drain. Shut down instead; the persisted resume marker lets the
        // user continue the interrupted turn.
        if (intent == null) {
            if (activeDecodeCount == 0) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val action = intent?.getStringExtra(EXTRA_ACTION)
        when (action) {
            ACTION_UPDATE -> {
                // Progress ticks must not bump the reference count or re-acquire locks.
                if (activeDecodeCount > 0) {
                    val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Long decode active…"
                    notifyOrStop(status)
                } else stopSelf(startId)
                return START_NOT_STICKY
            }

            ACTION_SYNC -> {
                activeDecodeCount = (intent?.getIntExtra(EXTRA_OWNERS, 0) ?: 0).coerceAtLeast(0)
                val runnable = (intent?.getIntExtra(EXTRA_RUNNABLE, 0) ?: 0).coerceIn(0, activeDecodeCount)
                if (activeDecodeCount == 0) {
                    shutdownForTimeout()
                    return START_NOT_STICKY
                }
                val statusText = intent?.getStringExtra(EXTRA_STATUS) ?: "Long decode active…"
                // Promote before taking locks; failed promotion must not leave a hold behind.
                if (notifyOrStop(statusText)) {
                    if (runnable > 0) acquireLocksIfNeeded() else releaseLocks()
                }
                return START_NOT_STICKY
            }
            else -> { shutdownForTimeout(); return START_NOT_STICKY }
        }
    }

    /**
     * Android 15+ invokes this when a foreground service type exceeds its runtime allowance.
     * `specialUse` has no allowance, so this is a safety net: stop cleanly rather than let the
     * system throw [android.app.RemoteServiceException].
     */
    override fun onTimeout(startId: Int) {
        shutdownForTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        shutdownForTimeout()
    }

    private fun shutdownForTimeout() {
        activeDecodeCount = 0
        releaseLocks()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /**
     * Swiping the app away from Recents must not kill an active turn. Only stop when nothing is
     * generating.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (activeDecodeCount == 0) {
            releaseLocks()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notify(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        refreshNotificationNotice(this, clearRecoveredFailure = true)
    }

    private fun notifyOrStop(statusText: String): Boolean =
        runCatching { notify(statusText); true }.getOrElse {
            reportFailure(it)
            shutdownForTimeout()
            false
        }

    private fun acquireLocksIfNeeded() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                    setReferenceCounted(false)
                }
            }
            // Process-owned snapshots renew this lease during silent tools/network waits.
            // A live but stalled process cannot retain the CPU lock indefinitely without liveness.
            checkNotNull(wakeLock) { "CPU wake-lock support is unavailable." }.acquire(CPU_LEASE_MS)
        }.onSuccess { clearCpuFailure() }.onFailure { reportFailure(it, cpuLease = true) }
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, WIFI_LOCK_TAG)?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Parchment Long Decode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps long-running AI generation streams active in background"
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        return builder
            .setContentTitle("Parchment — Active Generation")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    companion object {
        private val noticeState = kotlinx.coroutines.flow.MutableStateFlow("")
        val executionNotice: kotlinx.coroutines.flow.StateFlow<String> = noticeState
        private const val CPU_LEASE_MS = 60_000L
        private val noticeLock = Any()
        private var foregroundFailure = ""
        private var cpuFailure = ""
        private var notificationNotice = ""
        private fun publishNotice() {
            noticeState.value = listOf(foregroundFailure, cpuFailure, notificationNotice).filter { it.isNotBlank() }.joinToString("\n")
        }
        fun reportFailure(error: Throwable, cpuLease: Boolean = false) {
            synchronized(noticeLock) {
                val message = error.message ?: error.javaClass.simpleName
                if (cpuLease) cpuFailure = "CPU keep-awake unavailable: $message"
                else foregroundFailure = "Background execution unavailable: $message"
                publishNotice()
            }
            android.util.Log.w("Parchment", "Background execution failed", error)
        }
        private fun clearCpuFailure() = synchronized(noticeLock) { cpuFailure = ""; publishNotice() }
        fun refreshNotificationNotice(context: Context, clearRecoveredFailure: Boolean = false) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val enabled = manager != null && manager.areNotificationsEnabled() &&
                (manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE)
            synchronized(noticeLock) {
                if (clearRecoveredFailure) foregroundFailure = ""
                notificationNotice = if (enabled) "" else if (Build.VERSION.SDK_INT >= 33) {
                    "Notifications are disabled. Active foreground services appear in Android Task Manager."
                } else "Notifications are disabled for Parchment."
                publishNotice()
            }
        }
        private const val CHANNEL_ID = "parchment_long_decode_channel"
        private const val NOTIFICATION_ID = 4101
        private const val WAKE_LOCK_TAG = "ParchmentAI::LongDecodeWakeLock"
        private const val WIFI_LOCK_TAG = "ParchmentAI::LongDecodeWifiLock"
        const val EXTRA_ACTION = "extra_action"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_OWNERS = "extra_owners"
        const val EXTRA_RUNNABLE = "extra_runnable"
        const val ACTION_SYNC = "SYNC"
        const val ACTION_UPDATE = "UPDATE"
    }
}
