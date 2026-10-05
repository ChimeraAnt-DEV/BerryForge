package dev.chimeraant.berryforge.mcp

import android.content.Context
import dev.chimeraant.berryforge.build.BuildLogParser
import dev.chimeraant.berryforge.build.GradleRunner
import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import dev.chimeraant.berryforge.data.github.CommitFlow
import dev.chimeraant.berryforge.data.github.GitHubApi
import dev.chimeraant.berryforge.data.github.RepoCache
import dev.chimeraant.berryforge.data.settings.SecureStore
import dev.chimeraant.berryforge.data.settings.SettingsStore
import dev.chimeraant.berryforge.mcp.tools.CommitTool
import dev.chimeraant.berryforge.mcp.tools.CreateBranchTool
import dev.chimeraant.berryforge.mcp.tools.DeleteFileTool
import dev.chimeraant.berryforge.mcp.tools.GetDiffTool
import dev.chimeraant.berryforge.mcp.tools.GitStatusTool
import dev.chimeraant.berryforge.mcp.tools.ListFilesTool
import dev.chimeraant.berryforge.mcp.tools.SearchCodeTool
import dev.chimeraant.berryforge.mcp.tools.GetBuildErrorsTool
import dev.chimeraant.berryforge.mcp.tools.InstallApkTool
import dev.chimeraant.berryforge.mcp.tools.ListReposTool
import dev.chimeraant.berryforge.mcp.tools.ReadFileTool
import dev.chimeraant.berryforge.mcp.tools.RunBuildTool
import dev.chimeraant.berryforge.mcp.tools.RunTestsTool
import dev.chimeraant.berryforge.mcp.tools.WriteFileTool
import dev.chimeraant.berryforge.sandbox.SandboxGuard
import dev.chimeraant.berryforge.session.SessionRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The BerryForge MCP server.
 *
 * Implements the Model Context Protocol over HTTP so a remote OpenHands agent can drive
 * this device: list repos, read and write files, commit, build, test, install.
 *
 * Responsibilities:
 *  - JSON-RPC 2.0 dispatch for `initialize`, `tools/list`, `tools/call`, `ping`,
 *    `resources/list` and `prompts/list`.
 *  - Bearer-token authentication on every request.
 *  - Approval gating: a tool whose effect requires approval blocks until the user
 *    answers in the in-app approval sheet.
 *  - Sandbox enforcement: effects the current mode forbids are refused before any
 *    work happens.
 *  - Session recording: every call and every file change is logged for audit and revert.
 *
 * The class is transport-agnostic. [McpHttpServer] owns the socket and hands bodies here.
 */
class McpServer(
    private val context: Context,
    private val api: GitHubApi,
    cache: RepoCache,
    workspace: EditorWorkspace,
    gradle: GradleRunner,
    buildLogs: BuildLogParser,
    private val settings: SettingsStore,
    private val secure: SecureStore,
    private val sandbox: SandboxGuard,
    private val sessions: SessionRecorder,
) {

    private val commitFlow = CommitFlow(api, workspace, sandbox)

    val tools: List<McpTool> = listOf(
        ListReposTool(api, cache),
        ListFilesTool(api, workspace),
        SearchCodeTool(api, workspace),
        ReadFileTool(api, cache, workspace),
        WriteFileTool(api, workspace, sessions, sandbox),
        DeleteFileTool(workspace, sessions, sandbox),
        GetDiffTool(commitFlow, workspace),
        GitStatusTool(api, workspace),
        CreateBranchTool(api),
        CommitTool(commitFlow, workspace, sessions, sandbox),
        RunBuildTool(gradle, buildLogs, workspace, sessions, sandbox),
        GetBuildErrorsTool(gradle, buildLogs),
        RunTestsTool(gradle, buildLogs, workspace, sessions),
        InstallApkTool(context, sandbox, sessions),
    ).sortedBy { it.name }

    private val toolsByName = tools.associateBy { it.name }

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    private val _lastRequestAt = MutableStateFlow(0L)
    val lastRequestAt: StateFlow<Long> = _lastRequestAt.asStateFlow()

    private val _initializedClients = MutableStateFlow<List<String>>(emptyList())
    val initializedClients: StateFlow<List<String>> = _initializedClients.asStateFlow()

    /** Id of the session opened by the most recent initialize, or null. */
    @Volatile
    private var activeSessionId: String? = null

    /** True when the request carried a valid bearer token. */
    fun authenticate(authorizationHeader: String?): Boolean {
        val expected = secure.mcpTokenOrCreate()
        if (authorizationHeader.isNullOrBlank()) return false
        val presented = authorizationHeader
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .trim()
        return constantTimeEquals(presented, expected)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    // ---- JSON-RPC entry point ----

    /** Handles one JSON-RPC message and returns the response, or null for notifications. */
    suspend fun handleMessage(body: String): String? {
        _lastRequestAt.value = System.currentTimeMillis()
        val root = runCatching { Mcp.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Mcp.error(null, Mcp.PARSE_ERROR, "Request body is not a JSON object.").toString()

        val id = root["id"]
        val method = root["method"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val params = root.params()

        // Notifications carry no id and expect no response.
        if (id == null && method != null && method.startsWith("notifications/")) {
            handleNotification(method, params)
            return null
        }
        if (method == null) {
            return Mcp.error(id, Mcp.INVALID_REQUEST, "Missing 'method'.").toString()
        }

        val response = when (method) {
            "initialize" -> handleInitialize(id, params)
            "ping" -> Mcp.result(id, buildJsonObject {})
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolsCall(id, params)
            "resources/list" -> Mcp.result(id, buildJsonObject {
                put("resources", buildJsonArray {})
            })
            "prompts/list" -> Mcp.result(id, buildJsonObject {
                put("prompts", buildJsonArray {})
            })
            "logging/setLevel" -> Mcp.result(id, buildJsonObject {})
            else -> Mcp.error(id, Mcp.METHOD_NOT_FOUND, "Unknown method '$method'.")
        }
        return response.toString()
    }

    private fun handleNotification(method: String, params: JsonObject) {
        when (method) {
            "notifications/initialized" -> {
                sessions.log("session", "Agent completed MCP handshake", severity = "success")
            }
            "notifications/cancelled" -> {
                sessions.log("session", "Agent cancelled a request", severity = "warn")
            }
        }
    }

    private suspend fun handleInitialize(id: JsonElement?, params: JsonObject): JsonObject {
        val requested = params["protocolVersion"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val negotiated = requested?.takeIf { it in Mcp.SUPPORTED_VERSIONS } ?: Mcp.PROTOCOL_VERSION
        val clientName = params["clientInfo"]?.jsonObject?.get("name")
            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: "unknown client"

        _initializedClients.value = (_initializedClients.value + clientName).distinct().takeLast(10)
        _connectionCount.value = _connectionCount.value + 1

        // Start recording the session. Without this the activity feed, the change list,
        // the approval counters and the revert history were all permanently empty — the
        // recorder existed but nothing ever opened a session.
        startSession(clientName)

        sessions.log("session", "MCP client initialised: $clientName", "protocol $negotiated", severity = "success")

        return Mcp.result(id, buildJsonObject {
            put("protocolVersion", negotiated)
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
                putJsonObject("resources") { put("subscribe", false); put("listChanged", false) }
                putJsonObject("logging") {}
            }
            putJsonObject("serverInfo") {
                put("name", Mcp.SERVER_NAME)
                put("version", Mcp.SERVER_VERSION)
            }
            put("instructions", SERVER_INSTRUCTIONS)
        })
    }

    /**
     * Opens a recorded session for the connecting client.
     *
     * The repo and branch are taken from whatever the user has open, so the recorded
     * diff lands against the right repository.
     */
    private suspend fun startSession(clientName: String) {
        val repo = runCatching {
            val last = settings.lastRepo.first()
            last.orEmpty()
        }.getOrDefault("")
        val branch = runCatching {
            if (repo.contains('/')) {
                val parts = repo.split('/')
                api.repo(parts[0], parts[1]).defaultBranch
            } else {
                ""
            }
        }.getOrDefault("")
        val sandboxed = runCatching { sandbox.isSandboxed() }.getOrDefault(true)
        val session = sessions.begin(repo = repo, branch = branch, sandboxed = sandboxed)
        activeSessionId = session.id
    }

    /** Closes the recorded session, if one is open. */
    fun endSession(summary: String? = null) {
        if (activeSessionId != null) {
            sessions.end(summary)
            activeSessionId = null
        }
    }

    /** Closes any open session. Called when the server stops. */
    fun closeAllSessions() = endSession("MCP server stopped")

    private fun handleToolsList(id: JsonElement?): JsonObject = Mcp.result(id, buildJsonObject {
        put("tools", buildJsonArray {
            tools.forEach { tool ->
                add(buildJsonObject {
                    put("name", tool.name)
                    put("title", tool.title)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                })
            }
        })
    })

    private suspend fun handleToolsCall(id: JsonElement?, params: JsonObject): JsonObject {
        val name = params["name"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?: return Mcp.error(id, Mcp.INVALID_PARAMS, "Missing tool 'name'.")
        val tool = toolsByName[name]
            ?: return Mcp.error(id, Mcp.INVALID_PARAMS, "Unknown tool '$name'.")
        val args = params.args()

        sessions.log("tool", name, summarizeArgs(name, args))

        // Gate the action before doing any work.
        val decision = sandbox.evaluate(tool.effect)
        if (!decision.allowed) {
            sessions.log("tool", "$name blocked", decision.reason ?: "", severity = "danger")
            return Mcp.result(id, Mcp.textResult(
                "Blocked: ${decision.reason ?: "not permitted in the current mode."}",
                isError = true,
            ))
        }
        if (decision.requiresApproval) {
            val approved = requestApproval(tool, args, decision.reason)
            if (!approved) {
                sessions.log("tool", "$name rejected by user", severity = "danger")
                return Mcp.result(id, Mcp.textResult(
                    "The user rejected this action. Do not retry it; ask what they would prefer instead.",
                    isError = true,
                ))
            }
        }

        return try {
            val result = tool.invoke(args)
            Mcp.result(id, result)
        } catch (denied: McpDeniedException) {
            Mcp.result(id, Mcp.textResult(denied.message ?: "Denied.", isError = true))
        } catch (invalid: McpToolException) {
            Mcp.error(id, Mcp.INVALID_PARAMS, invalid.message ?: "Invalid arguments.")
        } catch (error: Exception) {
            sessions.log("tool", "$name failed", error.message ?: "", severity = "danger")
            Mcp.result(id, Mcp.textResult(
                "Tool '$name' failed: ${error.message ?: error::class.simpleName}",
                isError = true,
            ))
        }
    }

    /**
     * Blocks the tool call until the user answers the approval sheet, or until the
     * request is abandoned. Defaults to denying on timeout so an unattended device
     * cannot be driven without consent.
     */
    private suspend fun requestApproval(
        tool: McpTool,
        args: JsonObject,
        reason: String?,
    ): Boolean {
        val request = dev.chimeraant.berryforge.session.ApprovalRequest(
            label = tool.title,
            detail = describeAction(tool.name, args, reason),
            path = args.str("path").orEmpty(),
            effect = tool.effect.name,
        )
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        request.decision = deferred
        sessions.requestApproval(request)
        return try {
            kotlinx.coroutines.withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } ?: run {
                sessions.resolveApproval(request.id, approved = false)
                false
            }
        } catch (error: Exception) {
            false
        }
    }

    /**
     * Builds the text the user sees at an approval gate.
     *
     * The previous version showed only repo and path, which is not enough to decide
     * whether to allow a write, a commit or a build — the user could not see *what* was
     * being written or *which* task was about to run.
     */
    private fun describeAction(name: String, args: JsonObject, reason: String?): String = buildString {
        val repo = args.str("repo").orEmpty()
        val path = args.str("path").orEmpty()
        if (repo.isNotBlank()) appendLine("Repository: $repo")
        if (path.isNotBlank()) appendLine("Path: $path")

        when (name) {
            "write_file" -> {
                val content = args.str("content").orEmpty()
                appendLine("Bytes: ${content.toByteArray().size}  Lines: ${content.lines().size}")
                if (content.isNotBlank()) {
                    appendLine("Preview:")
                    appendLine(content.lines().take(PREVIEW_LINES).joinToString("\n").take(PREVIEW_CHARS))
                    if (content.lines().size > PREVIEW_LINES) appendLine("…")
                }
            }
            "commit" -> {
                appendLine("Branch: ${args.str("branch").orEmpty()}")
                appendLine("Message: ${args.str("message").orEmpty()}")
                args.str("paths")?.let { appendLine("Paths: $it") }
            }
            "run_build", "run_tests" -> {
                appendLine("Task: ${args.str("task") ?: "assembleDebug"}")
            }
            "install_apk" -> appendLine("APK: ${args.str("path").orEmpty()}")
        }
        if (reason != null) appendLine(reason)
    }.trim().ifBlank { summarizeArgs(name, args) }

    private fun summarizeArgs(name: String, args: JsonObject): String {
        val repo = args.str("repo").orEmpty()
        val path = args.str("path").orEmpty()
        return buildString {
            if (repo.isNotBlank()) append(repo)
            if (path.isNotBlank()) append(if (isNotEmpty()) ":" else "").append(path)
            if (repo.isBlank() && path.isBlank()) append(args.keys.joinToString(", "))
        }
    }

    companion object {
        private const val APPROVAL_TIMEOUT_MS = 5 * 60 * 1000L

        /** How much of a write to show at an approval gate. */
        private const val PREVIEW_LINES = 12
        private const val PREVIEW_CHARS = 800

        private const val SERVER_INSTRUCTIONS = """
BerryForge exposes a working copy of the user's GitHub repositories running on their own
Android device. Files live in an on-device mirror; read_file and write_file operate on that
mirror, not on GitHub directly.

Working agreement:
1. Call list_repos first to learn the exact owner/name of the target repository.
2. Read before you write. read_file gives you the current contents and the blob sha.
3. write_file stages changes locally. It never pushes. The user reviews the diff.
4. Call commit to publish, and only when the user has agreed the change is ready.
5. Use run_build and run_tests to verify. get_build_errors returns parsed errors with
   file and line so you can fix them without re-reading the whole log.
6. The user may have sandbox mode on. In sandbox mode pushes and APK installs are refused
   and file writes land in a scratch area. If a tool reports that it is blocked, do not
   retry it — tell the user and ask how they would like to proceed.
7. Every write and build may require explicit user approval. If a call is rejected, treat
   that as a decision, not an error, and do not attempt the same action again.
"""
    }
}
