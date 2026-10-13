package app.drishti.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Latest ambient light in lux, or null if there is no sensor or no reading yet. */
class AmbientLightSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val _lux = MutableStateFlow<Double?>(null)
    val lux: StateFlow<Double?> = _lux.asStateFlow()

    val available: Boolean get() = sensor != null

    fun start() {
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        event.values.firstOrNull()?.let { _lux.value = it.toDouble() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
