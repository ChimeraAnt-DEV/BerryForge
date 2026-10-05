package dev.chimeraant.berryforge.mcp.tools

import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import dev.chimeraant.berryforge.data.github.GitHubApi
import dev.chimeraant.berryforge.data.github.RepoCache
import dev.chimeraant.berryforge.mcp.Mcp
import dev.chimeraant.berryforge.mcp.McpTool
import dev.chimeraant.berryforge.mcp.McpToolException
import dev.chimeraant.berryforge.mcp.Schema
import dev.chimeraant.berryforge.mcp.args
import dev.chimeraant.berryforge.mcp.bool
import dev.chimeraant.berryforge.mcp.int
import dev.chimeraant.berryforge.mcp.requireStr
import dev.chimeraant.berryforge.mcp.str
import dev.chimeraant.berryforge.sandbox.SandboxGuard
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `read_file` — reads a file from a repository.
 *
 * Resolution order: the local mirror, then the disk cache, then GitHub. A hit at any
 * level is written back into the mirror so the editor and later tools agree on one copy.
 * Paths are validated against traversal before touching the filesystem.
 */
class ReadFileTool(
    private val api: GitHubApi,
    private val cache: RepoCache,
    private val workspace: EditorWorkspace,
) : McpTool {

    override val name = "read_file"
    override val title = "Read file"
    override val description =
        "Read the contents of a text file in a repository. " +
            "Use the repository's full_name (owner/name) and a repo-relative path such as " +
            "'app/src/main/java/MainActivity.kt'. Optionally pass a branch; defaults to the repo's default branch."
    override val effect = SandboxGuard.Effect.ReadFile

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "repo" to Schema.string("Repository in owner/name form, e.g. ChimeraAnt-DEV/BerryForge."),
            "path" to Schema.string("Repo-relative path to the file."),
            "branch" to Schema.string("Branch or ref to read from. Defaults to the repository default branch."),
            "max_bytes" to Schema.integer("Truncate the returned content beyond this many bytes.", 200_000),
        ),
        required = listOf("repo", "path"),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val repo = args.requireStr("repo")
        val path = args.requireStr("path")
        val maxBytes = args.int("max_bytes", 200_000)
        val (owner, name) = splitRepo(repo)

        val branch = args.str("branch")?.takeIf { it.isNotBlank() }
            ?: cache.getRepos("user", allowStale = true)
                ?.firstOrNull { it.fullName.equals(repo, ignoreCase = true) }
                ?.defaultBranch
            ?: "HEAD"

        validatePath(path)

        // 1. Local mirror, if the file was already opened or written.
        workspace.readLocal(owner, name, path)?.let { text ->
            return respond(repo, path, branch, text, maxBytes, source = "local mirror")
        }

        // 2. GitHub, then cache + mirror the result.
        val file = try {
            api.readFile(owner, name, path, branch.takeIf { it != "HEAD" })
        } catch (error: dev.chimeraant.berryforge.data.github.GitHubException) {
            throw McpToolException(
                if (error.isNotFound) "No file at '$path' in $repo (branch $branch)."
                else "GitHub returned ${error.code} reading $path: ${error.payload.take(200)}",
            )
        }
        workspace.storeFetched(owner, name, file)
        cache.putFile(repo, branch, path, file.sha, file.text)
        return respond(repo, path, branch, file.text, maxBytes, source = "github", sha = file.sha)
    }

    private fun respond(
        repo: String,
        path: String,
        branch: String,
        text: String,
        maxBytes: Int,
        source: String,
        sha: String? = null,
    ): JsonObject {
        val truncated = text.length > maxBytes
        val body = if (truncated) text.take(maxBytes) else text
        val header = buildString {
            appendLine("$repo:$path @ $branch ($source)")
            if (sha != null) appendLine("sha: $sha")
            appendLine("lines: ${text.lines().size}  bytes: ${text.length}")
            if (truncated) appendLine("truncated to $maxBytes bytes")
        }
        return Mcp.jsonResult(
            summary = header.trim(),
            payload = buildJsonObject {
                put("repo", repo)
                put("path", path)
                put("branch", branch)
                put("sha", sha ?: "")
                put("lines", text.lines().size)
                put("bytes", text.length)
                put("truncated", truncated)
                put("content", body)
            },
        )
    }

    private fun splitRepo(repo: String): Pair<String, String> {
        val parts = repo.trim().removePrefix("https://github.com/").split('/')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw McpToolException("'repo' must be in owner/name form, got '$repo'.")
        }
        return parts[0] to parts[1].removeSuffix(".git")
    }

    private fun validatePath(path: String) {
        if (path.startsWith("/") || path.contains("..") || path.contains('\u0000')) {
            throw McpToolException("Path '$path' is not a safe repo-relative path.")
        }
    }
}
