package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SessionRecordTest {

    private val config = TestConfigs.shipped()
    private val farR = TestId(TestKind.FAR, Eye.RIGHT)

    private val stamp = RecordStamp(
        appVersion = "0.1.0 (1)",
        configSchemaVersion = 1,
        configSha256 = "ab".repeat(32),
        pxPerMm = 15.75,
        deviceId = "dev-1",
        remoteName = "Clicker",
        remoteMapping = "built-in defaults",
        decisionsRev = "bc63682",
    )

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

    /** Runs a whole screening: the right eye fails (so its pinhole runs), one remote drop on the right eye, a pad answer. */
    private fun screen(schoolId: String = "SCH-14", age: String = "9"): Pair<ScreeningFlow, ScreeningResult> {
        val f = ScreeningFlow(config, 400 / 25.4, FixedDistance.from(config), Random(3), { 0L }, { 300.0 }, 0)
        f.setViewport(2400, 1080)
        assertTrue(f.register(schoolId, age, 120.0, consent = true).isEmpty())
        f.onRemoteConnection(true)
        var dropped = false
        var padded = false
        repeat(100_000) {
            when (val s = f.screen) {
                is FlowScreen.RemoteCheck -> RemoteCheck.ORDER.forEach { f.onAction(it) }
                is FlowScreen.Prompt -> f.onAction(RemoteAction.OK)
                is FlowScreen.Letter -> {
                    if (!dropped && s.test == farR && s.trial.phase == Phase.DESCENDING_LINES) {
                        dropped = true
                        f.onRemoteConnection(false)
                        f.onRemoteConnection(true)
                        return@repeat
                    }
                    val t = s.trial
                    val failing = s.test == farR && t.phase == Phase.DESCENDING_LINES
                    val source = if (!padded) AnswerSource.PAD.also { padded = true } else AnswerSource.REMOTE
                    f.onAction(if (failing) t.orientation.wrong() else t.orientation.action(), source)
                }
                is FlowScreen.Result -> return f to s.result
                else -> error("unexpected $s")
            }
        }
        error("did not finish")
    }

    // ---- token and school ID (D56, D57) --------------------------------------------------

    @Test
    fun `tokens are six characters from an unambiguous alphabet, shown as two groups`() {
        val r = Random(1)
        repeat(500) {
            val t = SessionToken.generate(r)
            assertTrue(t, Regex("^[A-Z2-9]{3}-[A-Z2-9]{3}$").matches(t))
            assertTrue(t, t.none { it in "01OILU" })
        }
        assertEquals(30, SessionToken.ALPHABET.length)
        assertEquals(SessionToken.generate(Random(7)), SessionToken.generate(Random(7)))
        assertNotEquals(SessionToken.generate(Random(7)), SessionToken.generate(Random(8)))
    }

    @Test
    fun `the flow makes a token before registration and keeps it`() {
        val f = ScreeningFlow(config, 15.0, FixedDistance.from(config), Random(1), { 0L }, { null }, 0)
        val token = f.token
        f.setViewport(2400, 1080)
        assertTrue(f.register("", "", 100.0, consent = true).isEmpty())
        assertEquals(token, f.patient?.token)
    }

    @Test
    fun `school ID is optional, a code with a digit, never a plain word`() {
        fun errors(id: String) = Registration.validate("ABC-234", id, "", consent = true).second
        assertTrue(errors("").isEmpty())
        assertTrue(errors("   ").isEmpty())
        assertTrue(errors("SCH-14").isEmpty())
        assertTrue(errors("Village 7/B").isEmpty())
        assertTrue(errors("गाँव 12").isEmpty())
        assertTrue(errors("Ravi").isNotEmpty())
        assertTrue(errors("Ravi Kumar").any { it.contains("not a name") })
        assertTrue(errors("A".repeat(32) + "1").isNotEmpty())
        assertTrue(errors("=1+1").isNotEmpty())
        assertTrue(errors("sch,14").isNotEmpty())
        val (p, _) = Registration.validate("ABC-234", "  SCH-14 ", "", consent = true)
        assertEquals("SCH-14", p?.schoolId)
        assertNull(Registration.validate("ABC-234", " ", "", consent = true).first?.schoolId)
    }

    @Test
    fun `no consent tick, no patient (D65)`() {
        val (p, errors) = Registration.validate("ABC-234", "SCH-14", "9", consent = false)
        assertNull(p)
        assertEquals(listOf(Registration.CONSENT_MISSING), errors)
        // Every problem is listed at once, consent first.
        val all = Registration.validate("ABC-234", "Ravi", "abc", consent = false).second
        assertEquals(Registration.CONSENT_MISSING, all.first())
        assertEquals(3, all.size)
    }

    // ---- the record (D53, D54) -----------------------------------------------------------

    @Test
    fun `a session record holds every outcome, quality flag and stamp`() {
        val (f, result) = screen()
        val saved = SessionRecords.build("sid-1", 1_700_000_000_000, "2026-10-20T10:00:00+05:30", result, stamp)
        val r = saved.record

        assertEquals("sid-1", r.sessionId)
        assertEquals(f.token, r.token)
        assertEquals("SCH-14", r.schoolId)
        assertEquals(9, r.age)
        assertEquals(true, r.consent)
        assertEquals(result.overall, r.overall)
        assertEquals(result.overallText, r.overallText)

        assertEquals(Referral.REFER, r.rightReferral)
        assertEquals(result.right.reason, r.rightReason)
        assertEquals(result.right.far.lastPassedLogMAR, r.rightLogMAR)
        assertEquals(Acuity.of(result.right.far).snellen(), r.rightAcuity)
        assertEquals(1, r.rightRemoteDrops)
        assertEquals(result.right.quality.joinToString(" | "), r.rightQuality)
        assertTrue(r.rightPinholeRun)
        assertEquals(result.right.pinhole?.decision, r.rightPinholeReferral)
        assertEquals(result.right.pinholeImproved, r.rightPinholeImproved)
        assertEquals(result.right.pinholeText, r.rightPinholeText)

        assertEquals(Referral.PASS, r.leftReferral)
        assertFalse(r.leftPinholeRun)
        assertNull(r.leftPinholeReferral)
        assertEquals(0, r.leftRemoteDrops)
        assertEquals(result.near.decision, r.nearReferral)

        assertEquals(result.light.atRegister, r.luxAtRegister)
        assertEquals(result.light.atTestStart, r.luxAtTestStart)
        assertEquals(result.lightOk, r.lightOk)
        assertTrue(r.distanceOk)
        assertTrue(r.remoteAnswers)

        assertEquals(stamp.appVersion, r.appVersion)
        assertEquals(stamp.configSha256, r.configSha256)
        assertEquals(stamp.pxPerMm, r.pxPerMm, 0.0)
        assertEquals(stamp.deviceId, r.deviceId)
        assertEquals(stamp.remoteName, r.remoteName)
        assertEquals(stamp.remoteMapping, r.remoteMapping)
        assertEquals(stamp.decisionsRev, r.decisionsRev)
        assertFalse(r.synced)
    }

    @Test
    fun `one trial row per answered letter, with its drawn size and input source`() {
        val (f, result) = screen()
        val saved = SessionRecords.build("sid-1", 0, "t", result, stamp)
        assertEquals(f.trials.size, saved.trials.size)
        assertEquals((0 until f.trials.size).toList(), saved.trials.map { it.seq })
        assertTrue(saved.trials.all { it.sessionId == "sid-1" })
        f.trials.zip(saved.trials).forEach { (t, row) ->
            assertEquals(t.test.kind, row.test)
            assertEquals(t.test.eye, row.eye)
            assertEquals(t.phase, row.phase)
            assertEquals(t.orientation, row.orientation)
            assertEquals(t.response, row.response)
            assertEquals(t.response == Dir.UNSURE, row.unsure)
            assertEquals(t.correct, row.correct)
            assertEquals(t.intendedMm, row.intendedMm, 0.0)
            assertEquals(t.drawnMm, row.drawnMm, 0.0)
            assertEquals(t.strokePx, row.strokePx)
            assertEquals(t.sizeErrorFraction, row.sizeError, 0.0)
            assertEquals(t.crowded, row.crowded)
            assertEquals(t.source, row.source)
        }
        assertEquals(AnswerSource.PAD, saved.trials.first().source)
    }

    @Test
    fun `responses_ok is false if any far or near test was not OK`() {
        val (_, result) = screen()
        val r = SessionRecords.build("s", 0, "t", result, stamp).record
        val expected = result.right.far.quality.responsesOk && result.left.far.quality.responsesOk &&
            result.near.result.quality.responsesOk
        assertEquals(expected, r.responsesOk)
        assertEquals(result.right.far.quality.scoredResponses, r.rightScored)
        assertEquals(result.right.far.quality.unsureResponses, r.rightUnsure)
    }

    @Test
    fun `remote mapping is described as stored, or built-in defaults`() {
        val label: (InputId) -> String = { if (it is InputId.Key) "KEY_${it.keyCode}" else "AXIS" }
        assertEquals(RemoteMappingText.BUILT_IN, RemoteMappingText.describe(null, label))
        assertEquals(RemoteMappingText.BUILT_IN, RemoteMappingText.describe(RemoteProfile(RemoteKey("d", "n")), label))
        val p = RemoteProfile(RemoteKey("d", "n")).assign(InputId.Key(97), RemoteAction.LEFT).assign(InputId.Axis(15, true), RemoteAction.UP)
        assertEquals("KEY_97=LEFT; AXIS=UP", RemoteMappingText.describe(p, label))
    }

    @Test
    fun `config hash is the SHA-256 of the exact bytes`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ConfigHash.sha256(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ConfigHash.sha256("abc".toByteArray()),
        )
    }
}
