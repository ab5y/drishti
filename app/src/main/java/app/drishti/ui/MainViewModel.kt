package app.drishti.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.drishti.core.AppConfig
import app.drishti.core.CardCalibration
import app.drishti.core.ConfigException
import app.drishti.core.DisplaySize
import app.drishti.core.FixedDistance
import app.drishti.core.FlowScreen
import app.drishti.core.KeyDebouncer
import app.drishti.core.Readiness
import app.drishti.core.RemoteAction
import app.drishti.core.ScreenCalibration
import app.drishti.core.ScreeningFlow
import app.drishti.core.TestGate
import app.drishti.data.CalibrationStore
import app.drishti.data.ConfigRepository
import app.drishti.input.AmbientLightSensor
import app.drishti.input.KeyMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class Screen { HOME, CALIBRATION, DEBUG, FLOW, KEY_CAPTURE }

/** One raw key press, for mapping a real remote. */
data class CapturedKey(
    val keyCode: Int,
    val keyName: String,
    val scanCode: Int,
    val deviceName: String,
    val mapsTo: RemoteAction?,
)

sealed interface UiState {
    data object Loading : UiState
    /** config.json is invalid: the app refuses to test until it is fixed. */
    data class ConfigError(val errors: List<String>) : UiState
    data class Ready(
        val config: AppConfig,
        val screen: Screen = Screen.HOME,
        val calibration: ScreenCalibration? = null,
        val readiness: Readiness = Readiness.NotCalibrated,
        val lastKey: RemoteAction? = null,
        val notice: String? = null,
        val flow: FlowScreen? = null,
        val registerErrors: List<String> = emptyList(),
        val lux: Double? = null,
        val lightSensorAvailable: Boolean = false,
        val capturedKeys: List<CapturedKey> = emptyList(),
    ) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = CalibrationStore(app)
    private val light = AmbientLightSensor(app)
    private val display = MutableStateFlow<DisplaySize?>(null)
    private val debouncer = KeyDebouncer(KeyMap.DEBOUNCE_MS)
    private var deviceId: String? = null
    private var flow: ScreeningFlow? = null

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val config = try {
            ConfigRepository(app).load()
        } catch (e: ConfigException) {
            null.also { _state.value = UiState.ConfigError(e.errors) }
        }
        if (config != null) {
            _state.value = UiState.Ready(config, lightSensorAvailable = light.available)
            viewModelScope.launch {
                val id = store.deviceId().also { deviceId = it }
                combine(store.calibration, display.filterNotNull()) { cal, d -> cal to TestGate.check(cal, id, d) }
                    .collect { (cal, readiness) ->
                        updateReady { s ->
                            // Leave the debug screen if calibration stops being valid.
                            val screen = if (s.screen == Screen.DEBUG && readiness !is Readiness.Ready) Screen.HOME else s.screen
                            s.copy(calibration = cal, readiness = readiness, screen = screen)
                        }
                    }
            }
            viewModelScope.launch { light.lux.collect { lux -> updateReady { it.copy(lux = lux) } } }
        }
    }

    fun setDisplay(size: DisplaySize) {
        display.value = size
    }

    fun open(screen: Screen) = updateReady { s ->
        if (screen == Screen.DEBUG && s.readiness !is Readiness.Ready) {
            s.copy(notice = "Calibrate the screen first.")
        } else {
            s.copy(screen = screen, notice = null, capturedKeys = if (screen == Screen.KEY_CAPTURE) emptyList() else s.capturedKeys)
        }
    }

    fun saveCalibration(matchedEdgePx: Int, xdpi: Double) {
        val id = deviceId ?: return
        val d = display.value ?: return
        val cal = ScreenCalibration(
            deviceId = id,
            pxPerMm = CardCalibration.pxPerMm(matchedEdgePx.toDouble()),
            display = d,
            xdpi = xdpi,
            calibratedAtEpochMs = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            store.save(cal)
            updateReady { it.copy(screen = Screen.HOME, notice = "Saved: %.3f px/mm".format(cal.pxPerMm)) }
        }
    }

    // ---- screening flow ------------------------------------------------------------------

    /** The test flow refuses to start without a valid card calibration. */
    fun startTest() {
        val s = _state.value as? UiState.Ready ?: return
        val r = s.readiness
        if (r !is Readiness.Ready) {
            updateReady {
                it.copy(
                    notice = if (r is Readiness.DisplayChanged) {
                        "Cannot start: display resolution changed since calibration. Recalibrate."
                    } else {
                        "Cannot start: calibrate the screen with a card first."
                    },
                )
            }
            return
        }
        flow = ScreeningFlow(s.config, r.pxPerMm, FixedDistance.from(s.config), Random.Default)
        light.start()
        updateReady { it.copy(screen = Screen.FLOW, notice = null, registerErrors = emptyList()) }
        publishFlow()
    }

    fun setTestViewport(widthPx: Int, heightPx: Int) {
        flow?.setViewport(widthPx, heightPx)
    }

    fun register(token: String, age: String) {
        val f = flow ?: return
        val lux = light.lux.value
        light.stop() // read once, as a baseline for this screening
        val errors = f.register(token, age, lux)
        if (errors.isNotEmpty()) light.start()
        updateReady { it.copy(registerErrors = errors) }
        publishFlow()
    }

    /** On-screen equivalent of the remote's OK key, for the operator. */
    fun flowOk() {
        flow?.onAction(RemoteAction.OK)
        publishFlow()
    }

    fun pollFlow() {
        flow?.poll()
        publishFlow()
    }

    fun exitFlow() {
        flow = null
        light.stop()
        updateReady { it.copy(screen = Screen.HOME, flow = null) }
    }

    private fun publishFlow() = updateReady { it.copy(flow = flow?.screen) }

    // ---- keys --------------------------------------------------------------------------

    /**
     * All key events from the activity. Returns true if consumed. Key-down with
     * repeat count 0 only, debounced; ignored while paused for distance (the flow
     * ignores them there).
     */
    fun onKey(keyCode: Int, keyName: String, scanCode: Int, deviceName: String, isDown: Boolean, repeatCount: Int, eventTimeMs: Long): Boolean {
        val s = _state.value as? UiState.Ready ?: return false
        val action = KeyMap.TABLE[keyCode]
        if (s.screen == Screen.KEY_CAPTURE) {
            if (isDown && repeatCount == 0) {
                val k = CapturedKey(keyCode, keyName, scanCode, deviceName, action)
                updateReady { it.copy(capturedKeys = (listOf(k) + it.capturedKeys).take(MAX_CAPTURED)) }
            }
            return true // capture everything, including back and volume
        }
        if (action == null) return false
        if (!isDown || repeatCount != 0 || !debouncer.accept(eventTimeMs)) return true
        if (s.screen == Screen.FLOW) {
            flow?.onAction(action)
            publishFlow()
        }
        updateReady { it.copy(lastKey = action) }
        return true
    }

    override fun onCleared() {
        light.stop()
    }

    private fun updateReady(f: (UiState.Ready) -> UiState.Ready) {
        _state.update { s -> if (s is UiState.Ready) f(s) else s }
    }

    private companion object {
        const val MAX_CAPTURED = 50
    }
}
