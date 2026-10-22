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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableIntStateOf
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
import dev.chimeraant.berryforge.ai.color
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
import dev.chimeraant.berryforge.ui.components.HtmlPreviewButton
import dev.chimeraant.berryforge.ui.components.HtmlPreviewSheet
import dev.chimeraant.berryforge.ui.components.MechaFloatingButton
import dev.chimeraant.berryforge.ui.components.MechaRole
import dev.chimeraant.berryforge.ui.components.MechaSheet
import dev.chimeraant.berryforge.ui.components.MechaTurn
import dev.chimeraant.berryforge.ai.mecha.FindingSelector
import dev.chimeraant.berryforge.ai.mecha.MechaBrain
import dev.chimeraant.berryforge.ai.mecha.MechaContext
import dev.chimeraant.berryforge.ai.mecha.MechaFixAction
import dev.chimeraant.berryforge.ai.mecha.MechaHandlers
import dev.chimeraant.berryforge.ai.mecha.MechaIntent
import dev.chimeraant.berryforge.ai.mecha.MechaReply
import dev.chimeraant.berryforge.ui.components.ModelFormats
import dev.chimeraant.berryforge.ui.components.ModelPreviewSheet
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryMotion
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType
import kotlinx.coroutines.flow.first
import dev.chimeraant.berryforge.core.launchSafely
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
    initialLine: Int? = null,
) {
    val repo by viewModel.openRepo.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val repoValue = repo ?: return

    var text by remember(path) { mutableStateOf("") }
    var originalText by remember(path) { mutableStateOf("") }
    var persistedDirty by remember(path) { mutableStateOf(false) }
    var loading by remember(path) { mutableStateOf(true) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var fontSize by remember { mutableIntStateOf(13) }
    var wordWrap by remember { mutableStateOf(false) }
    var lineNumbers by remember { mutableStateOf(true) }
    var tabWidth by remember { mutableIntStateOf(4) }

    var cursorLine by remember { mutableIntStateOf(1) }
    var cursorColumn by remember { mutableIntStateOf(1) }

    // The editor throttles its content emissions (a full buffer copy per keystroke was the
    // typing lag in large files), so `text` can be a few milliseconds stale. Everything
    // that must not lose a keystroke — save, commit, undo snapshots — reads the live
    // buffer through this instead.
    val liveText = remember(path) { dev.chimeraant.berryforge.editor.EditorTextBridge() }
    fun currentText(): String = liveText.current() ?: text

    var review by remember(path) { mutableStateOf<ReviewResult?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var reviewSheetOpen by remember { mutableStateOf(false) }
    var mechaSheetOpen by remember { mutableStateOf(false) }
    var mechaThinking by remember { mutableStateOf(false) }
    var mechaTurns by remember(path) { mutableStateOf(emptyList<MechaTurn>()) }
    // Conversation memory: which finding and symbol were last discussed, so "explain it",
    // "fix that one" and "usages of that" resolve without the user repeating themselves.
    var mechaLastFinding by remember(path) { mutableStateOf<Int?>(null) }
    var mechaLastSymbol by remember(path) { mutableStateOf<String?>(null) }
    // Snapshot of the buffer before the last applied fix, for Undo.
    var mechaUndoText by remember(path) { mutableStateOf<String?>(null) }
    var findSheetOpen by remember { mutableStateOf(false) }
    var commitSheetOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var previewVisible by remember { mutableStateOf(false) }
    var htmlPreviewSeen by remember { mutableStateOf(false) }
    val isHtml = path.endsWith(".html", ignoreCase = true) || path.endsWith(".htm", ignoreCase = true)
    val isModel = ModelFormats.isModelFormat(path)
    var modelViewMode by remember(path) { mutableStateOf(false) }

    // Load the file through the view model, which resolves mirror -> cache -> GitHub and
    // records the blob SHA at every step so a later commit has a base to update against.
    var reloadToken by remember { mutableIntStateOf(0) }
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
    LaunchedEffect(Unit) {
        // First time only: after the user has seen the explainer it stays dismissed
        // for every later session, but the preview control itself remains available.
        runCatching { htmlPreviewSeen = viewModel.settings.htmlPreviewSeen.first() }
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
                    // launchSafely + a finally: `reviewing` must be cleared on every path,
                    // otherwise a throw leaves the Review button spinning forever with no
                    // way back except reopening the file.
                    scope.launchSafely(onError = { viewModel.toast("Review failed: ${it.message ?: "unknown error"}") }) {
                        try {
                            val context = dev.chimeraant.berryforge.ai.RepoContext(
                                repo = repoValue.fullName,
                                branch = repoValue.defaultBranch,
                                siblingPaths = viewModel.tree.value?.tree?.take(120)?.map { it.path } ?: emptyList(),
                            )
                            viewModel.aiReview.review(path, currentText(), context)
                                .onSuccess { review = it }
                                .onFailure { viewModel.toast(it.message ?: "Review failed.") }
                        } finally {
                            reviewing = false
                        }
                    }
                }
            },
            onCommit = {
                // Flush the buffer before opening the sheet. CommitFlow and dirtyPaths read
                // the file back off disk, so edits still sitting only in the editor were
                // invisible to the commit: the sheet could report "No local changes" and
                // commit an older revision than the one on screen.
                //
                // The sheet is opened *after* the write returns, not alongside it. Opening it
                // concurrently would let its LaunchedEffect read the workspace while the
                // write was still in flight, which is the same bug in a narrower window.
                val snapshot = currentText()
                text = snapshot
                scope.launchSafely {
                    runCatching {
                        viewModel.workspace.writeLocal(repoValue.ownerLogin, repoValue.name, path, snapshot)
                        persistedDirty = true
                    }.onFailure { failure ->
                        viewModel.toast("Could not save before committing: ${failure.message ?: "unknown error"}")
                    }
                    commitSheetOpen = true
                }
            },
            onSave = {
                val snapshot = currentText()
                text = snapshot
                scope.launchSafely {
                    runCatching {
                        viewModel.workspace.writeLocal(repoValue.ownerLogin, repoValue.name, path, snapshot)
                    }.onSuccess {
                        persistedDirty = true
                        viewModel.toast("Saved to the local working copy.")
                    }.onFailure { failure ->
                        viewModel.toast("Could not save: ${failure.message ?: "unknown error"}")
                    }
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
                    action = {
                        BerryButton(
                            "Retry",
                            onClick = { reloadToken++ },
                            variant = BerryButtonVariant.Secondary,
                        )
                    },
                )
                isModel && modelViewMode -> ModelPreviewSheet(
                    path = path,
                    rawText = text,
                    modifier = Modifier.fillMaxSize(),
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
                        diagnostics = review?.findings.orEmpty().map { f ->
                            dev.chimeraant.berryforge.editor.EditorDiagnostic(
                                startLine = f.startLine,
                                endLine = f.endLine,
                                brief = f.message,
                                detail = f.suggestion ?: f.category.label,
                                isError = f.severity == dev.chimeraant.berryforge.ai.Severity.Error,
                            )
                        },
                    ),
                    modifier = Modifier.fillMaxSize(),
                    initialLine = initialLine,
                    onContentChange = { text = it },
                    onSelectionChange = { line, column ->
                        cursorLine = line + 1
                        cursorColumn = column + 1
                    },
                    textBridge = liveText,
                )
            }

            // Severity spine for review findings, pinned to the right edge.
            val findings = review?.findings
            if (!findings.isNullOrEmpty()) {
                dev.chimeraant.berryforge.editor.FindingsSpine(
                    marks = findings.associate { it.startLine to it.severity.gutterColor() },
                    lineCount = text.lines().size,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(vertical = BerrySpacing.md)
                        .width(4.dp)
                        .fillMaxHeight(),
                    onClick = { reviewSheetOpen = true },
                )
                FindingsRail(
                    findings = findings,
                    modifier = Modifier.align(Alignment.TopEnd).padding(BerrySpacing.md),
                    onClick = { reviewSheetOpen = true },
                )
            }
        }

        EditorActionStrip(
            dirty = dirty,
            isHtml = isHtml,
            isModel = isModel,
            isModelView = modelViewMode,
            onToggleModelView = { modelViewMode = !modelViewMode },
            previewHintShown = htmlPreviewSeen,
            onUndo = {
                // A real undo of the last buffer-changing action (a Mecha fix, a
                // replace-all, or a format). Previously this was a toast reading "Undo"
                // and did nothing at all.
                text = currentText()
                val previous = mechaUndoText
                if (previous == null) {
                    viewModel.toast("Nothing to undo yet.")
                } else {
                    text = previous
                    mechaUndoText = null
                    viewModel.toast("Reverted the last change.")
                }
            },
            onFormat = {
                // A real tidy, not a toast. The buffer is only replaced when the formatter
                // actually changed something, so a no-op does not mark the file dirty.
                val before = currentText()
                if (dev.chimeraant.berryforge.editor.CodeFormatter.isTidy(before, tabWidth)) {
                    viewModel.toast("Already tidy.")
                } else {
                    mechaUndoText = before
                    text = dev.chimeraant.berryforge.editor.CodeFormatter.tidy(before, tabWidth)
                    viewModel.toast("Formatted.")
                }
            },
            onBuild = onOpenBuildLog,
            onPreview = {
                if (!htmlPreviewSeen) {
                    htmlPreviewSeen = true
                    scope.launchSafely { viewModel.settings.setHtmlPreviewSeen(true) }
                }
                previewVisible = true
            },
        )
    }

        BerrySnackbar(
            message = viewModel.toastValue(),
            onDismiss = { viewModel.toast(null) },
            icon = BerryIcons.Info,
            modifier = Modifier.align(Alignment.TopEnd),
        )

        // Mecha — the animated assistant. Floats just above the action strip.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = BerrySpacing.lg, bottom = 88.dp),
        ) {
            MechaFloatingButton(
                expanded = mechaSheetOpen,
                thinking = mechaThinking,
                onToggle = { mechaSheetOpen = !mechaSheetOpen },
            )
        }
    }

    ReviewSheet(
        visible = reviewSheetOpen,
        review = review,
        reviewing = reviewing,
        onDismiss = { reviewSheetOpen = false },
        onRerun = {
            reviewing = true
            scope.launchSafely(onError = { viewModel.toast("Review failed: ${it.message ?: "unknown error"}") }) {
                try {
                    val context = dev.chimeraant.berryforge.ai.RepoContext(
                        repo = repoValue.fullName,
                        branch = repoValue.defaultBranch,
                    )
                    viewModel.aiReview.review(path, currentText(), context)
                        .onSuccess { review = it }
                        .onFailure { viewModel.toast(it.message ?: "Review failed.") }
                } finally {
                    reviewing = false
                }
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
            val before = currentText()
            text = before
            if (findQuery.isNotBlank() && before.contains(findQuery)) {
                // Snapshot first so the strip's Undo can put the buffer back: a
                // replace-all across a large file is the easiest thing to trigger by
                // accident and the hardest to reverse by hand.
                mechaUndoText = before
                text = before.replace(findQuery, replaceWith)
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
            originalText = currentText()
            persistedDirty = false
        },
    )

    // Live HTML preview floats above everything so it never fights the sheet z-order.
    if (path.endsWith(".html", ignoreCase = true) || path.endsWith(".htm", ignoreCase = true)) {
        HtmlPreviewSheet(
            visible = previewVisible,
            htmlSource = text,
            onDismiss = { previewVisible = false },
        )
    }

    // Mecha — a deterministic, instant assistant that only answers from this file's real
    // contents. Unknown queries optionally fall back to the configured model.
    fun mechaContext(): MechaContext = MechaContext(
        path = path,
        text = currentText(),
        originalText = originalText,
        cursorLine = cursorLine,
        findings = review?.findings.orEmpty(),
        lastFindingIndex = mechaLastFinding,
        lastSymbol = mechaLastSymbol,
    )

    fun applyFixActions(actions: List<MechaFixAction>) {
        if (actions.isEmpty()) return
        runCatching {
            val before = currentText()
            mechaUndoText = before
            val lines = before.split('\n').toMutableList()
            // Apply bottom-up so an earlier edit never shifts a later line number.
            actions.sortedByDescending { it.startLine }.forEach { action ->
                if (lines.isEmpty()) return@forEach
                val start = (action.startLine - 1).coerceIn(0, lines.lastIndex)
                val end = (action.endLine - 1).coerceIn(start, lines.lastIndex)
                lines[start] = action.replacement
                repeat(end - start) { if (start + 1 < lines.size) lines.removeAt(start + 1) }
            }
            text = lines.joinToString("\n")
            viewModel.toast("Mecha applied ${actions.size} fix${if (actions.size == 1) "" else "es"}.")
        }.onFailure { viewModel.toast("Mecha couldn't apply that fix cleanly — please edit it yourself.") }
    }

    fun rememberIntent(intent: MechaIntent, ctx: MechaContext) {
        val count = ctx.findings.size.coerceAtLeast(1)
        when (intent) {
            is MechaIntent.ExplainFindings -> intent.selector.index?.let { mechaLastFinding = it }
            is MechaIntent.FixFindings -> intent.selector.index?.let { mechaLastFinding = it }
            is MechaIntent.FindSymbol -> mechaLastSymbol = intent.name
            is MechaIntent.FindUsages -> mechaLastSymbol = intent.name
            MechaIntent.NextFinding ->
                mechaLastFinding = ((mechaLastFinding ?: 0) + 1).coerceAtMost(count)
            MechaIntent.PreviousFinding ->
                mechaLastFinding = ((mechaLastFinding ?: 2) - 1).coerceAtLeast(1)
            else -> Unit
        }
    }

    fun runMecha(input: String) {
        val ctx = mechaContext()
        mechaTurns = mechaTurns + MechaTurn(MechaRole.User, input)
        when (val intent = MechaBrain.classify(input, ctx)) {
            is MechaIntent.Unknown -> {
                // The key check reads encrypted prefs, so it happens off the main thread
                // along with the request itself — the composer never blocks on I/O.
                mechaThinking = true
                val history = mechaTurns.map {
                    (if (it.role == MechaRole.User) "user" else "assistant") to it.text
                }
                scope.launchSafely {
                    // The whole fallback runs inside runCatching, including the `hasKey()`
                    // read: it touches the Keystore, which can throw on an invalidated key.
                    // Without this an exception here escaped the coroutine, cancelled the
                    // scope and left the composer stuck in the thinking state with no reply
                    // — or took the activity down through the uncaught handler.
                    val reply = runCatching {
                        val hasKey = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            viewModel.aiReview.hasKey()
                        }
                        if (hasKey) {
                            viewModel.aiReview.chat(history, text).getOrElse { failure ->
                                MechaReply.unknown(input).text +
                                    "\n\n(Offline answer — the model could not be reached: ${failure.message})"
                            }
                        } else {
                            MechaReply.unknown(input).text
                        }
                    }.getOrElse { failure ->
                        MechaReply.unknown(input).text +
                            "\n\n(Offline answer — ${failure.message ?: "the assistant could not be reached"})"
                    }
                    mechaTurns = mechaTurns + MechaTurn(MechaRole.Bot, reply)
                    mechaThinking = false
                }
            }
            MechaIntent.Clear -> mechaTurns = listOf(MechaTurn(MechaRole.Bot, MechaHandlers.greetingText(ctx)))
            MechaIntent.Undo -> {
                val previous = mechaUndoText
                if (previous != null) {
                    text = previous
                    mechaUndoText = null
                    mechaTurns = mechaTurns + MechaTurn(MechaRole.Bot, "Reverted to the previous version.")
                } else {
                    mechaTurns = mechaTurns + MechaTurn(MechaRole.Bot, "Nothing to undo yet.")
                }
            }
            is MechaIntent.GotoLine -> {
                cursorLine = intent.line.coerceAtLeast(1)
                mechaTurns = mechaTurns + MechaTurn(MechaRole.Bot, "Line ${intent.line} is in view now.")
            }
            is MechaIntent.Search -> {
                findQuery = intent.query
                mechaTurns = mechaTurns + MechaTurn(MechaRole.Bot, "Loaded \"${intent.query}\" into Find.")
                findSheetOpen = true
            }
            else -> {
                val reply = MechaHandlers.execute(intent, ctx)
                mechaTurns = mechaTurns + MechaTurn(MechaRole.Bot, reply.text)
                rememberIntent(intent, ctx)
                applyFixActions(reply.actions)
            }
        }
    }

    MechaSheet(
        visible = mechaSheetOpen,
        findings = review?.findings.orEmpty(),
        thinking = mechaThinking,
        turns = mechaTurns,
        onDismiss = { mechaSheetOpen = false },
        onFix = { finding ->
            val ctx = mechaContext()
            val index = ctx.findings.indexOf(finding).takeIf { it >= 0 }?.plus(1)
            val selector = FindingSelector(index = index)
            val reply = MechaHandlers.execute(MechaIntent.FixFindings(selector), ctx)
            mechaTurns = mechaTurns + MechaTurn(MechaRole.Bot, reply.text)
            index?.let { mechaLastFinding = it }
            applyFixActions(reply.actions)
        },
        onExplain = { finding ->
            val ctx = mechaContext()
            val index = ctx.findings.indexOf(finding).takeIf { it >= 0 }?.plus(1)
            val reply = MechaHandlers.execute(MechaIntent.ExplainFindings(FindingSelector(index = index)), ctx)
            mechaTurns = mechaTurns + MechaTurn(MechaRole.Bot, reply.text)
            index?.let { mechaLastFinding = it }
        },
        onSend = { runMecha(it) },
        onUndo = {
            val previous = mechaUndoText
            if (previous != null) {
                text = previous
                mechaUndoText = null
                mechaTurns = mechaTurns + MechaTurn(MechaRole.Bot, "Reverted to the previous version.")
            } else {
                mechaTurns = mechaTurns + MechaTurn(MechaRole.Bot, "Nothing to undo yet.")
            }
        },
    )

    // Greet with real state the first time the sheet opens for this file.
    LaunchedEffect(mechaSheetOpen, path) {
        if (mechaSheetOpen && mechaTurns.isEmpty()) {
            mechaTurns = listOf(MechaTurn(MechaRole.Bot, MechaHandlers.greetingText(mechaContext())))
        }
    }
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
    isHtml: Boolean,
    isModel: Boolean,
    isModelView: Boolean,
    onToggleModelView: () -> Unit,
    previewHintShown: Boolean,
    onUndo: () -> Unit,
    onFormat: () -> Unit,
    onBuild: () -> Unit,
    onPreview: () -> Unit = {},
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
            if (isModel) {
                BerryButton(
                    if (isModelView) "Code view" else "3D view",
                    onToggleModelView,
                    variant = if (isModelView) BerryButtonVariant.Secondary else BerryButtonVariant.Ghost,
                    size = BerryButtonSize.Sm,
                    icon = BerryIcons.Eye,
                )
            }
            Spacer(Modifier.weight(1f))
            if (isHtml) {
                // Animated blue→cyan preview control. A small Beta badge sits above it
                // the first time, then hides so the strip stays calm on later edits.
                HtmlPreviewCta(
                    showBeta = !previewHintShown,
                    onClick = onPreview,
                )
                Spacer(Modifier.width(BerrySpacing.xs))
            }
            if (dirty) {
                BerryChip("local changes", color = BerryColors.Warning, icon = BerryIcons.Diff)
            }
            BerryButton("Build", onBuild, variant = BerryButtonVariant.Secondary, size = BerryButtonSize.Sm, icon = BerryIcons.Hammer)
        }
        if (isHtml && !previewHintShown) {
            Text(
                "Live preview: tap the button to see your page update as you type, no save needed.",
                style = BerryType.Caption,
                color = BerryColors.TextTertiary,
                modifier = Modifier.padding(horizontal = BerrySpacing.md).padding(bottom = BerrySpacing.sm),
            )
        }
    }
}

/** The animated preview button with a one-time "BETA" badge floating above it. */
@Composable
private fun HtmlPreviewCta(
    showBeta: Boolean,
    onClick: () -> Unit,
) {
    Box {
        HtmlPreviewButton(onClick = onClick)
        if (showBeta) {
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(BerryMotion.Fast)) +
                    slideInVertically(tween(BerryMotion.Fast)) { it / 2 },
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-10).dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(BerryRadius.sm))
                        .background(BerryColors.Warning.copy(alpha = 0.92f))
                        .padding(horizontal = BerrySpacing.xs, vertical = BerrySpacing.xxs),
                ) {
                    Text("BETA", style = BerryType.Overline, color = BerryColors.Base)
                }
            }
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

internal fun Severity.gutterColor() = this.color()

internal fun Severity.icon() = when (this) {
    Severity.Error -> BerryIcons.Alert
    Severity.Warning -> BerryIcons.Warning
    Severity.Perf -> BerryIcons.Zap
    Severity.DeadCode -> BerryIcons.Trash
    Severity.Info -> BerryIcons.Info
}
