package app.drishti.input

import android.view.InputDevice
import android.view.MotionEvent

/** One axis value from a joystick motion event. */
data class AxisValue(val axis: Int, val name: String, val value: Double)

/**
 * One sample of a joystick motion event: the hat, the left stick and every
 * other axis the device reports. Batched (historical) samples come first, so a
 * quick push and release inside one event is not lost.
 */
data class MotionFrame(
    val eventTimeMs: Long,
    val hatX: Double,
    val hatY: Double,
    val stickX: Double,
    val stickY: Double,
    /** Axes with no mapping (right stick, triggers, ...). */
    val others: List<AxisValue>,
)

/** Reads gamepad motion into plain values for core/AxisGate (D40). No decisions here. */
object GamepadMotion {
    private val MAPPED = setOf(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_X, MotionEvent.AXIS_Y)

    fun isJoystickMove(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.actionMasked == MotionEvent.ACTION_MOVE

    fun frames(event: MotionEvent): List<MotionFrame> {
        val otherAxes = event.device?.motionRanges
            ?.filter { it.isFromSource(InputDevice.SOURCE_JOYSTICK) && it.axis !in MAPPED }
            ?.map { it.axis }
            ?.distinct()
            .orEmpty()

        fun frame(time: Long, value: (Int) -> Float) = MotionFrame(
            eventTimeMs = time,
            hatX = value(MotionEvent.AXIS_HAT_X).toDouble(),
            hatY = value(MotionEvent.AXIS_HAT_Y).toDouble(),
            stickX = value(MotionEvent.AXIS_X).toDouble(),
            stickY = value(MotionEvent.AXIS_Y).toDouble(),
            others = otherAxes.map { AxisValue(it, MotionEvent.axisToString(it), value(it).toDouble()) },
        )

        return (0 until event.historySize).map { i ->
            frame(event.getHistoricalEventTime(i)) { axis -> event.getHistoricalAxisValue(axis, i) }
        } + frame(event.eventTime) { axis -> event.getAxisValue(axis) }
    }
}
