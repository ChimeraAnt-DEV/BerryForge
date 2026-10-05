package dev.chimeraant.berryforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chimeraant.berryforge.ui.BerryViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.chimeraant.berryforge.data.github.GhUser
import dev.chimeraant.berryforge.ui.Destination
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryMotion
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType

/**
 * The persistent top bar. Always shows the signed-in identity chip on the right, which
 * is what makes the owner badge visible from anywhere in the app.
 */
@Composable
fun BerryTopBar(
    title: String,
    viewModel: BerryViewModel,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onProfileClick: () -> Unit = {},
    onManageAccounts: () -> Unit = {},
    actions: (@Composable () -> Unit)? = null,
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val isOwner by viewModel.isOwner.collectAsStateWithLifecycle()
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(BerryColors.Surface1, BerryColors.Base),
                ),
            )
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(BerrySize.topBar)
                .padding(horizontal = BerrySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Box(Modifier.size(BerrySpacing.sm))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = BerryType.Headline,
                    color = BerryColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = BerryType.Micro,
                        color = BerryColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (actions != null) {
                actions()
                Box(Modifier.size(BerrySpacing.xs))
            }
            ProfileChip(
                viewModel = viewModel,
                onOpenProfile = onProfileClick,
                onManageAccounts = onManageAccounts,
            )
        }
        BerryDivider(strong = true)
    }
}

/**
 * Avatar + handle in a pill, with the owner crown when applicable.
 *
 * Tapping opens an account menu listing every signed-in account, with switch, per-account
 * sign-out and an add-account action. Previously this only fired a callback that opened
 * the profile screen, so there was no way to reach account management from the chip.
 *
 * The menu is self-contained: it resolves the account list from the view model, so every
 * screen that renders the top bar gets the behaviour without each one having to thread
 * callbacks through.
 */
@Composable
fun ProfileChip(
    viewModel: BerryViewModel,
    modifier: Modifier = Modifier,
    onOpenProfile: () -> Unit = {},
    onManageAccounts: () -> Unit = {},
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val isOwner by viewModel.isOwner.collectAsStateWithLifecycle()
    val accounts = viewModel.accounts
    val activeLogin = viewModel.secure.activeLogin
    var menuOpen by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf<String?>(null) }

    Box(modifier) {
        ProfileChipSurface(
            user = user,
            isOwner = isOwner,
            onClick = { menuOpen = true },
        )

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier
                .widthIn(min = 260.dp, max = 300.dp)
                .background(BerryColors.Surface2, RoundedCornerShape(BerryRadius.md))
                .border(
                    BerrySize.hairline,
                    BerryColors.OutlineStrong,
                    RoundedCornerShape(BerryRadius.md),
                ),
        ) {
            AccountMenuContent(
                user = user,
                isOwner = isOwner,
                accounts = accounts,
                activeLogin = activeLogin,
                onSwitch = { login ->
                    viewModel.switchAccount(login)
                    menuOpen = false
                },
                onSignOut = { login ->
                    confirmSignOut = login
                    menuOpen = false
                },
                onOpenProfile = {
                    menuOpen = false
                    onOpenProfile()
                },
                onManageAccounts = {
                    menuOpen = false
                    onManageAccounts()
                },
                onAddAccount = {
                    menuOpen = false
                    viewModel.addAccount()
                },
            )
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

/** The pill itself, without the menu. */
@Composable
private fun ProfileChipSurface(
    user: GhUser?,
    isOwner: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 1200f),
        label = "chipScale",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isOwner) BerryColors.Owner.copy(alpha = 0.55f) else BerryColors.Outline,
        animationSpec = tween(BerryMotion.Standard),
        label = "chipBorder",
    )
    val shape = RoundedCornerShape(BerryRadius.pill)

    Row(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(BerryColors.Surface3, shape)
            .border(BerrySize.hairline, borderColor, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 3.dp, end = BerrySpacing.sm, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BerrySpacing.xs),
    ) {
        Avatar(user = user, size = BerrySize.avatarChip, isOwner = isOwner)
        if (user != null) {
            Text(
                user.login,
                style = BerryType.Micro,
                color = BerryColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isOwner) OwnerBadge(compact = true)
        BerryIcon(
            BerryIcons.ChevronDown,
            "Account menu",
            size = 12.dp,
            tint = BerryColors.TextTertiary,
        )
    }
}

/** Contents of the account menu: identity, account list, and actions. */
@Composable
private fun AccountMenuContent(
    user: GhUser?,
    isOwner: Boolean,
    accounts: List<String>,
    activeLogin: String?,
    onSwitch: (String) -> Unit,
    onSignOut: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onManageAccounts: () -> Unit,
    onAddAccount: () -> Unit,
) {
    Column(Modifier.padding(vertical = BerrySpacing.xs)) {
        // ---- Current identity ----
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenProfile)
                .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(user = user, size = BerrySize.avatar, isOwner = isOwner)
            Spacer(Modifier.width(BerrySpacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        user?.displayName ?: "Not signed in",
                        style = BerryType.BodyStrong,
                        color = BerryColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isOwner) {
                        Spacer(Modifier.width(BerrySpacing.xs))
                        OwnerBadge(compact = true)
                    }
                }
                Text(
                    "@${user?.login ?: "—"}",
                    style = BerryType.Caption,
                    color = BerryColors.TextTertiary,
                )
            }
            BerryIcon(BerryIcons.ArrowRight, null, size = 13.dp, tint = BerryColors.TextDisabled)
        }

        BerryDivider()

        // ---- Signed-in accounts ----
        if (accounts.size > 1) {
            Text(
                "SIGNED IN",
                style = BerryType.Overline,
                color = BerryColors.TextTertiary,
                modifier = Modifier.padding(
                    start = BerrySpacing.md,
                    top = BerrySpacing.sm,
                    bottom = BerrySpacing.xxs,
                ),
            )
            accounts.forEach { login ->
                val isActive = login == activeLogin
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isActive) { onSwitch(login) }
                        .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BerryIcon(
                        if (isActive) BerryIcons.CheckCircle else BerryIcons.User,
                        null,
                        size = BerrySize.iconSm,
                        tint = if (isActive) BerryColors.Edit else BerryColors.TextTertiary,
                    )
                    Spacer(Modifier.width(BerrySpacing.sm))
                    Text(
                        login,
                        style = BerryType.BodySmall,
                        color = if (isActive) BerryColors.TextPrimary else BerryColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isActive) {
                        Text("active", style = BerryType.Micro, color = BerryColors.Edit)
                    } else {
                        BerryIconButton(
                            BerryIcons.Close,
                            "Sign out of $login",
                            onClick = { onSignOut(login) },
                            tint = BerryColors.TextTertiary,
                            containerSize = 26.dp,
                            size = 13.dp,
                        )
                    }
                }
            }
            BerryDivider()
        }

        // ---- Actions ----
        MenuAction(BerryIcons.Plus, "Add account", onAddAccount)
        MenuAction(BerryIcons.User, "Manage accounts", onManageAccounts)
        MenuAction(BerryIcons.Settings, "Profile", onOpenProfile)
    }
}

@Composable
private fun MenuAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BerryIcon(icon, null, size = BerrySize.iconSm, tint = BerryColors.TextSecondary)
        Spacer(Modifier.width(BerrySpacing.sm))
        Text(label, style = BerryType.BodySmall, color = BerryColors.TextPrimary)
    }
}

/** Gold crown + OWNER tag. The only place gold appears outside owner buttons. */
@Composable
fun OwnerBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(BerryRadius.xs)
    Row(
        modifier = modifier
            .clip(shape)
            .background(BerryColors.OwnerGlow, shape)
            .border(BerrySize.hairline, BerryColors.Owner.copy(alpha = 0.5f), shape)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BerryIcon(BerryIcons.Crown, "Owner", size = 11.dp, tint = BerryColors.Owner)
        if (!compact) {
            Text("OWNER", style = BerryType.Overline, color = BerryColors.Owner)
        }
    }
}

@Composable
fun Avatar(
    user: GhUser?,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    isOwner: Boolean = false,
) {
    val shape = RoundedCornerShape(BerryRadius.pill)
    // Owners get a gold ring around the avatar, per the owner badge spec.
    val ringColor = if (isOwner) BerryColors.Owner else BerryColors.OutlineStrong
    val ringWidth = if (isOwner) 2.dp else BerrySize.hairline
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(BerryColors.Surface4)
            .border(ringWidth, ringColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        val url = user?.avatar_url
        if (url.isNullOrBlank()) {
            BerryIcon(
                BerryIcons.User,
                null,
                size = size * 0.55f,
                tint = if (isOwner) BerryColors.Owner else BerryColors.TextTertiary,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = user?.login,
                modifier = Modifier
                    .size(if (isOwner) size - 6.dp else size)
                    .clip(shape),
            )
        }
    }
}

/**
 * Bottom navigation. Custom-drawn so the active indicator can shift colour with the
 * destination rather than using Material's pill.
 */
@Composable
fun BerryBottomBar(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    accentFor: (Destination) -> Color = { BerryColors.Edit },
    enabledDestinations: Set<Destination> = Destination.entries.toSet(),
) {
    Column(modifier.fillMaxWidth().background(BerryColors.Surface1).navigationBarsPadding()) {
        BerryDivider(strong = true)
        Row(
            Modifier
                .fillMaxWidth()
                .height(BerrySize.bottomBar)
                .padding(horizontal = BerrySpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Destination.entries.forEach { destination ->
                NavItem(
                    destination = destination,
                    selected = current == destination,
                    enabled = destination in enabledDestinations,
                    accent = accentFor(destination),
                    onClick = { onSelect(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    destination: Destination,
    selected: Boolean,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> BerryColors.TextDisabled
            selected -> accent
            else -> BerryColors.TextTertiary
        },
        animationSpec = tween(BerryMotion.Fast),
        label = "navTint",
    )
    val icon: ImageVector = when (destination) {
        Destination.Repos -> BerryIcons.Branch
        Destination.Editor -> BerryIcons.FileCode
        Destination.Build -> BerryIcons.Hammer
        Destination.Agent -> BerryIcons.Robot
        Destination.Terminal -> BerryIcons.Terminal
        Destination.Settings -> BerryIcons.Settings
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(BerryRadius.sm))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = BerrySpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BerryIcon(icon, destination.label, size = BerrySize.icon, tint = tint)
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(tween(BerryMotion.Fast)) + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut(tween(BerryMotion.Instant)),
        ) {
            Text(destination.label, style = BerryType.Overline, color = tint)
        }
        if (!selected) {
            Box(Modifier.height(13.dp))
        }
    }
}
