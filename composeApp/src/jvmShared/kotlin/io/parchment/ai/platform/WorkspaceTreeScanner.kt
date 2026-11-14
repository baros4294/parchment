package io.parchment.ai.platform

import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors

internal data class WorkspaceScanConfig(
    val missingDirMessage: (String) -> String,
    val notDirMessage: (String) -> String,
    val emptyDirectoryMessage: String,
    val useTreeCache: Boolean,
    val canonicalizeRelativize: Boolean,
    val allowEmptyPlainGlobFallback: Boolean,
    val requirePositiveSearchFileBytes: Boolean,
    val maxSearchFileBytes: Long,
    val maxSearchHits: Int,
    val maxSearchLineChars: Int,
    val parallelSearch: Boolean,
    val filterSearchByRelPath: Boolean
)

/** Shared sandboxed workspace directory listing, glob matching, and text search with optional tree caching. */
internal class WorkspaceTreeScanner(
    private val config: WorkspaceScanConfig
) {
    private data class CachedEntry(
        val relPath: String,
        val name: String,
        val file: File,
        val isDirectory: Boolean,
        val length: Long
    ) {
        fun formatListing(): String =
            if (isDirectory) "$relPath/" else "$relPath ($length bytes)"
    }

    private data class CachedWorkspaceTree(
        val rootCanonicalPath: String,
        val timestampMs: Long,
        val entries: List<CachedEntry>
    )

    @Volatile
    private var cachedTree: CachedWorkspaceTree? = null

    fun invalidate() {
        cachedTree = null
    }

    fun list(workspaceRoot: String, relativePath: String, recursive: Boolean): Result<String> = runCatching {
        val target = WorkspacePathGuard.resolve(workspaceRoot, relativePath).toFile()
        val rootFile = WorkspacePathGuard.root(workspaceRoot).toFile()
        val rootPath = rootFile.toPath().normalize()
        if (!target.exists()) throw IllegalArgumentException(config.missingDirMessage(relativePath))
        require(target.isDirectory) { config.notDirMessage(relativePath) }

        val entries = when {
            recursive && config.useTreeCache && relativePath.trim().isEmpty() -> {
                getOrBuildRootTree(rootFile).take(MAX_RECURSIVE_LIST_ENTRIES).map { it.formatListing() }
            }
            recursive -> {
                walkEntries(target, rootPath).take(MAX_RECURSIVE_LIST_ENTRIES).map { it.formatListing() }.toList()
            }
            else -> {
                (target.listFiles()?.toList() ?: emptyList())
                    .filter { WorkspacePathGuard.canTraverse(rootPath, it.toPath()) }
                    .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    .take(MAX_FLAT_LIST_ENTRIES)
                    .map { toEntry(rootPath, it).formatListing() }
            }
        }
        if (entries.isEmpty()) config.emptyDirectoryMessage else entries.joinToString("\n")
    }

    fun glob(workspaceRoot: String, pattern: String, maxResults: Int): Result<String> = runCatching {
        val rootFile = WorkspacePathGuard.root(workspaceRoot).toFile()
        val rootPath = rootFile.toPath().normalize()
        require(rootFile.exists() && rootFile.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        val cleanPat = pattern.trim().replace('\\', '/').removePrefix("./")
        require(cleanPat.isNotEmpty()) { "Glob pattern must not be empty." }

        val globRegex = compileGlobRegex(cleanPat)
        val hasSlash = cleanPat.contains('/')
        val plainSub = cleanPat.replace("*", "")
        val entries = if (config.useTreeCache) {
            getOrBuildRootTree(rootFile).asSequence()
        } else {
            walkEntries(rootFile, rootPath)
        }
        val matches = entries
            .mapNotNull { entry ->
                val matchTarget = if (!hasSlash) entry.name else entry.relPath
                val plainMatch = (config.allowEmptyPlainGlobFallback || plainSub.isNotEmpty()) &&
                    entry.relPath.contains(plainSub, ignoreCase = true)
                if (globRegex.matches(matchTarget) || plainMatch) entry.formatListing() else null
            }
            .take(maxResults.coerceIn(1, MAX_GLOB_RESULTS))
            .toList()
        if (matches.isEmpty()) "No files matched glob '$pattern'." else matches.joinToString("\n")
    }

    fun search(workspaceRoot: String, query: String, fileGlob: String): Result<String> = runCatching {
        val rootFile = WorkspacePathGuard.root(workspaceRoot).toFile()
        val rootPath = rootFile.toPath().normalize()
        require(rootFile.exists() && rootFile.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        require(query.isNotBlank()) { "Search query must not be blank." }

        val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
        val cleanFilter = fileGlob.removePrefix("*").trim()
        val sourceEntries = if (config.useTreeCache) {
            getOrBuildRootTree(rootFile)
        } else {
            walkEntries(rootFile, rootPath).toList()
        }
        val candidates = sourceEntries.filter { entry ->
            !entry.isDirectory &&
                entry.length <= config.maxSearchFileBytes &&
                (!config.requirePositiveSearchFileBytes || entry.length > 0L) &&
                (cleanFilter.isEmpty() ||
                    entry.name.contains(cleanFilter, ignoreCase = true) ||
                    (config.filterSearchByRelPath && entry.relPath.contains(cleanFilter, ignoreCase = true)))
        }

        val hitCount = AtomicInteger(0)
        val matches = if (config.parallelSearch) {
            candidates.parallelStream()
                .map { entry -> searchSingleFile(entry, rootPath, query, regex, hitCount) }
                .filter { it != null }
                .collect(Collectors.toList())
                .filterNotNull()
                .flatten()
        } else {
            buildList {
                for (entry in candidates) {
                    if (hitCount.get() >= config.maxSearchHits) break
                    searchSingleFile(entry, rootPath, query, regex, hitCount)?.let(::addAll)
                }
            }
        }
        if (matches.isEmpty()) "No matches found for '$query'." else matches.joinToString("\n")
    }

    private fun searchSingleFile(
        entry: CachedEntry,
        rootPath: Path,
        query: String,
        regex: Regex?,
        hitCount: AtomicInteger
    ): List<String>? {
        if (hitCount.get() >= config.maxSearchHits) return null
        val fileHits = mutableListOf<String>()
        runCatching {
            require(WorkspacePathGuard.canTraverse(rootPath, entry.file.toPath())) {
                "Workspace path changed during search."
            }
            entry.file.useLines(Charsets.UTF_8) { seq ->
                seq.forEachIndexed { idx, line ->
                    if (hitCount.get() < config.maxSearchHits) {
                        val hit = regex?.containsMatchIn(line) ?: line.contains(query, ignoreCase = true)
                        if (hit && hitCount.incrementAndGet() <= config.maxSearchHits) {
                            fileHits.add("${entry.relPath}:${idx + 1}: ${line.trim().take(config.maxSearchLineChars)}")
                        }
                    }
                }
            }
        }
        return fileHits.ifEmpty { null }
    }

    private fun getOrBuildRootTree(rootFile: File): List<CachedEntry> {
        val canonPath = rootFile.canonicalPath
        val now = System.currentTimeMillis()
        val existing = cachedTree
        if (existing != null && existing.rootCanonicalPath == canonPath && now - existing.timestampMs <= TREE_CACHE_TTL_MS) {
            return existing.entries
        }
        val rootPath = rootFile.toPath().normalize()
        val built = walkEntries(rootFile, rootPath).toList()
        cachedTree = CachedWorkspaceTree(canonPath, now, built)
        return built
    }

    private fun walkEntries(startDir: File, rootPath: Path): Sequence<CachedEntry> =
        startDir.walkTopDown()
            .onEnter { dir ->
                (dir == startDir || !WorkspacePathGuard.isIgnoredDirectory(dir.name)) &&
                    WorkspacePathGuard.canTraverse(rootPath, dir.toPath())
            }
            .maxDepth(MAX_WALK_DEPTH)
            .filter { file ->
                file != startDir &&
                    !WorkspacePathGuard.isIgnoredDirectory(file.name) &&
                    WorkspacePathGuard.canTraverse(rootPath, file.toPath())
            }
            .map { file -> toEntry(rootPath, file) }

    private fun toEntry(rootPath: Path, file: File): CachedEntry {
        val pathForRel = if (config.canonicalizeRelativize) file.canonicalFile.toPath() else file.toPath()
        val rel = rootPath.relativize(pathForRel).toString().replace('\\', '/')
        val isDir = file.isDirectory
        return CachedEntry(
            relPath = rel,
            name = file.name,
            file = file,
            isDirectory = isDir,
            length = if (isDir) 0L else file.length()
        )
    }

    private fun compileGlobRegex(cleanPat: String): Regex {
        val regexStr = buildString {
            append("^")
            var i = 0
            while (i < cleanPat.length) {
                val c = cleanPat[i]
                if (c == '*' && i + 1 < cleanPat.length && cleanPat[i + 1] == '*') {
                    if (i + 2 < cleanPat.length && cleanPat[i + 2] == '/') {
                        append("(?:.*/)?")
                        i += 3
                        continue
                    } else {
                        append(".*")
                        i += 2
                        continue
                    }
                } else if (c == '*') {
                    append("[^/]*")
                } else if (c == '?') {
                    append("[^/]")
                } else if (".+()[]{}|^$\\".contains(c)) {
                    append('\\').append(c)
                } else {
                    append(c)
                }
                i++
            }
            append("$")
        }
        return Regex(regexStr, RegexOption.IGNORE_CASE)
    }

    private companion object {
        const val TREE_CACHE_TTL_MS = 10_000L
        const val MAX_WALK_DEPTH = 24
        const val MAX_FLAT_LIST_ENTRIES = 300
        const val MAX_RECURSIVE_LIST_ENTRIES = 400
        const val MAX_GLOB_RESULTS = 500
    }
}
