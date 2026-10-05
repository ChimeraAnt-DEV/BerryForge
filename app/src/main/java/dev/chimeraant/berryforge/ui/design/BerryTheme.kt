package dev.chimeraant.berryforge.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * BerryForge theme.
 *
 * Material3 is used only as a rendering substrate — none of its defaults are visible:
 * colours, type, shapes and every component come from this package. The scheme below
 * exists so third-party Compose internals (text selection handles, ripple fallbacks)
 * inherit the BerryForge palette instead of the stock purple.
 */
@Composable
fun BerryTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = BerryColors.Edit,
        onPrimary = BerryColors.Base,
        primaryContainer = BerryColors.EditDim,
        onPrimaryContainer = BerryColors.TextPrimary,
        secondary = BerryColors.Ai,
        onSecondary = BerryColors.Base,
        tertiary = BerryColors.Owner,
        background = BerryColors.Base,
        onBackground = BerryColors.TextPrimary,
        surface = BerryColors.Surface1,
        onSurface = BerryColors.TextPrimary,
        surfaceVariant = BerryColors.Surface3,
        onSurfaceVariant = BerryColors.TextSecondary,
        outline = BerryColors.Outline,
        outlineVariant = BerryColors.OutlineSubtle,
        error = BerryColors.Danger,
        onError = BerryColors.Base,
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(BerryRadius.xs),
        small = RoundedCornerShape(BerryRadius.sm),
        medium = RoundedCornerShape(BerryRadius.md),
        large = RoundedCornerShape(BerryRadius.lg),
        extraLarge = RoundedCornerShape(BerryRadius.xl),
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = BerryType.material,
        shapes = shapes,
        content = content,
    )
}
