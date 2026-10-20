package app.drishti.core

import kotlin.math.roundToInt

/** The station remote chosen on Remote setup (D48). */
data class StationRemote(val descriptor: String, val name: String)

/** Which presses count during a test (D48). */
object RemoteFilter {
    /**
     * With a station remote set, presses from other external devices are
     * ignored during a test. Devices that are not external (the phone's own
     * keys, virtual devices, adb) are always accepted.
     */
    fun accepts(station: StationRemote?, descriptor: String?, external: Boolean, duringTest: Boolean): Boolean =
        station == null || !duringTest || !external || descriptor == station.descriptor
}

/** A battery reading as Android reports it; never estimated (D51). */
sealed interface Battery {
    data class Level(val fraction: Double) : Battery
    data object NotReported : Battery

    companion object {
        /** From InputDevice.getBatteryState(): isPresent and getCapacity (0..1, NaN if unknown). */
        fun of(present: Boolean, capacity: Float): Battery =
            if (present && capacity.isFinite() && capacity in 0f..1f) Level(capacity.toDouble()) else NotReported
    }
}

/** One connected input device, as the input layer sees it. */
data class SeenDevice(
    val descriptor: String,
    val name: String,
    val external: Boolean,
    /** Reports gamepad, joystick, D-pad or keyboard input. */
    val remoteLike: Boolean,
    val battery: Battery,
)

/** Whether a remote is connected, and its battery (D49, D51). */
data class RemoteStatus(
    val connected: Boolean,
    /** Null when nothing is connected. */
    val battery: Battery?,
) {
    fun lowBattery(lowAt: Double): Boolean = (battery as? Battery.Level)?.let { it.fraction < lowAt } == true

    fun chipText(lowAt: Double): String {
        if (!connected) return "Remote ✗ - reconnect"
        return "Remote ✓ · battery " + when (val b = battery) {
            is Battery.Level -> (if (lowBattery(lowAt)) "LOW " else "") + pct(b)
            Battery.NotReported, null -> "not reported"
        }
    }

    /** The battery line on the remote check. */
    fun batteryText(lowAt: Double): String {
        if (!connected) return "No remote connected."
        return when (val b = battery) {
            is Battery.Level ->
                if (lowBattery(lowAt)) "Remote battery LOW: ${pct(b)}. Replace or charge it before screening."
                else "Remote battery: ${pct(b)}."
            Battery.NotReported, null ->
                "Remote battery: not reported (needs Android 12 or later and a remote that reports it)."
        }
    }

    companion object {
        /**
         * With a station remote, only that remote counts. Without one, any
         * external remote-like device counts, and the lowest battery is kept.
         */
        fun of(station: StationRemote?, devices: List<SeenDevice>): RemoteStatus {
            val candidates = if (station != null) {
                devices.filter { it.descriptor == station.descriptor }
            } else {
                devices.filter { it.external && it.remoteLike }
            }
            if (candidates.isEmpty()) return RemoteStatus(connected = false, battery = null)
            val lowest = candidates.mapNotNull { it.battery as? Battery.Level }.minByOrNull { it.fraction }
            return RemoteStatus(connected = true, battery = lowest ?: Battery.NotReported)
        }

        private fun pct(b: Battery.Level) = "${(b.fraction * 100).roundToInt()}%"
    }
}
