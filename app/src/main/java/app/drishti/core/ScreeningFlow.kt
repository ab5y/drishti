package app.drishti.core

import kotlin.random.Random

enum class Eye { RIGHT, LEFT, BOTH }

/** One test run in the flow, e.g. far right eye, near both eyes, pinhole left eye. */
data class TestId(val kind: TestKind, val eye: Eye)

data class Patient(val token: String, val age: Int?)

data class PromptText(val title: String, val lines: List<String>)

/** Where an answer came from (D43). */
enum class AnswerSource { REMOTE, PAD }

/** What happened to a press. A press that is not used always says why (D44). */
sealed interface InputResult {
    data object Used : InputResult
    data class NotUsed(val why: String) : InputResult
}

/** The remote check before the first eye (D42). */
object RemoteCheck {
    val ORDER = listOf(RemoteAction.UP, RemoteAction.DOWN, RemoteAction.LEFT, RemoteAction.RIGHT, RemoteAction.OK)
}

/** One answered presentation, with what was actually drawn. */
data class TrialRecord(
    val test: TestId,
    val phase: Phase,
    val logMAR: Double,
    val orientation: Orientation,
    val response: Dir,
    val correct: Boolean,
    /** False if the engine voided it (distance lost). */
    val counted: Boolean,
    val distanceMm: Double,
    val intendedMm: Double,
    val drawnMm: Double,
    val strokePx: Int,
    /** drawn / intended - 1 (positive = drawn larger). */
    val sizeErrorFraction: Double,
    val crowded: Boolean,
    val source: AnswerSource,
)

sealed interface FlowScreen {
    data object Register : FlowScreen
    /** Light below min_lux, or unknown. The operator may continue. */
    data class LightWarning(val lux: Double?, val minLux: Double) : FlowScreen
    /** Operator presses [RemoteCheck.ORDER] in turn; [ticked] of them are done. */
    data class RemoteCheck(val ticked: Int, val lastWrong: RemoteAction?) : FlowScreen {
        val expected: RemoteAction? get() = app.drishti.core.RemoteCheck.ORDER.getOrNull(ticked)
    }
    /** Occlusion / positioning prompt; OK continues. */
    data class Prompt(val test: TestId, val text: PromptText) : FlowScreen
    data class Letter(val test: TestId, val trial: Trial, val render: OptotypeRender.Drawable, val crowded: Boolean) : FlowScreen
    data class AdjustDistance(val test: TestId) : FlowScreen
    /** The remote dropped during [test] (D50): reconnect, tap the pad, or continue on screen. */
    data class RemoteLost(val test: TestId) : FlowScreen
    data class Result(val result: ScreeningResult) : FlowScreen
}

/** Keep-screen-on and test brightness apply on these screens only. */
val FlowScreen.usesTestDisplay: Boolean
    get() = this is FlowScreen.Prompt || this is FlowScreen.Letter || this is FlowScreen.AdjustDistance ||
        this is FlowScreen.RemoteLost

/**
 * The screening sequence: register -> (light warning) -> remote check -> far
 * eye 1 -> far eye 2 -> near (both eyes) -> pinhole for each far eye that is
 * REFER -> result. Pure Kotlin; the UI only shows [screen] and forwards
 * presses, the viewport and polls.
 *
 * [clock] is uptime in ms, for the prompt dwell (D41). [readLux] reads the
 * light sensor when the first eye's test starts (D38).
 */
class ScreeningFlow(
    private val config: AppConfig,
    private val pxPerMm: Double,
    private val distance: DistanceSource,
    private val random: Random,
    private val clock: () -> Long,
    private val readLux: () -> Double?,
    private val promptMinShowMs: Long,
) {
    private var shownAtMs = clock()

    var screen: FlowScreen = FlowScreen.Register
        private set(value) {
            field = value
            shownAtMs = clock()
        }

    /** False once the operator chose on-screen answers on the remote check. */
    var remoteAnswers: Boolean = true
        private set

    /** Null until the UI first reports the remote; only a change from true to false is a drop (D50). */
    private var remoteConnected: Boolean? = null

    /** The remote dropped while no letter was up; the next test start pauses. */
    private var dropPending = false
    private val remoteDrops = LinkedHashMap<TestId, Int>()

    private val _trials = mutableListOf<TrialRecord>()
    val trials: List<TrialRecord> get() = _trials

    private var viewport: Viewport? = null
    private var patient: Patient? = null
    private var luxAtRegister: Double? = null
    private var luxAtTestStart: Double? = null
    private var testStarted = false
    private var eyeOrder: List<Eye> = emptyList()

    private val queue = ArrayDeque<TestId>()
    private var pinholePlanned = false
    private val results = LinkedHashMap<TestId, EngineResult>()
    private var engine: TestEngine? = null
    private var current: TestId? = null

    fun setViewport(widthPx: Int, heightPx: Int) {
        if (widthPx > 0 && heightPx > 0) viewport = Viewport(widthPx, heightPx)
    }

    /** Returns validation errors; on success moves to the light warning or the first prompt. */
    fun register(token: String, ageText: String, lux: Double?): List<String> {
        check(screen == FlowScreen.Register) { "already registered" }
        if (viewport == null) return listOf("The test screen is not ready yet. Try again.")
        val (p, errors) = Registration.validate(token, ageText)
        if (p == null) return errors

        patient = p
        luxAtRegister = lux
        eyeOrder = when (config.eyeOrder) {
            EyeOrder.RIGHT_FIRST -> listOf(Eye.RIGHT, Eye.LEFT)
            EyeOrder.LEFT_FIRST -> listOf(Eye.LEFT, Eye.RIGHT)
            EyeOrder.RANDOM -> if (random.nextBoolean()) listOf(Eye.RIGHT, Eye.LEFT) else listOf(Eye.LEFT, Eye.RIGHT)
        }
        eyeOrder.forEach { queue.addLast(TestId(TestKind.FAR, it)) }
        queue.addLast(TestId(TestKind.NEAR, Eye.BOTH))

        val lightOk = LightReadings(lux, null).ok(config.minLux)
        screen = if (lightOk != true) FlowScreen.LightWarning(lux, config.minLux) else checkScreen()
        return emptyList()
    }

    /** A mapped press: from the remote (keys or axes) or the on-screen controls. */
    fun onAction(action: RemoteAction, source: AnswerSource = AnswerSource.REMOTE): InputResult = when (val s = screen) {
        is FlowScreen.LightWarning -> continueFrom(source) { screen = checkScreen() }
        is FlowScreen.RemoteCheck ->
            if (source == AnswerSource.REMOTE) check(s, action) else InputResult.NotUsed(CHECK_REMOTE_ONLY)
        is FlowScreen.Prompt -> continueFrom(source) { startTest() }
        is FlowScreen.Letter -> if (source == AnswerSource.REMOTE && !remoteAnswers) {
            InputResult.NotUsed(REMOTE_OFF)
        } else {
            val dir = action.toDir()
            val counted = checkNotNull(engine).respond(dir)
            _trials += record(s, dir, counted, source)
            advance()
            InputResult.Used
        }
        is FlowScreen.AdjustDistance -> InputResult.NotUsed(PAUSED)
        is FlowScreen.RemoteLost -> if (source == AnswerSource.PAD) {
            resume()
            InputResult.NotUsed(RESUMED_NOT_ANSWER)
        } else {
            InputResult.NotUsed(REMOTE_LOST_PRESS)
        }
        FlowScreen.Register, is FlowScreen.Result -> InputResult.NotUsed(USE_SCREEN)
    }

    /** A remote press with no mapping (unmapped key, other axis, diagonal): it only continues prompts (D41). */
    fun onOtherPress(): InputResult = when (screen) {
        is FlowScreen.LightWarning -> continueFrom(AnswerSource.REMOTE) { screen = checkScreen() }
        is FlowScreen.Prompt -> continueFrom(AnswerSource.REMOTE) { startTest() }
        is FlowScreen.RemoteCheck -> InputResult.NotUsed(CHECK_UNMAPPED)
        is FlowScreen.Letter -> InputResult.NotUsed(NOT_AN_ANSWER)
        is FlowScreen.AdjustDistance -> InputResult.NotUsed(PAUSED)
        is FlowScreen.RemoteLost -> InputResult.NotUsed(REMOTE_LOST_PRESS)
        FlowScreen.Register, is FlowScreen.Result -> InputResult.NotUsed(USE_SCREEN)
    }

    /** From the remote check: the remote cannot give all five presses, so answers come from the pad (D42). */
    fun useOnScreenAnswers() {
        if (screen !is FlowScreen.RemoteCheck) return
        remoteAnswers = false
        nextTest()
    }

    /**
     * Whether the remote is connected (D49, D50), from the device listener.
     * In remote-answer mode a change from connected to not connected pauses
     * the test: a letter that is up is withdrawn unscored. The test resumes
     * at the same slot when the remote is back.
     */
    fun onRemoteConnection(connected: Boolean) {
        val wasConnected = remoteConnected == true
        remoteConnected = connected
        if (connected) {
            dropPending = false
            if (screen is FlowScreen.RemoteLost) resume()
            return
        }
        if (!wasConnected || !remoteAnswers) return
        when (val s = screen) {
            is FlowScreen.Letter -> {
                checkNotNull(engine).withdraw()
                pause(s.test)
            }
            is FlowScreen.AdjustDistance -> pause(s.test)
            is FlowScreen.LightWarning, is FlowScreen.RemoteCheck, is FlowScreen.Prompt -> dropPending = true
            FlowScreen.Register, is FlowScreen.RemoteLost, is FlowScreen.Result -> Unit
        }
    }

    /** From the remote-lost pause: answers come from the on-screen pad from now on (D50). */
    fun continueOnScreen() {
        if (screen !is FlowScreen.RemoteLost) return
        remoteAnswers = false
        resume()
    }

    /** Called periodically while paused for distance. */
    fun poll() {
        if (screen is FlowScreen.AdjustDistance) advance()
    }

    private fun checkScreen() = FlowScreen.RemoteCheck(ticked = 0, lastWrong = null)

    private fun check(s: FlowScreen.RemoteCheck, action: RemoteAction): InputResult {
        val expected = checkNotNull(s.expected)
        if (action != expected) {
            screen = s.copy(lastWrong = action)
            return InputResult.NotUsed("Got $action, waiting for $expected.")
        }
        val ticked = s.ticked + 1
        if (ticked == RemoteCheck.ORDER.size) nextTest() else screen = FlowScreen.RemoteCheck(ticked, lastWrong = null)
        return InputResult.Used
    }

    /** Remote presses on a prompt count only after it has been up for [promptMinShowMs]. */
    private inline fun continueFrom(source: AnswerSource, go: () -> Unit): InputResult {
        if (source == AnswerSource.REMOTE && clock() - shownAtMs < promptMinShowMs) return InputResult.NotUsed(WAIT_PROMPT)
        go()
        return InputResult.Used
    }

    private fun startTest() {
        if (!testStarted) {
            testStarted = true
            luxAtTestStart = readLux()
        }
        if (dropPending && remoteAnswers) {
            dropPending = false
            pause(checkNotNull(current))
        } else {
            advance()
        }
    }

    private fun pause(test: TestId) {
        remoteDrops[test] = (remoteDrops[test] ?: 0) + 1
        screen = FlowScreen.RemoteLost(test)
    }

    private fun resume() {
        dropPending = false
        advance()
    }

    private fun nextTest() {
        if (queue.isEmpty() && !pinholePlanned) {
            pinholePlanned = true
            if (config.pinhole.onFail) {
                eyeOrder.filter { results[TestId(TestKind.FAR, it)]?.decision == Referral.REFER }
                    .forEach { queue.addLast(TestId(TestKind.PINHOLE, it)) }
            }
        }
        val test = queue.removeFirstOrNull()
        if (test == null) {
            finish()
            return
        }
        current = test
        engine = TestEngine(config.engineConfig(test.kind), distance, pxPerMm, random)
        screen = FlowScreen.Prompt(test, Prompts.forTest(test, config))
    }

    private fun advance() {
        val e = checkNotNull(engine)
        val test = checkNotNull(current)
        when (val step = e.next()) {
            is Step.Present -> {
                val vp = checkNotNull(viewport)
                when (val plan = RenderPlanner.plan(step.trial, pxPerMm, config.minStrokePx, config.crowdingSpec(), vp)) {
                    is RenderPlan.Draw -> screen = FlowScreen.Letter(test, step.trial, plan.render, plan.crowded)
                    is RenderPlan.Abort -> testFinished(test, e.abort(plan.reason))
                }
            }
            Step.AdjustDistance -> screen = FlowScreen.AdjustDistance(test)
            is Step.Finished -> testFinished(test, step.result)
        }
    }

    private fun testFinished(test: TestId, result: EngineResult) {
        results[test] = result
        engine = null
        current = null
        nextTest()
    }

    private fun record(s: FlowScreen.Letter, dir: Dir, counted: Boolean, source: AnswerSource): TrialRecord {
        val intended = OptotypeMath.optotypeHeightMm(s.trial.distanceMm, s.trial.logMAR)
        return TrialRecord(
            test = s.test,
            phase = s.trial.phase,
            logMAR = s.trial.logMAR,
            orientation = s.trial.orientation,
            response = dir,
            correct = s.trial.orientation.isCorrect(dir),
            counted = counted,
            distanceMm = s.trial.distanceMm,
            intendedMm = intended,
            drawnMm = s.render.actualHeightMm,
            strokePx = s.render.strokePx,
            sizeErrorFraction = s.render.actualHeightMm / intended - 1,
            crowded = s.crowded,
            source = source,
        )
    }

    private fun finish() {
        screen = FlowScreen.Result(
            ResultBuilder.build(
                config, checkNotNull(patient), results, LightReadings(luxAtRegister, luxAtTestStart), trials.toList(),
                remoteDrops.toMap(),
            ),
        )
    }

    companion object {
        const val WAIT_PROMPT = "Wait: prompt just appeared. Press again."
        const val REMOTE_OFF = "Remote answers are off: use the on-screen pad."
        const val PAUSED = "Paused for distance: press not used."
        const val USE_SCREEN = "Use the on-screen buttons on this screen."
        const val CHECK_REMOTE_ONLY = "Press the buttons on the remote for this check."
        const val CHECK_UNMAPPED = "Not one of Up, Down, Left, Right, OK."
        const val NOT_AN_ANSWER = "Not an answer: use Up, Down, Left, Right or OK."
        const val REMOTE_LOST = "Reconnect the remote, or tap the arrows on screen"
        const val REMOTE_LOST_PRESS = "Paused: the remote disconnected. Press not used."
        const val RESUMED_NOT_ANSWER = "Resumed. That tap was not an answer: tap the arrow for the letter now shown."
    }
}

object Registration {
    const val MAX_TOKEN_LENGTH = 32
    const val MAX_AGE = 120

    /** Token is required (non-identifying tag, no names); age is optional. */
    fun validate(token: String, ageText: String): Pair<Patient?, List<String>> {
        val errors = mutableListOf<String>()
        val t = token.trim()
        if (t.isEmpty()) errors += "Enter a token (a code, not a name)."
        if (t.length > MAX_TOKEN_LENGTH) errors += "Token must be at most $MAX_TOKEN_LENGTH characters."
        val a = ageText.trim()
        val age = if (a.isEmpty()) null else a.toIntOrNull()
        if (a.isNotEmpty() && (age == null || age !in 0..MAX_AGE)) errors += "Age must be a whole number from 0 to $MAX_AGE, or blank."
        return if (errors.isEmpty()) Patient(t, age) to emptyList() else null to errors
    }
}

object Prompts {
    fun forTest(test: TestId, config: AppConfig): PromptText {
        val far = config.farDistanceMm.toInt()
        val nearMm = config.nearDistanceMm.toInt()
        val ok = "Press any button on the remote, or tap Ready."
        return when (test.kind) {
            TestKind.FAR -> {
                val (eye, other) = names(test.eye)
                PromptText(
                    "$eye eye",
                    listOfNotNull(
                        "Cover the $other eye with the occluder. Keep the $eye eye open.",
                        "The patient sits at the far mark, $far mm from the screen.",
                        "Check the patient is not peeking or squinting.",
                        if (config.far.practiceTrials > 0) "A large practice E comes first: show how the arrows match where it points." else null,
                        ok,
                    ),
                )
            }
            TestKind.NEAR -> PromptText(
                "Near test, both eyes",
                listOfNotNull(
                    "Remove the occluder. Both eyes open.",
                    "The patient's eyes are $nearMm mm from the screen (use the near string).",
                    if (config.near.practiceTrials > 0) "A practice E comes first." else null,
                    ok,
                ),
            )
            TestKind.PINHOLE -> {
                val (eye, other) = names(test.eye)
                PromptText(
                    "$eye eye, pinhole",
                    listOfNotNull(
                        "Cover the $other eye. Hold the pinhole occluder in front of the $eye eye.",
                        "The patient sits at the far mark, $far mm from the screen.",
                        if (config.pinhole.practiceTrials > 0) "A practice E comes first." else null,
                        ok,
                    ),
                )
            }
        }
    }

    private fun names(eye: Eye): Pair<String, String> = when (eye) {
        Eye.RIGHT -> "RIGHT" to "LEFT"
        Eye.LEFT -> "LEFT" to "RIGHT"
        Eye.BOTH -> error("far and pinhole tests are one eye at a time")
    }
}
