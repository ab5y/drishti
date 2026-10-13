package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Small pure pieces used by the flow: render planning, keys, Snellen, engine abort. */
class FlowPartsTest {

    private val px = 400 / 25.4
    private val crowding = CrowdingSpec(2.5, 1.0)

    private fun trial(logMAR: Double, distanceMm: Double = 3000.0) = Trial(
        phase = Phase.DESCENDING_LINES, lineIndex = 0, indexInLine = 0, logMAR = logMAR,
        orientation = Orientation.RIGHT, distanceMm = distanceMm,
        heightPx = OptotypeMath.optotypeHeightPx(distanceMm, logMAR, px),
    )

    // ---- RenderPlanner --------------------------------------------------------------------

    @Test
    fun `crowded when the crowded footprint fits`() {
        val p = RenderPlanner.plan(trial(0.3), px, 3.0, crowding, Viewport(2400, 1080)) as RenderPlan.Draw
        assertTrue(p.crowded)
        assertEquals(4, p.render.bars.size)
    }

    @Test
    fun `uncrowded when only the bare letter fits`() {
        val p = RenderPlanner.plan(trial(1.0), px, 3.0, crowding, Viewport(2400, 1080)) as RenderPlan.Draw
        assertFalse(p.crowded)
        assertTrue(p.render.bars.isEmpty())
    }

    @Test
    fun `fit is checked against both width and height`() {
        // 0.3 crowded footprint is ~330 px square.
        assertFalse((RenderPlanner.plan(trial(0.3), px, 3.0, crowding, Viewport(300, 2000)) as RenderPlan.Draw).crowded)
        assertFalse((RenderPlanner.plan(trial(0.3), px, 3.0, crowding, Viewport(2000, 300)) as RenderPlan.Draw).crowded)
    }

    @Test
    fun `no crowding spec means uncrowded`() {
        assertFalse((RenderPlanner.plan(trial(0.3), px, 3.0, null, Viewport(2400, 1080)) as RenderPlan.Draw).crowded)
    }

    @Test
    fun `abort when even the bare letter does not fit, or it is pixel-limited`() {
        assertEquals(
            RenderPlan.Abort(AbortReason.LETTER_DOES_NOT_FIT),
            RenderPlanner.plan(trial(1.0), px, 3.0, crowding, Viewport(2400, 600)),
        )
        assertEquals(
            RenderPlan.Abort(AbortReason.PIXEL_LIMITED),
            RenderPlanner.plan(trial(0.0, 400.0), px, 3.0, crowding, Viewport(2400, 1080)),
        )
    }

    // ---- engine abort ---------------------------------------------------------------------

    @Test
    fun `aborting an engine gives INCONCLUSIVE with the reason`() {
        val cfg = TestConfigs.shipped().engineConfig(TestKind.FAR)
        val e = TestEngine(cfg, FixedDistance(3000.0, 400.0), px, Random(1))
        e.next()
        val r = e.abort(AbortReason.LETTER_DOES_NOT_FIT)
        assertEquals(Referral.INCONCLUSIVE, r.decision)
        assertEquals(AbortReason.LETTER_DOES_NOT_FIT, r.abortReason)
        assertFalse(r.quality.pixelLimited)
        assertEquals(Phase.DONE, e.phase)
        assertTrue(e.next() is Step.Finished)

        val e2 = TestEngine(cfg, FixedDistance(3000.0, 400.0), px, Random(1))
        assertTrue(e2.abort(AbortReason.PIXEL_LIMITED).quality.pixelLimited)
    }

    @Test
    fun `engine pixel limit reports the PIXEL_LIMITED reason`() {
        val cfg = TestConfigs.shipped().engineConfig(TestKind.FAR)
        val r = (TestEngine(cfg, FixedDistance(3000.0, 400.0), 1.5, Random(1)).next() as Step.Finished).result
        assertEquals(AbortReason.PIXEL_LIMITED, r.abortReason)
    }

    @Test
    fun `respond reports whether the answer counted`() {
        val cfg = TestConfigs.shipped().engineConfig(TestKind.FAR)
        val e = TestEngine(cfg, FixedDistance(3000.0, 400.0), px, Random(1))
        e.next()
        assertTrue(e.respond(Dir.UP))
    }

    // ---- keys -----------------------------------------------------------------------------

    @Test
    fun `debouncer drops presses closer than the interval`() {
        val d = KeyDebouncer(250)
        assertTrue(d.accept(1_000))
        assertFalse(d.accept(1_100))
        assertFalse(d.accept(1_249))
        assertTrue(d.accept(1_250))
        assertTrue(d.accept(5_000))
    }

    @Test
    fun `OK is unsure while a letter is shown`() {
        assertEquals(Dir.UNSURE, RemoteAction.OK.toDir())
        assertEquals(Dir.UP, RemoteAction.UP.toDir())
        assertEquals(Dir.DOWN, RemoteAction.DOWN.toDir())
        assertEquals(Dir.LEFT, RemoteAction.LEFT.toDir())
        assertEquals(Dir.RIGHT, RemoteAction.RIGHT.toDir())
    }

    // ---- Snellen --------------------------------------------------------------------------

    @Test
    fun `approximate Snellen uses the standard 6 m series`() {
        val expected = listOf("6/6", "6/7.5", "6/9.5", "6/12", "6/15", "6/19", "6/24", "6/30", "6/38", "6/48", "6/60")
        assertEquals(expected, (0..10).map { Snellen.sixMetre(it / 10.0) })
        assertEquals("6/95", Snellen.sixMetre(1.2))
    }
}
