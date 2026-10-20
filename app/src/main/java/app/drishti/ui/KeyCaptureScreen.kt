package app.drishti.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drishti.core.AxisGate
import app.drishti.input.KeyMap

/**
 * Shows every raw key press (keyCode, scanCode, device) and joystick motion
 * (axis name and value, and the action it gives) so a real remote or gamepad
 * can be mapped in input/KeyMap.kt. All keys are captured here, including back.
 */
@Composable
fun KeyCaptureScreen(state: UiState.Ready, vm: MainViewModel) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Key capture", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Press each button on the remote and move each stick or hat. Copy the keyCode for each direction and OK " +
                    "into input/KeyMap.kt. Back and volume keys are captured here too; use Done to leave.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { vm.open(Screen.HOME) }) { Text("Done") }

            LiveMotionRows(state.liveMotion)
            HorizontalDivider()

            if (state.captured.isEmpty()) Text("Nothing captured yet.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.captured) { c ->
                    Text(
                        when (c) {
                            is Captured.Key ->
                                "Key: keyCode ${c.keyCode} (${c.keyName}) · scanCode ${c.scanCode} · device \"${c.deviceName}\" → " +
                                    (c.mapsTo?.name ?: "not mapped")
                            is Captured.Motion -> "Motion: ${c.control} (${c.values}) · device \"${c.deviceName}\" → ${c.result}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** Current joystick values, and what each control would give if pushed there from centre. */
@Composable
private fun LiveMotionRows(m: LiveMotion?) {
    if (m == null) {
        Text("Motion: none yet (sticks and hat show here).", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text("Motion now · device \"${m.deviceName}\"", style = MaterialTheme.typography.titleSmall)
    listOf("Hat" to m.hat, "Left stick" to m.stick).forEach { (control, xy) ->
        val gives = MainViewModel.describe(AxisGate.directionOf(xy.first, xy.second, KeyMap.AXIS_TRIGGER))
        Text("$control: ${MainViewModel.xyText(control, xy)} → $gives", style = MaterialTheme.typography.bodyMedium)
    }
    m.others.filter { it.value != 0.0 }.forEach {
        Text("%s %.2f → not mapped".format(it.name, it.value), style = MaterialTheme.typography.bodyMedium)
    }
}
