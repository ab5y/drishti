package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardCalibrationTest {

    @Test
    fun `pxPerMm is matched edge over 85_6 mm`() {
        assertEquals(10.0, CardCalibration.pxPerMm(856.0), 1e-12)
        assertEquals(1348.2 / 85.6, CardCalibration.pxPerMm(1348.2), 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive edge`() {
        CardCalibration.pxPerMm(0.0)
    }

    @Test
    fun `xdpi converts to px per mm`() {
        assertEquals(400.0 / 25.4, CardCalibration.pxPerMmFromDpi(400.0)!!, 1e-12)
        assertNull(CardCalibration.pxPerMmFromDpi(0.0))
        assertNull(CardCalibration.pxPerMmFromDpi(Double.NaN))
        assertNull(CardCalibration.pxPerMmFromDpi(-1.0))
    }

    @Test
    fun `seed edge comes from xdpi`() {
        // 400 dpi -> 15.748 px/mm -> card long edge 1348.03 px -> 1348
        assertEquals(1348, CardCalibration.seedEdgePx(400.0))
        assertNull(CardCalibration.seedEdgePx(0.0))
    }

    @Test
    fun `sanity check warns only beyond 15 percent from xdpi`() {
        val xdpi = 400.0
        val ref = 400.0 / 25.4
        assertFalse(CardCalibration.check(ref, xdpi).warn)
        assertFalse(CardCalibration.check(ref * 1.149, xdpi).warn)
        assertFalse(CardCalibration.check(ref * 0.851, xdpi).warn)
        assertTrue(CardCalibration.check(ref * 1.151, xdpi).warn)
        assertTrue(CardCalibration.check(ref * 0.849, xdpi).warn)
        assertEquals(0.10, CardCalibration.check(ref * 1.10, xdpi).deviation!!, 1e-12)
        assertEquals(-0.20, CardCalibration.check(ref * 0.80, xdpi).deviation!!, 1e-12)
    }

    @Test
    fun `sanity check cannot warn without a usable xdpi`() {
        val c = CardCalibration.check(15.0, 0.0)
        assertFalse(c.warn)
        assertNull(c.deviation)
        assertNull(c.xdpiPxPerMm)
    }

    @Test
    fun `the saved value is the card match, not xdpi`() {
        // A true 300 ppi screen whose OEM reports 360 dpi: the card value wins, and the operator is warned.
        val trueEdgePx = 1011 // 85.6 mm at 300 ppi
        val badXdpi = 360.0
        val pxPerMm = CardCalibration.pxPerMm(trueEdgePx.toDouble())
        assertEquals(1011 / 85.6, pxPerMm, 1e-12)
        assertTrue(CardCalibration.check(pxPerMm, badXdpi).warn)
    }
}
