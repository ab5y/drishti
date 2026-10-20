package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteStatusTest {

    private val low = 0.2
    private val station = StationRemote("desc-remote", "Clicker")

    private fun dev(
        descriptor: String,
        external: Boolean = true,
        remoteLike: Boolean = true,
        battery: Battery = Battery.NotReported,
        name: String = descriptor,
    ) = SeenDevice(descriptor, name, external, remoteLike, battery)

    // ---- station remote filter (D48) ---------------------------------------------------

    @Test
    fun `with no station remote every device is accepted`() {
        assertTrue(RemoteFilter.accepts(null, "any", external = true, duringTest = true))
        assertTrue(RemoteFilter.accepts(null, null, external = false, duringTest = true))
    }

    @Test
    fun `during a test only the station remote and the phone's own keys are accepted`() {
        assertTrue(RemoteFilter.accepts(station, "desc-remote", external = true, duringTest = true))
        assertFalse(RemoteFilter.accepts(station, "desc-other", external = true, duringTest = true))
        assertFalse(RemoteFilter.accepts(station, null, external = true, duringTest = true))
        // Built-in keys, virtual devices and adb input are not external.
        assertTrue(RemoteFilter.accepts(station, "gpio-keys", external = false, duringTest = true))
        assertTrue(RemoteFilter.accepts(station, null, external = false, duringTest = true))
    }

    @Test
    fun `outside a test nothing is filtered`() {
        assertTrue(RemoteFilter.accepts(station, "desc-other", external = true, duringTest = false))
    }

    // ---- connected (D49) -------------------------------------------------------------

    @Test
    fun `with a station remote, connected means that remote is present`() {
        assertTrue(RemoteStatus.of(station, listOf(dev("desc-remote"))).connected)
        assertFalse(RemoteStatus.of(station, listOf(dev("desc-other"))).connected)
        assertFalse(RemoteStatus.of(station, emptyList()).connected)
    }

    @Test
    fun `with no station remote, connected means any external remote-like device`() {
        assertTrue(RemoteStatus.of(null, listOf(dev("a"))).connected)
        assertFalse(RemoteStatus.of(null, listOf(dev("gpio-keys", external = false))).connected)
        // An external device that is not a gamepad, D-pad or keyboard (a fingerprint reader, a mouse).
        assertFalse(RemoteStatus.of(null, listOf(dev("mouse", remoteLike = false))).connected)
        assertFalse(RemoteStatus.of(null, emptyList()).connected)
    }

    // ---- battery (D51) ---------------------------------------------------------------

    @Test
    fun `battery is read only from what the device reports`() {
        assertEquals(Battery.Level(0.5), Battery.of(present = true, capacity = 0.5f))
        assertEquals(Battery.NotReported, Battery.of(present = false, capacity = 0.5f))
        assertEquals(Battery.NotReported, Battery.of(present = true, capacity = Float.NaN))
        assertEquals(Battery.NotReported, Battery.of(present = true, capacity = -1f))
        assertEquals(Battery.NotReported, Battery.of(present = true, capacity = 1.5f))
        assertEquals(Battery.Level(0.0), Battery.of(present = true, capacity = 0f))
    }

    @Test
    fun `with no station remote the lowest reported level is kept`() {
        val s = RemoteStatus.of(
            null,
            listOf(dev("a", battery = Battery.Level(0.8)), dev("b", battery = Battery.NotReported), dev("c", battery = Battery.Level(0.3))),
        )
        assertEquals(Battery.Level(0.3), s.battery)
        assertEquals(Battery.NotReported, RemoteStatus.of(null, listOf(dev("a"))).battery)
        assertNull(RemoteStatus.of(null, emptyList()).battery)
    }

    @Test
    fun `with a station remote only its battery counts`() {
        val s = RemoteStatus.of(
            station,
            listOf(dev("desc-remote", battery = Battery.Level(0.9)), dev("desc-other", battery = Battery.Level(0.05))),
        )
        assertEquals(Battery.Level(0.9), s.battery)
    }

    @Test
    fun `chip says connected or reconnect, with battery, warning below 20 percent`() {
        assertEquals("Remote ✗ - reconnect", RemoteStatus.of(station, emptyList()).chipText(low))
        assertEquals("Remote ✓ · battery 64%", RemoteStatus.of(null, listOf(dev("a", battery = Battery.Level(0.64)))).chipText(low))
        assertEquals("Remote ✓ · battery LOW 15%", RemoteStatus.of(null, listOf(dev("a", battery = Battery.Level(0.15)))).chipText(low))
        // Exactly 20% is not low.
        assertEquals("Remote ✓ · battery 20%", RemoteStatus.of(null, listOf(dev("a", battery = Battery.Level(0.2)))).chipText(low))
        assertEquals("Remote ✓ · battery not reported", RemoteStatus.of(null, listOf(dev("a"))).chipText(low))
    }

    @Test
    fun `remote check battery line never guesses a level`() {
        fun line(b: Battery) = RemoteStatus.of(null, listOf(dev("a", battery = b))).batteryText(low)
        assertEquals("Remote battery: 64%.", line(Battery.Level(0.64)))
        assertTrue(line(Battery.Level(0.1)).startsWith("Remote battery LOW: 10%."))
        assertTrue(line(Battery.NotReported).startsWith("Remote battery: not reported"))
        assertEquals("No remote connected.", RemoteStatus.of(station, emptyList()).batteryText(low))
        assertTrue(RemoteStatus.of(null, listOf(dev("a", battery = Battery.Level(0.1)))).lowBattery(low))
        assertFalse(RemoteStatus.of(null, listOf(dev("a"))).lowBattery(low))
    }
}
