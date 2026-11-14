package io.parchment.ai.platform

import android.content.Context
import io.parchment.ai.MainActivity
import java.io.File
import java.lang.ref.WeakReference

internal object AndroidPlatformHost {
    @Volatile
    var appContext: Context? = null
    @Volatile
    var mainActivityRef: WeakReference<MainActivity>? = null

    fun initAndroid(context: Context, activity: MainActivity) {
        appContext = context.applicationContext
        mainActivityRef = WeakReference(activity)
        // Ensure default local workspace folder exists
        val wsDir = File(resolveStorageDir(), "workspace")
        if (!wsDir.exists()) wsDir.mkdirs()
        PlatformVoice.ensureTtsInitialized(context.applicationContext)
    }

    fun resolveStorageDir(): File {
        val ctx = appContext
        val dir = ctx?.filesDir ?: ctx?.noBackupFilesDir ?: File(System.getProperty("java.io.tmpdir", "/data/local/tmp"), "parchment")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
