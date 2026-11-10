package io.parchment.ai.network

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.parchment.ai.ParchmentBuildConfig
import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class OtaReleaseInfo(
    val tagName: String,
    val versionString: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val publishedAt: String,
    val assetName: String,
    val assetDownloadUrl: String,
    val assetSizeBytes: Long,
    val isNewerThanCurrent: Boolean,
    val assetSha256: String = ""
)

/** One downloaded installer, staged on disk until it is verified and handed to the platform. */
interface OtaStagedDownload {
    fun write(buffer: ByteArray, count: Int)

    /** Throws unless the complete download matches the published size and checksum. */
    fun finish(expectedBytes: Long, expectedSha256: String)

    fun install(): Result<String>

    /** Removes the staged file unless the platform installer now owns it. */
    fun discard()
}

sealed class OtaUpdateState {
    data object Idle : OtaUpdateState()
    data object Checking : OtaUpdateState()
    data class Available(val release: OtaReleaseInfo) : OtaUpdateState()
    data class UpToDate(val currentVersion: String, val latestRelease: OtaReleaseInfo?) : OtaUpdateState()
    data class Downloading(val release: OtaReleaseInfo, val downloadedBytes: Long, val totalBytes: Long) : OtaUpdateState() {
        val progressPercent: Int
            get() = if (totalBytes > 0L) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
    }
    data class Installing(val message: String) : OtaUpdateState()
    data class Error(val message: String) : OtaUpdateState()
}

private val GITHUB_REPO_NAME = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
private val SHA256_HEX = Regex("[0-9a-f]{64}")

/** Reads GitHub's nullable `sha256:<hex>` asset digest; anything else counts as not published. */
/** An update closes the app, so it must wait until no chat is running and no prompt is queued. */
internal fun updateBlockedReason(runningChats: Int, queuedPrompts: Int): String? = when {
    runningChats > 0 -> "Finish or stop $runningChats running chat(s) before updating, so no work is interrupted."
    queuedPrompts > 0 -> "Send or clear $queuedPrompts queued message(s) before updating, so they are not lost."
    else -> null
}

internal fun sha256FromAssetDigest(rawDigest: String?): String =
    rawDigest.orEmpty().trim().removePrefix("sha256:").lowercase().takeIf(SHA256_HEX::matches).orEmpty()

object OtaUpdateEngine {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient
        get() = AiProviderEngine.httpClient

    val currentAppVersion: String
        get() = ParchmentBuildConfig.APP_VERSION

    /**
     * Queries GitHub Releases for the latest `.apk` (on Android) or `.exe` installer (on Windows)
     * without opening a browser.
     */
    suspend fun checkForUpdates(
        githubRepo: String = ParchmentBuildConfig.DEFAULT_GITHUB_REPO
    ): Result<OtaReleaseInfo> = runCatching {
        val cleanRepo = githubRepo.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .trim('/')
            .ifBlank { ParchmentBuildConfig.DEFAULT_GITHUB_REPO }
        check(GITHUB_REPO_NAME.matches(cleanRepo)) { "The update repository must be in owner/name form." }

        // Try /releases/latest first, then fallback to /releases?per_page=5
        val latestUrl = "https://api.github.com/repos/$cleanRepo/releases/latest"
        val listUrl = "https://api.github.com/repos/$cleanRepo/releases?per_page=5"

        var releaseObj: JsonObject? = null

        val latestResp = runCatching {
            httpClient.get(latestUrl) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "Parchment-OTA-Updater/${ParchmentBuildConfig.APP_VERSION}")
            }
        }.getOrNull()

        if (latestResp != null && latestResp.status.isSuccess()) {
            releaseObj = runCatching { json.parseToJsonElement(latestResp.bodyAsText()).jsonObject }.getOrNull()
        }

        if (releaseObj == null) {
            val listResp = httpClient.get(listUrl) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "Parchment-OTA-Updater/${ParchmentBuildConfig.APP_VERSION}")
            }
            if (!listResp.status.isSuccess()) {
                error("GitHub Releases HTTP ${listResp.status.value}: ${listResp.bodyAsText().take(200)}")
            }
            val arr = json.parseToJsonElement(listResp.bodyAsText()) as? JsonArray
            releaseObj = arr?.firstOrNull() as? JsonObject
                ?: error("No releases published yet in $cleanRepo.")
        }

        val tagName = releaseObj["tag_name"]?.jsonPrimitive?.contentOrNull ?: "v1.0.0"
        val cleanRemoteVersion = tagName.removePrefix("v").removePrefix("V").trim()
        val title = releaseObj["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { tagName } ?: tagName
        val body = releaseObj["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val publishedAt = releaseObj["published_at"]?.jsonPrimitive?.contentOrNull ?: ""

        val assets = releaseObj["assets"]?.jsonArray ?: JsonArray(emptyList())
        val expectedSuffix = if (PlatformBridge.isAndroid) ".apk" else ".exe"

        val matchingAsset = assets.mapNotNull { it as? JsonObject }.firstOrNull { assetObj ->
            val name = assetObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            name.endsWith(expectedSuffix, ignoreCase = true)
        } ?: error("Release '$tagName' does not contain a $expectedSuffix installer asset yet (build may still be in progress).")

        val assetName = matchingAsset["name"]?.jsonPrimitive?.contentOrNull ?: "Parchment$expectedSuffix"
        val downloadUrl = matchingAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing browser_download_url on asset '$assetName'.")
        val sizeBytes = matchingAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L

        val isNewer = compareVersions(cleanRemoteVersion, currentAppVersion) > 0

        OtaReleaseInfo(
            tagName = tagName,
            versionString = cleanRemoteVersion,
            releaseTitle = title,
            releaseNotes = body,
            publishedAt = publishedAt,
            assetName = assetName,
            assetDownloadUrl = downloadUrl,
            assetSizeBytes = sizeBytes,
            isNewerThanCurrent = isNewer,
            assetSha256 = sha256FromAssetDigest(matchingAsset["digest"]?.jsonPrimitive?.contentOrNull)
        )
    }

    /**
     * Streams the `.apk` or `.exe` to an owned staged file, verifies the complete download,
     * and only then starts the platform installer. A cancelled or failed download installs nothing.
     */
    fun downloadAndInstall(
        release: OtaReleaseInfo,
        installBlockedReason: () -> String? = { null },
        stage: (assetName: String) -> OtaStagedDownload = PlatformBridge::createOtaStagedDownload
    ): Flow<OtaUpdateState> = flow {
        emit(OtaUpdateState.Downloading(release, 0L, release.assetSizeBytes))

        var staged: OtaStagedDownload? = null
        try {
            val download = stage(release.assetName).also { staged = it }
            var expectedBytes = release.assetSizeBytes

            httpClient.prepareGet(release.assetDownloadUrl) {
                header("User-Agent", "Parchment-OTA-Updater/${ParchmentBuildConfig.APP_VERSION}")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    error("Failed to download ${release.assetName}: HTTP ${response.status.value}")
                }
                if (expectedBytes <= 0L) expectedBytes = response.contentLength() ?: 0L
                check(expectedBytes > 0L) { "The update size is unknown, so the update was not installed." }

                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var totalRead = 0L
                var lastEmittedPercent = -1
                var lastEmitTimeMs = 0L

                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    totalRead += read
                    check(totalRead <= expectedBytes) { "The download is larger than the published update." }
                    download.write(buffer, read)
                    val now = PlatformBridge.currentTimeMillis()
                    val pct = ((totalRead * 100L) / expectedBytes).toInt().coerceIn(0, 100)
                    if (pct != lastEmittedPercent || now - lastEmitTimeMs >= 80L || totalRead >= expectedBytes) {
                        lastEmittedPercent = pct
                        lastEmitTimeMs = now
                        emit(OtaUpdateState.Downloading(release, totalRead, expectedBytes))
                    }
                }
            }

            download.finish(expectedBytes, release.assetSha256)
            // Downloads take time, so work may have started since the update was requested.
            installBlockedReason()?.let { reason ->
                emit(OtaUpdateState.Error(reason))
                return@flow
            }
            emit(OtaUpdateState.Installing("Applying update (${release.tagName})…"))
            download.install().fold(
                onSuccess = { msg -> emit(OtaUpdateState.Installing(msg)) },
                onFailure = { err -> emit(OtaUpdateState.Error("Install error: ${err.message}")) }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            emit(OtaUpdateState.Error(t.message ?: t::class.simpleName ?: "OTA update failed"))
        } finally {
            staged?.discard()
        }
    }.flowOn(Dispatchers.Default)

    fun compareVersions(remote: String, local: String): Int {
        val rMain = remote.substringBefore('-').trim()
        val lMain = local.substringBefore('-').trim()
        val rPre = remote.substringAfter('-', "").trim()
        val lPre = local.substringAfter('-', "").trim()

        val rParts = rMain.split('.').mapNotNull { it.toIntOrNull() }
        val lParts = lMain.split('.').mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r != l) return r.compareTo(l)
        }
        // In SemVer, a stable release (no pre-release suffix) is newer than a pre-release of the same version
        if (rPre.isEmpty() && lPre.isNotEmpty()) return 1
        if (rPre.isNotEmpty() && lPre.isEmpty()) return -1
        return rPre.compareTo(lPre)
    }
}
