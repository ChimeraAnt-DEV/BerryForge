package dev.chimeraant.berryforge.ui.design

import androidx.compose.ui.unit.dp

/**
 * Spacing scale. 4dp base, with a wider rhythm for large surfaces.
 * Never hard-code dp values in feature code — use these.
 */
object BerrySpacing {
    val none = 0.dp
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 40.dp
    val giant = 56.dp
}

object BerryRadius {
    val xs = 4.dp
    val sm = 6.dp
    val md = 10.dp
    val lg = 14.dp
    val xl = 18.dp
    val xxl = 24.dp
    val pill = 999.dp
}

object BerrySize {
    val topBar = 56.dp
    val bottomBar = 64.dp
    val row = 44.dp
    val rowCompact = 36.dp
    val iconSm = 16.dp
    val icon = 20.dp
    val iconLg = 24.dp
    val avatarChip = 30.dp
    val avatar = 40.dp
    val avatarLg = 72.dp
    val hairline = 1.dp
    val sheetHandleWidth = 36.dp
    val sheetHandleHeight = 4.dp
}

/** Animation timings. Everything in the app animates; these keep it consistent. */
object BerryMotion {
    const val Instant = 90
    const val Fast = 150
    const val Standard = 220
    const val Slow = 340
    const val Reveal = 420
    const val Stagger = 36
}
