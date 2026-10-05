package dev.chimeraant.berryforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.components.BerryButton
import dev.chimeraant.berryforge.ui.components.BerryButtonSize
import dev.chimeraant.berryforge.ui.components.BerryButtonVariant
import dev.chimeraant.berryforge.ui.components.BerryChip
import dev.chimeraant.berryforge.ui.components.BerryIcon
import dev.chimeraant.berryforge.ui.components.BerrySheet
import dev.chimeraant.berryforge.ui.components.BerrySwitch
import dev.chimeraant.berryforge.ui.components.BerryTextField
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType
import dev.chimeraant.berryforge.data.diff.DiffEngine
import kotlinx.coroutines.launch

/**
 * Stage → commit → push → open PR, all from one sheet.
 *
 * The sheet shows the real diff of what is about to be pushed, offers an AI-generated
 * subject line, and makes the branch/PR decision explicit so nothing lands on the
 * default branch by accident.
 */
@Composable
fun CommitSheet(
    visible: Boolean,
    viewModel: BerryViewModel,
    repoFullName: String,
    owner: String,
    name: String,
    defaultBranch: String,
    onDismiss: () -> Unit,
    onCommitted: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var message by remember(visible) { mutableStateOf("") }
    var branch by remember(visible) { mutableStateOf("") }
    var openPr by remember(visible) { mutableStateOf(true) }
    var prTitle by remember(visible) { mutableStateOf("") }
    var working by remember(visible) { mutableStateOf(false) }
    var generating by remember(visible) { mutableStateOf(false) }
    var diff by remember(visible) { mutableStateOf("") }
    var stagedPaths by remember(visible) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        branch = "berryforge/${System.currentTimeMillis() / 1000 % 100000}"
        stagedPaths = viewModel.workspace.dirtyPaths(owner, name)
        diff = viewModel.commitFlow.stagedDiff(owner, name, stagedPaths)
    }

    BerrySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Commit and push",
        subtitle = repoFullName,
        icon = BerryIcons.PullRequest,
        accent = BerryColors.Edit,
    ) {
        Column(
            Modifier
                .padding(horizontal = BerrySpacing.xl)
                .verticalScroll(rememberScrollState()),
        ) {
            // ---- What is staged ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Staged changes", style = BerryType.Overline, color = BerryColors.TextTertiary)
                Spacer(Modifier.weight(1f))
                BerryChip(
                    "${stagedPaths.size} file${if (stagedPaths.size == 1) "" else "s"}",
                    color = if (stagedPaths.isEmpty()) BerryColors.TextTertiary else BerryColors.Edit,
                )
            }
            Spacer(Modifier.height(BerrySpacing.sm))
            if (stagedPaths.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BerryIcon(BerryIcons.Info, null, size = BerrySize.iconSm, tint = BerryColors.TextTertiary)
                    Spacer(Modifier.width(BerrySpacing.sm))
                    Text("No local changes. Edit a file first.", style = BerryType.BodySmall, color = BerryColors.TextTertiary)
                }
            } else {
                stagedPaths.take(8).forEach { path ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BerryIcon(BerryIcons.Diff, null, size = 12.dp, tint = BerryColors.Warning)
                        Spacer(Modifier.width(BerrySpacing.sm))
                        Text(
                            path,
                            style = BerryType.CodeSmall,
                            color = BerryColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (stagedPaths.size > 8) {
                    Text("+${stagedPaths.size - 8} more", style = BerryType.Micro, color = BerryColors.TextDisabled)
                }
            }

            Spacer(Modifier.height(BerrySpacing.lg))

            // ---- Diff preview ----
            if (diff.isNotBlank()) {
                Text("Diff preview", style = BerryType.Overline, color = BerryColors.TextTertiary)
                Spacer(Modifier.height(BerrySpacing.sm))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(BerryRadius.md))
                        .background(BerryColors.Base)
                        .verticalScroll(rememberScrollState())
                        .padding(BerrySpacing.sm),
                ) {
                    DiffText(diff)
                }
                Spacer(Modifier.height(BerrySpacing.lg))
            }

            // ---- Message ----
            BerryTextField(
                value = message,
                onValueChange = { message = it },
                label = "Commit message",
                placeholder = "Describe the change",
                singleLine = false,
                minHeight = 72.dp,
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    BerryButton(
                        text = if (generating) "…" else "AI",
                        onClick = {
                            generating = true
                            scope.launch {
                                viewModel.aiReview.suggestCommitMessage(repoFullName, stagedPaths, diff)
                                    .onSuccess { suggestion ->
                                        if (suggestion.isNotBlank()) {
                                            message = suggestion
                                            if (prTitle.isBlank()) prTitle = suggestion
                                        }
                                    }
                                    .onFailure { viewModel.toast(it.message ?: "Could not generate a message.") }
                                generating = false
                            }
                        },
                        variant = BerryButtonVariant.Ai,
                        size = BerryButtonSize.Sm,
                        icon = BerryIcons.Sparkle,
                        enabled = !generating && stagedPaths.isNotEmpty(),
                    )
                },
            )

            Spacer(Modifier.height(BerrySpacing.md))

            BerryTextField(
                value = branch,
                onValueChange = { branch = it },
                label = "Branch",
                placeholder = defaultBranch,
                icon = BerryIcons.Branch,
                modifier = Modifier.fillMaxWidth(),
                helper = "A new branch is created from $defaultBranch if it does not exist.",
            )

            Spacer(Modifier.height(BerrySpacing.md))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Open a pull request", style = BerryType.BodyStrong, color = BerryColors.TextPrimary)
                    Text(
                        "Targets $defaultBranch",
                        style = BerryType.Caption,
                        color = BerryColors.TextTertiary,
                    )
                }
                BerrySwitch(checked = openPr, onCheckedChange = { openPr = it })
            }

            if (openPr) {
                Spacer(Modifier.height(BerrySpacing.md))
                BerryTextField(
                    value = prTitle,
                    onValueChange = { prTitle = it },
                    label = "Pull request title",
                    placeholder = message.ifBlank { "Title" },
                    icon = BerryIcons.PullRequest,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(BerrySpacing.xl))

            Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm)) {
                BerryButton(
                    text = if (working) "Pushing…" else "Commit and push",
                    onClick = {
                        if (message.isBlank()) {
                            viewModel.toast("Add a commit message first.")
                            return@BerryButton
                        }
                        working = true
                        scope.launch {
                            val outcome = viewModel.commitFlow.commit(
                                owner = owner,
                                name = name,
                                message = message,
                                branch = branch,
                                paths = stagedPaths,
                                openPr = openPr,
                                prTitle = prTitle.ifBlank { message },
                                prBody = "",
                                baseBranch = defaultBranch,
                            )
                            working = false
                            if (outcome.pushed || outcome.files.isNotEmpty()) {
                                onCommitted(outcome.branch)
                                outcome.note?.let { viewModel.toast(it) }
                                onDismiss()
                            } else {
                                viewModel.toast(outcome.note ?: "Nothing was pushed.")
                            }
                        }
                    },
                    icon = BerryIcons.Upload,
                    fillWidth = true,
                    enabled = !working && stagedPaths.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
                BerryButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    variant = BerryButtonVariant.Ghost,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(BerrySpacing.xl))
        }
    }
}

/** Renders a unified diff with add/remove colouring. */
@Composable
private fun DiffText(diff: String) {
    Column {
        diff.lines().forEach { line ->
            val color = when {
                line.startsWith("+++") || line.startsWith("---") -> BerryColors.TextTertiary
                line.startsWith("@@") -> BerryColors.Ai
                line.startsWith("+") -> BerryColors.DiffAddStrong
                line.startsWith("-") -> BerryColors.DiffRemoveStrong
                else -> BerryColors.TextSecondary
            }
            val background = when {
                line.startsWith("+") && !line.startsWith("+++") -> BerryColors.DiffAdd
                line.startsWith("-") && !line.startsWith("---") -> BerryColors.DiffRemove
                else -> androidx.compose.ui.graphics.Color.Transparent
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(background)
                    .padding(horizontal = 2.dp),
            ) {
                Text(line.ifBlank { " " }, style = BerryType.CodeMicro, color = color, maxLines = 1)
            }
        }
    }
}
