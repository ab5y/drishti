package app.drishti.core

enum class TestKind(val distanceMode: DistanceMode) {
    FAR(DistanceMode.FAR),
    NEAR(DistanceMode.NEAR),
    /** Pinhole retest is a far test with its own line set. */
    PINHOLE(DistanceMode.FAR),
}

enum class Phase { PRACTICE, DESCENDING_LINES, DONE }

/** INCONCLUSIVE: the target line could not be rendered (pixel-limited), so nothing was scored to it. */
enum class Referral { PASS, REFER, INCONCLUSIVE }

/** Everything the engine needs for one test; built from [AppConfig.engineConfig]. */
data class EngineConfig(
    val kind: TestKind,
    val distanceMode: DistanceMode,
    val practiceLogMAR: Double,
    val practiceTrials: Int,
    /** Largest letter first, descending. */
    val linesLogMAR: List<Double>,
    /** Passing this line passes the test; the engine never descends below it. */
    val referralCutoffLogMAR: Double,
    val optotypesPerLine: Int,
    val passMinCorrect: Int,
    val minStrokePx: Double,
    val maxUnsureFraction: Double,
) {
    /** Index of the referral line: the last line ever presented. */
    val stopIndex: Int = linesLogMAR.indexOfFirst { logMarEquals(it, referralCutoffLogMAR) }

    init {
        require(linesLogMAR.isNotEmpty()) { "linesLogMAR must not be empty" }
        require(linesLogMAR.zipWithNext().all { (a, b) -> a > b }) { "linesLogMAR must be strictly descending" }
        require(stopIndex >= 0) { "referralCutoffLogMAR $referralCutoffLogMAR is not in linesLogMAR" }
        require(optotypesPerLine >= 1) { "optotypesPerLine must be >= 1" }
        require(passMinCorrect in 1..optotypesPerLine) { "passMinCorrect must be in 1..$optotypesPerLine" }
        require(practiceTrials >= 0) { "practiceTrials must be >= 0" }
        require(minStrokePx > 0) { "minStrokePx must be > 0" }
        require(maxUnsureFraction in 0.0..1.0) { "maxUnsureFraction must be in 0..1" }
    }
}

/**
 * One presentation. Its size is fixed when it is created and never changes;
 * a new distance only affects the next presentation.
 */
data class Trial(
    val phase: Phase,
    /** Index into [EngineConfig.linesLogMAR]; null for practice. */
    val lineIndex: Int?,
    /** Position within the line (or practice trial number). */
    val indexInLine: Int,
    val logMAR: Double,
    val orientation: Orientation,
    val distanceMm: Double,
    val heightPx: Double,
)

data class LineResult(
    val logMAR: Double,
    val shown: Int,
    val correct: Int,
    val unsure: Int,
    val passed: Boolean,
)

data class QualityFlags(
    /** Enough answers, and unsure / scored <= the configured fraction. */
    val responsesOk: Boolean,
    val scoredResponses: Int,
    val unsureResponses: Int,
    val practiceTrials: Int,
    val practiceCorrect: Int,
    /** The target line could not meet the minimum stroke at the current distance. */
    val pixelLimited: Boolean,
    /** Times the test paused because distance was unknown or unstable. */
    val distancePauses: Int,
    /** Presentations withdrawn unscored because distance was lost while they were up. */
    val voidedTrials: Int,
)

data class EngineResult(
    val kind: TestKind,
    /** Last line passed (recorded acuity), or null if none passed. */
    val lastPassedLogMAR: Double?,
    val decision: Referral,
    val quality: QualityFlags,
    val lines: List<LineResult>,
)

sealed interface Step {
    data class Present(val trial: Trial) : Step
    /** Distance unknown or unstable: show "adjust distance", render nothing. */
    data object AdjustDistance : Step
    data class Finished(val result: EngineResult) : Step
}
