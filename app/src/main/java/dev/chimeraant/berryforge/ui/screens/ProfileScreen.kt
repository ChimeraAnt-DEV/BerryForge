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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chimeraant.berryforge.data.github.GhContributionDay
import dev.chimeraant.berryforge.data.github.GhRepo
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.components.Avatar
import dev.chimeraant.berryforge.ui.components.BerryButton
import dev.chimeraant.berryforge.ui.components.BerryButtonSize
import dev.chimeraant.berryforge.ui.components.BerryButtonVariant
import dev.chimeraant.berryforge.ui.components.BerryChip
import dev.chimeraant.berryforge.ui.components.BerryDivider
import dev.chimeraant.berryforge.ui.components.BerryIcon
import dev.chimeraant.berryforge.ui.components.BerryIconButton
import dev.chimeraant.berryforge.ui.components.BerrySectionHeader
import dev.chimeraant.berryforge.ui.components.BerrySurface
import dev.chimeraant.berryforge.ui.components.OwnerBadge
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Profile.
 *
 * Fetches the signed-in user's avatar and username, shows their org memberships, a
 * contribution graph, and their repositories with quick-open.
 *
 * The owner badge is derived client-side from GET /user/orgs. It is a cosmetic
 * affordance and the UI says so — nothing in the app treats it as authorisation.
 */
@Composable
fun ProfileScreen(
    viewModel: BerryViewModel,
    onOpenRepo: (GhRepo) -> Unit,
    onOpenSettings: () -> Unit,
    onManageAccounts: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val isOwner by viewModel.isOwner.collectAsStateWithLifecycle()
    val orgs by viewModel.orgs.collectAsStateWithLifecycle()
    val repos by viewModel.repos.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var contributions by remember { mutableStateOf<List<GhContributionDay>>(emptyList()) }
    var loadingContributions by remember { mutableStateOf(false) }

    LaunchedEffect(user?.login) {
        val login = user?.login ?: return@LaunchedEffect
        loadingContributions = true
        runCatching { viewModel.api.contributionActivity(login) }
            .onSuccess { contributions = it }
        loadingContributions = false
    }

    val myRepos = remember(repos, user) {
        repos.filter { it.ownerLogin.equals(user?.login, ignoreCase = true) }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(BerryColors.Surface1)
                .statusBarsPadding()
                .padding(horizontal = BerrySpacing.sm, vertical = BerrySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BerryIconButton(BerryIcons.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(BerrySpacing.sm))
            Text("Profile", style = BerryType.Headline, color = BerryColors.TextPrimary, modifier = Modifier.weight(1f))
            BerryIconButton(BerryIcons.Settings, "Settings", onOpenSettings)
        }
        BerryDivider(strong = true)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // ---- Identity ----
            Row(
                Modifier.fillMaxWidth().padding(BerrySpacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(user = user, size = BerrySize.avatarLg, isOwner = isOwner)
                Spacer(Modifier.width(BerrySpacing.lg))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            user?.displayName ?: "Not signed in",
                            style = BerryType.Title,
                            color = BerryColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isOwner) {
                            Spacer(Modifier.width(BerrySpacing.sm))
                            OwnerBadge()
                        }
                    }
                    Text("@${user?.login ?: "—"}", style = BerryType.Body, color = BerryColors.TextSecondary)
                    user?.bio?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(BerrySpacing.xs))
                        Text(it, style = BerryType.BodySmall, color = BerryColors.TextTertiary, maxLines = 3)
                    }
                }
            }

            if (isOwner) {
                OwnerNotice()
                Spacer(Modifier.height(BerrySpacing.lg))
            }

            // ---- Stats ----
            user?.let { current ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = BerrySpacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
                ) {
                    StatCard("Repos", current.public_repos.toString(), Modifier.weight(1f))
                    StatCard("Followers", current.followers.toString(), Modifier.weight(1f))
                    StatCard("Following", current.following.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(BerrySpacing.xl))
            }

            // ---- Orgs ----
            if (orgs.isNotEmpty()) {
                Column(Modifier.padding(horizontal = BerrySpacing.lg)) {
                    BerrySectionHeader("Organisations", trailing = "${orgs.size}")
                    Spacer(Modifier.height(BerrySpacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm)) {
                        orgs.take(6).forEach { org ->
                            val isOwnerOrg = org.equals(
                                dev.chimeraant.berryforge.data.settings.SettingsStore.OWNER_ORG,
                                ignoreCase = true,
                            )
                            BerryChip(
                                text = org,
                                color = if (isOwnerOrg) BerryColors.Owner else BerryColors.TextSecondary,
                                container = if (isOwnerOrg) BerryColors.OwnerGlow else BerryColors.Surface3,
                                icon = if (isOwnerOrg) BerryIcons.Crown else BerryIcons.Building,
                                border = if (isOwnerOrg) BerryColors.Owner.copy(alpha = 0.4f) else null,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(BerrySpacing.xl))
            }

            // ---- Contributions ----
            Column(Modifier.padding(horizontal = BerrySpacing.lg)) {
                BerrySectionHeader(
                    "Contribution activity",
                    trailing = if (loadingContributions) "loading" else "${contributions.sumOf { it.count }} commits",
                )
                Spacer(Modifier.height(BerrySpacing.sm))
                ContributionGraph(contributions)
                Spacer(Modifier.height(BerrySpacing.xs))
                Text(
                    "Derived from recent public push events via the GitHub API.",
                    style = BerryType.Micro,
                    color = BerryColors.TextDisabled,
                )
            }

            Spacer(Modifier.height(BerrySpacing.xl))

            // ---- Repos ----
            Column(Modifier.padding(horizontal = BerrySpacing.lg)) {
                BerrySectionHeader("Repositories", trailing = "${myRepos.size}")
                Spacer(Modifier.height(BerrySpacing.sm))
            }
            if (myRepos.isEmpty()) {
                Text(
                    "No repositories owned by this account in the current list.",
                    style = BerryType.BodySmall,
                    color = BerryColors.TextTertiary,
                    modifier = Modifier.padding(horizontal = BerrySpacing.lg),
                )
            } else {
                myRepos.take(20).forEach { repo ->
                    ProfileRepoRow(repo = repo, onClick = { onOpenRepo(repo) })
                }
            }

            Spacer(Modifier.height(BerrySpacing.xl))

            // ---- Actions ----
            Column(Modifier.padding(horizontal = BerrySpacing.lg)) {
                BerryButton(
                    text = "Manage accounts",
                    onClick = onManageAccounts,
                    icon = BerryIcons.User,
                    fillWidth = true,
                    variant = BerryButtonVariant.Secondary,
                )
                Spacer(Modifier.height(BerrySpacing.sm))
                BerryButton(
                    text = "Settings",
                    onClick = onOpenSettings,
                    icon = BerryIcons.Settings,
                    fillWidth = true,
                    variant = BerryButtonVariant.Secondary,
                )
                Spacer(Modifier.height(BerrySpacing.sm))
                BerryButton(
                    text = "Refresh profile",
                    onClick = { viewModel.refreshOwnerStatus(force = true); viewModel.loadRepos(force = true) },
                    icon = BerryIcons.Refresh,
                    fillWidth = true,
                    variant = BerryButtonVariant.Ghost,
                )
            }

            Spacer(Modifier.height(BerrySpacing.giant))
        }
    }
}


@Composable
private fun OwnerNotice() {
    BerrySurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BerrySpacing.lg),
        color = BerryColors.OwnerGlow,
        border = BerryColors.Owner.copy(alpha = 0.35f),
        radius = BerryRadius.lg,
        contentPadding = PaddingValues(BerrySpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            BerryIcon(BerryIcons.Crown, null, size = BerrySize.icon, tint = BerryColors.Owner)
            Spacer(Modifier.width(BerrySpacing.md))
            Column {
                Text("Owner of ${dev.chimeraant.berryforge.data.settings.SettingsStore.OWNER_ORG}",
                    style = BerryType.BodyStrong, color = BerryColors.Owner)
                Spacer(Modifier.height(BerrySpacing.xxs))
                Text(
                    "This badge is determined on-device by checking your organisation memberships " +
                        "with the GitHub API. It is a client-side indicator, not server-verified " +
                        "authorisation, and it is cached locally.",
                    style = BerryType.Caption,
                    color = BerryColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    BerrySurface(
        modifier = modifier,
        color = BerryColors.Surface2,
        border = BerryColors.Outline,
        radius = BerryRadius.md,
        contentPadding = PaddingValues(BerrySpacing.md),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = BerryType.Title, color = BerryColors.TextPrimary)
            Text(label, style = BerryType.Micro, color = BerryColors.TextTertiary)
        }
    }
}

/**
 * Contribution heat strip.
 *
 * The public GitHub API does not expose the calendar, so this renders the push-event
 * activity we can actually fetch. It is labelled as such rather than passed off as the
 * real graph.
 */
@Composable
private fun ContributionGraph(days: List<GhContributionDay>) {
    if (days.isEmpty()) {
        BerrySurface(
            modifier = Modifier.fillMaxWidth(),
            color = BerryColors.Surface2,
            border = BerryColors.Outline,
            radius = BerryRadius.md,
            contentPadding = PaddingValues(BerrySpacing.lg),
        ) {
            Text(
                "No recent public push activity.",
                style = BerryType.BodySmall,
                color = BerryColors.TextTertiary,
            )
        }
        return
    }

    // Bucket into the last 12 weeks, oldest first.
    val weeks = 12
    val sorted = days.sortedBy { it.date }
    val perWeek = remember(sorted, weeks) {
        sorted.takeLast(weeks * 7).chunked(7).map { week -> week.sumOf { it.count } }
    }
    val max = (perWeek.maxOrNull() ?: 1).coerceAtLeast(1)

    BerrySurface(
        modifier = Modifier.fillMaxWidth(),
        color = BerryColors.Surface2,
        border = BerryColors.Outline,
        radius = BerryRadius.md,
        contentPadding = PaddingValues(BerrySpacing.md),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            perWeek.forEach { count ->
                val intensity = count.toFloat() / max
                Box(
                    Modifier
                        .weight(1f)
                        .height((12 + intensity * 40).dp)
                        .clip(RoundedCornerShape(BerryRadius.xs))
                        .background(
                            when {
                                count == 0 -> BerryColors.Surface4
                                intensity < 0.34f -> BerryColors.Success.copy(alpha = 0.35f)
                                intensity < 0.67f -> BerryColors.Success.copy(alpha = 0.6f)
                                else -> BerryColors.Success
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun ProfileRepoRow(repo: GhRepo, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BerrySpacing.lg, vertical = 2.dp)
            .clip(RoundedCornerShape(BerryRadius.md))
            .background(BerryColors.Surface2.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(BerrySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BerryIcon(BerryIcons.Branch, null, size = BerrySize.iconSm, tint = BerryColors.Edit)
        Spacer(Modifier.width(BerrySpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                repo.name,
                style = BerryType.BodyStrong,
                color = BerryColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                repo.language ?: repo.defaultBranch,
                style = BerryType.Micro,
                color = BerryColors.TextTertiary,
            )
        }
        if (repo.private) {
            BerryChip("private", icon = BerryIcons.Lock, color = BerryColors.TextTertiary)
            Spacer(Modifier.width(BerrySpacing.sm))
        }
        BerryIcon(BerryIcons.ChevronRight, null, size = 14.dp, tint = BerryColors.TextDisabled)
    }
}
