package app.drishti.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import app.drishti.core.Battery
import app.drishti.core.SeenDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The device a key or motion came from, as plain values for the view model. */
data class DeviceRef(val descriptor: String?, val name: String, val external: Boolean) {
    companion object {
        fun of(device: InputDevice?): DeviceRef =
            if (device == null) DeviceRef(null, "unknown", external = false)
            else DeviceRef(device.descriptor, device.name ?: "unknown", InputDevices.isExternal(device))
    }
}

/**
 * Connected input devices, kept current by InputManager.InputDeviceListener
 * (D49). Android owns pairing and reconnecting; this only reports (D52).
 */
class InputDevices(context: Context) {
    private val inputManager = context.applicationContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val _devices = MutableStateFlow(read())
    val devices: StateFlow<List<SeenDevice>> = _devices.asStateFlow()

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refresh()
        override fun onInputDeviceRemoved(deviceId: Int) = refresh()
        override fun onInputDeviceChanged(deviceId: Int) = refresh()
    }

    fun start() = inputManager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))

    fun stop() = inputManager.unregisterInputDeviceListener(listener)

    /** Re-reads every device, including battery (D51). */
    fun refresh() {
        _devices.value = read()
    }

    private fun read(): List<SeenDevice> =
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { !it.isVirtual }
            .map { d -> SeenDevice(d.descriptor, d.name ?: "unknown", isExternal(d), isRemoteLike(d), battery(d)) }

    companion object {
        private val REMOTE_SOURCES = listOf(
            InputDevice.SOURCE_GAMEPAD,
            InputDevice.SOURCE_JOYSTICK,
            InputDevice.SOURCE_DPAD,
            InputDevice.SOURCE_KEYBOARD,
        )

        fun isRemoteLike(d: InputDevice): Boolean = REMOTE_SOURCES.any { (d.sources and it) == it }

        /**
         * InputDevice.isExternal() is public from API 29. Below that the same
         * hidden method is read by reflection; if that fails the device counts
         * as not external, so it is never filtered out (D48).
         */
        fun isExternal(d: InputDevice): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                d.isExternal
            } else {
                runCatching { InputDevice::class.java.getMethod("isExternal").invoke(d) as Boolean }.getOrDefault(false)
            }

        /** Only what Android reports; API 31+ (D51). */
        fun battery(d: InputDevice): Battery =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val b = d.batteryState
                Battery.of(b.isPresent, b.capacity)
            } else {
                Battery.NotReported
            }
    }
}
