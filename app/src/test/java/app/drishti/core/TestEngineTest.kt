package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class TestEngineTest {

    // ---- fixtures (test values, not app logic) -------------------------------------------

    private val tenToZero = (10 downTo 0).map { it / 10.0 } // 6/60 -> 6/6 in 0.1 logMAR

    private fun config(
        kind: TestKind = TestKind.FAR,
        practiceTrials: Int = 1,
        practiceLogMAR: Double = 1.0,
        lines: List<Double> = tenToZero,
        cutoff: Double = 0.3,
        perLine: Int = 5,
        passMin: Int = 4,
        minStrokePx: Double = 3.0,
        maxUnsure: Double = 0.2,
    ) = EngineConfig(
        kind = kind,
        distanceMode = kind.distanceMode,
        practiceLogMAR = practiceLogMAR,
        practiceTrials = practiceTrials,
        linesLogMAR = lines,
        referralCutoffLogMAR = cutoff,
        optotypesPerLine = perLine,
        passMinCorrect = passMin,
        minStrokePx = minStrokePx,
        maxUnsureFraction = maxUnsure,
    )

    private val rope = FixedDistance(farMm = 3000.0, nearMm = 400.0)
    private val pxPerMm = 15.0 // ~381 ppi

    private fun engine(
        cfg: EngineConfig = config(),
        distance: DistanceSource = rope,
        px: Double = pxPerMm,
        seed: Int = 1,
    ) = TestEngine(cfg, distance, px, Random(seed))

    private class Run(val result: EngineResult, val trials: List<Trial>) {
        val scored get() = trials.filter { it.phase == Phase.DESCENDING_LINES }
    }

    private fun run(e: TestEngine, responder: (Trial) -> Dir): Run {
        val trials = mutableListOf<Trial>()
        repeat(10_000) {
            when (val s = e.next()) {
                is Step.Present -> {
                    trials += s.trial
                    e.respond(responder(s.trial))
                }
                is Step.Finished -> return Run(s.result, trials)
                Step.AdjustDistance -> fail("unexpected distance pause")
            }
        }
        fail("engine did not terminate")
        throw AssertionError()
    }

    private fun Orientation.asDir() = when (this) {
        Orientation.UP -> Dir.UP
        Orientation.DOWN -> Dir.DOWN
        Orientation.LEFT -> Dir.LEFT
        Orientation.RIGHT -> Dir.RIGHT
    }

    private fun Orientation.wrongDir() = when (this) {
        Orientation.UP -> Dir.DOWN
        Orientation.DOWN -> Dir.UP
        Orientation.LEFT -> Dir.RIGHT
        Orientation.RIGHT -> Dir.LEFT
    }

    private val perfect: (Trial) -> Dir = { it.orientation.asDir() }
    private val alwaysWrong: (Trial) -> Dir = { it.orientation.wrongDir() }
    private val allUnsure: (Trial) -> Dir = { Dir.UNSURE }
    private val directions = listOf(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT)
    private fun guesser(seed: Int): (Trial) -> Dir {
        val r = Random(seed * 7919 + 13)
        return { directions[r.nextInt(4)] }
    }

    // ---- required scenarios --------------------------------------------------------------

    @Test
    fun `perfect responder passes every line down to the referral line`() {
        val r = run(engine(), perfect)
        assertEquals(Referral.PASS, r.result.decision)
        assertEquals(0.3, r.result.lastPassedLogMAR!!, 1e-9)
        assertEquals(listOf(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3), r.result.lines.map { roundLogMAR(it.logMAR) })
        assertTrue(r.result.lines.all { it.passed && it.correct == 5 && it.shown == 5 })
        assertEquals(40, r.result.quality.scoredResponses)
        assertEquals(0, r.result.quality.unsureResponses)
        assertTrue(r.result.quality.responsesOk)
        assertFalse(r.result.quality.pixelLimited)
    }

    @Test
    fun `always-wrong responder fails the first line and is referred`() {
        val r = run(engine(), alwaysWrong)
        assertEquals(Referral.REFER, r.result.decision)
        assertNull(r.result.lastPassedLogMAR)
        assertEquals(1, r.result.lines.size)
        assertEquals(0, r.result.lines[0].correct)
        assertFalse(r.result.lines[0].passed)
        assertEquals(5, r.result.quality.scoredResponses)
    }

    @Test
    fun `all-unsure responder is referred and flagged as poor responses`() {
        val r = run(engine(), allUnsure)
        assertEquals(Referral.REFER, r.result.decision)
        assertNull(r.result.lastPassedLogMAR)
        assertEquals(5, r.result.quality.scoredResponses)
        assertEquals(5, r.result.quality.unsureResponses)
        assertFalse(r.result.quality.responsesOk)
    }

    @Test
    fun `random guesser false-pass rate per line is about 1_6 percent for 4 of 5`() {
        // Analytic: P(X >= 4), X ~ Bin(5, 0.25) = 5*(1/4)^4*(3/4) + (1/4)^5 = 1/64 = 1.5625%
        val analytic = 5 * Math.pow(0.25, 4.0) * 0.75 + Math.pow(0.25, 5.0)
        assertEquals(0.015625, analytic, 1e-12)

        val runs = 20_000
        var firstLinePassed = 0
        var linesShown = 0
        var linesPassed = 0
        var overallPasses = 0
        for (seed in 0 until runs) {
            val r = run(engine(seed = seed), guesser(seed))
            if (r.result.lines.first().passed) firstLinePassed++
            linesShown += r.result.lines.size
            linesPassed += r.result.lines.count { it.passed }
            if (r.result.decision == Referral.PASS) overallPasses++
        }
        val firstLineRate = firstLinePassed.toDouble() / runs
        val perLineRate = linesPassed.toDouble() / linesShown
        println("random guesser: first-line false-pass = %.4f%%, per-line = %.4f%% (analytic 1.5625%%), overall PASS = %d / %d"
            .format(firstLineRate * 100, perLineRate * 100, overallPasses, runs))
        // SE at n=20000 is ~0.088 percentage points; +/-0.4 pp is > 4 SE.
        assertEquals(analytic, firstLineRate, 0.004)
        assertEquals(analytic, perLineRate, 0.004)
        // Needs 8 consecutive line passes (1.0 -> 0.3): ~(1/64)^8, effectively never.
        assertEquals(0, overallPasses)
    }

    @Test
    fun `stops at the referral line, never presenting smaller lines`() {
        val r = run(engine(config(cutoff = 0.3)), perfect)
        assertTrue(r.scored.all { it.logMAR >= 0.3 - 1e-9 })
        assertEquals(0.3, r.scored.last().logMAR, 1e-9)
        assertEquals(1 + 8 * 5, r.trials.size)

        val r5 = run(engine(config(cutoff = 0.5)), perfect)
        assertEquals(0.5, r5.result.lastPassedLogMAR!!, 1e-9)
        assertTrue(r5.scored.all { it.logMAR >= 0.5 - 1e-9 })

        val r0 = run(engine(config(cutoff = 0.0)), perfect)
        assertEquals(0.0, r0.result.lastPassedLogMAR!!, 1e-9)
        assertEquals(11, r0.result.lines.size)
    }

    @Test
    fun `practice is not scored`() {
        // Practice answered wrong, then perfect: identical outcome to a perfect run.
        var first = true
        val r = run(engine()) { t ->
            if (t.phase == Phase.PRACTICE) {
                assertTrue(first); first = false
                t.orientation.wrongDir()
            } else t.orientation.asDir()
        }
        assertEquals(Phase.PRACTICE, r.trials.first().phase)
        assertEquals(1.0, r.trials.first().logMAR, 1e-9)
        assertEquals(Referral.PASS, r.result.decision)
        assertEquals(0.3, r.result.lastPassedLogMAR!!, 1e-9)
        assertEquals(40, r.result.quality.scoredResponses)
        assertEquals(1, r.result.quality.practiceTrials)
        assertEquals(0, r.result.quality.practiceCorrect)
        assertTrue(r.result.lines.all { it.correct == 5 })

        // Practice UNSURE does not count toward unsure either.
        val ru = run(engine()) { t -> if (t.phase == Phase.PRACTICE) Dir.UNSURE else t.orientation.asDir() }
        assertEquals(0, ru.result.quality.unsureResponses)
        assertTrue(ru.result.quality.responsesOk)
    }

    @Test
    fun `zero practice trials starts directly on the first line`() {
        val e = engine(config(practiceTrials = 0))
        assertEquals(Phase.DESCENDING_LINES, e.phase)
        val s = e.next() as Step.Present
        assertEquals(Phase.DESCENDING_LINES, s.trial.phase)
        assertEquals(1.0, s.trial.logMAR, 1e-9)
    }

    // ---- pass rule ---------------------------------------------------------------------

    @Test
    fun `exactly 4 of 5 passes a line, 3 of 5 fails`() {
        // 4/5 on every line -> PASS
        val r4 = run(engine()) { t ->
            if (t.phase == Phase.DESCENDING_LINES && t.indexInLine == 0) t.orientation.wrongDir() else t.orientation.asDir()
        }
        assertEquals(Referral.PASS, r4.result.decision)
        assertTrue(r4.result.lines.all { it.correct == 4 && it.passed })

        // 3/5 on the first line -> fail, stop
        val r3 = run(engine()) { t ->
            if (t.phase == Phase.DESCENDING_LINES && t.indexInLine < 2) t.orientation.wrongDir() else t.orientation.asDir()
        }
        assertEquals(Referral.REFER, r3.result.decision)
        assertEquals(1, r3.result.lines.size)
        assertEquals(3, r3.result.lines[0].correct)
    }

    @Test
    fun `failing a line above the cutoff records the last passed line and refers`() {
        // Sees 0.6 and larger perfectly, blind below.
        val r = run(engine()) { t -> if (t.logMAR >= 0.6 - 1e-9) t.orientation.asDir() else t.orientation.wrongDir() }
        assertEquals(Referral.REFER, r.result.decision)
        assertEquals(0.6, r.result.lastPassedLogMAR!!, 1e-9)
        assertEquals(0.5, r.result.lines.last().logMAR, 1e-9)
        assertFalse(r.result.lines.last().passed)
    }

    @Test
    fun `unsure counts as incorrect`() {
        // Two unsure per line -> 3/5 -> fail first line.
        val r = run(engine()) { t ->
            if (t.phase == Phase.DESCENDING_LINES && t.indexInLine < 2) Dir.UNSURE else t.orientation.asDir()
        }
        assertEquals(Referral.REFER, r.result.decision)
        assertEquals(3, r.result.lines[0].correct)
        assertEquals(2, r.result.quality.unsureResponses)
    }

    @Test
    fun `responsesOk uses the configured unsure fraction`() {
        // One unsure per line over 8 lines = 8/40 = 0.2.
        val oneUnsurePerLine: (Trial) -> Dir = { t ->
            if (t.phase == Phase.DESCENDING_LINES && t.indexInLine == 0) Dir.UNSURE else t.orientation.asDir()
        }
        assertTrue(run(engine(config(maxUnsure = 0.2)), oneUnsurePerLine).result.quality.responsesOk)
        assertFalse(run(engine(config(maxUnsure = 0.1)), oneUnsurePerLine).result.quality.responsesOk)
    }

    // ---- randomisation -----------------------------------------------------------------

    @Test
    fun `orientations are seeded, reproducible and roughly uniform`() {
        val a = run(engine(seed = 42), perfect).trials.map { it.orientation }
        val b = run(engine(seed = 42), perfect).trials.map { it.orientation }
        val c = run(engine(seed = 43), perfect).trials.map { it.orientation }
        assertEquals(a, b)
        assertNotEquals(a, c)

        val counts = IntArray(4)
        for (seed in 0 until 500) run(engine(seed = seed), perfect).trials.forEach { counts[it.orientation.ordinal]++ }
        val total = counts.sum().toDouble()
        counts.forEach { assertEquals(0.25, it / total, 0.02) }
    }

    // ---- distance gating and frozen size -------------------------------------------------

    private class MutableDistance(var mm: Double?, var stable: Boolean = true) : DistanceSource {
        override fun currentDistanceMm(mode: DistanceMode) = mm
        override fun isStable() = stable
    }

    @Test
    fun `null distance pauses with AdjustDistance and resumes when available`() {
        val d = MutableDistance(null)
        val e = engine(distance = d)
        assertSame(Step.AdjustDistance, e.next())
        assertSame(Step.AdjustDistance, e.next())
        try {
            e.respond(Dir.UP); fail("respond without a presented trial must throw")
        } catch (_: IllegalStateException) {
        }
        d.mm = 3000.0
        val s = e.next() as Step.Present
        assertEquals(OptotypeMath.optotypeHeightPx(3000.0, 1.0, pxPerMm), s.trial.heightPx, 1e-9)
        e.respond(s.trial.orientation.asDir())
        val r = run(e, perfect)
        assertEquals(1, r.result.quality.distancePauses)
    }

    @Test
    fun `unstable distance pauses`() {
        val d = MutableDistance(3000.0, stable = false)
        val e = engine(distance = d)
        assertSame(Step.AdjustDistance, e.next())
        d.stable = true
        assertTrue(e.next() is Step.Present)
    }

    @Test
    fun `a presented trial is frozen and never resized mid-trial`() {
        val d = MutableDistance(3000.0)
        val e = engine(distance = d)
        val first = (e.next() as Step.Present).trial
        d.mm = 2500.0
        val again = (e.next() as Step.Present).trial
        assertEquals(first, again)
        e.respond(first.orientation.asDir())
        // The next presentation picks up the new distance.
        val next = (e.next() as Step.Present).trial
        assertEquals(2500.0, next.distanceMm, 1e-9)
        assertEquals(OptotypeMath.optotypeHeightPx(2500.0, next.logMAR, pxPerMm), next.heightPx, 1e-9)
    }

    @Test
    fun `losing distance while a letter is up withdraws the trial unscored`() {
        val d = MutableDistance(3000.0)
        val e = engine(cfg = config(practiceTrials = 0), distance = d)
        val first = (e.next() as Step.Present).trial
        d.mm = null
        assertSame(Step.AdjustDistance, e.next())
        d.mm = 3000.0
        val retry = (e.next() as Step.Present).trial
        assertEquals(first.lineIndex, retry.lineIndex)
        assertEquals(first.indexInLine, retry.indexInLine)

        // A response arriving after distance became unstable is voided, not scored.
        d.stable = false
        e.respond(retry.orientation.asDir())
        assertSame(Step.AdjustDistance, e.next())
        d.stable = true
        val r = run(e, perfect)
        assertEquals(40, r.result.quality.scoredResponses)
        assertEquals(2, r.result.quality.voidedTrials)
        assertEquals(2, r.result.quality.distancePauses)
        assertEquals(Referral.PASS, r.result.decision)
    }

    // ---- pixel limit ---------------------------------------------------------------------

    @Test
    fun `pixel-limited target line is never presented or scored`() {
        // 0.3 at 3 m = 8.7 mm, stroke 1.74 mm. At 1.5 px/mm stroke = 2.6 px < 3.
        val r = run(engine(px = 1.5), perfect)
        assertEquals(Referral.INCONCLUSIVE, r.result.decision)
        assertTrue(r.result.quality.pixelLimited)
        assertTrue(r.trials.isEmpty())
        assertNull(r.result.lastPassedLogMAR)
    }

    @Test
    fun `near test to 6-6 equivalent is pixel-limited on a 300 ppi screen`() {
        val r = run(engine(config(kind = TestKind.NEAR, cutoff = 0.0), px = 11.8), perfect)
        assertEquals(Referral.INCONCLUSIVE, r.result.decision)
        assertTrue(r.result.quality.pixelLimited)
    }

    // ---- near and pinhole configurations ---------------------------------------------------

    @Test
    fun `near configuration uses the near distance and its own cutoff`() {
        val r = run(engine(config(kind = TestKind.NEAR, cutoff = 0.4)), perfect)
        assertEquals(TestKind.NEAR, r.result.kind)
        assertTrue(r.trials.all { it.distanceMm == 400.0 })
        r.trials.forEach {
            assertEquals(OptotypeMath.optotypeHeightPx(400.0, it.logMAR, pxPerMm), it.heightPx, 1e-9)
        }
        assertEquals(Referral.PASS, r.result.decision)
        assertEquals(0.4, r.result.lastPassedLogMAR!!, 1e-9)
    }

    @Test
    fun `pinhole configuration uses the far distance and its own line set`() {
        val lines = listOf(0.8, 0.7, 0.6, 0.5, 0.4, 0.3)
        val r = run(engine(config(kind = TestKind.PINHOLE, practiceTrials = 0, lines = lines)), perfect)
        assertEquals(TestKind.PINHOLE, r.result.kind)
        assertTrue(r.trials.all { it.distanceMm == 3000.0 })
        assertEquals(0.8, r.trials.first().logMAR, 1e-9)
        assertEquals(lines, r.result.lines.map { roundLogMAR(it.logMAR) })
    }

    @Test
    fun `pinhole improvement compares against the unaided result`() {
        val lines = tenToZero
        fun improved(unaided: Double?, pinhole: Double?, min: Double = 0.2) =
            Pinhole.improved(unaided, pinhole, min, lines, step = 0.1)
        assertTrue(improved(0.6, 0.4))
        assertFalse(improved(0.6, 0.5))
        assertFalse(improved(0.6, 0.6))
        assertFalse(improved(0.4, 0.6))
        // Nothing passed unaided counts as one line worse than the top line (1.1).
        assertTrue(improved(null, 0.8))
        assertFalse(improved(null, 1.0))
        assertFalse(improved(null, null))
        assertTrue(improved(0.6, 0.5, min = 0.1))
    }

    // ---- state machine misuse ------------------------------------------------------------

    @Test
    fun `phases progress PRACTICE to DESCENDING_LINES to DONE`() {
        val e = engine()
        assertEquals(Phase.PRACTICE, e.phase)
        val p = (e.next() as Step.Present).trial
        e.respond(p.orientation.asDir())
        assertEquals(Phase.DESCENDING_LINES, e.phase)
        run(e, perfect)
        assertEquals(Phase.DONE, e.phase)
        assertTrue(e.next() is Step.Finished)
        try {
            e.respond(Dir.UP); fail("respond after DONE must throw")
        } catch (_: IllegalStateException) {
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `engine config rejects a pass rule larger than the line`() {
        config(perLine = 5, passMin = 6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `engine config rejects a cutoff not in the line set`() {
        config(cutoff = 0.35)
    }

    @Test
    fun `a withdrawn letter is not scored and its slot is shown again`() {
        val e = engine(cfg = config(practiceTrials = 0))
        val first = (e.next() as Step.Present).trial
        e.withdraw()
        val again = (e.next() as Step.Present).trial
        assertEquals(first.lineIndex, again.lineIndex)
        assertEquals(first.indexInLine, again.indexInLine)
        assertEquals(first.logMAR, again.logMAR, 0.0)
        e.respond(again.orientation.toDir())
        val r = run(e) { it.orientation.toDir() }.result
        assertEquals(1, r.quality.voidedTrials)
        // One line per logMAR step down to the cutoff, five letters each: the withdrawn letter adds none.
        assertEquals(r.lines.sumOf { it.shown }, r.quality.scoredResponses)
    }

    @Test(expected = IllegalStateException::class)
    fun `withdraw needs a letter on screen`() {
        engine().withdraw()
    }

    private fun Orientation.toDir() = when (this) {
        Orientation.UP -> Dir.UP
        Orientation.DOWN -> Dir.DOWN
        Orientation.LEFT -> Dir.LEFT
        Orientation.RIGHT -> Dir.RIGHT
    }
}
