package app.drishti.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drishti.core.Eye
import app.drishti.core.EyeOutcome
import app.drishti.core.FlowScreen
import app.drishti.core.Phase
import app.drishti.core.Referral
import app.drishti.core.Registration
import app.drishti.core.RemoteAction
import app.drishti.core.RemoteCheck
import app.drishti.core.RemoteStatus
import app.drishti.core.ResultText
import app.drishti.core.ScreeningFlow
import app.drishti.core.ScreeningResult
import app.drishti.core.TestId
import app.drishti.core.TestKind
import app.drishti.input.KeyMap
import kotlinx.coroutines.delay

private const val DISTANCE_POLL_MS = 500L

/** Width of the operator answer pad strip at the right edge of letter screens (D43). */
private val PAD_STRIP = 168.dp
private val PAD_KEY = 48.dp
private val PAD_INK = Color(0xFFAAAAAA)
private val PAD_EDGE = Color(0xFFE3E3E3)

/**
 * Hosts every flow screen in landscape. Reports the letter area (the screen
 * minus the answer pad strip) to the flow so letters are planned against the
 * area they are drawn in. Only wiring: all decisions are in core/ScreeningFlow.
 */
@Composable
fun FlowHost(state: UiState.Ready, vm: MainViewModel) {
    val screen = state.flow ?: return
    // Back abandons the screening, but never while a letter or pause is up.
    val midTest = screen is FlowScreen.Letter || screen is FlowScreen.AdjustDistance || screen is FlowScreen.RemoteLost
    BackHandler(enabled = !midTest) { vm.exitFlow() }
    BackHandler(enabled = midTest) { }
    // D49: the chip is hidden once answers come from the on-screen pad, and on the result.
    val chip = state.remote.chipText(KeyMap.LOW_BATTERY).takeIf { state.remoteAnswers && screen !is FlowScreen.Result }
    val chipBad = !state.remote.connected || state.remote.lowBattery(KeyMap.LOW_BATTERY)

    Box(Modifier.fillMaxSize().background(Color.White)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val h = constraints.maxHeight
            // The pad strip is never part of the letter area, so a letter or its bars cannot sit under it.
            val letterW = (constraints.maxWidth - with(LocalDensity.current) { PAD_STRIP.roundToPx() }).coerceAtLeast(1)
            LaunchedEffect(letterW, h) { vm.setTestViewport(letterW, h) }
            when (screen) {
                FlowScreen.Register -> RegisterScreen(state, vm)
                is FlowScreen.LightWarning -> LightWarningScreen(screen, vm)
                is FlowScreen.RemoteCheck -> RemoteCheckScreen(screen, state.remote, vm)
                is FlowScreen.Prompt -> PromptScreen(screen, vm)
                is FlowScreen.Letter -> LetterScreen(screen, letterW, state.inputNotice, chip, chipBad, vm)
                is FlowScreen.AdjustDistance -> AdjustDistanceScreen(screen, vm)
                is FlowScreen.RemoteLost -> RemoteLostScreen(letterW, state.inputNotice, chip, chipBad, vm)
                is FlowScreen.Result -> ResultScreen(screen.result, state.save, vm)
            }
            // Letter screens carry the chip in the pad strip, so it never covers a letter.
            if (chip != null && screen !is FlowScreen.Letter && screen !is FlowScreen.RemoteLost) {
                RemoteChip(chip, chipBad, Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
        }
    }
}

/** "Remote ✓" or "Remote ✗ - reconnect", with battery (D49, D51). */
@Composable
private fun RemoteChip(text: String, bad: Boolean, modifier: Modifier = Modifier, muted: Boolean = false) {
    val color = when {
        bad -> Color(0xFFB00020)
        muted -> PAD_INK
        else -> Color(0xFF1B6B3A)
    }
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        modifier = modifier.border(1.dp, color, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun RegisterScreen(state: UiState.Ready, vm: MainViewModel) {
    var schoolId by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Register", style = MaterialTheme.typography.headlineSmall, color = Color.Black)
        // D56: the token is generated; the operator writes it on the child's card.
        Text(
            "Token: ${state.token ?: "…"}",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.Black,
            fontWeight = FontWeight.Bold,
        )
        Text("Write this token on the child's card. It links the card to this screening.", color = Color.Black)
        // D65: placeholder wording until the screening program supplies its consent text.
        Row(
            Modifier.toggleable(value = consent, role = Role.Checkbox, onValueChange = { consent = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = consent, onCheckedChange = null)
            Text(Registration.CONSENT_LABEL, color = Color.Black)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                schoolId, { schoolId = it.take(Registration.MAX_SCHOOL_ID_LENGTH) },
                label = { Text("School or village ID (optional)") },
                supportingText = { Text("Do not enter a name. Use a code with a digit.", color = MaterialTheme.colorScheme.error) },
                singleLine = true, modifier = Modifier.width(320.dp),
            )
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
            Button(onClick = { vm.register(schoolId, age, consent) }) { Text("Start screening") }
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
        Text("Press any button on the remote, or tap Continue.", color = Color.Black)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.exitFlow() }) { Text("Cancel") }
            Button(onClick = { vm.flowOk() }) { Text("Continue") }
        }
    }
}

/** D42: every button the test needs must be seen from the remote before it answers anything. */
@Composable
private fun RemoteCheckScreen(s: FlowScreen.RemoteCheck, remote: RemoteStatus, vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Remote check", style = MaterialTheme.typography.headlineSmall, color = Color.Black)
        Text("Press each button on the remote in turn: Up, Down, Left, Right, then OK. Each one is ticked when it arrives.", color = Color.Black)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RemoteCheck.ORDER.forEachIndexed { i, a ->
                val (mark, color) = when {
                    i < s.ticked -> "✓" to Color(0xFF1B6B3A)
                    i == s.ticked -> "→" to Color.Black
                    else -> "·" to Color.Gray
                }
                Text(
                    "$mark ${label(a)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = color,
                    fontWeight = if (i == s.ticked) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        s.expected?.let { Text("Now press ${label(it)}.", style = MaterialTheme.typography.titleMedium, color = Color.Black) }
        s.lastWrong?.let { w ->
            Text("Got ${label(w)}, waiting for ${s.expected?.let(::label)}.", color = MaterialTheme.colorScheme.error)
        }
        // D51: battery as Android reports it; warned below the low mark.
        Text(
            remote.batteryText(KeyMap.LOW_BATTERY),
            color = if (remote.lowBattery(KeyMap.LOW_BATTERY)) MaterialTheme.colorScheme.error else Color.Black,
            fontWeight = if (remote.lowBattery(KeyMap.LOW_BATTERY)) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            "If the remote has no button for any of these, it cannot be used to answer. Use on-screen answers instead: " +
                "the patient says or points to the answer and you tap it on the pad.",
            color = Color.Black,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.exitFlow() }) { Text("Cancel") }
            OutlinedButton(onClick = { vm.useOnScreenAnswers() }) { Text("Use on-screen answers") }
        }
    }
}

private fun label(a: RemoteAction) = when (a) {
    RemoteAction.UP -> "Up"
    RemoteAction.DOWN -> "Down"
    RemoteAction.LEFT -> "Left"
    RemoteAction.RIGHT -> "Right"
    RemoteAction.OK -> "OK (Not sure)"
}

@Composable
private fun PromptScreen(s: FlowScreen.Prompt, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(s.text.title, style = MaterialTheme.typography.headlineMedium, color = Color.Black, fontWeight = FontWeight.Bold)
        s.text.lines.forEach { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.Black) }
        Button(onClick = { vm.flowOk() }) { Text("Ready") }
    }
}

/**
 * Plain white background, black letter at its exact pixel geometry, centred on
 * whole pixels in the letter area; the muted answer pad sits in its own strip.
 */
@Composable
private fun LetterScreen(
    s: FlowScreen.Letter,
    letterWidthPx: Int,
    notice: String?,
    chip: String?,
    chipBad: Boolean,
    vm: MainViewModel,
) {
    val letterWidth = with(LocalDensity.current) { letterWidthPx.toDp() }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(letterWidth).fillMaxHeight()) {
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
        AnswerPad(notice, chip, chipBad, onPress = vm::padPress, modifier = Modifier.fillMaxSize())
    }
}

/** D50: the remote dropped mid-test. The letter was withdrawn; nothing is scored until the test resumes. */
@Composable
private fun RemoteLostScreen(letterWidthPx: Int, notice: String?, chip: String?, chipBad: Boolean, vm: MainViewModel) {
    val letterWidth = with(LocalDensity.current) { letterWidthPx.toDp() }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(letterWidth).fillMaxHeight().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(ScreeningFlow.REMOTE_LOST, style = MaterialTheme.typography.headlineSmall, color = Color.Black)
            Text(
                "The letter that was showing was taken back and not scored. The test goes on from the same place " +
                    "when the remote reconnects, or when you tap an arrow (that tap is not an answer).",
                color = Color.Black,
            )
            OutlinedButton(onClick = { vm.continueOnScreen() }) { Text("Continue on screen (no remote)") }
        }
        AnswerPad(notice, chip, chipBad, onPress = vm::padPress, modifier = Modifier.fillMaxSize())
    }
}

/** D43: the operator can enter the patient's answer. Muted so it does not draw the patient's eye. */
@Composable
private fun AnswerPad(notice: String?, chip: String?, chipBad: Boolean, onPress: (RemoteAction) -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Muted while all is well so it does not draw the patient's eye; red when it needs the operator.
        chip?.let { RemoteChip(it, chipBad, muted = true) }
        PadKey("Up") { onPress(RemoteAction.UP) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PadKey("Left") { onPress(RemoteAction.LEFT) }
            Spacer(Modifier.size(PAD_KEY))
            PadKey("Right") { onPress(RemoteAction.RIGHT) }
        }
        PadKey("Down") { onPress(RemoteAction.DOWN) }
        PadKey("Not sure", width = PAD_KEY * 3 + 8.dp) { onPress(RemoteAction.OK) }
        // Notices go here on letter screens so they never cover the letter (D44).
        notice?.let { Text(it, color = Color(0xFF8A8A8A), fontSize = 11.sp, lineHeight = 13.sp) }
    }
}

@Composable
private fun PadKey(text: String, width: Dp = PAD_KEY, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = width, height = PAD_KEY)
            .border(1.dp, PAD_EDGE, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = PAD_INK, fontSize = 11.sp)
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
private fun ResultScreen(r: ScreeningResult, save: SaveStatus?, vm: MainViewModel) {
    var confirmLeave by remember { mutableStateOf(false) }
    val done = { if (save is SaveStatus.Failed) confirmLeave = true else vm.exitFlow() }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Result not saved") },
            text = { Text("This result was not saved. Leave anyway? Write it down first.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.exitFlow() }) { Text("Leave without saving") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SaveLine(save, vm)
            Text(
                "Screening result · token ${r.patient.token}" + (r.patient.schoolId?.let { " · ID $it" } ?: "") +
                    (r.patient.age?.let { " · age $it" } ?: ""),
                color = Color.Black,
            )
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
            Button(onClick = done) { Text("Done") }
        }
    }
}

/** D55: the save never blocks the result; a failure is shown with a retry. */
@Composable
private fun SaveLine(save: SaveStatus?, vm: MainViewModel) {
    when (save) {
        null, SaveStatus.Saving -> Text("Saving…", color = Color.Gray)
        SaveStatus.Saved -> Text("Saved on this phone.", color = Color(0xFF1B6B3A))
        is SaveStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "NOT SAVED: ${save.reason}. Write this result down.",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
            OutlinedButton(onClick = { vm.retrySave() }) { Text("Try saving again") }
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
