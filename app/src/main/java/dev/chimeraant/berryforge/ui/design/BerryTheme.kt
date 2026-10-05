package dev.chimeraant.berryforge.ui.design

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

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
    // Supply the branded press indication as the default. Without this, any plain
    // `Modifier.clickable {}` falls back to Material's grey ripple, which does not match
    // the palette — and the explicit controls had no feedback at all.
    CompositionLocalProvider(
        LocalIndication provides dev.chimeraant.berryforge.ui.components.berryIndication(),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = BerryType.material,
            shapes = shapes,
        ) {
            // The animated background sits behind everything. Applying it here means
            // every screen inherits it rather than each having to opt in and stay
            // consistent. Screens that paint their own opaque surface simply cover it.
            dev.chimeraant.berryforge.ui.components.BerryAnimatedBackground {
                content()
            }
        }
    }
}
