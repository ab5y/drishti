package app.drishti.input

import android.view.KeyEvent
import app.drishti.core.RemoteAction

/**
 * BLE HID remote key table (build sheet section 06). This file is the only
 * place to edit when mapping a real remote: use the key capture screen to read
 * its keycodes and axes, then change the table below. No logic lives here.
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
        // Gamepad A (D39). Other gamepad buttons stay unmapped until the real gamepad is captured.
        KeyEvent.KEYCODE_BUTTON_A to RemoteAction.OK,
    )

    /** A second press within this many ms of the last accepted one is ignored. */
    const val DEBOUNCE_MS = 250L

    /** Hat and stick: a press fires when the dominant axis passes this (D40). */
    const val AXIS_TRIGGER = 0.6

    /** Hat and stick: both axes must fall below this before the next press (D40). */
    const val AXIS_RELEASE = 0.3

    /** Remote presses on a prompt or the light warning count only after it has been up this long (D41). */
    const val PROMPT_MIN_SHOW_MS = 800L

    /** How long an "Unmapped" or "not used" notice stays on screen (D44). */
    const val NOTICE_MS = 3_000L
}
