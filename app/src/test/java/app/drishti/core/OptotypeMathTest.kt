package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptotypeMathTest {

    private val tolMm = 0.01

    // With pxPerMm = 1, pixels == millimetres, so these check the H_mm values
    // from build sheet section 04 through the public px function.

    @Test
    fun `3 m at logMAR 0 is 4_36 mm`() {
        assertEquals(4.36, OptotypeMath.optotypeHeightPx(3000.0, 0.0, 1.0), tolMm)
        assertEquals(4.36, OptotypeMath.optotypeHeightMm(3000.0, 0.0), tolMm)
    }

    @Test
    fun `3 m at logMAR 1_0 is 43_6 mm`() {
        // Exact value is 43.633 mm. The build sheet's "43.6" is rounded to 3 s.f. and is
        // 0.033 mm off, so it cannot meet a 0.01 mm tolerance. Assert the exact value at
        // 0.01 mm, and the sheet's figure at the precision it was printed (+/-0.05).
        assertEquals(43.63, OptotypeMath.optotypeHeightPx(3000.0, 1.0, 1.0), tolMm)
        assertEquals(43.63, OptotypeMath.optotypeHeightMm(3000.0, 1.0), tolMm)
        assertEquals(43.6, OptotypeMath.optotypeHeightMm(3000.0, 1.0), 0.05)
    }

    @Test
    fun `400 mm at logMAR 0 is 0_58 mm`() {
        assertEquals(0.58, OptotypeMath.optotypeHeightPx(400.0, 0.0, 1.0), tolMm)
        assertEquals(0.58, OptotypeMath.optotypeHeightMm(400.0, 0.0), tolMm)
    }

    @Test
    fun `pixel height scales linearly with the card-calibrated pxPerMm`() {
        val mm = OptotypeMath.optotypeHeightMm(3000.0, 0.3)
        assertEquals(mm * 15.75, OptotypeMath.optotypeHeightPx(3000.0, 0.3, 15.75), 1e-9)
    }

    @Test
    fun `one line (0_1 logMAR) is a factor of 10^0_1`() {
        val a = OptotypeMath.optotypeHeightMm(3000.0, 0.4)
        val b = OptotypeMath.optotypeHeightMm(3000.0, 0.3)
        assertEquals(Math.pow(10.0, 0.1), a / b, 1e-12)
    }

    @Test
    fun `stroke is one fifth of the height (5x5 grid)`() {
        assertEquals(4.0, OptotypeMath.strokePx(20.0), 1e-12)
    }

    @Test
    fun `minStrokeOk enforces the minimum stroke`() {
        assertTrue(OptotypeMath.minStrokeOk(heightPx = 15.0, minStrokePx = 3.0)) // stroke exactly 3
        assertFalse(OptotypeMath.minStrokeOk(heightPx = 14.99, minStrokePx = 3.0))
        assertTrue(OptotypeMath.minStrokeOk(heightPx = 100.0, minStrokePx = 3.0))
    }

    @Test
    fun `near logMAR 0 is pixel-limited on a typical budget screen`() {
        // 0.58 mm letter -> 0.116 mm stroke; at ~11.8 px/mm (300 ppi) that is ~1.4 px.
        val h = OptotypeMath.optotypeHeightPx(400.0, 0.0, 11.8)
        assertFalse(OptotypeMath.minStrokeOk(h, 3.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-positive distance`() {
        OptotypeMath.optotypeHeightPx(0.0, 0.0, 10.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-positive pxPerMm`() {
        OptotypeMath.optotypeHeightPx(3000.0, 0.0, 0.0)
    }
}
