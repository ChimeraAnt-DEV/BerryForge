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
 * `search_code` — searches the repository for a string or pattern.
 *
 * Complements list_files: an agent can find the file that mentions a symbol instead of
 * reading the tree file by file. Searches the local mirror when present so it also sees
 * uncommitted edits, and falls back to GitHub's code search otherwise.
 */
class SearchCodeTool(
    private val api: GitHubApi,
    private val workspace: EditorWorkspace,
) : McpTool {

    override val name = "search_code"
    override val title = "Search code"
    override val description =
        "Search a repository's files for a literal string or regular expression. " +
            "Returns matching paths with line numbers and a short excerpt. " +
            "Useful for finding where a symbol is defined or used."
    override val effect = SandboxGuard.Effect.ReadFile

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "repo" to Schema.string("Repository in owner/name form."),
            "query" to Schema.string("Text to search for."),
            "regex" to Schema.boolean("Treat the query as a regular expression.", false),
            "case_sensitive" to Schema.boolean("Match case.", false),
            "path" to Schema.string("Optional directory to limit the search to."),
            "max_results" to Schema.integer("Maximum matches to return.", 50),
        ),
        required = listOf("repo", "query"),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val repo = args.requireStr("repo")
        val query = args.requireStr("query")
        val (owner, name) = McpValidation.splitRepo(repo)
        val useRegex = args.bool("regex", false)
        val caseSensitive = args.bool("case_sensitive", false)
        val limit = args.int("max_results", 50).coerceIn(1, 500)
        val pathFilter = args.str("path")?.takeIf { it.isNotBlank() }?.let { McpValidation.validatePath(it) }

        val matcher: (String) -> Boolean
        if (useRegex) {
            val regex = runCatching {
                Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            }.getOrElse { throw McpToolException("Invalid regular expression: ${it.message}") }
            matcher = { line -> regex.containsMatchIn(line) }
        } else {
            val needle = if (caseSensitive) query else query.lowercase()
            matcher = { line -> (if (caseSensitive) line else line.lowercase()).contains(needle) }
        }

        val localFiles = workspace.listLocal(owner, name)
        if (localFiles.isEmpty()) {
            throw McpToolException(
                "No local copy of $repo yet. Open the repository in BerryForge first, " +
                    "or use read_file to fetch individual files.",
            )
        }

        val results = mutableListOf<Triple<String, Int, String>>()
        for (path in localFiles) {
            if (results.size >= limit) break
            if (pathFilter != null && !path.startsWith(pathFilter)) continue
            // Skip anything that is unlikely to be text and would waste time.
            if (path.substringAfterLast('.', "").lowercase() in BINARY_EXTENSIONS) continue
            val text = workspace.readLocal(owner, name, path) ?: continue
            text.lineSequence().forEachIndexed { index, line ->
                if (results.size < limit && matcher(line)) {
                    results += Triple(path, index + 1, line.trim().take(EXCERPT_CHARS))
                }
            }
        }

        val summary = if (results.isEmpty()) {
            "No matches for \"$query\" in $repo."
        } else {
            "${results.size} match(es) for \"$query\" in $repo" +
                if (results.size >= limit) " (truncated to $limit)" else ""
        }

        return Mcp.jsonResult(
            summary = summary,
            payload = buildJsonObject {
                put("repo", repo)
                put("query", query)
                put("count", results.size)
                put("matches", buildJsonArray {
                    results.forEach { (path, line, excerpt) ->
                        add(buildJsonObject {
                            put("path", path)
                            put("line", line)
                            put("excerpt", excerpt)
                        })
                    }
                })
            },
        )
    }

    private companion object {
        const val EXCERPT_CHARS = 200
        val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp",
            "jar", "aar", "apk", "dex", "class", "so", "a", "o",
            "zip", "gz", "xz", "tar", "7z", "keystore", "jks", "ttf", "otf", "woff",
        )
    }
}
