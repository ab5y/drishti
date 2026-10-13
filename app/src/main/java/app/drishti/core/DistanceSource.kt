package app.drishti.core

enum class DistanceMode { FAR, NEAR }

/**
 * Where the eye-to-screen distance comes from. The engine and renderer depend
 * only on this. A null or unstable distance means: pause, show "adjust
 * distance", never render a letter at a guessed distance.
 */
interface DistanceSource {
    fun currentDistanceMm(mode: DistanceMode): Double?
    fun isStable(): Boolean
}

/** Rope / floor-mark distance: exact by construction, always stable. */
class FixedDistance(private val farMm: Double, private val nearMm: Double) : DistanceSource {
    init {
        require(farMm > 0 && farMm.isFinite()) { "farMm must be > 0, was $farMm" }
        require(nearMm > 0 && nearMm.isFinite()) { "nearMm must be > 0, was $nearMm" }
    }

    override fun currentDistanceMm(mode: DistanceMode): Double = when (mode) {
        DistanceMode.FAR -> farMm
        DistanceMode.NEAR -> nearMm
    }

    override fun isStable(): Boolean = true

    companion object {
        fun from(config: AppConfig) = FixedDistance(config.farDistanceMm, config.nearDistanceMm)
    }
}
