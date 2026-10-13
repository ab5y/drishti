package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class ScreeningFlowTest {

    private val pxPerMm = 400 / 25.4 // 15.75 px/mm
    private val landscape = Viewport(2400, 1080) // ~152 x 69 mm at 400 ppi
    private val goodLux = 300.0

    private fun flow(
        config: AppConfig = TestConfigs.shipped(),
        px: Double = pxPerMm,
        distance: DistanceSource = FixedDistance.from(config),
        viewport: Viewport? = landscape,
        seed: Int = 1,
    ) = ScreeningFlow(config, px, distance, Random(seed)).also { f -> viewport?.let { f.setViewport(it.widthPx, it.heightPx) } }

    private fun Orientation.action() = when (this) {
        Orientation.UP -> RemoteAction.UP
        Orientation.DOWN -> RemoteAction.DOWN
        Orientation.LEFT -> RemoteAction.LEFT
        Orientation.RIGHT -> RemoteAction.RIGHT
    }

    private fun Orientation.wrong() = when (this) {
        Orientation.UP -> RemoteAction.DOWN
        Orientation.DOWN -> RemoteAction.UP
        Orientation.LEFT -> RemoteAction.RIGHT
        Orientation.RIGHT -> RemoteAction.LEFT
    }

    private val perfect: (TestId, Trial) -> RemoteAction = { _, t -> t.orientation.action() }

    /** Fails [failing] tests (wrong after practice), answers everything else perfectly. */
    private fun failing(vararg failing: TestId): (TestId, Trial) -> RemoteAction = { id, t ->
        if (id in failing && t.phase == Phase.DESCENDING_LINES) t.orientation.wrong() else t.orientation.action()
    }

    private class Driven(val result: ScreeningResult, val prompts: List<TestId>, val letters: List<FlowScreen.Letter>)

    private fun drive(f: ScreeningFlow, lux: Double? = goodLux, responder: (TestId, Trial) -> RemoteAction): Driven {
        assertTrue(f.register("SCH-014", "9", lux).isEmpty())
        val prompts = mutableListOf<TestId>()
        val letters = mutableListOf<FlowScreen.Letter>()
        repeat(100_000) {
            when (val s = f.screen) {
                is FlowScreen.LightWarning -> f.onAction(RemoteAction.OK)
                is FlowScreen.Prompt -> {
                    prompts += s.test
                    f.onAction(RemoteAction.OK)
                }
                is FlowScreen.Letter -> {
                    letters += s
                    f.onAction(responder(s.test, s.trial))
                }
                is FlowScreen.Result -> return Driven(s.result, prompts, letters)
                else -> fail("unexpected screen $s")
            }
        }
        fail("flow did not finish")
        throw AssertionError()
    }

    private val farR = TestId(TestKind.FAR, Eye.RIGHT)
    private val farL = TestId(TestKind.FAR, Eye.LEFT)
    private val near = TestId(TestKind.NEAR, Eye.BOTH)
    private val pinR = TestId(TestKind.PINHOLE, Eye.RIGHT)
    private val pinL = TestId(TestKind.PINHOLE, Eye.LEFT)

    // ---- which tests run ----------------------------------------------------------------

    @Test
    fun `all pass runs right, left, near and no pinhole`() {
        val d = drive(flow(), responder = perfect)
        assertEquals(listOf(farR, farL, near), d.prompts)
        assertEquals(Referral.PASS, d.result.right.decision)
        assertEquals(Referral.PASS, d.result.left.decision)
        assertEquals(Referral.PASS, d.result.near.decision)
        assertEquals(Referral.PASS, d.result.overall)
        assertNull(d.result.right.pinhole)
        assertNull(d.result.right.pinholeImproved)
    }

    @Test
    fun `pinhole runs only for the failing eye, after near`() {
        val d = drive(flow(), responder = failing(farR))
        assertEquals(listOf(farR, farL, near, pinR), d.prompts)
        assertEquals(Referral.REFER, d.result.right.decision)
        assertEquals(Referral.PASS, d.result.left.decision)
        assertEquals(Referral.REFER, d.result.overall)
        assertNotNull(d.result.right.pinhole)
        assertNull(d.result.left.pinhole)
    }

    @Test
    fun `both eyes failing get pinhole in eye order`() {
        val d = drive(flow(), responder = failing(farR, farL))
        assertEquals(listOf(farR, farL, near, pinR, pinL), d.prompts)
    }

    @Test
    fun `pinhole improved and not improved`() {
        // Unaided right fails line 1.0 (nothing passed); pinhole perfect -> improved.
        val improved = drive(flow(), responder = failing(farR)).result.right
        assertEquals(true, improved.pinholeImproved)
        assertEquals(Referral.REFER, improved.decision) // pinhole does not change the far decision

        val notImproved = drive(flow(), responder = failing(farR, pinR)).result.right
        assertEquals(false, notImproved.pinholeImproved)
    }

    @Test
    fun `pinhole disabled in config never runs`() {
        val d = drive(flow(TestConfigs.with("pinhole.on_fail" to false)), responder = failing(farR, farL))
        assertEquals(listOf(farR, farL, near), d.prompts)
        assertNull(d.result.right.pinholeImproved)
    }

    @Test
    fun `near failure refers overall but does not trigger pinhole`() {
        val d = drive(flow(), responder = failing(near))
        assertEquals(listOf(farR, farL, near), d.prompts)
        assertEquals(Referral.REFER, d.result.near.decision)
        assertEquals(Referral.REFER, d.result.overall)
    }

    @Test
    fun `eye order follows config`() {
        assertEquals(listOf(farL, farR, near), drive(flow(TestConfigs.with("eye_order" to "left_first")), responder = perfect).prompts)
        val orders = (0 until 40).map { seed ->
            drive(flow(TestConfigs.with("eye_order" to "random"), seed = seed), responder = perfect).prompts.first()
        }.toSet()
        assertEquals(setOf(farR, farL), orders)
    }

    @Test
    fun `near uses the near distance and both eyes`() {
        val d = drive(flow(), responder = perfect)
        val nearLetters = d.letters.filter { it.test == near }
        assertTrue(nearLetters.isNotEmpty())
        assertTrue(nearLetters.all { it.trial.distanceMm == 400.0 })
        assertTrue(d.letters.filter { it.test == farR }.all { it.trial.distanceMm == 3000.0 })
    }

    // ---- registration, light, prompts and keys ----------------------------------------------

    @Test
    fun `registration takes a token and optional age only`() {
        val f = flow()
        assertTrue(f.register("  ", "", goodLux).isNotEmpty())
        assertTrue(f.register("SCH-1", "abc", goodLux).isNotEmpty())
        assertTrue(f.register("SCH-1", "150", goodLux).isNotEmpty())
        assertEquals(FlowScreen.Register, f.screen)
        assertTrue(f.register("SCH-1", "", goodLux).isEmpty())
        assertTrue(f.screen is FlowScreen.Prompt)
    }

    @Test
    fun `registration needs the test viewport first`() {
        val f = flow(viewport = null)
        assertTrue(f.register("SCH-1", "", goodLux).isNotEmpty())
        f.setViewport(2400, 1080)
        assertTrue(f.register("SCH-1", "", goodLux).isEmpty())
    }

    @Test
    fun `low light warns but the operator can continue`() {
        val f = flow()
        f.register("SCH-1", "", 20.0)
        val w = f.screen as FlowScreen.LightWarning
        assertEquals(20.0, w.lux!!, 0.0)
        assertEquals(50.0, w.minLux, 0.0)
        f.onAction(RemoteAction.OK)
        assertTrue(f.screen is FlowScreen.Prompt)

        val d = drive(flow(), lux = 20.0, responder = perfect)
        assertEquals(false, d.result.lightOk)
        assertEquals(Referral.PASS, d.result.overall) // light never blocks or changes the decision
    }

    @Test
    fun `no light sensor is unknown, warned, not blocking`() {
        val f = flow()
        f.register("SCH-1", "", null)
        assertTrue(f.screen is FlowScreen.LightWarning)
        val d = drive(flow(), lux = null, responder = perfect)
        assertNull(d.result.lightOk)
    }

    @Test
    fun `good light goes straight to the first occlusion prompt`() {
        val f = flow()
        f.register("SCH-1", "", goodLux)
        val p = f.screen as FlowScreen.Prompt
        assertEquals(farR, p.test)
        assertTrue(p.text.lines.any { it.contains("LEFT eye", ignoreCase = false) && it.contains("Cover") })
    }

    @Test
    fun `only OK leaves a prompt, then practice comes first`() {
        val f = flow()
        f.register("SCH-1", "", goodLux)
        listOf(RemoteAction.UP, RemoteAction.LEFT, RemoteAction.DOWN, RemoteAction.RIGHT).forEach {
            f.onAction(it)
            assertTrue(f.screen is FlowScreen.Prompt)
        }
        f.onAction(RemoteAction.OK)
        val l = f.screen as FlowScreen.Letter
        assertEquals(Phase.PRACTICE, l.trial.phase)
        assertTrue(f.trials.isEmpty())
    }

    @Test
    fun `OK on a letter is an unsure answer`() {
        val f = flow(TestConfigs.with("far.practice_trials" to 0))
        f.register("SCH-1", "", goodLux)
        f.onAction(RemoteAction.OK)
        f.onAction(RemoteAction.OK)
        assertEquals(Dir.UNSURE, f.trials.single().response)
        assertFalse(f.trials.single().correct)
    }

    private class SwitchableDistance(var mm: Double?) : DistanceSource {
        override fun currentDistanceMm(mode: DistanceMode) = mm
        override fun isStable() = true
    }

    @Test
    fun `keys are ignored while paused for distance, poll resumes`() {
        val d = SwitchableDistance(null)
        val f = flow(distance = d)
        f.register("SCH-1", "", goodLux)
        f.onAction(RemoteAction.OK)
        assertEquals(FlowScreen.AdjustDistance(farR), f.screen)
        RemoteAction.entries.forEach { f.onAction(it) }
        assertEquals(FlowScreen.AdjustDistance(farR), f.screen)
        assertTrue(f.trials.isEmpty())
        f.poll()
        assertEquals(FlowScreen.AdjustDistance(farR), f.screen)
        d.mm = 3000.0
        f.poll()
        assertTrue(f.screen is FlowScreen.Letter)
    }

    @Test
    fun `test display is used on prompts, letters and pauses only`() {
        assertFalse(FlowScreen.Register.usesTestDisplay)
        assertFalse(FlowScreen.LightWarning(1.0, 50.0).usesTestDisplay)
        assertTrue(FlowScreen.AdjustDistance(farR).usesTestDisplay)
        val f = flow()
        f.register("SCH-1", "", goodLux)
        assertTrue(f.screen.usesTestDisplay)
        f.onAction(RemoteAction.OK)
        assertTrue(f.screen.usesTestDisplay)
        val d = drive(flow(), responder = perfect)
        assertFalse(FlowScreen.Result(d.result).usesTestDisplay)
    }

    // ---- crowding fallback and per-trial records ---------------------------------------------

    @Test
    fun `crowding falls back to uncrowded where the bars do not fit`() {
        val d = drive(flow(), responder = perfect)
        val far = d.result.trials.filter { it.test == farR && it.phase == Phase.DESCENDING_LINES }
        // At 400 ppi in a 1080 px tall landscape area: 1.0 and 0.9 do not fit crowded, 0.8 and smaller do.
        far.forEach {
            val expectCrowded = it.logMAR < 0.85
            assertEquals("logMAR ${it.logMAR}", expectCrowded, it.crowded)
        }
        d.letters.forEach { l ->
            assertTrue(l.render.footprintPx <= 1080)
            assertEquals(l.crowded, l.render.bars.isNotEmpty())
        }
    }

    @Test
    fun `crowding disabled in config is never drawn`() {
        val d = drive(flow(TestConfigs.with("crowding.enabled" to false)), responder = perfect)
        assertTrue(d.result.trials.none { it.crowded })
    }

    @Test
    fun `every answered trial is recorded with its drawn size`() {
        val d = drive(flow(), responder = failing(farR))
        assertEquals(d.letters.size, d.result.trials.size)
        d.result.trials.forEachIndexed { i, r ->
            val l = d.letters[i]
            assertEquals(l.test, r.test)
            assertEquals(l.trial.logMAR, r.logMAR, 0.0)
            assertEquals(OptotypeMath.optotypeHeightMm(r.distanceMm, r.logMAR), r.intendedMm, 1e-9)
            assertEquals(r.strokePx * 5 / pxPerMm, r.drawnMm, 1e-9)
            assertEquals(r.drawnMm / r.intendedMm - 1, r.sizeErrorFraction, 1e-12)
            assertEquals(l.render.strokePx, r.strokePx)
        }
    }

    // ---- INCONCLUSIVE paths ---------------------------------------------------------------

    @Test
    fun `near pixel-limited is INCONCLUSIVE with next-step text`() {
        // 9 px/mm: far 0.3 at 3 m is fine (stroke 15.7 px), near 0.4 at 400 mm is not (2.6 px).
        val d = drive(flow(px = 9.0), responder = perfect)
        assertEquals(Referral.PASS, d.result.right.decision)
        assertEquals(Referral.INCONCLUSIVE, d.result.near.decision)
        assertEquals(Referral.INCONCLUSIVE, d.result.overall)
        assertEquals(ResultText.INCONCLUSIVE_PIXEL_LIMITED, d.result.near.reason)
        assertTrue(d.result.near.reason.contains("Next step"))
        assertEquals(ResultText.OVERALL_INCONCLUSIVE, d.result.overallText)
    }

    @Test
    fun `a letter too large for the screen ends that test INCONCLUSIVE`() {
        // 6/60 at 3 m is 43.6 mm = 687 px; a 600 px tall area cannot hold it.
        val d = drive(flow(viewport = Viewport(1400, 600)), responder = perfect)
        assertEquals(Referral.INCONCLUSIVE, d.result.right.decision)
        assertEquals(ResultText.INCONCLUSIVE_DOES_NOT_FIT, d.result.right.reason)
        assertTrue(d.letters.none { it.test == farR })
        assertEquals(Referral.PASS, d.result.near.decision) // near letters are small and fit
    }

    @Test
    fun `REFER outranks INCONCLUSIVE overall`() {
        val d = drive(flow(px = 9.0), responder = failing(farR))
        assertEquals(Referral.INCONCLUSIVE, d.result.near.decision)
        assertEquals(Referral.REFER, d.result.overall)
    }

    // ---- result text ----------------------------------------------------------------------

    private val banned = Regex(
        "diagnos|myop|hyperop|presbyop|astigmat|refractive|glasses|spectacle|cataract|amblyop|disease|normal vision|prescri",
        RegexOption.IGNORE_CASE,
    )

    private fun allText(r: ScreeningResult) = buildList {
        add(r.overallText)
        listOf(r.right, r.left).forEach { e ->
            add(ResultText.decisionLabel(e.decision)); add(e.reason); e.pinholeText?.let(::add); addAll(e.quality)
        }
        add(ResultText.decisionLabel(r.near.decision)); add(r.near.reason); addAll(r.near.quality)
    }

    @Test
    fun `result text never uses diagnostic wording`() {
        val scenarios = listOf(
            drive(flow(), responder = perfect),
            drive(flow(), responder = failing(farR)),
            drive(flow(), responder = failing(farR, farL, pinR, pinL, near)),
            drive(flow(px = 9.0), lux = null, responder = perfect),
            drive(flow(viewport = Viewport(1400, 600)), lux = 10.0, responder = perfect),
            drive(flow()) { _, _ -> RemoteAction.OK },
        )
        scenarios.forEach { d ->
            allText(d.result).forEach { assertFalse("diagnostic wording in: $it", banned.containsMatchIn(it)) }
        }
        listOf(farR, farL, near, pinR, pinL).forEach { id ->
            val p = Prompts.forTest(id, TestConfigs.shipped())
            (p.lines + p.title).forEach { assertFalse(banned.containsMatchIn(it)) }
        }
    }

    @Test
    fun `result reasons state the screening line and the line passed`() {
        val pass = drive(flow(), responder = perfect).result
        assertEquals("Passed the screening line 6/12 (logMAR 0.3).", pass.right.reason)
        assertEquals("PASS", ResultText.decisionLabel(Referral.PASS))
        assertEquals("REFER for a full eye examination", ResultText.decisionLabel(Referral.REFER))

        // Right eye sees 0.6 and larger only.
        val refer = drive(flow()) { id, t ->
            if (id == farR && t.logMAR < 0.55) t.orientation.wrong() else t.orientation.action()
        }.result
        assertEquals(
            "Did not pass the screening line 6/12 (logMAR 0.3). Smallest line passed: 6/24 (logMAR 0.6).",
            refer.right.reason,
        )
        assertEquals("Pinhole: improved from 6/24 to 6/12 (3 lines).", refer.right.pinholeText)

        val none = drive(flow(), responder = failing(farR, pinR)).result
        assertEquals(
            "Did not pass the screening line 6/12 (logMAR 0.3). No line passed; the largest shown was 6/60 (logMAR 1.0).",
            none.right.reason,
        )
        assertEquals("Pinhole: not improved (no line passed with or without the pinhole).", none.right.pinholeText)
    }

    @Test
    fun `quality lines report responses, light, distance and crowding`() {
        val d = drive(flow(), lux = 20.0) { _, t ->
            if (t.phase == Phase.DESCENDING_LINES && t.logMAR > 0.95) RemoteAction.OK else t.orientation.action()
        }.result
        val q = d.right.quality.joinToString("\n")
        assertTrue(q, q.contains("Responses: NOT OK"))
        assertTrue(q, q.contains("Light: 20 lux, below the minimum of 50"))
        assertTrue(q, q.contains("Distance: confirmed by the operator"))
        assertTrue(q, q.contains("shown without crowding bars"))
    }
}
