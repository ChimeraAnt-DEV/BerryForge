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
import dev.chimeraant.berryforge.data.github.GhUser
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.components.Avatar
import dev.chimeraant.berryforge.ui.components.BerryButton
import dev.chimeraant.berryforge.ui.components.BerryButtonSize
import dev.chimeraant.berryforge.ui.components.BerryButtonVariant
import dev.chimeraant.berryforge.ui.components.BerryChip
import dev.chimeraant.berryforge.ui.components.BerryDialog
import dev.chimeraant.berryforge.ui.components.BerryDivider
import dev.chimeraant.berryforge.ui.components.BerryEmptyState
import dev.chimeraant.berryforge.ui.components.BerryIcon
import dev.chimeraant.berryforge.ui.components.BerryIconButton
import dev.chimeraant.berryforge.ui.components.BerrySurface
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType

/**
 * Manage Accounts.
 *
 * Lists every signed-in GitHub account with its avatar, marks which is active, and
 * offers switch, per-account sign-out and adding a new one via the device flow.
 *
 * Signing out of one account leaves the others untouched — the previous behaviour only
 * offered "sign out of everything", which meant adding a second account was a one-way
 * door unless you dropped the first.
 */
@Composable
fun ManageAccountsScreen(
    viewModel: BerryViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accounts = viewModel.accounts
    val activeLogin = viewModel.secure.activeLogin
    var profiles by remember { mutableStateOf<Map<String, GhUser?>>(emptyMap()) }
    var confirmSignOut by remember { mutableStateOf<String?>(null) }

    // Load whatever profiles are cached; the accounts screen should not block on network.
    LaunchedEffect(accounts) {
        val loaded = HashMap<String, GhUser?>()
        accounts.forEach { login -> loaded[login] = runCatching { viewModel.cachedProfile(login) }.getOrNull() }
        profiles = loaded
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
            Column(Modifier.weight(1f)) {
                Text("Manage accounts", style = BerryType.Headline, color = BerryColors.TextPrimary)
                Text(
                    "${accounts.size} signed in",
                    style = BerryType.Micro,
                    color = BerryColors.TextTertiary,
                )
            }
        }
        BerryDivider(strong = true)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BerrySpacing.lg),
        ) {
            Spacer(Modifier.height(BerrySpacing.lg))

            if (accounts.isEmpty()) {
                BerryEmptyState(
                    icon = BerryIcons.User,
                    title = "No accounts",
                    body = "Sign in with GitHub to get started.",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                accounts.forEach { login ->
                    AccountRow(
                        login = login,
                        profile = profiles[login],
                        isActive = login == activeLogin,
                        onSwitch = { viewModel.switchAccount(login) },
                        onSignOut = { confirmSignOut = login },
                    )
                    Spacer(Modifier.height(BerrySpacing.sm))
                }
            }

            Spacer(Modifier.height(BerrySpacing.lg))
            BerryDivider()
            Spacer(Modifier.height(BerrySpacing.lg))

            BerryButton(
                text = "Add account",
                onClick = { viewModel.addAccount(); onBack() },
                icon = BerryIcons.Plus,
                fillWidth = true,
                size = BerryButtonSize.Lg,
            )
            Spacer(Modifier.height(BerrySpacing.sm))
            Text(
                "Runs the GitHub device flow again. Your existing accounts stay signed in.",
                style = BerryType.Caption,
                color = BerryColors.TextTertiary,
            )

            Spacer(Modifier.height(BerrySpacing.giant))
        }
    }

    confirmSignOut?.let { login ->
        BerryDialog(
            visible = true,
            onDismiss = { confirmSignOut = null },
            title = "Sign out of $login?",
            message = "Only this account is removed. Any other signed-in accounts stay as they are.",
            icon = BerryIcons.Close,
            accent = BerryColors.Danger,
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = { viewModel.signOutAccount(login) },
        )
    }
}

@Composable
private fun AccountRow(
    login: String,
    profile: GhUser?,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onSignOut: () -> Unit,
) {
    BerrySurface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isActive) BerryColors.Edit.copy(alpha = 0.08f) else BerryColors.Surface2,
        border = if (isActive) BerryColors.Edit.copy(alpha = 0.4f) else BerryColors.Outline,
        radius = BerryRadius.lg,
        contentPadding = PaddingValues(BerrySpacing.lg),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(user = profile, size = BerrySize.avatar)
                Spacer(Modifier.width(BerrySpacing.md))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile?.displayName ?: login,
                            style = BerryType.BodyStrong,
                            color = BerryColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isActive) {
                            Spacer(Modifier.width(BerrySpacing.sm))
                            BerryChip("active", color = BerryColors.Edit, icon = BerryIcons.CheckCircle)
                        }
                    }
                    Text("@$login", style = BerryType.Caption, color = BerryColors.TextTertiary)
                }
            }

            Spacer(Modifier.height(BerrySpacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm)) {
                if (!isActive) {
                    BerryButton(
                        text = "Switch to",
                        onClick = onSwitch,
                        variant = BerryButtonVariant.Secondary,
                        size = BerryButtonSize.Sm,
                        icon = BerryIcons.Refresh,
                        modifier = Modifier.weight(1f),
                    )
                }
                BerryButton(
                    text = "Sign out",
                    onClick = onSignOut,
                    variant = BerryButtonVariant.Ghost,
                    size = BerryButtonSize.Sm,
                    icon = BerryIcons.Close,
                    modifier = if (isActive) Modifier.fillMaxWidth() else Modifier.weight(1f),
                )
            }
        }
    }
}
