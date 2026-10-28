package app.drishti.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drishti.core.Battery
import app.drishti.core.RemoteAction
import app.drishti.core.SeenDevice
import app.drishti.core.StationRemote
import app.drishti.input.KeyMap

/**
 * Remote setup (D47, D48; clicker section 03). Shows every raw press, lets the
 * operator assign the last one to an action for that remote, choose the
 * station remote, and reset a stored mapping. All keys are captured here,
 * including back; leave with Done.
 */
@Composable
fun RemoteSetupScreen(state: UiState.Ready, vm: MainViewModel) {
    val last = state.captured.firstOrNull()
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("Remote setup", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Press a button on the remote (or push a stick or hat), then tap what it should mean. " +
                        "The mapping is saved on this phone for that remote. Back and volume keys are captured here " +
                        "too; use Done to leave.",
                    style = MaterialTheme.typography.bodySmall,
                )
                // D68: Android handles Home before any app sees it, so no screen can capture it.
                Text(
                    "Some remotes send Home from a button (the VR BOX does from B in game mode). " +
                        "Home always leaves the app: Android does not let any app block it. Do not use that " +
                        "button; if it is pressed, reopen the app from recent apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = { vm.open(Screen.HOME) }) { Text("Done") }
            }

            item { HorizontalDivider() }
            item { LastPress(last, vm) }

            item { HorizontalDivider() }
            item { Devices(state, vm) }

            item { HorizontalDivider() }
            item { SavedMappings(state, vm) }

            item { HorizontalDivider() }
            item { LiveMotionRows(state.liveMotion) }
            item { Text("Press log (newest first)", style = MaterialTheme.typography.titleSmall) }
            if (state.captured.isEmpty()) item { Text("Nothing captured yet.") }
            items(state.captured) { c ->
                Text("${c.what} · device \"${c.device.name}\" → ${c.gives}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LastPress(last: Captured?, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Last press", style = MaterialTheme.typography.titleMedium)
        if (last == null) {
            Text("Nothing yet: press a button on the remote.")
            return
        }
        Text("${last.what} · device \"${last.device.name}\"")
        Text("Now: ${last.gives}", style = MaterialTheme.typography.bodyLarge)
        val canAssign = last.input != null && last.device.descriptor != null
        if (!canAssign) {
            Text(
                if (last.input == null) "A diagonal cannot be assigned: push one direction."
                else "This device has no identifier, so a mapping cannot be saved for it.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text("Assign this press to:")
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(RemoteAction.UP, RemoteAction.DOWN, RemoteAction.LEFT, RemoteAction.RIGHT, RemoteAction.OK).forEach { a ->
                OutlinedButton(onClick = { vm.assignLast(a) }, enabled = canAssign) { Text(MainViewModel.actionLabel(a)) }
            }
        }
    }
}

/** D48: the operator marks one external device as the station remote. */
@Composable
private fun Devices(state: UiState.Ready, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Station remote", style = MaterialTheme.typography.titleMedium)
        Text(
            "During a test, only the station remote and the phone's own keys are used; presses from other remotes " +
                "and keyboards are ignored. With no station remote, any device is used.",
            style = MaterialTheme.typography.bodySmall,
        )
        val station = state.station
        if (station == null) {
            Text("None set.")
        } else {
            val present = state.devices.any { it.descriptor == station.descriptor }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Set: ${station.name} (${if (present) "connected" else "not connected"})")
                OutlinedButton(onClick = { vm.setStation(null) }) { Text("Clear") }
            }
        }

        Text("Connected devices", style = MaterialTheme.typography.titleSmall)
        val shown = state.devices.filter { it.remoteLike }
        if (shown.isEmpty()) Text("No remote, gamepad or keyboard devices seen.")
        shown.forEach { d -> DeviceRow(d, station, vm) }
        Text(
            "Bluetooth pairing and reconnecting are done by Android, not by this app (D52).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DeviceRow(d: SeenDevice, station: StationRemote?, vm: MainViewModel) {
    val isStation = station?.descriptor == d.descriptor
    Column {
        Text(d.name + if (d.external) "" else " (built in)", style = MaterialTheme.typography.bodyLarge)
        Text(
            when (val b = d.battery) {
                is Battery.Level -> "Battery %.0f%%".format(b.fraction * 100) +
                    if (b.fraction < KeyMap.LOW_BATTERY) " (LOW)" else ""
                Battery.NotReported -> "Battery not reported"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        when {
            isStation -> Text("This is the station remote.")
            d.external -> OutlinedButton(onClick = { vm.setStation(StationRemote(d.descriptor, d.name)) }) {
                Text("Use as station remote")
            }
            // Built-in keys are never filtered, so there is nothing to choose (D48).
            else -> Unit
        }
    }
}

/** D47: stored mappings per remote, each with its own reset. */
@Composable
private fun SavedMappings(state: UiState.Ready, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Saved mappings", style = MaterialTheme.typography.titleMedium)
        if (state.profiles.profiles.isEmpty()) Text("None: every remote uses the built-in defaults.")
        state.profiles.profiles.forEach { p ->
            Text(p.remote.name, style = MaterialTheme.typography.bodyLarge)
            p.assignments.forEach { a ->
                Text("${MainViewModel.inputLabel(a.input)} → ${MainViewModel.actionLabel(a.action)}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { vm.resetMapping(p.remote) }) { Text("Reset mapping") }
        }
    }
}

/** Current joystick values; captured pushes in the log show what each one means. */
@Composable
private fun LiveMotionRows(m: LiveMotion?) {
    if (m == null) {
        Text("Motion: none yet (sticks and hat show here).", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text("Motion now · device \"${m.deviceName}\"", style = MaterialTheme.typography.titleSmall)
    listOf("Hat" to m.hat, "Left stick" to m.stick).forEach { (control, xy) ->
        Text("$control: ${MainViewModel.xyText(control, xy)}", style = MaterialTheme.typography.bodyMedium)
    }
    m.others.filter { it.value != 0.0 }.forEach {
        Text("%s %.2f".format(it.name, it.value), style = MaterialTheme.typography.bodyMedium)
    }
}
