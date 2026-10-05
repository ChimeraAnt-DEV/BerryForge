package dev.chimeraant.berryforge.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import dev.chimeraant.berryforge.ai.FindingCategory
import dev.chimeraant.berryforge.ai.ReviewFinding
import dev.chimeraant.berryforge.ai.ReviewResult
import dev.chimeraant.berryforge.ai.Severity
import dev.chimeraant.berryforge.editor.BerryCodeEditor
import dev.chimeraant.berryforge.editor.EditorConfig
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.components.BerryButton
import dev.chimeraant.berryforge.ui.components.BerryButtonSize
import dev.chimeraant.berryforge.ui.components.BerryButtonVariant
import dev.chimeraant.berryforge.ui.components.BerryChip
import dev.chimeraant.berryforge.ui.components.BerryDivider
import dev.chimeraant.berryforge.ui.components.BerryEmptyState
import dev.chimeraant.berryforge.ui.components.BerryIcon
import dev.chimeraant.berryforge.ui.components.BerryIconButton
import dev.chimeraant.berryforge.ui.components.BerrySheet
import dev.chimeraant.berryforge.ui.components.BerrySnackbar
import dev.chimeraant.berryforge.ui.components.BerrySurface
import dev.chimeraant.berryforge.ui.components.BerryTextField
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType
import kotlinx.coroutines.launch

/**
 * The code editor.
 *
 * Layout is a single column: a compact header, the Sora editor filling the rest, and a
 * floating action strip. Review findings appear both as a gutter tint and in a
 * dedicated sheet, and the AI accent (purple) is used for everything review-related so
 * the state colour contract holds.
 */
@Composable
fun EditorScreen(
    viewModel: BerryViewModel,
    path: String,
    onBack: () -> Unit,
    onOpenBuildLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo by viewModel.openRepo.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val repoValue = repo ?: return

    var text by remember(path) { mutableStateOf("") }
    var originalText by remember(path) { mutableStateOf("") }
    var persistedDirty by remember(path) { mutableStateOf(false) }
    var loading by remember(path) { mutableStateOf(true) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var fontSize by remember { mutableStateOf(13) }
    var wordWrap by remember { mutableStateOf(false) }
    var lineNumbers by remember { mutableStateOf(true) }
    var tabWidth by remember { mutableStateOf(4) }

    var cursorLine by remember { mutableStateOf(1) }
    var cursorColumn by remember { mutableStateOf(1) }

    var review by remember(path) { mutableStateOf<ReviewResult?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var reviewSheetOpen by remember { mutableStateOf(false) }
    var findSheetOpen by remember { mutableStateOf(false) }
    var commitSheetOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }

    // Load the file through the view model, which resolves mirror -> cache -> GitHub and
    // records the blob SHA at every step so a later commit has a base to update against.
    var reloadToken by remember { mutableStateOf(0) }
    LaunchedEffect(path, repoValue.fullName, reloadToken) {
        loading = true
        error = null
        val revalidate = reloadToken > 0
        runCatching {
            viewModel.loadFile(
                owner = repoValue.ownerLogin,
                name = repoValue.name,
                path = path,
                branch = repoValue.defaultBranch,
                revalidate = revalidate,
            )
        }.onSuccess { file ->
            text = file.text
            originalText = viewModel.workspace.originalOf(repoValue.ownerLogin, repoValue.name, path) ?: file.text
            persistedDirty = viewModel.isFileDirty(repoValue.ownerLogin, repoValue.name, path)
            loading = false
        }.onFailure { failure ->
            error = failure.message ?: "Could not open this file."
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.settings.editorFontSize.collect { fontSize = it }
    }
    LaunchedEffect(Unit) {
        viewModel.settings.editorWordWrap.collect { wordWrap = it }
    }
    LaunchedEffect(Unit) {
        viewModel.settings.editorLineNumbers.collect { lineNumbers = it }
    }
    LaunchedEffect(Unit) {
        viewModel.settings.tabWidth.collect { tabWidth = it }
    }

    // Dirty if the buffer differs from the baseline, or if the workspace still has
    // uncommitted state for this file (which survives a process restart).
    val dirty = text != originalText || persistedDirty

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
        EditorHeader(
            repoName = repoValue.fullName,
            path = path,
            dirty = dirty,
            cursorLine = cursorLine,
            cursorColumn = cursorColumn,
            findingCount = review?.findings?.size ?: 0,
            reviewing = reviewing,
            onBack = onBack,
            onFind = { findSheetOpen = true },
            onReview = {
                reviewSheetOpen = true
                if (review == null && !reviewing) {
                    reviewing = true
                    scope.launch {
                        val context = dev.chimeraant.berryforge.ai.RepoContext(
                            repo = repoValue.fullName,
                            branch = repoValue.defaultBranch,
                            siblingPaths = viewModel.tree.value?.tree?.take(120)?.map { it.path } ?: emptyList(),
                        )
                        viewModel.aiReview.review(path, text, context)
                            .onSuccess { review = it }
                            .onFailure { viewModel.toast(it.message ?: "Review failed.") }
                        reviewing = false
                    }
                }
            },
            onCommit = { commitSheetOpen = true },
            onSave = {
                scope.launch {
                    viewModel.workspace.writeLocal(repoValue.ownerLogin, repoValue.name, path, text)
                    persistedDirty = true
                    viewModel.toast("Saved to the local working copy.")
                }
            },
            onReload = { reloadToken++ },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Opening $path…", style = BerryType.Body, color = BerryColors.TextTertiary)
                }
                error != null -> BerryEmptyState(
                    icon = BerryIcons.Alert,
                    title = "Could not open this file",
                    body = error ?: "",
                    accent = BerryColors.Danger,
                    modifier = Modifier.fillMaxSize(),
                    action = { BerryButton("Retry", onClick = { viewModel.toast("Pull to reopen") }, variant = BerryButtonVariant.Secondary) },
                )
                else -> BerryCodeEditor(
                    config = EditorConfig(
                        path = path,
                        text = text,
                        fontSize = fontSize,
                        wordWrap = wordWrap,
                        lineNumbers = lineNumbers,
                        tabWidth = tabWidth,
                        gutterMarks = review?.findings.orEmpty().associate { it.startLine to it.severity.gutterColor() },
                    ),
                    modifier = Modifier.fillMaxSize(),
                    onContentChange = { text = it },
                    onSelectionChange = { line, column ->
                        cursorLine = line + 1
                        cursorColumn = column + 1
                    },
                )
            }

            // Severity spine for review findings, pinned to the right edge.
            if (!review?.findings.isNullOrEmpty()) {
                dev.chimeraant.berryforge.editor.FindingsSpine(
                    marks = review!!.findings.associate { it.startLine to it.severity.gutterColor() },
                    lineCount = text.lines().size,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(vertical = BerrySpacing.md)
                        .width(4.dp)
                        .fillMaxHeight(),
                    onClick = { reviewSheetOpen = true },
                )
                FindingsRail(
                    findings = review!!.findings,
                    modifier = Modifier.align(Alignment.TopEnd).padding(BerrySpacing.md),
                    onClick = { reviewSheetOpen = true },
                )
            }
        }

        EditorActionStrip(
            dirty = dirty,
            onUndo = { viewModel.toast("Undo") },
            onFormat = { viewModel.toast("Formatting") },
            onBuild = onOpenBuildLog,
        )
    }

        BerrySnackbar(
            message = viewModel.toastValue(),
            onDismiss = { viewModel.toast(null) },
            icon = BerryIcons.Info,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }

    ReviewSheet(
        visible = reviewSheetOpen,
        review = review,
        reviewing = reviewing,
        onDismiss = { reviewSheetOpen = false },
        onRerun = {
            reviewing = true
            scope.launch {
                val context = dev.chimeraant.berryforge.ai.RepoContext(
                    repo = repoValue.fullName,
                    branch = repoValue.defaultBranch,
                )
                viewModel.aiReview.review(path, text, context)
                    .onSuccess { review = it }
                    .onFailure { viewModel.toast(it.message ?: "Review failed.") }
                reviewing = false
            }
        },
    )

    FindReplaceSheet(
        visible = findSheetOpen,
        query = findQuery,
        replacement = replaceWith,
        onQueryChange = { findQuery = it },
        onReplacementChange = { replaceWith = it },
        onDismiss = { findSheetOpen = false },
        onReplaceAll = {
            if (findQuery.isNotBlank()) {
                text = text.replace(findQuery, replaceWith)
                viewModel.toast("Replaced every occurrence of \"$findQuery\".")
            }
            findSheetOpen = false
        },
    )

    CommitSheet(
        visible = commitSheetOpen,
        viewModel = viewModel,
        repoFullName = repoValue.fullName,
        owner = repoValue.ownerLogin,
        name = repoValue.name,
        defaultBranch = repoValue.defaultBranch,
        onDismiss = { commitSheetOpen = false },
        onCommitted = { branch ->
            viewModel.toast("Pushed to $branch.")
            originalText = text
            persistedDirty = false
        },
    )
}

@Composable
private fun EditorHeader(
    repoName: String,
    path: String,
    dirty: Boolean,
    cursorLine: Int,
    cursorColumn: Int,
    findingCount: Int,
    reviewing: Boolean,
    onBack: () -> Unit,
    onFind: () -> Unit,
    onReview: () -> Unit,
    onCommit: () -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(BerryColors.Surface1)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(BerrySize.topBar)
                .padding(horizontal = BerrySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BerryIconButton(BerryIcons.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(BerrySpacing.xs))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        path.substringAfterLast('/'),
                        style = BerryType.BodyStrong,
                        color = BerryColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dirty) {
                        Spacer(Modifier.width(BerrySpacing.sm))
                        BerryChip("unsaved", color = BerryColors.Warning, container = BerryColors.Warning.copy(alpha = 0.14f))
                    }
                }
                Text(
                    "$repoName · Ln $cursorLine, Col $cursorColumn",
                    style = BerryType.Micro,
                    color = BerryColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BerryIconButton(BerryIcons.Search, "Find and replace", onFind)
            BerryIconButton(
                BerryIcons.SparkleStroke,
                "AI review",
                onReview,
                tint = BerryColors.Ai,
                activeTint = BerryColors.Ai,
                active = findingCount > 0 || reviewing,
            )
            BerryIconButton(BerryIcons.Refresh, "Reload from GitHub", onReload)
            BerryIconButton(BerryIcons.Save, "Save", onSave, tint = if (dirty) BerryColors.Edit else BerryColors.TextTertiary)
            BerryIconButton(BerryIcons.PullRequest, "Commit and push", onCommit, tint = BerryColors.Edit)
        }
        BerryDivider(strong = true)
    }
}


@Composable
private fun FindingsRail(
    findings: List<ReviewFinding>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val errors = findings.count { it.severity == Severity.Error }
    val warnings = findings.count { it.severity == Severity.Warning }
    BerrySurface(
        modifier = modifier.clickable(onClick = onClick),
        color = BerryColors.Surface3.copy(alpha = 0.94f),
        border = BerryColors.Ai.copy(alpha = 0.4f),
        radius = BerryRadius.md,
        contentPadding = PaddingValues(horizontal = BerrySpacing.sm, vertical = BerrySpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BerryIcon(BerryIcons.Sparkle, null, size = 13.dp, tint = BerryColors.Ai)
            Spacer(Modifier.width(BerrySpacing.xs))
            Text("${findings.size}", style = BerryType.Micro, color = BerryColors.Ai)
            if (errors > 0) {
                Spacer(Modifier.width(BerrySpacing.xs))
                Text("$errors err", style = BerryType.Micro, color = BerryColors.Danger)
            }
            if (warnings > 0) {
                Spacer(Modifier.width(BerrySpacing.xs))
                Text("$warnings warn", style = BerryType.Micro, color = BerryColors.Warning)
            }
        }
    }
}

@Composable
private fun EditorActionStrip(
    dirty: Boolean,
    onUndo: () -> Unit,
    onFormat: () -> Unit,
    onBuild: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(BerryColors.Surface1)) {
        BerryDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BerryButton("Undo", onUndo, variant = BerryButtonVariant.Ghost, size = BerryButtonSize.Sm, icon = BerryIcons.History)
            BerryButton("Format", onFormat, variant = BerryButtonVariant.Ghost, size = BerryButtonSize.Sm, icon = BerryIcons.Wand)
            Spacer(Modifier.weight(1f))
            if (dirty) {
                BerryChip("local changes", color = BerryColors.Warning, icon = BerryIcons.Diff)
            }
            BerryButton("Build", onBuild, variant = BerryButtonVariant.Secondary, size = BerryButtonSize.Sm, icon = BerryIcons.Hammer)
        }
    }
}

@Composable
private fun FindReplaceSheet(
    visible: Boolean,
    query: String,
    replacement: String,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onReplaceAll: () -> Unit,
) {
    BerrySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Find and replace",
        subtitle = "Search the open file",
        icon = BerryIcons.Search,
        accent = BerryColors.Edit,
    ) {
        Column(Modifier.padding(horizontal = BerrySpacing.xl)) {
            BerryTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Find",
                icon = BerryIcons.Search,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(BerrySpacing.md))
            BerryTextField(
                value = replacement,
                onValueChange = onReplacementChange,
                placeholder = "Replace with",
                icon = BerryIcons.Pencil,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(BerrySpacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm)) {
                BerryButton("Replace all", onReplaceAll, fillWidth = true, modifier = Modifier.weight(1f))
                BerryButton("Close", onDismiss, variant = BerryButtonVariant.Ghost, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(BerrySpacing.lg))
        }
    }
}

@Composable
private fun ReviewSheet(
    visible: Boolean,
    review: ReviewResult?,
    reviewing: Boolean,
    onDismiss: () -> Unit,
    onRerun: () -> Unit,
) {
    BerrySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "AI review",
        subtitle = review?.let { "${it.findings.size} findings · ${it.model}" } ?: "Sending the file to your configured model",
        icon = BerryIcons.Sparkle,
        accent = BerryColors.Ai,
    ) {
        Column(Modifier.padding(horizontal = BerrySpacing.xl)) {
            if (reviewing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BerryIcon(BerryIcons.Sparkle, null, size = BerrySize.iconSm, tint = BerryColors.Ai)
                    Spacer(Modifier.width(BerrySpacing.sm))
                    Text("Reviewing…", style = BerryType.Body, color = BerryColors.TextSecondary)
                }
                Spacer(Modifier.height(BerrySpacing.lg))
            } else if (review == null) {
                BerryEmptyState(
                    icon = BerryIcons.SparkleStroke,
                    title = "No review yet",
                    body = "Run a review to get compile errors, crash risks, performance problems and dead code marked in the gutter.",
                    accent = BerryColors.Ai,
                )
            } else {
                if (review.summary.isNotBlank()) {
                    BerrySurface(
                        color = BerryColors.Ai.copy(alpha = 0.10f),
                        border = BerryColors.Ai.copy(alpha = 0.3f),
                        radius = BerryRadius.md,
                        contentPadding = PaddingValues(BerrySpacing.md),
                    ) {
                        Text(review.summary, style = BerryType.BodySmall, color = BerryColors.TextPrimary)
                    }
                    Spacer(Modifier.height(BerrySpacing.md))
                }
                if (review.findings.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BerryIcon(BerryIcons.CheckCircle, null, size = BerrySize.iconSm, tint = BerryColors.Success)
                        Spacer(Modifier.width(BerrySpacing.sm))
                        Text("No issues found in this file.", style = BerryType.Body, color = BerryColors.Success)
                    }
                } else {
                    LazyColumn(
                        Modifier.height(420.dp),
                        verticalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
                    ) {
                        items(review.findings) { finding ->
                            FindingCard(finding)
                        }
                    }
                }
            }
            Spacer(Modifier.height(BerrySpacing.lg))
            BerryButton(
                text = if (review == null) "Run review" else "Review again",
                onClick = onRerun,
                variant = BerryButtonVariant.Ai,
                icon = BerryIcons.Sparkle,
                fillWidth = true,
                enabled = !reviewing,
            )
            Spacer(Modifier.height(BerrySpacing.lg))
        }
    }
}

@Composable
private fun FindingCard(finding: ReviewFinding) {
    val accent = finding.severity.gutterColor()
    BerrySurface(
        color = BerryColors.Surface2,
        border = accent.copy(alpha = 0.3f),
        radius = BerryRadius.md,
        contentPadding = PaddingValues(BerrySpacing.md),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BerryIcon(finding.severity.icon(), null, size = 13.dp, tint = accent)
                Spacer(Modifier.width(BerrySpacing.xs))
                Text(
                    finding.category.label,
                    style = BerryType.Micro,
                    color = accent,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (finding.startLine == finding.endLine) "line ${finding.startLine}"
                    else "lines ${finding.startLine}–${finding.endLine}",
                    style = BerryType.Micro,
                    color = BerryColors.TextDisabled,
                )
            }
            Spacer(Modifier.height(BerrySpacing.xs))
            Text(finding.message, style = BerryType.BodySmall, color = BerryColors.TextPrimary)
            finding.suggestion?.let { suggestion ->
                Spacer(Modifier.height(BerrySpacing.xs))
                Text(
                    suggestion,
                    style = BerryType.CodeSmall,
                    color = BerryColors.Success,
                )
            }
        }
    }
}

internal fun Severity.gutterColor() = when (this) {
    Severity.Error -> BerryColors.Danger
    Severity.Warning -> BerryColors.Warning
    Severity.Perf -> BerryColors.SynFunction
    Severity.DeadCode -> BerryColors.TextTertiary
    Severity.Info -> BerryColors.Edit
}

internal fun Severity.icon() = when (this) {
    Severity.Error -> BerryIcons.Alert
    Severity.Warning -> BerryIcons.Warning
    Severity.Perf -> BerryIcons.Zap
    Severity.DeadCode -> BerryIcons.Trash
    Severity.Info -> BerryIcons.Info
}
