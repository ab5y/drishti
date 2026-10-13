package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestGateTest {

    private val display = DisplaySize.of(1080, 2400)
    private val cal = ScreenCalibration(
        deviceId = "dev-1",
        pxPerMm = 15.5,
        display = display,
        xdpi = 395.0,
        calibratedAtEpochMs = 0L,
    )

    @Test
    fun `refuses to start without a calibration`() {
        assertEquals(Readiness.NotCalibrated, TestGate.check(null, "dev-1", display))
    }

    @Test
    fun `ready with a calibration for this device and display`() {
        assertEquals(Readiness.Ready(15.5), TestGate.check(cal, "dev-1", display))
    }

    @Test
    fun `display size is orientation independent`() {
        assertEquals(display, DisplaySize.of(2400, 1080))
        assertEquals(Readiness.Ready(15.5), TestGate.check(cal, "dev-1", DisplaySize.of(2400, 1080)))
    }

    @Test
    fun `refuses if the display resolution changed since calibration`() {
        // e.g. a phone switched from FHD+ to QHD+ mode: physical px per mm changed.
        val r = TestGate.check(cal, "dev-1", DisplaySize.of(1440, 3200))
        assertTrue(r is Readiness.DisplayChanged)
    }

    @Test
    fun `refuses a calibration that belongs to another device id`() {
        assertEquals(Readiness.NotCalibrated, TestGate.check(cal, "dev-2", display))
    }
}
