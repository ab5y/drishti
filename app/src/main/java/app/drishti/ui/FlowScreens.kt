package app.drishti.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.drishti.core.Eye
import app.drishti.core.EyeOutcome
import app.drishti.core.FlowScreen
import app.drishti.core.Phase
import app.drishti.core.Referral
import app.drishti.core.ResultText
import app.drishti.core.ScreeningResult
import app.drishti.core.TestId
import app.drishti.core.TestKind
import kotlinx.coroutines.delay

private const val DISTANCE_POLL_MS = 500L

/**
 * Hosts every flow screen in landscape. Reports the available area to the
 * flow so letters are planned against the real viewport. Only wiring: all
 * decisions are in core/ScreeningFlow.
 */
@Composable
fun FlowHost(state: UiState.Ready, vm: MainViewModel) {
    val screen = state.flow ?: return
    // Back abandons the screening, but never while a letter or pause is up.
    BackHandler(enabled = screen !is FlowScreen.Letter && screen !is FlowScreen.AdjustDistance) { vm.exitFlow() }
    BackHandler(enabled = screen is FlowScreen.Letter || screen is FlowScreen.AdjustDistance) { }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            LaunchedEffect(w, h) { vm.setTestViewport(w, h) }
            when (screen) {
                FlowScreen.Register -> RegisterScreen(state, vm)
                is FlowScreen.LightWarning -> LightWarningScreen(screen, vm)
                is FlowScreen.Prompt -> PromptScreen(screen, vm)
                is FlowScreen.Letter -> LetterScreen(screen)
                is FlowScreen.AdjustDistance -> AdjustDistanceScreen(screen, vm)
                is FlowScreen.Result -> ResultScreen(screen.result, vm)
            }
        }
    }
}

@Composable
private fun RegisterScreen(state: UiState.Ready, vm: MainViewModel) {
    var token by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Register", style = MaterialTheme.typography.headlineSmall, color = Color.Black)
        Text("Token only: a code for this person. Do not enter a name.", color = Color.Black)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true, modifier = Modifier.width(260.dp))
            OutlinedTextField(
                age, { age = it.filter(Char::isDigit).take(3) }, label = { Text("Age (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(160.dp),
            )
        }
        state.registerErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            when {
                !state.lightSensorAvailable -> "Light: this phone has no light sensor."
                state.lux == null -> "Light: waiting for a reading…"
                else -> "Light: %.0f lux (minimum %.0f)".format(state.lux, state.config.minLux)
            },
            color = Color.Black,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.exitFlow() }) { Text("Cancel") }
            Button(onClick = { vm.register(token, age) }) { Text("Start screening") }
        }
    }
}

@Composable
private fun LightWarningScreen(s: FlowScreen.LightWarning, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Check the lighting", style = MaterialTheme.typography.headlineSmall, color = Color.Black)
        Text(
            if (s.lux == null) {
                "No light reading is available on this phone, so room lighting cannot be checked."
            } else {
                "The room reads %.0f lux, below the minimum of %.0f lux. Turn on lights or move to a brighter spot if you can."
                    .format(s.lux, s.minLux)
            },
            color = Color.Black,
        )
        Text(
            if (s.lux == null) "You can continue; the result will show the lighting as unknown."
            else "You can continue; the result will show the lighting as not OK.",
            color = Color.Black,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.exitFlow() }) { Text("Cancel") }
            Button(onClick = { vm.flowOk() }) { Text("Continue (OK)") }
        }
    }
}

@Composable
private fun PromptScreen(s: FlowScreen.Prompt, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(s.text.title, style = MaterialTheme.typography.headlineMedium, color = Color.Black, fontWeight = FontWeight.Bold)
        s.text.lines.forEach { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.Black) }
        Button(onClick = { vm.flowOk() }) { Text("Ready (OK)") }
    }
}

/** Plain white background, black letter at its exact pixel geometry, centred on whole pixels. */
@Composable
private fun LetterScreen(s: FlowScreen.Letter) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val ox = (size.width.toInt() - s.render.footprintPx) / 2
            val oy = (size.height.toInt() - s.render.footprintPx) / 2
            fillPxRects(s.render.rects, ox, oy, android.graphics.Color.BLACK)
        }
        Text(
            statusLine(s),
            color = Color(0xFFB0B0B0),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
    }
}

private fun statusLine(s: FlowScreen.Letter): String {
    val part = testName(s.test)
    return if (s.trial.phase == Phase.PRACTICE) {
        "$part · practice"
    } else {
        "$part · logMAR %.1f · letter %d".format(s.trial.logMAR, s.trial.indexInLine + 1)
    }
}

@Composable
private fun AdjustDistanceScreen(s: FlowScreen.AdjustDistance, vm: MainViewModel) {
    LaunchedEffect(s) {
        while (true) {
            delay(DISTANCE_POLL_MS)
            vm.pollFlow()
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Adjust distance", style = MaterialTheme.typography.headlineLarge, color = Color.Black)
        Text("The patient must sit at the mark. The test continues when the distance is right.", color = Color.Black)
    }
}

@Composable
private fun ResultScreen(r: ScreeningResult, vm: MainViewModel) {
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Screening result · token ${r.patient.token}" + (r.patient.age?.let { " · age $it" } ?: ""), color = Color.Black)
            Text(r.overallText, style = MaterialTheme.typography.headlineSmall, color = colorFor(r.overall), fontWeight = FontWeight.Bold)
            Text("This is a screening result, not a diagnosis.", style = MaterialTheme.typography.bodySmall, color = Color.Black)
            EyeCard(r.right)
            EyeCard(r.left)
            Part(
                "Near (both eyes)",
                r.near.decision,
                r.near.reason,
                null,
                r.near.quality,
            )
            Text("Not saved: saving records comes in a later stage.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Button(onClick = { vm.exitFlow() }) { Text("Done") }
        }
    }
}

@Composable
private fun EyeCard(e: EyeOutcome) {
    val name = if (e.eye == Eye.RIGHT) "Right eye" else "Left eye"
    Part(name, e.decision, e.reason, e.pinholeText, e.quality)
}

@Composable
private fun Part(title: String, decision: Referral, reason: String, extra: String?, quality: List<String>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$title: ${ResultText.decisionLabel(decision)}", style = MaterialTheme.typography.titleMedium, color = colorFor(decision), fontWeight = FontWeight.Bold)
        Text(reason, color = Color.Black)
        extra?.let { Text(it, color = Color.Black) }
        quality.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray) }
    }
}

private fun colorFor(r: Referral) = when (r) {
    Referral.PASS -> Color(0xFF1B6B3A)
    Referral.REFER -> Color(0xFFB04A00)
    Referral.INCONCLUSIVE -> Color(0xFF5A4FB0)
}

private fun testName(t: TestId): String = when (t.kind) {
    TestKind.FAR -> if (t.eye == Eye.RIGHT) "Right eye" else "Left eye"
    TestKind.NEAR -> "Near"
    TestKind.PINHOLE -> if (t.eye == Eye.RIGHT) "Right eye pinhole" else "Left eye pinhole"
}
