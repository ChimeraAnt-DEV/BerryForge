package dev.chimeraant.berryforge.mcp.tools

import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import dev.chimeraant.berryforge.data.github.GitHubApi
import dev.chimeraant.berryforge.mcp.Mcp
import dev.chimeraant.berryforge.mcp.McpTool
import dev.chimeraant.berryforge.mcp.McpToolException
import dev.chimeraant.berryforge.mcp.McpValidation
import dev.chimeraant.berryforge.mcp.Schema
import dev.chimeraant.berryforge.mcp.args
import dev.chimeraant.berryforge.mcp.bool
import dev.chimeraant.berryforge.mcp.int
import dev.chimeraant.berryforge.mcp.requireStr
import dev.chimeraant.berryforge.mcp.str
import dev.chimeraant.berryforge.sandbox.SandboxGuard
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `list_files` — lists the files in a repository.
 *
 * Without this an agent could not discover anything: it had to guess paths before it
 * could call read_file, which made the whole tool surface much less useful than it
 * looked.
 *
 * Served from the local mirror when the repo has been opened, otherwise from GitHub's
 * tree API. Paths are filtered by an optional prefix so an agent can explore one
 * directory at a time.
 */
class ListFilesTool(
    private val api: GitHubApi,
    private val workspace: EditorWorkspace,
) : McpTool {

    override val name = "list_files"
    override val title = "List files"
    override val description =
        "List files in a repository, optionally limited to a directory. " +
            "Use this to discover what exists before reading. Returns repo-relative paths, " +
            "and marks files that have uncommitted local changes."
    override val effect = SandboxGuard.Effect.ReadFile

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "repo" to Schema.string("Repository in owner/name form."),
            "path" to Schema.string("Optional directory to list. Defaults to the repository root."),
            "branch" to Schema.string("Branch to list. Defaults to the repository default branch."),
            "recursive" to Schema.boolean("List all files under the path rather than one level.", true),
            "limit" to Schema.integer("Maximum number of paths to return.", 500),
        ),
        required = listOf("repo"),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val repo = args.requireStr("repo")
        val (owner, name) = McpValidation.splitRepo(repo)
        val rawPath = args.str("path").orEmpty()
        val prefix = if (rawPath.isBlank()) "" else McpValidation.validatePath(rawPath)
        val recursive = args.bool("recursive", true)
        val limit = args.int("limit", 500).coerceIn(1, 5000)

        val branch = args.str("branch")?.takeIf { it.isNotBlank() }
            ?: runCatching { api.repo(owner, name).defaultBranch }.getOrDefault("main")

        // Prefer the mirror: it reflects local edits, and is free.
        val localPaths = workspace.listLocal(owner, name)
        val dirty = workspace.dirtyPaths(owner, name).toSet()

        val paths: List<Pair<String, Boolean>> = if (localPaths.isNotEmpty()) {
            localPaths.map { it to (it in dirty) }
        } else {
            val tree = runCatching { api.tree(owner, name, branch) }
                .getOrElse { throw McpToolException("Could not list $repo on branch $branch: ${it.message}") }
            tree.tree.filter { it.isFile }.map { it.path to false }
        }

        val filtered = paths
            .filter { entry ->
                if (prefix.isBlank()) true
                else entry.first == prefix || entry.first.startsWith("$prefix/")
            }
            .filter { entry ->
                if (recursive) true
                else entry.first.removePrefix(if (prefix.isBlank()) "" else "$prefix/").count { it == '/' } == 0
            }
            .take(limit)

        val summary = buildString {
            appendLine("${filtered.size} file(s) in $repo" + if (prefix.isBlank()) "" else " under $prefix")
            appendLine("branch: $branch  source: ${if (localPaths.isNotEmpty()) "local mirror" else "github"}")
            if (paths.size > filtered.size) appendLine("truncated to $limit of ${paths.size}")
        }.trim()

        return Mcp.jsonResult(
            summary = summary,
            payload = buildJsonObject {
                put("repo", repo)
                put("branch", branch)
                put("path", prefix)
                put("count", filtered.size)
                put("files", buildJsonArray {
                    filtered.forEach { (path, isDirty) ->
                        add(buildJsonObject {
                            put("path", path)
                            put("dirty", isDirty)
                        })
                    }
                })
            },
        )
    }
}
