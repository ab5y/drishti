package app.drishti.input

import android.view.KeyEvent
import app.drishti.core.InputId
import app.drishti.core.RemoteAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyMapTest {
    private val phoneKeys = listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_BACK)

    @Test
    fun `volume and back are not remote keys by default (D67)`() {
        phoneKeys.forEach { code ->
            assertFalse("$code in TABLE", code in KeyMap.TABLE)
            assertFalse("$code in DEFAULTS", InputId.Key(code) in KeyMap.DEFAULTS)
        }
    }

    @Test
    fun `the built-in keys are the D-pad, Enter and gamepad A`() {
        assertEquals(
            mapOf(
                KeyEvent.KEYCODE_DPAD_UP to RemoteAction.UP,
                KeyEvent.KEYCODE_DPAD_DOWN to RemoteAction.DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT to RemoteAction.LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT to RemoteAction.RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER to RemoteAction.OK,
                KeyEvent.KEYCODE_ENTER to RemoteAction.OK,
                KeyEvent.KEYCODE_BUTTON_A to RemoteAction.OK,
            ),
            KeyMap.TABLE,
        )
    }
}
