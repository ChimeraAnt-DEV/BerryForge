package dev.chimeraant.berryforge.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.chimeraant.berryforge.data.github.GhRepo
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.components.BerryChip
import dev.chimeraant.berryforge.ui.components.BerryDivider
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

/** Repository list with search, pull-to-refresh semantics and staggered row reveals. */
@Composable
fun ReposScreen(
    viewModel: BerryViewModel,
    onOpenRepo: (GhRepo) -> Unit,
    onProfileClick: () -> Unit,
    onManageAccounts: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val repos by viewModel.repos.collectAsStateWithLifecycle()
    val loading by viewModel.reposLoading.collectAsStateWithLifecycle()
    val error by viewModel.reposError.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val isOwner by viewModel.isOwner.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val filtered = remember(repos, query) {
        if (query.isBlank()) repos
        else repos.filter {
            it.name.contains(query, ignoreCase = true) ||
                (it.description?.contains(query, ignoreCase = true) == true)
        }
    }

    Column(modifier.fillMaxSize()) {
        BerryTopBar(
            title = "Repositories",
            subtitle = "${repos.size} accessible",
            viewModel = viewModel,
            onProfileClick = onProfileClick,
            onManageAccounts = onManageAccounts,
            actions = {
                BerryIconButton(
                    BerryIcons.Refresh,
                    "Refresh",
                    onClick = { viewModel.loadRepos(force = true) },
                )
            },
        )

        Box(Modifier.padding(horizontal = BerrySpacing.lg, vertical = BerrySpacing.md)) {
            BerrySearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Filter repositories",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (error != null) {
            Box(Modifier.padding(horizontal = BerrySpacing.lg)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(BerryRadius.md))
                        .background(BerryColors.Warning.copy(alpha = 0.12f))
                        .padding(BerrySpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BerryIcon(BerryIcons.Warning, null, size = BerrySize.iconSm, tint = BerryColors.Warning)
                    Spacer(Modifier.width(BerrySpacing.sm))
                    Text(error ?: "", style = BerryType.Caption, color = BerryColors.Warning)
                }
            }
            Spacer(Modifier.height(BerrySpacing.sm))
        }

        when {
            loading && repos.isEmpty() -> RepoListSkeleton()
            filtered.isEmpty() -> BerryEmptyState(
                icon = BerryIcons.Branch,
                title = if (query.isBlank()) "No repositories yet" else "No matches",
                body = if (query.isBlank()) {
                    "Repositories you can access will appear here."
                } else {
                    "Nothing matches \"$query\". Try a different filter."
                },
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = BerrySpacing.lg,
                    end = BerrySpacing.lg,
                    bottom = BerrySpacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
            ) {
                itemsIndexed(filtered, key = { _, repo -> repo.id }) { index, repo ->
                    androidx.compose.animation.AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(BerryMotion.Standard, delayMillis = (index % 12) * BerryMotion.Stagger)) +
                            slideInVertically(
                                initialOffsetY = { it / 4 },
                                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                            ),
                    ) {
                        RepoRow(repo = repo, onClick = { onOpenRepo(repo) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoRow(repo: GhRepo, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BerryRadius.lg))
            .background(BerryColors.Surface2)
            .clickable(onClick = onClick)
            .padding(BerrySpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(BerryRadius.md))
                .background(BerryColors.Edit.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            BerryIcon(
                if (repo.fork) BerryIcons.Branch else BerryIcons.Folder,
                null,
                size = BerrySize.icon,
                tint = BerryColors.Edit,
            )
        }
        Spacer(Modifier.width(BerrySpacing.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    repo.name,
                    style = BerryType.BodyStrong,
                    color = BerryColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(BerrySpacing.sm))
                if (repo.private) {
                    BerryChip("private", icon = BerryIcons.Lock, color = BerryColors.TextTertiary)
                }
                if (repo.archived) {
                    Spacer(Modifier.width(BerrySpacing.xs))
                    BerryChip("archived", color = BerryColors.Warning)
                }
            }
            if (!repo.description.isNullOrBlank()) {
                Text(
                    repo.description!!,
                    style = BerryType.Caption,
                    color = BerryColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.md)) {
                if (repo.language != null) {
                    MetaItem(BerryIcons.Code, repo.language!!)
                }
                if (repo.stars > 0) MetaItem(BerryIcons.Star, repo.stars.toString())
                MetaItem(BerryIcons.Branch, repo.defaultBranch)
            }
        }
        BerryIcon(BerryIcons.ChevronRight, null, size = BerrySize.iconSm, tint = BerryColors.TextTertiary)
    }
}

@Composable
private fun MetaItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BerryIcon(icon, null, size = 12.dp, tint = BerryColors.TextDisabled)
        Spacer(Modifier.width(3.dp))
        Text(text, style = BerryType.Micro, color = BerryColors.TextDisabled, maxLines = 1)
    }
}

@Composable
private fun RepoListSkeleton() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = BerrySpacing.lg),
        verticalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
    ) {
        repeat(6) {
            BerryShimmer(
                Modifier.fillMaxWidth().height(76.dp),
                radius = BerryRadius.lg,
            )
        }
    }
}
