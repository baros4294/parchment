package io.parchment.ai.platform

import java.io.File

internal object DesktopPlatformHost {
    @Volatile
    private var cachedStorageDir: File? = null

    fun resolveStorageDir(): File {
        cachedStorageDir?.let { if (it.exists()) return it }
        // 0. Test workers get their own temporary folder, so tests never touch the real %LOCALAPPDATA%\ParchmentAI.
        if (!System.getProperty("org.gradle.test.worker").isNullOrBlank()) {
            val testDir = java.nio.file.Files.createTempDirectory("parchment-test-data-").toFile()
            val ownedPath = testDir.canonicalFile.toPath()
            Runtime.getRuntime().addShutdownHook(Thread({
                val temporaryRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile.toPath()
                if (testDir.canonicalFile.toPath() == ownedPath && ownedPath.startsWith(temporaryRoot) &&
                    testDir.name.startsWith("parchment-test-data-")
                ) {
                    runCatching { AtomicFileStorage.deleteTree(ownedPath) }
                }
            }, "parchment-test-storage-cleanup"))
            cachedStorageDir = testDir
            return testDir
        }
        // 1. Check if portable 'data' directory exists alongside app launcher
        val portableDir = File(System.getProperty("user.dir"), "parchment-portable-data")
        if (portableDir.exists() && portableDir.isDirectory && portableDir.canWrite()) {
            cachedStorageDir = portableDir
            return portableDir
        }
        // 2. Standard isolated per-user app data directory
        val localAppData = System.getenv("LOCALAPPDATA")
        val baseDir = if (!localAppData.isNullOrBlank()) {
            File(localAppData, "ParchmentAI")
        } else {
            File(System.getProperty("user.home"), ".parchment-ai")
        }
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        cachedStorageDir = baseDir
        return baseDir
    }

    fun otaTempDir(): File = File(System.getProperty("java.io.tmpdir"), "parchment-ota")
}
