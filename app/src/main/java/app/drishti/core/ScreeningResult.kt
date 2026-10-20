package app.drishti.core

import kotlin.math.abs
import kotlin.math.roundToInt

data class EyeOutcome(
    val eye: Eye,
    val far: EngineResult,
    /** Equals the far decision; the pinhole is informational (D27). */
    val decision: Referral,
    val reason: String,
    val pinhole: EngineResult?,
    /** Null if the pinhole was not run or could not be scored. */
    val pinholeImproved: Boolean?,
    val pinholeText: String?,
    val quality: List<String>,
)

data class NearOutcome(
    val result: EngineResult,
    val decision: Referral,
    val reason: String,
    val quality: List<String>,
)

/** A screening result. Screening wording only, no diagnosis (D29). */
data class ScreeningResult(
    val patient: Patient,
    val right: EyeOutcome,
    val left: EyeOutcome,
    val near: NearOutcome,
    val overall: Referral,
    val overallText: String,
    val light: LightReadings,
    /** From the lower reading (D38); null when there is no light reading at all. */
    val lightOk: Boolean?,
    /** Rope distance: confirmed by the operator on each prompt (D31). */
    val distanceOk: Boolean,
    val trials: List<TrialRecord>,
)

object ResultBuilder {
    fun build(
        config: AppConfig,
        patient: Patient,
        results: Map<TestId, EngineResult>,
        light: LightReadings,
        trials: List<TrialRecord>,
        /** Remote drops per test (D50). */
        remoteDrops: Map<TestId, Int> = emptyMap(),
    ): ScreeningResult {
        val lightOk = light.ok(config.minLux)
        val perLine = config.optotypesPerLine
        fun eye(eye: Eye): EyeOutcome {
            val farId = TestId(TestKind.FAR, eye)
            val far = results.getValue(farId)
            val pinId = TestId(TestKind.PINHOLE, eye)
            val pin = results[pinId]
            val improved = pin?.takeIf { it.decision != Referral.INCONCLUSIVE }?.let {
                Pinhole.improved(
                    far.lastPassedLogMAR, it.lastPassedLogMAR, config.pinhole.improvementMinLogMAR,
                    config.far.linesLogMAR, config.lineStepLogMAR,
                )
            }
            return EyeOutcome(
                eye = eye,
                far = far,
                decision = far.decision,
                reason = ResultText.reason(far, config.far, perLine),
                pinhole = pin,
                pinholeImproved = improved,
                pinholeText = pin?.let { ResultText.pinhole(far, it, improved, config) },
                quality = ResultText.quality(
                    far, config, config.farDistanceMm, light, lightOk, trials.filter { it.test == farId },
                    remoteDrops[farId] ?: 0,
                ) + listOfNotNull(
                    remoteDrops[pinId]?.let { "Pinhole test: " + ResultText.dropText(it).replaceFirstChar { c -> c.lowercase() } },
                ),
            )
        }

        val right = eye(Eye.RIGHT)
        val left = eye(Eye.LEFT)
        val nearId = TestId(TestKind.NEAR, Eye.BOTH)
        val nearResult = results.getValue(nearId)
        val near = NearOutcome(
            result = nearResult,
            decision = nearResult.decision,
            reason = ResultText.reason(nearResult, config.near, perLine),
            quality = ResultText.quality(
                nearResult, config, config.nearDistanceMm, light, lightOk, trials.filter { it.test == nearId },
                remoteDrops[nearId] ?: 0,
            ),
        )

        val parts = listOf("right eye" to right.decision, "left eye" to left.decision, "near" to near.decision)
        val overall = overall(parts.map { it.second })
        val overallText = when (overall) {
            Referral.PASS -> ResultText.OVERALL_PASS
            Referral.REFER -> "REFER for a full eye examination (" +
                parts.filter { it.second == Referral.REFER }.joinToString(", ") { it.first } + ")."
            Referral.INCONCLUSIVE -> ResultText.OVERALL_INCONCLUSIVE
        }
        return ScreeningResult(patient, right, left, near, overall, overallText, light, lightOk, distanceOk = true, trials)
    }

    /** D28: any REFER -> REFER; else any INCONCLUSIVE -> INCONCLUSIVE; else PASS. */
    fun overall(decisions: List<Referral>): Referral = when {
        Referral.REFER in decisions -> Referral.REFER
        Referral.INCONCLUSIVE in decisions -> Referral.INCONCLUSIVE
        else -> Referral.PASS
    }
}

/** Operator-facing result text. Wording needs optometrist sign-off (D24, D29). */
object ResultText {
    const val INCONCLUSIVE_PIXEL_LIMITED =
        "Not screened. This phone's screen cannot draw the screening-line letter sharply enough at this distance, " +
            "so no result was recorded. Next step: repeat on a calibrated phone with a sharper (higher-resolution) " +
            "screen. If that is not possible today, refer for a full eye examination."
    const val INCONCLUSIVE_DOES_NOT_FIT =
        "Not screened. The largest letter does not fit on this screen at this distance, so no result was recorded. " +
            "Next step: repeat on a calibrated phone with a larger screen. If that is not possible today, refer for " +
            "a full eye examination."
    const val OVERALL_INCONCLUSIVE =
        "Screening incomplete. Follow the next step shown for each part marked INCONCLUSIVE."
    const val OVERALL_PASS = "PASS. No referral from this screening."
    const val NO_ANSWERS = "No answers were entered for this test."

    fun decisionLabel(r: Referral) = when (r) {
        Referral.PASS -> "PASS"
        Referral.REFER -> "REFER for a full eye examination"
        Referral.INCONCLUSIVE -> "INCONCLUSIVE"
    }

    fun line(logMAR: Double) = "${Snellen.sixMetre(logMAR)} (logMAR ${fmt("%.1f", logMAR)})"

    /** D45: every scored answer was "unsure", or fewer than one line was scored. */
    fun noAnswers(r: EngineResult, optotypesPerLine: Int): Boolean {
        val q = r.quality
        return q.scoredResponses < optotypesPerLine || q.unsureResponses == q.scoredResponses
    }

    fun reason(r: EngineResult, spec: TestSpec, optotypesPerLine: Int): String {
        val text = when (r.decision) {
            // D37: a PASS stopped at the screening line, so better acuity was never measured.
            Referral.PASS -> "Passed the screening line. Reads at least ${Snellen.sixMetre(spec.referralCutoffLogMAR)} " +
                "(logMAR ${fmt("%.1f", spec.referralCutoffLogMAR)} or better); smaller lines were not tested."
            Referral.REFER -> "Did not pass the screening line ${line(spec.referralCutoffLogMAR)}. " +
                when (val a = Acuity.of(r)) {
                    is Acuity.Exact -> "Smallest line passed: ${line(a.logMAR)}."
                    else -> "No line passed; the largest shown was ${line(spec.linesLogMAR.first())}."
                }
            Referral.INCONCLUSIVE -> when (r.abortReason) {
                AbortReason.LETTER_DOES_NOT_FIT -> INCONCLUSIVE_DOES_NOT_FIT
                AbortReason.PIXEL_LIMITED, null -> INCONCLUSIVE_PIXEL_LIMITED
            }
        }
        return if (noAnswers(r, optotypesPerLine)) "$NO_ANSWERS $text" else text
    }

    fun pinhole(far: EngineResult, pin: EngineResult, improved: Boolean?, config: AppConfig): String {
        if (improved == null) return "Pinhole: not screened."
        val without = Acuity.of(far)
        val with = Acuity.of(pin)
        val body = when {
            without == Acuity.NoLinePassed && with == Acuity.NoLinePassed ->
                "not improved (no line passed with or without the pinhole)."
            improved -> {
                val unaided = far.lastPassedLogMAR ?: (config.far.linesLogMAR.first() + config.lineStepLogMAR)
                val lines = ((unaided - pin.lastPassedLogMAR!!) / config.lineStepLogMAR).roundToInt()
                val count = "$lines ${if (lines == 1) "line" else "lines"}"
                "improved from ${without.snellen()} to ${with.snellen()} (${if (with is Acuity.AtLeast) "at least " else ""}$count)."
            }
            else -> "not improved (${without.snellen()} without, ${with.snellen()} with the pinhole)."
        }
        return if (noAnswers(pin, config.optotypesPerLine)) {
            "Pinhole: $NO_ANSWERS ${body.replaceFirstChar { it.uppercase() }}"
        } else {
            "Pinhole: $body"
        }
    }

    fun quality(
        r: EngineResult,
        config: AppConfig,
        distanceMm: Double,
        light: LightReadings,
        lightOk: Boolean?,
        trials: List<TrialRecord>,
        remoteDrops: Int = 0,
    ): List<String> = buildList {
        val q = r.quality
        val limitPct = fmt("%.0f", config.quality.maxUnsureFraction * 100)
        add(
            when {
                q.scoredResponses == 0 -> "Responses: none scored."
                q.responsesOk -> "Responses: OK (${q.unsureResponses} of ${q.scoredResponses} answers unsure)."
                else -> "Responses: NOT OK. ${q.unsureResponses} of ${q.scoredResponses} answers were unsure (limit $limitPct%)."
            },
        )
        if (q.practiceTrials > 0) {
            add(
                if (q.practiceCorrect == q.practiceTrials) "Practice E: answered correctly."
                else "Practice E: ${q.practiceCorrect} of ${q.practiceTrials} correct (not scored).",
            )
        }
        add(
            "Distance: confirmed by the operator at the ${distanceMm.toInt()} mm mark" +
                (if (q.distancePauses > 0) "; paused ${q.distancePauses} times to adjust distance." else "."),
        )
        add(lightLine(light, lightOk, config.minLux))
        val pad = trials.count { it.source == AnswerSource.PAD }
        if (pad > 0) add("Answers: $pad of ${trials.size} entered on the on-screen pad.")
        if (remoteDrops > 0) add(dropText(remoteDrops))
        if (config.crowding.enabled) {
            val uncrowded = trials.count { !it.crowded }
            if (uncrowded > 0) add("$uncrowded of ${trials.size} letters were shown without crowding bars (the bars did not fit the screen).")
        }
        trials.maxOfOrNull { abs(it.sizeErrorFraction) }?.let {
            add("Letter size after pixel rounding: within ${fmt("%.1f", it * 100)}% of intended.")
        }
    }

    /** D50: how often the remote dropped during one test. */
    fun dropText(drops: Int): String =
        "Remote: disconnected $drops ${if (drops == 1) "time" else "times"} during this test; " +
            "any letter showing was withdrawn unscored and shown again."

    /** D38: both readings are shown; the lower one decides. */
    fun lightLine(light: LightReadings, lightOk: Boolean?, minLux: Double): String {
        fun one(v: Double?, at: String) = if (v == null) "no reading $at" else "${fmt("%.0f", v)} lux $at"
        val readings = one(light.atRegister, "at registration") + ", " + one(light.atTestStart, "when the first eye started")
        val min = fmt("%.0f", minLux)
        return when (lightOk) {
            null -> "Light: unknown (no light reading)."
            true -> "Light: OK. $readings (minimum $min)."
            false -> "Light: NOT OK. $readings; the lower is below the minimum of $min."
        }
    }
}

/** Light sensor readings for one screening (D38). */
data class LightReadings(val atRegister: Double?, val atTestStart: Double?) {
    val lowest: Double? get() = listOfNotNull(atRegister, atTestStart).minOrNull()

    /** Null only when there is no reading at all. */
    fun ok(minLux: Double): Boolean? = lowest?.let { it >= minLux }
}

/**
 * What one test shows about acuity (D37). The engine stops at the screening
 * line, so a passed final line is only a lower bound; a value is exact only
 * when the next line was failed.
 */
sealed interface Acuity {
    data class AtLeast(val logMAR: Double) : Acuity
    data class Exact(val logMAR: Double) : Acuity
    data object NoLinePassed : Acuity
    data object NotMeasured : Acuity

    fun snellen(): String = when (this) {
        is AtLeast -> "at least ${Snellen.sixMetre(logMAR)}"
        is Exact -> Snellen.sixMetre(logMAR)
        NoLinePassed -> "no line passed"
        NotMeasured -> "not measured"
    }

    companion object {
        fun of(r: EngineResult): Acuity {
            if (r.decision == Referral.INCONCLUSIVE) return NotMeasured
            val passed = r.lastPassedLogMAR ?: return NoLinePassed
            return if (r.lines.lastOrNull()?.passed == true) AtLeast(passed) else Exact(passed)
        }
    }
}
