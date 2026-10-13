package app.drishti.core

import kotlin.random.Random

enum class Eye { RIGHT, LEFT, BOTH }

/** One test run in the flow, e.g. far right eye, near both eyes, pinhole left eye. */
data class TestId(val kind: TestKind, val eye: Eye)

data class Patient(val token: String, val age: Int?)

data class PromptText(val title: String, val lines: List<String>)

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
)

sealed interface FlowScreen {
    data object Register : FlowScreen
    /** Light below min_lux, or unknown. The operator may continue. */
    data class LightWarning(val lux: Double?, val minLux: Double) : FlowScreen
    /** Occlusion / positioning prompt; OK continues. */
    data class Prompt(val test: TestId, val text: PromptText) : FlowScreen
    data class Letter(val test: TestId, val trial: Trial, val render: OptotypeRender.Drawable, val crowded: Boolean) : FlowScreen
    data class AdjustDistance(val test: TestId) : FlowScreen
    data class Result(val result: ScreeningResult) : FlowScreen
}

/** Keep-screen-on and test brightness apply on these screens only. */
val FlowScreen.usesTestDisplay: Boolean
    get() = this is FlowScreen.Prompt || this is FlowScreen.Letter || this is FlowScreen.AdjustDistance

/**
 * The screening sequence: register -> far eye 1 -> far eye 2 -> near (both
 * eyes) -> pinhole for each far eye that is REFER -> result. Pure Kotlin; the
 * UI only shows [screen] and forwards keys, the viewport and polls.
 */
class ScreeningFlow(
    private val config: AppConfig,
    private val pxPerMm: Double,
    private val distance: DistanceSource,
    private val random: Random,
) {
    var screen: FlowScreen = FlowScreen.Register
        private set

    private val _trials = mutableListOf<TrialRecord>()
    val trials: List<TrialRecord> get() = _trials

    private var viewport: Viewport? = null
    private var patient: Patient? = null
    private var lux: Double? = null
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
        this.lux = lux
        eyeOrder = when (config.eyeOrder) {
            EyeOrder.RIGHT_FIRST -> listOf(Eye.RIGHT, Eye.LEFT)
            EyeOrder.LEFT_FIRST -> listOf(Eye.LEFT, Eye.RIGHT)
            EyeOrder.RANDOM -> if (random.nextBoolean()) listOf(Eye.RIGHT, Eye.LEFT) else listOf(Eye.LEFT, Eye.RIGHT)
        }
        eyeOrder.forEach { queue.addLast(TestId(TestKind.FAR, it)) }
        queue.addLast(TestId(TestKind.NEAR, Eye.BOTH))

        if (lightOk() != true) screen = FlowScreen.LightWarning(lux, config.minLux) else nextTest()
        return emptyList()
    }

    fun onAction(action: RemoteAction) {
        when (val s = screen) {
            is FlowScreen.LightWarning -> if (action == RemoteAction.OK) nextTest()
            is FlowScreen.Prompt -> if (action == RemoteAction.OK) advance()
            is FlowScreen.Letter -> {
                val e = checkNotNull(engine)
                val dir = action.toDir()
                val counted = e.respond(dir)
                _trials += record(s, dir, counted)
                advance()
            }
            // Register and Result use on-screen controls; keys are ignored while paused for distance.
            FlowScreen.Register, is FlowScreen.AdjustDistance, is FlowScreen.Result -> Unit
        }
    }

    /** Called periodically while paused for distance. */
    fun poll() {
        if (screen is FlowScreen.AdjustDistance) advance()
    }

    private fun lightOk(): Boolean? = lux?.let { it >= config.minLux }

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

    private fun record(s: FlowScreen.Letter, dir: Dir, counted: Boolean): TrialRecord {
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
        )
    }

    private fun finish() {
        screen = FlowScreen.Result(
            ResultBuilder.build(config, checkNotNull(patient), results, lux, lightOk(), trials.toList()),
        )
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
        val ok = "Press OK on the remote when ready."
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
