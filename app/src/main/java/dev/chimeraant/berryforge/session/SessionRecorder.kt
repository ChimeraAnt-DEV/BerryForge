package dev.chimeraant.berryforge.session

import android.content.Context
import dev.chimeraant.berryforge.data.diff.DiffEngine
import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** One file-level change an agent session produced. */
@Serializable
data class FileChange(
    val path: String,
    val before: String,
    val after: String,
    val added: Int,
    val removed: Int,
) {
    val isNew: Boolean get() = before.isEmpty() && after.isNotEmpty()
    val isDeleted: Boolean get() = after.isEmpty() && before.isNotEmpty()
}

/** A single recorded interaction with a remote OpenHands agent. */
@Serializable
data class AgentSession(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val repo: String = "",
    val branch: String = "",
    val summary: String = "",
    val toolCalls: Int = 0,
    val builds: Int = 0,
    val tests: Int = 0,
    val sandboxed: Boolean = true,
    val changes: List<FileChange> = emptyList(),
    val approvedActions: Int = 0,
    val rejectedActions: Int = 0,
) {
    val durationMs: Long get() = (endedAt ?: System.currentTimeMillis()) - startedAt
    val totalAdded: Int get() = changes.sumOf { it.added }
    val totalRemoved: Int get() = changes.sumOf { it.removed }
}

/** A live line in the agent activity feed. */
@Serializable
data class AgentEvent(
    val at: Long,
    val kind: String,
    val title: String,
    val detail: String = "",
    val repo: String = "",
    val path: String = "",
    val severity: String = "info",
)

/**
 * Records what a connected agent did so the user can audit and undo it.
 *
 * The recorder keeps a live in-memory feed for the session viewer and persists the
 * finished sessions to disk. Every write the agent performs is captured with its
 * before/after text, which is what makes the revert button real rather than cosmetic.
 */
class SessionRecorder(
    private val context: Context,
    private val workspace: EditorWorkspace,
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; explicitNulls = false }
    private val dir: File = File(context.filesDir, "sessions").apply { mkdirs() }

    private val _events = MutableStateFlow<List<AgentEvent>>(emptyList())
    val events: StateFlow<List<AgentEvent>> = _events.asStateFlow()

    private val _sessions = MutableStateFlow<List<AgentSession>>(emptyList())
    val sessions: StateFlow<List<AgentSession>> = _sessions.asStateFlow()

    private val _current = MutableStateFlow<AgentSession?>(null)
    val current: StateFlow<AgentSession?> = _current.asStateFlow()

    /** Pending approval requests raised by the agent, awaiting a user decision. */
    private val _pending = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pending: StateFlow<List<ApprovalRequest>> = _pending.asStateFlow()

    private var sessionChanges = mutableListOf<FileChange>()
    private var sessionEvents = mutableListOf<AgentEvent>()

    init {
        _sessions.value = loadIndex()
    }

    // ---- Session lifecycle ----

    fun begin(repo: String, branch: String, sandboxed: Boolean): AgentSession {
        val session = AgentSession(
            id = UUID.randomUUID().toString(),
            startedAt = System.currentTimeMillis(),
            repo = repo,
            branch = branch,
            sandboxed = sandboxed,
            summary = "OpenHands session",
        )
        sessionChanges = mutableListOf()
        sessionEvents = mutableListOf()
        _events.value = emptyList()
        _current.value = session
        log("session", "Agent connected", if (sandboxed) "Running in sandbox mode" else "Full access", severity = "info")
        return session
    }

    fun end(summary: String? = null) {
        val active = _current.value ?: return
        val finished = active.copy(
            endedAt = System.currentTimeMillis(),
            changes = sessionChanges.toList(),
            summary = summary ?: active.summary,
            toolCalls = sessionEvents.count { it.kind == "tool" },
            builds = sessionEvents.count { it.kind == "build" },
            tests = sessionEvents.count { it.kind == "test" },
        )
        _current.value = null
        _sessions.value = listOf(finished) + _sessions.value
        persist(finished)
        log("session", "Agent disconnected", "Session recorded", severity = "info")
    }

    fun incrementApproved() {
        _current.value = _current.value?.let { it.copy(approvedActions = it.approvedActions + 1) }
    }

    fun incrementRejected() {
        _current.value = _current.value?.let { it.copy(rejectedActions = it.rejectedActions + 1) }
    }

    // ---- Event feed ----

    fun log(
        kind: String,
        title: String,
        detail: String = "",
        repo: String = "",
        path: String = "",
        severity: String = "info",
    ) {
        val event = AgentEvent(
            at = System.currentTimeMillis(),
            kind = kind,
            title = title,
            detail = detail,
            repo = repo,
            path = path,
            severity = severity,
        )
        sessionEvents += event
        _events.value = _events.value + event
    }

    fun clearEvents() {
        _events.value = emptyList()
        sessionEvents = mutableListOf()
    }

    // ---- Change capture ----

    /**
     * Records a file change with its before/after text so it can be diffed and reverted.
     * Called by the MCP server immediately before applying an agent write.
     */
    fun recordChange(path: String, before: String, after: String) {
        val diff = DiffEngine.compute(before, after)
        sessionChanges += FileChange(
            path = path,
            before = before,
            after = after,
            added = diff.added,
            removed = diff.removed,
        )
        _current.value = _current.value?.let { it.copy(changes = sessionChanges.toList()) }
        log(
            kind = "edit",
            title = "Edited $path",
            detail = "+${diff.added} / -${diff.removed}",
            path = path,
            severity = "edit",
        )
    }

    // ---- Approval gates ----

    fun requestApproval(request: ApprovalRequest) {
        _pending.value = _pending.value + request
        log(
            kind = "approval",
            title = "Awaiting approval: ${request.label}",
            detail = request.detail,
            path = request.path,
            severity = "warn",
        )
    }

    fun resolveApproval(id: String, approved: Boolean) {
        val request = _pending.value.firstOrNull { it.id == id } ?: return
        request.decision?.complete(approved)
        _pending.value = _pending.value.filterNot { it.id == id }
        if (approved) incrementApproved() else incrementRejected()
        log(
            kind = "approval",
            title = if (approved) "Approved ${request.label}" else "Rejected ${request.label}",
            detail = request.detail,
            path = request.path,
            severity = if (approved) "success" else "danger",
        )
    }

    // ---- Revert ----

    /**
     * Reverts every change in a session, newest first, so overlapping edits to the same
     * file unwind in the correct order.
     */
    suspend fun revertSession(session: AgentSession): Int = withContext(Dispatchers.IO) {
        val parts = session.repo.split("/")
        if (parts.size != 2) return@withContext 0
        var reverted = 0
        session.changes.asReversed().forEach { change ->
            val ok = runCatching {
                if (change.isNew) {
                    workspace.delete(parts[0], parts[1], change.path)
                } else {
                    workspace.writeLocal(parts[0], parts[1], change.path, change.before)
                }
            }.getOrDefault(false)
            if (ok) reverted++
        }
        if (reverted > 0) {
            log("revert", "Reverted ${session.changes.size} file(s)", session.repo, severity = "warn")
        }
        reverted
    }

    suspend fun revertSingleChange(session: AgentSession, change: FileChange): Boolean =
        withContext(Dispatchers.IO) {
            val parts = session.repo.split("/")
            if (parts.size != 2) return@withContext false
            if (change.isNew) {
                workspace.delete(parts[0], parts[1], change.path)
            } else {
                workspace.writeLocal(parts[0], parts[1], change.path, change.before)
            }
        }

    fun deleteSession(id: String) {
        _sessions.value = _sessions.value.filterNot { it.id == id }
        runCatching { File(dir, "$id.json").delete() }
    }

    // ---- Persistence ----

    private fun persist(session: AgentSession) {
        runCatching {
            File(dir, "${session.id}.json").writeText(json.encodeToString(AgentSession.serializer(), session))
        }
    }

    private fun loadIndex(): List<AgentSession> {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                json.decodeFromString(AgentSession.serializer(), file.readText())
            }.getOrNull()
        }.sortedByDescending { it.startedAt }
    }

    fun reload() {
        _sessions.value = loadIndex()
    }
}

/** An action the agent wants to take that needs a human decision. */
class ApprovalRequest(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val detail: String,
    val path: String = "",
    val effect: String = "",
) {
    @Volatile
    var decision: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
}
