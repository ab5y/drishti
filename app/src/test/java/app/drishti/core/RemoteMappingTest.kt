package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMappingTest {

    // Android values, repeated here because core/ has no Android imports.
    private val dpadUp = 19
    private val dpadDown = 20
    private val buttonA = 96
    private val buttonB = 97
    private val axisX = 0
    private val axisY = 1
    private val hatX = 15
    private val hatY = 16
    private val rz = 14

    private val defaults: Map<InputId, RemoteAction> =
        mapOf(InputId.Key(dpadUp) to RemoteAction.UP, InputId.Key(dpadDown) to RemoteAction.DOWN, InputId.Key(buttonA) to RemoteAction.OK) +
            AxisInputs.defaults(hatX, hatY) + AxisInputs.defaults(axisX, axisY)

    private val mapping = RemoteMapping(defaults)
    private val pad = RemoteKey("desc-1", "Mini Gamepad")

    private fun Resolved.action() = (this as? Resolved.Mapped)?.action

    @Test
    fun `with nothing stored, the built-in defaults apply`() {
        assertEquals(Resolved.Mapped(RemoteAction.UP, stored = false), mapping.resolve(null, InputId.Key(dpadUp)))
        assertEquals(Resolved.Unmapped, mapping.resolve(null, InputId.Key(buttonB)))
        assertEquals(Resolved.Unmapped, mapping.resolve(RemoteProfile(pad), InputId.Key(buttonB)))
    }

    @Test
    fun `a stored mapping wins over the defaults`() {
        val p = RemoteProfile(pad).assign(InputId.Key(dpadUp), RemoteAction.DOWN).assign(InputId.Key(buttonB), RemoteAction.LEFT)
        assertEquals(Resolved.Mapped(RemoteAction.DOWN, stored = true), mapping.resolve(p, InputId.Key(dpadUp)))
        assertEquals(Resolved.Mapped(RemoteAction.LEFT, stored = true), mapping.resolve(p, InputId.Key(buttonB)))
        // Not stored: still the default.
        assertEquals(Resolved.Mapped(RemoteAction.DOWN, stored = false), mapping.resolve(p, InputId.Key(dpadDown)))
    }

    @Test
    fun `assigning the same press again replaces it, other presses keep their action`() {
        val p = RemoteProfile(pad)
            .assign(InputId.Key(buttonB), RemoteAction.LEFT)
            .assign(InputId.Key(buttonA), RemoteAction.LEFT)
            .assign(InputId.Key(buttonB), RemoteAction.RIGHT)
        assertEquals(2, p.assignments.size)
        assertEquals(RemoteAction.RIGHT, mapping.resolve(p, InputId.Key(buttonB)).action())
        // Two buttons may both mean the same action.
        assertEquals(RemoteAction.LEFT, mapping.resolve(p, InputId.Key(buttonA)).action())
    }

    @Test
    fun `axes resolve by axis and direction, and can be remapped`() {
        assertEquals(RemoteAction.RIGHT, mapping.resolve(null, InputId.Axis(hatX, positive = true)).action())
        assertEquals(RemoteAction.LEFT, mapping.resolve(null, InputId.Axis(hatX, positive = false)).action())
        assertEquals(RemoteAction.DOWN, mapping.resolve(null, InputId.Axis(axisY, positive = true)).action())
        assertEquals(RemoteAction.UP, mapping.resolve(null, InputId.Axis(axisY, positive = false)).action())
        assertEquals(Resolved.Unmapped, mapping.resolve(null, InputId.Axis(rz, positive = true)))

        // A remote mounted sideways: hat right means Up.
        val p = RemoteProfile(pad).assign(InputId.Axis(hatX, true), RemoteAction.UP).assign(InputId.Axis(rz, false), RemoteAction.OK)
        assertEquals(RemoteAction.UP, mapping.resolve(p, InputId.Axis(hatX, true)).action())
        assertEquals(RemoteAction.OK, mapping.resolve(p, InputId.Axis(rz, false)).action())
        assertEquals(Resolved.Unmapped, mapping.resolve(p, InputId.Axis(rz, true)))
    }

    @Test
    fun `a gate press names the axis and direction it came from`() {
        assertEquals(InputId.Axis(hatX, true), AxisInputs.of(hatX, hatY, RemoteAction.RIGHT))
        assertEquals(InputId.Axis(hatX, false), AxisInputs.of(hatX, hatY, RemoteAction.LEFT))
        assertEquals(InputId.Axis(hatY, true), AxisInputs.of(hatX, hatY, RemoteAction.DOWN))
        assertEquals(InputId.Axis(hatY, false), AxisInputs.of(hatX, hatY, RemoteAction.UP))
        assertNull(AxisInputs.of(hatX, hatY, RemoteAction.OK))
        // Round trip: with nothing stored a gate press resolves to itself.
        listOf(RemoteAction.UP, RemoteAction.DOWN, RemoteAction.LEFT, RemoteAction.RIGHT).forEach {
            assertEquals(it, mapping.resolve(null, AxisInputs.of(axisX, axisY, it)!!).action())
        }
    }

    @Test
    fun `profiles are kept per remote, by descriptor plus name, and reset one at a time`() {
        val other = RemoteKey("desc-2", "Clicker")
        val renamed = RemoteKey("desc-1", "Other name")
        var all = RemoteProfiles()
            .assign(pad, InputId.Key(buttonB), RemoteAction.LEFT)
            .assign(other, InputId.Key(buttonB), RemoteAction.RIGHT)
        assertEquals(RemoteAction.LEFT, mapping.resolve(all.forRemote(pad), InputId.Key(buttonB)).action())
        assertEquals(RemoteAction.RIGHT, mapping.resolve(all.forRemote(other), InputId.Key(buttonB)).action())
        assertNull(all.forRemote(renamed))

        all = all.reset(pad)
        assertNull(all.forRemote(pad))
        assertEquals(Resolved.Unmapped, mapping.resolve(all.forRemote(pad), InputId.Key(buttonB)))
        assertEquals(RemoteAction.RIGHT, mapping.resolve(all.forRemote(other), InputId.Key(buttonB)).action())
    }

    @Test
    fun `profiles survive a save and load, and bad saved text loads as empty`() {
        val all = RemoteProfiles()
            .assign(pad, InputId.Key(buttonB), RemoteAction.LEFT)
            .assign(pad, InputId.Axis(hatX, true), RemoteAction.UP)
        assertEquals(all, RemoteProfiles.decode(all.encode()))
        assertEquals(RemoteProfiles(), RemoteProfiles.decode("not json"))
        assertEquals(RemoteProfiles(), RemoteProfiles.decode(""))
    }

    @Test
    fun `input names are readable on the setup screen`() {
        assertEquals("axis 15 +", InputId.Axis(15, true).label { null })
        assertEquals("AXIS_HAT_X -", InputId.Axis(15, false).label { "AXIS_HAT_X" })
        assertTrue(InputId.Key(96).label { "KEYCODE_BUTTON_A" }.contains("KEYCODE_BUTTON_A"))
        assertTrue(InputId.Key(96).label { "KEYCODE_BUTTON_A" }.contains("96"))
    }
}
