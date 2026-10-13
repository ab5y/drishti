package app.drishti.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shows every raw key press (keyCode, scanCode, device) so a real remote can
 * be mapped in input/KeyMap.kt. All keys are captured here, including back.
 */
@Composable
fun KeyCaptureScreen(state: UiState.Ready, vm: MainViewModel) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Key capture", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Press each button on the remote. Copy the keyCode for each direction and OK into input/KeyMap.kt. " +
                    "Back and volume keys are captured here too; use Done to leave.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { vm.open(Screen.HOME) }) { Text("Done") }
            if (state.capturedKeys.isEmpty()) Text("No keys yet.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.capturedKeys) { k ->
                    Text(
                        "keyCode ${k.keyCode} (${k.keyName}) · scanCode ${k.scanCode} · device \"${k.deviceName}\" → " +
                            (k.mapsTo?.name ?: "not mapped"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
