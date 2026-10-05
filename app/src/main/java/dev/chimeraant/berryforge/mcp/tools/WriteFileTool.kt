package dev.chimeraant.berryforge.mcp.tools

import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import dev.chimeraant.berryforge.data.github.GitHubApi
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
import dev.chimeraant.berryforge.session.SessionRecorder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `write_file` — writes content into the local mirror.
 *
 * Per the tool contract this never pushes: it stages the change locally so a human can
 * review the diff and commit through the normal flow. The before/after pair is handed to
 * [SessionRecorder] so the change can be reverted.
 */
class WriteFileTool(
    private val api: GitHubApi,
    private val workspace: EditorWorkspace,
    private val sessions: SessionRecorder,
    private val sandbox: SandboxGuard,
) : McpTool {

    override val name = "write_file"
    override val title = "Write file"
    override val description =
        "Write text to a file in the local working copy of a repository. " +
            "This stages the change on-device only — it does not commit or push. " +
            "Use commit() afterwards to publish, after the user has reviewed the diff."
    override val effect = SandboxGuard.Effect.WriteFile

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "repo" to Schema.string("Repository in owner/name form."),
            "path" to Schema.string("Repo-relative path to write."),
            "content" to Schema.string("Full new contents of the file."),
            "create_dirs" to Schema.boolean("Create parent directories if missing.", true),
        ),
        required = listOf("repo", "path", "content"),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val repo = args.requireStr("repo")
        val path = args.requireStr("path")
        val content = args.str("content") ?: ""
        val createDirs = args.bool("create_dirs", true)
        val (owner, name) = splitRepo(repo)
        validatePath(path)

        if (path.startsWith("/") || path.contains("..")) {
            throw McpToolException("Refusing to write outside the repository: '$path'.")
        }

        val before = workspace.readLocal(owner, name, path)
            ?: runCatching { api.readFile(owner, name, path).text }.getOrNull()
            ?: ""

        if (createDirs) workspace.ensureDir(owner, name, path.substringBeforeLast('/', ""))
        workspace.writeLocal(owner, name, path, content)

        sessions.recordChange(path = path, before = before, after = content)
        sessions.log(
            kind = "tool",
            title = "write_file",
            detail = "$repo:$path",
            repo = repo,
            path = path,
            severity = "edit",
        )

        val added = content.lines().size - before.lines().size
        val summary = buildString {
            appendLine("Staged $path in $repo (local only, not pushed).")
            appendLine("before: ${before.lines().size} lines  after: ${content.lines().size} lines")
            appendLine(
                if (before.isEmpty()) "This creates a new file."
                else "Delta: ${if (added >= 0) "+" else ""}$added lines",
            )
        }.trim()
        return Mcp.jsonResult(
            summary = summary,
            payload = buildJsonObject {
                put("repo", repo)
                put("path", path)
                put("bytes_written", content.toByteArray().size)
                put("staged", true)
                put("pushed", false)
            },
        )
    }

    private fun splitRepo(repo: String): Pair<String, String> {
        val parts = repo.trim().removePrefix("https://github.com/").split('/')
        if (parts.size < 2) throw McpToolException("'repo' must be in owner/name form, got '$repo'.")
        return parts[0] to parts[1].removeSuffix(".git")
    }

    private fun validatePath(path: String) {
        if (path.isBlank() || path.contains('\u0000')) throw McpToolException("Invalid path.")
    }
}
