package dev.chimeraant.berryforge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryMotion
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType

enum class BerryButtonVariant { Primary, Secondary, Ghost, Danger, Ai, Owner }
enum class BerryButtonSize { Sm, Md, Lg }

private data class ButtonSkin(
    val container: Color,
    val containerPressed: Color,
    val content: Color,
    val border: Color?,
    val glow: Color?,
)

@Composable
private fun skinFor(variant: BerryButtonVariant): ButtonSkin = when (variant) {
    BerryButtonVariant.Primary -> ButtonSkin(
        container = BerryColors.Edit,
        containerPressed = BerryColors.EditDim,
        content = BerryColors.Base,
        border = null,
        glow = BerryColors.EditGlow,
    )
    BerryButtonVariant.Secondary -> ButtonSkin(
        container = BerryColors.Surface3,
        containerPressed = BerryColors.Surface4,
        content = BerryColors.TextPrimary,
        border = BerryColors.OutlineStrong,
        glow = null,
    )
    BerryButtonVariant.Ghost -> ButtonSkin(
        container = Color.Transparent,
        containerPressed = BerryColors.Surface3,
        content = BerryColors.TextSecondary,
        border = null,
        glow = null,
    )
    BerryButtonVariant.Danger -> ButtonSkin(
        container = BerryColors.Danger,
        containerPressed = BerryColors.DangerDim,
        content = BerryColors.Base,
        border = null,
        glow = null,
    )
    BerryButtonVariant.Ai -> ButtonSkin(
        container = BerryColors.Ai,
        containerPressed = BerryColors.AiDim,
        content = BerryColors.Base,
        border = null,
        glow = BerryColors.AiGlow,
    )
    BerryButtonVariant.Owner -> ButtonSkin(
        container = BerryColors.Owner,
        containerPressed = BerryColors.OwnerDim,
        content = BerryColors.Base,
        border = null,
        glow = BerryColors.OwnerGlow,
    )
}

/**
 * The single button in BerryForge. Squarer than Material, with a state-shifting accent
 * and a press-scale that keeps taps feeling physical.
 *
 * ## Why the background is not animated directly
 *
 * Compose interpolates colours component-wise, and `Color.Transparent` is
 * `0x00000000` — transparent *black*, not the absence of a colour. Animating a Ghost
 * button's background from Transparent to Surface3 therefore sweeps the RGB channels
 * from black to navy while the alpha climbs, so the button passes through muddy dark
 * grey instead of fading its own hue in.
 *
 * On a rapid tap sequence those animations are cancelled and restarted faster than
 * they complete, and the button can be left resting on an intermediate value — which is
 * exactly the reported symptom of the icon and text colour vanishing until you navigate
 * away and back (recomposition reset the animation to its target).
 *
 * The fix is to keep the RGB constant and animate only the alpha, so every frame is a
 * legitimate colour rather than an interpolated black blend.
 */
@Composable
fun BerryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BerryButtonVariant = BerryButtonVariant.Primary,
    size: BerryButtonSize = BerryButtonSize.Md,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
) {
    val skin = skinFor(variant)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()

    // The hue is chosen by state; the animation only moves between two alphas of the
    // *same* colour, so an interrupted animation can never rest on a muddy blend.
    val baseColor = when {
        !enabled -> BerryColors.Surface2
        pressed -> skin.containerPressed
        hovered && skin.container != Color.Transparent -> skin.container
        hovered -> BerryColors.Surface2
        else -> skin.container
    }
    val targetAlpha = when {
        !enabled -> 1f
        skin.container == Color.Transparent && !pressed && !hovered -> 0f
        else -> 1f
    }
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(BerryMotion.Fast),
        label = "buttonAlpha",
    )
    val container = baseColor.copy(alpha = animatedAlpha)

    val contentColor by animateColorAsState(
        targetValue = if (enabled) skin.content else BerryColors.TextDisabled,
        animationSpec = tween(BerryMotion.Fast),
        label = "buttonContent",
    )

    val height: Dp = when (size) {
        BerryButtonSize.Sm -> 32.dp
        BerryButtonSize.Md -> 40.dp
        BerryButtonSize.Lg -> 48.dp
    }
    val hPad: Dp = when (size) {
        BerryButtonSize.Sm -> BerrySpacing.md
        BerryButtonSize.Md -> BerrySpacing.lg
        BerryButtonSize.Lg -> BerrySpacing.xl
    }
    val textStyle = when (size) {
        BerryButtonSize.Sm -> BerryType.Label
        else -> BerryType.BodyStrong
    }
    val shape = RoundedCornerShape(BerryRadius.md)

    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = height)
            .clip(shape)
            .background(container, shape)
            .then(
                if (skin.border != null && enabled) {
                    Modifier.border(BorderStroke(BerrySize.hairline, skin.border), shape)
                } else {
                    Modifier
                },
            )
            .scale(if (pressed) 0.975f else 1f)
            .clickable(
                interactionSource = interaction,
                // Was null, which removed all press feedback: a tap produced no visual
                // response, so users could not tell whether it registered.
                indication = berryIndication(
                    if (skin.container == Color.Transparent) BerryColors.Edit else skin.container,
                ),
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = hPad, vertical = BerrySpacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            if (icon != null) {
                BerryIcon(icon, size = if (size == BerryButtonSize.Sm) BerrySize.iconSm else BerrySize.icon)
                Box(Modifier.size(BerrySpacing.sm))
            }
            ProvideTextStyle(textStyle) {
                Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis, color = contentColor)
            }
            if (trailingIcon != null) {
                Box(Modifier.size(BerrySpacing.sm))
                BerryIcon(trailingIcon, size = if (size == BerryButtonSize.Sm) BerrySize.iconSm else BerrySize.icon)
            }
        }
    }
}


/** Square icon-only button used in toolbars and list rows. */
@Composable
fun BerryIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = BerryColors.TextSecondary,
    activeTint: Color? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = BerrySize.icon,
    containerSize: Dp = 34.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val resolvedTint by animateColorAsState(
        targetValue = when {
            !enabled -> BerryColors.TextDisabled
            active && activeTint != null -> activeTint
            active -> BerryColors.Edit
            else -> tint
        },
        animationSpec = tween(BerryMotion.Fast),
        label = "iconTint",
    )
    // Same reasoning as BerryButton: the RGB stays fixed and only the alpha animates, so
    // an interrupted animation cannot leave the icon resting on a blend of black.
    val baseBg = when {
        pressed -> BerryColors.Surface4
        hovered -> BerryColors.Surface3
        active && activeTint != null -> activeTint
        else -> BerryColors.Surface3
    }
    val targetAlpha = when {
        pressed -> 1f
        hovered -> 1f
        active && activeTint != null -> 0.14f
        else -> 0f
    }
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(BerryMotion.Fast),
        label = "iconBgAlpha",
    )
    val bg = baseBg.copy(alpha = animatedAlpha)
    Box(
        modifier = modifier
            .size(containerSize)
            .clip(RoundedCornerShape(BerryRadius.sm))
            .background(bg)
            .clickable(interactionSource = interaction, indication = berryIndication(activeTint ?: BerryColors.Edit), enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.6f),
        contentAlignment = Alignment.Center,
    ) {
        BerryIcon(icon, contentDescription, size = size, tint = resolvedTint)
    }
}

/**
 * A subtle bordered surface. The workhorse container for cards, panels and sheets.
 */
@Composable
fun BerrySurface(
    modifier: Modifier = Modifier,
    color: Color = BerryColors.Surface2,
    border: Color? = BerryColors.Outline,
    radius: Dp = BerryRadius.lg,
    contentPadding: PaddingValues = PaddingValues(BerrySpacing.lg),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(color, shape)
            .then(if (border != null) Modifier.border(BerrySize.hairline, border, shape) else Modifier)
            .padding(contentPadding),
    ) {
        content()
    }
}

/** Gradient hairline used as a section divider. */
@Composable
fun BerryDivider(
    modifier: Modifier = Modifier,
    color: Color = BerryColors.Outline,
    strong: Boolean = false,
) {
    val brush = if (strong) {
        Brush.horizontalGradient(listOf(Color.Transparent, color, Color.Transparent))
    } else {
        Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = 0.7f), Color.Transparent))
    }
    Box(modifier.fillMaxWidth().size(BerrySize.hairline).background(brush))
}

/** Small status pill: build state, branch, PR state, owner badge. */
@Composable
fun BerryChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = BerryColors.TextSecondary,
    container: Color = BerryColors.Surface3,
    icon: ImageVector? = null,
    border: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(BerryRadius.sm)
    Row(
        modifier = modifier
            .clip(shape)
            .background(container, shape)
            .then(if (border != null) Modifier.border(BerrySize.hairline, border, shape) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = BerrySpacing.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BerrySpacing.xs),
    ) {
        if (icon != null) BerryIcon(icon, size = 13.dp, tint = color)
        Text(text = text, style = BerryType.Micro, color = color, maxLines = 1)
    }
}

/** All-caps section label with an optional trailing count. */
@Composable
fun BerrySectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    color: Color = BerryColors.TextTertiary,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text.uppercase(), style = BerryType.Overline, color = color)
        if (trailing != null) {
            Text(trailing, style = BerryType.Micro, color = color)
        }
    }
}

/** A one-line helper used across empty states and settings rows. */
@Composable
fun BerryHintRow(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    accent: Color = BerryColors.Edit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.padding(vertical = BerrySpacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(BerryRadius.sm))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { BerryIcon(icon, size = BerrySize.iconSm, tint = accent) }
        Box(Modifier.size(BerrySpacing.md))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = BerryType.BodyStrong, color = BerryColors.TextPrimary)
            if (body != null) {
                Text(body, style = BerryType.Caption, color = BerryColors.TextTertiary)
            }
        }
        if (trailing != null) trailing()
    }
}
