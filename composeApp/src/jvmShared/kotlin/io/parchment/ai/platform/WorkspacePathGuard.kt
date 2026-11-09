package io.parchment.ai.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Resolves existing ancestors too, so writes to missing files cannot cross a link/junction. */
internal object WorkspacePathGuard {
    fun root(workspaceRoot: String): Path {
        require(workspaceRoot.isNotBlank()) { "No workspace directory is mounted." }
        val root = realPathWithMissingSuffix(File(workspaceRoot).toPath().toAbsolutePath().normalize())
        require(!Files.exists(root, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(root)) { "Workspace root is not a directory." }
        return root
    }

    fun resolve(workspaceRoot: String, relativePath: String): Path {
        val root = root(workspaceRoot)
        val cleaned = relativePath.trim()
        require(!cleaned.startsWith('/') && !cleaned.startsWith('\\') && ':' !in cleaned && '\u0000' !in cleaned) {
            "Access denied: Use a relative workspace path."
        }
        val relative = File(cleaned.ifEmpty { "." }).toPath()
        require(!relative.isAbsolute) { "Access denied: Absolute paths are not allowed." }
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root)) { "Access denied: Path '$relativePath' escapes the mounted workspace." }
        val realTarget = realPathWithMissingSuffix(target)
        require(realTarget.startsWith(root)) { "Access denied: A link or junction escapes the mounted workspace." }
        return target
    }

    private fun realPathWithMissingSuffix(target: Path): Path {
        var ancestor = target
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.parent ?: error("Access denied: No valid workspace ancestor.")
        }
        return ancestor.toRealPath().resolve(ancestor.relativize(target)).normalize()
    }

    /** Recursive operations skip aliases altogether to prevent cycles and external traversal. */
    fun canTraverse(root: Path, target: Path): Boolean = runCatching {
        val absolute = target.toAbsolutePath().normalize()
        val real = absolute.toRealPath()
        real.startsWith(root) && (absolute == root || (!Files.isSymbolicLink(absolute) && real == absolute))
    }.getOrDefault(false)

    fun requireUnlinkedTree(root: Path, target: File) {
        require(canTraverse(root, target.toPath())) { "Access denied: Linked workspace targets cannot be moved or recursively deleted." }
        if (target.isDirectory) {
            target.walkTopDown().onEnter { directory ->
                require(canTraverse(root, directory.toPath())) { "Access denied: Directory contains a link or junction." }
                true
            }.forEach { entry ->
                require(canTraverse(root, entry.toPath())) { "Access denied: Directory contains a link or junction." }
            }
        }
    }
}
