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

    /** Fake uptime clock for the prompt dwell. */
    private var nowMs = 0L

    /** What the light sensor returns when the first eye's test starts. */
    private var startLux: Double? = goodLux

    private fun flow(
        config: AppConfig = TestConfigs.shipped(),
        px: Double = pxPerMm,
        distance: DistanceSource = FixedDistance.from(config),
        viewport: Viewport? = landscape,
        seed: Int = 1,
        promptMinShowMs: Long = 0,
    ) = ScreeningFlow(
        config, px, distance, Random(seed),
        clock = { nowMs },
        readLux = { startLux },
        promptMinShowMs = promptMinShowMs,
    ).also { f -> viewport?.let { f.setViewport(it.widthPx, it.heightPx) } }

    /** Presses Up, Down, Left, Right, OK on the remote check. */
    private fun passCheck(f: ScreeningFlow) {
        check(f.screen is FlowScreen.RemoteCheck)
        RemoteCheck.ORDER.forEach { assertEquals(InputResult.Used, f.onAction(it)) }
    }

    /** Registers with good light and passes the remote check: the first prompt is up. */
    private fun toFirstPrompt(f: ScreeningFlow): ScreeningFlow {
        assertTrue(f.register("SCH-1", "", goodLux).isEmpty())
        passCheck(f)
        return f
    }

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

    private fun drive(
        f: ScreeningFlow,
        lux: Double? = goodLux,
        source: (TestId, Trial) -> AnswerSource = { _, _ -> AnswerSource.REMOTE },
        responder: (TestId, Trial) -> RemoteAction,
    ): Driven {
        assertTrue(f.register("SCH-014", "9", lux).isEmpty())
        val prompts = mutableListOf<TestId>()
        val letters = mutableListOf<FlowScreen.Letter>()
        repeat(100_000) {
            when (val s = f.screen) {
                is FlowScreen.LightWarning -> f.onAction(RemoteAction.OK)
                is FlowScreen.RemoteCheck -> passCheck(f)
                is FlowScreen.Prompt -> {
                    prompts += s.test
                    f.onAction(RemoteAction.OK)
                }
                is FlowScreen.Letter -> {
                    letters += s
                    assertEquals(InputResult.Used, f.onAction(responder(s.test, s.trial), source(s.test, s.trial)))
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

    // ---- registration, light, remote check, prompts and keys ----------------------------------

    @Test
    fun `registration takes an optional school ID and optional age only`() {
        val f = flow()
        assertTrue(f.register("Ravi", "", goodLux).isNotEmpty())
        assertTrue(f.register("SCH-1", "abc", goodLux).isNotEmpty())
        assertTrue(f.register("SCH-1", "150", goodLux).isNotEmpty())
        assertEquals(FlowScreen.Register, f.screen)
        assertTrue(f.register("SCH-1", "", goodLux).isEmpty())
        assertTrue(f.screen is FlowScreen.RemoteCheck)
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
        assertTrue(f.screen is FlowScreen.RemoteCheck)

        val d = drive(flow(), lux = 20.0, responder = perfect)
        assertEquals(false, d.result.lightOk)
        assertEquals(Referral.PASS, d.result.overall) // light never blocks or changes the decision
    }

    @Test
    fun `no light sensor is unknown, warned, not blocking`() {
        startLux = null
        val f = flow()
        f.register("SCH-1", "", null)
        assertTrue(f.screen is FlowScreen.LightWarning)
        val d = drive(flow(), lux = null, responder = perfect)
        assertNull(d.result.lightOk)
        assertNull(d.result.light.lowest)
    }

    @Test
    fun `light is read again when the first eye starts, and the lower reading decides`() {
        // Both fine.
        assertEquals(true, drive(flow(), lux = 300.0, responder = perfect).result.lightOk)

        // Fine at registration, dark when the first eye starts.
        startLux = 30.0
        val dark = drive(flow(), lux = 300.0, responder = perfect).result
        assertEquals(LightReadings(atRegister = 300.0, atTestStart = 30.0), dark.light)
        assertEquals(30.0, dark.light.lowest!!, 0.0)
        assertEquals(false, dark.lightOk)

        // Dark at registration only.
        startLux = 300.0
        assertEquals(false, drive(flow(), lux = 30.0, responder = perfect).result.lightOk)

        // One reading missing: the other one decides.
        startLux = null
        val oneReading = drive(flow(), lux = 300.0, responder = perfect).result
        assertEquals(LightReadings(300.0, null), oneReading.light)
        assertEquals(true, oneReading.lightOk)
    }

    @Test
    fun `the second light reading is taken on OK at the first eye's prompt, once`() {
        var reads = 0
        val f = ScreeningFlow(
            TestConfigs.shipped(), pxPerMm, FixedDistance.from(TestConfigs.shipped()), Random(1),
            clock = { 0L }, readLux = { reads++; 123.0 }, promptMinShowMs = 0,
        ).also { it.setViewport(2400, 1080) }
        toFirstPrompt(f)
        assertEquals(0, reads)
        f.onAction(RemoteAction.OK)
        assertEquals(1, reads)
        assertTrue(f.screen is FlowScreen.Letter)
    }

    @Test
    fun `remote check ticks Up, Down, Left, Right, OK in order`() {
        val f = flow()
        f.register("SCH-1", "", goodLux)
        assertEquals(FlowScreen.RemoteCheck(ticked = 0, lastWrong = null), f.screen)
        assertEquals(InputResult.Used, f.onAction(RemoteAction.UP))
        assertEquals(FlowScreen.RemoteCheck(ticked = 1, lastWrong = null), f.screen)

        // A different press is reported, not ticked.
        val wrong = f.onAction(RemoteAction.LEFT)
        assertEquals(InputResult.NotUsed("Got LEFT, waiting for DOWN."), wrong)
        assertEquals(FlowScreen.RemoteCheck(ticked = 1, lastWrong = RemoteAction.LEFT), f.screen)
        assertTrue(f.onOtherPress() is InputResult.NotUsed)
        assertEquals(1, (f.screen as FlowScreen.RemoteCheck).ticked)

        listOf(RemoteAction.DOWN, RemoteAction.LEFT, RemoteAction.RIGHT).forEach { f.onAction(it) }
        assertEquals(4, (f.screen as FlowScreen.RemoteCheck).ticked)
        f.onAction(RemoteAction.OK)
        assertEquals(farR, (f.screen as FlowScreen.Prompt).test)
        assertTrue(f.remoteAnswers)
    }

    @Test
    fun `on-screen taps do not tick the remote check`() {
        val f = flow()
        f.register("SCH-1", "", goodLux)
        assertTrue(f.onAction(RemoteAction.UP, AnswerSource.PAD) is InputResult.NotUsed)
        assertEquals(0, (f.screen as FlowScreen.RemoteCheck).ticked)
    }

    @Test
    fun `a remote that fails the check is never used for answers`() {
        val f = flow(TestConfigs.with("far.practice_trials" to 0))
        f.register("SCH-1", "", goodLux)
        f.onAction(RemoteAction.UP)
        f.onAction(RemoteAction.DOWN) // no LEFT on this remote
        f.useOnScreenAnswers()
        assertFalse(f.remoteAnswers)
        assertEquals(farR, (f.screen as FlowScreen.Prompt).test)

        f.onAction(RemoteAction.OK) // any remote press still leaves a prompt
        val letter = f.screen as FlowScreen.Letter
        RemoteAction.entries.forEach {
            assertEquals(InputResult.NotUsed(ScreeningFlow.REMOTE_OFF), f.onAction(it))
        }
        assertTrue(f.trials.isEmpty())
        assertEquals(letter, f.screen)

        assertEquals(InputResult.Used, f.onAction(letter.trial.orientation.action(), AnswerSource.PAD))
        assertEquals(AnswerSource.PAD, f.trials.single().source)
    }

    @Test
    fun `on-screen answers are only offered on the remote check`() {
        val f = toFirstPrompt(flow())
        f.useOnScreenAnswers()
        assertTrue(f.remoteAnswers)
    }

    @Test
    fun `good light goes to the remote check, then the first occlusion prompt`() {
        val f = toFirstPrompt(flow())
        val p = f.screen as FlowScreen.Prompt
        assertEquals(farR, p.test)
        assertTrue(p.text.lines.any { it.contains("LEFT eye", ignoreCase = false) && it.contains("Cover") })
    }

    @Test
    fun `any press leaves a prompt, then practice comes first`() {
        RemoteAction.entries.forEach { action ->
            val f = toFirstPrompt(flow())
            assertEquals(InputResult.Used, f.onAction(action))
            val l = f.screen as FlowScreen.Letter
            assertEquals(Phase.PRACTICE, l.trial.phase)
            assertTrue(f.trials.isEmpty())
        }
        val f = toFirstPrompt(flow())
        assertEquals(InputResult.Used, f.onOtherPress())
        assertTrue(f.screen is FlowScreen.Letter)
    }

    @Test
    fun `any press leaves the light warning`() {
        val f = flow()
        f.register("SCH-1", "", 5.0)
        assertEquals(InputResult.Used, f.onOtherPress())
        assertTrue(f.screen is FlowScreen.RemoteCheck)
    }

    @Test
    fun `an unmapped press on a letter is not an answer`() {
        val f = toFirstPrompt(flow())
        f.onAction(RemoteAction.OK)
        val letter = f.screen
        assertTrue(f.onOtherPress() is InputResult.NotUsed)
        assertEquals(letter, f.screen)
        assertTrue(f.trials.isEmpty())
    }

    @Test
    fun `remote presses on a prompt wait for the dwell, on-screen taps do not`() {
        val f = flow(promptMinShowMs = 800)
        nowMs = 10_000
        f.register("SCH-1", "", 5.0) // light warning appears at 10 000
        nowMs = 10_500
        assertEquals(InputResult.NotUsed(ScreeningFlow.WAIT_PROMPT), f.onOtherPress())
        assertTrue(f.screen is FlowScreen.LightWarning)
        nowMs = 10_800
        assertEquals(InputResult.Used, f.onOtherPress())

        passCheck(f) // first prompt appears at 10 800
        nowMs = 11_000
        assertEquals(InputResult.NotUsed(ScreeningFlow.WAIT_PROMPT), f.onAction(RemoteAction.OK))
        assertTrue(f.screen is FlowScreen.Prompt)
        assertEquals(InputResult.Used, f.onAction(RemoteAction.OK, AnswerSource.PAD))
        assertTrue(f.screen is FlowScreen.Letter)
    }

    @Test
    fun `a fast extra press after the last letter cannot skip the next eye's prompt`() {
        val f = flow(promptMinShowMs = 800)
        toFirstPrompt(f)
        nowMs += 1_000
        f.onAction(RemoteAction.OK)
        while (f.screen is FlowScreen.Letter) {
            nowMs += 300
            f.onAction((f.screen as FlowScreen.Letter).trial.orientation.action())
        }
        val prompt = f.screen as FlowScreen.Prompt
        assertEquals(farL, prompt.test)
        nowMs += 300
        assertEquals(InputResult.NotUsed(ScreeningFlow.WAIT_PROMPT), f.onAction(RemoteAction.UP))
        assertEquals(prompt, f.screen)
    }

    @Test
    fun `OK on a letter is an unsure answer`() {
        val f = toFirstPrompt(flow(TestConfigs.with("far.practice_trials" to 0)))
        f.onAction(RemoteAction.OK)
        f.onAction(RemoteAction.OK)
        assertEquals(Dir.UNSURE, f.trials.single().response)
        assertFalse(f.trials.single().correct)
    }

    @Test
    fun `pad answers score like remote answers and are recorded as pad`() {
        val viaPad = drive(flow(), source = { _, _ -> AnswerSource.PAD }, responder = perfect).result
        val viaRemote = drive(flow(), responder = perfect).result
        assertEquals(viaRemote.overall, viaPad.overall)
        assertEquals(viaRemote.trials.map { it.correct }, viaPad.trials.map { it.correct })
        assertTrue(viaPad.trials.all { it.source == AnswerSource.PAD })
        assertTrue(viaRemote.trials.all { it.source == AnswerSource.REMOTE })

        // Mixed: every second answer from the pad.
        var n = 0
        val mixed = drive(flow(), source = { _, _ -> if (n++ % 2 == 0) AnswerSource.PAD else AnswerSource.REMOTE }, responder = perfect).result
        val pad = mixed.trials.count { it.source == AnswerSource.PAD }
        assertTrue(pad > 0 && pad < mixed.trials.size)
        val farTrials = mixed.trials.filter { it.test == farR }
        val line = "Answers: ${farTrials.count { it.source == AnswerSource.PAD }} of ${farTrials.size} entered on the on-screen pad."
        assertTrue(mixed.right.quality.joinToString("\n"), line in mixed.right.quality)
        assertTrue(viaRemote.right.quality.none { it.startsWith("Answers:") })
    }

    private class SwitchableDistance(var mm: Double?) : DistanceSource {
        override fun currentDistanceMm(mode: DistanceMode) = mm
        override fun isStable() = true
    }

    @Test
    fun `presses are reported unused while paused for distance, poll resumes`() {
        val d = SwitchableDistance(null)
        val f = toFirstPrompt(flow(distance = d))
        f.onAction(RemoteAction.OK)
        assertEquals(FlowScreen.AdjustDistance(farR), f.screen)
        RemoteAction.entries.forEach { assertEquals(InputResult.NotUsed(ScreeningFlow.PAUSED), f.onAction(it)) }
        assertTrue(f.onOtherPress() is InputResult.NotUsed)
        assertEquals(FlowScreen.AdjustDistance(farR), f.screen)
        assertTrue(f.trials.isEmpty())
        f.poll()
        assertEquals(FlowScreen.AdjustDistance(farR), f.screen)
        d.mm = 3000.0
        f.poll()
        assertTrue(f.screen is FlowScreen.Letter)
    }

    @Test
    fun `presses on the result screen are reported unused`() {
        val f = flow()
        drive(f, responder = perfect)
        assertTrue(f.onAction(RemoteAction.OK) is InputResult.NotUsed)
        assertTrue(f.onOtherPress() is InputResult.NotUsed)
        assertTrue(f.screen is FlowScreen.Result)
    }

    @Test
    fun `test display is used on prompts, letters and pauses only`() {
        assertFalse(FlowScreen.Register.usesTestDisplay)
        assertFalse(FlowScreen.LightWarning(1.0, 50.0).usesTestDisplay)
        assertFalse(FlowScreen.RemoteCheck(0, null).usesTestDisplay)
        assertTrue(FlowScreen.AdjustDistance(farR).usesTestDisplay)
        val f = toFirstPrompt(flow())
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
        // Nothing was scored, so it also says no answers were entered (D45).
        assertEquals(ResultText.NO_ANSWERS + " " + ResultText.INCONCLUSIVE_PIXEL_LIMITED, d.result.near.reason)
        assertTrue(d.result.near.reason.contains("Next step"))
        assertEquals(ResultText.OVERALL_INCONCLUSIVE, d.result.overallText)
    }

    @Test
    fun `a letter too large for the screen ends that test INCONCLUSIVE`() {
        // 6/60 at 3 m is 43.6 mm = 687 px; a 600 px tall area cannot hold it.
        val d = drive(flow(viewport = Viewport(1400, 600)), responder = perfect)
        assertEquals(Referral.INCONCLUSIVE, d.result.right.decision)
        assertEquals(ResultText.NO_ANSWERS + " " + ResultText.INCONCLUSIVE_DOES_NOT_FIT, d.result.right.reason)
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

    private fun scenarios() = listOf(
        drive(flow(), responder = perfect),
        drive(flow(), responder = failing(farR)),
        drive(flow(), responder = failing(farR, farL, pinR, pinL, near)),
        // Right eye sees 0.4 and larger only: the pinhole then passes without a 2-line gain.
        drive(flow()) { id, t -> if (id == farR && t.logMAR < 0.35) t.orientation.wrong() else t.orientation.action() },
        drive(flow(px = 9.0), lux = null, responder = perfect),
        drive(flow(viewport = Viewport(1400, 600)), lux = 10.0, responder = perfect),
        drive(flow()) { _, _ -> RemoteAction.OK },
    )

    @Test
    fun `result text never uses diagnostic wording`() {
        scenarios().forEach { d ->
            allText(d.result).forEach { assertFalse("diagnostic wording in: $it", banned.containsMatchIn(it)) }
        }
        listOf(farR, farL, near, pinR, pinL).forEach { id ->
            val p = Prompts.forTest(id, TestConfigs.shipped())
            (p.lines + p.title).forEach { assertFalse(banned.containsMatchIn(it)) }
        }
    }

    private val snellen = Regex("6/[0-9]+(\\.[0-9]+)?")

    /** Every Snellen value in [text] is preceded by "at least ". */
    private fun assertNoMeasuredAcuity(text: String) {
        snellen.findAll(text).forEach { m ->
            assertTrue("bare acuity '${m.value}' in: $text", text.substring(0, m.range.first).endsWith("at least "))
        }
    }

    @Test
    fun `no PASS text implies a measured acuity`() {
        var passes = 0
        scenarios().forEach { d ->
            val r = d.result
            listOf(r.right, r.left).forEach { e ->
                if (e.decision == Referral.PASS) {
                    passes++
                    assertNoMeasuredAcuity(e.reason)
                    assertTrue(e.reason, e.reason.contains("at least"))
                }
                // A pinhole retest that passed stopped at its cutoff too: its value must say "at least".
                if (e.pinhole?.decision == Referral.PASS) {
                    passes++
                    val text = e.pinholeText!!
                    val withPinhole = if (" to " in text) text.substringAfter(" to ") else text.substringAfter(" without, ")
                    assertTrue(text, withPinhole.startsWith("at least "))
                    assertNoMeasuredAcuity(withPinhole)
                }
            }
            if (r.near.decision == Referral.PASS) {
                passes++
                assertNoMeasuredAcuity(r.near.reason)
                assertTrue(r.near.reason, r.near.reason.contains("at least"))
            }
        }
        assertTrue("scenarios must include passes, got $passes", passes >= 10)
    }

    @Test
    fun `acuity is exact only after a failed line`() {
        fun result(lines: List<LineResult>, decision: Referral) = EngineResult(
            TestKind.FAR, lines.lastOrNull { it.passed }?.logMAR, decision,
            QualityFlags(true, lines.sumOf { it.shown }, 0, 1, 1, false, 0, 0), lines,
        )
        val pass = LineResult(0.4, 5, 5, 0, true)
        val fail = LineResult(0.3, 5, 2, 0, false)
        assertEquals(Acuity.AtLeast(0.3), Acuity.of(result(listOf(pass, LineResult(0.3, 5, 4, 0, true)), Referral.PASS)))
        assertEquals(Acuity.Exact(0.4), Acuity.of(result(listOf(pass, fail), Referral.REFER)))
        assertEquals(Acuity.NoLinePassed, Acuity.of(result(listOf(LineResult(1.0, 5, 0, 0, false)), Referral.REFER)))
        assertEquals(Acuity.NotMeasured, Acuity.of(result(emptyList(), Referral.INCONCLUSIVE)))
        assertEquals("at least 6/12", Acuity.AtLeast(0.3).snellen())
        assertEquals("6/15", Acuity.Exact(0.4).snellen())
        assertEquals("no line passed", Acuity.NoLinePassed.snellen())
    }

    @Test
    fun `result reasons state the screening line and the line passed`() {
        val pass = drive(flow(), responder = perfect).result
        assertEquals(
            "Passed the screening line. Reads at least 6/12 (logMAR 0.3 or better); smaller lines were not tested.",
            pass.right.reason,
        )
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
        assertEquals("Pinhole: improved from 6/24 to at least 6/12 (at least 3 lines).", refer.right.pinholeText)

        // Right eye sees 0.4 and larger only: the pinhole passes 0.3, a 1-line gain.
        val small = drive(flow()) { id, t ->
            if (id == farR && t.logMAR < 0.35) t.orientation.wrong() else t.orientation.action()
        }.result
        assertEquals("Pinhole: not improved (6/15 without, at least 6/12 with the pinhole).", small.right.pinholeText)

        val none = drive(flow(), responder = failing(farR, pinR)).result
        assertEquals(
            "Did not pass the screening line 6/12 (logMAR 0.3). No line passed; the largest shown was 6/60 (logMAR 1.0).",
            none.right.reason,
        )
        assertEquals("Pinhole: not improved (no line passed with or without the pinhole).", none.right.pinholeText)
    }

    @Test
    fun `all unsure answers say no answers were entered`() {
        val d = drive(flow()) { _, _ -> RemoteAction.OK }.result
        listOf(d.right.reason, d.left.reason, d.near.reason).forEach {
            assertTrue(it, it.startsWith(ResultText.NO_ANSWERS))
        }
        assertEquals("No answers were entered for this test.", ResultText.NO_ANSWERS)
        assertEquals(Referral.REFER, d.right.decision) // decision unchanged (D45)
        val pinhole = d.right.pinholeText!!
        assertTrue(pinhole, pinhole.startsWith("Pinhole: " + ResultText.NO_ANSWERS))

        // Real answers, even all wrong, are not "no answers".
        val wrong = drive(flow(), responder = failing(farR)).result
        assertFalse(wrong.right.reason.contains(ResultText.NO_ANSWERS))
        assertFalse(drive(flow(), responder = perfect).result.right.reason.contains(ResultText.NO_ANSWERS))
    }

    @Test
    fun `no-answers rule covers all unsure and fewer than a line scored`() {
        fun q(scored: Int, unsure: Int) = EngineResult(
            TestKind.FAR, null, Referral.REFER, QualityFlags(false, scored, unsure, 1, 1, false, 0, 0), emptyList(),
        )
        assertTrue(ResultText.noAnswers(q(0, 0), 5))
        assertTrue(ResultText.noAnswers(q(4, 0), 5))
        assertTrue(ResultText.noAnswers(q(5, 5), 5))
        assertTrue(ResultText.noAnswers(q(10, 10), 5))
        assertFalse(ResultText.noAnswers(q(5, 4), 5))
        assertFalse(ResultText.noAnswers(q(5, 0), 5))
    }

    @Test
    fun `quality lines report responses, light, distance and crowding`() {
        val d = drive(flow(), lux = 20.0) { _, t ->
            if (t.phase == Phase.DESCENDING_LINES && t.logMAR > 0.95) RemoteAction.OK else t.orientation.action()
        }.result
        val q = d.right.quality.joinToString("\n")
        assertTrue(q, q.contains("Responses: NOT OK"))
        assertTrue(
            q,
            q.contains("Light: NOT OK. 20 lux at registration, 300 lux when the first eye started; the lower is below the minimum of 50."),
        )
        assertTrue(q, q.contains("Distance: confirmed by the operator"))
        assertTrue(q, q.contains("shown without crowding bars"))

        val ok = drive(flow(), lux = 120.0, responder = perfect).result.right.quality
        assertTrue(ok.joinToString("\n"), "Light: OK. 120 lux at registration, 300 lux when the first eye started (minimum 50)." in ok)

        startLux = null
        val missing = drive(flow(), lux = 120.0, responder = perfect).result.right.quality
        assertTrue(
            missing.joinToString("\n"),
            "Light: OK. 120 lux at registration, no reading when the first eye started (minimum 50)." in missing,
        )
        assertTrue(missing.none { it.startsWith("Answers:") })
    }

    // ---- remote drops mid-test (D50) ----------------------------------------------------

    /** First prompt, remote seen connected, then OK: the practice letter is up. */
    private fun toFirstLetter(f: ScreeningFlow = flow()): ScreeningFlow {
        toFirstPrompt(f)
        f.onRemoteConnection(true)
        f.onAction(RemoteAction.OK)
        check(f.screen is FlowScreen.Letter)
        return f
    }

    @Test
    fun `a drop with a letter up pauses and withdraws the letter unscored`() {
        val f = toFirstLetter()
        f.onAction((f.screen as FlowScreen.Letter).trial.orientation.action()) // practice
        val shown = f.screen as FlowScreen.Letter
        val before = f.trials.size

        f.onRemoteConnection(false)
        assertEquals(FlowScreen.RemoteLost(farR), f.screen)
        assertEquals(before, f.trials.size)

        f.onRemoteConnection(true)
        val again = f.screen as FlowScreen.Letter
        assertEquals(shown.trial.phase, again.trial.phase)
        assertEquals(shown.trial.lineIndex, again.trial.lineIndex)
        assertEquals(shown.trial.indexInLine, again.trial.indexInLine)
        assertEquals(before, f.trials.size)
    }

    @Test
    fun `presses while paused are never scored`() {
        val f = toFirstLetter()
        f.onRemoteConnection(false)
        RemoteAction.entries.forEach {
            assertEquals(InputResult.NotUsed(ScreeningFlow.REMOTE_LOST_PRESS), f.onAction(it, AnswerSource.REMOTE))
        }
        assertEquals(InputResult.NotUsed(ScreeningFlow.REMOTE_LOST_PRESS), f.onOtherPress())
        f.poll()
        assertEquals(FlowScreen.RemoteLost(farR), f.screen)
        assertTrue(f.trials.isEmpty())
    }

    @Test
    fun `a pad tap resumes but is not an answer`() {
        val f = toFirstLetter()
        f.onRemoteConnection(false)
        assertEquals(InputResult.NotUsed(ScreeningFlow.RESUMED_NOT_ANSWER), f.onAction(RemoteAction.LEFT, AnswerSource.PAD))
        assertTrue(f.screen is FlowScreen.Letter)
        assertTrue(f.trials.isEmpty())
        assertTrue(f.remoteAnswers)
        // The next pad tap is an answer as usual.
        assertEquals(InputResult.Used, f.onAction(RemoteAction.LEFT, AnswerSource.PAD))
        assertEquals(1, f.trials.size)
    }

    @Test
    fun `continue on screen resumes in on-screen answer mode`() {
        val f = toFirstLetter()
        f.onRemoteConnection(false)
        f.continueOnScreen()
        assertTrue(f.screen is FlowScreen.Letter)
        assertFalse(f.remoteAnswers)
        assertEquals(InputResult.NotUsed(ScreeningFlow.REMOTE_OFF), f.onAction(RemoteAction.UP))
        // A later reconnect and drop change nothing in on-screen mode.
        f.onRemoteConnection(true)
        f.onRemoteConnection(false)
        assertTrue(f.screen is FlowScreen.Letter)
    }

    @Test
    fun `continue on screen does nothing unless paused for the remote`() {
        val f = toFirstLetter()
        val s = f.screen
        f.continueOnScreen()
        assertEquals(s, f.screen)
        assertTrue(f.remoteAnswers)
    }

    @Test
    fun `only a change from connected pauses`() {
        // Never seen connected (the emulator, a remote that is not external): nothing pauses.
        val f = toFirstPrompt(flow())
        f.onRemoteConnection(false)
        f.onAction(RemoteAction.OK)
        assertTrue(f.screen is FlowScreen.Letter)
        f.onRemoteConnection(false)
        assertTrue(f.screen is FlowScreen.Letter)
        // Connected twice in a row is not a change either.
        f.onRemoteConnection(true)
        f.onRemoteConnection(true)
        assertTrue(f.screen is FlowScreen.Letter)
    }

    @Test
    fun `on-screen answer mode ignores drops`() {
        val f = flow()
        assertTrue(f.register("SCH-1", "", goodLux).isEmpty())
        f.onRemoteConnection(true)
        f.useOnScreenAnswers()
        f.onAction(RemoteAction.OK, AnswerSource.PAD)
        f.onRemoteConnection(false)
        assertTrue(f.screen is FlowScreen.Letter)
    }

    @Test
    fun `a drop on a prompt pauses before the first letter, unless the remote came back`() {
        val f = toFirstPrompt(flow())
        f.onRemoteConnection(true)
        f.onRemoteConnection(false)
        assertTrue(f.screen is FlowScreen.Prompt)
        f.onAction(RemoteAction.OK, AnswerSource.PAD)
        assertEquals(FlowScreen.RemoteLost(farR), f.screen)
        f.onRemoteConnection(true)
        assertTrue(f.screen is FlowScreen.Letter)

        val g = toFirstPrompt(flow())
        g.onRemoteConnection(true)
        g.onRemoteConnection(false)
        g.onRemoteConnection(true)
        g.onAction(RemoteAction.OK)
        assertTrue(g.screen is FlowScreen.Letter)
    }

    @Test
    fun `a drop while paused for distance waits for both`() {
        val d = SwitchableDistance(null)
        val f = toFirstPrompt(flow(distance = d))
        f.onRemoteConnection(true)
        f.onAction(RemoteAction.OK)
        assertEquals(FlowScreen.AdjustDistance(farR), f.screen)
        f.onRemoteConnection(false)
        assertEquals(FlowScreen.RemoteLost(farR), f.screen)
        f.onRemoteConnection(true)
        assertEquals(FlowScreen.AdjustDistance(farR), f.screen)
        d.mm = 3000.0
        f.poll()
        assertTrue(f.screen is FlowScreen.Letter)
    }

    @Test
    fun `drops are counted per test and shown on the result`() {
        val f = flow()
        var dropped = 0
        // Drop once on the third letter of each far test, reconnect straight away.
        val d = drive(f) { id, t ->
            if (id.kind == TestKind.FAR && t.phase == Phase.DESCENDING_LINES && t.indexInLine == 2 && dropped < 2 &&
                f.trials.count { it.test == id && it.phase == Phase.DESCENDING_LINES } == 2
            ) {
                f.onRemoteConnection(true)
                f.onRemoteConnection(false)
                dropped++
                assertEquals(FlowScreen.RemoteLost(id), f.screen)
                f.onRemoteConnection(true)
            }
            (f.screen as FlowScreen.Letter).trial.orientation.action()
        }.result
        assertEquals(2, dropped)
        assertEquals(Referral.PASS, d.right.decision)
        assertTrue(d.right.quality.joinToString("\n"), d.right.quality.any { it.startsWith("Remote: disconnected 1 time during this test") })
        assertTrue(d.left.quality.any { it.startsWith("Remote: disconnected 1 time") })
        assertTrue(d.near.quality.none { it.startsWith("Remote:") })
        // The withdrawn letters were never recorded.
        assertEquals(d.right.far.quality.scoredResponses, d.trials.count { it.test == farR && it.phase == Phase.DESCENDING_LINES })
    }

    @Test
    fun `the remote pause uses the test display`() {
        assertTrue(FlowScreen.RemoteLost(farR).usesTestDisplay)
    }
}
