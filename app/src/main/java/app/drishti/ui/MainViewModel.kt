package app.drishti.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.drishti.core.AppConfig
import app.drishti.core.ConfigException
import app.drishti.core.Dir
import app.drishti.data.CalibrationStore
import app.drishti.data.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Loading : UiState
    /** config.json is invalid: the app refuses to test until it is fixed. */
    data class ConfigError(val errors: List<String>) : UiState
    data class Ready(val config: AppConfig, val pxPerMm: Double?, val lastKey: Dir?) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val calibration = CalibrationStore(app)

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val config = try {
            ConfigRepository(app).load()
        } catch (e: ConfigException) {
            null.also { _state.value = UiState.ConfigError(e.errors) }
        }
        if (config != null) {
            _state.value = UiState.Ready(config, pxPerMm = null, lastKey = null)
            viewModelScope.launch {
                calibration.pxPerMm.collect { px ->
                    _state.update { s -> if (s is UiState.Ready) s.copy(pxPerMm = px) else s }
                }
            }
        }
    }

    fun onResponse(dir: Dir) {
        _state.update { s -> if (s is UiState.Ready) s.copy(lastKey = dir) else s }
    }
}
