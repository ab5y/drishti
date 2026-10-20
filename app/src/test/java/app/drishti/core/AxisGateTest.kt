package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisGateTest {

    private fun gate(debouncer: KeyDebouncer = KeyDebouncer(250)) = AxisGate(debouncer, triggerAt = 0.6, releaseBelow = 0.3)

    /** Feeds samples 1 s apart (so the debouncer never interferes) and returns the presses. */
    private fun AxisGate.feed(vararg xy: Pair<Double, Double>, startMs: Long = 0, stepMs: Long = 1_000): List<AxisEvent> =
        xy.mapIndexedNotNull { i, (x, y) -> update(x, y, startMs + i * stepMs) }

    private fun press(a: RemoteAction) = AxisEvent.Press(a)

    @Test
    fun `each direction follows Android's axis signs`() {
        assertEquals(listOf(press(RemoteAction.RIGHT)), gate().feed(1.0 to 0.0))
        assertEquals(listOf(press(RemoteAction.LEFT)), gate().feed(-1.0 to 0.0))
        assertEquals(listOf(press(RemoteAction.UP)), gate().feed(0.0 to -1.0))
        assertEquals(listOf(press(RemoteAction.DOWN)), gate().feed(0.0 to 1.0))
    }

    @Test
    fun `fires only when the dominant axis passes 0_6`() {
        assertEquals(emptyList<AxisEvent>(), gate().feed(0.6 to 0.0, 0.0 to -0.6, 0.59 to 0.59))
        assertEquals(listOf(press(RemoteAction.RIGHT)), gate().feed(0.61 to 0.0))
    }

    @Test
    fun `diagonals use the dominant axis and ignore the weaker one`() {
        assertEquals(listOf(press(RemoteAction.RIGHT)), gate().feed(0.9 to 0.5))
        assertEquals(listOf(press(RemoteAction.RIGHT)), gate().feed(0.65 to -0.64))
        assertEquals(listOf(press(RemoteAction.UP)), gate().feed(-0.4 to -0.8))
        assertEquals(listOf(press(RemoteAction.LEFT)), gate().feed(-0.95 to 0.9))
        assertEquals(listOf(press(RemoteAction.DOWN)), gate().feed(0.7 to 0.71))
        // The weaker axis alone being high is irrelevant: (0.2, 0.7) is DOWN, not RIGHT.
        assertEquals(listOf(press(RemoteAction.DOWN)), gate().feed(0.2 to 0.7))
    }

    @Test
    fun `an exact diagonal fires no direction and still needs a release`() {
        val g = gate()
        assertEquals(listOf(AxisEvent.Diagonal), g.feed(1.0 to 1.0))
        assertEquals(emptyList<AxisEvent>(), g.feed(1.0 to 0.0, startMs = 10_000)) // not released yet
        assertEquals(listOf(press(RemoteAction.RIGHT)), g.feed(0.0 to 0.0, 1.0 to 0.0, startMs = 20_000))
    }

    @Test
    fun `hold gives one press, release re-arms`() {
        val g = gate()
        val held = g.feed(0.0 to 0.0, 0.8 to 0.0, 0.9 to 0.0, 1.0 to 0.0, 1.0 to 0.0, 0.95 to 0.1)
        assertEquals(listOf(press(RemoteAction.RIGHT)), held)
        assertEquals(listOf(press(RemoteAction.RIGHT)), g.feed(0.1 to 0.0, 0.9 to 0.0, startMs = 100_000))
    }

    @Test
    fun `release means both axes below 0_3`() {
        val g = gate()
        assertEquals(listOf(press(RemoteAction.RIGHT)), g.feed(1.0 to 0.0))
        // X back to centre, Y drifted to 0.35: not released, so the next push does not fire.
        assertEquals(emptyList<AxisEvent>(), g.feed(0.0 to 0.35, 0.0 to 0.9, startMs = 10_000))
        assertEquals(listOf(press(RemoteAction.DOWN)), g.feed(0.29 to 0.29, 0.0 to 0.9, startMs = 20_000))
    }

    @Test
    fun `slow drift never fires, and cannot repeat a press`() {
        val g = gate()
        val drift = (0..59).map { (it / 100.0) to 0.0 } // 0.00 .. 0.59
        assertEquals(emptyList<AxisEvent>(), g.feed(*drift.toTypedArray()))
        assertEquals(listOf(press(RemoteAction.RIGHT)), g.feed(0.61 to 0.0, startMs = 100_000))
        // Drifts down to 0.31 and back up: never released, so no second press.
        val back = (61 downTo 31).map { (it / 100.0) to 0.0 } + (31..100).map { (it / 100.0) to 0.0 }
        assertEquals(emptyList<AxisEvent>(), g.feed(*back.toTypedArray(), startMs = 200_000))
    }

    @Test
    fun `jitter around the trigger threshold gives one press`() {
        val jitter = List(20) { i -> (if (i % 2 == 0) 0.59 else 0.61) to 0.02 }
        assertEquals(listOf(press(RemoteAction.RIGHT)), gate().feed(*jitter.toTypedArray()))
    }

    @Test
    fun `jitter around the release threshold re-arms only below it`() {
        val g = gate()
        assertEquals(listOf(press(RemoteAction.LEFT)), g.feed(-1.0 to 0.0))
        val nearRelease = List(10) { i -> (if (i % 2 == 0) -0.31 else -0.30) to 0.0 }
        assertEquals(emptyList<AxisEvent>(), g.feed(*nearRelease.toTypedArray(), -0.9 to 0.0, startMs = 10_000))
        assertEquals(listOf(press(RemoteAction.LEFT)), g.feed(-0.29 to 0.0, -0.9 to 0.0, startMs = 50_000))
    }

    @Test
    fun `hat switch presses in turn`() {
        val g = gate()
        val hat = g.feed(
            0.0 to 0.0, 0.0 to -1.0, 0.0 to 0.0, // up
            0.0 to 1.0, 0.0 to 0.0,              // down
            -1.0 to 0.0, 0.0 to 0.0,             // left
            1.0 to 0.0, 1.0 to 0.0, 0.0 to 0.0,  // right, held for two samples
        )
        assertEquals(
            listOf(RemoteAction.UP, RemoteAction.DOWN, RemoteAction.LEFT, RemoteAction.RIGHT).map(::press),
            hat,
        )
    }

    @Test
    fun `hat switch straight from one direction to another needs a centre in between`() {
        val g = gate()
        assertEquals(listOf(press(RemoteAction.UP)), g.feed(0.0 to -1.0, 1.0 to 0.0))
    }

    @Test
    fun `debounce applies and is shared with keys`() {
        val shared = KeyDebouncer(250)
        val g = gate(shared)
        assertTrue(shared.accept(1_000)) // a D-pad key-down for the same press
        assertNull(g.update(0.0, -1.0, 1_050)) // the hat event for it is dropped
        assertNull(g.update(0.0, -1.0, 1_400)) // still held: consumed, no late press
        assertNull(g.update(0.0, 0.0, 1_500))
        assertEquals(press(RemoteAction.UP), g.update(0.0, -1.0, 1_600))
        // Two fast presses less than 250 ms apart: the second is dropped.
        assertNull(g.update(0.0, 0.0, 1_650))
        assertNull(g.update(0.0, 1.0, 1_700))
    }

    @Test
    fun `non-finite values count as centred`() {
        val g = gate()
        assertEquals(emptyList<AxisEvent>(), g.feed(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY))
        assertEquals(listOf(press(RemoteAction.RIGHT)), g.feed(1.0 to Double.NaN, startMs = 10_000))
    }

    @Test
    fun `single-axis latch reports each push past the trigger once`() {
        val l = AxisLatch(triggerAt = 0.6, releaseBelow = 0.3)
        val fired = listOf(0.0, 0.5, 0.7, 1.0, 0.4, 0.8, 0.2, -0.9, -0.1).map { l.update(it) }
        assertEquals(listOf(false, false, true, false, false, false, false, true, false), fired)
    }

    @Test
    fun `single-axis latch never fires for an axis resting at full scale`() {
        val l = AxisLatch(triggerAt = 0.6, releaseBelow = 0.3)
        assertEquals(listOf(false, false, false), listOf(-1.0, -1.0, -0.9).map { l.update(it) })
        assertEquals(listOf(false, true), listOf(0.0, 0.9).map { l.update(it) })
    }

    @Test
    fun `direction of a position, without state`() {
        assertEquals(press(RemoteAction.RIGHT), AxisGate.directionOf(0.9, 0.5, 0.6))
        assertEquals(press(RemoteAction.UP), AxisGate.directionOf(0.0, -1.0, 0.6))
        assertEquals(AxisEvent.Diagonal, AxisGate.directionOf(-1.0, 1.0, 0.6))
        assertNull(AxisGate.directionOf(0.6, 0.2, 0.6))
    }

    @Test
    fun `thresholds are validated`() {
        listOf(0.3 to 0.3, 0.6 to 0.7, 1.2 to 0.3, 0.6 to 0.0).forEach { (t, r) ->
            var threw = false
            try {
                AxisGate(KeyDebouncer(0), t, r)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("trigger $t release $r", threw)
        }
        assertFalse(AxisLatch(0.6, 0.3).update(0.6))
    }
}
