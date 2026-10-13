package app.drishti.core

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

/** Integer pixel rectangle; right/bottom exclusive. */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Crowding bars on all four sides; sizes are in units of the letter's stroke. */
data class CrowdingSpec(val gapStrokes: Double, val barThicknessStrokes: Double) {
    init {
        require(gapStrokes > 0) { "gapStrokes must be > 0" }
        require(barThicknessStrokes > 0) { "barThicknessStrokes must be > 0" }
    }
}

sealed interface OptotypeRender {
    /**
     * A letter that can be drawn exactly. All geometry is whole pixels, relative
     * to the footprint's top-left corner. Letter size is 5 * [strokePx], which
     * can differ from the intended size by up to half a pixel per grid cell.
     */
    data class Drawable(
        val strokePx: Int,
        val letterPx: Int,
        val footprintPx: Int,
        /** The E's 17 cells, then the crowding bars (if any). */
        val rects: List<PxRect>,
        val bars: List<PxRect>,
        val intendedHeightPx: Double,
        val intendedHeightMm: Double,
        val actualHeightMm: Double,
        /** log10(actual / intended): positive means drawn larger (easier). */
        val roundingErrorLogMAR: Double,
    ) : OptotypeRender

    /** Stroke would be below the minimum: draw nothing, score nothing. */
    data class PixelLimited(val intendedStrokePx: Double, val minStrokePx: Double) : OptotypeRender
}

object OptotypeLayout {
    fun layout(
        distanceMm: Double,
        logMAR: Double,
        pxPerMm: Double,
        orientation: Orientation,
        minStrokePx: Double,
        crowding: CrowdingSpec?,
    ): OptotypeRender {
        val intendedPx = OptotypeMath.optotypeHeightPx(distanceMm, logMAR, pxPerMm)
        val intendedStroke = OptotypeMath.strokePx(intendedPx)
        if (!OptotypeMath.minStrokeOk(intendedPx, minStrokePx)) {
            return OptotypeRender.PixelLimited(intendedStroke, minStrokePx)
        }

        val s = floor(intendedStroke + 0.5).toInt() // nearest whole pixel
        val letter = s * OptotypeMath.GRID
        val gap = if (crowding != null) (crowding.gapStrokes * s).roundToInt().coerceAtLeast(1) else 0
        val bar = if (crowding != null) (crowding.barThicknessStrokes * s).roundToInt().coerceAtLeast(1) else 0
        val m = gap + bar
        val footprint = letter + 2 * m

        val cells = TumblingE.cells(orientation)
            .sortedWith(compareBy({ it.row }, { it.col }))
            .map { PxRect(m + it.col * s, m + it.row * s, m + (it.col + 1) * s, m + (it.row + 1) * s) }
        val bars = if (crowding == null) emptyList() else listOf(
            PxRect(m, 0, m + letter, bar), // top
            PxRect(0, m, bar, m + letter), // left
            PxRect(m + letter + gap, m, footprint, m + letter), // right
            PxRect(m, m + letter + gap, m + letter, footprint), // bottom
        )

        return OptotypeRender.Drawable(
            strokePx = s,
            letterPx = letter,
            footprintPx = footprint,
            rects = cells + bars,
            bars = bars,
            intendedHeightPx = intendedPx,
            intendedHeightMm = intendedPx / pxPerMm,
            actualHeightMm = letter / pxPerMm,
            roundingErrorLogMAR = log10(letter / intendedPx),
        )
    }
}
