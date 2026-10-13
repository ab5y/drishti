package app.drishti.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Stage 1 placeholder: proves config, calibration store and key input are wired. */
@Composable
fun HomeScreen(state: UiState) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                UiState.Loading -> Text("Loading…")
                is UiState.ConfigError -> {
                    Text("config.json is invalid", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                    state.errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
                }
                is UiState.Ready -> {
                    val c = state.config
                    Text("Eye chart", style = MaterialTheme.typography.headlineSmall)
                    Text("Far ${c.farDistanceMm.toInt()} mm · near ${c.nearDistanceMm.toInt()} mm · ${c.distanceMethod.wireName}")
                    Text("${c.passMinCorrect} of ${c.optotypesPerLine} to pass a line")
                    Text(
                        state.pxPerMm?.let { "Screen calibrated: %.3f px/mm".format(it) }
                            ?: "Screen not calibrated. Card calibration is required before testing."
                    )
                    Text("Last remote key: ${state.lastKey ?: "none"}")
                }
            }
        }
    }
}
