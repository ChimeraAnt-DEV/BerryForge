package dev.chimeraant.berryforge.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the sheet that crashed on open.
 *
 * `BerrySheet` used `heightIn(max = Dp.Infinity)`. That is a no-op, so the sheet was
 * measured with an unbounded height and tall content pushed its own action buttons off the
 * top of the screen. [sheetMaxHeight] is the pure function that replaced it, and these
 * tests pin that it can never again return an unbounded value.
 */
class BerrySheetHeightTest {

    @Test
    fun `a fraction of the screen is returned`() {
        assertEquals(792.dp, sheetMaxHeight(screenHeightDp = 900, fraction = 0.88f))
        assertEquals(810.dp, sheetMaxHeight(screenHeightDp = 900, fraction = 0.9f))
    }

    @Test
    fun `the result is always finite`() {
        // Dp.Infinity is what the bug used. Nothing may ever return it.
        listOf(0.88f, 1f, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { fraction ->
            val height = sheetMaxHeight(screenHeightDp = 900, fraction = fraction)
            assertTrue(
                "fraction=$fraction produced a non-finite height $height",
                height.value.isFinite() && height.value > 0f,
            )
        }
    }

    @Test
    fun `out-of-range fractions are clamped, not trusted`() {
        // Above 1 would let a sheet grow past the screen; below the floor would hide it.
        assertEquals(sheetMaxHeight(900, 1f), sheetMaxHeight(900, 4f))
        assertEquals(sheetMaxHeight(900, 0.25f), sheetMaxHeight(900, 0f))
        assertEquals(sheetMaxHeight(900, 0.88f), sheetMaxHeight(900, Float.NaN))
    }

    @Test
    fun `a degenerate screen height never yields zero`() {
        assertTrue(sheetMaxHeight(screenHeightDp = 0, fraction = 0.9f).value > 0f)
    }
}
