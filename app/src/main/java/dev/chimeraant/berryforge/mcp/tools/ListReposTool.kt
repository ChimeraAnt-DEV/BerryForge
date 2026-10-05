package dev.chimeraant.berryforge.mcp.tools

import dev.chimeraant.berryforge.data.github.GitHubApi
import dev.chimeraant.berryforge.data.github.RepoCache
import dev.chimeraant.berryforge.mcp.Mcp
import dev.chimeraant.berryforge.mcp.McpTool
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
 * `list_repos` — returns the signed-in user's GitHub repositories.
 *
 * Read-only. Served from the disk cache when fresh so an agent polling for repos does
 * not burn the user's API rate limit.
 */
class ListReposTool(
    private val api: GitHubApi,
    private val cache: RepoCache,
) : McpTool {

    override val name = "list_repos"
    override val title = "List repositories"
    override val description =
        "List the GitHub repositories the signed-in user can access. " +
            "Returns name, full_name, default branch, visibility and last push time. " +
            "Use the full_name value as the `repo` argument for other tools."
    override val effect = SandboxGuard.Effect.ReadFile

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "limit" to Schema.integer("Maximum number of repositories to return.", 100),
            "include_forks" to Schema.boolean("Include forked repositories.", false),
            "include_archived" to Schema.boolean("Include archived repositories.", false),
            "query" to Schema.string("Optional case-insensitive substring filter on the repository name."),
        ),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val limit = args.int("limit", 100).coerceIn(1, 500)
        val includeForks = args.bool("include_forks", false)
        val includeArchived = args.bool("include_archived", false)
        val query = args.str("query")?.lowercase()?.takeIf { it.isNotBlank() }

        val repos = cache.getRepos("user", allowStale = false)
            ?: api.allMyRepos().also { cache.putRepos("user", it) }

        val filtered = repos
            .asSequence()
            .filter { includeForks || !it.fork }
            .filter { includeArchived || !it.archived }
            .filter { query == null || it.name.lowercase().contains(query) || it.fullName.lowercase().contains(query) }
            .take(limit)
            .toList()

        val payload = buildJsonArray {
            filtered.forEach { repo ->
                add(buildJsonObject {
                    put("name", repo.name)
                    put("full_name", repo.fullName)
                    put("private", repo.private)
                    put("fork", repo.fork)
                    put("archived", repo.archived)
                    put("default_branch", repo.defaultBranch)
                    put("language", repo.language ?: "")
                    put("description", repo.description ?: "")
                    put("pushed_at", repo.pushedAt ?: "")
                })
            }
        }
        val summary = "Found ${filtered.size} repositories" +
            (query?.let { " matching \"$it\"" } ?: "") +
            " (of ${repos.size} accessible)."
        return Mcp.jsonResult(summary, payload)
    }
}
