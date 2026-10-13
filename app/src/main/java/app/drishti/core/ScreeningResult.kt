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
    val lux: Double?,
    /** Null when there is no light reading. */
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
        lux: Double?,
        lightOk: Boolean?,
        trials: List<TrialRecord>,
    ): ScreeningResult {
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
                reason = ResultText.reason(far, config.far),
                pinhole = pin,
                pinholeImproved = improved,
                pinholeText = pin?.let { ResultText.pinhole(far, it, improved, config) },
                quality = ResultText.quality(far, config, config.farDistanceMm, lux, lightOk, trials.filter { it.test == farId }),
            )
        }

        val right = eye(Eye.RIGHT)
        val left = eye(Eye.LEFT)
        val nearId = TestId(TestKind.NEAR, Eye.BOTH)
        val nearResult = results.getValue(nearId)
        val near = NearOutcome(
            result = nearResult,
            decision = nearResult.decision,
            reason = ResultText.reason(nearResult, config.near),
            quality = ResultText.quality(nearResult, config, config.nearDistanceMm, lux, lightOk, trials.filter { it.test == nearId }),
        )

        val parts = listOf("right eye" to right.decision, "left eye" to left.decision, "near" to near.decision)
        val overall = overall(parts.map { it.second })
        val overallText = when (overall) {
            Referral.PASS -> ResultText.OVERALL_PASS
            Referral.REFER -> "REFER for a full eye examination (" +
                parts.filter { it.second == Referral.REFER }.joinToString(", ") { it.first } + ")."
            Referral.INCONCLUSIVE -> ResultText.OVERALL_INCONCLUSIVE
        }
        return ScreeningResult(patient, right, left, near, overall, overallText, lux, lightOk, distanceOk = true, trials)
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

    fun decisionLabel(r: Referral) = when (r) {
        Referral.PASS -> "PASS"
        Referral.REFER -> "REFER for a full eye examination"
        Referral.INCONCLUSIVE -> "INCONCLUSIVE"
    }

    fun line(logMAR: Double) = "${Snellen.sixMetre(logMAR)} (logMAR ${fmt("%.1f", logMAR)})"

    fun reason(r: EngineResult, spec: TestSpec): String = when (r.decision) {
        Referral.PASS -> "Passed the screening line ${line(spec.referralCutoffLogMAR)}."
        Referral.REFER -> "Did not pass the screening line ${line(spec.referralCutoffLogMAR)}. " +
            (r.lastPassedLogMAR?.let { "Smallest line passed: ${line(it)}." }
                ?: "No line passed; the largest shown was ${line(spec.linesLogMAR.first())}.")
        Referral.INCONCLUSIVE -> when (r.abortReason) {
            AbortReason.LETTER_DOES_NOT_FIT -> INCONCLUSIVE_DOES_NOT_FIT
            AbortReason.PIXEL_LIMITED, null -> INCONCLUSIVE_PIXEL_LIMITED
        }
    }

    fun pinhole(far: EngineResult, pin: EngineResult, improved: Boolean?, config: AppConfig): String {
        if (improved == null) return "Pinhole: not screened."
        val without = far.lastPassedLogMAR
        val with = pin.lastPassedLogMAR
        if (without == null && with == null) return "Pinhole: not improved (no line passed with or without the pinhole)."
        fun name(v: Double?) = v?.let { Snellen.sixMetre(it) } ?: "no line passed"
        return if (improved) {
            val unaided = without ?: (config.far.linesLogMAR.first() + config.lineStepLogMAR)
            val lines = ((unaided - with!!) / config.lineStepLogMAR).roundToInt()
            "Pinhole: improved from ${name(without)} to ${name(with)} ($lines ${if (lines == 1) "line" else "lines"})."
        } else {
            "Pinhole: not improved (${name(without)} without, ${name(with)} with the pinhole)."
        }
    }

    fun quality(
        r: EngineResult,
        config: AppConfig,
        distanceMm: Double,
        lux: Double?,
        lightOk: Boolean?,
        trials: List<TrialRecord>,
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
        add(
            when (lightOk) {
                null -> "Light: unknown (no light reading)."
                true -> "Light: ${fmt("%.0f", lux)} lux (minimum ${fmt("%.0f", config.minLux)})."
                false -> "Light: ${fmt("%.0f", lux)} lux, below the minimum of ${fmt("%.0f", config.minLux)}."
            },
        )
        if (config.crowding.enabled) {
            val uncrowded = trials.count { !it.crowded }
            if (uncrowded > 0) add("$uncrowded of ${trials.size} letters were shown without crowding bars (the bars did not fit the screen).")
        }
        trials.maxOfOrNull { abs(it.sizeErrorFraction) }?.let {
            add("Letter size after pixel rounding: within ${fmt("%.1f", it * 100)}% of intended.")
        }
    }
}
