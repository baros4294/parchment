package io.parchment.ai.network

import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.platform.PlatformBridge
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val REGISTRY_URL = "https://registry.modelcontextprotocol.io/v0/servers"
private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
private const val REGISTRY_PAGE_SIZE = 30

internal data class RegistryServer(val name: String, val title: String, val description: String, val url: String, val needsToken: Boolean)
internal data class RegistryPage(val servers: List<RegistryServer>, val nextCursor: String)

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

/** Keeps servers that can be reached directly and need nothing beyond an optional access token. */
internal fun parseRegistryPage(body: String): RegistryPage {
    val root = AiProviderEngine.json.parseToJsonElement(body) as? JsonObject ?: return RegistryPage(emptyList(), "")
    val servers = (root["servers"] as? JsonArray).orEmpty().mapNotNull { element ->
        val server = (element as? JsonObject)?.get("server") as? JsonObject ?: return@mapNotNull null
        val remote = (server["remotes"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull {
            it.text("type") == "streamable-http" && it.text("url").startsWith("https://")
        } ?: return@mapNotNull null
        val required = (remote["headers"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { (it["isRequired"] as? JsonPrimitive)?.booleanOrNull == true }
        if (required.any { !it.text("name").equals("Authorization", ignoreCase = true) }) return@mapNotNull null
        RegistryServer(server.text("name"), server.text("title"), server.text("description"), remote.text("url"), required.isNotEmpty())
    }.filter { it.name.isNotBlank() }.distinctBy { it.name }
    val cursor = ((root["metadata"] as? JsonObject)?.text("nextCursor")).orEmpty()
    return RegistryPage(servers, cursor)
}

internal suspend fun searchMcpRegistry(query: String, cursor: String = ""): Result<RegistryPage> = runCatching {
    val url = buildString {
        append(REGISTRY_URL).append("?limit=").append(REGISTRY_PAGE_SIZE).append("&version=latest")
        if (query.isNotBlank()) append("&search=").append(query.trim().encodeURLParameter())
        if (cursor.isNotBlank()) append("&cursor=").append(cursor.encodeURLParameter())
    }
    parseRegistryPage(withContext(Dispatchers.IO) { PlatformBridge.fetchPublicBytes(url, MAX_RESPONSE_BYTES).getOrThrow() }.decodeToString())
}

internal data class GitHubFolder(val owner: String, val repo: String, val ref: String, val path: String)

private val GITHUB_NAME = Regex("[A-Za-z0-9._-]+")

/** Accepts a repository, folder or SKILL.md link on github.com. */
internal fun parseGitHubFolder(link: String): GitHubFolder? {
    val trimmed = link.trim().removeSuffix("/")
    val rest = trimmed.removePrefix("https://github.com/").takeIf { it != trimmed } ?: return null
    val parts = rest.substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
    if (parts.size < 2 || !GITHUB_NAME.matches(parts[0]) || !GITHUB_NAME.matches(parts[1])) return null
    val repo = parts[1].removeSuffix(".git")
    if (parts.size == 2) return GitHubFolder(parts[0], repo, "", "")
    if (parts.size < 4 || parts[2] !in setOf("tree", "blob")) return null
    val inner = parts.drop(4)
    if (inner.any { it == ".." || it == "." }) return null
    val folder = if (inner.lastOrNull().equals("SKILL.md", ignoreCase = true)) inner.dropLast(1) else inner
    return GitHubFolder(parts[0], repo, parts[3], folder.joinToString("/"))
}

private const val MAX_SKILL_FILES = 64
private const val MAX_SKILL_FILE_BYTES = 1024 * 1024
private const val MAX_SKILL_TOTAL_BYTES = 8 * 1024 * 1024
private const val MAX_SKILL_DEPTH = 5
private val SKILL_FOLDER_NAME = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")
private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._ -]+")

/**
 * Copies a skill's text files into the workspace. Nothing is written until the whole folder has
 * been read and checked, and a skill is never overwritten. Scripts are copied but never run here.
 */
internal suspend fun installSkillFromGitHub(
    folder: GitHubFolder,
    exists: suspend (String) -> Boolean,
    write: suspend (String, String) -> Result<String>,
    fetch: suspend (String, Int) -> ByteArray
): Result<String> = runCatching {
    val files = linkedMapOf<String, String>()
    var total = 0
    var skipped = 0
    suspend fun walk(path: String, relative: String, depth: Int) {
        check(depth <= MAX_SKILL_DEPTH) { "This skill's folders are nested too deeply." }
        val encoded = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { it.encodeURLParameter() }
        val query = if (folder.ref.isBlank()) "" else "?ref=${folder.ref.encodeURLParameter()}"
        val listing = fetch("https://api.github.com/repos/${folder.owner}/${folder.repo}/contents/$encoded$query", MAX_RESPONSE_BYTES).decodeToString()
        val entries = AiProviderEngine.json.parseToJsonElement(listing) as? JsonArray ?: error("That link is not a folder.")
        for (element in entries.filterIsInstance<JsonObject>()) {
            val name = element.text("name")
            val target = if (relative.isEmpty()) name else "$relative/$name"
            if (!SAFE_FILE_NAME.matches(name) || name.all { it == '.' }) error("This skill contains an unsafe file name.")
            when (element.text("type")) {
                "dir" -> walk(element.text("path"), target, depth + 1)
                "file" -> {
                    check(files.size < MAX_SKILL_FILES) { "This skill has too many files." }
                    val download = element.text("download_url")
                    check(download.startsWith("https://raw.githubusercontent.com/")) { "This skill has a file from an unexpected address." }
                    val bytes = fetch(download, MAX_SKILL_FILE_BYTES)
                    total += bytes.size
                    check(total <= MAX_SKILL_TOTAL_BYTES) { "This skill is too large." }
                    val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
                    if (text == null || '\u0000' in text) skipped++ else files[target] = text
                }
            }
        }
    }
    walk(folder.path, "", 0)
    val manifest = files.entries.firstOrNull { it.key.equals("SKILL.md", ignoreCase = true) }?.value
        ?: error("That folder has no SKILL.md, so it is not a skill.")
    val (declared, description) = WorkspaceHarness.parseSkillHeader(manifest) ?: error("This skill's SKILL.md has no valid header.")
    val name = declared.ifBlank { folder.path.substringAfterLast('/').ifBlank { folder.repo } }
    check(SKILL_FOLDER_NAME.matches(name)) { "This skill has an unusable name." }
    check(description.isNotBlank()) { "This skill has no description, so the agent could never choose it." }
    val base = ".parchment/skills/$name"
    check(!exists("$base/SKILL.md")) { "A skill named $name is already installed." }
    // The manifest goes last: it is what marks a skill as installed, so a failed copy can be retried.
    files.entries.sortedBy { it.key.equals("SKILL.md", ignoreCase = true) }.forEach { (relative, text) -> write("$base/$relative", text).getOrThrow() }
    if (skipped > 0) "Installed $name, without $skipped binary file${if (skipped == 1) "" else "s"}." else "Installed $name."
}
