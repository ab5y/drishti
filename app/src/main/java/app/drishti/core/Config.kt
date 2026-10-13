package app.drishti.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Typed view of assets/config.json. Every clinical lever lives here; nothing
 * clinical is hardcoded in engine logic. All fields are required (no Kotlin
 * defaults), so a missing field fails parsing instead of silently defaulting.
 */
@Serializable
data class AppConfig(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("optotype") val optotype: Optotype,
    @SerialName("crowding") val crowding: CrowdingConfig,
    @SerialName("engine") val engine: EngineKind,
    @SerialName("distance_method") val distanceMethod: DistanceMethod,
    @SerialName("far_distance_mm") val farDistanceMm: Double,
    @SerialName("near_distance_mm") val nearDistanceMm: Double,
    @SerialName("min_stroke_px") val minStrokePx: Double,
    @SerialName("min_lux") val minLux: Double,
    /** Window brightness (0..1] during a test; build sheet section 10 uses 1.0. */
    @SerialName("test_brightness") val testBrightness: Double,
    @SerialName("optotypes_per_line") val optotypesPerLine: Int,
    @SerialName("pass_min_correct") val passMinCorrect: Int,
    @SerialName("line_step_logmar") val lineStepLogMAR: Double,
    @SerialName("eye_order") val eyeOrder: EyeOrder,
    @SerialName("quality") val quality: QualityConfig,
    @SerialName("far") val far: TestSpec,
    @SerialName("near") val near: TestSpec,
    @SerialName("pinhole") val pinhole: PinholeConfig,
) {
    /** Builds the engine configuration for one test kind (far, near or pinhole). */
    fun engineConfig(kind: TestKind): EngineConfig {
        val spec = when (kind) {
            TestKind.FAR -> far
            TestKind.NEAR -> near
            TestKind.PINHOLE -> pinhole.toTestSpec()
        }
        return EngineConfig(
            kind = kind,
            distanceMode = kind.distanceMode,
            practiceLogMAR = spec.practiceLogMAR,
            practiceTrials = spec.practiceTrials,
            linesLogMAR = spec.linesLogMAR,
            referralCutoffLogMAR = spec.referralCutoffLogMAR,
            optotypesPerLine = optotypesPerLine,
            passMinCorrect = passMinCorrect,
            minStrokePx = minStrokePx,
            maxUnsureFraction = quality.maxUnsureFraction,
        )
    }

    /** Crowding bars for the renderer, or null when crowding is off. */
    fun crowdingSpec(): CrowdingSpec? =
        if (crowding.enabled) CrowdingSpec(crowding.barGapStrokes, crowding.barThicknessStrokes) else null

    /** Returns every problem found; empty means valid. */
    fun validationErrors(): List<String> = buildList {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            add("schema_version must be $SUPPORTED_SCHEMA_VERSION, was $schemaVersion")
        }
        if (engine != EngineKind.DESCENDING_LINES) {
            add("engine '${engine.wireName}' is not implemented; use 'descending_lines'")
        }
        if (distanceMethod != DistanceMethod.ROPE) {
            add("distance_method '${distanceMethod.wireName}' needs the camera module, which is not built; use 'rope'")
        }
        if (!(farDistanceMm > 0)) add("far_distance_mm must be > 0, was $farDistanceMm")
        if (!(nearDistanceMm > 0)) add("near_distance_mm must be > 0, was $nearDistanceMm")
        if (nearDistanceMm >= farDistanceMm) {
            add("near_distance_mm ($nearDistanceMm) must be less than far_distance_mm ($farDistanceMm)")
        }
        if (!(minStrokePx > 0)) add("min_stroke_px must be > 0, was $minStrokePx")
        if (!(minLux >= 0)) add("min_lux must be >= 0, was $minLux")
        if (!(testBrightness > 0 && testBrightness <= 1)) add("test_brightness must be in (0, 1], was $testBrightness")
        if (optotypesPerLine < 1) add("optotypes_per_line must be >= 1, was $optotypesPerLine")
        if (passMinCorrect < 1 || passMinCorrect > optotypesPerLine) {
            add("pass_min_correct must be in 1..optotypes_per_line ($optotypesPerLine), was $passMinCorrect")
        }
        if (!(lineStepLogMAR > 0)) add("line_step_logmar must be > 0, was $lineStepLogMAR")
        if (!(quality.maxUnsureFraction in 0.0..1.0)) {
            add("quality.max_unsure_fraction must be in 0..1, was ${quality.maxUnsureFraction}")
        }
        if (!(crowding.barGapStrokes > 0)) add("crowding.bar_gap_strokes must be > 0, was ${crowding.barGapStrokes}")
        if (!(crowding.barThicknessStrokes > 0)) {
            add("crowding.bar_thickness_strokes must be > 0, was ${crowding.barThicknessStrokes}")
        }
        if (!(pinhole.improvementMinLogMAR >= 0)) {
            add("pinhole.improvement_min_logmar must be >= 0, was ${pinhole.improvementMinLogMAR}")
        }
        addAll(far.validationErrors("far", lineStepLogMAR))
        addAll(near.validationErrors("near", lineStepLogMAR))
        addAll(pinhole.toTestSpec().validationErrors("pinhole", lineStepLogMAR))
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = false // a typo'd key is an error, not a silent default
            isLenient = false
        }

        /** Parses and validates. Throws [ConfigException] listing every problem. */
        fun parse(text: String): AppConfig {
            val config = try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw ConfigException(listOf("config.json could not be parsed: ${e.message}"), e)
            } catch (e: IllegalArgumentException) {
                throw ConfigException(listOf("config.json could not be parsed: ${e.message}"), e)
            }
            val errors = config.validationErrors()
            if (errors.isNotEmpty()) throw ConfigException(errors)
            return config
        }
    }
}

/** One test's line set (far, near, pinhole each have their own). */
@Serializable
data class TestSpec(
    @SerialName("practice_logmar") val practiceLogMAR: Double,
    @SerialName("practice_trials") val practiceTrials: Int,
    @SerialName("lines_logmar") val linesLogMAR: List<Double>,
    @SerialName("referral_cutoff_logmar") val referralCutoffLogMAR: Double,
) {
    fun validationErrors(name: String, step: Double): List<String> = buildList {
        if (practiceTrials < 0) add("$name.practice_trials must be >= 0, was $practiceTrials")
        if (!practiceLogMAR.isFinite()) add("$name.practice_logmar must be a number")
        if (linesLogMAR.isEmpty()) {
            add("$name.lines_logmar must not be empty")
            return@buildList
        }
        if (linesLogMAR.any { !it.isFinite() }) add("$name.lines_logmar must all be numbers")
        linesLogMAR.zipWithNext().forEachIndexed { i, (a, b) ->
            if (abs((a - b) - step) > LOGMAR_EPS) {
                add("$name.lines_logmar must descend by line_step_logmar ($step); entries $i->${i + 1} are $a -> $b")
            }
        }
        if (linesLogMAR.none { logMarEquals(it, referralCutoffLogMAR) }) {
            add("$name.referral_cutoff_logmar ($referralCutoffLogMAR) must be one of $name.lines_logmar")
        }
    }
}

@Serializable
data class PinholeConfig(
    @SerialName("on_fail") val onFail: Boolean,
    @SerialName("improvement_min_logmar") val improvementMinLogMAR: Double,
    @SerialName("practice_logmar") val practiceLogMAR: Double,
    @SerialName("practice_trials") val practiceTrials: Int,
    @SerialName("lines_logmar") val linesLogMAR: List<Double>,
    @SerialName("referral_cutoff_logmar") val referralCutoffLogMAR: Double,
) {
    fun toTestSpec() = TestSpec(practiceLogMAR, practiceTrials, linesLogMAR, referralCutoffLogMAR)
}

/**
 * Bars on all four sides of the letter. Gap (letter edge to bar) and bar
 * thickness are in units of the letter's stroke (1/5 letter height).
 */
@Serializable
data class CrowdingConfig(
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("bar_gap_strokes") val barGapStrokes: Double,
    @SerialName("bar_thickness_strokes") val barThicknessStrokes: Double,
)

@Serializable
data class QualityConfig(
    /** responses_ok requires unsure / scored <= this. */
    @SerialName("max_unsure_fraction") val maxUnsureFraction: Double,
)

@Serializable
enum class Optotype { @SerialName("tumbling_E") TUMBLING_E }

@Serializable
enum class EngineKind(val wireName: String) {
    @SerialName("descending_lines") DESCENDING_LINES("descending_lines"),
    @SerialName("adaptive_staircase") ADAPTIVE_STAIRCASE("adaptive_staircase"),
}

@Serializable
enum class DistanceMethod(val wireName: String) {
    @SerialName("rope") ROPE("rope"),
    @SerialName("stand_card") STAND_CARD("stand_card"),
    @SerialName("held_card") HELD_CARD("held_card"),
}

@Serializable
enum class EyeOrder {
    @SerialName("right_first") RIGHT_FIRST,
    @SerialName("left_first") LEFT_FIRST,
    @SerialName("random") RANDOM,
}

class ConfigException(val errors: List<String>, cause: Throwable? = null) :
    IllegalStateException("Invalid config.json:\n - " + errors.joinToString("\n - "), cause)

/** logMAR values come from decimal JSON; compare with a tolerance far below one line (0.1). */
internal const val LOGMAR_EPS = 1e-6

internal fun logMarEquals(a: Double, b: Double) = abs(a - b) <= LOGMAR_EPS

/** Locale-independent formatting for text built in core. */
internal fun fmt(pattern: String, vararg args: Any?): String = String.format(java.util.Locale.ROOT, pattern, *args)

/** Rounds a logMAR to 2 dp for display / storage, removing binary noise. */
fun roundLogMAR(v: Double): Double = (v * 100).roundToLong() / 100.0
