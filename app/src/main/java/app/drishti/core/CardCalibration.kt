package app.drishti.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Screen pixel pitch from an ID-1 card held to the glass (build sheet section 02).
 * The saved value is always the card match. xdpi only seeds the rectangle and
 * feeds a sanity warning; it is never saved as pxPerMm.
 */
object CardCalibration {
    /** ISO/IEC 7810 ID-1 card. */
    const val ID1_LONG_EDGE_MM = 85.6
    const val ID1_SHORT_EDGE_MM = 53.98
    const val MM_PER_INCH = 25.4

    /** Warn (do not block) when the card result differs from xdpi by more than this. */
    const val XDPI_WARN_FRACTION = 0.15

    fun pxPerMm(matchedEdgePx: Double): Double {
        require(matchedEdgePx > 0 && matchedEdgePx.isFinite()) { "matchedEdgePx must be > 0, was $matchedEdgePx" }
        return matchedEdgePx / ID1_LONG_EDGE_MM
    }

    /** OEM-reported pitch; null if the OEM value is unusable. Seed and sanity check only. */
    fun pxPerMmFromDpi(dpi: Double): Double? =
        if (dpi > 0 && dpi.isFinite()) dpi / MM_PER_INCH else null

    /** Initial rectangle long edge in px from xdpi, or null if xdpi is unusable. */
    fun seedEdgePx(xdpi: Double): Int? = pxPerMmFromDpi(xdpi)?.let { (it * ID1_LONG_EDGE_MM).roundToInt() }

    fun check(pxPerMm: Double, xdpi: Double): SanityCheck {
        val ref = pxPerMmFromDpi(xdpi) ?: return SanityCheck(null, null, warn = false)
        val deviation = pxPerMm / ref - 1.0
        return SanityCheck(ref, deviation, warn = abs(deviation) > XDPI_WARN_FRACTION)
    }
}

/** [deviation] is (card / xdpi) - 1; null when xdpi is unusable. */
data class SanityCheck(val xdpiPxPerMm: Double?, val deviation: Double?, val warn: Boolean)

/** Real display resolution, independent of orientation. */
data class DisplaySize(val longPx: Int, val shortPx: Int) {
    companion object {
        fun of(widthPx: Int, heightPx: Int) = DisplaySize(maxOf(widthPx, heightPx), minOf(widthPx, heightPx))
    }
}

/** A saved card calibration. Only valid for the device id and display mode it was made on. */
data class ScreenCalibration(
    val deviceId: String,
    val pxPerMm: Double,
    val display: DisplaySize,
    /** xdpi at calibration time, kept for the record only. */
    val xdpi: Double,
    val calibratedAtEpochMs: Long,
)

sealed interface Readiness {
    data class Ready(val pxPerMm: Double) : Readiness
    data object NotCalibrated : Readiness
    /** Resolution changed (e.g. FHD/QHD switch), so pixel pitch changed: recalibrate. */
    data class DisplayChanged(val calibrated: DisplaySize, val current: DisplaySize) : Readiness
}

/** The test flow may start only from [Readiness.Ready]. */
object TestGate {
    fun check(calibration: ScreenCalibration?, deviceId: String, display: DisplaySize): Readiness = when {
        calibration == null || calibration.deviceId != deviceId -> Readiness.NotCalibrated
        calibration.display != display -> Readiness.DisplayChanged(calibration.display, display)
        else -> Readiness.Ready(calibration.pxPerMm)
    }
}
