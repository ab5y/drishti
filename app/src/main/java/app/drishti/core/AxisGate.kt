package app.drishti.core

import kotlin.math.abs

/** What a joystick or hat movement amounts to. */
sealed interface AxisEvent {
    data class Press(val action: RemoteAction) : AxisEvent

    /** Both axes equal and past the trigger: no direction can be chosen. */
    data object Diagonal : AxisEvent
}

/**
 * Turns one two-axis control (a hat switch or a stick) into presses (D40).
 * Pure Kotlin: raw axis values in, at most one press per push out.
 *
 * - The axis with the larger magnitude is dominant; the weaker one is ignored.
 * - A press fires when the dominant axis passes [triggerAt] (+X right, -Y up).
 * - After a push, both axes must fall below [releaseBelow] before the next one,
 *   so a held or drifting stick cannot repeat.
 * - Presses go through [debouncer], which the keys share.
 */
class AxisGate(
    private val debouncer: KeyDebouncer,
    private val triggerAt: Double,
    private val releaseBelow: Double,
) {
    init {
        validateThresholds(triggerAt, releaseBelow)
    }

    private var armed = true

    fun update(x: Double, y: Double, eventTimeMs: Long): AxisEvent? {
        val dominant = maxOf(magnitude(x), magnitude(y))
        if (!armed) {
            if (dominant < releaseBelow) armed = true
            return null
        }
        val event = directionOf(x, y, triggerAt) ?: return null
        // This push is used up whatever happens next, even if the debouncer drops it.
        armed = false
        if (event is AxisEvent.Press && !debouncer.accept(eventTimeMs)) return null
        return event
    }

    companion object {
        /** What this position means on its own, ignoring release and debounce (for the key capture screen). */
        fun directionOf(x: Double, y: Double, triggerAt: Double): AxisEvent? {
            val ax = magnitude(x)
            val ay = magnitude(y)
            if (maxOf(ax, ay) <= triggerAt) return null
            if (ax == ay) return AxisEvent.Diagonal
            val action = if (ax > ay) {
                if (x > 0) RemoteAction.RIGHT else RemoteAction.LEFT
            } else {
                if (y > 0) RemoteAction.DOWN else RemoteAction.UP
            }
            return AxisEvent.Press(action)
        }
    }
}

/**
 * One axis with the same trigger and release rule as [AxisGate], for axes
 * that have no mapping: reports each push past [triggerAt] once (D44).
 *
 * Unlike [AxisGate] it starts disarmed: an unknown axis may rest at +-1 (some
 * triggers do), so it must be seen below [releaseBelow] before it can fire.
 */
class AxisLatch(private val triggerAt: Double, private val releaseBelow: Double) {
    init {
        validateThresholds(triggerAt, releaseBelow)
    }

    private var armed = false

    /** True when this value starts a new push past the trigger. */
    fun update(value: Double): Boolean {
        val v = magnitude(value)
        if (!armed) {
            if (v < releaseBelow) armed = true
            return false
        }
        if (v <= triggerAt) return false
        armed = false
        return true
    }
}

private fun magnitude(v: Double): Double = if (v.isFinite()) abs(v) else 0.0

private fun validateThresholds(triggerAt: Double, releaseBelow: Double) {
    require(triggerAt > 0 && triggerAt <= 1) { "triggerAt must be in (0, 1], was $triggerAt" }
    require(releaseBelow > 0 && releaseBelow < triggerAt) { "releaseBelow must be in (0, triggerAt), was $releaseBelow" }
}
