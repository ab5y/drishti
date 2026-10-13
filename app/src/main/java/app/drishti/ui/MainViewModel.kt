package app.drishti.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.drishti.core.AppConfig
import app.drishti.core.CardCalibration
import app.drishti.core.ConfigException
import app.drishti.core.Dir
import app.drishti.core.DisplaySize
import app.drishti.core.Readiness
import app.drishti.core.ScreenCalibration
import app.drishti.core.TestGate
import app.drishti.data.CalibrationStore
import app.drishti.data.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { HOME, CALIBRATION, DEBUG }

sealed interface UiState {
    data object Loading : UiState
    /** config.json is invalid: the app refuses to test until it is fixed. */
    data class ConfigError(val errors: List<String>) : UiState
    data class Ready(
        val config: AppConfig,
        val screen: Screen = Screen.HOME,
        val calibration: ScreenCalibration? = null,
        val readiness: Readiness = Readiness.NotCalibrated,
        val lastKey: Dir? = null,
        val notice: String? = null,
    ) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = CalibrationStore(app)
    private val display = MutableStateFlow<DisplaySize?>(null)
    private var deviceId: String? = null

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val config = try {
            ConfigRepository(app).load()
        } catch (e: ConfigException) {
            null.also { _state.value = UiState.ConfigError(e.errors) }
        }
        if (config != null) {
            _state.value = UiState.Ready(config)
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
        }
    }

    fun setDisplay(size: DisplaySize) {
        display.value = size
    }

    fun open(screen: Screen) = updateReady { s ->
        if (screen == Screen.DEBUG && s.readiness !is Readiness.Ready) {
            s.copy(notice = "Calibrate the screen first.")
        } else {
            s.copy(screen = screen, notice = null)
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

    /** The test flow refuses to start without a valid card calibration. */
    fun startTest() = updateReady { s ->
        val notice = when (val r = s.readiness) {
            is Readiness.Ready -> "Calibrated at %.3f px/mm. The test flow is not built yet.".format(r.pxPerMm)
            Readiness.NotCalibrated -> "Cannot start: calibrate the screen with a card first."
            is Readiness.DisplayChanged -> "Cannot start: display resolution changed since calibration. Recalibrate."
        }
        s.copy(notice = notice)
    }

    fun onResponse(dir: Dir) = updateReady { it.copy(lastKey = dir) }

    private fun updateReady(f: (UiState.Ready) -> UiState.Ready) {
        _state.update { s -> if (s is UiState.Ready) f(s) else s }
    }
}
