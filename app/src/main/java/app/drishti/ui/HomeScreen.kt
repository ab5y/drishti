package app.drishti.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drishti.core.Readiness

@Composable
fun AppRoot(state: UiState, vm: MainViewModel, xdpi: Double) {
    when (state) {
        UiState.Loading -> Surface(Modifier.fillMaxSize()) { Text("Loading…", Modifier.safeDrawingPadding().padding(24.dp)) }
        is UiState.ConfigError -> ConfigErrorScreen(state.errors)
        is UiState.Ready -> when (state.screen) {
            Screen.HOME -> HomeScreen(state, vm)
            Screen.CALIBRATION -> {
                BackHandler { vm.open(Screen.HOME) }
                CalibrationScreen(
                    xdpi = xdpi,
                    saved = state.calibration,
                    onSave = { edgePx -> vm.saveCalibration(edgePx, xdpi) },
                    onCancel = { vm.open(Screen.HOME) },
                )
            }
            Screen.DEBUG -> {
                BackHandler { vm.open(Screen.HOME) }
                val ready = state.readiness as? Readiness.Ready
                if (ready != null) DebugScreen(state.config, ready.pxPerMm, onBack = { vm.open(Screen.HOME) })
            }
            Screen.FLOW -> FlowHost(state, vm)
            Screen.KEY_CAPTURE -> {
                BackHandler { } // back is captured as a key; leave with Done
                KeyCaptureScreen(state, vm)
            }
        }
    }
}

@Composable
private fun ConfigErrorScreen(errors: List<String>) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("config.json is invalid", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
            errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun HomeScreen(state: UiState.Ready, vm: MainViewModel) {
    val c = state.config
    val ready = state.readiness is Readiness.Ready
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Eye chart", style = MaterialTheme.typography.headlineSmall)
            Text("Far ${c.farDistanceMm.toInt()} mm · near ${c.nearDistanceMm.toInt()} mm · ${c.distanceMethod.wireName}")
            Text("${c.passMinCorrect} of ${c.optotypesPerLine} to pass a line")

            val status = when (val r = state.readiness) {
                is Readiness.Ready -> "Screen calibrated: %.3f px/mm".format(r.pxPerMm)
                Readiness.NotCalibrated -> "Screen not calibrated. Card calibration is required before testing."
                is Readiness.DisplayChanged ->
                    "Display resolution changed (${r.calibrated.longPx}×${r.calibrated.shortPx} → " +
                        "${r.current.longPx}×${r.current.shortPx}). Recalibrate before testing."
            }
            Text(status, color = if (ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)

            OutlinedButton(onClick = { vm.open(Screen.CALIBRATION) }, Modifier.fillMaxWidth()) {
                Text(if (state.calibration == null) "Calibrate screen" else "Recalibrate")
            }
            OutlinedButton(onClick = { vm.open(Screen.DEBUG) }, Modifier.fillMaxWidth(), enabled = ready) {
                Text("Ruler and letter-size check")
            }
            Button(onClick = { vm.startTest() }, Modifier.fillMaxWidth(), enabled = ready) {
                Text("Start screening")
            }
            OutlinedButton(onClick = { vm.open(Screen.KEY_CAPTURE) }, Modifier.fillMaxWidth()) {
                Text("Remote key capture")
            }
            state.notice?.let { Text(it) }
            Text("Last remote key: ${state.lastKey ?: "none"}")
        }
    }
}
