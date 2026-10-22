package dev.chimeraant.berryforge.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [triangleWave], the closed form of `RepeatMode.Reverse` used by the animated
 * background.
 *
 * The background used to be driven by three `rememberInfiniteTransition`s, which repainted
 * the full-screen gradient canvas at the display refresh rate behind every screen. It now
 * runs off one throttled clock and derives each drift from this function, so the curve has
 * to behave exactly like the transition it replaced: start at 0, peak at the midpoint, and
 * return to 0 at the end of the period with no discontinuity.
 */
class TriangleWaveTest {

    private val period = 22_000L

    @Test
    fun `starts at zero and peaks at the midpoint`() {
        assertEquals(0f, triangleWave(0L, period), 1e-6f)
        assertEquals(1f, triangleWave(period / 2, period), 1e-6f)
    }

    @Test
    fun `returns to zero at the end of the period`() {
        assertEquals(0f, triangleWave(period, period), 1e-6f)
        // And is continuous across the wrap: just before and just after the boundary the
        // value must be close, or the drift would visibly jump every cycle.
        val before = triangleWave(period - 5L, period)
        val after = triangleWave(period + 5L, period)
        assertTrue("wrap must be seamless", Math.abs(before - after) < 0.01f)
    }

    @Test
    fun `rises then falls monotonically within a period`() {
        var previous = triangleWave(0L, period)
        for (step in 1..40) {
            val value = triangleWave(period / 2 * step / 40, period)
            assertTrue("must rise on the way up", value >= previous - 1e-6f)
            previous = value
        }
        for (step in 1..40) {
            val value = triangleWave(period / 2 + period / 2 * step / 40, period)
            assertTrue("must fall on the way down", value <= previous + 1e-6f)
            previous = value
        }
    }

    @Test
    fun `stays within zero and one`() {
        var elapsed = 0L
        while (elapsed < period * 3) {
            val value = triangleWave(elapsed, period)
            assertTrue("out of range at $elapsed: $value", value in 0f..1f)
            elapsed += 137L
        }
    }

    @Test
    fun `a degenerate period is handled instead of dividing by zero`() {
        assertEquals(0f, triangleWave(1_000L, 0L), 1e-6f)
        assertEquals(0f, triangleWave(1_000L, -5L), 1e-6f)
    }
}
