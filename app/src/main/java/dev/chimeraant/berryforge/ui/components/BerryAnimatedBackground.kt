package dev.chimeraant.berryforge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
 * Everything is drawn on one [Canvas] in a single pass, so it costs one draw per update
 * and no recomposition of the content above it.
 *
 * ## Why the update rate is throttled
 *
 * This sits behind every screen, including the editor while the user types, and each
 * update repaints the full viewport with four gradient shaders. Driving that at the
 * display's refresh rate meant paying for those shaders 120 times a second on a
 * high-refresh phone, permanently, for motion whose *fastest* cycle is 22 seconds — about
 * 0.1% of a cycle per frame. The clock below updates at [BACKGROUND_FRAME_MS] instead,
 * which is still far finer than the motion can be perceived at, and cuts the work several
 * times over. The alpha values stay low by design: this sits behind text, and the contrast
 * of body copy against the base colour must not change appreciably.
 */
private const val BACKGROUND_FRAME_MS = 48L

/** Longest cycle, in milliseconds; the clock wraps at this multiple of the periods. */
private const val BACKGROUND_PERIOD_MS = 38_000L

@Composable
fun BerryAnimatedBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // A single throttled clock replaces three infinite transitions. Each drift is derived
    // from it, so one frame-tick drives all of them and the whole background has exactly
    // one state write per BACKGROUND_FRAME_MS.
    //
    // Every frame's delta is accumulated, and only the *publication* is throttled. Timing
    // the gap between publications instead would silently discard the sub-threshold
    // remainder on each tick, so the clock would run slower than real time and the drift
    // would visibly stall.
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var lastFrame = 0L
        var accumulated = 0L
        var published = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastFrame == 0L) lastFrame = now
                accumulated += (now - lastFrame) / 1_000_000L
                lastFrame = now
            }
            if (accumulated - published >= BACKGROUND_FRAME_MS) {
                published = accumulated
                elapsedMs = accumulated
            }
        }
    }

    // Triangle waves: equivalent to RepeatMode.Reverse, so the motion eases back and forth
    // instead of jumping when a cycle wraps. They are computed *inside* the draw lambda
    // below, not here: a state read in the draw phase invalidates only the draw, whereas
    // reading it in composition would recompose this composable — and risk recomposing the
    // screen inside `content` — on every tick.
    Box(modifier.fillMaxSize().background(BerryColors.Base)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val driftA = triangleWave(elapsedMs, 22_000L)
            val driftB = triangleWave(elapsedMs, 30_000L)
            val sweep = triangleWave(elapsedMs, BACKGROUND_PERIOD_MS)

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

/**
 * A 0→1→0 triangle wave over [periodMs], from a monotonically increasing clock.
 *
 * This is the closed form of `RepeatMode.Reverse`: at the midpoint of a period the value is
 * 1, and it returns to 0 at the end without a jump, so a wrapped cycle is seamless.
 */
internal fun triangleWave(elapsedMs: Long, periodMs: Long): Float {
    if (periodMs <= 0L) return 0f
    val phase = (elapsedMs % periodMs).toFloat() / periodMs.toFloat()
    return if (phase <= 0.5f) phase * 2f else (1f - phase) * 2f
}
