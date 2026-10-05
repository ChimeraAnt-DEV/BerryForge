package dev.chimeraant.berryforge.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chimeraant.berryforge.build.BuildError
import dev.chimeraant.berryforge.build.BuildState
import dev.chimeraant.berryforge.build.BuildTask
import dev.chimeraant.berryforge.build.LogLine
import dev.chimeraant.berryforge.build.LogSeverity
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.components.BerryButton
import dev.chimeraant.berryforge.ui.components.BerryButtonSize
import dev.chimeraant.berryforge.ui.components.BerryButtonVariant
import dev.chimeraant.berryforge.ui.components.BerryChip
import dev.chimeraant.berryforge.ui.components.BerryEmptyState
import dev.chimeraant.berryforge.ui.components.BerryIcon
import dev.chimeraant.berryforge.ui.components.BerryIconButton
import dev.chimeraant.berryforge.ui.components.BerrySearchField
import dev.chimeraant.berryforge.ui.components.BerrySheet
import dev.chimeraant.berryforge.ui.components.BerrySnackbar
import dev.chimeraant.berryforge.ui.components.BerrySurface
import dev.chimeraant.berryforge.ui.components.BerryTopBar
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType
import kotlinx.coroutines.launch
import java.io.File

/**
 * Build log viewer.
 *
 * Every line is colour-coded by severity, the log is searchable, and each parsed error
 * is tappable — tapping one opens the file in the editor at that line. The errors panel
 * is the primary surface; the raw log sits behind it for when the parser is not enough.
 */
@Composable
fun BuildScreen(
    viewModel: BerryViewModel,
    onOpenFileAtLine: (path: String, line: Int) -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.gradle.state.collectAsStateWithLifecycle()
    val lines by viewModel.gradle.logLines.collectAsStateWithLifecycle()
    val errors by viewModel.gradle.errors.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val isOwner by viewModel.isOwner.collectAsStateWithLifecycle()
    val repo by viewModel.openRepo.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var showErrorsOnly by remember { mutableStateOf(false) }
    var apkSheet by remember { mutableStateOf<File?>(null) }
    var tab by remember { mutableStateOf(BuildTab.Errors) }
    val listState = rememberLazyListState()

    val filtered = remember(lines, query, showErrorsOnly) {
        lines.filter { line ->
            (!showErrorsOnly || line.severity == LogSeverity.Error) &&
                (query.isBlank() || line.text.contains(query, ignoreCase = true))
        }
    }

    // Follow the tail while a build is streaming.
    LaunchedEffect(filtered.size, state) {
        if (state is BuildState.Running && filtered.isNotEmpty()) {
            runCatching { listState.scrollToItem(filtered.lastIndex) }
        }
    }

    val running = state is BuildState.Running
    val canBuild = repo != null

    Column(modifier.fillMaxSize().background(BerryColors.Base)) {
        BerryTopBar(
            title = "Build",
            subtitle = when (val current = state) {
                is BuildState.Running -> "${current.task.args.first()} · ${current.lineCount} lines"
                is BuildState.Succeeded -> "${current.task.args.first()} · succeeded in ${current.durationMs / 1000}s"
                is BuildState.Failed -> "${current.task.args.first()} · failed with ${current.errors} error(s)"
                is BuildState.Cancelled -> "cancelled"
                else -> repo?.fullName ?: "No repository open"
            },
            user = user,
            isOwner = isOwner,
            onProfileClick = onProfileClick,
            actions = {
                if (running) {
                    BerryIconButton(BerryIcons.Close, "Cancel build", onClick = { viewModel.gradle.cancel() }, tint = BerryColors.Danger)
                } else {
                    BerryIconButton(BerryIcons.Trash, "Clear log", onClick = { viewModel.gradle.clearLogs() })
                }
            },
        )

        // ---- Task launcher ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BerrySpacing.lg, vertical = BerrySpacing.md),
            horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
        ) {
            BerryButton(
                text = if (running) "Building…" else "assembleDebug",
                onClick = {
                    val target = repo ?: return@BerryButton
                    scope.launch {
                        val projectDir = viewModel.workspace.repoDir(target.ownerLogin, target.name)
                        val result = viewModel.gradle.run(projectDir, BuildTask.AssembleDebug)
                        if (result is BuildState.Succeeded && result.apk != null) {
                            apkSheet = result.apk
                        }
                    }
                },
                icon = BerryIcons.Play,
                enabled = canBuild && !running,
                modifier = Modifier.weight(1f),
            )
            BerryButton(
                text = "Test",
                onClick = {
                    val target = repo ?: return@BerryButton
                    scope.launch {
                        viewModel.gradle.run(
                            viewModel.workspace.repoDir(target.ownerLogin, target.name),
                            BuildTask.Test,
                        )
                    }
                },
                variant = BerryButtonVariant.Secondary,
                icon = BerryIcons.Bug,
                enabled = canBuild && !running,
            )
            BerryButton(
                text = "Clean",
                onClick = {
                    val target = repo ?: return@BerryButton
                    scope.launch {
                        viewModel.gradle.run(
                            viewModel.workspace.repoDir(target.ownerLogin, target.name),
                            BuildTask.Clean,
                        )
                    }
                },
                variant = BerryButtonVariant.Ghost,
                icon = BerryIcons.Trash,
                enabled = canBuild && !running,
            )
        }

        if (!canBuild) {
            BerryEmptyState(
                icon = BerryIcons.Hammer,
                title = "No repository open",
                body = "Open a repository from the Repos tab, then run a build here.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        // ---- Tabs ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BerrySpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
        ) {
            SegmentedTab("Errors", errors.size, tab == BuildTab.Errors, BerryColors.Danger) { tab = BuildTab.Errors }
            SegmentedTab("Log", filtered.size, tab == BuildTab.Log, BerryColors.Edit) { tab = BuildTab.Log }
        }

        Spacer(Modifier.height(BerrySpacing.sm))

        Box(Modifier.padding(horizontal = BerrySpacing.lg)) {
            BerrySearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = if (tab == BuildTab.Errors) "Filter errors" else "Search the log",
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    BerryIconButton(
                        BerryIcons.Filter,
                        "Errors only",
                        onClick = { showErrorsOnly = !showErrorsOnly },
                        active = showErrorsOnly,
                        activeTint = BerryColors.Danger,
                        tint = BerryColors.TextTertiary,
                    )
                },
            )
        }

        Spacer(Modifier.height(BerrySpacing.sm))

        when (tab) {
            BuildTab.Errors -> if (errors.isEmpty()) {
                BerryEmptyState(
                    icon = if (lines.isEmpty()) BerryIcons.Hammer else BerryIcons.CheckCircle,
                    title = if (lines.isEmpty()) "No build yet" else "No errors",
                    body = if (lines.isEmpty()) {
                        "Run a build to see parsed errors here, each linked to its file and line."
                    } else {
                        "The last build produced no compile errors."
                    },
                    accent = if (lines.isEmpty()) BerryColors.Edit else BerryColors.Success,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = BerrySpacing.lg,
                        end = BerrySpacing.lg,
                        bottom = BerrySpacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(BerrySpacing.xs),
                ) {
                    items(errors, key = { it.key }) { error ->
                        ErrorRow(error = error, onOpen = { onOpenFileAtLine(error.path, error.line) })
                    }
                }
            }

            BuildTab.Log -> if (filtered.isEmpty()) {
                BerryEmptyState(
                    icon = BerryIcons.Terminal,
                    title = "Nothing to show",
                    body = if (lines.isEmpty()) "Build output will stream here." else "No lines match your filter.",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().background(BerryColors.TermBackground),
                    contentPadding = PaddingValues(BerrySpacing.md),
                ) {
                    itemsIndexed(filtered) { _, line ->
                        LogRow(line)
                    }
                }
            }
        }
    }

    BerrySnackbar(
        message = viewModel.toastValue(),
        onDismiss = { viewModel.toast(null) },
        icon = BerryIcons.Info,
    )

    apkSheet?.let { apk ->
        ApkSheet(
            visible = true,
            apk = apk,
            onDismiss = { apkSheet = null },
            onInstall = { viewModel.installApk(apk); apkSheet = null },
            onShare = { viewModel.shareApk(apk); apkSheet = null },
        )
    }
}

private enum class BuildTab { Errors, Log }

@Composable
private fun SegmentedTab(
    label: String,
    count: Int,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
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
        Text(
            label,
            style = BerryType.Label,
            color = if (selected) accent else BerryColors.TextTertiary,
        )
        if (count > 0) {
            Spacer(Modifier.width(BerrySpacing.xs))
            Text("$count", style = BerryType.Micro, color = accent)
        }
    }
}

@Composable
private fun ErrorRow(error: BuildError, onOpen: () -> Unit) {
    BerrySurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = BerryColors.Surface2,
        border = BerryColors.Danger.copy(alpha = 0.25f),
        radius = BerryRadius.md,
        contentPadding = PaddingValues(BerrySpacing.md),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            BerryIcon(BerryIcons.Alert, null, size = BerrySize.iconSm, tint = BerryColors.Danger)
            Spacer(Modifier.width(BerrySpacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (error.path.isBlank()) "Build" else "${error.path}:${error.line}",
                        style = BerryType.CodeSmall,
                        color = BerryColors.Edit,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    BerryChip(error.kind.name.lowercase(), color = BerryColors.TextTertiary)
                }
                Spacer(Modifier.height(BerrySpacing.xxs))
                Text(error.message, style = BerryType.BodySmall, color = BerryColors.TextPrimary)
            }
            BerryIcon(BerryIcons.ArrowRight, null, size = 14.dp, tint = BerryColors.TextDisabled)
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val color = when (line.severity) {
        LogSeverity.Error -> BerryColors.Danger
        LogSeverity.Warn -> BerryColors.Warning
        LogSeverity.Success -> BerryColors.Success
        LogSeverity.Info -> BerryColors.TextSecondary
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        if (line.severity != LogSeverity.Info) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(5.dp)
                    .clip(RoundedCornerShape(BerryRadius.pill))
                    .background(color),
            )
            Spacer(Modifier.width(BerrySpacing.sm))
        } else {
            Spacer(Modifier.width(13.dp))
        }
        Text(
            line.text,
            style = BerryType.CodeMicro,
            color = color,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Post-build APK actions: install, share, or reveal the file. */
@Composable
private fun ApkSheet(
    visible: Boolean,
    apk: File,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onShare: () -> Unit,
) {
    BerrySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Build succeeded",
        subtitle = apk.name,
        icon = BerryIcons.CheckCircle,
        accent = BerryColors.Success,
    ) {
        Column(Modifier.padding(horizontal = BerrySpacing.xl)) {
            BerrySurface(
                color = BerryColors.Surface2,
                border = BerryColors.Outline,
                radius = BerryRadius.md,
                contentPadding = PaddingValues(BerrySpacing.md),
            ) {
                Column {
                    Text("APK produced", style = BerryType.Overline, color = BerryColors.TextTertiary)
                    Spacer(Modifier.height(BerrySpacing.xs))
                    Text(apk.name, style = BerryType.CodeSmall, color = BerryColors.TextPrimary)
                    Text(
                        "${formatBytes(apk.length())} · ${apk.absolutePath}",
                        style = BerryType.Micro,
                        color = BerryColors.TextDisabled,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(BerrySpacing.lg))
            BerryButton("Install", onClick = onInstall, icon = BerryIcons.Download, fillWidth = true)
            Spacer(Modifier.height(BerrySpacing.sm))
            BerryButton("Share", onClick = onShare, icon = BerryIcons.Share, variant = BerryButtonVariant.Secondary, fillWidth = true)
            Spacer(Modifier.height(BerrySpacing.lg))
        }
    }
}
