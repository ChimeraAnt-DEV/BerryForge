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
import dev.chimeraant.berryforge.session.SessionRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val rawPath = args.requireStr("path")
        val content = args.str("content") ?: ""
        val createDirs = args.bool("create_dirs", true)

        // Validated centrally: repo shape and path traversal are both rejected before
        // anything touches the filesystem.
        val (owner, name) = McpValidation.splitRepo(repo)
        val path = McpValidation.validatePath(rawPath)

        val sandboxed = sandbox.isSandboxed()
        val realTarget = workspace.fileFor(owner, name, path)
        McpValidation.requireInside(workspace.repoDir(owner, name), realTarget)

        // In sandbox mode the write is redirected to the scratch area, so the user's
        // working copy is never modified by a sandboxed session.
        val target = McpValidation.resolveWriteTarget(sandbox, realTarget, sandboxed)

        // Always read the real file, so the diff shown to the user is meaningful even
        // when the write itself is redirected into the sandbox.
        val before = workspace.readLocal(owner, name, path)
            ?: runCatching { api.readFile(owner, name, path).text }.getOrNull()
            ?: ""

        if (createDirs) target.parentFile?.mkdirs()
        withContext(Dispatchers.IO) { target.writeText(content) }

        if (!sandboxed) {
            // Record it as a real working-copy edit so the commit flow sees it.
            workspace.writeLocal(owner, name, path, content)
        } else {
            // Still record the change for the audit trail and revert, without marking the
            // real working copy dirty.
            workspace.recordSandboxWrite(owner, name, path, content)
        }

        sessions.recordChange(path = path, before = before, after = content)
        sessions.log(
            kind = "tool",
            title = "write_file",
            detail = "$repo:$path" + if (sandboxed) " (sandbox)" else "",
            repo = repo,
            path = path,
            severity = "edit",
        )

        val added = content.lines().size - before.lines().size
        val summary = buildString {
            if (sandboxed) {
                appendLine("Sandboxed: wrote $path to the scratch area, not the working copy.")
            } else {
                appendLine("Staged $path in $repo (local only, not pushed).")
            }
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
                put("sandboxed", sandboxed)
                put("written_to", if (sandboxed) "sandbox" else "workspace")
            },
        )
    }
}
