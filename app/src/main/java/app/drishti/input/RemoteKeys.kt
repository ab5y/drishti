package app.drishti.input

import android.view.KeyEvent
import app.drishti.core.Dir

/**
 * BLE HID remote -> response (build sheet section 06). Real keycodes vary by
 * remote; confirm this table on the actual unit.
 */
object RemoteKeys {
    fun toDir(keyCode: Int): Dir? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> Dir.UP
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> Dir.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Dir.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Dir.UNSURE
        else -> null
    }
}
