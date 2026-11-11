package io.parchment.ai.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.withImportedPermissions
import io.parchment.ai.platform.AtomicFileStorage
import io.parchment.ai.platform.OtaStagedFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.*

class OtaUpdateEngineHttpTest {
    private val root: File = Files.createTempDirectory("parchment-ota-test-").toFile()
    private val staged = File(root, "update/installer.exe")
    private val payload = Random(11).nextBytes(256_000)
    private val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private var installs = 0

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    private fun release(url: String, size: Long = payload.size.toLong(), sha: String = checksum) =
        OtaReleaseInfo("v9.0.0", "9.0.0", "Update", "", "", "Parchment-Setup.exe", url, size, true, sha)

    private fun <T> serving(respond: (HttpExchange) -> Unit, block: (String) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange -> try { respond(exchange) } finally { exchange.close() } }
        server.start()
        try { return block("http://127.0.0.1:${server.address.port}/asset") } finally { server.stop(0) }
    }

    private fun sendAll(exchange: HttpExchange, body: ByteArray = payload) {
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.write(body)
    }

    private fun run(release: OtaReleaseInfo, requireDigest: Boolean = true, officialBuild: Boolean = true): List<OtaUpdateState> = runBlocking {
        OtaUpdateEngine.downloadAndInstall(release, officialBuild = officialBuild) {
            OtaStagedFile(staged, requireDigest) { installs++; Result.success("installed") }
        }.toList()
    }

    private fun assertRejected(states: List<OtaUpdateState>, expected: String) {
        val error = assertIs<OtaUpdateState.Error>(states.last())
        assertTrue(expected in error.message, error.message)
        assertEquals(0, installs, "A rejected download must not reach the installer")
        assertFalse(staged.exists(), "A rejected download must be removed")
    }

    @Test fun verifiedDownloadReachesTheInstaller() = serving({ sendAll(it) }) { url ->
        val states = run(release(url))
        assertEquals("installed", assertIs<OtaUpdateState.Installing>(states.last()).message)
        assertEquals(1, installs)
        assertContentEquals(payload, staged.readBytes())
    }

    @Test fun truncatedDownloadIsNotInstalled() = serving({ exchange ->
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.write(payload, 0, payload.size / 2)
    }) { url -> assertRejected(run(release(url)), "incomplete") }

    @Test fun oversizedDownloadIsNotInstalled() = serving({ sendAll(it, payload + payload) }) { url ->
        assertRejected(run(release(url)), "larger")
    }

    @Test fun checksumMismatchIsNotInstalled() = serving({ sendAll(it) }) { url ->
        assertRejected(run(release(url, sha = "0".repeat(64))), "checksum")
    }

    @Test fun missingChecksumIsRefusedWhereTheSystemDoesNotVerifySignatures() = serving({ sendAll(it) }) { url ->
        assertRejected(run(release(url, sha = "")), "no checksum")
        assertIs<OtaUpdateState.Installing>(run(release(url, sha = ""), requireDigest = false).last())
        assertEquals(1, installs)
    }

    @Test fun unknownSizeIsNotInstalled() = serving({ exchange ->
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.write(payload)
    }) { url -> assertRejected(run(release(url, size = 0L)), "size is unknown") }

    @Test fun serverErrorIsNotInstalled() = serving({ it.sendResponseHeaders(503, -1) }) { url ->
        assertRejected(run(release(url)), "HTTP 503")
    }

    @Test fun cancelledDownloadInstallsNothingAndCleansUp() = serving({ exchange ->
        exchange.sendResponseHeaders(200, payload.size.toLong())
        runCatching {
            exchange.responseBody.write(payload, 0, 1_024)
            exchange.responseBody.flush()
            Thread.sleep(3_000)
        }
    }) { url ->
        runBlocking {
            OtaUpdateEngine.downloadAndInstall(release(url), officialBuild = true) {
                OtaStagedFile(staged, true) { installs++; Result.success("installed") }
            }.first { it is OtaUpdateState.Downloading && it.downloadedBytes > 0L }
        }
        val deadline = System.currentTimeMillis() + 3_000
        while (staged.exists() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(0, installs)
        assertFalse(staged.exists())
    }

    @Test fun workStartedDuringDownloadStopsTheInstall() = serving({ sendAll(it) }) { url ->
        val states = runBlocking {
            OtaUpdateEngine.downloadAndInstall(release(url), { "a chat is running" }, officialBuild = true) {
                OtaStagedFile(staged, true) { installs++; Result.success("installed") }
            }.toList()
        }
        assertRejected(states, "a chat is running")
    }

    @Test fun aDevelopmentBuildNeverDownloadsOrInstalls() = serving({ sendAll(it) }) { url ->
        val states = run(release(url), officialBuild = false)
        assertEquals(listOf<OtaUpdateState>(OtaUpdateState.Error(DEV_BUILD_UPDATE_MESSAGE)), states)
        assertEquals(0, installs)
        assertFalse(staged.exists())
    }

    @Test fun updatesWaitForRunningChatsAndQueuedPrompts() {
        assertNull(updateBlockedReason(runningChats = 0, queuedPrompts = 0))
        assertTrue("2 running chat" in requireNotNull(updateBlockedReason(runningChats = 2, queuedPrompts = 1)))
        assertTrue("1 queued message" in requireNotNull(updateBlockedReason(runningChats = 0, queuedPrompts = 1)))
    }

    @Test fun githubDigestParsingRequiresARealSha256() {
        val hex = "a".repeat(64)
        assertEquals(hex, sha256FromAssetDigest("sha256:$hex"))
        assertEquals(hex, sha256FromAssetDigest("sha256:${"A".repeat(64)}"))
        listOf(null, "", "sha256:short", "md5:$hex", "sha256:${"g".repeat(64)}").forEach {
            assertEquals("", sha256FromAssetDigest(it), it.toString())
        }
    }

    @Test fun importedSettingsCannotRepointUpdates() {
        val imported = AppStateSnapshot(otaGithubRepo = "attacker/lookalike")
        assertEquals("owner/current", imported.withImportedPermissions("owner/current").otaGithubRepo)
        assertEquals(AppStateSnapshot().otaGithubRepo, imported.withImportedPermissions().otaGithubRepo)
    }

    @Test fun failedExportLeavesTheEarlierFileIntact() {
        val export = File(root, "chat.zip").apply { writeText("earlier") }
        assertFailsWith<IOException> {
            AtomicFileStorage.writeBytes(export, "new".encodeToByteArray()) { _, _ -> throw IOException("disk full") }
        }
        assertEquals("earlier", export.readText())
        assertFalse(File(root, "chat.zip.tmp").exists())
        AtomicFileStorage.writeBytes(export, "new".encodeToByteArray())
        assertEquals("new", export.readText())
    }
}
