package app.drishti.input

import android.view.KeyEvent
import android.view.MotionEvent
import app.drishti.core.AxisInputs
import app.drishti.core.InputId
import app.drishti.core.RemoteAction

/**
 * Built-in remote key table (build sheet section 06). These are the defaults:
 * an operator can map a real remote on the Remote setup screen (D47), and that
 * stored mapping wins over this table. No logic lives here.
 */
object KeyMap {
    val TABLE: Map<Int, RemoteAction> = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to RemoteAction.UP,
        KeyEvent.KEYCODE_DPAD_DOWN to RemoteAction.DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to RemoteAction.LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to RemoteAction.RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER to RemoteAction.OK,
        KeyEvent.KEYCODE_ENTER to RemoteAction.OK,
        // Gamepad A (D39). Other gamepad buttons stay unmapped until the real gamepad is captured.
        KeyEvent.KEYCODE_BUTTON_A to RemoteAction.OK,
    )

    /** Keys, hat and left stick with their built-in meaning, for core/RemoteMapping (D47). */
    val DEFAULTS: Map<InputId, RemoteAction> =
        TABLE.mapKeys { (code, _) -> InputId.Key(code) } +
            AxisInputs.defaults(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y) +
            AxisInputs.defaults(MotionEvent.AXIS_X, MotionEvent.AXIS_Y)

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

    /** Remote battery below this fraction is warned about (D51). */
    const val LOW_BATTERY = 0.2

    /** Battery is re-read this often during the flow, as well as on device changes (D51). */
    const val BATTERY_REFRESH_MS = 60_000L
}
