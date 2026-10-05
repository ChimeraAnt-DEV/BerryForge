package dev.chimeraant.berryforge.mcp

import dev.chimeraant.berryforge.sandbox.SandboxGuard
import java.io.File

/**
 * Argument validation shared by every MCP tool.
 *
 * Each tool previously carried its own `splitRepo` and `validatePath`, which drifted and
 * left gaps: `repo` was never validated at all, so a value like `../../..` resolved
 * outside the workspace and into other app-private directories.
 *
 * Everything an agent supplies that ends up on a filesystem path or in a URL goes through
 * here first.
 */
object McpValidation {

    /**
     * Splits and validates an `owner/name` repository identifier.
     *
     * Rejects anything that is not exactly two non-empty segments of legal GitHub
     * characters, which rules out traversal (`..`), absolute paths and URL fragments
     * before they can reach the filesystem.
     */
    fun splitRepo(repo: String): Pair<String, String> {
        val raw = repo.trim()
        // Reject absolute and UNC forms before any normalisation: trimming the leading
        // slash would otherwise turn "/etc/passwd" into a plausible-looking "etc/passwd".
        if (raw.startsWith("/") || raw.startsWith("\\")) {
            throw McpToolException("'repo' must be a repository name, not a path: '$repo'.")
        }
        if (raw.contains("\\")) {
            throw McpToolException("'repo' must not contain backslashes: '$repo'.")
        }
        val cleaned = raw
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removeSuffix(".git")
            .trim('/')

        val parts = cleaned.split('/')
        if (parts.size != 2) {
            throw McpToolException("'repo' must be in owner/name form, got '$repo'.")
        }
        val (owner, name) = parts
        if (!isLegalOwner(owner) || !isLegalRepoName(name)) {
            throw McpToolException("'repo' contains illegal characters: '$repo'.")
        }
        return owner to name
    }

    private fun isLegalOwner(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= 100 &&
            value != "." && value != ".." &&
            OWNER_PATTERN.matches(value)

    private fun isLegalRepoName(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= 100 &&
            value != "." && value != ".." &&
            REPO_PATTERN.matches(value)

    /**
     * Validates a repo-relative file path.
     *
     * Rejects absolute paths, traversal segments, NUL bytes and control characters, so a
     * path can never escape the workspace.
     */
    fun validatePath(path: String): String {
        val cleaned = path.trim()
        if (cleaned.isBlank()) throw McpToolException("'path' must not be blank.")
        if (cleaned.length > MAX_PATH_LENGTH) {
            throw McpToolException("'path' is too long (${cleaned.length} characters).")
        }
        if (cleaned.startsWith("/") || cleaned.startsWith("\\")) {
            throw McpToolException("'path' must be repo-relative, got '$path'.")
        }
        // Reject backslashes outright. GitHub paths are always forward-slash separated,
        // and accepting them invites Windows-style absolute paths such as "C:\\x".
        if (cleaned.contains("\\")) {
            throw McpToolException("'path' must use forward slashes, got '$path'.")
        }
        if (cleaned.contains('\u0000')) {
            throw McpToolException("'path' contains a NUL byte.")
        }
        if (cleaned.any { it.isISOControl() }) {
            throw McpToolException("'path' contains control characters.")
        }
        // A drive-letter prefix is an absolute path on another platform.
        if (DRIVE_PATTERN.matches(cleaned)) {
            throw McpToolException("'path' must be repo-relative, got '$path'.")
        }
        // Check every segment, so "a/../../b" is rejected as well as "../b".
        val segments = cleaned.split('/')
        if (segments.any { it == ".." }) {
            throw McpToolException("'path' must not contain '..' segments.")
        }
        // A leading "./" is harmless but stripped so paths normalise consistently.
        return segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }

    /**
     * Confirms a resolved file really sits inside [root].
     *
     * This is the belt-and-braces check that runs after a path has been assembled, so a
     * symlink or a platform quirk cannot smuggle a write outside the workspace.
     */
    fun requireInside(root: File, candidate: File, label: String = "path") {
        val rootPath = root.canonicalPath
        val candidatePath = candidate.canonicalPath
        val inside = candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
        if (!inside) {
            throw McpToolException("Refusing to touch a $label outside the repository workspace.")
        }
    }

    /**
     * Resolves where an agent write should land, honouring sandbox mode.
     *
     * When the sandbox is on, the write is redirected into the sandbox scratch area so the
     * real working copy is untouched. Previously `write_file` wrote straight to the real
     * mirror and this helper was never called at all, so "sandbox mode" did not sandbox
     * writes.
     */
    fun resolveWriteTarget(sandbox: SandboxGuard, realTarget: File, sandboxed: Boolean): File =
        if (sandboxed) {
            sandbox.sandboxTargetFor(realTarget)
        } else {
            realTarget
        }

    private const val MAX_PATH_LENGTH = 1024
    private val DRIVE_PATTERN = Regex("""^[A-Za-z]:.*$""")
    private val OWNER_PATTERN = Regex("""^[A-Za-z0-9](?:[A-Za-z0-9-]{0,98}[A-Za-z0-9])?$""")
    private val REPO_PATTERN = Regex("""^[A-Za-z0-9._-]+$""")
}
