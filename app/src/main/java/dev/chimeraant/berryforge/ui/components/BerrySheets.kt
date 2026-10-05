package dev.chimeraant.berryforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryMotion
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType
import kotlinx.coroutines.delay

/**
 * Bottom sheet with a drag handle, spring entrance and scrim fade.
 * Replaces Material's ModalBottomSheet entirely.
 */
@Composable
fun BerrySheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = BerryColors.Edit,
    maxHeightFraction: Float = 0.88f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(BerryMotion.Standard)),
            exit = fadeOut(tween(BerryMotion.Fast)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(BerryColors.Scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
            ) + fadeIn(tween(BerryMotion.Standard)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(BerryMotion.Standard),
            ) + fadeOut(tween(BerryMotion.Fast)),
        ) {
            val shape = RoundedCornerShape(topStart = BerryRadius.xxl, topEnd = BerryRadius.xxl)
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = Dp.Infinity)
                    .clip(shape)
                    .background(BerryColors.Surface1)
                    .navigationBarsPadding()
                    .padding(bottom = BerrySpacing.sm),
            ) {
                // Handle
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = BerrySpacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(BerrySize.sheetHandleWidth, BerrySize.sheetHandleHeight)
                            .clip(RoundedCornerShape(BerryRadius.pill))
                            .background(BerryColors.OutlineStrong),
                    )
                }
                if (title != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BerrySpacing.xl),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (icon != null) {
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(BerryRadius.sm))
                                    .background(accent.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) { BerryIcon(icon, size = BerrySize.iconSm, tint = accent) }
                            Box(Modifier.size(BerrySpacing.md))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = BerryType.Title, color = BerryColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (subtitle != null) {
                                Text(subtitle, style = BerryType.Caption, color = BerryColors.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        BerryIconButton(BerryIcons.Close, "Close", onDismiss)
                    }
                    Box(Modifier.size(BerrySpacing.md))
                    BerryDivider(Modifier.padding(horizontal = BerrySpacing.xl))
                    Box(Modifier.size(BerrySpacing.sm))
                }
                Column(Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}

/** Centred dialog used for destructive confirmations and approval gates. */
@Composable
fun BerryDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    message: String? = null,
    icon: ImageVector? = null,
    accent: Color = BerryColors.Edit,
    confirmLabel: String = "Confirm",
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(BerryMotion.Standard)),
            exit = fadeOut(tween(BerryMotion.Fast)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(BerryColors.Scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(BerryMotion.Standard)) +
                androidx.compose.animation.scaleIn(initialScale = 0.94f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)),
            exit = fadeOut(tween(BerryMotion.Fast)) +
                androidx.compose.animation.scaleOut(targetScale = 0.96f, animationSpec = tween(BerryMotion.Fast)),
        ) {
            BerrySurface(
                modifier = Modifier
                    .padding(BerrySpacing.xxl)
                    .width(320.dp),
                color = BerryColors.Surface2,
                border = BerryColors.OutlineStrong,
                radius = BerryRadius.xl,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(BerrySpacing.xl),
            ) {
                Column {
                    if (icon != null) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(BerryRadius.md))
                                .background(accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) { BerryIcon(icon, size = BerrySize.iconLg, tint = accent) }
                        Box(Modifier.size(BerrySpacing.lg))
                    }
                    Text(title, style = BerryType.Headline, color = BerryColors.TextPrimary)
                    if (message != null) {
                        Box(Modifier.size(BerrySpacing.sm))
                        Text(message, style = BerryType.Body, color = BerryColors.TextSecondary)
                    }
                    Box(Modifier.size(BerrySpacing.xl))
                    Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm)) {
                        BerryButton(
                            text = dismissLabel,
                            onClick = onDismiss,
                            variant = BerryButtonVariant.Ghost,
                            modifier = Modifier.weight(1f),
                        )
                        BerryButton(
                            text = confirmLabel,
                            onClick = {
                                onConfirm()
                                onDismiss()
                            },
                            variant = if (destructive) BerryButtonVariant.Danger else BerryButtonVariant.Primary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Transient notification, shown in the top-right corner.
 *
 * Replaces an earlier bottom-anchored bar that had three problems: it sat over the
 * navigation bar, it never went away (there was no timeout at all), and it rendered the
 * whole message so a long one wrapped to three or more lines.
 *
 * Now:
 *  - anchored top-right, clear of the bottom navigation
 *  - auto-dismisses after [autoDismissMillis] (default 3s)
 *  - truncated to [MAX_LENGTH] characters with an ellipsis; the full text is passed to
 *    [onExpand] so a caller can open a details sheet
 *  - swipeable/clickable to dismiss early
 */
@Composable
fun BerrySnackbar(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    accent: Color = BerryColors.Edit,
    icon: ImageVector? = null,
    autoDismissMillis: Long = DEFAULT_AUTO_DISMISS_MS,
    onExpand: ((String) -> Unit)? = null,
) {
    val visible = message != null
    val currentMessage = message

    // Auto-dismiss. Keyed on the message so a new message restarts the timer rather than
    // inheriting the remainder of the previous one.
    LaunchedEffect(currentMessage) {
        if (currentMessage != null && autoDismissMillis > 0) {
            delay(autoDismissMillis)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BerrySpacing.lg)
            .statusBarsPadding()
            .padding(top = BerrySpacing.md),
        enter = slideInVertically(initialOffsetY = { -it / 2 }, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(BerryMotion.Standard)),
        exit = slideOutVertically(targetOffsetY = { -it / 2 }, animationSpec = tween(BerryMotion.Fast)) + fadeOut(tween(BerryMotion.Fast)),
    ) {
        BerrySurface(
            modifier = Modifier.clickable {
                // A tap reveals the full text when it was truncated, otherwise dismisses.
                if (currentMessage != null && currentMessage.length > MAX_LENGTH && onExpand != null) {
                    onExpand(currentMessage)
                }
                onDismiss()
            },
            color = BerryColors.Surface3,
            border = BerryColors.OutlineStrong,
            radius = BerryRadius.lg,
            contentPadding = PaddingValues(
                horizontal = BerrySpacing.lg,
                vertical = BerrySpacing.md,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    BerryIcon(icon, size = BerrySize.iconSm, tint = accent)
                    Box(Modifier.size(BerrySpacing.md))
                }
                Text(
                    currentMessage?.let { truncate(it) }.orEmpty(),
                    style = BerryType.BodySmall,
                    color = BerryColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (action != null && onAction != null) {
                    Box(Modifier.size(BerrySpacing.md))
                    Text(
                        action.uppercase(),
                        style = BerryType.Label,
                        color = accent,
                        modifier = Modifier.clickable { onAction(); onDismiss() },
                    )
                }
            }
        }
    }
}

/** Maximum characters shown before an ellipsis. Keeps a notification to one short line. */
private const val MAX_LENGTH = 60

/** Default time on screen before auto-dismissal. */
private const val DEFAULT_AUTO_DISMISS_MS = 3_000L

/** Shortens a message to [MAX_LENGTH], appending an ellipsis when it was cut. */
internal fun truncate(text: String): String {
    val single = text.replace('\n', ' ').trim()
    return if (single.length <= MAX_LENGTH) single else single.take(MAX_LENGTH - 1).trimEnd() + "…"
}

/**
 * Empty state with a soft accent halo. Used everywhere a list can be empty so the
 * product never shows a bare "no data" string.
 */
@Composable
fun BerryEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    accent: Color = BerryColors.Edit,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(BerrySpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(BerryRadius.xl))
                .background(accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { BerryIcon(icon, size = 28.dp, tint = accent) }
        Box(Modifier.size(BerrySpacing.lg))
        Text(title, style = BerryType.Headline, color = BerryColors.TextPrimary)
        Box(Modifier.size(BerrySpacing.xs))
        Text(
            body,
            style = BerryType.BodySmall,
            color = BerryColors.TextTertiary,
            modifier = Modifier.padding(horizontal = BerrySpacing.xl),
        )
        if (action != null) {
            Box(Modifier.size(BerrySpacing.lg))
            action()
        }
    }
}

/** Skeleton shimmer for list placeholders — no spinners on primary surfaces. */
@Composable
fun BerryShimmer(
    modifier: Modifier = Modifier,
    radius: Dp = BerryRadius.sm,
) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(1100, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    colors = listOf(
                        BerryColors.Surface2,
                        BerryColors.Surface3,
                        BerryColors.Surface2,
                    ),
                    startX = -400f + progress * 900f,
                    endX = -200f + progress * 900f,
                ),
            ),
    )
}
