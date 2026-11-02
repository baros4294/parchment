package io.parchment.ai.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
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
    val isNewerThanCurrent: Boolean
)

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

object OtaUpdateEngine {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15 * 60 * 1000L
            connectTimeoutMillis = 20_000L
            socketTimeoutMillis = 5 * 60 * 1000L
        }
    }

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
            isNewerThanCurrent = isNewer
        )
    }

    /**
     * Streams the `.apk` or `.exe` binary directly in-app and triggers native silent/1-tap installation.
     */
    fun downloadAndInstall(release: OtaReleaseInfo): Flow<OtaUpdateState> = flow {
        emit(OtaUpdateState.Downloading(release, 0L, release.assetSizeBytes))

        try {
            var downloadedBytesArray = ByteArray(0)

            httpClient.prepareGet(release.assetDownloadUrl) {
                header("User-Agent", "Parchment-OTA-Updater/${ParchmentBuildConfig.APP_VERSION}")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    error("Failed to download ${release.assetName}: HTTP ${response.status.value}")
                }

                val total = (response.contentLength() ?: release.assetSizeBytes).coerceAtLeast(release.assetSizeBytes)
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                val outList = ArrayList<ByteArray>()
                var totalRead = 0L
                var lastEmittedPercent = -1
                var lastEmitTimeMs = 0L

                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    val chunk = buffer.copyOf(read)
                    outList.add(chunk)
                    totalRead += read
                    val now = PlatformBridge.currentTimeMillis()
                    val pct = if (total > 0L) ((totalRead * 100L) / total).toInt().coerceIn(0, 100) else 0
                    if (pct != lastEmittedPercent || now - lastEmitTimeMs >= 80L || totalRead >= total) {
                        lastEmittedPercent = pct
                        lastEmitTimeMs = now
                        emit(OtaUpdateState.Downloading(release, totalRead, total))
                    }
                }

                emit(OtaUpdateState.Downloading(release, totalRead, total.coerceAtLeast(totalRead)))

                val combined = ByteArray(totalRead.toInt())
                var offset = 0
                for (chunk in outList) {
                    chunk.copyInto(combined, offset)
                    offset += chunk.size
                }
                downloadedBytesArray = combined
            }

            emit(OtaUpdateState.Installing("Applying update (${release.tagName})…"))
            val installResult = PlatformBridge.installOtaUpdate(release.assetName, downloadedBytesArray)
            installResult.fold(
                onSuccess = { msg ->
                    emit(OtaUpdateState.Installing(msg))
                },
                onFailure = { err ->
                    emit(OtaUpdateState.Error("Install error: ${err.message}"))
                }
            )
        } catch (t: Throwable) {
            emit(OtaUpdateState.Error(t.message ?: t::class.simpleName ?: "OTA update failed"))
        }
    }.flowOn(Dispatchers.Default)

    fun compareVersions(remote: String, local: String): Int {
        val rParts = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val lParts = local.split('.', '-').mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r != l) return r.compareTo(l)
        }
        return remote.compareTo(local)
    }
}
