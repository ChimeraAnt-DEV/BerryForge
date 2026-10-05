package dev.chimeraant.berryforge.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chimeraant.berryforge.ui.design.BerrySize

/**
 * Renders a [dev.chimeraant.berryforge.ui.design.BerryIcons] vector.
 * Material's Icon is only a painter here — every glyph is BerryForge's own.
 */
@Composable
fun BerryIcon(
    icon: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = BerrySize.icon,
) {
    M3Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}

@Composable
fun BerryIconSmall(
    icon: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) = BerryIcon(icon, contentDescription, modifier, tint, BerrySize.iconSm)

@Composable
fun BerryIconLarge(
    icon: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) = BerryIcon(icon, contentDescription, modifier, tint, BerrySize.iconLg)
