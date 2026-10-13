package app.drishti.input

import android.view.KeyEvent
import app.drishti.core.RemoteAction

/**
 * BLE HID remote key table (build sheet section 06). This file is the only
 * place to edit when mapping a real remote: use the key capture screen to read
 * its keycodes, then change the table below. No logic lives here.
 */
object KeyMap {
    val TABLE: Map<Int, RemoteAction> = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to RemoteAction.UP,
        KeyEvent.KEYCODE_VOLUME_UP to RemoteAction.UP,
        KeyEvent.KEYCODE_DPAD_DOWN to RemoteAction.DOWN,
        KeyEvent.KEYCODE_VOLUME_DOWN to RemoteAction.DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to RemoteAction.LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to RemoteAction.RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER to RemoteAction.OK,
        KeyEvent.KEYCODE_ENTER to RemoteAction.OK,
    )

    /** A second press within this many ms of the last accepted one is ignored. */
    const val DEBOUNCE_MS = 250L
}
