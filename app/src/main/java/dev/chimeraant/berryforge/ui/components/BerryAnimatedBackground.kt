package dev.chimeraant.berryforge.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.chimeraant.berryforge.ui.design.BerryColors

/**
 * The app's animated background: a near-black field with slow-drifting blue light.
 *
 * Built from the existing palette rather than new colours — [BerryColors.Base] as the
 * field, [BerryColors.Edit] and [BerryColors.Ai] as the light — so it reads as the same
 * product rather than a decorative layer bolted on.
 *
 * Three elements move on different, deliberately long cycles so the motion never
 * resolves into an obvious loop:
 *  - two large radial washes drifting on a 22s and 30s cycle
 *  - a faint diagonal gradient sliding on a 38s cycle
 *
 * Everything is drawn on one [Canvas] in a single pass, and the whole thing is driven by
 * one infinite transition, so it costs one draw per frame and no recomposition. The
 * alpha values are low by design: this sits behind text, and the contrast of body copy
 * against the base colour must not change appreciably.
 */
@Composable
fun BerryAnimatedBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "berryBackground")

    // Two independent drifts, different periods so they never line up.
    val driftA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(22_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftA",
    )
    val driftB by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(30_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftB",
    )
    // A slow sweep across the whole surface, for a sense of depth.
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(38_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )

    Box(modifier.fillMaxSize().background(BerryColors.Base)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // ---- Diagonal wash: a very faint blue that slides with the sweep ----
            val slide = (sweep - 0.5f) * w * 0.6f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        BerryColors.Edit.copy(alpha = 0.055f),
                        Color.Transparent,
                    ),
                    start = Offset(-w * 0.3f + slide, 0f),
                    end = Offset(w * 1.1f + slide, h),
                ),
            )

            // ---- Primary blue wash, upper-left drifting ----
            val radiusA = w * 0.95f
            val centreA = Offset(
                x = w * (0.18f + driftA * 0.34f),
                y = h * (0.10f + driftA * 0.22f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        BerryColors.Edit.copy(alpha = 0.13f),
                        Color.Transparent,
                    ),
                    center = centreA,
                    radius = radiusA,
                ),
                radius = radiusA,
                center = centreA,
            )

            // ---- Secondary wash, lower-right, on the AI accent ----
            // Kept dimmer than the blue so the app still reads as blue-first.
            val radiusB = w * 0.85f
            val centreB = Offset(
                x = w * (0.92f - driftB * 0.30f),
                y = h * (0.88f - driftB * 0.26f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        BerryColors.Ai.copy(alpha = 0.085f),
                        Color.Transparent,
                    ),
                    center = centreB,
                    radius = radiusB,
                ),
                radius = radiusB,
                center = centreB,
            )

            // ---- A third, small highlight that keeps the middle from going flat ----
            val radiusC = w * 0.5f
            val centreC = Offset(
                x = w * (0.62f + driftA * 0.16f),
                y = h * (0.34f + driftB * 0.18f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        BerryColors.Edit.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    center = centreC,
                    radius = radiusC,
                ),
                radius = radiusC,
                center = centreC,
            )
        }

        content()
    }
}
