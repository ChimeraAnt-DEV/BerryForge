package dev.chimeraant.berryforge.mcp.tools

import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import dev.chimeraant.berryforge.data.github.CommitFlow
import dev.chimeraant.berryforge.data.github.GitHubApi
import dev.chimeraant.berryforge.mcp.Mcp
import dev.chimeraant.berryforge.mcp.McpTool
import dev.chimeraant.berryforge.mcp.McpToolException
import dev.chimeraant.berryforge.mcp.McpValidation
import dev.chimeraant.berryforge.mcp.Schema
import dev.chimeraant.berryforge.mcp.args
import dev.chimeraant.berryforge.mcp.bool
import dev.chimeraant.berryforge.mcp.int
import kotlinx.serialization.json.JsonPrimitive
import dev.chimeraant.berryforge.mcp.requireStr
import dev.chimeraant.berryforge.mcp.str
import dev.chimeraant.berryforge.sandbox.SandboxGuard
import dev.chimeraant.berryforge.session.SessionRecorder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `delete_file` — stages a file deletion in the local working copy. */
class DeleteFileTool(
    private val workspace: EditorWorkspace,
    private val sessions: SessionRecorder,
    private val sandbox: SandboxGuard,
) : McpTool {

    override val name = "delete_file"
    override val title = "Delete file"
    override val description =
        "Delete a file from the local working copy. Like write_file this stages the change " +
            "on-device only; the deletion is published by commit()."
    override val effect = SandboxGuard.Effect.DeleteFile

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "repo" to Schema.string("Repository in owner/name form."),
            "path" to Schema.string("Repo-relative path to delete."),
        ),
        required = listOf("repo", "path"),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val repo = args.requireStr("repo")
        val (owner, name) = McpValidation.splitRepo(repo)
        val path = McpValidation.validatePath(args.requireStr("path"))

        if (sandbox.isSandboxed()) {
            throw McpToolException(
                "Sandbox mode is on, so deletions are refused rather than applied to the working copy. " +
                    "Turn sandbox mode off to delete files.",
            )
        }

        val before = workspace.readLocal(owner, name, path)
            ?: throw McpToolException("No local copy of '$path' to delete.")

        workspace.deleteLocal(owner, name, path)
        sessions.recordChange(path = path, before = before, after = "")
        sessions.log(
            kind = "tool",
            title = "delete_file",
            detail = "$repo:$path",
            repo = repo,
            path = path,
            severity = "warn",
        )

        return Mcp.jsonResult(
            summary = "Staged deletion of $path in $repo (local only, not pushed).",
            payload = buildJsonObject {
                put("repo", repo)
                put("path", path)
                put("deleted", true)
                put("pushed", false)
            },
        )
    }
}

/** `get_diff` — the diff of everything staged locally. */
class GetDiffTool(
    private val flow: CommitFlow,
    private val workspace: EditorWorkspace,
) : McpTool {

    override val name = "get_diff"
    override val title = "Get staged diff"
    override val description =
        "Return the unified diff of all uncommitted local changes, or of specific paths. " +
            "Call this before commit() so you know exactly what you are about to publish."
    override val effect = SandboxGuard.Effect.ReadFile

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "repo" to Schema.string("Repository in owner/name form."),
            "paths" to Schema.string("Optional comma-separated list of paths. Defaults to all changed files."),
            "max_chars" to Schema.integer("Truncate the diff beyond this many characters.", 40000),
        ),
        required = listOf("repo"),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val repo = args.requireStr("repo")
        val (owner, name) = McpValidation.splitRepo(repo)
        val maxChars = args.int("max_chars", 40000).coerceIn(1000, 500_000)
        val paths = args.str("paths")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.map { McpValidation.validatePath(it) }
            ?.takeIf { it.isNotEmpty() }

        val changed = paths ?: workspace.dirtyPaths(owner, name)
        val diff = flow.stagedDiff(owner, name, changed)
        val truncated = diff.length > maxChars

        val summary = when {
            changed.isEmpty() -> "No uncommitted changes in $repo."
            diff.isBlank() -> "${changed.size} file(s) marked changed but no textual difference."
            else -> "Diff for ${changed.size} file(s) in $repo" + if (truncated) " (truncated)" else ""
        }

        return Mcp.jsonResult(
            summary = summary,
            payload = buildJsonObject {
                put("repo", repo)
                put("files", buildJsonArray { changed.forEach { add(JsonPrimitive(it)) } })
                put("truncated", truncated)
                put("diff", if (truncated) diff.take(maxChars) else diff)
            },
        )
    }
}

/** `git_status` — what is changed, staged and on which branch. */
class GitStatusTool(
    private val api: GitHubApi,
    private val workspace: EditorWorkspace,
) : McpTool {

    override val name = "git_status"
    override val title = "Git status"
    override val description =
        "Report the working-copy state for a repository: current branch, files with local " +
            "changes, files staged for deletion, and whether the branch exists on GitHub."
    override val effect = SandboxGuard.Effect.ReadFile

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "repo" to Schema.string("Repository in owner/name form."),
            "branch" to Schema.string("Branch to report on. Defaults to the repository default branch."),
        ),
        required = listOf("repo"),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val repo = args.requireStr("repo")
        val (owner, name) = McpValidation.splitRepo(repo)

        val defaultBranch = runCatching { api.repo(owner, name).defaultBranch }.getOrDefault("main")
        val branch = args.str("branch")?.takeIf { it.isNotBlank() } ?: defaultBranch

        val changed = workspace.dirtyPaths(owner, name)
        val deleted = changed.filter { workspace.isDeleted(owner, name, it) }
        val modified = changed - deleted.toSet()

        val remoteSha = api.headShaOrNull(owner, name, branch)

        val summary = buildString {
            appendLine("$repo on $branch")
            appendLine("modified: ${modified.size}  deleted: ${deleted.size}")
            appendLine(if (remoteSha != null) "branch exists on GitHub" else "branch does not exist on GitHub yet")
        }.trim()

        return Mcp.jsonResult(
            summary = summary,
            payload = buildJsonObject {
                put("repo", repo)
                put("branch", branch)
                put("default_branch", defaultBranch)
                put("branch_exists_remotely", remoteSha != null)
                put("remote_head", remoteSha ?: "")
                put("modified", buildJsonArray { modified.forEach { add(JsonPrimitive(it)) } })
                put("deleted", buildJsonArray { deleted.forEach { add(JsonPrimitive(it)) } })
                put("clean", changed.isEmpty())
            },
        )
    }
}

/** `create_branch` — creates a branch on GitHub without committing to it. */
class CreateBranchTool(
    private val api: GitHubApi,
) : McpTool {

    override val name = "create_branch"
    override val title = "Create branch"
    override val description =
        "Create a new branch on GitHub from an existing one. Use this to prepare a branch " +
            "before committing, or to check the name is free."
    override val effect = SandboxGuard.Effect.Push

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "repo" to Schema.string("Repository in owner/name form."),
            "branch" to Schema.string("Name of the new branch."),
            "from" to Schema.string("Branch to branch from. Defaults to the repository default branch."),
        ),
        required = listOf("repo", "branch"),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val repo = args.requireStr("repo")
        val newBranch = args.requireStr("branch")
        val (owner, name) = McpValidation.splitRepo(repo)

        if (newBranch.contains("..") || newBranch.startsWith("/") || newBranch.contains(' ')) {
            throw McpToolException("'$newBranch' is not a valid branch name.")
        }

        val from = args.str("from")?.takeIf { it.isNotBlank() }
            ?: runCatching { api.repo(owner, name).defaultBranch }.getOrDefault("main")

        val existing = api.headShaOrNull(owner, name, newBranch)
        if (existing != null) {
            return Mcp.jsonResult(
                summary = "Branch $newBranch already exists in $repo at ${existing.take(8)}.",
                payload = buildJsonObject {
                    put("repo", repo)
                    put("branch", newBranch)
                    put("created", false)
                    put("sha", existing)
                },
            )
        }

        val baseSha = api.headShaOrNull(owner, name, from)
            ?: throw McpToolException("Base branch '$from' does not exist in $repo.")

        api.createBranch(owner, name, newBranch, baseSha)

        return Mcp.jsonResult(
            summary = "Created $newBranch in $repo from $from (${baseSha.take(8)}).",
            payload = buildJsonObject {
                put("repo", repo)
                put("branch", newBranch)
                put("from", from)
                put("created", true)
                put("sha", baseSha)
            },
        )
    }
}
