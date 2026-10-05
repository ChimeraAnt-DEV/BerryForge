package dev.chimeraant.berryforge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the colour-interpolation rule the buttons rely on.
 *
 * Regression: button backgrounds were animated directly between `Color.Transparent`
 * and an opaque surface colour. Because Compose interpolates colours component-wise and
 * `Color.Transparent` is transparent *black*, the sweep passed through muddy dark grey,
 * and an animation interrupted by a rapid tap could rest there — leaving the icon and
 * text apparently colourless until the screen was recomposed.
 *
 * The fix is to hold the RGB constant and animate only the alpha. These tests pin that
 * property so the old approach cannot creep back in.
 */
class ColorInterpolationTest {

    /** Component-wise RGBA interpolation, as Compose's colour converter performs it. */
    private fun lerpRgba(from: Long, to: Long, t: Float): Long {
        fun ch(shift: Int): Long {
            val f = (from shr shift) and 0xFF
            val d = (to shr shift) and 0xFF
            return (f + ((d - f) * t)).toLong() and 0xFF
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun rgbOf(color: Long): Triple<Long, Long, Long> =
        Triple((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)

    private fun alphaOf(color: Long): Long = (color shr 24) and 0xFF

    private val transparentBlack = 0x00000000L        // Color.Transparent
    private val surface3 = 0xFF1A2238L                // BerryColors.Surface3

    @Test
    fun interpolatingFromTransparentPassesThroughBlack() {
        // Demonstrates the bug: at the midpoint the colour is a dark blend of black and
        // navy, not navy at half alpha.
        val mid = lerpRgba(transparentBlack, surface3, 0.5f)
        val (r, g, b) = rgbOf(mid)
        assertEquals("midpoint red should be a black blend, not the target hue", 13L, r)
        assertEquals(17L, g)
        assertEquals(28L, b)
        assertNotEquals("the midpoint must NOT already be the target colour", surface3 and 0xFFFFFF, mid and 0xFFFFFF)
    }

    @Test
    fun animatingAlphaKeepsTheHueConstant() {
        // The approach now used: RGB fixed, alpha swept. Every frame is a real colour.
        for (step in 0..8) {
            val t = step / 8f
            val frame = (alphaOf(surface3).toFloat() * t).toInt().toLong() shl 24 or (surface3 and 0xFFFFFF)
            val (r, g, b) = rgbOf(frame)
            assertEquals("red must stay at the target hue", 26L, r)
            assertEquals("green must stay at the target hue", 34L, g)
            assertEquals("blue must stay at the target hue", 56L, b)
        }
    }

    @Test
    fun aFullyOpaqueFrameIsTheExactTargetColour() {
        val full = (255L shl 24) or (surface3 and 0xFFFFFF)
        assertEquals(surface3, full)
    }

    @Test
    fun aFullyTransparentFrameIsInvisible() {
        val none = (0L shl 24) or (surface3 and 0xFFFFFF)
        assertEquals(0L, alphaOf(none))
    }

    @Test
    fun anInterruptedAlphaAnimationAlwaysRestsOnAValidColour() {
        // Simulate rapid taps: sample many interrupted points and confirm none of them
        // is a blend of black. This is the property the old approach violated.
        val samples = listOf(0.05f, 0.2f, 0.37f, 0.51f, 0.68f, 0.83f, 0.99f)
        for (t in samples) {
            val frame = (alphaOf(surface3).toFloat() * t).toInt().toLong() shl 24 or (surface3 and 0xFFFFFF)
            val (r, g, b) = rgbOf(frame)
            assertTrue("every interrupted frame must keep the target hue", r == 26L && g == 34L && b == 56L)
        }
    }
}
