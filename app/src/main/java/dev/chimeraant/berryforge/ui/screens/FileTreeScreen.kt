package dev.chimeraant.berryforge.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chimeraant.berryforge.data.github.GhTreeEntry
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.components.BerryChip
import dev.chimeraant.berryforge.ui.components.BerryEmptyState
import dev.chimeraant.berryforge.ui.components.BerryIcon
import dev.chimeraant.berryforge.ui.components.BerryIconButton
import dev.chimeraant.berryforge.ui.components.BerrySearchField
import dev.chimeraant.berryforge.ui.components.BerryShimmer
import dev.chimeraant.berryforge.ui.components.BerryTopBar
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryMotion
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType

/**
 * File tree browser for one repository.
 *
 * Navigation is a breadcrumb path rather than a stack of screens, which keeps the
 * transition instant and lets any ancestor be reached in one tap. Rows animate in with
 * a short stagger so a large directory does not appear all at once.
 */
@Composable
fun FileTreeScreen(
    viewModel: BerryViewModel,
    onOpenFile: (String) -> Unit,
    onBack: () -> Unit,
    onProfileClick: () -> Unit,
    onManageAccounts: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val repo by viewModel.openRepo.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val isOwner by viewModel.isOwner.collectAsStateWithLifecycle()
    val loading by viewModel.treeLoading.collectAsStateWithLifecycle()
    var path by remember(repo?.fullName) { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var dirtyPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    val repoValue = repo ?: return
    // Refresh the dirty set whenever the tree or the open folder changes, off the main
    // thread: the workspace persists its state to disk, so this is a suspend call.
    LaunchedEffect(repoValue.fullName, path, viewModel.tree.value) {
        dirtyPaths = viewModel.workspace.dirtyPaths(repoValue.ownerLogin, repoValue.name).toSet()
    }

    val children = remember(repoValue.fullName, path, viewModel.tree.value, query) {
        val base = viewModel.childrenOf(path)
        if (query.isBlank()) base
        else base.filter { it.name.contains(query, ignoreCase = true) }
    }

    Column(modifier.fillMaxSize().background(BerryColors.Base)) {
        BerryTopBar(
            title = repoValue.name,
            subtitle = "${repoValue.ownerLogin} · ${repoValue.defaultBranch}",
            viewModel = viewModel,
            onProfileClick = onProfileClick,
            onManageAccounts = onManageAccounts,
            leading = {
                BerryIconButton(BerryIcons.ChevronLeft, "Back", onBack)
            },
            actions = {
                BerryIconButton(
                    BerryIcons.Refresh,
                    "Refresh tree",
                    onClick = { viewModel.loadTree(repoValue, repoValue.defaultBranch) },
                )
            },
        )

        Breadcrumbs(
            path = path,
            repoName = repoValue.name,
            onNavigate = { path = it },
        )

        Box(Modifier.padding(horizontal = BerrySpacing.lg, vertical = BerrySpacing.sm)) {
            BerrySearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Filter this folder",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        when {
            loading && children.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(horizontal = BerrySpacing.lg),
                verticalArrangement = Arrangement.spacedBy(BerrySpacing.xs),
            ) {
                repeat(8) { BerryShimmer(Modifier.fillMaxWidth().height(44.dp)) }
            }
            children.isEmpty() -> BerryEmptyState(
                icon = BerryIcons.FolderOpen,
                title = if (query.isBlank()) "Empty folder" else "No matches",
                body = if (query.isBlank()) "Nothing in this directory." else "Nothing matches \"$query\".",
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = BerrySpacing.lg,
                    end = BerrySpacing.lg,
                    bottom = BerrySpacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(children, key = { _, entry -> entry.path }) { index, entry ->
                    androidx.compose.animation.AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(BerryMotion.Fast, delayMillis = (index % 14) * BerryMotion.Stagger)) +
                            slideInVertically(
                                initialOffsetY = { it / 6 },
                                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                            ),
                    ) {
                        TreeRow(
                            entry = entry,
                            dirty = entry.path in dirtyPaths,
                            onClick = {
                                if (entry.isDir) {
                                    path = entry.path
                                } else {
                                    onOpenFile(entry.path)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(
    path: String,
    repoName: String,
    onNavigate: (String) -> Unit,
) {
    val segments = remember(path) { path.split('/').filter { it.isNotBlank() } }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = BerrySpacing.lg, vertical = BerrySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(text = repoName, root = true, onClick = { onNavigate("") })
        segments.forEachIndexed { index, segment ->
            BerryIcon(
                BerryIcons.ChevronRight,
                null,
                size = 12.dp,
                tint = BerryColors.TextDisabled,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            val target = segments.take(index + 1).joinToString("/")
            Crumb(
                text = segment,
                root = false,
                onClick = { onNavigate(target) },
            )
        }
    }
}

@Composable
private fun Crumb(text: String, root: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(BerryRadius.sm))
            .background(if (root) BerryColors.Edit.copy(alpha = 0.12f) else BerryColors.Surface3)
            .clickable(onClick = onClick)
            .padding(horizontal = BerrySpacing.sm, vertical = 4.dp),
    ) {
        Text(
            text,
            style = BerryType.Micro,
            color = if (root) BerryColors.Edit else BerryColors.TextSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun TreeRow(entry: GhTreeEntry, dirty: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BerryRadius.md))
            .background(BerryColors.Surface2.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val accent = when {
            entry.isDir -> BerryColors.Warning
            else -> fileAccent(entry.name)
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(BerryRadius.xs))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            BerryIcon(
                if (entry.isDir) BerryIcons.Folder else BerryIcons.FileCode,
                null,
                size = 15.dp,
                tint = accent,
            )
        }
        Spacer(Modifier.width(BerrySpacing.md))
        Text(
            entry.name,
            style = BerryType.Body,
            color = BerryColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (dirty) {
            BerryChip("edited", color = BerryColors.Warning, container = BerryColors.Warning.copy(alpha = 0.14f))
            Spacer(Modifier.width(BerrySpacing.sm))
        }
        if (entry.isFile && entry.size != null && entry.size!! > 0) {
            Text(humanSize(entry.size!!), style = BerryType.Micro, color = BerryColors.TextDisabled)
            Spacer(Modifier.width(BerrySpacing.sm))
        }
        BerryIcon(
            if (entry.isDir) BerryIcons.ChevronRight else BerryIcons.ArrowRight,
            null,
            size = 14.dp,
            tint = BerryColors.TextDisabled,
        )
    }
}

/** Colour-codes the file glyph by language so a directory reads at a glance. */
internal fun fileAccent(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> BerryColors.Ai
    "java" -> BerryColors.Edit
    "cpp", "cc", "h", "hpp" -> BerryColors.Success
    "gradle", "pro" -> BerryColors.Warning
    "json", "yml", "yaml" -> BerryColors.SynFunction
    "md" -> BerryColors.TextSecondary
    "xml" -> BerryColors.Danger
    else -> BerryColors.TextTertiary
}

internal fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
