package app.drishti.core

import kotlin.random.Random

/**
 * Descending-line acuity engine for one eye (build sheet section 05).
 * PRACTICE (unscored) -> DESCENDING_LINES -> DONE.
 *
 * Drive it with [next] until [Step.Finished], calling [respond] after each
 * [Step.Present]. Pure Kotlin; randomness is injected for reproducible tests.
 */
class TestEngine(
    private val config: EngineConfig,
    private val distance: DistanceSource,
    private val pxPerMm: Double,
    private val random: Random,
) {
    init {
        require(pxPerMm > 0 && pxPerMm.isFinite()) { "pxPerMm must be > 0, was $pxPerMm" }
    }

    var phase: Phase = if (config.practiceTrials > 0) Phase.PRACTICE else Phase.DESCENDING_LINES
        private set

    private var pending: Trial? = null
    private var result: EngineResult? = null

    private var practiceDone = 0
    private var practiceCorrect = 0

    private var lineIndex = 0
    private var lineShown = 0
    private var lineCorrect = 0
    private var lineUnsure = 0
    private val lines = mutableListOf<LineResult>()
    private var lastPassed: Double? = null

    private var scored = 0
    private var unsure = 0
    private var pixelLimited = false
    private var abortReason: AbortReason? = null
    private var paused = false
    private var distancePauses = 0
    private var voided = 0

    fun next(): Step {
        result?.let { return Step.Finished(it) }

        val d = usableDistanceMm()
        pending?.let { trial ->
            if (d != null) return Step.Present(trial)
            // Distance lost while the letter is up: withdraw it, unscored.
            pending = null
            voided++
            return pause()
        }
        if (d == null) return pause()
        paused = false

        val logMAR = currentLogMAR()
        val targetPx = OptotypeMath.optotypeHeightPx(d, config.linesLogMAR[config.stopIndex], pxPerMm)
        val heightPx = OptotypeMath.optotypeHeightPx(d, logMAR, pxPerMm)
        if (!OptotypeMath.minStrokeOk(targetPx, config.minStrokePx) ||
            !OptotypeMath.minStrokeOk(heightPx, config.minStrokePx)
        ) {
            pixelLimited = true
            abortReason = AbortReason.PIXEL_LIMITED
            return Step.Finished(finish())
        }

        val trial = Trial(
            phase = phase,
            lineIndex = if (phase == Phase.PRACTICE) null else lineIndex,
            indexInLine = if (phase == Phase.PRACTICE) practiceDone else lineShown,
            logMAR = logMAR,
            orientation = ORIENTATIONS[random.nextInt(ORIENTATIONS.size)],
            distanceMm = d,
            heightPx = heightPx,
        )
        pending = trial
        return Step.Present(trial)
    }

    /**
     * Records the answer to the presented trial. Returns false if it was voided
     * because distance was lost (the slot is presented again), true otherwise.
     */
    fun respond(dir: Dir): Boolean {
        check(result == null) { "test is finished" }
        val trial = checkNotNull(pending) { "no trial is being presented" }
        pending = null

        if (usableDistanceMm() == null) {
            voided++ // answered at an unknown distance: not scored, slot is re-presented
            return false
        }

        val correct = trial.orientation.isCorrect(dir)
        when (trial.phase) {
            Phase.PRACTICE -> {
                if (correct) practiceCorrect++
                practiceDone++
                if (practiceDone >= config.practiceTrials) phase = Phase.DESCENDING_LINES
            }
            Phase.DESCENDING_LINES -> {
                scored++
                lineShown++
                if (dir == Dir.UNSURE) {
                    unsure++
                    lineUnsure++
                }
                if (correct) lineCorrect++
                if (lineShown >= config.optotypesPerLine) completeLine()
            }
            Phase.DONE -> error("unreachable")
        }
        return true
    }

    /**
     * Ends the test as INCONCLUSIVE when the presented letter cannot be drawn
     * (it does not fit the screen, or the renderer finds it pixel-limited).
     */
    fun abort(reason: AbortReason): EngineResult {
        check(result == null) { "test is finished" }
        if (reason == AbortReason.PIXEL_LIMITED) pixelLimited = true
        abortReason = reason
        return finish()
    }

    private fun completeLine() {
        val logMAR = config.linesLogMAR[lineIndex]
        val passed = lineCorrect >= config.passMinCorrect
        lines += LineResult(logMAR, lineShown, lineCorrect, lineUnsure, passed)
        lineShown = 0
        lineCorrect = 0
        lineUnsure = 0

        if (!passed) {
            finish()
            return
        }
        lastPassed = logMAR
        if (lineIndex >= config.stopIndex) finish() else lineIndex++
    }

    private fun finish(): EngineResult {
        phase = Phase.DONE
        pending = null
        val passedCutoff = lastPassed?.let { it <= config.referralCutoffLogMAR + LOGMAR_EPS } == true
        val decision = when {
            pixelLimited || abortReason != null -> Referral.INCONCLUSIVE
            passedCutoff -> Referral.PASS
            else -> Referral.REFER
        }
        val quality = QualityFlags(
            responsesOk = scored > 0 && unsure.toDouble() / scored <= config.maxUnsureFraction + 1e-12,
            scoredResponses = scored,
            unsureResponses = unsure,
            practiceTrials = practiceDone,
            practiceCorrect = practiceCorrect,
            pixelLimited = pixelLimited,
            distancePauses = distancePauses,
            voidedTrials = voided,
        )
        return EngineResult(config.kind, lastPassed, decision, quality, lines.toList(), abortReason).also { result = it }
    }

    private fun currentLogMAR(): Double =
        if (phase == Phase.PRACTICE) config.practiceLogMAR else config.linesLogMAR[lineIndex]

    private fun usableDistanceMm(): Double? {
        val d = distance.currentDistanceMm(config.distanceMode) ?: return null
        if (!distance.isStable() || !(d > 0) || !d.isFinite()) return null
        return d
    }

    private fun pause(): Step {
        if (!paused) {
            paused = true
            distancePauses++
        }
        return Step.AdjustDistance
    }

    private companion object {
        val ORIENTATIONS = Orientation.entries.toTypedArray()
    }
}

/** Pinhole retest interpretation. */
object Pinhole {
    /**
     * True if the pinhole result is at least [minImprovementLogMAR] better than
     * unaided. "Nothing passed" unaided is scored one step worse than the top line.
     */
    fun improved(
        unaidedLogMAR: Double?,
        pinholeLogMAR: Double?,
        minImprovementLogMAR: Double,
        linesLogMAR: List<Double>,
        step: Double,
    ): Boolean {
        if (pinholeLogMAR == null) return false
        val unaided = unaidedLogMAR ?: (linesLogMAR.first() + step)
        return unaided - pinholeLogMAR >= minImprovementLogMAR - LOGMAR_EPS
    }
}
