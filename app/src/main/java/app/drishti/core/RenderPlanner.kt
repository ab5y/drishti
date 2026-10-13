package app.drishti.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** The area available for the letter, in px. */
data class Viewport(val widthPx: Int, val heightPx: Int)

sealed interface RenderPlan {
    data class Draw(val render: OptotypeRender.Drawable, val crowded: Boolean) : RenderPlan
    /** The letter cannot be shown correctly; the test ends INCONCLUSIVE. */
    data class Abort(val reason: AbortReason) : RenderPlan
}

/**
 * Chooses how to draw a trial: crowded if the crowded footprint fits the
 * viewport, otherwise uncrowded (decision D23), otherwise abort. Size and
 * rounding rule are always those of [OptotypeLayout].
 */
object RenderPlanner {
    fun plan(trial: Trial, pxPerMm: Double, minStrokePx: Double, crowding: CrowdingSpec?, viewport: Viewport): RenderPlan {
        fun fits(d: OptotypeRender.Drawable) = d.footprintPx <= viewport.widthPx && d.footprintPx <= viewport.heightPx
        fun layout(c: CrowdingSpec?) =
            OptotypeLayout.layout(trial.distanceMm, trial.logMAR, pxPerMm, trial.orientation, minStrokePx, c)

        if (crowding != null) {
            val crowded = layout(crowding)
            if (crowded is OptotypeRender.Drawable && fits(crowded)) return RenderPlan.Draw(crowded, crowded = true)
        }
        return when (val bare = layout(null)) {
            is OptotypeRender.PixelLimited -> RenderPlan.Abort(AbortReason.PIXEL_LIMITED)
            is OptotypeRender.Drawable ->
                if (fits(bare)) RenderPlan.Draw(bare, crowded = false) else RenderPlan.Abort(AbortReason.LETTER_DOES_NOT_FIT)
        }
    }
}

/** Approximate Snellen notation at 6 m, for the result screen. */
object Snellen {
    private val standard = listOf("6/6", "6/7.5", "6/9.5", "6/12", "6/15", "6/19", "6/24", "6/30", "6/38", "6/48", "6/60")

    fun sixMetre(logMAR: Double): String {
        val tenth = (logMAR * 10).roundToInt()
        if (abs(logMAR * 10 - tenth) < 1e-6 && tenth in standard.indices) return standard[tenth]
        val denominator = 6 * 10.0.pow(logMAR)
        return if (denominator >= 10) "6/${denominator.roundToInt()}" else "6/" + fmt("%.1f", denominator)
    }
}
