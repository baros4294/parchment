package io.parchment.ai.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import io.parchment.ai.model.StorageFileCategory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlatformSharedHelpersTest {
    private fun desktopConfig() = WorkspaceScanConfig(
        missingDirMessage = { "Directory does not exist: $it" },
        notDirMessage = { "Path is not a directory: $it" },
        emptyDirectoryMessage = "(Empty directory)",
        useTreeCache = true,
        canonicalizeRelativize = false,
        allowEmptyPlainGlobFallback = false,
        requirePositiveSearchFileBytes = true,
        maxSearchFileBytes = 2_000_000L,
        maxSearchHits = 150,
        maxSearchLineChars = 200,
        parallelSearch = true,
        filterSearchByRelPath = true
    )

    private fun androidConfig() = WorkspaceScanConfig(
        missingDirMessage = { "Directory not found: $it" },
        notDirMessage = { "Directory not found: $it" },
        emptyDirectoryMessage = "(Empty workspace directory)",
        useTreeCache = false,
        canonicalizeRelativize = true,
        allowEmptyPlainGlobFallback = true,
        requirePositiveSearchFileBytes = false,
        maxSearchFileBytes = 1_499_999L,
        maxSearchHits = 100,
        maxSearchLineChars = 180,
        parallelSearch = false,
        filterSearchByRelPath = false
    )

    @Test
    fun storageInventoryListsCategorizesAndDeletesSafely() {
        val tempRoot = Files.createTempDirectory("parchment-storage-inv-").toFile()
        val otaDir = Files.createTempDirectory("parchment-ota-inv-").toFile()
        try {
            File(tempRoot, "chats/s1.json").apply { parentFile.mkdirs(); writeText("{}") }
            File(tempRoot, "media/att-photo.png").apply { parentFile.mkdirs(); writeText("att") }
            File(tempRoot, "media/gen-art.png").apply { parentFile.mkdirs(); writeText("gen") }
            File(tempRoot, "settings.json").writeText("{}")
            File(tempRoot, "pending.tmp").writeText("tmp")
            File(otaDir, "update.exe").writeText("exe")

            val outsideFile = File(tempRoot.parentFile, "outside-${System.nanoTime()}.txt").apply {
                writeText("keep")
            }
            try {
                val items = AppStorageInventory.listFiles(tempRoot, extraOtaDir = otaDir)
                val byRel = items.associateBy { it.relativePath }
                assertEquals(StorageFileCategory.CHAT_SESSION, byRel["chats/s1.json"]?.category)
                assertEquals(StorageFileCategory.UPLOADED_MEDIA, byRel["media/att-photo.png"]?.category)
                assertEquals(StorageFileCategory.GENERATED_MEDIA, byRel["media/gen-art.png"]?.category)
                assertEquals(StorageFileCategory.SETTINGS_AND_BACKUPS, byRel["settings.json"]?.category)
                assertEquals(StorageFileCategory.OTA_AND_TEMP, byRel["pending.tmp"]?.category)
                assertEquals(StorageFileCategory.OTA_AND_TEMP, byRel["temp/parchment-ota/update.exe"]?.category)

                val deletedCallbacks = mutableListOf<String>()
                assertFalse(
                    AppStorageInventory.deleteFile(outsideFile.absolutePath, tempRoot, otaDir) {
                        deletedCallbacks.add(it)
                    }
                )
                assertTrue(outsideFile.exists())
                assertTrue(deletedCallbacks.isEmpty())

                val targetMedia = File(tempRoot, "media/gen-art.png")
                assertTrue(
                    AppStorageInventory.deleteFile(targetMedia.absolutePath, tempRoot, otaDir) {
                        deletedCallbacks.add(it)
                    }
                )
                assertFalse(targetMedia.exists())
                assertEquals(listOf(targetMedia.canonicalFile.absolutePath), deletedCallbacks)

                val extraTarget = File(otaDir, "update.exe")
                assertTrue(
                    AppStorageInventory.deleteFile(extraTarget.absolutePath, tempRoot, otaDir) {}
                )
                assertFalse(extraTarget.exists())
            } finally {
                outsideFile.delete()
            }
        } finally {
            tempRoot.deleteRecursively()
            otaDir.deleteRecursively()
        }
    }

    @Test
    fun storageInventoryWipeRespectsPreserveRootAndFontCacheDeduplicates() {
        val dirA = Files.createTempDirectory("parchment-wipe-keep-").toFile()
        val dirB = Files.createTempDirectory("parchment-wipe-drop-").toFile()
        try {
            val childA = File(dirA, "child.txt").apply { writeText("a") }
            val childB = File(dirB, "child.txt").apply { writeText("b") }
            assertTrue(AppStorageInventory.wipeDirectory(dirA, preserveRoot = true))
            assertTrue(dirA.exists())
            assertFalse(childA.exists())

            assertTrue(AppStorageInventory.wipeDirectory(dirB, preserveRoot = false))
            assertFalse(dirB.exists())
            assertFalse(childB.exists())

            val fontFile = File(dirA, "dummy.ttf").apply { writeText("font") }
            val fontCache = ConcurrentHashMap<String, FontFamily>()
            var buildCount = 0
            assertNull(AppStorageInventory.loadCachedFontFamily(fontCache, "   ") { FontFamily.SansSerif })
            assertNull(
                AppStorageInventory.loadCachedFontFamily(
                    fontCache,
                    File(dirA, "missing.ttf").absolutePath
                ) { FontFamily.SansSerif }
            )

            val first = AppStorageInventory.loadCachedFontFamily(fontCache, " \"${fontFile.absolutePath}\" ") {
                buildCount++
                FontFamily.Serif
            }
            val second = AppStorageInventory.loadCachedFontFamily(fontCache, fontFile.absolutePath) {
                buildCount++
                FontFamily.Monospace
            }
            assertNotNull(first)
            assertSame(first, second)
            assertEquals(1, buildCount)
        } finally {
            dirA.deleteRecursively()
            dirB.deleteRecursively()
        }
    }

    @Test
    fun boundedImageCacheCachesHitsEvictsByCountAndBytesAndSupportsRemoval() {
        val tempRoot = Files.createTempDirectory("parchment-img-cache-").toFile()
        try {
            val file1 = File(tempRoot, "one.png").apply { writeText("1") }
            val file2 = File(tempRoot, "two.png").apply { writeText("2") }
            val file3 = File(tempRoot, "three.png").apply { writeText("3") }
            val resolvedMap = mapOf(
                "one.png" to file1,
                "two.png" to file2,
                "three.png" to file3,
                file1.absolutePath to file1,
                file2.absolutePath to file2,
                file3.absolutePath to file3
            )
            var decodeCalls = 0
            // Each 1024x512 RGBA image = 2 MiB; limit = 3 images / 4 MiB
            val cache = BoundedImageCache { 3 to (4L * 1024L * 1024L) }

            assertNull(cache.getOrLoad("   ", { resolvedMap[it] }, { ImageBitmap(16, 16) }))
            assertNull(cache.getOrLoad("missing.png", { resolvedMap[it] }, { ImageBitmap(16, 16) }))
            assertNull(cache.getOrLoad("one.png", { resolvedMap[it] }, { null }))

            val bmp1 = cache.getOrLoad(file1.absolutePath, { resolvedMap[it] }) {
                decodeCalls++
                ImageBitmap(1024, 512)
            }
            assertNotNull(bmp1)
            assertSame(
                bmp1,
                cache.getOrLoad(file1.absolutePath, { resolvedMap[it] }) {
                    decodeCalls++
                    ImageBitmap(16, 16)
                }
            )
            assertEquals(1, decodeCalls)

            cache.getOrLoad(file2.absolutePath, { resolvedMap[it] }) {
                decodeCalls++
                ImageBitmap(1024, 512)
            }
            // Adding a 3rd 2 MiB bitmap exceeds 4 MiB total, evicting eldest (file1)
            cache.getOrLoad(file3.absolutePath, { resolvedMap[it] }) {
                decodeCalls++
                ImageBitmap(1024, 512)
            }
            assertNull(cache.get(file1.absolutePath))
            assertNotNull(cache.get(file2.absolutePath))
            assertNotNull(cache.get(file3.absolutePath))

            cache.remove(file2.absolutePath)
            assertNull(cache.get(file2.absolutePath))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun workspaceTreeScannerListsGlobsSearchesAndInvalidatesCache() {
        val root = Files.createTempDirectory("parchment-scanner-").toFile()
        try {
            File(root, "src/App.kt").apply {
                parentFile.mkdirs()
                writeText("fun greet() = \"hello world\"\n")
            }
            File(root, "notes/readme.md").apply {
                parentFile.mkdirs()
                writeText("other content\n")
            }

            val desktopScanner = WorkspaceTreeScanner(desktopConfig())
            val flat = desktopScanner.list(root.absolutePath, "", recursive = false).getOrThrow()
            assertTrue(flat.contains("notes/"))
            assertTrue(flat.contains("src/"))

            val globbed = desktopScanner.glob(root.absolutePath, "**/*.kt", maxResults = 10).getOrThrow()
            assertTrue(globbed.contains("src/App.kt"))
            assertFalse(globbed.contains("readme.md"))

            val hit = desktopScanner.search(root.absolutePath, "hello\\s+world", "*.kt").getOrThrow()
            assertTrue(hit.contains("src/App.kt:1: fun greet()"))

            val beforeInvalidate = desktopScanner.list(root.absolutePath, "", recursive = true).getOrThrow()
            assertTrue(beforeInvalidate.contains("src/App.kt"))

            File(root, "src/Added.kt").writeText("fun added() = true\n")
            // Cached tree still active until invalidated
            val stillCached = desktopScanner.list(root.absolutePath, "", recursive = true).getOrThrow()
            assertFalse(stillCached.contains("src/Added.kt"))

            desktopScanner.invalidate()
            val afterInvalidate = desktopScanner.list(root.absolutePath, "", recursive = true).getOrThrow()
            assertTrue(afterInvalidate.contains("src/Added.kt"))

            val missingDesktop = desktopScanner.list(root.absolutePath, "nope", recursive = false)
            assertTrue(missingDesktop.exceptionOrNull()?.message?.contains("Directory does not exist: nope") == true)

            val androidScanner = WorkspaceTreeScanner(androidConfig())
            val missingAndroid = androidScanner.list(root.absolutePath, "nope", recursive = false)
            assertTrue(missingAndroid.exceptionOrNull()?.message?.contains("Directory not found: nope") == true)
            val androidSearch = androidScanner.search(root.absolutePath, "added", "*.kt").getOrThrow()
            assertTrue(androidSearch.contains("src/Added.kt:1:"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun desktopOwnedShellExecutesCapturesOutputAndRespectsPreCancellation() {
        val root = Files.createTempDirectory("parchment-shell-").toFile()
        try {
            val cancelled = DesktopOwnedShell.execute(
                workspaceRoot = root.absolutePath,
                command = "echo never",
                timeoutSeconds = 10,
                onOutputDelta = null,
                isCancelled = { true }
            )
            assertTrue(cancelled.isFailure)
            assertTrue(cancelled.exceptionOrNull()?.message?.contains("cancelled before shell startup") == true)

            val deltas = mutableListOf<String>()
            val ok = DesktopOwnedShell.execute(
                workspaceRoot = root.absolutePath,
                command = "echo parchment-shell-ok",
                timeoutSeconds = 15,
                onOutputDelta = { deltas.add(it) },
                isCancelled = { false }
            ).getOrThrow()
            assertTrue(ok.contains("Exit Code: 0"))
            assertTrue(ok.contains("parchment-shell-ok"))
            assertTrue(deltas.any { it.contains("parchment-shell-ok") })
        } finally {
            root.deleteRecursively()
        }
    }
}
