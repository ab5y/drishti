package app.drishti.core

import kotlin.math.pow
import kotlin.math.tan

/**
 * Optotype geometry (build sheet section 04). A logMAR 0 tumbling E subtends
 * 5 arcmin; each limb and gap 1 arcmin on a 5x5 grid. These are the definition
 * of logMAR, not clinical levers.
 *
 * Size always uses the CARD-calibrated pxPerMm, never OEM densityDpi/xdpi.
 */
object OptotypeMath {
    /** The tumbling E is drawn on a GRID x GRID grid: stroke = gap = height / GRID. */
    const val GRID = 5

    private val HALF_ANGLE_RAD = Math.toRadians((5.0 / 2.0) / 60.0) // 2.5 arcmin

    /** H_mm = 2 * d_mm * tan(2.5') * 10^logMAR */
    fun optotypeHeightMm(distanceMm: Double, logMAR: Double): Double {
        require(distanceMm > 0 && distanceMm.isFinite()) { "distanceMm must be > 0, was $distanceMm" }
        require(logMAR.isFinite()) { "logMAR must be finite, was $logMAR" }
        return 2.0 * distanceMm * tan(HALF_ANGLE_RAD) * 10.0.pow(logMAR)
    }

    /** H_px = H_mm * pxPerMm */
    fun optotypeHeightPx(distanceMm: Double, logMAR: Double, pxPerMm: Double): Double {
        require(pxPerMm > 0 && pxPerMm.isFinite()) { "pxPerMm must be > 0, was $pxPerMm" }
        return optotypeHeightMm(distanceMm, logMAR) * pxPerMm
    }

    fun strokePx(heightPx: Double): Double = heightPx / GRID

    /** False means the letter would be degraded: pixel-limited, must not be shown or scored. */
    fun minStrokeOk(heightPx: Double, minStrokePx: Double): Boolean = strokePx(heightPx) >= minStrokePx
}
