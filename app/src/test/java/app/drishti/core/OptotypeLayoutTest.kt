package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random

class OptotypeLayoutTest {

    private val crowding = CrowdingSpec(gapStrokes = 2.5, barThicknessStrokes = 1.0)

    private fun drawable(r: OptotypeRender) = r as? OptotypeRender.Drawable
        ?: throw AssertionError("expected Drawable, got $r")

    @Test
    fun `stroke is rounded to whole pixels and the size follows from it`() {
        // 3 m, logMAR 0.3, 15 px/mm: H = 8.7066 mm = 130.60 px, stroke 26.12 -> 26, letter 130 px
        val r = drawable(OptotypeLayout.layout(3000.0, 0.3, 15.0, Orientation.RIGHT, 3.0, null))
        assertEquals(26, r.strokePx)
        assertEquals(130, r.letterPx)
        assertEquals(OptotypeMath.optotypeHeightPx(3000.0, 0.3, 15.0), r.intendedHeightPx, 1e-9)
        assertEquals(130 / 15.0, r.actualHeightMm, 1e-12)
        assertEquals(OptotypeMath.optotypeHeightMm(3000.0, 0.3), r.intendedHeightMm, 1e-12)
        assertEquals(log10(130 / r.intendedHeightPx), r.roundingErrorLogMAR, 1e-12)
    }

    @Test
    fun `rounding is to the nearest pixel`() {
        // pxPerMm chosen so intended stroke is 4.6 -> 5, and 4.4 -> 4
        val hMm = OptotypeMath.optotypeHeightMm(400.0, 0.4)
        val up = drawable(OptotypeLayout.layout(400.0, 0.4, 4.6 * 5 / hMm, Orientation.UP, 3.0, null))
        val down = drawable(OptotypeLayout.layout(400.0, 0.4, 4.4 * 5 / hMm, Orientation.UP, 3.0, null))
        assertEquals(5, up.strokePx)
        assertEquals(4, down.strokePx)
    }

    @Test
    fun `limbs and gaps are equal whole-pixel squares on the 5x5 grid`() {
        Orientation.entries.forEach { o ->
            val r = drawable(OptotypeLayout.layout(3000.0, 0.5, 12.3, o, 3.0, null))
            val s = r.strokePx
            assertEquals(17, r.rects.size)
            r.rects.forEach { assertEquals(s, it.width); assertEquals(s, it.height) }
            val cells = r.rects.map { Cell(it.top / s, it.left / s) }.toSet()
            assertEquals(TumblingE.cells(o), cells)
            r.rects.forEach { assertEquals(0, it.left % s); assertEquals(0, it.top % s) }
            assertEquals(r.letterPx, r.footprintPx)
        }
    }

    @Test
    fun `refuses to draw below the minimum stroke`() {
        // 3 m, logMAR 0.3 at 1.5 px/mm: stroke 2.61 px. Rounds to 3, but the letter would be degraded.
        val r = OptotypeLayout.layout(3000.0, 0.3, 1.5, Orientation.RIGHT, 3.0, null)
        assertTrue(r is OptotypeRender.PixelLimited)
        r as OptotypeRender.PixelLimited
        assertEquals(OptotypeMath.optotypeHeightPx(3000.0, 0.3, 1.5) / 5, r.intendedStrokePx, 1e-12)
        // Exactly at the minimum is allowed.
        val hPx = 15.0
        val px = hPx / OptotypeMath.optotypeHeightMm(3000.0, 0.3)
        assertEquals(3, drawable(OptotypeLayout.layout(3000.0, 0.3, px, Orientation.RIGHT, 3.0, null)).strokePx)
    }

    @Test
    fun `never returns a drawable letter with a stroke below the minimum`() {
        val rnd = Random(7)
        repeat(20_000) {
            val d = 300.0 + rnd.nextDouble() * 5000
            val lm = (rnd.nextInt(16) - 3) / 10.0
            val px = 2.0 + rnd.nextDouble() * 25
            val r = OptotypeLayout.layout(d, lm, px, Orientation.LEFT, 3.0, crowding)
            if (r is OptotypeRender.Drawable) {
                assertTrue(r.strokePx >= 3)
                // Rounding moves the letter by at most half a pixel per grid cell.
                assertTrue(abs(r.letterPx - r.intendedHeightPx) <= 2.5 + 1e-9)
            } else {
                assertTrue(OptotypeMath.optotypeHeightPx(d, lm, px) / 5 < 3.0)
            }
        }
    }

    @Test
    fun `crowding adds four bars at the configured spacing`() {
        val r = drawable(OptotypeLayout.layout(3000.0, 0.3, 15.0, Orientation.RIGHT, 3.0, crowding))
        val s = r.strokePx // 26
        val gap = Math.round(2.5 * s).toInt() // 65
        val bar = s
        val m = gap + bar
        assertEquals(r.letterPx + 2 * m, r.footprintPx)
        assertEquals(17 + 4, r.rects.size)
        val letter = r.rects.take(17)
        letter.forEach { assertTrue(it.left >= m && it.right <= m + r.letterPx && it.top >= m && it.bottom <= m + r.letterPx) }
        val bars = r.bars
        assertEquals(4, bars.size)
        val left = PxRect(0, m, bar, m + r.letterPx)
        val right = PxRect(m + r.letterPx + gap, m, r.footprintPx, m + r.letterPx)
        val top = PxRect(m, 0, m + r.letterPx, bar)
        val bottom = PxRect(m, m + r.letterPx + gap, m + r.letterPx, r.footprintPx)
        assertEquals(setOf(left, right, top, bottom), bars.toSet())
    }

    @Test
    fun `uncrowded letter has no bars`() {
        val r = drawable(OptotypeLayout.layout(3000.0, 0.3, 15.0, Orientation.RIGHT, 3.0, null))
        assertTrue(r.bars.isEmpty())
    }

    /** Prints the per-line rounding error for the report; asserts it stays within half a pixel per cell. */
    @Test
    fun `rounding error table`() {
        val lines = (10 downTo 0).map { it / 10.0 }
        val cases = listOf(
            "far 3000 mm @ 10.63 px/mm (270 ppi)" to (3000.0 to 270 / 25.4),
            "far 3000 mm @ 15.75 px/mm (400 ppi)" to (3000.0 to 400 / 25.4),
            "near 400 mm @ 10.63 px/mm (270 ppi)" to (400.0 to 270 / 25.4),
            "near 400 mm @ 15.75 px/mm (400 ppi)" to (400.0 to 400 / 25.4),
        )
        val out = StringBuilder()
        for ((name, dp) in cases) {
            val (d, px) = dp
            out.appendLine(name)
            out.appendLine("  logMAR  intended_mm  drawn_mm  stroke_px  err_%    err_logMAR")
            for (lm in lines) {
                when (val r = OptotypeLayout.layout(d, lm, px, Orientation.RIGHT, 3.0, null)) {
                    is OptotypeRender.Drawable -> {
                        val pct = (r.actualHeightMm / r.intendedHeightMm - 1) * 100
                        out.appendLine("  %5.1f  %10.3f  %8.3f  %9d  %+6.2f  %+.4f"
                            .format(lm, r.intendedHeightMm, r.actualHeightMm, r.strokePx, pct, r.roundingErrorLogMAR))
                        // Intended stroke is within 0.5 px of the drawn one, so the worst case is rounding down.
                        assertTrue(abs(r.roundingErrorLogMAR) <= -log10(1 - 0.5 / r.strokePx) + 1e-12)
                    }
                    is OptotypeRender.PixelLimited ->
                        out.appendLine("  %5.1f  %10.3f  PIXEL-LIMITED (stroke %.2f px < 3)"
                            .format(lm, OptotypeMath.optotypeHeightMm(d, lm), r.intendedStrokePx))
                }
            }
        }
        println(out)
    }
}
