package dev.chimeraant.berryforge.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chimeraant.berryforge.session.AgentEvent
import dev.chimeraant.berryforge.session.AgentSession
import dev.chimeraant.berryforge.session.ApprovalRequest
import dev.chimeraant.berryforge.session.FileChange
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.components.BerryButton
import dev.chimeraant.berryforge.ui.components.BerryButtonSize
import dev.chimeraant.berryforge.ui.components.BerryButtonVariant
import dev.chimeraant.berryforge.ui.components.BerryChip
import dev.chimeraant.berryforge.ui.components.BerryDialog
import dev.chimeraant.berryforge.ui.components.BerryDivider
import dev.chimeraant.berryforge.ui.components.BerryEmptyState
import dev.chimeraant.berryforge.ui.components.BerryIcon
import dev.chimeraant.berryforge.ui.components.BerryIconButton
import dev.chimeraant.berryforge.ui.components.BerrySheet
import dev.chimeraant.berryforge.ui.components.BerrySurface
import dev.chimeraant.berryforge.ui.components.BerrySwitch
import dev.chimeraant.berryforge.ui.components.BerryTopBar
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * The agent session viewer.
 *
 * Three things happen on this screen and nowhere else:
 *  1. A live feed of what the connected agent is doing — tool calls, reads, edits,
 *     builds, tests — so the user can watch rather than trust.
 *  2. Approval gates. Anything the agent wants to do that mutates state blocks here
 *     until the user answers. Default is approve reads, require approval for writes
 *     and builds.
 *  3. Sandbox mode. A single toggle that restricts the agent to the BerryForge cache:
 *     no pushes, no APK installs.
 *
 * Session history lives in a sheet, with a real revert for every recorded change.
 */
@Composable
fun AgentScreen(
    viewModel: BerryViewModel,
    onProfileClick: () -> Unit,
    onManageAccounts: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val events by viewModel.sessions.events.collectAsStateWithLifecycle()
    val current by viewModel.sessions.current.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.sessions.collectAsStateWithLifecycle()
    val pending by viewModel.pendingApprovals.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val isOwner by viewModel.isOwner.collectAsStateWithLifecycle()
    val tunnelState by viewModel.tunnels.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(AgentTab.Activity) }
    var historyOpen by remember { mutableStateOf(false) }
    var sandboxMode by remember { mutableStateOf(true) }
    var approveReads by remember { mutableStateOf(false) }
    var approveWrites by remember { mutableStateOf(true) }
    var approveBuilds by remember { mutableStateOf(true) }
    var revertTarget by remember { mutableStateOf<AgentSession?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.settings.sandboxMode.collect { sandboxMode = it } }
    LaunchedEffect(Unit) { viewModel.settings.approveReads.collect { approveReads = it } }
    LaunchedEffect(Unit) { viewModel.settings.approveWrites.collect { approveWrites = it } }
    LaunchedEffect(Unit) { viewModel.settings.approveBuilds.collect { approveBuilds = it } }

    // Keep the activity feed pinned to the newest event.
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) runCatching { listState.scrollToItem(events.lastIndex) }
    }

    val endpoint = (tunnelState as? dev.chimeraant.berryforge.mcp.TunnelState.Up)?.publicUrl
    val active = current != null

    Column(modifier.fillMaxSize().background(BerryColors.Base)) {
        BerryTopBar(
            title = "Agent",
            subtitle = when {
                endpoint != null -> endpoint
                active -> "Session in progress"
                else -> "Not connected"
            },
            viewModel = viewModel,
            onProfileClick = onProfileClick,
            onManageAccounts = onManageAccounts,
            actions = {
                BerryIconButton(BerryIcons.History, "Session history", onClick = { historyOpen = true })
            },
        )

        // ---- Live endpoint banner ----
        EndpointBanner(
            endpoint = endpoint,
            connected = active,
            onCopy = { viewModel.copyEndpoint(endpoint) },
        )

        // ---- Sandbox + approvals ----
        SandboxPanel(
            sandboxMode = sandboxMode,
            onSandboxChange = {
                sandboxMode = it
                scope.launch { viewModel.settings.setSandboxMode(it) }
            },
            approveReads = approveReads,
            onApproveReads = { approveReads = it; scope.launch { viewModel.settings.setApproveReads(it) } },
            approveWrites = approveWrites,
            onApproveWrites = { approveWrites = it; scope.launch { viewModel.settings.setApproveWrites(it) } },
            approveBuilds = approveBuilds,
            onApproveBuilds = { approveBuilds = it; scope.launch { viewModel.settings.setApproveBuilds(it) } },
        )

        Spacer(Modifier.height(BerrySpacing.md))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = BerrySpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
        ) {
            SegmentedTab("Activity", events.size, tab == AgentTab.Activity, BerryColors.Ai) { tab = AgentTab.Activity }
            SegmentedTab("Changes", current?.changes?.size ?: 0, tab == AgentTab.Changes, BerryColors.Edit) { tab = AgentTab.Changes }
            if (pending.isNotEmpty()) {
                SegmentedTab("Approvals", pending.size, tab == AgentTab.Approvals, BerryColors.Warning) { tab = AgentTab.Approvals }
            }
        }

        Spacer(Modifier.height(BerrySpacing.sm))

        when (tab) {
            AgentTab.Activity -> if (events.isEmpty()) {
                BerryEmptyState(
                    icon = BerryIcons.Robot,
                    title = "No agent activity yet",
                    body = "Start the MCP endpoint in Settings, paste the URL and token into " +
                        "OpenHands, and everything the agent does will stream here.",
                    accent = BerryColors.Ai,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().background(BerryColors.TermBackground),
                    state = listState,
                    contentPadding = PaddingValues(BerrySpacing.md),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(events) { _, event -> EventRow(event) }
                }
            }

            AgentTab.Changes -> {
                val changes = current?.changes.orEmpty()
                if (changes.isEmpty()) {
                    BerryEmptyState(
                        icon = BerryIcons.Diff,
                        title = "No changes recorded",
                        body = "When the agent writes a file, the before and after are captured here so you can review or revert them.",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = BerrySpacing.lg,
                            end = BerrySpacing.lg,
                            bottom = BerrySpacing.xxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
                    ) {
                        items(changes) { change -> ChangeCard(change = change) }
                    }
                }
            }

            AgentTab.Approvals -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = BerrySpacing.lg,
                    end = BerrySpacing.lg,
                    bottom = BerrySpacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
            ) {
                items(pending, key = { it.id }) { request ->
                    ApprovalCard(
                        request = request,
                        onApprove = { viewModel.sessions.resolveApproval(request.id, true) },
                        onReject = { viewModel.sessions.resolveApproval(request.id, false) },
                    )
                }
            }
        }
    }

    HistorySheet(
        visible = historyOpen,
        sessions = sessions,
        onDismiss = { historyOpen = false },
        onRevert = { revertTarget = it },
        onDelete = { viewModel.sessions.deleteSession(it.id) },
    )

    revertTarget?.let { session ->
        BerryDialog(
            visible = true,
            onDismiss = { revertTarget = null },
            title = "Revert this session?",
            message = "Every file the agent changed in this session will be restored to its " +
                "previous contents. ${session.changes.size} file(s) affected.",
            icon = BerryIcons.History,
            accent = BerryColors.Warning,
            confirmLabel = "Revert",
            destructive = true,
            onConfirm = {
                scope.launch {
                    val count = viewModel.sessions.revertSession(session)
                    viewModel.toast("Reverted $count file(s).")
                }
            },
        )
    }

    // Approval gate shown as a blocking dialog whenever a request is outstanding.
    pending.firstOrNull()?.let { request ->
        BerryDialog(
            visible = true,
            onDismiss = { viewModel.sessions.resolveApproval(request.id, false) },
            title = "Approve ${request.label}?",
            message = request.detail.ifBlank { "The agent is waiting to proceed." },
            icon = BerryIcons.Shield,
            accent = BerryColors.Warning,
            confirmLabel = "Approve",
            dismissLabel = "Reject",
            onConfirm = { viewModel.sessions.resolveApproval(request.id, true) },
        )
    }
}

private enum class AgentTab { Activity, Changes, Approvals }

@Composable
private fun SegmentedTab(
    label: String,
    count: Int,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(BerryRadius.sm))
            .background(if (selected) accent.copy(alpha = 0.14f) else BerryColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = BerryType.Label, color = if (selected) accent else BerryColors.TextTertiary)
        if (count > 0) {
            Spacer(Modifier.width(BerrySpacing.xs))
            Text("$count", style = BerryType.Micro, color = accent)
        }
    }
}

@Composable
private fun EndpointBanner(endpoint: String?, connected: Boolean, onCopy: () -> Unit) {
    val accent = if (endpoint != null) BerryColors.Success else BerryColors.TextTertiary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BerrySpacing.lg, vertical = BerrySpacing.sm)
            .clip(RoundedCornerShape(BerryRadius.md))
            .background(accent.copy(alpha = 0.10f))
            .padding(BerrySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(BerryRadius.pill))
                .background(if (connected) BerryColors.Success else accent),
        )
        Spacer(Modifier.width(BerrySpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                if (endpoint != null) "Public endpoint" else "Endpoint not running",
                style = BerryType.Overline,
                color = accent,
            )
            Text(
                endpoint ?: "Start the MCP server in Settings to expose this device",
                style = BerryType.CodeSmall,
                color = if (endpoint != null) BerryColors.TextPrimary else BerryColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (endpoint != null) {
            BerryIconButton(BerryIcons.Copy, "Copy endpoint", onCopy, tint = accent)
        }
    }
}

@Composable
private fun SandboxPanel(
    sandboxMode: Boolean,
    onSandboxChange: (Boolean) -> Unit,
    approveReads: Boolean,
    onApproveReads: (Boolean) -> Unit,
    approveWrites: Boolean,
    onApproveWrites: (Boolean) -> Unit,
    approveBuilds: Boolean,
    onApproveBuilds: (Boolean) -> Unit,
) {
    BerrySurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BerrySpacing.lg),
        color = if (sandboxMode) BerryColors.Success.copy(alpha = 0.08f) else BerryColors.Warning.copy(alpha = 0.08f),
        border = if (sandboxMode) BerryColors.Success.copy(alpha = 0.3f) else BerryColors.Warning.copy(alpha = 0.3f),
        radius = BerryRadius.lg,
        contentPadding = PaddingValues(BerrySpacing.lg),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BerryIcon(
                    if (sandboxMode) BerryIcons.Shield else BerryIcons.Zap,
                    null,
                    size = BerrySize.icon,
                    tint = if (sandboxMode) BerryColors.Success else BerryColors.Warning,
                )
                Spacer(Modifier.width(BerrySpacing.md))
                Column(Modifier.weight(1f)) {
                    Text("Sandbox mode", style = BerryType.BodyStrong, color = BerryColors.TextPrimary)
                    Text(
                        if (sandboxMode) {
                            "Agent is limited to the BerryForge cache. No pushes, no APK installs."
                        } else {
                            "Agent has full access, including pushing to GitHub."
                        },
                        style = BerryType.Caption,
                        color = BerryColors.TextTertiary,
                    )
                }
                BerrySwitch(
                    checked = sandboxMode,
                    onCheckedChange = onSandboxChange,
                    accent = BerryColors.Success,
                )
            }
            Spacer(Modifier.height(BerrySpacing.md))
            BerryDivider()
            Spacer(Modifier.height(BerrySpacing.sm))
            Text("APPROVAL GATES", style = BerryType.Overline, color = BerryColors.TextTertiary)
            Spacer(Modifier.height(BerrySpacing.xs))
            GateRow("Approve reads automatically", approveReads, onApproveReads)
            GateRow("Require approval for writes", approveWrites, onApproveWrites)
            GateRow("Require approval for builds", approveBuilds, onApproveBuilds)
        }
    }
}

@Composable
private fun GateRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = BerrySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = BerryType.BodySmall, color = BerryColors.TextSecondary, modifier = Modifier.weight(1f))
        BerrySwitch(checked = checked, onCheckedChange = onChange, accent = BerryColors.Warning)
    }
}

@Composable
private fun EventRow(event: AgentEvent) {
    val color = when (event.severity) {
        "danger" -> BerryColors.Danger
        "warn" -> BerryColors.Warning
        "success" -> BerryColors.Success
        "edit" -> BerryColors.Ai
        else -> BerryColors.TextSecondary
    }
    val icon = when (event.kind) {
        "tool" -> BerryIcons.Hammer
        "build" -> BerryIcons.Hammer
        "test" -> BerryIcons.Bug
        "edit" -> BerryIcons.Pencil
        "approval" -> BerryIcons.Shield
        "revert" -> BerryIcons.History
        else -> BerryIcons.Robot
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        BerryIcon(icon, null, size = 13.dp, tint = color)
        Spacer(Modifier.width(BerrySpacing.sm))
        Column(Modifier.weight(1f)) {
            Text(event.title, style = BerryType.CodeSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (event.detail.isNotBlank()) {
                Text(
                    event.detail,
                    style = BerryType.CodeMicro,
                    color = BerryColors.TextDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            formatClock(event.at),
            style = BerryType.CodeMicro,
            color = BerryColors.TextDisabled,
        )
    }
}

@Composable
private fun ChangeCard(change: FileChange) {
    BerrySurface(
        modifier = Modifier.fillMaxWidth(),
        color = BerryColors.Surface2,
        border = BerryColors.Outline,
        radius = BerryRadius.md,
        contentPadding = PaddingValues(BerrySpacing.md),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    change.path,
                    style = BerryType.CodeSmall,
                    color = BerryColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                BerryChip("+${change.added}", color = BerryColors.DiffAddStrong)
                Spacer(Modifier.width(BerrySpacing.xs))
                BerryChip("-${change.removed}", color = BerryColors.DiffRemoveStrong)
            }
            if (change.isNew) {
                Spacer(Modifier.height(BerrySpacing.xs))
                BerryChip("new file", color = BerryColors.Success)
            }
        }
    }
}

@Composable
private fun ApprovalCard(request: ApprovalRequest, onApprove: () -> Unit, onReject: () -> Unit) {
    BerrySurface(
        modifier = Modifier.fillMaxWidth(),
        color = BerryColors.Surface2,
        border = BerryColors.Warning.copy(alpha = 0.4f),
        radius = BerryRadius.md,
        contentPadding = PaddingValues(BerrySpacing.md),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BerryIcon(BerryIcons.Shield, null, size = BerrySize.iconSm, tint = BerryColors.Warning)
                Spacer(Modifier.width(BerrySpacing.sm))
                Text(request.label, style = BerryType.BodyStrong, color = BerryColors.TextPrimary)
            }
            Spacer(Modifier.height(BerrySpacing.xs))
            Text(request.detail, style = BerryType.Caption, color = BerryColors.TextTertiary)
            Spacer(Modifier.height(BerrySpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm)) {
                BerryButton("Approve", onApprove, modifier = Modifier.weight(1f), size = BerryButtonSize.Sm)
                BerryButton(
                    "Reject",
                    onReject,
                    variant = BerryButtonVariant.Danger,
                    modifier = Modifier.weight(1f),
                    size = BerryButtonSize.Sm,
                )
            }
        }
    }
}

@Composable
private fun HistorySheet(
    visible: Boolean,
    sessions: List<AgentSession>,
    onDismiss: () -> Unit,
    onRevert: (AgentSession) -> Unit,
    onDelete: (AgentSession) -> Unit,
) {
    BerrySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Session history",
        subtitle = "${sessions.size} recorded session(s)",
        icon = BerryIcons.History,
        accent = BerryColors.Edit,
    ) {
        if (sessions.isEmpty()) {
            BerryEmptyState(
                icon = BerryIcons.History,
                title = "No sessions yet",
                body = "Each time an OpenHands agent connects, the session is recorded here with the diff of what changed.",
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(440.dp).padding(horizontal = BerrySpacing.xl),
                verticalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onRevert = { onRevert(session) },
                        onDelete = { onDelete(session) },
                    )
                }
            }
            Spacer(Modifier.height(BerrySpacing.lg))
        }
    }
}

@Composable
private fun SessionCard(session: AgentSession, onRevert: () -> Unit, onDelete: () -> Unit) {
    BerrySurface(
        modifier = Modifier.fillMaxWidth(),
        color = BerryColors.Surface2,
        border = BerryColors.Outline,
        radius = BerryRadius.md,
        contentPadding = PaddingValues(BerrySpacing.md),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.repo.ifBlank { "Unknown repo" },
                    style = BerryType.BodyStrong,
                    color = BerryColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.sandboxed) {
                    BerryChip("sandboxed", color = BerryColors.Success, icon = BerryIcons.Shield)
                }
            }
            Spacer(Modifier.height(BerrySpacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.md)) {
                Text(formatDateTime(session.startedAt), style = BerryType.Micro, color = BerryColors.TextDisabled)
                Text("${session.durationMs / 1000}s", style = BerryType.Micro, color = BerryColors.TextDisabled)
                Text("${session.toolCalls} calls", style = BerryType.Micro, color = BerryColors.TextDisabled)
                if (session.changes.isNotEmpty()) {
                    Text(
                        "${session.changes.size} files +${session.totalAdded}/-${session.totalRemoved}",
                        style = BerryType.Micro,
                        color = BerryColors.Edit,
                    )
                }
            }
            if (session.approvedActions > 0 || session.rejectedActions > 0) {
                Spacer(Modifier.height(BerrySpacing.xs))
                Text(
                    "${session.approvedActions} approved · ${session.rejectedActions} rejected",
                    style = BerryType.Micro,
                    color = BerryColors.Warning,
                )
            }
            if (session.changes.isNotEmpty()) {
                Spacer(Modifier.height(BerrySpacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm)) {
                    BerryButton(
                        "Revert",
                        onRevert,
                        variant = BerryButtonVariant.Secondary,
                        size = BerryButtonSize.Sm,
                        icon = BerryIcons.History,
                        modifier = Modifier.weight(1f),
                    )
                    BerryButton(
                        "Delete",
                        onDelete,
                        variant = BerryButtonVariant.Ghost,
                        size = BerryButtonSize.Sm,
                        icon = BerryIcons.Trash,
                    )
                }
            }
        }
    }
}

private fun formatClock(millis: Long): String {
    val format = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    return format.format(java.util.Date(millis))
}

private fun formatDateTime(millis: Long): String {
    val format = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
    return format.format(java.util.Date(millis))
}
